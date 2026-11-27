// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.data.repository

import com.kestrel.core.di.IoDispatcher
import com.kestrel.data.local.PluginDao
import com.kestrel.data.local.toDomain
import com.kestrel.data.local.toEntity
import com.kestrel.domain.model.MediaDetails
import com.kestrel.domain.model.PluginInstallState
import com.kestrel.domain.model.RepoType
import com.kestrel.domain.model.ResolvedLinks
import com.kestrel.domain.provider.SourceProvider
import com.kestrel.domain.repository.CatalogRepository
import com.kestrel.domain.repository.PluginRuntimeLoader
import com.kestrel.domain.repository.ProviderCatalogResult
import com.kestrel.domain.repository.ProviderSearchResult
import com.kestrel.pluginhost.nuvio.CinemetaCatalogClient
import com.kestrel.pluginhost.nuvio.NuvioNiche
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
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CatalogRepositoryImpl @Inject constructor(
    private val pluginDao: PluginDao,
    private val installer: PluginInstaller,
    private val runtimeLoader: PluginRuntimeLoader,
    private val cinemetaCatalogClient: CinemetaCatalogClient,
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

        // In "All" mode, include up to one representative Nuvio provider per distinct niche
        // (e.g. Anime, Hindi/Bollywood, South Indian, Asian Drama, General) so Home displays
        // diverse catalogs instead of collapsing all Nuvio providers to a single one, while
        // selecting any specific provider chip loads that provider's own tailored catalog rows.
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

    companion object {
        private const val MAX_CONCURRENT_PROVIDERS = 4
    }
}
