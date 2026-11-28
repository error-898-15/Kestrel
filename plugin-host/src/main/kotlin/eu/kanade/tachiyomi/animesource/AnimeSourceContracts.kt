@file:Suppress("unused")

package eu.kanade.tachiyomi.animesource

import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import rx.Observable

interface AnimeSource {
    val id: Long
    val name: String
    val lang: String get() = ""
    val supportsLatest: Boolean get() = false

    fun getFilterList(): AnimeFilterList = AnimeFilterList()

    suspend fun getPopularAnime(page: Int): AnimesPage =
        fetchPopularAnime(page).toBlocking().first()

    suspend fun getLatestUpdates(page: Int): AnimesPage =
        fetchLatestUpdates(page).toBlocking().first()

    suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage =
        fetchSearchAnime(page, query, filters).toBlocking().first()

    suspend fun getAnimeDetails(anime: SAnime): SAnime =
        fetchAnimeDetails(anime).toBlocking().first()

    suspend fun getEpisodeList(anime: SAnime): List<SEpisode> =
        fetchEpisodeList(anime).toBlocking().first()

    suspend fun getVideoList(episode: SEpisode): List<Video> =
        fetchVideoList(episode).toBlocking().first()

    @Deprecated("Use getPopularAnime")
    fun fetchPopularAnime(page: Int): Observable<AnimesPage> =
        Observable.fromCallable { kotlinx.coroutines.runBlocking { getPopularAnime(page) } }

    @Deprecated("Use getLatestUpdates")
    fun fetchLatestUpdates(page: Int): Observable<AnimesPage> =
        Observable.fromCallable { kotlinx.coroutines.runBlocking { getLatestUpdates(page) } }

    @Deprecated("Use getSearchAnime")
    fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): Observable<AnimesPage> =
        Observable.fromCallable { kotlinx.coroutines.runBlocking { getSearchAnime(page, query, filters) } }

    @Deprecated("Use getAnimeDetails")
    fun fetchAnimeDetails(anime: SAnime): Observable<SAnime> =
        Observable.fromCallable { kotlinx.coroutines.runBlocking { getAnimeDetails(anime) } }

    @Deprecated("Use getEpisodeList")
    fun fetchEpisodeList(anime: SAnime): Observable<List<SEpisode>> =
        Observable.fromCallable { kotlinx.coroutines.runBlocking { getEpisodeList(anime) } }

    @Deprecated("Use getVideoList")
    fun fetchVideoList(episode: SEpisode): Observable<List<Video>> =
        Observable.fromCallable { kotlinx.coroutines.runBlocking { getVideoList(episode) } }
}

interface AnimeCatalogueSource : AnimeSource {
    override val lang: String
    override val supportsLatest: Boolean
    val supportsRelatedAnimes: Boolean get() = false

    suspend fun fetchRelatedAnimeList(anime: SAnime): List<SAnime> =
        throw UnsupportedOperationException("Unsupported!")
}

interface ConfigurableAnimeSource {
    fun setupPreferenceScreen(screen: PreferenceScreen)
}

interface AnimeSourceFactory {
    fun createSources(): List<AnimeSource>
}

interface UnmeteredSource
