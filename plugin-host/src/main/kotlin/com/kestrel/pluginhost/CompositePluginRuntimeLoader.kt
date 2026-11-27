// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.pluginhost

import com.kestrel.core.di.IoDispatcher
import com.kestrel.domain.model.PluginMetadata
import com.kestrel.domain.model.ProviderSettingField
import com.kestrel.domain.model.ProviderSettingOption
import com.kestrel.domain.model.ProviderSettingType
import com.kestrel.domain.model.ProviderSettingsUiTarget
import com.kestrel.domain.model.RepoType
import com.kestrel.domain.provider.SourceProvider
import com.kestrel.domain.repository.PluginRuntimeLoader
import com.kestrel.pluginhost.cloudstream.Cs3PluginLoader
import com.kestrel.pluginhost.nuvio.CinemetaCatalogClient
import com.kestrel.pluginhost.nuvio.NuvioAdapter
import com.kestrel.pluginhost.nuvio.NuvioJsEngine
import com.kestrel.pluginhost.settings.ProviderSettingsStore
import com.kestrel.pluginhost.stremio.StremioAdapter
import com.kestrel.pluginhost.stremio.StremioClient
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Routes plugin load/unload/settings requests across CloudStream (.cs3),
 * Nuvio (QuickJS), and Stremio HTTP add-on adapters.
 */
