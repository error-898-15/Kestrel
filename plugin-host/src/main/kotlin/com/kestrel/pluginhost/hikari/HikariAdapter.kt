// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.pluginhost.hikari

import android.util.Base64
import com.hikari.ext.HikariCatalog
import com.hikari.ext.HikariEpisode
import com.hikari.ext.HikariMedia
import com.hikari.ext.HikariMediaType
import com.hikari.ext.HikariProvider
import com.hikari.ext.HikariStream
import com.kestrel.domain.model.CatalogRow
import com.kestrel.domain.model.Episode
import com.kestrel.domain.model.MediaDetails
import com.kestrel.domain.model.MediaSummary
import com.kestrel.domain.model.MediaType
import com.kestrel.domain.model.PluginMetadata
import com.kestrel.domain.model.RepoType
import com.kestrel.domain.model.ResolvedLinks
import com.kestrel.domain.model.SeasonInfo
import com.kestrel.domain.model.StreamFormat
import com.kestrel.domain.model.StreamSource
import com.kestrel.domain.model.SubtitleTrack
import com.kestrel.domain.provider.SourceProvider
import com.kestrel.pluginhost.PluginSandbox
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Collections

/**
 * Adapts a DEX-loaded [HikariProvider] into Kestrel's [SourceProvider].
 */
