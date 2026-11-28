@file:Suppress("unused")

package eu.kanade.tachiyomi.animesource.online

import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.get
import java.net.URI
import java.security.MessageDigest

abstract class AnimeHttpSource : AnimeCatalogueSource {

    protected val network: NetworkHelper by lazy { Injekt.get() }

    abstract val baseUrl: String

    open fun getHomeUrl(): String = baseUrl

    open val versionId: Int = 1

    override val id: Long by lazy {
        val key = "${name.lowercase()}/$lang/$versionId"
        val bytes = MessageDigest.getInstance("MD5").digest(key.toByteArray())
        (0..7).map { bytes[it].toLong() and 0xff shl 8 * (7 - it) }
            .reduce(Long::or) and Long.MAX_VALUE
    }

    val headers: Headers by lazy { headersBuilder().build() }

    open val client: OkHttpClient
        get() = network.client

    protected open fun headersBuilder(): Headers.Builder = Headers.Builder().apply {
        add(
            "User-Agent",
            "Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
        )
    }

    override fun toString(): String = "$name (${lang.uppercase()})"

    private fun isOverridden(methodName: String, vararg params: Class<*>): Boolean {
        return runCatching {
            val method = javaClass.getMethod(methodName, *params)
            method.declaringClass != AnimeHttpSource::class.java
        }.getOrDefault(false)
    }

    private fun isProtectedOverridden(methodName: String, vararg params: Class<*>): Boolean {
        var cls: Class<*>? = javaClass
        while (cls != null && cls != AnimeHttpSource::class.java) {
            val found = runCatching { cls!!.getDeclaredMethod(methodName, *params) }.isSuccess
            if (found) return true
            cls = cls.superclass
        }
        return false
    }

    override suspend fun getPopularAnime(page: Int): AnimesPage = withContext(Dispatchers.IO) {
        if (!isProtectedOverridden("popularAnimeRequest", Int::class.javaPrimitiveType!!) &&
            isOverridden("fetchPopularAnime", Int::class.javaPrimitiveType!!)
        ) {
            @Suppress("DEPRECATION")
            return@withContext fetchPopularAnime(page).toBlocking().first()
        }
        val req = popularAnimeRequest(page)
        val resp = client.newCall(req).awaitSuccess()
        popularAnimeParse(resp)
    }

    override suspend fun getLatestUpdates(page: Int): AnimesPage = withContext(Dispatchers.IO) {
        if (!isProtectedOverridden("latestUpdatesRequest", Int::class.javaPrimitiveType!!) &&
            isOverridden("fetchLatestUpdates", Int::class.javaPrimitiveType!!)
        ) {
            @Suppress("DEPRECATION")
            return@withContext fetchLatestUpdates(page).toBlocking().first()
        }
        val req = latestUpdatesRequest(page)
        val resp = client.newCall(req).awaitSuccess()
        latestUpdatesParse(resp)
    }

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage =
        withContext(Dispatchers.IO) {
            if (!isProtectedOverridden(
                    "searchAnimeRequest",
                    Int::class.javaPrimitiveType!!,
                    String::class.java,
                    AnimeFilterList::class.java
                ) &&
                isOverridden(
                    "fetchSearchAnime",
                    Int::class.javaPrimitiveType!!,
                    String::class.java,
                    AnimeFilterList::class.java
                )
            ) {
                @Suppress("DEPRECATION")
                return@withContext fetchSearchAnime(page, query, filters).toBlocking().first()
            }
            val req = searchAnimeRequest(page, query, filters)
            val resp = client.newCall(req).awaitSuccess()
            searchAnimeParse(resp)
        }

    override suspend fun getAnimeDetails(anime: SAnime): SAnime = withContext(Dispatchers.IO) {
        if (!isOverridden("animeDetailsRequest", SAnime::class.java) &&
            !isProtectedOverridden("animeDetailsParse", Response::class.java) &&
            isOverridden("fetchAnimeDetails", SAnime::class.java)
        ) {
            @Suppress("DEPRECATION")
            return@withContext fetchAnimeDetails(anime).toBlocking().first().also { parsed ->
                if (parsed.url.isBlank()) parsed.url = anime.url
                if (parsed.title.isBlank()) parsed.title = anime.title
                if (parsed.thumbnail_url.isNullOrBlank()) parsed.thumbnail_url = anime.thumbnail_url
                parsed.initialized = true
            }
        }
        val resp = client.newCall(animeDetailsRequest(anime)).awaitSuccess()
        animeDetailsParse(resp).also { parsed ->
            if (parsed.url.isBlank()) parsed.url = anime.url
            if (parsed.title.isBlank()) parsed.title = anime.title
            if (parsed.thumbnail_url.isNullOrBlank()) parsed.thumbnail_url = anime.thumbnail_url
            parsed.initialized = true
        }
    }

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> = withContext(Dispatchers.IO) {
        if (!isOverridden("episodeListRequest", SAnime::class.java) &&
            !isProtectedOverridden("episodeListParse", Response::class.java) &&
            isOverridden("fetchEpisodeList", SAnime::class.java)
        ) {
            @Suppress("DEPRECATION")
            return@withContext fetchEpisodeList(anime).toBlocking().first()
        }
        val resp = client.newCall(episodeListRequest(anime)).awaitSuccess()
        episodeListParse(resp)
    }

