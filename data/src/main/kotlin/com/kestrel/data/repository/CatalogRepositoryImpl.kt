// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.data.repository

import com.kestrel.core.di.IoDispatcher
import com.kestrel.data.local.PluginDao
import com.kestrel.data.local.toDomain
import com.kestrel.data.local.toEntity
import com.kestrel.domain.model.MediaDetails
import com.kestrel.domain.model.MediaType
import com.kestrel.domain.model.MultiServerResolutionUpdate
import com.kestrel.domain.model.PluginInstallState
import com.kestrel.domain.model.ProviderServerProgress
import com.kestrel.domain.model.RepoType
import com.kestrel.domain.model.ResolvedLinks
import com.kestrel.domain.model.ServerSearchSettings
import com.kestrel.domain.model.ServerSearchTarget
import com.kestrel.domain.model.StreamSource
import com.kestrel.domain.model.SubtitleTrack
import com.kestrel.domain.provider.SourceProvider
import com.kestrel.domain.repository.CatalogRepository
import com.kestrel.domain.repository.PluginRuntimeLoader
import com.kestrel.domain.repository.ProviderCatalogResult
import com.kestrel.domain.repository.ProviderSearchResult
import com.kestrel.pluginhost.nuvio.CinemetaCatalogClient
import com.kestrel.pluginhost.nuvio.NuvioNiche
import com.kestrel.pluginhost.nuvio.ResolvedExternalIds
import com.kestrel.pluginhost.settings.ProviderSettingsStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

