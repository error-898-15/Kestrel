// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.data.repository

import com.kestrel.core.di.IoDispatcher
import com.kestrel.data.local.PluginDao
import com.kestrel.data.local.RepoDao
import com.kestrel.data.local.toDomain
import com.kestrel.data.local.toEntity
import com.kestrel.data.remote.ParsedRepoPayload
import com.kestrel.data.remote.RepoJsonParser
import com.kestrel.data.remote.RepoUrlResolver
import com.kestrel.domain.model.PluginInstallState
import com.kestrel.domain.model.PluginMetadata
import com.kestrel.domain.model.RepoConfig
import com.kestrel.domain.model.RepoType
import com.kestrel.domain.repository.ExtensionRepository
import com.kestrel.domain.repository.PluginRuntimeLoader
import com.kestrel.pluginhost.PluginSandbox
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ExtensionRepositoryImpl @Inject constructor(
    private val repoDao: RepoDao,
    private val pluginDao: PluginDao,
    private val urlResolver: RepoUrlResolver,
    private val jsonParser: RepoJsonParser,
    private val installer: PluginInstaller,
    private val runtimeLoader: PluginRuntimeLoader,
    private val json: Json,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : ExtensionRepository {

    override val repositories: Flow<List<RepoConfig>> =
        repoDao.observeAll().map { rows -> rows.map { it.toDomain(json) } }

    override val plugins: Flow<List<PluginMetadata>> =
        pluginDao.observeAll().map { rows -> rows.map { it.toDomain(json) } }

    override suspend fun addRepository(rawInput: String): Result<RepoConfig> =
        withContext(ioDispatcher) {
            runCatching {
                val (resolvedUrl, manifestBody) = urlResolver.fetchText(rawInput)
                val parsed = jsonParser.parseRepositoryManifest(
                    rawInputUrl = rawInput.trim(),
                    resolvedUrl = resolvedUrl,
                    rawJson = manifestBody
                )
                syncParsedRepo(parsed)
            }
        }

    override suspend fun refreshRepository(repoId: String): Result<RepoConfig> =
        withContext(ioDispatcher) {
            val existing = repoDao.getById(repoId)?.toDomain(json)
                ?: return@withContext Result.failure(IllegalArgumentException("Repository not found"))

            runCatching {
                val (resolvedUrl, manifestBody) = urlResolver.fetchText(existing.resolvedUrl)
                val parsed = jsonParser.parseRepositoryManifest(
                    rawInputUrl = existing.url,
                    resolvedUrl = resolvedUrl,
                    rawJson = manifestBody
                )
                syncParsedRepo(parsed)
            }.onFailure { err ->
                repoDao.upsert(
                    existing.copy(
                        isHealthy = false,
                        lastCheckedEpochMs = System.currentTimeMillis(),
                        lastError = err.message ?: "Refresh failed"
                    ).toEntity(json)
                )
            }
        }

    override suspend fun removeRepository(repoId: String) = withContext(ioDispatcher) {
        val repoPlugins = pluginDao.getByRepo(repoId)
        repoPlugins.forEach { runtimeLoader.unloadPlugin(it.id) }
        installer.deleteRepoDirectory(repoId)
        pluginDao.deleteByRepo(repoId)
        repoDao.deleteById(repoId)
    }

    override suspend fun installPlugin(pluginId: String): Result<PluginMetadata> =
        withContext(ioDispatcher) {
            val existing = pluginDao.getById(pluginId)?.toDomain(json)
                ?: return@withContext Result.failure(IllegalArgumentException("Plugin not found"))

            if (existing.installState == PluginInstallState.UNSUPPORTED_API) {
                return@withContext Result.failure(
                    IllegalStateException(existing.lastError ?: "Unsupported plugin API version")
                )
            }

            pluginDao.upsert(existing.copy(installState = PluginInstallState.INSTALLING, lastError = null).toEntity(json))

            runCatching {
                val installedFile = installer.downloadAndVerify(existing)
                val updated = existing.copy(
                    localFilePath = installedFile.absolutePath,
                    installState = PluginInstallState.INSTALLED,
                    // Nuvio scrapers can be enabled immediately once explicitly installed by user,
                    // or toggled off individually anytime.
                    isEnabled = true,
                    lastError = null
                )
                val loadResult = runtimeLoader.loadPlugin(updated)
                val finalPlugin = if (loadResult.isFailure) {
                    updated.copy(
                        isEnabled = false,
                        lastError = loadResult.exceptionOrNull()?.message ?: "Runtime load failed"
                    )
                } else {
                    updated
                }
                pluginDao.upsert(finalPlugin.toEntity(json))
                finalPlugin
            }.onFailure { err ->
                pluginDao.upsert(
                    existing.copy(
                        localFilePath = null,
                        isEnabled = false,
                        installState = PluginInstallState.ERROR,
                        lastError = err.message ?: "Installation failed"
                    ).toEntity(json)
                )
            }
        }

    override suspend fun installAllInRepo(repoId: String): Result<Int> =
        withContext(ioDispatcher) {
            runCatching {
                val candidates = pluginDao.getByRepo(repoId)
                    .map { it.toDomain(json) }
                    .filter { plugin ->
                        plugin.installState != PluginInstallState.UNSUPPORTED_API &&
                            (!installer.isInstalledOnDisk(plugin) ||
                                plugin.installState == PluginInstallState.UPDATE_AVAILABLE ||
                                plugin.installState == PluginInstallState.NOT_INSTALLED ||
                                plugin.installState == PluginInstallState.ERROR)
                    }

                val semaphore = Semaphore(3)
                val results = coroutineScope {
                    candidates.map { plugin ->
                        async {
                            semaphore.withPermit {
                                installPlugin(plugin.id).isSuccess
                            }
                        }
                    }.awaitAll()
                }
                results.count { it }
            }
        }

    override suspend fun updatePlugin(pluginId: String): Result<PluginMetadata> =
        installPlugin(pluginId)

    override suspend fun uninstallPlugin(pluginId: String) = withContext(ioDispatcher) {
        val existing = pluginDao.getById(pluginId)?.toDomain(json) ?: return@withContext
        runtimeLoader.unloadPlugin(pluginId)
        installer.deletePluginFile(existing.localFilePath)
        pluginDao.upsert(
            existing.copy(
                localFilePath = null,
                isEnabled = false,
                installState = PluginInstallState.NOT_INSTALLED,
                lastError = null
            ).toEntity(json)
        )
    }

    override suspend fun setPluginEnabled(pluginId: String, enabled: Boolean): Result<Unit> =
        withContext(ioDispatcher) {
            runCatching {
                val existing = pluginDao.getById(pluginId)?.toDomain(json)
                    ?: error("Plugin not found")
                if (enabled) {
                    if (!installer.isInstalledOnDisk(existing)) {
                        pluginDao.upsert(
                            existing.copy(
                                localFilePath = null,
                                isEnabled = false,
                                installState = PluginInstallState.NOT_INSTALLED,
                                lastError = null
                            ).toEntity(json)
                        )
                        error("Plugin file missing — tap Download to reinstall")
                    }
                    require(
                        existing.installState == PluginInstallState.INSTALLED ||
                            existing.installState == PluginInstallState.UPDATE_AVAILABLE
                    ) {
                        "Install plugin before enabling"
                    }
                    runtimeLoader.loadPlugin(existing).getOrThrow()
                } else {
                    runtimeLoader.unloadPlugin(pluginId)
                }
                pluginDao.setEnabled(pluginId, enabled)
            }
        }

    override suspend fun reconcileMissingFiles() = withContext(ioDispatcher) {
        val all = pluginDao.observeAll().first().map { it.toDomain(json) }
        val repaired = all.mapNotNull { plugin ->
            if (plugin.repoType != RepoType.STREMIO &&
                (plugin.installState == PluginInstallState.INSTALLED ||
                    plugin.installState == PluginInstallState.UPDATE_AVAILABLE) &&
                !installer.isInstalledOnDisk(plugin)
            ) {
                runtimeLoader.unloadPlugin(plugin.id)
                plugin.copy(
                    localFilePath = null,
                    isEnabled = false,
                    installState = PluginInstallState.NOT_INSTALLED,
                    lastError = null
                ).toEntity(json)
            } else {
                null
            }
        }
        if (repaired.isNotEmpty()) {
            pluginDao.upsertAll(repaired)
        }
    }

    private suspend fun syncParsedRepo(parsed: ParsedRepoPayload): RepoConfig {
        val repo = parsed.repo
        val existingPlugins = pluginDao.getByRepo(repo.id).map { it.toDomain(json) }.associateBy { it.id }

        val incomingPlugins = when (parsed) {
            is ParsedRepoPayload.CloudStream -> {
                if (parsed.inlinePlugins.isNotEmpty()) {
                    parsed.inlinePlugins
                } else {
                    parsed.pluginListUrls.flatMap { listUrl ->
                        val (_, listBody) = urlResolver.fetchText(listUrl)
                        jsonParser.parseCloudStreamPluginsList(
                            repoId = repo.id,
                            listUrl = listUrl,
                            rawJson = listBody,
                            supportedApiVersion = PluginSandbox.SUPPORTED_CLOUDSTREAM_API_VERSION
                        )
                    }
                }
            }
            is ParsedRepoPayload.Nuvio -> parsed.plugins
            is ParsedRepoPayload.Stremio -> listOf(parsed.addonPlugin)
        }

        val merged = incomingPlugins.map { remote ->
            val local = existingPlugins[remote.id]
            if (local == null) {
                remote
            } else if (remote.repoType == RepoType.STREMIO) {
                remote.copy(
                    isEnabled = local.isEnabled,
                    installState = PluginInstallState.INSTALLED,
                    lastError = null
                )
            } else {
                val hasInstalledFile = installer.isInstalledOnDisk(local)
                val newState = when {
                    remote.installState == PluginInstallState.UNSUPPORTED_API -> PluginInstallState.UNSUPPORTED_API
                    hasInstalledFile && remote.version > local.version -> PluginInstallState.UPDATE_AVAILABLE
                    hasInstalledFile -> PluginInstallState.INSTALLED
                    local.installState == PluginInstallState.ERROR -> PluginInstallState.ERROR
                    else -> PluginInstallState.NOT_INSTALLED
                }
                remote.copy(
                    localFilePath = if (hasInstalledFile) local.localFilePath else null,
                    isEnabled = if (hasInstalledFile && newState != PluginInstallState.UNSUPPORTED_API) local.isEnabled else false,
                    installState = newState,
                    lastError = when {
                        remote.lastError != null -> remote.lastError
                        hasInstalledFile -> local.lastError
                        else -> null
                    }
                )
            }
        }

        repoDao.upsert(repo.toEntity(json))
        pluginDao.upsertAll(merged.map { it.toEntity(json) })
        return repo
    }
}
