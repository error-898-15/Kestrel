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
import com.kestrel.domain.model.ProviderSettingsUiTarget
import com.kestrel.domain.model.RepoConfig
import com.kestrel.domain.model.RepoType
import com.kestrel.domain.repository.ExtensionRepository
import com.kestrel.domain.repository.PluginRuntimeLoader
import com.kestrel.pluginhost.PluginSandbox
import com.kestrel.pluginhost.settings.ProviderSettingsStore
import com.kestrel.pluginhost.stremio.StremioClient
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
    private val stremioClient: StremioClient,
    private val settingsStore: ProviderSettingsStore,
    private val json: Json,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : ExtensionRepository {

    override val repositories: Flow<List<RepoConfig>> =
        repoDao.observeAll().map { rows -> rows.map { it.toDomain(json) } }

    override val plugins: Flow<List<PluginMetadata>> =
        pluginDao.observeAll().map { rows -> rows.map { it.toDomain(json) } }

    override val configurablePluginIds: Flow<Set<String>> =
        runtimeLoader.configurablePluginIds

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
                syncParsedRepo(parsed, existingRepo = existing)
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
        repoPlugins.forEach {
            runtimeLoader.unloadPlugin(it.id)
            settingsStore.clear(it.id)
        }
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
                                installPlugin(plugin.id).mapCatching { it.lastError == null }.getOrDefault(false)
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
        settingsStore.clear(pluginId)
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
                    pluginDao.upsert(existing.copy(isEnabled = true, lastError = null).toEntity(json))
                } else {
                    runtimeLoader.unloadPlugin(pluginId)
                    pluginDao.setEnabled(pluginId, false)
                }
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

    override suspend fun openOrLoadSettings(pluginId: String): Result<ProviderSettingsUiTarget> =
        withContext(ioDispatcher) {
            val plugin = pluginDao.getById(pluginId)?.toDomain(json)
                ?: return@withContext Result.failure(IllegalArgumentException("Plugin not found"))
            runtimeLoader.openOrLoadSettings(plugin)
        }

    override suspend fun saveNuvioSettings(
        pluginId: String,
        values: Map<String, String>,
        customHeaders: Map<String, String>
    ): Result<Unit> = withContext(ioDispatcher) {
        val plugin = pluginDao.getById(pluginId)?.toDomain(json)
            ?: return@withContext Result.failure(IllegalArgumentException("Plugin not found"))
        runtimeLoader.saveProviderSettings(plugin, values, customHeaders).onSuccess {
            if (plugin.isEnabled) {
                runtimeLoader.unloadPlugin(plugin.id)
                runtimeLoader.loadPlugin(plugin)
            }
        }
    }

    override suspend fun saveStremioSettings(
        pluginId: String,
        manifestUrl: String,
        configValues: Map<String, String>,
        customHeaders: Map<String, String>
    ): Result<PluginMetadata> = withContext(ioDispatcher) {
        runCatching {
            val existing = pluginDao.getById(pluginId)?.toDomain(json)
                ?: error("Stremio addon not found")
            require(existing.repoType == RepoType.STREMIO) { "Not a Stremio addon" }

            val existingNormalized = stremioClient.normalizeManifestUrl(existing.downloadUrl)
            val normalizedInput = stremioClient.normalizeManifestUrl(
                manifestUrl.ifBlank { existing.downloadUrl }
            )

            // If the user kept the same base URL while editing/clearing manifest.config fields,
            // compute the Stremio SDK JSON-encoded manifest URL and probe it; only preserve an
            // existing web-configurator URL when no configValues were modified.
            val candidateUrl = if (normalizedInput == existingNormalized && configValues.isNotEmpty()) {
                val encodedUrl = stremioClient.buildConfigEncodedManifestUrl(normalizedInput, configValues)
                if (encodedUrl != normalizedInput) {
                    val probe = runCatching { stremioClient.fetchManifest(encodedUrl, customHeaders) }
                    if (probe.isSuccess) encodedUrl else normalizedInput
                } else {
                    normalizedInput
                }
            } else {
                normalizedInput
            }

            val manifest = stremioClient.fetchManifest(candidateUrl, customHeaders)
            val updatedPlugin = existing.copy(
                downloadUrl = candidateUrl,
                displayName = manifest.name.ifBlank { existing.displayName },
                description = manifest.description.ifBlank { existing.description },
                iconUrl = manifest.logo ?: existing.iconUrl,
                tvTypes = manifest.types.ifEmpty { existing.tvTypes },
                lastError = null
            )

            runtimeLoader.saveProviderSettings(updatedPlugin, configValues, customHeaders).getOrThrow()

            repoDao.getById(existing.repoId)?.toDomain(json)?.let { repo ->
                repoDao.upsert(
                    repo.copy(
                        url = candidateUrl,
                        resolvedUrl = candidateUrl,
                        name = manifest.name.ifBlank { repo.name },
                        description = manifest.description.ifBlank { repo.description },
                        isHealthy = true,
                        lastCheckedEpochMs = System.currentTimeMillis(),
                        lastError = null
                    ).toEntity(json)
                )
            }

            pluginDao.upsert(updatedPlugin.toEntity(json))

            if (updatedPlugin.isEnabled) {
                runtimeLoader.unloadPlugin(updatedPlugin.id)
                runtimeLoader.loadPlugin(updatedPlugin).getOrThrow()
            }

            updatedPlugin
        }
    }

    private suspend fun syncParsedRepo(
        parsed: ParsedRepoPayload,
        existingRepo: RepoConfig? = null
    ): RepoConfig {
        val targetRepoId = existingRepo?.id ?: parsed.repo.id
        val repo = parsed.repo.copy(
            id = targetRepoId,
            addedAtEpochMs = existingRepo?.addedAtEpochMs ?: parsed.repo.addedAtEpochMs
        )
        val existingPlugins = pluginDao.getByRepo(targetRepoId).map { it.toDomain(json) }
        val existingById = existingPlugins.associateBy { it.id }

        val incomingPlugins = when (parsed) {
            is ParsedRepoPayload.CloudStream -> {
                if (parsed.inlinePlugins.isNotEmpty()) {
                    parsed.inlinePlugins
                } else {
                    parsed.pluginListUrls.flatMap { listUrl ->
                        val (_, listBody) = urlResolver.fetchText(listUrl)
                        jsonParser.parseCloudStreamPluginsList(
                            repoId = targetRepoId,
                            listUrl = listUrl,
                            rawJson = listBody,
                            supportedApiVersion = PluginSandbox.SUPPORTED_CLOUDSTREAM_API_VERSION
                        )
                    }
                }
            }
            is ParsedRepoPayload.Nuvio -> parsed.plugins.map { it.copy(repoId = targetRepoId) }
            is ParsedRepoPayload.Stremio -> {
                val existingAddon = existingPlugins.firstOrNull { it.repoType == RepoType.STREMIO }
                val adjustedAddon = if (existingAddon != null) {
                    parsed.addonPlugin.copy(id = existingAddon.id, repoId = targetRepoId)
                } else {
                    parsed.addonPlugin.copy(repoId = targetRepoId)
                }
                listOf(adjustedAddon)
            }
        }

        val merged = incomingPlugins.map { remote ->
            val local = existingById[remote.id]
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
