// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.pluginhost.aniyomi

import android.content.Context
import android.content.pm.PackageManager
import com.kestrel.core.di.IoDispatcher
import com.kestrel.domain.model.MangaChapter
import com.kestrel.domain.model.MangaDetails
import com.kestrel.domain.model.MangaExtensionMetadata
import com.kestrel.domain.model.MangaPage
import com.kestrel.domain.model.MangaSummary
import com.kestrel.domain.provider.MangaSourceProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import dalvik.system.PathClassLoader
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import uy.kohesive.injekt.InjektRegistry
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Loads installed Aniyomi / Keiyoushi / Tachiyomi extension APKs via isolated PathClassLoader
 * backed by Kestrel's Tachiyomi/Mihon extension-lib + Injekt runtime, bridging real Source
 * instances directly into [MangaSourceProvider] with NO shared mock fallback.
 */
@Singleton
class AniyomiExtensionLoader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    private val loadedProviders = ConcurrentHashMap<String, List<MangaSourceProvider>>()

    fun getProvidersForExtension(extensionId: String): List<MangaSourceProvider> =
        loadedProviders[extensionId].orEmpty()

    fun getAllProviders(): List<MangaSourceProvider> =
        loadedProviders.values.flatten()

    fun findProvider(extensionId: String, sourceId: String): MangaSourceProvider? =
        loadedProviders[extensionId]?.firstOrNull { it.sourceId == sourceId }
            ?: loadedProviders.values.flatten().firstOrNull { it.sourceId == sourceId }
            ?: loadedProviders[extensionId]?.firstOrNull()

    suspend fun loadExtension(metadata: MangaExtensionMetadata): Result<List<MangaSourceProvider>> =
        withContext(ioDispatcher) {
            runCatching {
                InjektRegistry.ensureInitialized(context, okHttpClient)
                val apkPath = metadata.localApkPath
                    ?: error("Extension ${metadata.name} has no downloaded APK. Reinstall it from Settings -> Manga Source.")
                val apkFile = File(apkPath).takeIf { it.exists() && it.length() > 0L }
                    ?: error("Extension APK missing on disk for ${metadata.name}. Please reinstall.")

                val providers = loadSourcesFromApk(metadata, apkFile)
                if (providers.isEmpty()) {
                    error("No manga sources found inside ${metadata.name} APK.")
                }
                loadedProviders[metadata.id] = providers
                providers
            }
        }

    fun unloadExtension(extensionId: String) {
        loadedProviders.remove(extensionId)
    }

    private fun loadSourcesFromApk(
        metadata: MangaExtensionMetadata,
        apkFile: File
    ): List<MangaSourceProvider> {
        // Android 14+ requires dynamically loaded DEX/APK files to be non-writable
        apkFile.setWritable(false, false)
        apkFile.setReadOnly()

        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val pkgInfo = pm.getPackageArchiveInfo(
            apkFile.absolutePath,
            PackageManager.GET_META_DATA or PackageManager.GET_ACTIVITIES
        ) ?: error("Unable to read AndroidManifest from ${apkFile.name}")

        val metaData = pkgInfo.applicationInfo?.metaData
        val animeClassAttr = metaData?.getString("tachiyomi.animeextension.class")
        val mangaClassAttr = metaData?.getString("tachiyomi.extension.class")

        if (mangaClassAttr.isNullOrBlank()) {
            if (!animeClassAttr.isNullOrBlank() || metadata.packageName.contains(".animeextension.")) {
                error("${metadata.name} is an Aniyomi Anime (video) extension and has no manga catalog.")
            }
            error("${metadata.name} APK is missing tachiyomi.extension.class metadata.")
        }

        val basePackage = pkgInfo.packageName ?: metadata.packageName
        val classNames = mangaClassAttr
            .split(';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { raw -> if (raw.startsWith(".")) "$basePackage$raw" else raw }

        val classLoader = PathClassLoader(apkFile.absolutePath, context.classLoader)
        val loadedSources = mutableListOf<Any>()

        for (className in classNames) {
            val clazz = classLoader.loadClass(className)
            val instance = clazz.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
            when (instance) {
                is SourceFactory -> loadedSources += instance.createSources()
                is Source -> loadedSources += instance
                else -> {
                    val fromCreate = runCatching {
                        @Suppress("UNCHECKED_CAST")
                        clazz.getMethod("createSources").invoke(instance) as? List<Any>
                    }.getOrNull()
                    val fromLegacy = runCatching {
                        @Suppress("UNCHECKED_CAST")
                        clazz.getMethod("getSources").invoke(instance) as? List<Any>
                    }.getOrNull()
                    when {
                        !fromCreate.isNullOrEmpty() -> loadedSources += fromCreate
                        !fromLegacy.isNullOrEmpty() -> loadedSources += fromLegacy
                        else -> loadedSources += instance
                    }
                }
            }
        }

        return loadedSources.mapIndexed { idx, src ->
            if (src is Source) {
                DirectTachiyomiSourceProvider(
                    source = src,
                    extensionId = metadata.id,
                    fallbackName = metadata.name,
                    fallbackLang = metadata.lang
                )
            } else {
                ReflectiveLegacySourceProvider(
                    sourceObj = src,
                    extensionId = metadata.id,
                    fallbackId = "${metadata.id}_$idx",
                    fallbackName = metadata.name,
                    fallbackLang = metadata.lang,
                    ioDispatcher = ioDispatcher
                )
            }
        }
    }
}