@Singleton
class CompositePluginRuntimeLoader @Inject constructor(
    private val cs3PluginLoader: Cs3PluginLoader,
    private val nuvioJsEngine: NuvioJsEngine,
    private val cinemetaCatalogClient: CinemetaCatalogClient,
    private val stremioClient: StremioClient,
    private val settingsStore: ProviderSettingsStore,
    private val adapterRegistry: AdapterRegistry,
    private val sandbox: PluginSandbox,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : PluginRuntimeLoader {

    private val _otherConfigurableIds = MutableStateFlow<Set<String>>(emptySet())
    private val _localHomeReloadRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    private val pendingReloadPluginIds = Collections.synchronizedSet(HashSet<String>())

    override val activeProviders: Flow<List<SourceProvider>> =
        adapterRegistry.providers.map { it.values.toList() }

    override val homeReloadRequests: Flow<Unit> =
        merge(cs3PluginLoader.homeReloadRequests, _localHomeReloadRequests)

    override val configurablePluginIds: Flow<Set<String>> =
        combine(cs3PluginLoader.configurablePluginIds, _otherConfigurableIds) { cs3Ids, otherIds ->
            cs3Ids + otherIds
        }

    override suspend fun loadPlugin(plugin: PluginMetadata): Result<List<SourceProvider>> =
        when (plugin.repoType) {
            RepoType.CLOUDSTREAM -> cs3PluginLoader.loadPlugin(plugin)

            RepoType.NUVIO -> withContext(ioDispatcher) {
                runCatching {
                    val filePath = plugin.localFilePath ?: error("Nuvio scraper file is not installed")
                    require(File(filePath).exists()) { "Missing Nuvio script at $filePath" }
                    val adapter = NuvioAdapter(
                        plugin = plugin,
                        engine = nuvioJsEngine,
                        catalogClient = cinemetaCatalogClient,
                        sandbox = sandbox,
                        settingsStore = settingsStore
                    )
                    adapterRegistry.register(adapter)
                    _otherConfigurableIds.update { it + plugin.id }
                    sandbox.markHealthy(plugin.id)
                    if (pendingReloadPluginIds.remove(plugin.id)) {
                        _localHomeReloadRequests.tryEmit(Unit)
                    }
                    listOf(adapter)
                }.onFailure { err ->
                    pendingReloadPluginIds.remove(plugin.id)
                    sandbox.markError(plugin.id, err.message ?: "Nuvio load failed")
                }
            }

            RepoType.STREMIO -> withContext(ioDispatcher) {
                runCatching {
                    val headers = settingsStore.getHeaders(plugin.id)
                    val manifest = stremioClient.fetchManifest(plugin.downloadUrl, headers)
                    val adapter = StremioAdapter(
                        plugin = plugin,
                        manifest = manifest,
                        client = stremioClient,
                        sandbox = sandbox,
                        settingsStore = settingsStore
                    )
                    adapterRegistry.register(adapter)
                    _otherConfigurableIds.update { it + plugin.id }
                    sandbox.markHealthy(plugin.id)
                    if (pendingReloadPluginIds.remove(plugin.id)) {
                        _localHomeReloadRequests.tryEmit(Unit)
                    }
                    listOf(adapter)
                }.onFailure { err ->
                    pendingReloadPluginIds.remove(plugin.id)
                    sandbox.markError(plugin.id, err.message ?: "Stremio manifest load failed")
                }
            }
        }

    override suspend fun unloadPlugin(pluginId: String) {
        cs3PluginLoader.unloadPlugin(pluginId)
        _otherConfigurableIds.update { it - pluginId }
        adapterRegistry.unregisterByPlugin(pluginId)
    }

    override fun getProvider(providerId: String): SourceProvider? =
        adapterRegistry.get(providerId)

    override suspend fun openOrLoadSettings(plugin: PluginMetadata): Result<ProviderSettingsUiTarget> =
        when (plugin.repoType) {
            RepoType.CLOUDSTREAM -> cs3PluginLoader.openOrLoadSettings(plugin)

            RepoType.NUVIO -> withContext(ioDispatcher) {
                runCatching {
                    val filePath = plugin.localFilePath
                        ?: error("Install ${plugin.displayName} before configuring settings")
                    val file = File(filePath)
                    require(file.exists()) { "Missing scraper file for ${plugin.displayName}" }
                    val fields = nuvioJsEngine.inspectSettingsSchema(
                        pluginId = plugin.id,
                        scraperId = plugin.internalName,
                        scriptCode = file.readText()
                    )
                    ProviderSettingsUiTarget.NuvioConfig(
                        pluginId = plugin.id,
                        displayName = plugin.displayName,
                        fields = fields,
                        customHeaders = settingsStore.getHeaders(plugin.id)
                    )
                }
            }

            RepoType.STREMIO -> withContext(ioDispatcher) {
                runCatching {
                    val savedHeaders = settingsStore.getHeaders(plugin.id)
                    val savedValues = settingsStore.getValues(plugin.id)
                    val manifest = runCatching {
                        stremioClient.fetchManifest(plugin.downloadUrl, savedHeaders)
                    }.getOrNull()

                    val fields = manifest?.config.orEmpty().mapIndexed { idx, field ->
                        val type = when (field.type.lowercase()) {
                            "password" -> ProviderSettingType.PASSWORD
                            "number" -> ProviderSettingType.NUMBER
                            "checkbox", "boolean", "toggle" -> ProviderSettingType.TOGGLE
                            "select" -> ProviderSettingType.SELECT
                            else -> ProviderSettingType.TEXT
                        }
                        val defaultVal = (field.default as? JsonPrimitive)?.contentOrNull.orEmpty()
                        val options = field.options.mapNotNull { el ->
                            when (el) {
                                is JsonPrimitive -> el.contentOrNull?.takeIf { it.isNotBlank() }
                                    ?.let { ProviderSettingOption(it, it) }
                                is JsonObject -> {
                                    val value = (el["value"] as? JsonPrimitive)?.contentOrNull
                                        ?: (el["label"] as? JsonPrimitive)?.contentOrNull
                                    val label = (el["label"] as? JsonPrimitive)?.contentOrNull ?: value
                                    if (!value.isNullOrBlank()) {
                                        ProviderSettingOption(label = label ?: value, value = value)
                                    } else {
                                        null
                                    }
                                }
                                else -> null
                            }
                        }
                        val key = field.key.ifBlank { "config_$idx" }
                        ProviderSettingField(
                            key = key,
                            type = type,
                            label = field.title.ifBlank { key },
                            defaultValue = defaultVal,
                            currentValue = savedValues[key] ?: defaultVal,
                            options = options,
                            required = field.required
                        )
                    }

                    ProviderSettingsUiTarget.StremioConfig(
                        pluginId = plugin.id,
                        displayName = plugin.displayName,
                        manifestUrl = plugin.downloadUrl,
                        configureWebUrl = stremioClient.configureUrlOf(plugin.downloadUrl, manifest),
                        isConfigurable = manifest?.behaviorHints?.configurable == true ||
                            !manifest?.behaviorHints?.configurationURL.isNullOrBlank() ||
                            fields.isNotEmpty(),
                        fields = fields,
                        customHeaders = savedHeaders
                    )
                }
            }
        }

    override suspend fun saveProviderSettings(
        plugin: PluginMetadata,
        values: Map<String, String>,
        customHeaders: Map<String, String>
    ): Result<Unit> = withContext(ioDispatcher) {
        runCatching {
            when (plugin.repoType) {
                RepoType.CLOUDSTREAM -> Unit
                RepoType.NUVIO, RepoType.STREMIO -> {
                    settingsStore.saveValues(plugin.id, values)
                    settingsStore.saveHeaders(plugin.id, customHeaders)
                    if (plugin.isEnabled) {
                        // loadPlugin() will emit homeReloadRequests after registering the updated adapter.
                        pendingReloadPluginIds.add(plugin.id)
                    } else {
                        _localHomeReloadRequests.tryEmit(Unit)
                    }
                }
            }
            Unit // <-- FIX: force Result<Unit> (the if/else above yields Boolean)
        }
    }
}
