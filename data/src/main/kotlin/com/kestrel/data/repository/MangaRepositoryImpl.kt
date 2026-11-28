// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.data.repository

import android.content.Context
import android.net.Uri
import com.kestrel.core.di.IoDispatcher
import com.kestrel.data.local.MangaExtensionDao
import com.kestrel.data.local.MangaLibraryDao
import com.kestrel.data.local.MangaLibraryEntity
import com.kestrel.data.local.MangaRepoDao
import com.kestrel.data.local.toDomain
import com.kestrel.data.local.toEntity
import com.kestrel.data.preferences.AppPreferences
import com.kestrel.data.remote.AniyomiRepoParser
import com.kestrel.data.remote.RepoUrlResolver
import com.kestrel.domain.model.DownloadedChapterManifest
import com.kestrel.domain.model.MangaBrowseMode
import com.kestrel.domain.model.MangaCatalogPage
import com.kestrel.domain.model.MangaChapter
import com.kestrel.domain.model.MangaDetails
import com.kestrel.domain.model.MangaExtensionInstallState
import com.kestrel.domain.model.MangaExtensionMetadata
import com.kestrel.domain.model.MangaExtensionTestResult
import com.kestrel.domain.model.MangaPage
import com.kestrel.domain.model.MangaProgressInfo
import com.kestrel.domain.model.MangaReaderSettings
import com.kestrel.domain.model.MangaRepoConfig
import com.kestrel.domain.model.MangaSectionRow
import com.kestrel.domain.model.MangaSourceDescriptor
import com.kestrel.domain.model.MangaSummary
import com.kestrel.domain.provider.MangaSourceProvider
import com.kestrel.domain.repository.MangaRepository
import com.kestrel.pluginhost.aniyomi.AniyomiExtensionLoader
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MangaRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mangaRepoDao: MangaRepoDao,
    private val mangaExtensionDao: MangaExtensionDao,
    private val mangaLibraryDao: MangaLibraryDao,
    private val urlResolver: RepoUrlResolver,
    private val repoParser: AniyomiRepoParser,
    private val extensionLoader: AniyomiExtensionLoader,
    private val appPreferences: AppPreferences,
    private val okHttpClient: OkHttpClient,
    private val json: Json,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : MangaRepository {

    private val seedMutex = Mutex()
    private val loadMutex = Mutex()
    private val summaryCache = ConcurrentHashMap<String, MangaSummary>()

    override val repositories: Flow<List<MangaRepoConfig>> =
        mangaRepoDao.observeAll().map { list -> list.map { it.toDomain() } }

    override val extensions: Flow<List<MangaExtensionMetadata>> =
        mangaExtensionDao.observeAll().map { list -> list.map { it.toDomain(json) } }

    override val readerSettings: Flow<MangaReaderSettings> =
        appPreferences.mangaReaderSettings

    override val libraryEntries: Flow<List<MangaProgressInfo>> =
        mangaLibraryDao.observeAll().map { list ->
            list.map { entity ->
                val readIds = runCatching {
                    json.decodeFromString<List<String>>(entity.readChapterIdsJson)
                }.getOrDefault(emptyList()).toSet()
                MangaProgressInfo(
                    mangaId = entity.mangaId,
                    extensionId = entity.extensionId,
                    sourceId = entity.sourceId,
                    sourceName = entity.sourceName,
                    title = entity.title,
                    url = entity.url,
                    coverUrl = entity.coverUrl,
                    isFavorite = entity.isFavorite,
                    lastChapterId = entity.lastChapterId,
                    lastChapterName = entity.lastChapterName,
                    lastPageRead = entity.lastPageRead,
                    totalPages = entity.totalPages,
                    readChapterIds = readIds,
                    updatedAtEpochMs = entity.updatedAtEpochMs
                )
            }
        }

    override suspend fun ensureDefaultMangaRepositoriesSeeded() = withContext(ioDispatcher) {
        seedMutex.withLock {
            val alreadySeeded = appPreferences.getSeededMangaRepoUrls()
            val pending = DefaultMangaRepositories.ALL.filterNot { it in alreadySeeded }
            if (pending.isEmpty()) return@withContext
            val existingUrls = mangaRepoDao.observeAll().first()
                .flatMap { listOf(it.url, it.resolvedUrl) }
                .toSet()
            val semaphore = Semaphore(3)
            coroutineScope {
                pending.map { repoUrl ->
                    async {
                        semaphore.withPermit {
                            if (repoUrl in existingUrls) {
                                appPreferences.markMangaRepoSeeded(repoUrl)
                                return@withPermit
                            }
                            runCatching {
                                addRepositoryInternal(repoUrl)
                                appPreferences.markMangaRepoSeeded(repoUrl)
                            }
                        }
                    }
                }.awaitAll()
            }
            Unit
        }
    }

    override suspend fun ensureInstalledExtensionsLoaded() {
        ensureInstalledExtensionsLoadedInternal()
    }

    override suspend fun restoreDefaultMangaRepositories(): Result<Int> = withContext(ioDispatcher) {
        runCatching {
            appPreferences.clearSeededMangaRepos()
            val existingUrls = mangaRepoDao.observeAll().first()
                .flatMap { listOf(it.url, it.resolvedUrl) }
                .toSet()
            var added = 0
            for (url in DefaultMangaRepositories.ALL) {
                if (url !in existingUrls) {
                    if (runCatching { addRepositoryInternal(url) }.isSuccess) {
                        added++
                        appPreferences.markMangaRepoSeeded(url)
                    }
                } else {
                    appPreferences.markMangaRepoSeeded(url)
                }
            }
            added
        }
    }

    override suspend fun addRepository(rawInput: String): Result<MangaRepoConfig> =
        withContext(ioDispatcher) { runCatching { addRepositoryInternal(rawInput) } }

    private suspend fun addRepositoryInternal(rawInput: String): MangaRepoConfig {
        val (resolvedUrl, rawBytes) = urlResolver.fetchBytes(rawInput)
        val parsed = repoParser.parseBytes(rawInput.trim(), resolvedUrl, rawBytes)
        mangaRepoDao.upsert(parsed.repo.toEntity())
        val existingById = mangaExtensionDao.getByRepo(parsed.repo.id).associateBy { it.id }
        val merged = parsed.extensions.map { ext ->
            val old = existingById[ext.id]
            if (old != null) {
                ext.copy(
                    localApkPath = old.localApkPath,
                    isEnabled = old.isEnabled,
                    installState = runCatching { MangaExtensionInstallState.valueOf(old.installState) }
                        .getOrDefault(ext.installState)
                )
            } else {
                ext
            }
        }
        mangaExtensionDao.upsertAll(merged.map { it.toEntity(json) })
        return parsed.repo
    }

    override suspend fun refreshRepository(repoId: String): Result<MangaRepoConfig> =
        withContext(ioDispatcher) {
            val existing = mangaRepoDao.getById(repoId)?.toDomain()
                ?: return@withContext Result.failure(IllegalArgumentException("Repository not found"))
            runCatching {
                addRepositoryInternal(existing.url)
            }.onFailure { err ->
                mangaRepoDao.upsert(
                    existing.copy(
                        isHealthy = false,
                        lastCheckedEpochMs = System.currentTimeMillis(),
                        lastError = err.message ?: "Refresh failed"
                    ).toEntity()
                )
            }
        }

    override suspend fun removeRepository(repoId: String) = withContext(ioDispatcher) {
        val exts = mangaExtensionDao.getByRepo(repoId)
        exts.forEach { extensionLoader.unloadExtension(it.id) }
        File(context.filesDir, "manga_extensions/$repoId").deleteRecursively()
        mangaExtensionDao.deleteByRepo(repoId)
        mangaRepoDao.deleteById(repoId)
    }

    override suspend fun installExtension(extensionId: String): Result<MangaExtensionMetadata> =
        withContext(ioDispatcher) {
            val entity = mangaExtensionDao.getById(extensionId)
                ?: return@withContext Result.failure(IllegalArgumentException("Extension not found"))
            val current = entity.toDomain(json)
            mangaExtensionDao.upsert(
                current.copy(installState = MangaExtensionInstallState.INSTALLING, lastError = null)
                    .toEntity(json)
            )
            runCatching {
                require(current.apkUrl.isNotBlank()) {
                    "No APK download URL provided for ${current.name}"
                }
                val dir = File(context.filesDir, "manga_extensions/${current.repoId}").apply { mkdirs() }
                val outFile = File(dir, "${current.packageName}.apk")
                if (outFile.exists()) {
                    outFile.setWritable(true, false)
                    outFile.delete()
                }
                val req = Request.Builder()
                    .url(current.apkUrl)
                    .header("User-Agent", RepoUrlResolver.BROWSER_USER_AGENT)
                    .build()
                okHttpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        error("HTTP ${resp.code} downloading ${current.name} APK")
                    }
                    resp.body?.byteStream()?.use { input ->
                        outFile.outputStream().use { output -> input.copyTo(output) }
                    } ?: error("Empty APK response body for ${current.name}")
                }
                require(outFile.exists() && outFile.length() > 64L) {
                    "Downloaded APK file is empty for ${current.name}"
                }
                val header = ByteArray(2)
                outFile.inputStream().use { it.read(header) }
                require(header[0] == 'P'.code.toByte() && header[1] == 'K'.code.toByte()) {
                    "Downloaded file is not a valid APK archive"
                }

                outFile.setWritable(false, false)
                outFile.setReadOnly()

                val baseInstalled = current.copy(
                    localApkPath = outFile.absolutePath,
                    isEnabled = true,
                    installState = MangaExtensionInstallState.INSTALLED,
                    lastError = null
                )
                val loaded = extensionLoader.loadExtension(baseInstalled).getOrThrow()
                val syncedSources = if (loaded.descriptors.isNotEmpty()) {
                    loaded.descriptors
                } else {
                    providersToDescriptors(loaded.mangaProviders, baseInstalled.sources)
                }
                val installed = baseInstalled.copy(sources = syncedSources)
                mangaExtensionDao.upsert(installed.toEntity(json))
                installed
            }.onFailure { err ->
                mangaExtensionDao.upsert(
                    current.copy(
                        installState = MangaExtensionInstallState.ERROR,
                        lastError = err.message ?: "Install failed"
                    ).toEntity(json)
                )
            }
        }

    override suspend fun installAllInRepo(repoId: String): Result<Int> = withContext(ioDispatcher) {
        runCatching {
            val list = mangaExtensionDao.getByRepo(repoId)
            var count = 0
            for (ext in list) {
                if (ext.installState != MangaExtensionInstallState.INSTALLED.name) {
                    if (installExtension(ext.id).isSuccess) count++
                }
            }
            count
        }
    }

    override suspend fun uninstallExtension(extensionId: String) = withContext(ioDispatcher) {
        val current = mangaExtensionDao.getById(extensionId)?.toDomain(json) ?: return@withContext
        extensionLoader.unloadExtension(extensionId)
        current.localApkPath?.let { path ->
            File(path).apply {
                setWritable(true, false)
                delete()
            }
        }
        mangaExtensionDao.upsert(
            current.copy(
                localApkPath = null,
                isEnabled = false,
                installState = MangaExtensionInstallState.NOT_INSTALLED,
                lastError = null
            ).toEntity(json)
        )
    }

    override suspend fun testExtension(extensionId: String): MangaExtensionTestResult =
        withContext(ioDispatcher) {
            val ext = mangaExtensionDao.getById(extensionId)?.toDomain(json)
                ?: return@withContext MangaExtensionTestResult(
                    extensionId = extensionId,
                    isReachable = false,
                    statusCode = 0,
                    latencyMs = 0L,
                    checkedUrl = "",
                    error = "Extension not found"
                )
            val target = ext.sources.firstOrNull { it.baseUrl.isNotBlank() }?.baseUrl ?: ext.apkUrl
            val started = System.currentTimeMillis()
            runCatching {
                val req = Request.Builder()
                    .url(target)
                    .head()
                    .header("User-Agent", RepoUrlResolver.BROWSER_USER_AGENT)
                    .build()
                okHttpClient.newCall(req).execute().use { resp ->
                    MangaExtensionTestResult(
                        extensionId = extensionId,
                        isReachable = resp.code < 500,
                        statusCode = resp.code,
                        latencyMs = System.currentTimeMillis() - started,
                        checkedUrl = target
                    )
                }
            }.getOrElse { err ->
                MangaExtensionTestResult(
                    extensionId = extensionId,
                    isReachable = false,
                    statusCode = 0,
                    latencyMs = System.currentTimeMillis() - started,
                    checkedUrl = target,
                    error = err.message
                )
            }
        }

    override suspend fun browsePopular(sourceId: String?, page: Int): Result<MangaCatalogPage> =
        browseMode(sourceId, page, MangaBrowseMode.POPULAR)

    override suspend fun browseLatest(sourceId: String?, page: Int): Result<MangaCatalogPage> =
        browseMode(sourceId, page, MangaBrowseMode.LATEST)

    private suspend fun browseMode(
        sourceId: String?,
        page: Int,
        mode: MangaBrowseMode
    ): Result<MangaCatalogPage> {
        val loadError = ensureInstalledExtensionsLoadedInternal()
        val providers = extensionLoader.getAllProviders().distinctBy { it.sourceId }
        if (providers.isEmpty()) {
            return loadError ?: Result.success(MangaCatalogPage(emptyList(), false))
        }
        if (sourceId != null) {
            val target = providers.firstOrNull { it.sourceId == sourceId }
                ?: providers.firstOrNull { it.extensionId == sourceId }
                ?: return Result.failure(IllegalStateException("Selected manga provider is not loaded"))
            val res = when (mode) {
                MangaBrowseMode.POPULAR -> target.getPopular(page)
                MangaBrowseMode.LATEST -> target.getLatest(page)
            }
            res.onSuccess { cacheSummaries(it.items) }
            return res
        }
        val semaphore = Semaphore(4)
        val results = coroutineScope {
            providers.map { provider ->
                async {
                    semaphore.withPermit {
                        when (mode) {
                            MangaBrowseMode.POPULAR -> provider.getPopular(page)
                            MangaBrowseMode.LATEST -> provider.getLatest(page)
                        }
                    }
                }
            }.awaitAll()
        }
        val combined = interleaveSuccessful(results)
        cacheSummaries(combined)
        val hasNext = results.any { it.getOrNull()?.hasNextPage == true }
        if (combined.isNotEmpty()) return Result.success(MangaCatalogPage(combined, hasNext))
        val firstFailure = results.firstNotNullOfOrNull { it.exceptionOrNull() }
        return if (firstFailure != null) Result.failure(firstFailure)
        else Result.success(MangaCatalogPage(emptyList(), false))
    }

    override suspend fun searchManga(query: String, sourceId: String?, page: Int): Result<MangaCatalogPage> {
        val loadError = ensureInstalledExtensionsLoadedInternal()
        val allProviders = extensionLoader.getAllProviders().distinctBy { it.sourceId }
        if (allProviders.isEmpty()) {
            return loadError ?: Result.success(MangaCatalogPage(emptyList(), false))
        }
        if (sourceId != null) {
            val target = allProviders.firstOrNull { it.sourceId == sourceId }
                ?: allProviders.firstOrNull { it.extensionId == sourceId }
                ?: return Result.failure(IllegalStateException("Selected manga provider is not loaded"))
            val res = target.search(query, page)
            res.onSuccess { cacheSummaries(it.items) }
            return res
        }
        val semaphore = Semaphore(4)
        val results = coroutineScope {
            allProviders.map { provider ->
                async {
                    semaphore.withPermit {
                        provider.search(query, page)
                    }
                }
            }.awaitAll()
        }
        val combined = interleaveSuccessful(results)
        cacheSummaries(combined)
        val hasNext = results.any { it.getOrNull()?.hasNextPage == true }
        if (combined.isNotEmpty()) return Result.success(MangaCatalogPage(combined, hasNext))
        val firstFailure = results.firstNotNullOfOrNull { it.exceptionOrNull() }
        return if (firstFailure != null) Result.failure(firstFailure)
        else Result.success(MangaCatalogPage(emptyList(), false))
    }

    override suspend fun loadAllCatalogRows(): Result<List<MangaSectionRow>> {
        val loadError = ensureInstalledExtensionsLoadedInternal()
        val providers = extensionLoader.getAllProviders().distinctBy { it.sourceId }
        if (providers.isEmpty()) {
            return loadError?.exceptionOrNull()?.let { Result.failure(it) }
                ?: Result.success(emptyList())
        }

        val semaphore = Semaphore(4)
        val providerResults = coroutineScope {
            providers.map { provider ->
                async {
                    val pop = semaphore.withPermit { provider.getPopular(1) }
                    val lat = if (provider.supportsLatest) {
                        semaphore.withPermit { provider.getLatest(1) }
                    } else null
                    Triple(provider, pop, lat)
                }
            }.awaitAll()
        }

        val rows = mutableListOf<MangaSectionRow>()
        val allPopular = mutableListOf<MangaCatalogPage>()
        val allLatest = mutableListOf<MangaCatalogPage>()

        for ((provider, popRes, latRes) in providerResults) {
            val popPage = popRes.getOrNull()
            if (popPage != null && popPage.items.isNotEmpty()) {
                cacheSummaries(popPage.items)
                allPopular.add(popPage)
            }
            val latPage = latRes?.getOrNull()
            if (latPage != null && latPage.items.isNotEmpty()) {
                cacheSummaries(latPage.items)
                allLatest.add(latPage)
            }
        }

        val mergedLatest = interleavePages(allLatest)
        val mergedPopular = interleavePages(allPopular)

        if (mergedLatest.isNotEmpty()) {
            rows.add(
                MangaSectionRow(
                    id = "all_latest",
                    title = "Latest Manga",
                    sourceId = null,
                    sourceName = "All Sources",
                    browseMode = MangaBrowseMode.LATEST,
                    items = mergedLatest,
                    hasNextPage = allLatest.any { it.hasNextPage }
                )
            )
        }
        if (mergedPopular.isNotEmpty()) {
            rows.add(
                MangaSectionRow(
                    id = "all_popular",
                    title = "Popular Manga",
                    sourceId = null,
                    sourceName = "All Sources",
                    browseMode = MangaBrowseMode.POPULAR,
                    items = mergedPopular,
                    hasNextPage = allPopular.any { it.hasNextPage }
                )
            )
        }

        for ((provider, popRes, latRes) in providerResults) {
            val popPage = popRes.getOrNull()
            if (popPage != null && popPage.items.isNotEmpty()) {
                rows.add(
                    MangaSectionRow(
                        id = "${provider.sourceId}_popular",
                        title = "${provider.name} • Popular",
                        sourceId = provider.sourceId,
                        sourceName = provider.name,
                        browseMode = MangaBrowseMode.POPULAR,
                        items = popPage.items,
                        hasNextPage = popPage.hasNextPage
                    )
                )
            }
            val latPage = latRes?.getOrNull()
            if (latPage != null && latPage.items.isNotEmpty()) {
                rows.add(
                    MangaSectionRow(
                        id = "${provider.sourceId}_latest",
                        title = "${provider.name} • Latest",
                        sourceId = provider.sourceId,
                        sourceName = provider.name,
                        browseMode = MangaBrowseMode.LATEST,
                        items = latPage.items,
                        hasNextPage = latPage.hasNextPage
                    )
                )
            }
        }

        if (rows.isNotEmpty()) return Result.success(rows)

        val firstErr = providerResults.firstNotNullOfOrNull { it.second.exceptionOrNull() ?: it.third?.exceptionOrNull() }
        return if (firstErr != null) Result.failure(firstErr) else Result.success(emptyList())
    }

    private fun interleaveSuccessful(results: List<Result<MangaCatalogPage>>): List<MangaSummary> =
        interleavePages(results.mapNotNull { it.getOrNull() })

    private fun interleavePages(pages: List<MangaCatalogPage>): List<MangaSummary> {
        val lists = pages.map { it.items }.filter { it.isNotEmpty() }
        if (lists.isEmpty()) return emptyList()
        val maxLen = lists.maxOf { it.size }
        return buildList {
            for (i in 0 until maxLen) {
                for (list in lists) {
                    if (i < list.size) add(list[i])
                }
            }
        }
    }

    private fun cacheSummaries(items: List<MangaSummary>) {
        items.forEach { summaryCache[it.id] = it }
    }

    override suspend fun loadMangaDetails(
        extensionId: String,
        sourceId: String,
        mangaUrl: String
    ): Result<MangaDetails> {
        ensureInstalledExtensionsLoadedInternal()
        val provider = extensionLoader.findProvider(extensionId, sourceId)
            ?: return Result.failure(IllegalStateException("Manga provider not loaded"))
        val mangaId = "$sourceId:$mangaUrl"
        val cached = summaryCache[mangaId]
        val seedSummary = cached ?: MangaSummary(
            id = mangaId,
            extensionId = extensionId,
            sourceId = sourceId,
            sourceName = provider.name,
            title = "",
            url = mangaUrl
        )
        val library = withContext(ioDispatcher) { mangaLibraryDao.getById(mangaId) }
        val readIds = runCatching {
            json.decodeFromString<List<String>>(library?.readChapterIdsJson ?: "[]")
        }.getOrDefault(emptyList()).toSet()

        return provider.getDetails(seedSummary).map { details ->
            val updatedChapters = details.chapters.map { ch ->
                ch.copy(isRead = ch.id in readIds)
            }
            val updatedSummary = details.summary.copy(
                title = details.summary.title.ifBlank { cached?.title ?: library?.title ?: "Untitled" },
                coverUrl = details.summary.coverUrl ?: cached?.coverUrl ?: library?.coverUrl
            )
            summaryCache[mangaId] = updatedSummary
            details.copy(
                summary = updatedSummary,
                chapters = updatedChapters,
                isFavorite = library?.isFavorite ?: details.isFavorite
            )
        }
    }

    override suspend fun loadChapterPages(
        extensionId: String,
        sourceId: String,
        chapter: MangaChapter
    ): Result<List<MangaPage>> = withContext(ioDispatcher) {
        // Check offline downloaded pages first
        val offlinePages = readOfflineChapterPages(chapter.id)
        if (offlinePages != null) {
            return@withContext Result.success(offlinePages)
        }
        ensureInstalledExtensionsLoadedInternal()
        val provider = extensionLoader.findProvider(extensionId, sourceId)
            ?: return@withContext Result.failure(IllegalStateException("Manga provider not loaded"))
        provider.getPages(chapter)
    }

    override suspend fun downloadChapter(
        extensionId: String,
        sourceId: String,
        mangaId: String,
        chapter: MangaChapter
    ): Result<Unit> = withContext(ioDispatcher) {
        runCatching {
            ensureInstalledExtensionsLoadedInternal()
            val provider = extensionLoader.findProvider(extensionId, sourceId)
                ?: error("Manga provider not loaded")
            val pages = provider.getPages(chapter).getOrThrow()
            require(pages.isNotEmpty()) { "No pages available to download for ${chapter.name}" }

            val dir = chapterDownloadDir(mangaId, chapter.id).apply { mkdirs() }
            val localPaths = MutableList(pages.size) { "" }
            val semaphore = Semaphore(3)
            coroutineScope {
                pages.mapIndexed { idx, page ->
                    async {
                        semaphore.withPermit {
                            val reqBuilder = Request.Builder().url(page.imageUrl)
                            page.headers.forEach { (k, v) -> reqBuilder.header(k, v) }
                            val file = File(dir, "page_${idx.toString().padStart(4, '0')}.img")
                            okHttpClient.newCall(reqBuilder.build()).execute().use { resp ->
                                if (!resp.isSuccessful) {
                                    error("HTTP ${resp.code} downloading page ${idx + 1}")
                                }
                                resp.body?.byteStream()?.use { input ->
                                    file.outputStream().use { out -> input.copyTo(out) }
                                } ?: error("Empty page body")
                            }
                            localPaths[idx] = file.absolutePath
                        }
                    }
                }.awaitAll()
            }
            val manifest = DownloadedChapterManifest(
                chapterId = chapter.id,
                mangaId = mangaId,
                pagePaths = localPaths
            )
            File(dir, "manifest.json").writeText(json.encodeToString(manifest))
        }
    }

    override suspend fun deleteDownloadedChapter(mangaId: String, chapterId: String) =
        withContext(ioDispatcher) {
            chapterDownloadDir(mangaId, chapterId).deleteRecursively()
            Unit
        }

    override suspend fun getDownloadedChapterIds(mangaId: String): Set<String> =
        withContext(ioDispatcher) {
            val mangaDir = File(context.filesDir, "manga_downloads/${safeHash(mangaId)}")
            if (!mangaDir.exists()) return@withContext emptySet()
            mangaDir.listFiles()?.mapNotNull { chDir ->
                val manifestFile = File(chDir, "manifest.json")
                if (!manifestFile.exists()) return@mapNotNull null
                runCatching {
                    json.decodeFromString<DownloadedChapterManifest>(manifestFile.readText()).chapterId
                }.getOrNull()
            }?.toSet().orEmpty()
        }

    private fun readOfflineChapterPages(chapterId: String): List<MangaPage>? {
        val rootDir = File(context.filesDir, "manga_downloads")
        if (!rootDir.exists()) return null
        val chHash = safeHash(chapterId)
        val chDir = rootDir.listFiles()?.firstNotNullOfOrNull { mangaDir ->
            File(mangaDir, chHash).takeIf { File(it, "manifest.json").exists() }
        } ?: return null
        val manifest = runCatching {
            json.decodeFromString<DownloadedChapterManifest>(File(chDir, "manifest.json").readText())
        }.getOrNull() ?: return null
        val files = manifest.pagePaths.map(::File)
        if (files.isEmpty() || files.any { !it.exists() || it.length() == 0L }) return null
        return files.mapIndexed { idx, file ->
            MangaPage(index = idx, imageUrl = Uri.fromFile(file).toString(), headers = emptyMap())
        }
    }

    private fun chapterDownloadDir(mangaId: String, chapterId: String): File =
        File(context.filesDir, "manga_downloads/${safeHash(mangaId)}/${safeHash(chapterId)}")

    private fun safeHash(input: String): String =
        MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
            .take(12).joinToString("") { "%02x".format(it) }

    override suspend fun toggleFavorite(manga: MangaSummary) = withContext(ioDispatcher) {
        val existing = mangaLibraryDao.getById(manga.id)
        if (existing != null) {
            mangaLibraryDao.upsert(
                existing.copy(isFavorite = !existing.isFavorite, updatedAtEpochMs = System.currentTimeMillis())
            )
        } else {
            mangaLibraryDao.upsert(
                MangaLibraryEntity(
                    mangaId = manga.id,
                    extensionId = manga.extensionId,
                    sourceId = manga.sourceId,
                    sourceName = manga.sourceName,
                    title = manga.title,
                    url = manga.url,
                    coverUrl = manga.coverUrl,
                    isFavorite = true,
                    lastChapterId = null,
                    lastChapterName = null,
                    lastPageRead = 0,
                    totalPages = 0,
                    readChapterIdsJson = "[]",
                    updatedAtEpochMs = System.currentTimeMillis()
                )
            )
        }
    }

    override suspend fun saveChapterProgress(
        manga: MangaSummary,
        chapter: MangaChapter,
        pageIndex: Int,
        totalPages: Int
    ) = withContext(ioDispatcher) {
        val existing = mangaLibraryDao.getById(manga.id)
        val readIds = runCatching {
            json.decodeFromString<List<String>>(existing?.readChapterIdsJson ?: "[]")
        }.getOrDefault(emptyList()).toMutableSet()
        if (totalPages > 0 && pageIndex >= totalPages - 2) {
            readIds.add(chapter.id)
        }
        mangaLibraryDao.upsert(
            MangaLibraryEntity(
                mangaId = manga.id,
                extensionId = manga.extensionId,
                sourceId = manga.sourceId,
                sourceName = manga.sourceName,
                title = manga.title.ifBlank { existing?.title.orEmpty() },
                url = manga.url,
                coverUrl = manga.coverUrl ?: existing?.coverUrl,
                isFavorite = existing?.isFavorite ?: false,
                lastChapterId = chapter.id,
                lastChapterName = chapter.name,
                lastPageRead = pageIndex,
                totalPages = totalPages,
                readChapterIdsJson = json.encodeToString(readIds.toList()),
                updatedAtEpochMs = System.currentTimeMillis()
            )
        )
    }

    override suspend fun updateReaderSettings(transform: (MangaReaderSettings) -> MangaReaderSettings) {
        appPreferences.updateMangaReaderSettings(transform)
    }

    private fun providersToDescriptors(
        providers: List<MangaSourceProvider>,
        existing: List<MangaSourceDescriptor>
    ): List<MangaSourceDescriptor> {
        if (providers.isEmpty()) return existing
        return providers.map { p ->
            MangaSourceDescriptor(
                id = p.sourceId,
                name = p.name,
                lang = p.lang,
                baseUrl = p.baseUrl,
                isVideo = false
            )
        }
    }

    private suspend fun ensureInstalledExtensionsLoadedInternal(): Result<MangaCatalogPage>? =
        loadMutex.withLock {
            val installed = mangaExtensionDao.observeAll().first()
                .map { it.toDomain(json) }
                .filter { it.installState == MangaExtensionInstallState.INSTALLED }
            var lastError: Throwable? = null
            for (ext in installed) {
                if (!extensionLoader.isExtensionLoaded(ext.id)) {
                    extensionLoader.loadExtension(ext)
                        .onSuccess { loaded ->
                            val synced = if (loaded.descriptors.isNotEmpty()) {
                                loaded.descriptors
                            } else {
                                providersToDescriptors(loaded.mangaProviders, ext.sources)
                            }
                            if (synced != ext.sources) {
                                mangaExtensionDao.upsert(ext.copy(sources = synced).toEntity(json))
                            }
                        }
                        .onFailure { lastError = it }
                }
            }
            lastError?.let { Result.failure(it) }
        }
}