private class DirectTachiyomiSourceProvider(
    private val source: Source,
    override val extensionId: String,
    fallbackName: String,
    fallbackLang: String
) : MangaSourceProvider {

    override val sourceId: String = source.id.toString()
    override val name: String = source.name.ifBlank { fallbackName }
    override val lang: String = (source as? CatalogueSource)?.lang ?: fallbackLang
    override val baseUrl: String = (source as? HttpSource)?.baseUrl.orEmpty()

    private val defaultHeaders: Map<String, String> by lazy {
        val http = source as? HttpSource ?: return@lazy emptyMap()
        buildMap {
            val h = http.headers
            for (i in 0 until h.size) {
                put(h.name(i), h.value(i))
            }
            if (baseUrl.isNotBlank() && !containsKey("Referer")) {
                put("Referer", "$baseUrl/")
            }
        }
    }

    override suspend fun getPopular(page: Int): Result<List<MangaSummary>> = runCatching {
        mapMangasPage(source.getPopularManga(page))
    }

    override suspend fun getLatest(page: Int): Result<List<MangaSummary>> = runCatching {
        mapMangasPage(source.getLatestUpdates(page))
    }

    override suspend fun search(query: String, page: Int): Result<List<MangaSummary>> = runCatching {
        val filters = runCatching { source.getFilterList() }.getOrElse { FilterList() }
        mapMangasPage(source.getSearchManga(page, query, filters))
    }

    override suspend fun getDetails(manga: MangaSummary): Result<MangaDetails> = runCatching {
        val sManga = SManga.create().apply {
            url = manga.url
            title = manga.title
            thumbnail_url = manga.coverUrl
            author = manga.author
        }
        val update = source.getMangaUpdate(
            manga = sManga,
            chapters = emptyList(),
            fetchDetails = true,
            fetchChapters = true
        )
        val resolvedManga = update.manga
        val resolvedSummary = manga.copy(
            title = resolvedManga.title.ifBlank { manga.title },
            coverUrl = resolvedManga.thumbnail_url ?: manga.coverUrl,
            author = resolvedManga.author ?: manga.author,
            status = mapStatus(resolvedManga.status) ?: manga.status
        )
        val chapters = update.chapters.mapIndexed { idx, ch ->
            MangaChapter(
                id = "$sourceId:${ch.url}",
                name = ch.name.ifBlank { "Chapter ${idx + 1}" },
                url = ch.url,
                chapterNumber = ch.chapter_number,
                scanlator = ch.scanlator,
                dateUploadEpochMs = ch.date_upload
            )
        }
        MangaDetails(
            summary = resolvedSummary,
            artist = resolvedManga.artist,
            description = resolvedManga.description,
            genres = resolvedManga.genre
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                .orEmpty(),
            chapters = chapters
        )
    }

    override suspend fun getChapters(manga: MangaSummary): Result<List<MangaChapter>> =
        getDetails(manga).map { it.chapters }

    override suspend fun getPages(chapter: MangaChapter): Result<List<MangaPage>> = runCatching {
        val sChapter = SChapter.create().apply {
            url = chapter.url
            name = chapter.name
            chapter_number = chapter.chapterNumber
            scanlator = chapter.scanlator
            date_upload = chapter.dateUploadEpochMs
        }
        val pages: List<Page> = source.getPageList(sChapter)
        val http = source as? HttpSource
        pages.mapIndexedNotNull { idx, page ->
            val resolvedUrl = page.imageUrl?.takeIf { it.isNotBlank() }
                ?: if (http != null && page.url.isNotBlank()) {
                    runCatching { http.getImageUrl(page) }.getOrNull()?.takeIf { it.isNotBlank() }
                } else {
                    page.url.takeIf { it.isNotBlank() }
                }
                ?: return@mapIndexedNotNull null
            MangaPage(
                index = idx,
                imageUrl = resolvedUrl,
                headers = defaultHeaders
            )
        }
    }

    private fun mapMangasPage(mp: MangasPage): List<MangaSummary> = mp.mangas.map { item ->
        MangaSummary(
            id = "$sourceId:${item.url}",
            extensionId = extensionId,
            sourceId = sourceId,
            sourceName = name,
            title = item.title.ifBlank { "Untitled" },
            url = item.url,
            coverUrl = item.thumbnail_url,
            author = item.author,
            status = mapStatus(item.status)
        )
    }

    private fun mapStatus(status: Int): String? = when (status) {
        SManga.ONGOING -> "Ongoing"
        SManga.COMPLETED -> "Completed"
        SManga.LICENSED -> "Licensed"
        SManga.PUBLISHING_FINISHED -> "Publishing Finished"
        SManga.CANCELLED -> "Cancelled"
        SManga.ON_HIATUS -> "On Hiatus"
        else -> null
    }
}

