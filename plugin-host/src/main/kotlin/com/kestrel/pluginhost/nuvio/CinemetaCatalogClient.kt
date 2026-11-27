// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.pluginhost.nuvio

import com.kestrel.domain.model.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

data class CinemetaCatalogItem(
    val id: String,
    val type: String,
    val name: String,
    val poster: String?,
    val releaseInfo: String?,
    val imdbRating: String?,
    val moviedbId: String?,
    val sourceKind: String = "cinemeta"
)

data class CinemetaCatalogSection(
    val id: String,
    val title: String,
    val items: List<CinemetaCatalogItem>
)

data class CinemetaVideoItem(
    val id: String,
    val title: String?,
    val season: Int?,
    val episode: Int?,
    val thumbnail: String?,
    val overview: String?
)

data class CinemetaMetaDetail(
    val id: String,
    val moviedbId: String?,
    val type: String,
    val name: String,
    val poster: String?,
    val background: String?,
    val logo: String?,
    val description: String?,
    val releaseInfo: String?,
    val runtime: String?,
    val imdbRating: String?,
    val genres: List<String>,
    val cast: List<String>,
    val videos: List<CinemetaVideoItem>
)

data class ResolvedExternalIds(
    val tmdbId: String? = null,
    val imdbId: String? = null
)

enum class NuvioNiche {
    ANIME,
    BOLLYWOOD_HINDI,
    SOUTH_INDIAN,
    ASIAN_DRAMA,
    FRENCH,
    LATINO_SPANISH,
    PERSIAN,
    GENERAL
}

data class NuvioCatalogProfile(
    val providerId: String,
    val internalName: String,
    val displayName: String,
    val description: String,
    val language: String,
    val supportedTypes: Set<MediaType>,
    val customTmdbApiKey: String? = null
)

/**
 * Metadata & catalog client for Nuvio JS scrapers.
 * Provides niche-aware, type-filtered, per-provider rotated catalogs across
 * Cinemeta, IndiaStreams, AniList, Anime Kitsu, and optional user TMDB keys,
 * while resolving numeric TMDB IDs (moviedb_id / ARM) for playback.
 */
