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
import com.kestrel.pluginhost.settings.ProviderSettingsStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Adapts a user-enabled Nuvio JS scraper into Kestrel's [SourceProvider].
 * Runs script-exported getCatalog/search/getItemDetails when provided, and otherwise
 * falls back to niche-matched per-provider catalogs & metadata via [CinemetaCatalogClient],
 * resolving numeric TMDB IDs for getStreams() playback.
 */
class NuvioAdapter(
    private val plugin: PluginMetadata,
    private val engine: NuvioJsEngine,
    private val catalogClient: CinemetaCatalogClient,
    private val sandbox: PluginSandbox,
    private val settingsStore: ProviderSettingsStore? = null
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

    private fun buildProfile(): NuvioCatalogProfile {
        val values = settingsStore?.getValues(pluginId).orEmpty()
        val tmdbKey = values["tmdbApiKey"]
            ?: values["tmdb_api_key"]
            ?: values["TMDB_API_KEY"]
        return NuvioCatalogProfile(
            providerId = providerId,
            internalName = plugin.internalName,
            displayName = plugin.displayName,
            description = plugin.description,
            language = plugin.language,
            supportedTypes = supportedTypes,
            customTmdbApiKey = tmdbKey?.trim()?.takeIf { it.isNotEmpty() }
        )
    }

    override suspend fun getCatalog(page: Int): Result<List<CatalogRow>> =
        sandbox.execute(pluginId) {
            val safePage = page.coerceAtLeast(1)

            // 1. Check if the JS scraper itself exports getCatalog() / getHome()
            val scriptCode = plugin.localFilePath?.let { path ->
                val f = File(path)
                if (f.exists()) f.readText() else null
            }
            if (scriptCode != null && (
                    scriptCode.contains("getCatalog") ||
                    scriptCode.contains("getHome")
                )
            ) {
                val customOut = runCatching {
                    engine.executeGetCatalog(
                        pluginId = pluginId,
                        scraperId = plugin.internalName,
                        scriptCode = scriptCode,
                        page = safePage
                    )
                }.getOrNull()
                if (customOut != null && customOut.optBoolean("hasCustomCatalog", false)) {
                    val customRows = parseCustomCatalogSections(customOut.optJSONArray("sections"))
                    if (customRows.isNotEmpty()) {
                        return@execute customRows
                    }
                }
            }

            // 2. Otherwise use per-provider niche-matched & rotated catalogs
            if (safePage > 5) return@execute emptyList()
            catalogClient.fetchCatalogsForProvider(buildProfile(), safePage).map { section ->
                CatalogRow(
                    id = stableId("$providerId:${section.id}:$safePage"),
                    providerId = providerId,
                    providerName = name,
                    title = section.title,
                    items = section.items.map { mapCatalogItem(it) },
                    hasNextPage = section.items.size >= 10 && safePage < 5
                )
            }
        }

    override suspend fun search(query: String, page: Int): Result<List<MediaSummary>> =
        sandbox.execute(pluginId) {
            val trimmed = query.trim()
            if (trimmed.isBlank()) return@execute emptyList()
            val safePage = page.coerceAtLeast(1)

            val scriptCode = plugin.localFilePath?.let { path ->
                val f = File(path)
                if (f.exists()) f.readText() else null
            }
            if (scriptCode != null && Regex("\\bsearch\\b").containsMatchIn(scriptCode)) {
                val customOut = runCatching {
                    engine.executeSearch(
                        pluginId = pluginId,
                        scraperId = plugin.internalName,
                        scriptCode = scriptCode,
                        query = trimmed,
                        page = safePage
                    )
                }.getOrNull()
                if (customOut != null && customOut.optBoolean("hasCustomSearch", false)) {
                    val items = parseCustomSummaryArray(customOut.optJSONArray("items"))
                    if (items.isNotEmpty()) return@execute items
                }
            }

            catalogClient.searchForProvider(trimmed, buildProfile(), safePage)
                .map { mapCatalogItem(it) }
        }

    override suspend fun load(url: String): Result<MediaDetails> =
        sandbox.execute(pluginId) {
            when {
                url.startsWith("nuvio:") -> loadCustomNuvioDetails(url)
                url.startsWith("anilist:") -> loadAniListToken(url)
                url.startsWith("kitsu:") -> loadKitsuToken(url)
                url.startsWith("tmdbmeta:") -> loadTmdbMetaToken(url)
                url.startsWith("cinemeta:") -> loadCinemetaToken(url)
                else -> MediaDetails(
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

    private suspend fun loadCustomNuvioDetails(url: String): MediaDetails {
        val rawId = url.removePrefix("nuvio:")
        val scriptCode = plugin.localFilePath?.let { path ->
            val f = File(path)
            if (f.exists()) f.readText() else null
        }
        val custom = if (scriptCode != null) {
            runCatching {
                engine.executeGetDetails(
                    pluginId = pluginId,
                    scraperId = plugin.internalName,
                    scriptCode = scriptCode,
                    itemUrlOrId = rawId
                )
            }.getOrNull()
        } else null

        val detailsObj = if (custom != null && custom.optBoolean("hasCustomDetails", false)) {
            custom.optJSONObject("details")
        } else null

        if (detailsObj != null) {
            val title = detailsObj.optString("title").ifBlank { detailsObj.optString("name").ifBlank { rawId } }
            val rawType = detailsObj.optString("type", "movie")
            val mediaKind = if (rawType.equals("movie", ignoreCase = true)) "movie" else "tv"
            val explicitTmdb = detailsObj.optString("tmdbId")
                .ifBlank { detailsObj.optString("moviedb_id") }
                .takeIf { it.isNotBlank() && it.all(Char::isDigit) }
            val idSeed = explicitTmdb ?: rawId
            val epsArr = detailsObj.optJSONArray("episodes")
            val episodes = mutableListOf<Episode>()
            if (epsArr != null) {
                for (i in 0 until epsArr.length()) {
                    val ep = epsArr.optJSONObject(i) ?: continue
                    val s = ep.optInt("season", 1)
                    val e = ep.optInt("episode", ep.optInt("number", i + 1))
                    val epData = ep.optString("dataUrl").ifBlank {
                        ep.optString("url").ifBlank { "tmdb:$idSeed:$mediaKind:$s:$e" }
                    }
                    episodes.add(
                        Episode(
                            id = stableId("$providerId:$idSeed:$s:$e"),
                            name = ep.optString("name").ifBlank { ep.optString("title").ifBlank { "Episode $e" } },
                            season = s,
                            episodeNumber = e,
                            dataUrl = epData,
                            posterUrl = ep.optString("poster").ifBlank { ep.optString("thumbnail") }.takeIf { it.isNotBlank() },
                            description = ep.optString("description").takeIf { it.isNotBlank() }
                        )
                    )
                }
            }
            val directPlay = detailsObj.optString("playDataUrl").ifBlank {
                detailsObj.optString("url").takeIf { it.startsWith("tmdb:") }.orEmpty()
            }.ifBlank { "tmdb:$idSeed:$mediaKind" }

            return MediaDetails(
                id = stableId("$providerId:$rawId"),
                providerId = providerId,
                providerName = name,
                title = title,
                url = url,
                type = mapCinemetaType(rawType),
                plot = detailsObj.optString("description").ifBlank { detailsObj.optString("plot") }.takeIf { it.isNotBlank() },
                posterUrl = detailsObj.optString("poster").ifBlank { detailsObj.optString("posterUrl") }.takeIf { it.isNotBlank() },
                backdropUrl = detailsObj.optString("backdrop").ifBlank { detailsObj.optString("background") }.takeIf { it.isNotBlank() },
                year = detailsObj.optString("year").take(4).toIntOrNull(),
                rating = detailsObj.optString("rating").toDoubleOrNull(),
                episodes = episodes,
                playDataUrl = if (episodes.isEmpty()) directPlay else episodes.first().dataUrl
            )
        }

        return MediaDetails(
            id = stableId("$providerId:$url"),
            providerId = providerId,
            providerName = name,
            title = rawId,
            url = url,
            type = MediaType.MOVIE,
            playDataUrl = "tmdb:$rawId:movie"
        )
    }

    private suspend fun loadAniListToken(url: String): MediaDetails {
        val parts = url.removePrefix("anilist:").split(":")
        val type = parts.getOrNull(0).orEmpty().ifBlank { "series" }
        val aniId = parts.getOrNull(1).orEmpty().ifBlank { url }
        val hintTmdb = parts.getOrNull(2)?.takeIf { it.isNotBlank() }

        val meta = catalogClient.fetchAniListMeta(type, aniId)
            ?: error("$name returned no details for AniList $aniId")
        val resolvedTmdb = meta.moviedbId
            ?: hintTmdb
            ?: catalogClient.resolveArmIds("anilist", aniId).first
        return buildDetailsFromMeta(meta = meta, rawUrl = url, tmdbOrFallback = resolvedTmdb ?: meta.id)
    }

    private suspend fun loadKitsuToken(url: String): MediaDetails {
        val parts = url.removePrefix("kitsu:").split(":")
        val type = parts.getOrNull(0).orEmpty().ifBlank { "series" }
        val rawKitsuId = parts.getOrNull(1).orEmpty().ifBlank { url }
        val hintTmdb = parts.getOrNull(2)?.takeIf { it.isNotBlank() }

        val meta = catalogClient.fetchKitsuMeta(type, rawKitsuId)
            ?: catalogClient.fetchMeta(type, rawKitsuId)
            ?: error("$name returned no details for $rawKitsuId")
        val numericKitsu = rawKitsuId.removePrefix("kitsu:")
        val resolvedTmdb = meta.moviedbId
            ?: hintTmdb
            ?: catalogClient.resolveArmIds("kitsu", numericKitsu).first
        return buildDetailsFromMeta(meta = meta, rawUrl = url, tmdbOrFallback = resolvedTmdb ?: meta.id)
    }

    private suspend fun loadTmdbMetaToken(url: String): MediaDetails {
        val parts = url.removePrefix("tmdbmeta:").split(":")
        val type = parts.getOrNull(0).orEmpty().ifBlank { "movie" }
        val tmdbId = parts.getOrNull(1).orEmpty().ifBlank { url }
        val tmdbKey = buildProfile().customTmdbApiKey
        val meta = if (!tmdbKey.isNullOrBlank()) {
            catalogClient.fetchTmdbMeta(type, tmdbId, tmdbKey)
        } else {
            null
        } ?: error("$name returned no TMDB details for $tmdbId")
        return buildDetailsFromMeta(meta = meta, rawUrl = url, tmdbOrFallback = tmdbId)
    }

    private suspend fun loadCinemetaToken(url: String): MediaDetails {
        val parts = url.removePrefix("cinemeta:").split(":")
        val type = parts.getOrNull(0).orEmpty().ifBlank { "movie" }
        val id = parts.getOrNull(1).orEmpty().ifBlank { url }
        val hintTmdb = parts.getOrNull(2)?.takeIf { it.isNotBlank() }

        val meta = catalogClient.fetchMeta(type, id)
        if (meta != null) {
            val resolvedTmdb = meta.moviedbId
                ?: hintTmdb
                ?: if (id.startsWith("tt", ignoreCase = true)) {
                    catalogClient.resolveArmIds("imdb", id).first
                } else {
                    null
                }
            return buildDetailsFromMeta(meta = meta, rawUrl = url, tmdbOrFallback = resolvedTmdb ?: meta.id)
        }

        // Handle IndiaStreams items that may use numeric TMDB IDs or unindexed IMDb IDs
        val cleanId = id.removePrefix("tmdb:").removePrefix("tmdb_")
        val resolvedTmdb = hintTmdb ?: when {
            cleanId.all(Char::isDigit) -> cleanId
            id.startsWith("tt", ignoreCase = true) -> catalogClient.resolveArmIds("imdb", id).first
            else -> null
        } ?: cleanId
        val mediaKind = if (type.equals("movie", ignoreCase = true)) "movie" else "tv"
        return MediaDetails(
            id = stableId("$providerId:$id"),
            providerId = providerId,
            providerName = name,
            title = id,
            url = url,
            type = mapCinemetaType(type),
            playDataUrl = "tmdb:$resolvedTmdb:$mediaKind"
        )
    }

    private fun buildDetailsFromMeta(
        meta: CinemetaMetaDetail,
        rawUrl: String,
        tmdbOrFallback: String
    ): MediaDetails {
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
                    dataUrl = "tmdb:$tmdbOrFallback:$mediaKind:$s:$e",
                    posterUrl = v.thumbnail,
                    description = v.overview
                )
            }

        return MediaDetails(
            id = stableId("$providerId:${meta.id}"),
            providerId = providerId,
            providerName = name,
            title = meta.name,
            url = rawUrl,
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
            playDataUrl = if (episodes.isEmpty()) "tmdb:$tmdbOrFallback:$mediaKind" else episodes.first().dataUrl
        )
    }

    override suspend fun getLinks(data: String): Result<ResolvedLinks> =
        sandbox.execute(pluginId, timeoutMs = 30_000L) {
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
                    val streamUrl = listOf("url", "proxy_url", "file", "link")
                        .firstNotNullOfOrNull { key -> obj.optString(key).takeIf { it.isNotBlank() } }
                        ?: continue

                    val mergedHeaders = LinkedHashMap<String, String>()
                    obj.optJSONObject("headers")?.toStringMap()?.let { mergedHeaders.putAll(it) }
                    val hints = obj.optJSONObject("behaviorHints")
                    hints?.optJSONObject("headers")?.toStringMap()?.let { mergedHeaders.putAll(it) }
                    hints?.optJSONObject("proxyHeaders")?.optJSONObject("request")?.toStringMap()?.let {
                        mergedHeaders.putAll(it)
                    }

                    val qualityLabel = obj.optString("quality")
                        .ifBlank { obj.optString("title") }
                        .ifBlank { obj.optString("name") }
                        .ifBlank { "Auto" }

                    val qualityScore = when {
                        qualityLabel.contains("4K", ignoreCase = true) ||
                            qualityLabel.contains("2160", ignoreCase = true) -> 2160
                        else -> Regex("(\\d{3,4})p", RegexOption.IGNORE_CASE)
                            .find(qualityLabel)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    }

                    val lowerUrl = streamUrl.lowercase()
                    val format = when {
                        ".m3u8" in lowerUrl || obj.optString("type").equals("hls", ignoreCase = true) -> StreamFormat.HLS
                        ".mpd" in lowerUrl || obj.optString("type").equals("dash", ignoreCase = true) -> StreamFormat.DASH
                        ".mp4" in lowerUrl || ".mkv" in lowerUrl -> StreamFormat.MP4
                        else -> StreamFormat.OTHER
                    }

                    val itemSubs = obj.optJSONArray("subtitles")
                    if (itemSubs != null) {
                        for (s in 0 until itemSubs.length()) {
                            val subObj = itemSubs.optJSONObject(s) ?: continue
                            val subUrl = subObj.optString("url").ifBlank { subObj.optString("file") }
                            if (subUrl.isNotBlank()) {
                                val lang = subObj.optString("lang")
                                    .ifBlank { subObj.optString("language") }
                                    .ifBlank { "und" }
                                subtitles.add(
                                    SubtitleTrack(
                                        url = subUrl,
                                        language = lang,
                                        label = subObj.optString("label").ifBlank { lang }
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
                            extractorName = obj.optString("name")
                                .ifBlank { obj.optString("server") }
                                .ifBlank { name },
                            url = streamUrl,
                            qualityLabel = qualityLabel,
                            qualityScore = qualityScore,
                            format = format,
                            headers = mergedHeaders,
                            referer = mergedHeaders["Referer"] ?: mergedHeaders["referer"]
                        )
                    )
                }
            }

            ResolvedLinks(
                streams = streams.sortedByDescending { it.qualityScore },
                subtitles = subtitles.distinctBy { it.url }
            )
        }

    private fun parseCustomCatalogSections(sectionsArr: JSONArray?): List<CatalogRow> {
        if (sectionsArr == null) return emptyList()
        val rows = mutableListOf<CatalogRow>()
        for (i in 0 until sectionsArr.length()) {
            val sec = sectionsArr.optJSONObject(i) ?: continue
            val secId = sec.optString("id").ifBlank { "sec_$i" }
            val title = sec.optString("title").ifBlank { sec.optString("name").ifBlank { name } }
            val items = parseCustomSummaryArray(sec.optJSONArray("items"))
            if (items.isNotEmpty()) {
                rows.add(
                    CatalogRow(
                        id = stableId("$providerId:$secId:$i"),
                        providerId = providerId,
                        providerName = name,
                        title = title,
                        items = items,
                        hasNextPage = sec.optBoolean("hasNextPage", items.size >= 10)
                    )
                )
            }
        }
        return rows
    }

    private fun parseCustomSummaryArray(itemsArr: JSONArray?): List<MediaSummary> {
        if (itemsArr == null) return emptyList()
        val list = mutableListOf<MediaSummary>()
        for (i in 0 until itemsArr.length()) {
            val obj = itemsArr.optJSONObject(i) ?: continue
            val title = obj.optString("title").ifBlank { obj.optString("name") }
            if (title.isBlank()) continue
            val itemUrl = obj.optString("url")
                .ifBlank { obj.optString("id") }
                .ifBlank { obj.optString("tmdbId") }
            if (itemUrl.isBlank()) continue

            val rawType = obj.optString("type", "movie")
            val token = when {
                itemUrl.startsWith("cinemeta:") ||
                    itemUrl.startsWith("anilist:") ||
                    itemUrl.startsWith("kitsu:") ||
                    itemUrl.startsWith("tmdbmeta:") ||
                    itemUrl.startsWith("nuvio:") -> itemUrl
                else -> "nuvio:$itemUrl"
            }
            list.add(
                MediaSummary(
                    id = stableId("$providerId:$token:$i"),
                    providerId = providerId,
                    providerName = name,
                    title = title,
                    url = token,
                    type = mapCinemetaType(rawType),
                    posterUrl = obj.optString("poster").ifBlank { obj.optString("posterUrl") }.takeIf { it.isNotBlank() },
                    year = obj.optString("year").ifBlank { obj.optString("releaseInfo") }.take(4).toIntOrNull(),
                    rating = obj.optString("rating").ifBlank { obj.optString("imdbRating") }.toDoubleOrNull()
                )
            )
        }
        return list
    }

    private fun mapCatalogItem(item: CinemetaCatalogItem): MediaSummary {
        val token = when (item.sourceKind) {
            "anilist" -> buildString {
                append("anilist:")
                append(item.type)
                append(":")
                append(item.id)
                if (!item.moviedbId.isNullOrBlank()) {
                    append(":")
                    append(item.moviedbId)
                }
            }
            "kitsu" -> buildString {
                append("kitsu:")
                append(item.type)
                append(":")
                append(item.id)
                if (!item.moviedbId.isNullOrBlank()) {
                    append(":")
                    append(item.moviedbId)
                }
            }
            "tmdb" -> "tmdbmeta:${item.type}:${item.id}"
            else -> buildString {
                append("cinemeta:")
                append(item.type)
                append(":")
                append(item.id)
                if (!item.moviedbId.isNullOrBlank()) {
                    append(":")
                    append(item.moviedbId)
                }
            }
        }
        return MediaSummary(
            id = stableId("$providerId:${item.sourceKind}:${item.type}:${item.id}"),
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
        keys().forEach { k ->
            val v = optString(k)
            if (v.isNotBlank()) out[k] = v
        }
        return out
    }

    private fun stableId(seed: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray())
        return bytes.take(12).joinToString("") { "%02x".format(it) }
    }
}
