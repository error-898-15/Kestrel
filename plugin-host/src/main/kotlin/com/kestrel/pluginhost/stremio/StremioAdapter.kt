// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.pluginhost.stremio

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
import com.kestrel.pluginhost.settings.ProviderSettingsStore
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.security.MessageDigest

/**
 * Adapts a Stremio HTTP addon manifest.json into Kestrel's [SourceProvider].
 */
class StremioAdapter(
    private val plugin: PluginMetadata,
    private val manifest: StremioAddonManifest,
    private val client: StremioClient,
    private val sandbox: PluginSandbox,
    private val settingsStore: ProviderSettingsStore? = null
) : SourceProvider {

    private val baseUrl: String = client.baseUrlOf(plugin.downloadUrl)

    override val pluginId: String = plugin.id
    override val providerId: String = "${plugin.id}:${manifest.id.ifBlank { plugin.internalName }}"
    override val name: String = manifest.name.ifBlank { plugin.displayName }
    override val language: String = plugin.language
    override val repoType: RepoType = RepoType.STREMIO

    override val supportedTypes: Set<MediaType> = manifest.types.map { mapStremioType(it) }
        .toSet().ifEmpty { setOf(MediaType.MOVIE, MediaType.TV_SERIES) }

    override val hasCatalog: Boolean = manifest.catalogs.isNotEmpty()

    private fun customHeaders(): Map<String, String> =
        settingsStore?.getHeaders(pluginId).orEmpty()

    override suspend fun getCatalog(page: Int): Result<List<CatalogRow>> =
        sandbox.execute(pluginId) {
            if (page > 1) return@execute emptyList()
            val headers = customHeaders()
            coroutineScope {
                manifest.catalogs.take(6).map { catalog ->
                    async {
                        val res = runCatching {
                            client.fetchCatalog(baseUrl, catalog.type, catalog.id, customHeaders = headers)
                        }.getOrNull()
                        val items = res?.metas.orEmpty().map { meta ->
                            mapMetaPreview(meta)
                        }
                        CatalogRow(
                            id = stableId("$providerId:${catalog.type}:${catalog.id}"),
                            providerId = providerId,
                            providerName = name,
                            title = catalog.name.ifBlank { "${catalog.type} • ${catalog.id}" },
                            items = items,
                            hasNextPage = false
                        )
                    }
                }.awaitAll().filter { it.items.isNotEmpty() }
            }
        }

    override suspend fun search(query: String, page: Int): Result<List<MediaSummary>> =
        sandbox.execute(pluginId) {
            if (page > 1) return@execute emptyList()
            val headers = customHeaders()
            coroutineScope {
                manifest.catalogs.take(4).map { catalog ->
                    async {
                        runCatching {
                            client.fetchCatalog(
                                baseUrl = baseUrl,
                                type = catalog.type,
                                catalogId = catalog.id,
                                searchQuery = query,
                                customHeaders = headers
                            )
                        }.getOrNull()?.metas.orEmpty().map { mapMetaPreview(it) }
                    }
                }.awaitAll().flatten().distinctBy { it.url }
            }
        }

    override suspend fun load(url: String): Result<MediaDetails> =
        sandbox.execute(pluginId) {
            val (type, id) = parseMetaToken(url)
            val headers = customHeaders()
            val meta = client.fetchMeta(baseUrl, type, id, headers).meta
                ?: error("$name returned no meta for $id")

            val episodes = meta.videos.mapIndexed { idx, v ->
                Episode(
                    id = stableId("$providerId:${v.id}"),
                    name = v.title ?: v.name ?: "Episode ${v.episode ?: (idx + 1)}",
                    season = v.season,
                    episodeNumber = v.episode ?: (idx + 1),
                    dataUrl = "${meta.type}::${v.id}",
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
                type = mapStremioType(meta.type),
                plot = meta.description,
                posterUrl = meta.poster,
                backdropUrl = meta.background ?: meta.poster,
                year = meta.releaseInfo?.take(4)?.toIntOrNull(),
                durationMinutes = meta.runtime?.filter(Char::isDigit)?.toIntOrNull(),
                rating = meta.imdbRating?.toDoubleOrNull(),
                tags = meta.genres,
                actors = meta.cast.map { ActorCredit(name = it) },
                episodes = episodes,
                playDataUrl = if (episodes.isEmpty()) "${meta.type}::${meta.id}" else episodes.first().dataUrl
            )
        }

    override suspend fun getLinks(data: String): Result<ResolvedLinks> =
        sandbox.execute(pluginId, timeoutMs = 25_000L) {
            val (type, id) = parseMetaToken(data)
            val response = client.fetchStreams(baseUrl, type, id, customHeaders())

            val streams = mutableListOf<StreamSource>()
            val subtitles = mutableListOf<SubtitleTrack>()

            response.streams.forEachIndexed { idx, dto ->
                val directUrl = dto.url
                if (!directUrl.isNullOrBlank()) {
                    val headers = dto.behaviorHints?.proxyHeaders?.request.orEmpty()
                    val label = dto.name ?: dto.title ?: "Stream ${idx + 1}"
                    val qualityScore = Regex("(\\d{3,4})p").find(label + " " + dto.description.orEmpty())
                        ?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    val format = when {
                        ".m3u8" in directUrl.lowercase() -> StreamFormat.HLS
                        ".mpd" in directUrl.lowercase() -> StreamFormat.DASH
                        ".mp4" in directUrl.lowercase() -> StreamFormat.MP4
                        else -> StreamFormat.OTHER
                    }

                    streams.add(
                        StreamSource(
                            id = stableId("$providerId:$directUrl:$idx"),
                            providerId = providerId,
                            providerName = name,
                            extractorName = label,
                            url = directUrl,
                            qualityLabel = if (qualityScore > 0) "${qualityScore}p" else "Auto",
                            qualityScore = qualityScore,
                            format = format,
                            headers = headers,
                            referer = headers["Referer"] ?: headers["referer"]
                        )
                    )
                }

                dto.subtitles.forEach { sub ->
                    if (sub.url.isNotBlank()) {
                        subtitles.add(SubtitleTrack(url = sub.url, language = sub.lang, label = sub.lang))
                    }
                }
            }

            ResolvedLinks(
                streams = streams.sortedByDescending { it.qualityScore },
                subtitles = subtitles.distinctBy { it.url }
            )
        }

    private fun mapMetaPreview(meta: StremioMetaPreview): MediaSummary = MediaSummary(
        id = stableId("$providerId:${meta.id}"),
        providerId = providerId,
        providerName = name,
        title = meta.name,
        url = "${meta.type}::${meta.id}",
        type = mapStremioType(meta.type),
        posterUrl = meta.poster,
        year = meta.releaseInfo?.take(4)?.toIntOrNull(),
        rating = meta.imdbRating?.toDoubleOrNull()
    )

    private fun parseMetaToken(token: String): Pair<String, String> {
        val parts = token.split("::", limit = 2)
        return if (parts.size == 2) parts[0] to parts[1] else "movie" to token
    }

    private fun mapStremioType(type: String): MediaType = when (type.lowercase()) {
        "movie" -> MediaType.MOVIE
        "series" -> MediaType.TV_SERIES
        "anime" -> MediaType.ANIME
        "tv", "channel" -> MediaType.LIVE
        else -> MediaType.OTHER
    }

    private fun stableId(seed: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray())
        return bytes.take(12).joinToString("") { "%02x".format(it) }
    }
}
