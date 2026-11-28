// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.pluginhost.aniyomi

import com.kestrel.domain.model.CatalogRow
import com.kestrel.domain.model.Episode
import com.kestrel.domain.model.MediaDetails
import com.kestrel.domain.model.MediaSummary
import com.kestrel.domain.model.MediaType
import com.kestrel.domain.model.RepoType
import com.kestrel.domain.model.ResolvedLinks
import com.kestrel.domain.model.StreamFormat
import com.kestrel.domain.model.StreamSource
import com.kestrel.domain.model.SubtitleTrack
import com.kestrel.domain.provider.SourceProvider
import com.kestrel.pluginhost.PluginSandbox
import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.security.MessageDigest

/**
 * Bridges Aniyomi [AnimeSource] extensions (e.g., AniLab, AniLight, AniKuro, Jellyfin, GDrive)
 * into Kestrel's unified video [SourceProvider] contract so they install cleanly and stream
 * inside Home, Search, Details, and Player.
 */
class AniyomiAnimeSourceAdapter(
    override val pluginId: String,
    private val source: AnimeSource,
    private val fallbackName: String,
    private val fallbackLang: String,
    private val sandbox: PluginSandbox
) : SourceProvider {

    override val providerId: String = "aniyomi_${source.id}"
    override val name: String = source.name.ifBlank { fallbackName }
    override val language: String = (source as? AnimeCatalogueSource)?.lang?.ifBlank { fallbackLang }
        ?: source.lang.ifBlank { fallbackLang }
    override val repoType: RepoType = RepoType.HIKARI
    override val supportedTypes: Set<MediaType> = setOf(MediaType.ANIME, MediaType.TV_SERIES, MediaType.MOVIE)
    override val hasCatalog: Boolean = true

    private val baseUrl: String = (source as? AnimeHttpSource)?.baseUrl.orEmpty()

    private val defaultHeaders: Map<String, String> by lazy {
        val http = source as? AnimeHttpSource ?: return@lazy emptyMap()
        buildMap {
            val h = http.headers
            for (i in 0 until h.size) {
                put(h.name(i), h.value(i))
            }
            if (baseUrl.isNotBlank() && !containsKey("Referer")) {
                put("Referer", "$baseUrl/")
            }
        }
    }

    override suspend fun getCatalog(page: Int): Result<List<CatalogRow>> =
        sandbox.execute(pluginId = pluginId, timeoutMs = 35_000L) {
            coroutineScope {
                val safePage = page.coerceAtLeast(1)
                val popDeferred = async { runCatching { source.getPopularAnime(safePage) } }
                val latDeferred = if (source.supportsLatest) {
                    async { runCatching { source.getLatestUpdates(safePage) } }
                } else null

                val popRes = popDeferred.await()
                val latRes = latDeferred?.await()

                val rows = buildList {
                    popRes.getOrNull()?.let { mp ->
                        if (mp.animes.isNotEmpty()) {
                            add(
                                CatalogRow(
                                    id = stableId("$providerId:popular:$safePage"),
                                    providerId = providerId,
                                    providerName = name,
                                    title = "$name • Popular",
                                    items = mp.animes.map(::mapAnimeSummary),
                                    hasNextPage = mp.hasNextPage
                                )
                            )
                        }
                    }
                    latRes?.getOrNull()?.let { mp ->
                        if (mp.animes.isNotEmpty()) {
                            add(
                                CatalogRow(
                                    id = stableId("$providerId:latest:$safePage"),
                                    providerId = providerId,
                                    providerName = name,
                                    title = "$name • Latest",
                                    items = mp.animes.map(::mapAnimeSummary),
                                    hasNextPage = mp.hasNextPage
                                )
                            )
                        }
                    }
                }
                if (rows.isEmpty()) {
                    popRes.exceptionOrNull()?.let { throw it }
                }
                rows
            }
        }

    override suspend fun search(query: String, page: Int): Result<List<MediaSummary>> =
        sandbox.execute(pluginId = pluginId, timeoutMs = 28_000L) {
            val trimmed = query.trim()
            if (trimmed.isBlank()) return@execute emptyList()
            val filters = runCatching { source.getFilterList() }.getOrElse { AnimeFilterList() }
            val result: AnimesPage = source.getSearchAnime(page.coerceAtLeast(1), trimmed, filters)
            result.animes.map(::mapAnimeSummary)
        }

    override suspend fun load(url: String): Result<MediaDetails> =
        sandbox.execute(pluginId = pluginId, timeoutMs = 35_000L) {
            val seed = SAnime.create().apply {
                this.url = url
                this.title = ""
            }
            val details = runCatching { source.getAnimeDetails(seed) }.getOrElse { seed }
            val episodes = runCatching { source.getEpisodeList(details) }.getOrDefault(emptyList())
            val mappedEpisodes = episodes.mapIndexed { idx, ep ->
                val epNum = if (ep.episode_number >= 0f) ep.episode_number.toInt() else (idx + 1)
                Episode(
                    id = stableId("$providerId:${ep.url}:$idx"),
                    name = ep.name.ifBlank { "Episode $epNum" },
                    season = 1,
                    episodeNumber = epNum,
                    dataUrl = ep.url,
                    airDateEpochMs = ep.date_upload.takeIf { it > 0L }
                )
            }
            MediaDetails(
                id = stableId("$providerId:$url"),
                providerId = providerId,
                providerName = name,
                title = details.title.ifBlank { "Untitled" },
                url = details.url.ifBlank { url },
                type = MediaType.ANIME,
                plot = details.description,
                posterUrl = resolveAssetUrl(details.thumbnail_url),
                posterHeaders = defaultHeaders,
                director = details.author ?: details.artist,
                showStatus = mapAnimeStatus(details.status),
                tags = details.genre
                    ?.split(',')
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    .orEmpty(),
                episodes = mappedEpisodes,
                playDataUrl = mappedEpisodes.firstOrNull()?.dataUrl
            )
        }

    override suspend fun getLinks(data: String): Result<ResolvedLinks> =
        sandbox.execute(pluginId = pluginId, timeoutMs = 35_000L) {
            val episode = SEpisode.create().apply {
                this.url = data
                this.name = "Episode"
            }
            val videos: List<Video> = source.getVideoList(episode)
            val http = source as? AnimeHttpSource
            val allSubtitles = LinkedHashMap<String, SubtitleTrack>()
            val streams = videos.mapIndexedNotNull { idx, video ->
                val rawUrl = (video.videoUrl?.takeIf { it.isNotBlank() }
                    ?: video.url.takeIf { it.isNotBlank() }
                    ?: if (http != null) runCatching { http.getVideoUrl(video) }.getOrNull() else null)
                    ?.takeIf { it.isNotBlank() }
                    ?: return@mapIndexedNotNull null

                val hdrs = buildMap {
                    putAll(defaultHeaders)
                    video.headers?.let { h ->
                        for (i in 0 until h.size) put(h.name(i), h.value(i))
                    }
                }
                val subs = video.subtitleTracks.map { tr ->
                    val sub = SubtitleTrack(
                        url = tr.url,
                        language = tr.lang.ifBlank { "und" },
                        label = tr.lang.ifBlank { null },
                        headers = hdrs
                    )
                    allSubtitles.putIfAbsent(tr.url, sub)
                    sub
                }
                StreamSource(
                    id = stableId("$providerId:$rawUrl:$idx"),
                    providerId = providerId,
                    providerName = name,
                    extractorName = video.quality.ifBlank { name },
                    url = rawUrl,
                    qualityLabel = video.quality.ifBlank { "Auto" },
                    qualityScore = parseQualityScore(video.quality),
                    format = inferFormat(rawUrl),
                    headers = hdrs,
                    referer = hdrs["Referer"],
                    subtitles = subs
                )
            }
            ResolvedLinks(
                streams = streams,
                subtitles = allSubtitles.values.toList()
            )
        }

    private fun mapAnimeSummary(anime: SAnime): MediaSummary = MediaSummary(
        id = stableId("$providerId:${anime.url}"),
        providerId = providerId,
        providerName = name,
        title = anime.title.ifBlank { "Untitled" },
        url = anime.url,
        type = MediaType.ANIME,
        posterUrl = resolveAssetUrl(anime.thumbnail_url),
        posterHeaders = defaultHeaders
    )

    private fun resolveAssetUrl(raw: String?): String? {
        val cleaned = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return when {
            cleaned.startsWith("http://", ignoreCase = true) ||
                cleaned.startsWith("https://", ignoreCase = true) -> cleaned
            cleaned.startsWith("//") -> "https:$cleaned"
            cleaned.startsWith("/") && baseUrl.isNotBlank() -> "${baseUrl.trimEnd('/')}$cleaned"
            else -> cleaned
        }
    }

    private fun mapAnimeStatus(status: Int): String? = when (status) {
        SAnime.ONGOING -> "Ongoing"
        SAnime.COMPLETED -> "Completed"
        SAnime.LICENSED -> "Licensed"
        SAnime.PUBLISHING_FINISHED -> "Publishing Finished"
        SAnime.CANCELLED -> "Cancelled"
        SAnime.ON_HIATUS -> "On Hiatus"
        else -> null
    }

    private fun inferFormat(url: String): StreamFormat = when {
        ".m3u8" in url.lowercase() -> StreamFormat.HLS
        ".mpd" in url.lowercase() -> StreamFormat.DASH
        ".mp4" in url.lowercase() || ".mkv" in url.lowercase() -> StreamFormat.MP4
        else -> StreamFormat.OTHER
    }

    private fun parseQualityScore(quality: String): Int {
        val q = quality.lowercase()
        return when {
            "2160" in q || "4k" in q -> 2160
            "1080" in q -> 1080
            "720" in q -> 720
            "480" in q -> 480
            "360" in q -> 360
            else -> 0
        }
    }

    private fun stableId(seed: String): String =
        MessageDigest.getInstance("SHA-256").digest(seed.toByteArray())
            .take(12).joinToString("") { "%02x".format(it) }
}
