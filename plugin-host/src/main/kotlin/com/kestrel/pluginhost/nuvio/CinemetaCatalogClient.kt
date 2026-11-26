// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.pluginhost.nuvio

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
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
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
    val moviedbId: String?
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

/**
 * Shared metadata & catalog client for Nuvio JS scrapers backed by Cinemeta,
 * resolving TMDB IDs (moviedb_id) and episodes without requiring user API keys.
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

    @Volatile
    private var cachedCatalogs: CachedCatalogs? = null
    private val metaCache = ConcurrentHashMap<String, CinemetaMetaDetail>()

    suspend fun fetchHomeCatalogs(): List<CinemetaCatalogSection> {
        val cached = cachedCatalogs
        if (cached != null && System.currentTimeMillis() - cached.fetchedAtMs < CACHE_TTL_MS && cached.sections.isNotEmpty()) {
            return cached.sections
        }

        val descriptors = listOf(
            Triple("movie", "top", "Popular Movies"),
            Triple("series", "top", "Popular Series"),
            Triple("movie", "imdbRating", "Top Rated Movies"),
            Triple("series", "imdbRating", "Top Rated Series")
        )

        val sections = coroutineScope {
            descriptors.map { (type, catalogId, title) ->
                async {
                    val items = runCatching {
                        fetchCatalogEndpoint("$BASE_URL/catalog/$type/$catalogId.json")
                    }.getOrDefault(emptyList())
                    CinemetaCatalogSection(id = "$type:$catalogId", title = title, items = items)
                }
            }.awaitAll().filter { it.items.isNotEmpty() }
        }

        if (sections.isNotEmpty()) {
            cachedCatalogs = CachedCatalogs(System.currentTimeMillis(), sections)
        }
        return sections
    }

    suspend fun search(query: String): List<CinemetaCatalogItem> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        val encoded = URLEncoder.encode(trimmed, "UTF-8").replace("+", "%20")
        return coroutineScope {
            listOf("movie", "series").map { type ->
                async {
                    runCatching {
                        fetchCatalogEndpoint("$BASE_URL/catalog/$type/top/search=$encoded.json")
                    }.getOrDefault(emptyList())
                }
            }.awaitAll().flatten().distinctBy { "${it.type}:${it.id}" }
        }
    }

    suspend fun fetchMeta(type: String, id: String): CinemetaMetaDetail? {
        val key = "$type:$id"
        metaCache[key]?.let { return it }

        val encodedType = URLEncoder.encode(type, "UTF-8").replace("+", "%20")
        val encodedId = URLEncoder.encode(id, "UTF-8").replace("+", "%20")
        val raw = get("$BASE_URL/meta/$encodedType/$encodedId.json")
        val root = json.parseToJsonElement(raw).jsonObject
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

        val detail = CinemetaMetaDetail(
            id = metaObj.str("id") ?: id,
            moviedbId = metaObj.primitiveStr("moviedb_id"),
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

    private suspend fun fetchCatalogEndpoint(url: String): List<CinemetaCatalogItem> {
        val raw = get(url)
        val root = json.parseToJsonElement(raw).jsonObject
        val metas = root["metas"] as? JsonArray ?: return emptyList()
        return metas.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val id = obj.str("id") ?: return@mapNotNull null
            val name = obj.str("name") ?: return@mapNotNull null
            CinemetaCatalogItem(
                id = id,
                type = obj.str("type") ?: "movie",
                name = name,
                poster = obj.str("poster"),
                releaseInfo = obj.str("releaseInfo") ?: obj.primitiveStr("year"),
                imdbRating = obj.primitiveStr("imdbRating"),
                moviedbId = obj.primitiveStr("moviedb_id")
            )
        }
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

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonObject.primitiveStr(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }

    private fun JsonObject.intVal(key: String): Int? =
        (this[key] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.strList(key: String): List<String> =
        (this[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

    companion object {
        private const val BASE_URL = "https://v3-cinemeta.strem.io"
        private const val CACHE_TTL_MS = 5 * 60 * 1000L
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
    }
}