@Singleton
class CinemetaCatalogClient @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val json: Json
) {
    private data class CachedCatalogs(
        val fetchedAtMs: Long,
        val sections: List<CinemetaCatalogSection>
    )

    private data class CachedEndpoint(
        val fetchedAtMs: Long,
        val items: List<CinemetaCatalogItem>
    )

    private enum class FeedSource {
        CINEMETA,
        INDIASTREAMS,
        KITSU,
        ANILIST_TRENDING,
        ANILIST_POPULAR,
        ANILIST_SCORE,
        ANILIST_AIRING,
        ANILIST_NEW,
        ANILIST_MOVIE_POPULAR,
        ANILIST_MOVIE_SCORE
    }

    private data class RowDescriptor(
        val id: String,
        val mediaType: MediaType,
        val title: String,
        val source: FeedSource,
        val catalogType: String = "movie",
        val catalogId: String = "top",
        val genre: String? = null
    )

    private val providerCatalogCache = ConcurrentHashMap<String, CachedCatalogs>()
    private val endpointCache = ConcurrentHashMap<String, CachedEndpoint>()
    private val metaCache = ConcurrentHashMap<String, CinemetaMetaDetail>()
    private val armTmdbCache = ConcurrentHashMap<String, Pair<String?, String?>>()
    private val externalIdCache = ConcurrentHashMap<String, ResolvedExternalIds>() // key -> (tmdbId, imdbId)

    @Volatile
    private var cachedDefaultCatalogs: CachedCatalogs? = null

    fun classifyNiche(
        internalName: String,
        displayName: String,
        description: String,
        language: String,
        supportedTypes: Set<MediaType>
    ): NuvioNiche {
        val text = "$internalName $displayName $description".lowercase()
        val lang = language.trim().lowercase()

        if (ANIME_KEYWORDS.any { it in text } ||
            (supportedTypes == setOf(MediaType.ANIME))
        ) {
            return NuvioNiche.ANIME
        }
        if (SOUTH_INDIAN_KEYWORDS.any { it in text } ||
            lang in setOf("ta", "te", "ml", "kn")
        ) {
            return NuvioNiche.SOUTH_INDIAN
        }
        if (HINDI_KEYWORDS.any { it in text } || lang == "hi") {
            return NuvioNiche.BOLLYWOOD_HINDI
        }
        if (KDRAMA_KEYWORDS.any { it in text } || lang in setOf("ko", "zh")) {
            return NuvioNiche.ASIAN_DRAMA
        }
        if (FRENCH_KEYWORDS.any { it in text } || lang == "fr") {
            return NuvioNiche.FRENCH
        }
        if (LATINO_KEYWORDS.any { it in text } || lang in setOf("es", "pt")) {
            return NuvioNiche.LATINO_SPANISH
        }
        if ("persian" in text || lang == "fa") {
            return NuvioNiche.PERSIAN
        }
        return NuvioNiche.GENERAL
    }

    suspend fun fetchCatalogsForProvider(
        profile: NuvioCatalogProfile,
        page: Int = 1
    ): List<CinemetaCatalogSection> {
        val safePage = page.coerceAtLeast(1)
        val cacheKey = "${profile.providerId}:$safePage"
        val cached = providerCatalogCache[cacheKey]
        if (cached != null &&
            System.currentTimeMillis() - cached.fetchedAtMs < CACHE_TTL_MS &&
            cached.sections.isNotEmpty()
        ) {
            return cached.sections
        }

        val niche = classifyNiche(
            internalName = profile.internalName,
            displayName = profile.displayName,
            description = profile.description,
            language = profile.language,
            supportedTypes = profile.supportedTypes
        )

        val tmdbKey = profile.customTmdbApiKey?.trim()?.takeIf { it.isNotEmpty() }
        if (tmdbKey != null && safePage <= 5) {
            val tmdbSections = runCatching {
                fetchTmdbNicheCatalogs(profile, niche, safePage, tmdbKey)
            }.getOrDefault(emptyList())
            if (tmdbSections.isNotEmpty()) {
                providerCatalogCache[cacheKey] = CachedCatalogs(System.currentTimeMillis(), tmdbSections)
                return tmdbSections
            }
        }

        val pool = selectPoolForNiche(niche, profile.supportedTypes)
        val offset = (profile.providerId.hashCode() and 0x7fffffff)
        val windowSize = if (pool.size <= 4) pool.size else 4
        val selected = rotatedWindow(pool, windowSize, offset)

        var sections = fetchRowDescriptors(selected, safePage)

        // Fallback to general Cinemeta pool if a regional feed (e.g. IndiaStreams) fails or has no page > 1.
        if (sections.isEmpty()) {
            val fallbackPool = filterBySupportedTypes(GENERAL_POOL, profile.supportedTypes)
            val fallbackSelected = rotatedWindow(fallbackPool, fallbackPool.size.coerceAtMost(4), offset)
            sections = fetchRowDescriptors(fallbackSelected, safePage)
        }

        if (sections.isNotEmpty()) {
            providerCatalogCache[cacheKey] = CachedCatalogs(System.currentTimeMillis(), sections)
        }
        return sections
    }

    suspend fun fetchHomeCatalogs(): List<CinemetaCatalogSection> {
        val cached = cachedDefaultCatalogs
        if (cached != null && System.currentTimeMillis() - cached.fetchedAtMs < CACHE_TTL_MS && cached.sections.isNotEmpty()) {
            return cached.sections
        }

        val descriptors = listOf(
            RowDescriptor("movie:top", MediaType.MOVIE, "Popular Movies", FeedSource.CINEMETA, "movie", "top"),
            RowDescriptor("series:top", MediaType.TV_SERIES, "Popular Series", FeedSource.CINEMETA, "series", "top"),
            RowDescriptor("movie:imdbRating", MediaType.MOVIE, "Top Rated Movies", FeedSource.CINEMETA, "movie", "imdbRating"),
            RowDescriptor("series:imdbRating", MediaType.TV_SERIES, "Top Rated Series", FeedSource.CINEMETA, "series", "imdbRating")
        )
        val sections = fetchRowDescriptors(descriptors, page = 1)
        if (sections.isNotEmpty()) {
            cachedDefaultCatalogs = CachedCatalogs(System.currentTimeMillis(), sections)
        }
        return sections
    }

    suspend fun searchForProvider(
        query: String,
        profile: NuvioCatalogProfile,
        page: Int = 1
    ): List<CinemetaCatalogItem> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        val niche = classifyNiche(
            internalName = profile.internalName,
            displayName = profile.displayName,
            description = profile.description,
            language = profile.language,
            supportedTypes = profile.supportedTypes
        )

        if (niche == NuvioNiche.ANIME) {
            val animeResults = coroutineScope {
                val aniDeferred = async { runCatching { searchAniList(trimmed, page) }.getOrDefault(emptyList()) }
                val kitsuDeferred = async {
                    if (page == 1) runCatching { searchKitsu(trimmed) }.getOrDefault(emptyList()) else emptyList()
                }
                (aniDeferred.await() + kitsuDeferred.await()).distinctBy { "${it.sourceKind}:${it.id}" }
            }
            if (animeResults.isNotEmpty()) return animeResults
        }

        if (page > 1) return emptyList()
        return search(trimmed)
    }

    suspend fun search(query: String): List<CinemetaCatalogItem> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        val encoded = encode(trimmed)
        return coroutineScope {
            listOf("movie", "series").map { type ->
                async {
                    runCatching {
                        fetchStremioCatalogEndpoint(
                            url = "$CINEMETA_BASE_URL/catalog/$type/top/search=$encoded.json",
                            sourceKind = "cinemeta"
                        )
                    }.getOrDefault(emptyList())
                }
            }.awaitAll().flatten().distinctBy { "${it.type}:${it.id}" }
        }
    }

    suspend fun fetchMeta(type: String, id: String): CinemetaMetaDetail? {
        val key = "$type:$id"
        metaCache[key]?.let { return it }

        val encodedType = encode(type)
        val encodedId = encode(id)
        val raw = runCatching { get("$CINEMETA_BASE_URL/meta/$encodedType/$encodedId.json") }.getOrNull()
            ?: return null
        val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
        val metaObj = root["meta"] as? JsonObject ?: return null

        val videos = (metaObj["videos"] as? JsonArray).orEmpty().mapNotNull { el ->
            val v = el as? JsonObject ?: return@mapNotNull null
            val vid = v.str("id") ?: return@mapNotNull null
            CinemetaVideoItem(
                id = vid,
                title = v.str("title") ?: v.str("name"),
                season = v.intVal("season") ?: v.intVal("number"),
                episode = v.intVal("episode"),
                thumbnail = v.str("thumbnail"),
                overview = v.str("overview")
            )
        }

        val resolvedId = metaObj.str("id") ?: id
        val resolvedTitle = metaObj.str("name") ?: id
        val resolvedYear = (metaObj.str("releaseInfo") ?: metaObj.primitiveStr("year"))?.take(4)?.toIntOrNull()
        val resolvedTypeStr = metaObj.str("type") ?: type
        var moviedbId = metaObj.primitiveStr("moviedb_id")
        if (moviedbId.isNullOrBlank() && resolvedId.startsWith("tt", ignoreCase = true)) {
            moviedbId = resolveTmdbIdForImdb(
                imdbId = resolvedId,
                title = resolvedTitle,
                year = resolvedYear,
                isMovie = resolvedTypeStr.equals("movie", ignoreCase = true),
                tmdbApiKey = null
            )
        }

        val detail = CinemetaMetaDetail(
            id = resolvedId,
            moviedbId = moviedbId,
            type = metaObj.str("type") ?: type,
            name = metaObj.str("name") ?: id,
            poster = metaObj.str("poster"),
            background = metaObj.str("background"),
            logo = metaObj.str("logo"),
            description = metaObj.str("description"),
            releaseInfo = metaObj.str("releaseInfo") ?: metaObj.primitiveStr("year"),
            runtime = metaObj.str("runtime"),
            imdbRating = metaObj.primitiveStr("imdbRating"),
            genres = metaObj.strList("genres"),
            cast = metaObj.strList("cast"),
            videos = videos
        )
        metaCache[key] = detail
        return detail
    }

    suspend fun fetchKitsuMeta(type: String, kitsuToken: String): CinemetaMetaDetail? {
        val numericId = kitsuToken.removePrefix("kitsu:").trim()
        val key = "kitsu:$numericId"
        metaCache[key]?.let { return it }

        val (armTmdb, armImdb) = resolveArmIds("kitsu", numericId)

        val encodedId = encode("kitsu:$numericId")
        val raw = runCatching { get("$KITSU_BASE_URL/meta/anime/$encodedId.json") }.getOrNull()
        val metaObj = raw?.let { runCatching { json.parseToJsonElement(it).jsonObject["meta"] as? JsonObject }.getOrNull() }

        val imdbId = metaObj?.str("imdb_id") ?: armImdb
        if (!imdbId.isNullOrBlank()) {
            val cinemetaType = if (metaObj?.str("animeType").equals("movie", ignoreCase = true) || type == "movie") "movie" else "series"
            val cm = fetchMeta(cinemetaType, imdbId)
            if (cm != null) {
                val merged = if (cm.moviedbId.isNullOrBlank() && !armTmdb.isNullOrBlank()) {
                    cm.copy(moviedbId = armTmdb)
                } else cm
                metaCache[key] = merged
                return merged
            }
        }

        if (metaObj == null) return null
        val videos = (metaObj["videos"] as? JsonArray).orEmpty().mapIndexedNotNull { idx, el ->
            val v = el as? JsonObject ?: return@mapIndexedNotNull null
            val vid = v.str("id") ?: return@mapIndexedNotNull null
            CinemetaVideoItem(
                id = vid,
                title = v.str("title") ?: v.str("name"),
                season = v.intVal("season") ?: 1,
                episode = v.intVal("episode") ?: v.intVal("number") ?: (idx + 1),
                thumbnail = v.str("thumbnail"),
                overview = v.str("overview")
            )
        }
        val detail = CinemetaMetaDetail(
            id = metaObj.str("id") ?: "kitsu:$numericId",
            moviedbId = armTmdb,
            type = if (metaObj.str("animeType").equals("movie", ignoreCase = true)) "movie" else "series",
            name = metaObj.str("name") ?: kitsuToken,
            poster = metaObj.str("poster"),
            background = metaObj.str("background"),
            logo = metaObj.str("logo"),
            description = metaObj.str("description"),
            releaseInfo = metaObj.str("releaseInfo") ?: metaObj.primitiveStr("year"),
            runtime = metaObj.str("runtime"),
            imdbRating = metaObj.primitiveStr("imdbRating"),
            genres = metaObj.strList("genres"),
            cast = metaObj.strList("cast"),
            videos = videos
        )
        metaCache[key] = detail
        return detail
    }

    suspend fun fetchAniListMeta(type: String, anilistId: String): CinemetaMetaDetail? {
        val cleanId = anilistId.removePrefix("anilist:").trim()
        val key = "anilist:$cleanId"
        metaCache[key]?.let { return it }

        val (armTmdb, armImdb) = resolveArmIds("anilist", cleanId)
        if (!armImdb.isNullOrBlank()) {
            val cinemetaType = if (type.equals("movie", ignoreCase = true)) "movie" else "series"
            val cm = fetchMeta(cinemetaType, armImdb)
            if (cm != null) {
                val merged = if (cm.moviedbId.isNullOrBlank() && !armTmdb.isNullOrBlank()) {
                    cm.copy(moviedbId = armTmdb)
                } else cm
                metaCache[key] = merged
                return merged
            }
        }

        val numId = cleanId.toIntOrNull() ?: return null
        val gql = """
            {
              Media(id: $numId, type: ANIME) {
                id
                format
                episodes
                duration
                averageScore
                seasonYear
                description(asHtml: false)
                genres
                title { english romaji native }
                coverImage { extraLarge large }
                bannerImage
              }
            }
        """.trimIndent()
        val raw = runCatching { postGraphQl(ANILIST_URL, gql) }.getOrNull() ?: return null
        val media = runCatching {
            json.parseToJsonElement(raw).jsonObject["data"]?.jsonObject?.get("Media") as? JsonObject
        }.getOrNull() ?: return null

        val titleObj = media["title"] as? JsonObject
        val title = titleObj?.str("english") ?: titleObj?.str("romaji") ?: titleObj?.str("native") ?: cleanId
        val format = media.str("format").orEmpty()
        val isMovie = format.equals("MOVIE", ignoreCase = true) || type.equals("movie", ignoreCase = true)
        val epCount = media.intVal("episodes") ?: if (isMovie) 0 else 12
        val cover = (media["coverImage"] as? JsonObject)?.let { it.str("extraLarge") ?: it.str("large") }
        val banner = media.str("bannerImage") ?: cover
        val score10 = media.intVal("averageScore")?.let { String.format(Locale.US, "%.1f", it / 10.0) }
        val desc = media.str("description")?.replace(Regex("<[^>]+>"), "")?.trim()

        val videos = if (isMovie) {
            emptyList()
        } else {
            (1..epCount.coerceIn(1, 200)).map { ep ->
                CinemetaVideoItem(
                    id = "$cleanId:1:$ep",
                    title = "Episode $ep",
                    season = 1,
                    episode = ep,
                    thumbnail = banner,
                    overview = null
                )
            }
        }

        val detail = CinemetaMetaDetail(
            id = "anilist:$cleanId",
            moviedbId = armTmdb,
            type = if (isMovie) "movie" else "series",
            name = title,
            poster = cover,
            background = banner,
            logo = null,
            description = desc,
            releaseInfo = media.primitiveStr("seasonYear"),
            runtime = media.intVal("duration")?.toString(),
            imdbRating = score10,
            genres = media.strList("genres"),
            cast = emptyList(),
            videos = videos
        )
        metaCache[key] = detail
        return detail
    }

    suspend fun resolveArmIds(source: String, id: String): Pair<String?, String?> {
        val key = "$source:$id"
        armTmdbCache[key]?.let { return it }

        val url = if (source == "imdb") {
            "https://arm.haglund.dev/api/v2/imdb?id=${encode(id)}"
        } else {
            "https://arm.haglund.dev/api/v2/ids?source=${encode(source)}&id=${encode(id)}"
        }
        val raw = runCatching { get(url) }.getOrNull() ?: return null to null
        val obj = runCatching {
            val el = json.parseToJsonElement(raw)
            when (el) {
                is JsonObject -> el
                is JsonArray -> el.firstOrNull() as? JsonObject
                else -> null
            }
        }.getOrNull() ?: return null to null

        val tmdb = obj.primitiveStr("themoviedb")?.takeIf { it.all(Char::isDigit) }
        val imdb = obj.str("imdb")?.takeIf { it.startsWith("tt", ignoreCase = true) }
        val pair = tmdb to imdb
        armTmdbCache[key] = pair
        return pair
    }

    /**
     * Resolves numeric TMDB ID and IMDb ID (`tt...`) for any movie, series, or anime
     * regardless of which provider family (CloudStream, Nuvio, Stremio) opened the title.
     */
    suspend fun resolveExternalIds(
        title: String,
        year: Int? = null,
        mediaType: MediaType = MediaType.MOVIE,
        rawMediaUrl: String? = null,
        originDataUrl: String? = null,
        tmdbApiKey: String? = null
    ): ResolvedExternalIds {
        val isMovie = mediaType == MediaType.MOVIE
        val hints = listOfNotNull(originDataUrl, rawMediaUrl)

        var imdbId: String? = hints.firstNotNullOfOrNull { s ->
            Regex("(tt\\d{5,12})", RegexOption.IGNORE_CASE).find(s)?.groupValues?.get(1)
        }
        var tmdbId: String? = null

        for (s in hints) {
            when {
                s.startsWith("tmdb:", ignoreCase = true) -> {
                    val seg = s.removePrefix("tmdb:").substringBefore(":").trim()
                    if (seg.all(Char::isDigit) && seg.isNotEmpty()) tmdbId = seg
                }
                s.startsWith("tmdbmeta:", ignoreCase = true) -> {
                    val seg = s.split(":").getOrNull(2)?.trim()
                    if (!seg.isNullOrBlank() && seg.all(Char::isDigit)) tmdbId = seg
                }
                s.startsWith("cinemeta:", ignoreCase = true) -> {
                    val parts = s.split(":")
                    val seg = parts.getOrNull(2)?.trim()
                    if (!seg.isNullOrBlank() && seg.all(Char::isDigit)) tmdbId = seg
                }
                s.startsWith("anilist:", ignoreCase = true) -> {
                    val parts = s.split(":")
                    val aniId = parts.getOrNull(1)?.trim().orEmpty()
                    val hint = parts.getOrNull(2)?.trim()
                    if (!hint.isNullOrBlank() && hint.all(Char::isDigit)) tmdbId = hint
                    if ((tmdbId == null || imdbId == null) && aniId.isNotBlank()) {
                        val (armT, armI) = resolveArmIds("anilist", aniId)
                        if (tmdbId == null) tmdbId = armT
                        if (imdbId == null) imdbId = armI
                    }
                }
                s.startsWith("kitsu:", ignoreCase = true) -> {
                    val parts = s.split(":")
                    val kId = parts.getOrNull(1)?.removePrefix("kitsu:")?.trim().orEmpty()
                    val hint = parts.getOrNull(2)?.trim()
                    if (!hint.isNullOrBlank() && hint.all(Char::isDigit)) tmdbId = hint
                    if ((tmdbId == null || imdbId == null) && kId.isNotBlank()) {
                        val (armT, armI) = resolveArmIds("kitsu", kId)
                        if (tmdbId == null) tmdbId = armT
                        if (imdbId == null) imdbId = armI
                    }
                }
            }
            if (tmdbId != null && imdbId != null) break
        }

        val cleanTitle = cleanSearchTitle(title)
        val cacheKey = listOf(
            imdbId.orEmpty(),
            tmdbId.orEmpty(),
            cleanTitle.lowercase(Locale.US),
            year?.toString().orEmpty(),
            mediaType.name
        ).joinToString("|")
        externalIdCache[cacheKey]?.let { cached ->
            if (!cached.tmdbId.isNullOrBlank() && !cached.imdbId.isNullOrBlank()) return cached
        }

        // 1. If we don't have an IMDb ID yet, search Cinemeta & IMDb suggestion API
        if (imdbId == null && cleanTitle.isNotBlank()) {
            val cmMatch = findBestCinemetaMatch(cleanTitle, year, isMovie)
            if (cmMatch != null) {
                imdbId = cmMatch.id.takeIf { it.startsWith("tt", ignoreCase = true) }
                if (tmdbId == null) {
                    tmdbId = cmMatch.moviedbId?.takeIf { it.all(Char::isDigit) }
                }
            }
            if (imdbId == null) {
                imdbId = searchImdbSuggestionId(cleanTitle, year, isMovie)
            }
        }

        // 2. If we have an IMDb ID (or title) and still need numeric TMDB ID
        if (tmdbId == null && imdbId != null) {
            tmdbId = resolveTmdbIdForImdb(
                imdbId = imdbId,
                title = cleanTitle,
                year = year,
                isMovie = isMovie,
                tmdbApiKey = tmdbApiKey
            )
        }

        // 3. Fallback: direct TMDB search by title (+ year) without requiring an IMDb ID
        if (tmdbId == null && cleanTitle.isNotBlank()) {
            tmdbId = if (!tmdbApiKey.isNullOrBlank()) {
                searchTmdbApiByTitle(cleanTitle, year, isMovie, tmdbApiKey)
            } else {
                null
            } ?: searchTmdbWebByTitle(cleanTitle, year, isMovie)
        }

        // 4. If we have TMDB ID and still need IMDb ID (so Stremio addons work too)
        if (imdbId == null && tmdbId != null) {
            imdbId = resolveImdbIdForTmdb(
                tmdbId = tmdbId,
                title = cleanTitle,
                year = year,
                isMovie = isMovie,
                tmdbApiKey = tmdbApiKey
            )
        }

        val resolved = ResolvedExternalIds(tmdbId = tmdbId, imdbId = imdbId)
        if (tmdbId != null || imdbId != null) {
            externalIdCache[cacheKey] = resolved
        }
        return resolved
    }

    private suspend fun resolveTmdbIdForImdb(
        imdbId: String,
        title: String,
        year: Int?,
        isMovie: Boolean,
        tmdbApiKey: String?
    ): String? {
        if (!tmdbApiKey.isNullOrBlank()) {
            val apiHit = findTmdbIdByImdbApi(imdbId, isMovie, tmdbApiKey)
            if (!apiHit.isNullOrBlank()) return apiHit
        }

        // Check Cinemeta meta directly (often carries moviedb_id)
        val cmType = if (isMovie) "movie" else "series"
        val cmRaw = runCatching { get("$CINEMETA_BASE_URL/meta/$cmType/${encode(imdbId)}.json") }.getOrNull()
        if (!cmRaw.isNullOrBlank()) {
            val metaObj = runCatching {
                json.parseToJsonElement(cmRaw).jsonObject["meta"] as? JsonObject
            }.getOrNull()
            val cmTmdb = metaObj?.primitiveStr("moviedb_id")?.takeIf { it.all(Char::isDigit) }
            if (!cmTmdb.isNullOrBlank()) return cmTmdb
        }

        // Wikidata SPARQL lookup: P345 (IMDb) -> P4947 (TMDB movie) / P4983 (TMDB TV)
        val wikidataHit = queryWikidataTmdbByImdb(imdbId, isMovie)
        if (!wikidataHit.isNullOrBlank()) return wikidataHit

        // Anime fallback (ARM)
        val armHit = resolveArmIds("imdb", imdbId).first
        if (!armHit.isNullOrBlank()) return armHit

        // Public TMDB web search fallback (works without API key for all movies & TV shows)
        if (title.isNotBlank()) {
            return searchTmdbWebByTitle(title, year, isMovie)
        }
        return null
    }

    private suspend fun resolveImdbIdForTmdb(
        tmdbId: String,
        title: String,
        year: Int?,
        isMovie: Boolean,
        tmdbApiKey: String?
    ): String? {
        if (!tmdbApiKey.isNullOrBlank()) {
            val kind = if (isMovie) "movie" else "tv"
            val url = "https://api.themoviedb.org/3/$kind/${encode(tmdbId)}/external_ids?api_key=${encode(tmdbApiKey)}"
            val raw = runCatching { get(url) }.getOrNull()
            if (!raw.isNullOrBlank()) {
                val imdb = runCatching {
                    json.parseToJsonElement(raw).jsonObject.str("imdb_id")
                }.getOrNull()
                if (!imdb.isNullOrBlank() && imdb.startsWith("tt", ignoreCase = true)) return imdb
            }
        }
        val wikidataImdb = queryWikidataImdbByTmdb(tmdbId, isMovie)
        if (!wikidataImdb.isNullOrBlank()) return wikidataImdb
        if (title.isNotBlank()) {
            val cm = findBestCinemetaMatch(title, year, isMovie)
            if (cm != null && cm.id.startsWith("tt", ignoreCase = true)) return cm.id
            return searchImdbSuggestionId(title, year, isMovie)
        }
        return null
    }

    private suspend fun findTmdbIdByImdbApi(imdbId: String, isMovie: Boolean, apiKey: String): String? {
        val url = "https://api.themoviedb.org/3/find/${encode(imdbId)}?api_key=${encode(apiKey)}&external_source=imdb_id"
        val raw = runCatching { get(url) }.getOrNull() ?: return null
        val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
        val primaryKey = if (isMovie) "movie_results" else "tv_results"
        val fallbackKey = if (isMovie) "tv_results" else "movie_results"
        val firstObj = (root[primaryKey] as? JsonArray)?.firstOrNull() as? JsonObject
            ?: (root[fallbackKey] as? JsonArray)?.firstOrNull() as? JsonObject
            ?: return null
        return firstObj.primitiveStr("id")?.takeIf { it.all(Char::isDigit) }
    }

    private suspend fun searchTmdbApiByTitle(title: String, year: Int?, isMovie: Boolean, apiKey: String): String? {
        val kind = if (isMovie) "movie" else "tv"
        val yearParam = if (year != null) {
            if (isMovie) "&year=$year" else "&first_air_date_year=$year"
        } else ""
        val url = "https://api.themoviedb.org/3/search/$kind?api_key=${encode(apiKey)}&query=${encode(title)}$yearParam"
        val raw = runCatching { get(url) }.getOrNull() ?: return null
        val arr = runCatching { json.parseToJsonElement(raw).jsonObject["results"] as? JsonArray }.getOrNull()
            ?: return null
        val first = arr.firstOrNull() as? JsonObject ?: return null
        return first.primitiveStr("id")?.takeIf { it.all(Char::isDigit) }
    }

    private suspend fun searchTmdbWebByTitle(title: String, year: Int?, isMovie: Boolean): String? =
        withContext(Dispatchers.IO) {
            val kind = if (isMovie) "movie" else "tv"
            val q = if (year != null) "$title $year" else title
            val urls = listOf(
                "https://www.themoviedb.org/search/$kind?query=${URLEncoder.encode(title, "UTF-8")}",
                "https://www.themoviedb.org/search/$kind?query=${URLEncoder.encode(q, "UTF-8")}"
            ).distinct()
            val normTarget = normalizeTitle(title)
            for (url in urls) {
                val html = runCatching { getHtml(url) }.getOrNull() ?: continue
                val doc = runCatching { Jsoup.parse(html) }.getOrNull() ?: continue
                val cards = doc.select("div.card.v4, div.card")
                var bestId: String? = null
                var bestScore = -1
                for (card in cards) {
                    val link = card.selectFirst("a.result[href], a[href^=/$kind/]") ?: continue
                    val href = link.attr("href")
                    val idMatch = Regex("^/$kind/(\\d+)").find(href) ?: continue
                    val candidateId = idMatch.groupValues[1]
                    val candidateTitle = card.selectFirst("h2")?.text()?.trim()
                        ?: link.text().trim()
                    val releaseText = card.selectFirst("span.release_date")?.text()?.trim().orEmpty()
                    val candidateYear = Regex("(\\d{4})").find(releaseText)?.groupValues?.get(1)?.toIntOrNull()
                    val normCand = normalizeTitle(candidateTitle)
                    var score = 0
                    if (normCand == normTarget) score += 100
                    else if (normCand.contains(normTarget) || normTarget.contains(normCand)) score += 50
                    if (year != null && candidateYear != null) {
                        val diff = kotlin.math.abs(year - candidateYear)
                        if (diff == 0) score += 40 else if (diff == 1) score += 20
                    }
                    if (score > bestScore) {
                        bestScore = score
                        bestId = candidateId
                    }
                }
                if (bestId != null) return@withContext bestId
                // Fallback to first matching link anywhere in search results
                val anyLink = doc.selectFirst("a[href^=/$kind/]")?.attr("href")
                val anyId = anyLink?.let { Regex("^/$kind/(\\d+)").find(it)?.groupValues?.get(1) }
                if (anyId != null) return@withContext anyId
            }
            null
        }

    private suspend fun queryWikidataTmdbByImdb(imdbId: String, isMovie: Boolean): String? {
        val sparql = "SELECT ?m ?t WHERE { ?i wdt:P345 \"$imdbId\" . OPTIONAL { ?i wdt:P4947 ?m . } OPTIONAL { ?i wdt:P4983 ?t . } } LIMIT 1"
        val url = "https://query.wikidata.org/sparql?format=json&query=${URLEncoder.encode(sparql, "UTF-8")}"
        val raw = runCatching { get(url) }.getOrNull() ?: return null
        val bindings = runCatching {
            json.parseToJsonElement(raw).jsonObject["results"]?.jsonObject?.get("bindings") as? JsonArray
        }.getOrNull() ?: return null
        val row = bindings.firstOrNull() as? JsonObject ?: return null
        val movieVal = (row["m"] as? JsonObject)?.str("value")?.takeIf { it.all(Char::isDigit) }
        val tvVal = (row["t"] as? JsonObject)?.str("value")?.takeIf { it.all(Char::isDigit) }
        return if (isMovie) (movieVal ?: tvVal) else (tvVal ?: movieVal)
    }

    private suspend fun queryWikidataImdbByTmdb(tmdbId: String, isMovie: Boolean): String? {
        val prop = if (isMovie) "P4947" else "P4983"
        val sparql = "SELECT ?imdb WHERE { ?i wdt:$prop \"$tmdbId\" ; wdt:P345 ?imdb . } LIMIT 1"
        val url = "https://query.wikidata.org/sparql?format=json&query=${URLEncoder.encode(sparql, "UTF-8")}"
        val raw = runCatching { get(url) }.getOrNull() ?: return null
        val bindings = runCatching {
            json.parseToJsonElement(raw).jsonObject["results"]?.jsonObject?.get("bindings") as? JsonArray
        }.getOrNull() ?: return null
        val row = bindings.firstOrNull() as? JsonObject ?: return null
        return (row["imdb"] as? JsonObject)?.str("value")?.takeIf { it.startsWith("tt", ignoreCase = true) }
    }

    private suspend fun findBestCinemetaMatch(title: String, year: Int?, isMovie: Boolean): CinemetaCatalogItem? {
        val results = runCatching { search(title) }.getOrDefault(emptyList())
        if (results.isEmpty()) return null
        val normTarget = normalizeTitle(title)
        val expectedType = if (isMovie) "movie" else "series"
        return results.maxByOrNull { item ->
            var score = 0
            val normItem = normalizeTitle(item.name)
            if (normItem == normTarget) score += 100
            else if (normItem.contains(normTarget) || normTarget.contains(normItem)) score += 50
            if (item.type.equals(expectedType, ignoreCase = true)) score += 30
            val itemYear = item.releaseInfo?.take(4)?.toIntOrNull()
            if (year != null && itemYear != null) {
                val diff = kotlin.math.abs(year - itemYear)
                if (diff == 0) score += 40 else if (diff == 1) score += 15
            }
            score
        }
    }

    private suspend fun searchImdbSuggestionId(title: String, year: Int?, isMovie: Boolean): String? {
        val slug = title.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "_").trim('_')
        val firstChar = slug.firstOrNull()?.takeIf { it.isLetterOrDigit() } ?: 'x'
        val url = "https://v3.sg.media-imdb.com/suggestion/$firstChar/${URLEncoder.encode(slug, "UTF-8")}.json"
        val raw = runCatching { get(url) }.getOrNull() ?: return null
        val arr = runCatching { json.parseToJsonElement(raw).jsonObject["d"] as? JsonArray }.getOrNull()
            ?: return null
        val normTarget = normalizeTitle(title)
        var bestId: String? = null
        var bestScore = -1
        for (el in arr) {
            val o = el as? JsonObject ?: continue
            val id = o.str("id")?.takeIf { it.startsWith("tt", ignoreCase = true) } ?: continue
            val label = o.str("l").orEmpty()
            val qid = o.str("qid").orEmpty().lowercase(Locale.US)
            val y = o.intVal("y")
            var score = 0
            val normLabel = normalizeTitle(label)
            if (normLabel == normTarget) score += 100
            else if (normLabel.contains(normTarget) || normTarget.contains(normLabel)) score += 50
            if (isMovie && qid == "movie") score += 25
            if (!isMovie && (qid == "tvseries" || qid == "tvminiseries")) score += 25
            if (year != null && y != null) {
                val diff = kotlin.math.abs(year - y)
                if (diff == 0) score += 40 else if (diff == 1) score += 15
            }
            if (score > bestScore) {
                bestScore = score
                bestId = id
            }
        }
        return bestId
    }

    private suspend fun getHtml(url: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .get()
            .build()
        okHttpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code} from $url")
            resp.body?.string().orEmpty()
        }
    }

    private fun cleanSearchTitle(raw: String): String = raw
        .replace(Regex("\\(\\d{4}\\)"), " ")
        .replace(Regex("\\[[^\\]]*\\]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun normalizeTitle(raw: String): String = raw
        .lowercase(Locale.US)
        .replace(Regex("[^a-z0-9]"), "")

    private fun selectPoolForNiche(
        niche: NuvioNiche,
        supportedTypes: Set<MediaType>
    ): List<RowDescriptor> {
        val rawPool = when (niche) {
            NuvioNiche.ANIME -> ANIME_POOL
            NuvioNiche.BOLLYWOOD_HINDI,
            NuvioNiche.SOUTH_INDIAN -> INDIAN_POOL
            NuvioNiche.ASIAN_DRAMA -> ASIAN_DRAMA_POOL
            NuvioNiche.FRENCH,
            NuvioNiche.LATINO_SPANISH,
            NuvioNiche.PERSIAN,
            NuvioNiche.GENERAL -> GENERAL_POOL
        }
        return filterBySupportedTypes(rawPool, supportedTypes)
    }

    private fun filterBySupportedTypes(
        pool: List<RowDescriptor>,
        supportedTypes: Set<MediaType>
    ): List<RowDescriptor> {
        if (supportedTypes.isEmpty()) return pool
        val wantsMovie = MediaType.MOVIE in supportedTypes
        val wantsSeries = MediaType.TV_SERIES in supportedTypes || MediaType.ANIME in supportedTypes
        val filtered = pool.filter { row ->
            when (row.mediaType) {
                MediaType.MOVIE -> wantsMovie
                MediaType.TV_SERIES -> wantsSeries
                MediaType.ANIME -> wantsSeries || MediaType.ANIME in supportedTypes
                else -> true
            }
        }
        return filtered.ifEmpty { pool }
    }

    private fun rotatedWindow(
        pool: List<RowDescriptor>,
        count: Int,
        offset: Int
    ): List<RowDescriptor> {
        if (pool.isEmpty() || count <= 0) return emptyList()
        val n = pool.size
        val start = ((offset % n) + n) % n
        return List(count.coerceAtMost(n)) { idx ->
            pool[(start + idx) % n]
        }
    }

    private suspend fun fetchRowDescriptors(
        descriptors: List<RowDescriptor>,
        page: Int
    ): List<CinemetaCatalogSection> = coroutineScope {
        descriptors.map { desc ->
            async {
                val items = runCatching { fetchDescriptorItems(desc, page) }.getOrDefault(emptyList())
                CinemetaCatalogSection(
                    id = desc.id,
                    title = desc.title,
                    items = items
                )
            }
        }.awaitAll().filter { it.items.isNotEmpty() }
    }

    private suspend fun fetchDescriptorItems(
        desc: RowDescriptor,
        page: Int
    ): List<CinemetaCatalogItem> {
        val skip = (page - 1) * 20
        return when (desc.source) {
            FeedSource.CINEMETA -> {
                val url = buildCinemetaCatalogUrl(CINEMETA_BASE_URL, desc.catalogType, desc.catalogId, desc.genre, skip)
                fetchCachedStremioEndpoint(url, "cinemeta")
            }
            FeedSource.INDIASTREAMS -> {
                if (page > 1) return emptyList()
                val url = buildCinemetaCatalogUrl(INDIASTREAMS_BASE_URL, desc.catalogType, desc.catalogId, null, 0)
                fetchCachedStremioEndpoint(url, "cinemeta")
            }
            FeedSource.KITSU -> {
                val url = buildCinemetaCatalogUrl(KITSU_BASE_URL, "anime", desc.catalogId, desc.genre, skip)
                fetchCachedStremioEndpoint(url, "kitsu")
            }
            FeedSource.ANILIST_TRENDING -> fetchAniListCatalog("TRENDING_DESC", null, null, page)
            FeedSource.ANILIST_POPULAR -> fetchAniListCatalog("POPULARITY_DESC", null, null, page)
            FeedSource.ANILIST_SCORE -> fetchAniListCatalog("SCORE_DESC", null, null, page)
            FeedSource.ANILIST_AIRING -> fetchAniListCatalog("POPULARITY_DESC", "RELEASING", null, page)
            FeedSource.ANILIST_NEW -> fetchAniListCatalog("START_DATE_DESC", null, null, page)
            FeedSource.ANILIST_MOVIE_POPULAR -> fetchAniListCatalog("POPULARITY_DESC", null, "MOVIE", page)
            FeedSource.ANILIST_MOVIE_SCORE -> fetchAniListCatalog("SCORE_DESC", null, "MOVIE", page)
        }
    }

    private fun buildCinemetaCatalogUrl(
        base: String,
        type: String,
        catalogId: String,
        genre: String?,
        skip: Int
    ): String {
        val t = encode(type)
        val c = encode(catalogId)
        val extras = buildList {
            if (!genre.isNullOrBlank()) add("genre=${encode(genre)}")
            if (skip > 0) add("skip=$skip")
        }
        return if (extras.isEmpty()) {
            "$base/catalog/$t/$c.json"
        } else {
            "$base/catalog/$t/$c/${extras.joinToString("&")}.json"
        }
    }

    private suspend fun fetchCachedStremioEndpoint(
        url: String,
        sourceKind: String
    ): List<CinemetaCatalogItem> {
        val cached = endpointCache[url]
        if (cached != null && System.currentTimeMillis() - cached.fetchedAtMs < CACHE_TTL_MS) {
            return cached.items
        }
        val items = fetchStremioCatalogEndpoint(url, sourceKind)
        if (items.isNotEmpty()) {
            endpointCache[url] = CachedEndpoint(System.currentTimeMillis(), items)
        }
        return items
    }

    private suspend fun fetchStremioCatalogEndpoint(
        url: String,
        sourceKind: String
    ): List<CinemetaCatalogItem> {
        val raw = get(url)
        val root = json.parseToJsonElement(raw).jsonObject
        val metas = root["metas"] as? JsonArray ?: return emptyList()
        return metas.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val id = obj.str("id") ?: obj.str("imdb_id") ?: return@mapNotNull null
            val name = obj.str("name") ?: return@mapNotNull null
            val rawType = obj.str("type") ?: "movie"
            val imdbFallbackPoster = if (id.startsWith("tt", ignoreCase = true)) {
                "https://images.metahub.space/poster/medium/$id/img"
            } else null
            val resolvedSource = when {
                id.startsWith("kitsu:", ignoreCase = true) -> "kitsu"
                else -> sourceKind
            }
            CinemetaCatalogItem(
                id = id,
                type = rawType,
                name = name,
                poster = obj.str("poster") ?: imdbFallbackPoster,
                releaseInfo = obj.str("releaseInfo") ?: obj.primitiveStr("year"),
                imdbRating = obj.primitiveStr("imdbRating"),
                moviedbId = obj.primitiveStr("moviedb_id"),
                sourceKind = resolvedSource
            )
        }
    }

    private suspend fun fetchAniListCatalog(
        sort: String,
        status: String?,
        format: String?,
        page: Int
    ): List<CinemetaCatalogItem> {
        val cacheKey = "anilist:$sort:$status:$format:$page"
        endpointCache[cacheKey]?.let { cached ->
            if (System.currentTimeMillis() - cached.fetchedAtMs < CACHE_TTL_MS) return cached.items
        }
        val filters = buildList {
            add("type: ANIME")
            add("isAdult: false")
            add("sort: $sort")
            if (status != null) add("status: $status")
            if (format != null) add("format: $format")
        }.joinToString(", ")

        val query = """
            {
              Page(page: $page, perPage: 20) {
                media($filters) {
                  id
                  format
                  startDate { year }
                  averageScore
                  title { english romaji }
                  coverImage { large }
                }
              }
            }
        """.trimIndent()

        val items = parseAniListPageResponse(postGraphQl(ANILIST_URL, query))
        if (items.isNotEmpty()) {
            endpointCache[cacheKey] = CachedEndpoint(System.currentTimeMillis(), items)
        }
        return items
    }

    private suspend fun searchAniList(query: String, page: Int): List<CinemetaCatalogItem> {
        val safeSearch = query.replace("\\", "\\\\").replace("\"", "\\\"")
        val gql = """
            {
              Page(page: $page, perPage: 20) {
                media(type: ANIME, isAdult: false, search: "$safeSearch") {
                  id
                  format
                  startDate { year }
                  averageScore
                  title { english romaji }
                  coverImage { large }
                }
              }
            }
        """.trimIndent()
        return parseAniListPageResponse(postGraphQl(ANILIST_URL, gql))
    }

    private suspend fun searchKitsu(query: String): List<CinemetaCatalogItem> {
        val url = "$KITSU_BASE_URL/catalog/anime/kitsu-anime-trending/search=${encode(query)}.json"
        return fetchStremioCatalogEndpoint(url, "kitsu")
    }

    private fun parseAniListPageResponse(raw: String): List<CinemetaCatalogItem> {
        val root = json.parseToJsonElement(raw).jsonObject
        val mediaArr = root["data"]?.jsonObject?.get("Page")?.jsonObject?.get("media") as? JsonArray
            ?: return emptyList()
        return mediaArr.mapNotNull { el ->
            val m = el as? JsonObject ?: return@mapNotNull null
            val id = m.primitiveStr("id") ?: return@mapNotNull null
            val titleObj = m["title"] as? JsonObject
            val title = titleObj?.str("english") ?: titleObj?.str("romaji") ?: return@mapNotNull null
            val format = m.str("format").orEmpty()
            val mediaType = if (format.equals("MOVIE", ignoreCase = true)) "movie" else "series"
            val year = (m["startDate"] as? JsonObject)?.primitiveStr("year")
            val score = m.intVal("averageScore")?.let { String.format(Locale.US, "%.1f", it / 10.0) }
            val poster = (m["coverImage"] as? JsonObject)?.str("large")
            CinemetaCatalogItem(
                id = id,
                type = mediaType,
                name = title,
                poster = poster,
                releaseInfo = year,
                imdbRating = score,
                moviedbId = null,
                sourceKind = "anilist"
            )
        }
    }

    private suspend fun fetchTmdbNicheCatalogs(
        profile: NuvioCatalogProfile,
        niche: NuvioNiche,
        page: Int,
        apiKey: String
    ): List<CinemetaCatalogSection> = coroutineScope {
        val specs = when (niche) {
            NuvioNiche.ANIME -> listOf(
                Triple("Popular Anime", "tv", "/discover/tv?with_genres=16&with_origin_country=JP&sort_by=popularity.desc"),
                Triple("Top Rated Anime", "tv", "/discover/tv?with_genres=16&with_origin_country=JP&sort_by=vote_average.desc&vote_count.gte=100"),
                Triple("Popular Anime Movies", "movie", "/discover/movie?with_genres=16&with_origin_country=JP&sort_by=popularity.desc")
            )
            NuvioNiche.BOLLYWOOD_HINDI -> listOf(
                Triple("Popular Hindi Movies", "movie", "/discover/movie?with_original_language=hi&sort_by=popularity.desc"),
                Triple("Popular Hindi Series", "tv", "/discover/tv?with_original_language=hi&sort_by=popularity.desc"),
                Triple("Top Rated Hindi Movies", "movie", "/discover/movie?with_original_language=hi&sort_by=vote_average.desc&vote_count.gte=50")
            )
            NuvioNiche.SOUTH_INDIAN -> listOf(
                Triple("Popular Tamil Movies", "movie", "/discover/movie?with_original_language=ta&sort_by=popularity.desc"),
                Triple("Popular Telugu Movies", "movie", "/discover/movie?with_original_language=te&sort_by=popularity.desc"),
                Triple("Popular Malayalam Movies", "movie", "/discover/movie?with_original_language=ml&sort_by=popularity.desc")
            )
            NuvioNiche.ASIAN_DRAMA -> listOf(
                Triple("Popular Korean Dramas", "tv", "/discover/tv?with_original_language=ko&sort_by=popularity.desc"),
                Triple("Top Rated Korean Dramas", "tv", "/discover/tv?with_original_language=ko&sort_by=vote_average.desc&vote_count.gte=100"),
                Triple("Popular Korean Movies", "movie", "/discover/movie?with_original_language=ko&sort_by=popularity.desc")
            )
            NuvioNiche.FRENCH -> listOf(
                Triple("Popular French Movies", "movie", "/discover/movie?with_original_language=fr&sort_by=popularity.desc"),
                Triple("Popular French Series", "tv", "/discover/tv?with_original_language=fr&sort_by=popularity.desc")
            )
            NuvioNiche.LATINO_SPANISH -> listOf(
                Triple("Popular Spanish Movies", "movie", "/discover/movie?with_original_language=es&sort_by=popularity.desc"),
                Triple("Popular Spanish Series", "tv", "/discover/tv?with_original_language=es&sort_by=popularity.desc")
            )
            NuvioNiche.PERSIAN -> listOf(
                Triple("Popular Persian Movies", "movie", "/discover/movie?with_original_language=fa&sort_by=popularity.desc"),
                Triple("Popular Persian Series", "tv", "/discover/tv?with_original_language=fa&sort_by=popularity.desc")
            )
            NuvioNiche.GENERAL -> listOf(
                Triple("Trending", "movie", "/trending/movie/week?"),
                Triple("Popular Movies", "movie", "/movie/popular?"),
                Triple("Popular Series", "tv", "/tv/popular?"),
                Triple("Top Rated Movies", "movie", "/movie/top_rated?"),
                Triple("Top Rated Series", "tv", "/tv/top_rated?")
            )
        }
        val filtered = specs.filter { (_, kind, _) ->
            profile.supportedTypes.isEmpty() ||
                (kind == "movie" && MediaType.MOVIE in profile.supportedTypes) ||
                (kind == "tv" && (MediaType.TV_SERIES in profile.supportedTypes || MediaType.ANIME in profile.supportedTypes))
        }.ifEmpty { specs }

        filtered.map { (title, kind, path) ->
            async {
                val sep = if ('?' in path && !path.endsWith('?')) "&" else if (path.endsWith('?')) "" else "?"
                val url = "https://api.themoviedb.org/3$path${sep}api_key=${encode(apiKey)}&page=$page"
                val raw = runCatching { get(url) }.getOrNull() ?: return@async null
                val arr = runCatching { json.parseToJsonElement(raw).jsonObject["results"] as? JsonArray }.getOrNull()
                    ?: return@async null
                val items = arr.mapNotNull { el ->
                    val o = el as? JsonObject ?: return@mapNotNull null
                    val id = o.primitiveStr("id") ?: return@mapNotNull null
                    val itemTitle = o.str("title") ?: o.str("name") ?: return@mapNotNull null
                    val poster = o.str("poster_path")?.let { "https://image.tmdb.org/t/p/w500$it" }
                    val year = (o.str("release_date") ?: o.str("first_air_date"))?.take(4)
                    val rating = (o["vote_average"] as? JsonPrimitive)?.doubleOrNull?.let { String.format(Locale.US, "%.1f", it) }
                    CinemetaCatalogItem(
                        id = id,
                        type = if (kind == "movie") "movie" else "series",
                        name = itemTitle,
                        poster = poster,
                        releaseInfo = year,
                        imdbRating = rating,
                        moviedbId = id,
                        sourceKind = "tmdb"
                    )
                }
                if (items.isEmpty()) null else CinemetaCatalogSection("tmdb:$title", title, items)
            }
        }.awaitAll().filterNotNull()
    }

    suspend fun fetchTmdbMeta(type: String, tmdbId: String, apiKey: String): CinemetaMetaDetail? {
        val kind = if (type.equals("movie", ignoreCase = true)) "movie" else "tv"
        val url = "https://api.themoviedb.org/3/$kind/${encode(tmdbId)}?api_key=${encode(apiKey)}&append_to_response=external_ids"
        val raw = runCatching { get(url) }.getOrNull() ?: return null
        val obj = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
        val imdbId = (obj["external_ids"] as? JsonObject)?.str("imdb_id")
        if (!imdbId.isNullOrBlank()) {
            val cm = fetchMeta(if (kind == "movie") "movie" else "series", imdbId)
            if (cm != null) return cm.copy(moviedbId = tmdbId)
        }
        val title = obj.str("title") ?: obj.str("name") ?: return null
        return CinemetaMetaDetail(
            id = tmdbId,
            moviedbId = tmdbId,
            type = if (kind == "movie") "movie" else "series",
            name = title,
            poster = obj.str("poster_path")?.let { "https://image.tmdb.org/t/p/w500$it" },
            background = obj.str("backdrop_path")?.let { "https://image.tmdb.org/t/p/w1280$it" },
            logo = null,
            description = obj.str("overview"),
            releaseInfo = (obj.str("release_date") ?: obj.str("first_air_date"))?.take(4),
            runtime = obj.intVal("runtime")?.toString(),
            imdbRating = (obj["vote_average"] as? JsonPrimitive)?.doubleOrNull?.let { String.format(Locale.US, "%.1f", it) },
            genres = (obj["genres"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.str("name") },
            cast = emptyList(),
            videos = emptyList()
        )
    }

    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json, text/plain, */*")
            .get()
            .build()
        okHttpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code} from $url")
            resp.body?.string().orEmpty()
        }
    }

    private suspend fun postGraphQl(url: String, query: String): String = withContext(Dispatchers.IO) {
        val payload = JsonObject(mapOf("query" to JsonPrimitive(query)))
        val body = json.encodeToString(JsonObject.serializer(), payload)
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .post(body)
            .build()
        okHttpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code} from $url")
            resp.body?.string().orEmpty()
        }
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonObject.primitiveStr(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }

    private fun JsonObject.intVal(key: String): Int? =
        (this[key] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.strList(key: String): List<String> =
        (this[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

    companion object {
        private const val CINEMETA_BASE_URL = "https://v3-cinemeta.strem.io"
        private const val INDIASTREAMS_BASE_URL = "https://indiastreams.rdata.in"
        private const val KITSU_BASE_URL = "https://anime-kitsu.strem.fun"
        private const val ANILIST_URL = "https://graphql.anilist.co"
        private const val CACHE_TTL_MS = 5 * 60 * 1000L
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

        private val ANIME_KEYWORDS = listOf(
            "anime", "hianime", "anidb", "anikoto", "allanime", "animekai", "animepahe",
            "animelok", "animesdigital", "allwish", "all-wish", "toonhub", "kurage",
            "cartoon", "otaku", "donghua"
        )
        private val HINDI_KEYWORDS = listOf(
            "desi", "hindi", "hindmoviez", "bollywood", "hdmovie2", "flixindia",
            "moviesdrive", "movieblast", "movies4u", "allmovieland", "vegamovies",
            "hdhub", "4khdhub", "einthusan", "moonflix", "ctgmovies", "hdghar",
            "movieshunt", "zinkmovie"
        )
        private val SOUTH_INDIAN_KEYWORDS = listOf(
            "tamil", "tamilblasters", "tamilian", "tamilmv", "isaidub", "moviesda",
            "telugu", "malayalam", "kannada"
        )
        private val KDRAMA_KEYWORDS = listOf(
            "dramafull", "kdrama", "kisskh", "dramacool", "dorama", "asian drama"
        )
        private val FRENCH_KEYWORDS = listOf(
            "movix", "nakios", "purstream", "wiflix", "vostfr", "frenchstream",
            "streamingvf", "papadustream", "french"
        )
        private val LATINO_KEYWORDS = listOf(
            "latino", "cuevana", "pelis", "cinecalidad"
        )

        private val ANIME_POOL = listOf(
            RowDescriptor("anime_trending", MediaType.ANIME, "Trending Anime", FeedSource.ANILIST_TRENDING),
            RowDescriptor("anime_popular", MediaType.ANIME, "Popular Anime", FeedSource.ANILIST_POPULAR),
            RowDescriptor("anime_score", MediaType.ANIME, "Top Rated Anime", FeedSource.ANILIST_SCORE),
            RowDescriptor("anime_airing", MediaType.ANIME, "Top Airing Anime", FeedSource.ANILIST_AIRING),
            RowDescriptor("anime_new", MediaType.ANIME, "New Anime", FeedSource.ANILIST_NEW),
            RowDescriptor("anime_mov_pop", MediaType.MOVIE, "Popular Anime Movies", FeedSource.ANILIST_MOVIE_POPULAR),
            RowDescriptor("anime_mov_top", MediaType.MOVIE, "Top Rated Anime Movies", FeedSource.ANILIST_MOVIE_SCORE),
            RowDescriptor("kitsu_trending", MediaType.ANIME, "Kitsu Trending Anime", FeedSource.KITSU, "anime", "kitsu-anime-trending"),
            RowDescriptor("kitsu_airing", MediaType.ANIME, "Kitsu Top Airing", FeedSource.KITSU, "anime", "kitsu-anime-airing")
        )

        private val INDIAN_POOL = listOf(
            RowDescriptor("in_trending_mov", MediaType.MOVIE, "Trending Movies in India", FeedSource.INDIASTREAMS, "movie", "trendingmovies"),
            RowDescriptor("in_trending_tv", MediaType.TV_SERIES, "Trending Series in India", FeedSource.INDIASTREAMS, "series", "trendingtv"),
            RowDescriptor("in_hstzee_mov", MediaType.MOVIE, "Hotstar, SonyLiv & Zee5 Movies", FeedSource.INDIASTREAMS, "movie", "hstzee"),
            RowDescriptor("in_hstzee_tv", MediaType.TV_SERIES, "Hotstar, SonyLiv & Zee5 Series", FeedSource.INDIASTREAMS, "series", "hstzeetv"),
            RowDescriptor("in_nfxprm_mov", MediaType.MOVIE, "Popular on Netflix & Prime", FeedSource.INDIASTREAMS, "movie", "nfxprm"),
            RowDescriptor("in_nfxprm_tv", MediaType.TV_SERIES, "Series on Netflix & Prime", FeedSource.INDIASTREAMS, "series", "nfxprmtv"),
            RowDescriptor("in_rec_mov", MediaType.MOVIE, "Recommended Indian Movies", FeedSource.INDIASTREAMS, "movie", "recmov"),
            RowDescriptor("in_rec_tv", MediaType.TV_SERIES, "Recommended Indian Series", FeedSource.INDIASTREAMS, "series", "atpmub")
        )

        private val ASIAN_DRAMA_POOL = listOf(
            RowDescriptor("cm_series_drama", MediaType.TV_SERIES, "Drama Series", FeedSource.CINEMETA, "series", "top", "Drama"),
            RowDescriptor("cm_series_romance", MediaType.TV_SERIES, "Romance Series", FeedSource.CINEMETA, "series", "top", "Romance"),
            RowDescriptor("cm_series_mystery", MediaType.TV_SERIES, "Mystery & Thriller Series", FeedSource.CINEMETA, "series", "top", "Mystery"),
            RowDescriptor("cm_series_new", MediaType.TV_SERIES, "New Series", FeedSource.CINEMETA, "series", "year"),
            RowDescriptor("cm_movie_drama", MediaType.MOVIE, "Drama Movies", FeedSource.CINEMETA, "movie", "top", "Drama"),
            RowDescriptor("cm_movie_romance", MediaType.MOVIE, "Romance Movies", FeedSource.CINEMETA, "movie", "top", "Romance"),
            RowDescriptor("cm_series_top", MediaType.TV_SERIES, "Popular Series", FeedSource.CINEMETA, "series", "top"),
            RowDescriptor("cm_series_rated", MediaType.TV_SERIES, "Top Rated Series", FeedSource.CINEMETA, "series", "imdbRating")
        )

        private val GENERAL_POOL = listOf(
            RowDescriptor("cm_mov_top", MediaType.MOVIE, "Popular Movies", FeedSource.CINEMETA, "movie", "top"),
            RowDescriptor("cm_tv_top", MediaType.TV_SERIES, "Popular Series", FeedSource.CINEMETA, "series", "top"),
            RowDescriptor("cm_mov_rated", MediaType.MOVIE, "Top Rated Movies", FeedSource.CINEMETA, "movie", "imdbRating"),
            RowDescriptor("cm_tv_rated", MediaType.TV_SERIES, "Top Rated Series", FeedSource.CINEMETA, "series", "imdbRating"),
            RowDescriptor("cm_mov_new", MediaType.MOVIE, "New Movies", FeedSource.CINEMETA, "movie", "year"),
            RowDescriptor("cm_tv_new", MediaType.TV_SERIES, "New Series", FeedSource.CINEMETA, "series", "year"),
            RowDescriptor("cm_mov_action", MediaType.MOVIE, "Action Movies", FeedSource.CINEMETA, "movie", "top", "Action"),
            RowDescriptor("cm_tv_action", MediaType.TV_SERIES, "Action Series", FeedSource.CINEMETA, "series", "top", "Action"),
            RowDescriptor("cm_mov_scifi", MediaType.MOVIE, "Sci-Fi Movies", FeedSource.CINEMETA, "movie", "top", "Sci-Fi"),
            RowDescriptor("cm_tv_scifi", MediaType.TV_SERIES, "Sci-Fi Series", FeedSource.CINEMETA, "series", "top", "Sci-Fi"),
            RowDescriptor("cm_mov_thriller", MediaType.MOVIE, "Thriller Movies", FeedSource.CINEMETA, "movie", "top", "Thriller"),
            RowDescriptor("cm_tv_crime", MediaType.TV_SERIES, "Crime Series", FeedSource.CINEMETA, "series", "top", "Crime"),
            RowDescriptor("cm_mov_comedy", MediaType.MOVIE, "Comedy Movies", FeedSource.CINEMETA, "movie", "top", "Comedy"),
            RowDescriptor("cm_tv_drama", MediaType.TV_SERIES, "Drama Series", FeedSource.CINEMETA, "series", "top", "Drama"),
            RowDescriptor("cm_mov_adventure", MediaType.MOVIE, "Adventure Movies", FeedSource.CINEMETA, "movie", "top", "Adventure"),
            RowDescriptor("cm_mov_horror", MediaType.MOVIE, "Horror Movies", FeedSource.CINEMETA, "movie", "top", "Horror")
        )
    }
}