private class ReflectiveLegacySourceProvider(
    private val sourceObj: Any,
    override val extensionId: String,
    fallbackId: String,
    fallbackName: String,
    fallbackLang: String,
    private val ioDispatcher: CoroutineDispatcher
) : MangaSourceProvider {
    private val cls = sourceObj.javaClass

    override val sourceId: String = runCatching {
        cls.getMethod("getId").invoke(sourceObj)?.toString()
    }.getOrNull() ?: fallbackId

    override val name: String = runCatching {
        cls.getMethod("getName").invoke(sourceObj)?.toString()
    }.getOrNull() ?: fallbackName

    override val lang: String = runCatching {
        cls.getMethod("getLang").invoke(sourceObj)?.toString()
    }.getOrNull() ?: fallbackLang

    override val baseUrl: String = runCatching {
        cls.getMethod("getBaseUrl").invoke(sourceObj)?.toString()
    }.getOrNull().orEmpty()

    override suspend fun getPopular(page: Int): Result<List<MangaSummary>> = withContext(ioDispatcher) {
        runCatching {
            val method = cls.getMethod("fetchPopularManga", Int::class.javaPrimitiveType)
            extractMangaList(awaitRxSingleOrFirst(method.invoke(sourceObj, page)))
        }
    }

    override suspend fun getLatest(page: Int): Result<List<MangaSummary>> = withContext(ioDispatcher) {
        runCatching {
            val method = cls.getMethod("fetchLatestUpdates", Int::class.javaPrimitiveType)
            extractMangaList(awaitRxSingleOrFirst(method.invoke(sourceObj, page)))
        }
    }

    override suspend fun search(query: String, page: Int): Result<List<MangaSummary>> = withContext(ioDispatcher) {
        runCatching {
            val filterListClass = cls.classLoader?.loadClass("eu.kanade.tachiyomi.source.model.FilterList")
            val emptyFilters = filterListClass?.getDeclaredConstructor()?.newInstance()
            val method = cls.methods.first { it.name == "fetchSearchManga" && it.parameterTypes.size == 3 }
            extractMangaList(awaitRxSingleOrFirst(method.invoke(sourceObj, page, query, emptyFilters)))
        }
    }

    override suspend fun getDetails(manga: MangaSummary): Result<MangaDetails> = withContext(ioDispatcher) {
        runCatching {
            val sManga = buildSManga(manga)
            val detailMethod = cls.methods.first { it.name == "fetchMangaDetails" }
            val resolved = awaitRxSingleOrFirst(detailMethod.invoke(sourceObj, sManga)) ?: sManga
            val chapters = getChapters(manga).getOrDefault(emptyList())
            MangaDetails(
                summary = manga.copy(
                    author = readProp(resolved, "getAuthor") ?: manga.author,
                    coverUrl = readProp(resolved, "getThumbnail_url") ?: manga.coverUrl
                ),
                artist = readProp(resolved, "getArtist"),
                description = readProp(resolved, "getDescription"),
                genres = readProp(resolved, "getGenre")
                    ?.split(',')
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    .orEmpty(),
                chapters = chapters
            )
        }
    }

    override suspend fun getChapters(manga: MangaSummary): Result<List<MangaChapter>> = withContext(ioDispatcher) {
        runCatching {
            val sManga = buildSManga(manga)
            val method = cls.methods.first { it.name == "fetchChapterList" }
            val list = awaitRxSingleOrFirst(method.invoke(sourceObj, sManga)) as? List<*> ?: emptyList<Any>()
            list.mapIndexedNotNull { idx, item ->
                if (item == null) return@mapIndexedNotNull null
                val url = readProp(item, "getUrl").orEmpty()
                val title = readProp(item, "getName") ?: "Chapter ${idx + 1}"
                val chNum = runCatching {
                    item.javaClass.getMethod("getChapter_number").invoke(item) as? Float
                }.getOrNull() ?: idx.toFloat()
                MangaChapter(
                    id = "$sourceId:$url",
                    name = title,
                    url = url,
                    chapterNumber = chNum,
                    scanlator = readProp(item, "getScanlator")
                )
            }
        }
    }

    override suspend fun getPages(chapter: MangaChapter): Result<List<MangaPage>> = withContext(ioDispatcher) {
        runCatching {
            val sChapterCls = cls.classLoader!!.loadClass("eu.kanade.tachiyomi.source.model.SChapter")
            val sChapter = sChapterCls.getMethod("create").invoke(null)
            sChapterCls.getMethod("setUrl", String::class.java).invoke(sChapter, chapter.url)
            sChapterCls.getMethod("setName", String::class.java).invoke(sChapter, chapter.name)
            val method = cls.methods.first { it.name == "fetchPageList" }
            val list = awaitRxSingleOrFirst(method.invoke(sourceObj, sChapter)) as? List<*> ?: emptyList<Any>()
            list.mapIndexedNotNull { idx, p ->
                if (p == null) return@mapIndexedNotNull null
                val imgUrl = readProp(p, "getImageUrl") ?: readProp(p, "getUrl") ?: return@mapIndexedNotNull null
                MangaPage(index = idx, imageUrl = imgUrl)
            }
        }
    }

    private fun extractMangaList(pageObj: Any?): List<MangaSummary> {
        if (pageObj == null) return emptyList()
        val mangas = runCatching {
            pageObj.javaClass.getMethod("getMangas").invoke(pageObj) as? List<*>
        }.getOrNull() ?: return emptyList()
        return mangas.mapNotNull { item ->
            if (item == null) return@mapNotNull null
            val url = readProp(item, "getUrl") ?: return@mapNotNull null
            val title = readProp(item, "getTitle") ?: "Untitled"
            MangaSummary(
                id = "$sourceId:$url",
                extensionId = extensionId,
                sourceId = sourceId,
                sourceName = name,
                title = title,
                url = url,
                coverUrl = readProp(item, "getThumbnail_url"),
                author = readProp(item, "getAuthor")
            )
        }
    }

    private fun buildSManga(summary: MangaSummary): Any {
        val sMangaCls = cls.classLoader!!.loadClass("eu.kanade.tachiyomi.source.model.SManga")
        val sManga = sMangaCls.getMethod("create").invoke(null)
        sMangaCls.getMethod("setUrl", String::class.java).invoke(sManga, summary.url)
        sMangaCls.getMethod("setTitle", String::class.java).invoke(sManga, summary.title)
        return sManga
    }

    private fun awaitRxSingleOrFirst(obs: Any?): Any? {
        if (obs == null) return null
        val toBlocking = obs.javaClass.getMethod("toBlocking")
        val blocking = toBlocking.invoke(obs)
        return blocking.javaClass.getMethod("first").invoke(blocking)
    }

    private fun readProp(target: Any, getter: String): String? =
        runCatching { target.javaClass.getMethod(getter).invoke(target)?.toString() }.getOrNull()
}