@Singleton
class CatalogRepositoryImpl @Inject constructor(
    private val pluginDao: PluginDao,
    private val installer: PluginInstaller,
    private val runtimeLoader: PluginRuntimeLoader,
    private val cinemetaCatalogClient: CinemetaCatalogClient,
    private val providerSettingsStore: ProviderSettingsStore,
    private val json: Json,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : CatalogRepository {

    private val syncMutex = Mutex()
    @Volatile
    private var syncedOnce = false

    override val activeProviders: Flow<List<SourceProvider>> = runtimeLoader.activeProviders
    override val homeReloadRequests: Flow<Unit> = runtimeLoader.homeReloadRequests

    override suspend fun syncEnabledPlugins(force: Boolean) = withContext(ioDispatcher) {
        if (!force && syncedOnce) return@withContext
        syncMutex.withLock {
            if (!force && syncedOnce) return@withContext
            val allPlugins = pluginDao.observeAll().first().map { it.toDomain(json) }

            allPlugins.forEach { plugin ->
                if (plugin.repoType != RepoType.STREMIO &&
                    (plugin.installState == PluginInstallState.INSTALLED ||
                        plugin.installState == PluginInstallState.UPDATE_AVAILABLE) &&
                    !installer.isInstalledOnDisk(plugin)
                ) {
                    runtimeLoader.unloadPlugin(plugin.id)
                    pluginDao.upsert(
                        plugin.copy(
                            localFilePath = null,
                            isEnabled = false,
                            installState = PluginInstallState.NOT_INSTALLED,
                            lastError = null
                        ).toEntity(json)
                    )
                } else if (plugin.isEnabled &&
                    (plugin.installState == PluginInstallState.INSTALLED ||
                        plugin.installState == PluginInstallState.UPDATE_AVAILABLE) &&
                    runtimeLoader.activeProviders.first().none { it.pluginId == plugin.id }
                ) {
                    val result = runtimeLoader.loadPlugin(plugin)
                    if (result.isFailure) {
                        pluginDao.upsert(
                            plugin.copy(
                                isEnabled = false,
                                lastError = result.exceptionOrNull()?.message ?: "Runtime load failed"
                            ).toEntity(json)
                        )
                    }
                }
            }
            syncedOnce = true
        }
    }

    override fun fetchHomeCatalogsStream(
        providerFilterId: String?,
        page: Int
    ): Flow<ProviderCatalogResult> = channelFlow {
        syncEnabledPlugins(force = false)
        val matching = runtimeLoader.activeProviders.first()
            .filter { it.hasCatalog }
            .filter { providerFilterId == null || it.providerId == providerFilterId }

        val providers = if (providerFilterId == null) {
            val seenNuvioNiches = HashSet<NuvioNiche>()
            matching.filter { provider ->
                if (provider.repoType == RepoType.NUVIO) {
                    val niche = cinemetaCatalogClient.classifyNiche(
                        internalName = provider.providerId,
                        displayName = provider.name,
                        description = "",
                        language = provider.language,
                        supportedTypes = provider.supportedTypes
                    )
                    seenNuvioNiches.add(niche)
                } else {
                    true
                }
            }
        } else {
            matching
        }

        val semaphore = Semaphore(MAX_CONCURRENT_PROVIDERS)
        supervisorScope {
            providers.forEach { provider ->
                launch {
                    val item = semaphore.withPermit {
                        val result = provider.getCatalog(page)
                        ProviderCatalogResult(
                            providerId = provider.providerId,
                            providerName = provider.name,
                            rows = result.getOrDefault(emptyList()),
                            errorMessage = result.exceptionOrNull()?.message
                        )
                    }
                    send(item)
                }
            }
        }
    }.flowOn(ioDispatcher)

    override suspend fun fetchHomeCatalogs(
        providerFilterId: String?,
        page: Int
    ): List<ProviderCatalogResult> = fetchHomeCatalogsStream(providerFilterId, page).toList()

    override suspend fun searchAll(
        query: String,
        page: Int
    ): List<ProviderSearchResult> = withContext(ioDispatcher) {
        if (query.isBlank()) return@withContext emptyList()
        syncEnabledPlugins(force = false)
        val providers = runtimeLoader.activeProviders.first()
        val semaphore = Semaphore(MAX_CONCURRENT_PROVIDERS)

        supervisorScope {
            providers.map { provider ->
                async {
                    semaphore.withPermit {
                        val result = provider.search(query.trim(), page)
                        ProviderSearchResult(
                            providerId = provider.providerId,
                            providerName = provider.name,
                            items = result.getOrDefault(emptyList()),
                            errorMessage = result.exceptionOrNull()?.message
                        )
                    }
                }
            }.awaitAll()
        }
    }

    override suspend fun loadDetails(providerId: String, url: String): Result<MediaDetails> =
        withContext(ioDispatcher) {
            if (runtimeLoader.getProvider(providerId) == null) {
                syncEnabledPlugins(force = true)
            }
            val provider = runtimeLoader.getProvider(providerId)
                ?: return@withContext Result.failure(IllegalStateException("Provider unavailable"))
            provider.load(url)
        }

    override suspend fun resolveLinks(providerId: String, dataUrl: String): Result<ResolvedLinks> =
        withContext(ioDispatcher) {
            if (runtimeLoader.getProvider(providerId) == null) {
                syncEnabledPlugins(force = true)
            }
            val provider = runtimeLoader.getProvider(providerId)
                ?: return@withContext Result.failure(IllegalStateException("Provider unavailable"))
            provider.getLinks(dataUrl)
        }

    override fun resolveLinksMultiServer(
        target: ServerSearchTarget,
        settings: ServerSearchSettings,
        onlyOriginProvider: Boolean
    ): Flow<MultiServerResolutionUpdate> = channelFlow {
        syncEnabledPlugins(force = false)
        val allActive = runtimeLoader.activeProviders.first()
        val originProvider = allActive.firstOrNull { it.providerId == target.originProviderId }
            ?: runtimeLoader.getProvider(target.originProviderId)

        val candidates = if (onlyOriginProvider) {
            listOfNotNull(originProvider)
        } else {
            val selected = LinkedHashMap<String, SourceProvider>()
            if (originProvider != null) {
                selected[originProvider.providerId] = originProvider
            }
            for (provider in allActive) {
                if (provider.providerId in selected) continue
                val include = if (settings.searchAllInstalled) {
                    true
                } else {
                    when (provider.repoType) {
                        RepoType.NUVIO -> settings.searchEveryNuvio ||
                            provider.providerId in settings.selectedNuvioIds ||
                            provider.pluginId in settings.selectedNuvioIds
                        RepoType.STREMIO -> settings.searchEveryStremio ||
                            provider.providerId in settings.selectedStremioIds ||
                            provider.pluginId in settings.selectedStremioIds
                        RepoType.CLOUDSTREAM -> settings.searchEveryCloudStream ||
                            provider.providerId in settings.selectedCloudStreamIds ||
                            provider.pluginId in settings.selectedCloudStreamIds
                    }
                }
                if (include) {
                    selected[provider.providerId] = provider
                }
            }
            selected.values.sortedWith(
                compareBy<SourceProvider>(
                    { it.providerId != target.originProviderId },
                    {
                        when (it.repoType) {
                            RepoType.NUVIO -> 0
                            RepoType.STREMIO -> 1
                            RepoType.CLOUDSTREAM -> 2
                        }
                    },
                    { it.name.lowercase(Locale.US) }
                )
            )
        }

        if (candidates.isEmpty()) {
            send(
                MultiServerResolutionUpdate(
                    providers = emptyList(),
                    allStreams = emptyList(),
                    allSubtitles = emptyList(),
                    isComplete = true,
                    completedCount = 0,
                    totalCount = 0
                )
            )
            return@channelFlow
        }

        val stateMutex = Mutex()
        val progressByProvider = LinkedHashMap<String, ProviderServerProgress>()
        candidates.forEach { p ->
            progressByProvider[p.providerId] = ProviderServerProgress(
                providerId = p.providerId,
                providerName = p.name,
                repoType = p.repoType,
                isFetching = true
            )
        }

        fun snapshot(completed: Int): MultiServerResolutionUpdate {
            val list = progressByProvider.values.toList()
            val streams = list.flatMap { it.streams }
                .distinctBy { "${it.providerId}|${it.url}" }
                .sortedWith(
                    compareByDescending<StreamSource> { it.qualityScore }
                        .thenBy { it.providerId != target.originProviderId }
                )
            val subs = list.flatMap { it.subtitles }.distinctBy { it.url }
            return MultiServerResolutionUpdate(
                providers = list,
                allStreams = streams,
                allSubtitles = subs,
                isComplete = completed >= candidates.size,
                completedCount = completed,
                totalCount = candidates.size
            )
        }

        send(snapshot(completed = 0))

        val needsIdResolution = candidates.any {
            it.repoType == RepoType.NUVIO || it.repoType == RepoType.STREMIO
        }
        val externalIds: ResolvedExternalIds = if (needsIdResolution) {
            val tmdbKey = findConfiguredTmdbKey(candidates)
            cinemetaCatalogClient.resolveExternalIds(
                title = target.title,
                year = target.year,
                mediaType = target.mediaType,
                rawMediaUrl = target.rawMediaUrl,
                originDataUrl = target.originDataUrl,
                tmdbApiKey = tmdbKey
            )
        } else {
            ResolvedExternalIds()
        }

        var completedCount = 0
        val semaphore = Semaphore(MAX_CONCURRENT_SERVER_SEARCH)

        supervisorScope {
            candidates.forEach { provider ->
                launch {
                    val timeoutSec = if (settings.sameSearchTimeForAll) {
                        settings.globalSearchTimeoutSec
                    } else {
                        settings.perExtensionTimeoutsSec[provider.providerId]
                            ?: settings.perExtensionTimeoutsSec[provider.pluginId]
                            ?: settings.globalSearchTimeoutSec
                    }
                    val timeoutMs = timeoutSec.coerceIn(5, 120) * 1000L

                    val result = semaphore.withPermit {
                        withTimeoutOrNull(timeoutMs) {
                            resolveForSingleProvider(
                                provider = provider,
                                target = target,
                                externalIds = externalIds,
                                timeoutMs = timeoutMs
                            )
                        } ?: Result.failure(IllegalStateException("Timed out after ${timeoutSec}s"))
                    }

                    val update = stateMutex.withLock {
                        completedCount++
                        val resolved = result.getOrNull()
                        val errorMsg = result.exceptionOrNull()?.message
                        val streams = resolved?.streams.orEmpty()
                        val subs = resolved?.subtitles.orEmpty()
                        progressByProvider[provider.providerId] = ProviderServerProgress(
                            providerId = provider.providerId,
                            providerName = provider.name,
                            repoType = provider.repoType,
                            isFetching = false,
                            streams = streams,
                            subtitles = subs,
                            errorMessage = errorMsg
                        )
                        snapshot(completedCount)
                    }
                    send(update)
                }
            }
        }
    }.flowOn(ioDispatcher)

    private suspend fun resolveForSingleProvider(
        provider: SourceProvider,
        target: ServerSearchTarget,
        externalIds: ResolvedExternalIds,
        timeoutMs: Long
    ): Result<ResolvedLinks> {
        // 1. Originating provider uses its exact dataUrl directly unless it is a Nuvio provider whose
        //    playDataUrl fell back to an IMDb ID (`tmdb:tt...`) when numeric TMDB ID is now resolved.
        if (provider.providerId == target.originProviderId) {
            val resolvedTmdbId = externalIds.tmdbId?.takeIf { it.isNotBlank() }
            val effectiveOriginData = if (
                provider.repoType == RepoType.NUVIO &&
                target.originDataUrl.startsWith("tmdb:tt", ignoreCase = true) &&
                resolvedTmdbId != null
            ) {
                buildNuvioDataToken(resolvedTmdbId, target)
            } else {
                target.originDataUrl
            }
            return provider.getLinks(effectiveOriginData, timeoutMs)
        }

        return when (provider.repoType) {
            RepoType.NUVIO -> {
                val idToUse = externalIds.tmdbId?.takeIf { it.isNotBlank() }
                    ?: externalIds.imdbId?.takeIf { it.isNotBlank() }
                    ?: return Result.success(ResolvedLinks())
                val token = buildNuvioDataToken(idToUse, target)
                provider.getLinks(token, timeoutMs)
            }

            RepoType.STREMIO -> {
                val imdbId = externalIds.imdbId?.takeIf { it.isNotBlank() }
                    ?: return Result.success(ResolvedLinks())
                val stremioType = if (target.mediaType == MediaType.MOVIE) "movie" else "series"
                val stremioId = if (
                    stremioType != "movie" &&
                    target.season != null &&
                    target.episode != null
                ) {
                    "$imdbId:${target.season}:${target.episode}"
                } else {
                    imdbId
                }
                provider.getLinks("$stremioType::$stremioId", timeoutMs)
            }

            RepoType.CLOUDSTREAM -> {
                val query = target.title
                    .replace(Regex("\\(\\d{4}\\)"), " ")
                    .replace(Regex("\\s+"), " ")
                    .trim()
                if (query.isBlank()) return Result.success(ResolvedLinks())

                val searchResults = provider.search(query, 1).getOrDefault(emptyList())
                val bestMatch = pickBestSummaryMatch(searchResults, query, target.year, target.mediaType)
                    ?: return Result.success(ResolvedLinks())

                val loaded = provider.load(bestMatch.url).getOrNull()
                    ?: return Result.success(ResolvedLinks())

                val linkData = if (loaded.episodes.isNotEmpty()) {
                    val s = target.season ?: 1
                    val e = target.episode ?: 1
                    loaded.episodes.firstOrNull { ep ->
                        (ep.season == null || ep.season == s) && ep.episodeNumber == e
                    }?.dataUrl ?: loaded.episodes.first().dataUrl
                } else {
                    loaded.playDataUrl ?: loaded.url
                }
                provider.getLinks(linkData, timeoutMs)
            }
        }
    }

    private fun buildNuvioDataToken(id: String, target: ServerSearchTarget): String {
        val mediaKind = if (target.mediaType == MediaType.MOVIE) "movie" else "tv"
        return if (mediaKind == "tv" && target.season != null && target.episode != null) {
            "tmdb:$id:$mediaKind:${target.season}:${target.episode}"
        } else {
            "tmdb:$id:$mediaKind"
        }
    }

    private fun pickBestSummaryMatch(
        results: List<com.kestrel.domain.model.MediaSummary>,
        title: String,
        year: Int?,
        mediaType: MediaType
    ): com.kestrel.domain.model.MediaSummary? {
        if (results.isEmpty()) return null
        val normTarget = normalizeTitle(title)
        var best: com.kestrel.domain.model.MediaSummary? = null
        var bestScore = 0
        for (item in results) {
            val normItem = normalizeTitle(item.title)
            var score = 0
            if (normItem == normTarget) {
                score += 100
            } else if (normItem.contains(normTarget) || normTarget.contains(normItem)) {
                score += 45
            } else {
                continue
            }
            if (item.type == mediaType) score += 20
            if (year != null && item.year != null) {
                val diff = abs(year - item.year!!)
                if (diff == 0) score += 40 else if (diff == 1) score += 15
            }
            if (score > bestScore) {
                bestScore = score
                best = item
            }
        }
        return best
    }

    private fun normalizeTitle(raw: String): String = raw
        .lowercase(Locale.US)
        .replace(Regex("[^a-z0-9]"), "")

    private fun findConfiguredTmdbKey(providers: List<SourceProvider>): String? {
        for (p in providers) {
            if (p.repoType == RepoType.NUVIO) {
                val values = providerSettingsStore.getValues(p.pluginId)
                val key = values["tmdbApiKey"] ?: values["tmdb_api_key"] ?: values["TMDB_API_KEY"]
                if (!key.isNullOrBlank()) return key.trim()
            }
        }
        return null
    }

    companion object {
        private const val MAX_CONCURRENT_PROVIDERS = 4
        private const val MAX_CONCURRENT_SERVER_SEARCH = 6
    }
}
