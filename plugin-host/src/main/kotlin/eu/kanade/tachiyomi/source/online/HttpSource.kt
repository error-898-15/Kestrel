@file:Suppress("unused")

package eu.kanade.tachiyomi.source.online

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
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

abstract class HttpSource : CatalogueSource {

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

    // --- tachiyomix 1.6 primary suspend contract (bridges legacy 1.4/1.5 RxJava sources if subclassed) ---

    override suspend fun getPopularManga(page: Int): MangasPage = withContext(Dispatchers.IO) {
        val req = popularMangaRequest(page)
        val resp = client.newCall(req).awaitSuccess()
        popularMangaParse(resp)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage = withContext(Dispatchers.IO) {
        val req = latestUpdatesRequest(page)
        val resp = client.newCall(req).awaitSuccess()
        latestUpdatesParse(resp)
    }

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage =
        withContext(Dispatchers.IO) {
            val req = searchMangaRequest(page, query, filters)
            val resp = client.newCall(req).awaitSuccess()
            searchMangaParse(resp)
        }

    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = withContext(Dispatchers.IO) {
        val resolvedManga = if (fetchDetails) {
            val resp = client.newCall(mangaDetailsRequest(manga)).awaitSuccess()
            mangaDetailsParse(resp).also { parsed ->
                if (parsed.url.isBlank()) parsed.url = manga.url
                if (parsed.title.isBlank()) parsed.title = manga.title
                parsed.initialized = true
            }
        } else {
            manga
        }
        val resolvedChapters = if (fetchChapters) {
            val resp = client.newCall(chapterListRequest(resolvedManga)).awaitSuccess()
            chapterListParse(resp)
        } else {
            chapters
        }
        SMangaUpdate(resolvedManga, resolvedChapters)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        val resp = client.newCall(pageListRequest(chapter)).awaitSuccess()
        pageListParse(resp)
    }

    open suspend fun getImageUrl(page: Page): String = withContext(Dispatchers.IO) {
        val existing = page.imageUrl
        if (!existing.isNullOrBlank()) return@withContext existing
        val req = imageUrlRequest(page)
        val resp = client.newCall(req).awaitSuccess()
        imageUrlParse(resp)
    }

    protected open fun imageRequest(page: Page): Request = GET(page.imageUrl!!, headers)

    // --- Legacy 1.4/1.5 hooks (kept open so older APKs link & run cleanly) ---

    open fun fetchPopularManga(page: Int): Observable<MangasPage> =
        Observable.fromCallable { kotlinx.coroutines.runBlocking { getPopularManga(page) } }

    open fun fetchLatestUpdates(page: Int): Observable<MangasPage> =
        Observable.fromCallable { kotlinx.coroutines.runBlocking { getLatestUpdates(page) } }

    open fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> =
        Observable.fromCallable { kotlinx.coroutines.runBlocking { getSearchManga(page, query, filters) } }

    open fun fetchMangaDetails(manga: SManga): Observable<SManga> =
        Observable.fromCallable {
            kotlinx.coroutines.runBlocking {
                getMangaUpdate(manga, emptyList(), fetchDetails = true, fetchChapters = false).manga
            }
        }

    open fun fetchChapterList(manga: SManga): Observable<List<SChapter>> =
        Observable.fromCallable {
            kotlinx.coroutines.runBlocking {
                getMangaUpdate(manga, emptyList(), fetchDetails = false, fetchChapters = true).chapters
            }
        }

    open fun fetchPageList(chapter: SChapter): Observable<List<Page>> =
        Observable.fromCallable { kotlinx.coroutines.runBlocking { getPageList(chapter) } }

    protected open fun popularMangaRequest(page: Int): Request =
        throw UnsupportedOperationException("popularMangaRequest not implemented")

    protected open fun popularMangaParse(response: Response): MangasPage =
        throw UnsupportedOperationException("popularMangaParse not implemented")

    protected open fun latestUpdatesRequest(page: Int): Request =
        throw UnsupportedOperationException("latestUpdatesRequest not implemented")

    protected open fun latestUpdatesParse(response: Response): MangasPage =
        throw UnsupportedOperationException("latestUpdatesParse not implemented")

    protected open fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request =
        throw UnsupportedOperationException("searchMangaRequest not implemented")

    protected open fun searchMangaParse(response: Response): MangasPage =
        throw UnsupportedOperationException("searchMangaParse not implemented")

    open fun mangaDetailsRequest(manga: SManga): Request = GET(baseUrl + manga.url, headers)

    protected open fun mangaDetailsParse(response: Response): SManga =
        throw UnsupportedOperationException("mangaDetailsParse not implemented")

    open fun chapterListRequest(manga: SManga): Request = GET(baseUrl + manga.url, headers)

    protected open fun chapterListParse(response: Response): List<SChapter> =
        throw UnsupportedOperationException("chapterListParse not implemented")

    open fun pageListRequest(chapter: SChapter): Request = GET(baseUrl + chapter.url, headers)

    protected open fun pageListParse(response: Response): List<Page> =
        throw UnsupportedOperationException("pageListParse not implemented")

    open fun imageUrlRequest(page: Page): Request = GET(page.url, headers)

    protected open fun imageUrlParse(response: Response): String =
        throw UnsupportedOperationException("imageUrlParse not implemented")

    fun SChapter.setUrlWithoutDomain(url: String) {
        this.url = getUrlWithoutDomain(url)
    }

    fun SManga.setUrlWithoutDomain(url: String) {
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

    open fun getMangaUrl(manga: SManga): String = baseUrl + manga.url

    open fun getChapterUrl(chapter: SChapter): String = baseUrl + chapter.url
}
