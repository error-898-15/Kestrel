// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.data.repository

import com.kestrel.core.di.IoDispatcher
import com.kestrel.data.local.PluginDao
import com.kestrel.data.local.toDomain
import com.kestrel.data.local.toEntity
import com.kestrel.domain.model.MediaDetails
import com.kestrel.domain.model.PluginInstallState
import com.kestrel.domain.model.ResolvedLinks
import com.kestrel.domain.provider.SourceProvider
import com.kestrel.domain.repository.CatalogRepository
import com.kestrel.domain.repository.PluginRuntimeLoader
import com.kestrel.domain.repository.ProviderCatalogResult
import com.kestrel.domain.repository.ProviderSearchResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CatalogRepositoryImpl @Inject constructor(
    private val pluginDao: PluginDao,
    private val runtimeLoader: PluginRuntimeLoader,
    private val json: Json,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : CatalogRepository {

    override val activeProviders: Flow<List<SourceProvider>> = runtimeLoader.activeProviders

    override suspend fun syncEnabledPlugins() = withContext(ioDispatcher) {
        val enabled = pluginDao.observeAll().first()
            .map { it.toDomain(json) }
            .filter {
                it.isEnabled &&
                    (it.installState == PluginInstallState.INSTALLED ||
                        it.installState == PluginInstallState.UPDATE_AVAILABLE)
            }

        enabled.forEach { plugin ->
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

    override suspend fun fetchHomeCatalogs(
        providerFilterId: String?,
        page: Int
    ): List<ProviderCatalogResult> = withContext(ioDispatcher) {
        syncEnabledPlugins()
        val providers = runtimeLoader.activeProviders.first()
            .filter { it.hasCatalog }
            .filter { providerFilterId == null || it.providerId == providerFilterId }

        supervisorScope {
            providers.map { provider ->
                async {
                    val result = provider.getCatalog(page)
                    ProviderCatalogResult(
                        providerId = provider.providerId,
                        providerName = provider.name,
                        rows = result.getOrDefault(emptyList()),
                        errorMessage = result.exceptionOrNull()?.message
                    )
                }
            }.awaitAll()
        }
    }

    override suspend fun searchAll(
        query: String,
        page: Int
    ): List<ProviderSearchResult> = withContext(ioDispatcher) {
        if (query.isBlank()) return@withContext emptyList()
        syncEnabledPlugins()
        val providers = runtimeLoader.activeProviders.first()

        supervisorScope {
            providers.map { provider ->
                async {
                    val result = provider.search(query.trim(), page)
                    ProviderSearchResult(
                        providerId = provider.providerId,
                        providerName = provider.name,
                        items = result.getOrDefault(emptyList()),
                        errorMessage = result.exceptionOrNull()?.message
                    )
                }
            }.awaitAll()
        }
    }

    override suspend fun loadDetails(providerId: String, url: String): Result<MediaDetails> =
        withContext(ioDispatcher) {
            val provider = runtimeLoader.getProvider(providerId)
                ?: return@withContext Result.failure(IllegalStateException("Provider unavailable"))
            provider.load(url)
        }

    override suspend fun resolveLinks(providerId: String, dataUrl: String): Result<ResolvedLinks> =
        withContext(ioDispatcher) {
            val provider = runtimeLoader.getProvider(providerId)
                ?: return@withContext Result.failure(IllegalStateException("Provider unavailable"))
            provider.getLinks(dataUrl)
        }
}