    override suspend fun getVideoList(episode: SEpisode): List<Video> = withContext(Dispatchers.IO) {
        if (!isOverridden("videoListRequest", SEpisode::class.java) &&
            !isProtectedOverridden("videoListParse", Response::class.java) &&
            isOverridden("fetchVideoList", SEpisode::class.java)
        ) {
            @Suppress("DEPRECATION")
            return@withContext fetchVideoList(episode).toBlocking().first()
        }
        val resp = client.newCall(videoListRequest(episode)).awaitSuccess()
        videoListParse(resp)
    }

    open suspend fun getVideoUrl(video: Video): String = withContext(Dispatchers.IO) {
        val existing = video.videoUrl ?: video.url
        if (existing.isNotBlank()) return@withContext existing
        val req = videoUrlRequest(video)
        val resp = client.newCall(req).awaitSuccess()
        videoUrlParse(resp)
    }

    @Suppress("DEPRECATION")
    override fun fetchPopularAnime(page: Int): Observable<AnimesPage> =
        Observable.fromCallable { kotlinx.coroutines.runBlocking { getPopularAnime(page) } }

    @Suppress("DEPRECATION")
    override fun fetchLatestUpdates(page: Int): Observable<AnimesPage> =
        Observable.fromCallable { kotlinx.coroutines.runBlocking { getLatestUpdates(page) } }

    @Suppress("DEPRECATION")
    override fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): Observable<AnimesPage> =
        Observable.fromCallable { kotlinx.coroutines.runBlocking { getSearchAnime(page, query, filters) } }

    @Suppress("DEPRECATION")
    override fun fetchAnimeDetails(anime: SAnime): Observable<SAnime> =
        Observable.fromCallable { kotlinx.coroutines.runBlocking { getAnimeDetails(anime) } }

    @Suppress("DEPRECATION")
    override fun fetchEpisodeList(anime: SAnime): Observable<List<SEpisode>> =
        Observable.fromCallable { kotlinx.coroutines.runBlocking { getEpisodeList(anime) } }

    @Suppress("DEPRECATION")
    override fun fetchVideoList(episode: SEpisode): Observable<List<Video>> =
        Observable.fromCallable { kotlinx.coroutines.runBlocking { getVideoList(episode) } }

    protected open fun popularAnimeRequest(page: Int): Request =
        throw UnsupportedOperationException("popularAnimeRequest not implemented")

    protected open fun popularAnimeParse(response: Response): AnimesPage =
        throw UnsupportedOperationException("popularAnimeParse not implemented")

    protected open fun latestUpdatesRequest(page: Int): Request =
        throw UnsupportedOperationException("latestUpdatesRequest not implemented")

    protected open fun latestUpdatesParse(response: Response): AnimesPage =
        throw UnsupportedOperationException("latestUpdatesParse not implemented")

    protected open fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request =
        throw UnsupportedOperationException("searchAnimeRequest not implemented")

    protected open fun searchAnimeParse(response: Response): AnimesPage =
        throw UnsupportedOperationException("searchAnimeParse not implemented")

    open fun animeDetailsRequest(anime: SAnime): Request = GET(resolveUrl(anime.url), headers)

    protected open fun animeDetailsParse(response: Response): SAnime =
        throw UnsupportedOperationException("animeDetailsParse not implemented")

    open fun episodeListRequest(anime: SAnime): Request = GET(resolveUrl(anime.url), headers)

    protected open fun episodeListParse(response: Response): List<SEpisode> =
        throw UnsupportedOperationException("episodeListParse not implemented")

    open fun videoListRequest(episode: SEpisode): Request = GET(resolveUrl(episode.url), headers)

    protected open fun videoListParse(response: Response): List<Video> =
        throw UnsupportedOperationException("videoListParse not implemented")

    open fun videoUrlRequest(video: Video): Request = GET(resolveUrl(video.url), headers)

    protected open fun videoUrlParse(response: Response): String =
        throw UnsupportedOperationException("videoUrlParse not implemented")

    open fun List<Video>.sort(): List<Video> = this

    private fun resolveUrl(raw: String): String = when {
        raw.startsWith("http://", ignoreCase = true) || raw.startsWith("https://", ignoreCase = true) -> raw
        raw.startsWith("//") -> "https:$raw"
        else -> baseUrl + raw
    }

    fun SEpisode.setUrlWithoutDomain(url: String) {
        this.url = getUrlWithoutDomain(url)
    }

    fun SAnime.setUrlWithoutDomain(url: String) {
        this.url = getUrlWithoutDomain(url)
    }

    private fun getUrlWithoutDomain(orig: String): String {
        return try {
            val uri = URI(orig.replace(" ", "%20"))
            var out = uri.path ?: ""
            uri.query?.let { out += "?$it" }
            uri.fragment?.let { out += "#$it" }
            out
        } catch (_: Exception) {
            orig
        }
    }

    open fun getAnimeUrl(anime: SAnime): String = resolveUrl(anime.url)

    open fun getEpisodeUrl(episode: SEpisode): String = resolveUrl(episode.url)
}
