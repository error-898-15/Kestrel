// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.pluginhost.nuvio

import com.kestrel.domain.model.ActorCredit
import com.kestrel.domain.model.CatalogRow
import com.kestrel.domain.model.Episode
import com.kestrel.domain.model.MediaDetails
import com.kestrel.domain.model.MediaSummary
import com.kestrel.domain.model.MediaType
import com.kestrel.domain.model.PluginMetadata
import com.kestrel.domain.model.RepoType
import com.kestrel.domain.model.ResolvedLinks
import com.kestrel.domain.model.StreamFormat
import com.kestrel.domain.model.StreamSource
import com.kestrel.domain.model.SubtitleTrack
import com.kestrel.domain.provider.SourceProvider
import com.kestrel.pluginhost.PluginSandbox
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Adapts a user-enabled Nuvio JS scraper into Kestrel's [SourceProvider].
 * Catalog, search, and metadata are powered by [CinemetaCatalogClient] so downloaded
 * Nuvio scrapers immediately appear on Home and resolve TMDB tokens for playback.
 */
class NuvioAdapter(
    private val plugin: PluginMetadata,
    private val engine: NuvioJsEngine,
    private val catalogClient: CinemetaCatalogClient,
    private val sandbox: PluginSandbox
) : SourceProvider {

    override val pluginId: String = plugin.id
    override val providerId: String = "${plugin.id}:${plugin.internalName}"
    override val name: String = plugin.displayName
    override val language: String = plugin.language
    override val repoType: RepoType = RepoType.NUVIO

    override val supportedTypes: Set<MediaType> = plugin.tvTypes.map {
        when (it.lowercase()) {
            "movie" -> MediaType.MOVIE
            "series", "tv", "tvseries" -> MediaType.TV_SERIES
            "anime" -> MediaType.ANIME
            else -> MediaType.OTHER
        }
    }.toSet().ifEmpty { setOf(MediaType.MOVIE, MediaType.TV_SERIES) }

    override val hasCatalog: Boolean = true

    override suspend fun getCatalog(page: Int): Result<List<CatalogRow>> =
        sandbox.execute(pluginId) {
            if (page > 1) return@execute emptyList()
            catalogClient.fetchHomeCatalogs().map { section ->
                CatalogRow(
                    id = stableId("$providerId:${section.id}"),
                    providerId = providerId,
                    providerName = name,
                    title = section.title,
                    items = section.items.map { mapCatalogItem(it) },
                    hasNextPage = false
                )
            }
        }

    override suspend fun search(query: String, page: Int): Result<List<MediaSummary>> =
        sandbox.execute(pluginId) {
            if (page > 1 || query.isBlank()) return@execute emptyList()
            catalogClient.search(query).map { mapCatalogItem(it) }
        }

    override suspend fun load(url: String): Result<MediaDetails> =
        sandbox.execute(pluginId) {
            if (url.startsWith("cinemeta:")) {
                val parts = url.removePrefix("cinemeta:").split(":")
                val type = parts.getOrNull(0).orEmpty().ifBlank { "movie" }
                val id = parts.getOrNull(1).orEmpty().ifBlank { url }
                val hintTmdb = parts.getOrNull(2)?.takeIf { it.isNotBlank() }

                val meta = catalogClient.fetchMeta(type, id)
                    ?: error("$name returned no details for $id")
                val tmdbOrId = meta.moviedbId ?: hintTmdb ?: meta.id
                val mediaKind = if (meta.type.equals("movie", ignoreCase = true)) "movie" else "tv"

                val episodes = meta.videos
                    .sortedWith(compareBy<CinemetaVideoItem>({ it.season ?: 0 }, { it.episode ?: 0 }))
                    .mapIndexed { idx, v ->
                        val s = v.season ?: 1
                        val e = v.episode ?: (idx + 1)
                        Episode(
                            id = stableId("$providerId:${v.id}:$s:$e"),
                            name = v.title ?: "Episode $e",
                            season = s,
                            episodeNumber = e,
                            dataUrl = "tmdb:$tmdbOrId:$mediaKind:$s:$e",
                            posterUrl = v.thumbnail,
                            description = v.overview
                        )
                    }

                MediaDetails(
                    id = stableId("$providerId:${meta.id}"),
                    providerId = providerId,
                    providerName = name,
                    title = meta.name,
                    url = url,
                    type = mapCinemetaType(meta.type),
                    plot = meta.description,
                    posterUrl = meta.poster,
                    backdropUrl = meta.background ?: meta.poster,
                    logoUrl = meta.logo,
                    year = meta.releaseInfo?.take(4)?.toIntOrNull(),
                    durationMinutes = meta.runtime?.filter(Char::isDigit)?.toIntOrNull(),
                    rating = meta.imdbRating?.toDoubleOrNull(),
                    tags = meta.genres,
                    actors = meta.cast.map { ActorCredit(name = it) },
                    episodes = episodes,
                    playDataUrl = if (episodes.isEmpty()) "tmdb:$tmdbOrId:$mediaKind" else episodes.first().dataUrl
                )
            } else {
                MediaDetails(
                    id = stableId("$providerId:$url"),
                    providerId = providerId,
                    providerName = name,
                    title = url,
                    url = url,
                    type = MediaType.MOVIE,
                    playDataUrl = url
                )
            }
        }

    override suspend fun getLinks(data: String): Result<ResolvedLinks> =
        sandbox.execute(pluginId, timeoutMs = 25_000L) {
            val filePath = plugin.localFilePath ?: error("Nuvio scraper file is not installed")
            val scriptCode = File(filePath).readText()
            val target = parseTarget(data)

            val output = engine.executeGetStreams(
                pluginId = pluginId,
                scraperId = plugin.internalName,
                scriptCode = scriptCode,
                tmdbId = target.tmdbId,
                mediaType = target.mediaType,
                season = target.season,
                episode = target.episode
            )
            if (output.has("error") && !output.has("streams")) {
                error(output.optString("error"))
            }

            val streamsArr = output.optJSONArray("streams")
            val streams = mutableListOf<StreamSource>()
            val subtitles = mutableListOf<SubtitleTrack>()

            if (streamsArr != null) {
                for (i in 0 until streamsArr.length()) {
                    val obj = streamsArr.optJSONObject(i) ?: continue
                    val streamUrl = obj.optString("url")
                    if (streamUrl.isBlank()) continue

                    val headers = obj.optJSONObject("headers")?.toStringMap().orEmpty()
                    val qualityLabel = obj.optString("quality").ifBlank {
                        obj.optString("title").ifBlank { "Auto" }
                    }
                    val qualityScore = Regex("(\\d{3,4})p").find(qualityLabel)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    val format = when {
                        ".m3u8" in streamUrl.lowercase() -> StreamFormat.HLS
                        ".mpd" in streamUrl.lowercase() -> StreamFormat.DASH
                        ".mp4" in streamUrl.lowercase() -> StreamFormat.MP4
                        else -> StreamFormat.OTHER
                    }

                    val itemSubs = obj.optJSONArray("subtitles")
                    if (itemSubs != null) {
                        for (s in 0 until itemSubs.length()) {
                            val subObj = itemSubs.optJSONObject(s) ?: continue
                            val subUrl = subObj.optString("url")
                            if (subUrl.isNotBlank()) {
                                subtitles.add(
                                    SubtitleTrack(
                                        url = subUrl,
                                        language = subObj.optString("lang", "und"),
                                        label = subObj.optString("label").ifBlank { subObj.optString("lang", "Sub") }
                                    )
                                )
                            }
                        }
                    }

                    streams.add(
                        StreamSource(
                            id = stableId("$providerId:$streamUrl:$i"),
                            providerId = providerId,
                            providerName = name,
                            extractorName = obj.optString("name").ifBlank { name },
                            url = streamUrl,
                            qualityLabel = qualityLabel,
                            qualityScore = qualityScore,
                            format = format,
                            headers = headers,
                            referer = headers["Referer"] ?: headers["referer"]
                        )
                    )
                }
            }

            ResolvedLinks(
                streams = streams.sortedByDescending { it.qualityScore },
                subtitles = subtitles.distinctBy { it.url }
            )
        }

    private fun mapCatalogItem(item: CinemetaCatalogItem): MediaSummary {
        val token = buildString {
            append("cinemeta:")
            append(item.type)
            append(":")
            append(item.id)
            if (!item.moviedbId.isNullOrBlank()) {
                append(":")
                append(item.moviedbId)
            }
        }
        return MediaSummary(
            id = stableId("$providerId:${item.type}:${item.id}"),
            providerId = providerId,
            providerName = name,
            title = item.name,
            url = token,
            type = mapCinemetaType(item.type),
            posterUrl = item.poster,
            year = item.releaseInfo?.take(4)?.toIntOrNull(),
            rating = item.imdbRating?.toDoubleOrNull()
        )
    }

    private fun mapCinemetaType(type: String): MediaType = when (type.lowercase()) {
        "movie" -> MediaType.MOVIE
        "series", "tv" -> MediaType.TV_SERIES
        "anime" -> MediaType.ANIME
        else -> MediaType.OTHER
    }

    private data class NuvioTarget(
        val tmdbId: String,
        val mediaType: String,
        val season: Int?,
        val episode: Int?
    )

    private fun parseTarget(data: String): NuvioTarget {
        val parts = data.removePrefix("tmdb:").split(":")
        return when {
            parts.size >= 4 -> NuvioTarget(
                tmdbId = parts[0],
                mediaType = parts[1],
                season = parts[2].toIntOrNull(),
                episode = parts[3].toIntOrNull()
            )
            parts.size >= 2 -> NuvioTarget(
                tmdbId = parts[0],
                mediaType = parts[1],
                season = null,
                episode = null
            )
            else -> NuvioTarget(tmdbId = data, mediaType = "movie", season = null, episode = null)
        }
    }

    private fun JSONObject.toStringMap(): Map<String, String> {
        val out = mutableMapOf<String, String>()
        keys().forEach { k -> out[k] = optString(k) }
        return out
    }

    private fun stableId(seed: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray())
        return bytes.take(12).joinToString("") { "%02x".format(it) }
    }
}
