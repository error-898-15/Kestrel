// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.pluginhost.nuvio

import com.kestrel.domain.model.CatalogRow
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
 * Scrapers expect data tokens in the form "tmdb:<id>:<movie|tv>[:season:episode]"
 * or plain TMDB IDs when invoked directly for stream resolution.
 */
class NuvioAdapter(
    private val plugin: PluginMetadata,
    private val engine: NuvioJsEngine,
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

    // Nuvio JS plugins are stream scrapers rather than standalone catalog providers.
    override val hasCatalog: Boolean = false

    override suspend fun getCatalog(page: Int): Result<List<CatalogRow>> =
        Result.success(emptyList())

    override suspend fun search(query: String, page: Int): Result<List<MediaSummary>> =
        Result.success(emptyList())

    override suspend fun load(url: String): Result<MediaDetails> =
        sandbox.execute(pluginId) {
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

    override suspend fun getLinks(data: String): Result<ResolvedLinks> =
        sandbox.execute(pluginId, timeoutMs = 25_000L) {
            val filePath = plugin.localFilePath ?: error("Nuvio scraper file is not installed")
            val scriptCode = File(filePath).readText()
            val target = parseTarget(data)

            val output = engine.executeGetStreams(
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