class HikariAdapter(
    private val plugin: PluginMetadata,
    override val providerId: String,
    private val provider: HikariProvider,
    private val sandbox: PluginSandbox
) : SourceProvider {

    override val pluginId: String = plugin.id
    override val name: String = provider.name.ifBlank { plugin.displayName }
    override val language: String = plugin.language.ifBlank { "en" }
    override val repoType: RepoType = RepoType.HIKARI

    override val supportedTypes: Set<MediaType> = provider.tvTypes
        .map { mapMediaType(it) }
        .toSet()
        .ifEmpty { setOf(MediaType.MOVIE, MediaType.TV_SERIES) }

    override val hasCatalog: Boolean
        get() = runCatching { provider.catalogs().isNotEmpty() }.getOrDefault(false)

    override suspend fun getCatalog(page: Int): Result<List<CatalogRow>> =
        sandbox.execute(pluginId = pluginId, timeoutMs = 40_000L) {
            val catalogs = provider.catalogs()
            if (catalogs.isEmpty()) return@execute emptyList()
            val safePage = page.coerceAtLeast(1)
            val errors = Collections.synchronizedList(mutableListOf<Throwable>())
            val semaphore = Semaphore(4)

            val rows = coroutineScope {
                catalogs.map { cat ->
                    async {
                        semaphore.withPermit {
                            val items = withTimeoutOrNull(14_000L) {
                                try {
                                    provider.getCatalog(cat, safePage)
                                } catch (ce: CancellationException) {
                                    throw ce
                                } catch (t: Throwable) {
                                    errors.add(t)
                                    emptyList()
                                }
                            }.orEmpty()

                            if (items.isEmpty()) null else {
                                CatalogRow(
                                    id = stableId("$providerId:${cat.id}:$safePage"),
                                    providerId = providerId,
                                    providerName = name,
                                    title = cat.name.ifBlank { name },
                                    items = items.map(::mapMediaSummary),
                                    hasNextPage = items.size >= 8
                                )
                            }
                        }
                    }
                }.awaitAll().filterNotNull()
            }

            if (rows.isEmpty() && errors.isNotEmpty()) {
                throw errors.first()
            }
            rows
        }

    override suspend fun search(query: String, page: Int): Result<List<MediaSummary>> =
        sandbox.execute(pluginId = pluginId, timeoutMs = 28_000L) {
            val trimmed = query.trim()
            if (trimmed.isBlank()) return@execute emptyList()
            provider.search(trimmed, page.coerceAtLeast(1)).map(::mapMediaSummary)
        }

    override suspend fun load(url: String): Result<MediaDetails> =
        sandbox.execute(pluginId = pluginId, timeoutMs = 30_000L) {
            val seedMedia = decodeMediaToken(url)
            val meta = runCatching { provider.getMeta(seedMedia) }.getOrElse { seedMedia }
            val episodes = runCatching { provider.getEpisodes(meta) }.getOrNull().orEmpty()

            val mappedEpisodes = episodes.mapIndexed { idx, ep ->
                Episode(
                    id = stableId("$providerId:${meta.id}:${ep.id}:${ep.season}:${ep.number}:$idx"),
                    name = ep.name?.takeIf { it.isNotBlank() } ?: "Episode ${ep.number}",
                    season = ep.season,
                    episodeNumber = ep.number,
                    dataUrl = encodeEpisodeToken(meta, ep),
                    posterUrl = resolveUrl(ep.image)
                )
            }

            val seasons = mappedEpisodes.mapNotNull { it.season }
                .distinct()
                .sorted()
                .map { s -> SeasonInfo(season = s, name = "Season $s") }

            val poster = resolveUrl(meta.posterUrl)
            val backdrop = resolveUrl(meta.backdropUrl) ?: poster
            val playData = if (mappedEpisodes.isEmpty()) encodeMediaToken(meta) else null

            MediaDetails(
                id = stableId("$providerId:${meta.id}"),
                providerId = providerId,
                providerName = name,
                title = meta.title.ifBlank { seedMedia.title },
                url = encodeMediaToken(meta),
                type = if (mappedEpisodes.size > 1) MediaType.TV_SERIES else mapMediaType(meta.type),
                plot = meta.overview?.takeIf { it.isNotBlank() },
                posterUrl = poster,
                backdropUrl = backdrop,
                year = meta.year,
                tags = meta.genres.filter { it.isNotBlank() },
                seasons = seasons,
                episodes = mappedEpisodes,
                playDataUrl = playData
            )
        }

    override suspend fun getLinks(data: String): Result<ResolvedLinks> =
        getLinks(data = data, timeoutMs = 45_000L)

    override suspend fun getLinks(data: String, timeoutMs: Long): Result<ResolvedLinks> =
        sandbox.execute(pluginId = pluginId, timeoutMs = timeoutMs.coerceAtLeast(8_000L)) {
            val (media, episode) = decodeEpisodeOrMediaToken(data)
            val rawStreams = provider.getStreams(media, episode)
            val streams = mutableListOf<StreamSource>()
            val allSubtitles = mutableListOf<SubtitleTrack>()

            rawStreams.forEachIndexed { idx, s ->
                val subs = s.subtitles
                    .filter { it.url.isNotBlank() }
                    .map { sub ->
                        SubtitleTrack(
                            url = resolveUrl(sub.url) ?: sub.url,
                            language = sub.lang.ifBlank { "en" },
                            label = sub.lang.ifBlank { "Subtitles" }
                        )
                    }
                allSubtitles.addAll(subs)

                val streamUrl = resolveUrl(s.url)
                if (!streamUrl.isNullOrBlank() && !s.isTorrent && !s.externalUrl) {
                    val format = when {
                        s.isM3u8 -> StreamFormat.HLS
                        s.isMpd -> StreamFormat.DASH
                        else -> inferFormatFromUrl(streamUrl)
                    }
                    val qualityScore = parseQualityScore(s.name, streamUrl)
                    val qualityLabel = parseQualityLabel(s.name, qualityScore)
                    streams.add(
                        StreamSource(
                            id = stableId("$providerId:$streamUrl:${s.name}:$idx"),
                            providerId = providerId,
                            providerName = name,
                            extractorName = s.name.ifBlank { name },
                            url = streamUrl,
                            qualityLabel = qualityLabel,
                            qualityScore = qualityScore,
                            format = format,
                            headers = s.headers,
                            referer = s.headers.entries
                                .firstOrNull { it.key.equals("referer", ignoreCase = true) }
                                ?.value,
                            subtitles = subs
                        )
                    )
                }
            }

            ResolvedLinks(
                streams = streams.distinctBy { "${it.url}|${it.extractorName}" }
                    .sortedByDescending { it.qualityScore },
                subtitles = allSubtitles.distinctBy { "${it.url}|${it.language}" }
            )
        }

    private fun mapMediaSummary(media: HikariMedia): MediaSummary = MediaSummary(
        id = stableId("$providerId:${media.id}"),
        providerId = providerId,
        providerName = name,
        title = media.title,
        url = encodeMediaToken(media),
        type = mapMediaType(media.type),
        posterUrl = resolveUrl(media.posterUrl),
        year = media.year
    )

    private fun mapMediaType(type: HikariMediaType): MediaType = when (type) {
        HikariMediaType.MOVIE -> MediaType.MOVIE
        HikariMediaType.SERIES -> MediaType.TV_SERIES
        HikariMediaType.UNKNOWN -> MediaType.OTHER
    }

    private fun resolveUrl(raw: String?): String? {
        val trimmed = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val base = provider.mainUrl.trimEnd('/')
        return when {
            trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true) ||
                trimmed.startsWith("data:", ignoreCase = true) -> trimmed
            trimmed.startsWith("//") -> "https:$trimmed"
            trimmed.startsWith("/") && base.isNotBlank() -> "$base$trimmed"
            else -> trimmed
        }
    }

    private fun encodeMediaToken(media: HikariMedia): String {
        val json = mediaToJson(media).toString()
        val b64 = Base64.encodeToString(json.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP)
        return "$MEDIA_PREFIX$b64"
    }

    private fun encodeEpisodeToken(media: HikariMedia, ep: HikariEpisode): String {
        val root = JSONObject()
            .put("media", mediaToJson(media))
            .put("episode", episodeToJson(ep))
            .toString()
        val b64 = Base64.encodeToString(root.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP)
        return "$EPISODE_PREFIX$b64"
    }

    private fun decodeMediaToken(token: String): HikariMedia {
        if (!token.startsWith(MEDIA_PREFIX)) {
            return HikariMedia(id = token, title = token, type = HikariMediaType.MOVIE)
        }
        val rawJson = String(
            Base64.decode(token.removePrefix(MEDIA_PREFIX), Base64.URL_SAFE or Base64.NO_WRAP),
            Charsets.UTF_8
        )
        return jsonToMedia(JSONObject(rawJson))
    }

    private fun decodeEpisodeOrMediaToken(token: String): Pair<HikariMedia, HikariEpisode?> {
        return when {
            token.startsWith(EPISODE_PREFIX) -> {
                val rawJson = String(
                    Base64.decode(token.removePrefix(EPISODE_PREFIX), Base64.URL_SAFE or Base64.NO_WRAP),
                    Charsets.UTF_8
                )
                val obj = JSONObject(rawJson)
                val media = jsonToMedia(obj.getJSONObject("media"))
                val ep = jsonToEpisode(obj.getJSONObject("episode"))
                media to ep
            }
            token.startsWith(MEDIA_PREFIX) -> decodeMediaToken(token) to null
            else -> HikariMedia(id = token, title = token, type = HikariMediaType.MOVIE) to null
        }
    }

    private fun mediaToJson(media: HikariMedia): JSONObject = JSONObject()
        .put("id", media.id)
        .put("title", media.title)
        .put("type", media.type.name)
        .put("posterUrl", media.posterUrl)
        .put("year", media.year)
        .put("overview", media.overview)
        .put("genres", JSONArray(media.genres))
        .put("backdropUrl", media.backdropUrl)
        .put("rawType", media.rawType)

    private fun jsonToMedia(obj: JSONObject): HikariMedia {
        val genres = buildList {
            val arr = obj.optJSONArray("genres")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val g = arr.optString(i)
                    if (g.isNotBlank()) add(g)
                }
            }
        }
        val type = runCatching {
            HikariMediaType.valueOf(obj.optString("type", "MOVIE"))
        }.getOrDefault(HikariMediaType.MOVIE)

        return HikariMedia(
            id = obj.optString("id"),
            title = obj.optString("title"),
            type = type,
            posterUrl = optNullableString(obj, "posterUrl"),
            year = if (obj.has("year") && !obj.isNull("year")) obj.optInt("year") else null,
            overview = optNullableString(obj, "overview"),
            genres = genres,
            backdropUrl = optNullableString(obj, "backdropUrl"),
            rawType = obj.optString("rawType")
        )
    }

    private fun episodeToJson(ep: HikariEpisode): JSONObject = JSONObject()
        .put("number", ep.number)
        .put("id", ep.id)
        .put("name", ep.name)
        .put("image", ep.image)
        .put("season", ep.season)

    private fun jsonToEpisode(obj: JSONObject): HikariEpisode = HikariEpisode(
        number = obj.optInt("number", 1),
        id = obj.optString("id"),
        name = optNullableString(obj, "name"),
        image = optNullableString(obj, "image"),
        season = obj.optInt("season", 1)
    )

    private fun optNullableString(obj: JSONObject, key: String): String? =
        if (!obj.has(key) || obj.isNull(key)) null else obj.optString(key).takeIf { it.isNotBlank() }

    private fun inferFormatFromUrl(url: String): StreamFormat {
        val lower = url.lowercase()
        return when {
            ".m3u8" in lower || "/hls/" in lower || "m3u8" in lower -> StreamFormat.HLS
            ".mpd" in lower || "/dash/" in lower -> StreamFormat.DASH
            ".mp4" in lower || ".mkv" in lower || ".webm" in lower -> StreamFormat.MP4
            else -> StreamFormat.OTHER
        }
    }

    private fun parseQualityScore(label: String, url: String): Int {
        val text = "$label $url".lowercase()
        return when {
            "2160" in text || "4k" in text -> 2160
            "1440" in text || "2k" in text -> 1440
            "1080" in text -> 1080
            "720" in text -> 720
            "480" in text -> 480
            "360" in text -> 360
            else -> 0
        }
    }

    private fun parseQualityLabel(name: String, score: Int): String =
        if (score > 0) "${score}p" else name.takeIf { it.isNotBlank() } ?: "Auto"

    private fun stableId(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val MEDIA_PREFIX = "hikari://media/"
        private const val EPISODE_PREFIX = "hikari://ep/"
    }
}
