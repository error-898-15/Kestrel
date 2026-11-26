// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.pluginhost

import com.kestrel.core.di.IoDispatcher
import com.kestrel.domain.model.PluginMetadata
import com.kestrel.domain.model.RepoType
import com.kestrel.domain.provider.SourceProvider
import com.kestrel.domain.repository.PluginRuntimeLoader
import com.kestrel.pluginhost.cloudstream.Cs3PluginLoader
import com.kestrel.pluginhost.nuvio.NuvioAdapter
import com.kestrel.pluginhost.nuvio.NuvioJsEngine
import com.kestrel.pluginhost.stremio.StremioAdapter
import com.kestrel.pluginhost.stremio.StremioClient
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Routes plugin load/unload requests across CloudStream (.cs3), Nuvio (QuickJS),
 * and Stremio HTTP add-on adapters.
 */
@Singleton
class CompositePluginRuntimeLoader @Inject constructor(
    private val cs3PluginLoader: Cs3PluginLoader,
    private val nuvioJsEngine: NuvioJsEngine,
    private val stremioClient: StremioClient,
    private val adapterRegistry: AdapterRegistry,
    private val sandbox: PluginSandbox,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : PluginRuntimeLoader {

    override val activeProviders: Flow<List<SourceProvider>> =
        adapterRegistry.providers.map { it.values.toList() }

    override suspend fun loadPlugin(plugin: PluginMetadata): Result<List<SourceProvider>> =
        when (plugin.repoType) {
            RepoType.CLOUDSTREAM -> cs3PluginLoader.loadPlugin(plugin)

            RepoType.NUVIO -> withContext(ioDispatcher) {
                runCatching {
                    val adapter = NuvioAdapter(
                        plugin = plugin,
                        engine = nuvioJsEngine,
                        sandbox = sandbox
                    )
                    adapterRegistry.register(adapter)
                    sandbox.markHealthy(plugin.id)
                    listOf(adapter)
                }.onFailure { err ->
                    sandbox.markError(plugin.id, err.message ?: "Nuvio load failed")
                }
            }

            RepoType.STREMIO -> withContext(ioDispatcher) {
                runCatching {
                    val manifest = stremioClient.fetchManifest(plugin.downloadUrl)
                    val adapter = StremioAdapter(
                        plugin = plugin,
                        manifest = manifest,
                        client = stremioClient,
                        sandbox = sandbox
                    )
                    adapterRegistry.register(adapter)
                    sandbox.markHealthy(plugin.id)
                    listOf(adapter)
                }.onFailure { err ->
                    sandbox.markError(plugin.id, err.message ?: "Stremio manifest load failed")
                }
            }
        }

    override suspend fun unloadPlugin(pluginId: String) {
        cs3PluginLoader.unloadPlugin(pluginId)
        adapterRegistry.unregisterByPlugin(pluginId)
    }

    override fun getProvider(providerId: String): SourceProvider? =
        adapterRegistry.get(providerId)
}
