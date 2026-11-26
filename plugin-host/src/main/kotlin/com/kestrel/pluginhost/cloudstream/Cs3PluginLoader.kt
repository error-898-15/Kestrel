// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.pluginhost.cloudstream

import android.content.Context
import android.content.res.AssetManager
import android.content.res.Resources
import android.util.Log
import com.kestrel.core.di.IoDispatcher
import com.kestrel.domain.model.PluginMetadata
import com.kestrel.domain.model.RepoType
import com.kestrel.domain.provider.SourceProvider
import com.kestrel.domain.repository.PluginRuntimeLoader
import com.kestrel.pluginhost.AdapterRegistry
import com.kestrel.pluginhost.PluginSandbox
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.extractorApis
import dagger.hilt.android.qualifiers.ApplicationContext
import dalvik.system.DexClassLoader
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Loads CloudStream .cs3 DEX archives at runtime using [DexClassLoader],
 * reads manifest.json, instantiates the Plugin subclass, calls load(context),
 * and collects registered MainAPI & ExtractorApi instances.
 */
@Singleton
class Cs3PluginLoader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val adapterRegistry: AdapterRegistry,
    private val sandbox: PluginSandbox,
    private val json: Json,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : PluginRuntimeLoader {

    private data class LoadedPluginRecord(
        val pluginId: String,
        val filePath: String,
        val instance: BasePlugin,
        val providers: List<SourceProvider>
    )

    private val mutex = Mutex()
    private val loadedByPluginId = ConcurrentHashMap<String, LoadedPluginRecord>()

    init {
        CloudStreamApp.init(context)
        LoadResponse.malIdPrefix = "mal"
        LoadResponse.kitsuIdPrefix = "kitsu"
        LoadResponse.aniListIdPrefix = "anilist"
        LoadResponse.simklIdPrefix = "simkl"
    }

    override val activeProviders: Flow<List<SourceProvider>> =
        adapterRegistry.providers.map { it.values.toList() }

    override suspend fun loadPlugin(plugin: PluginMetadata): Result<List<SourceProvider>> =
        withContext(ioDispatcher) {
            if (plugin.repoType != RepoType.CLOUDSTREAM) {
                return@withContext Result.success(emptyList())
            }
            if (plugin.apiVersion !in 0..PluginSandbox.SUPPORTED_CLOUDSTREAM_API_VERSION) {
                val msg = "Skipped ${plugin.displayName}: unsupported apiVersion ${plugin.apiVersion}"
                sandbox.markError(plugin.id, msg)
                return@withContext Result.failure(IllegalStateException(msg))
            }

            val path = plugin.localFilePath
            if (path.isNullOrBlank()) {
                return@withContext Result.failure(IllegalStateException("Plugin file is not installed"))
            }

            mutex.withLock {
                loadedByPluginId[plugin.id]?.let { existing ->
                    if (existing.filePath == path && File(path).exists()) {
                        return@withContext Result.success(existing.providers)
                    }
                    unloadInternal(plugin.id)
                }

                runCatching {
                    val file = File(path)
                    require(file.exists() && file.length() > 0L) { "Missing .cs3 file at $path" }
                    // Android 14+ refuses dynamic code loading from writable files.
                    runCatching { file.setReadOnly() }

                    val dexOutDir = File(context.codeCacheDir, "cs3_dex/${plugin.id}")
                    val stampFile = File(dexOutDir, ".cs3_stamp")
                    val currentStamp = "${file.length()}:${file.lastModified()}"
                    if (dexOutDir.exists() && runCatching { stampFile.readText() }.getOrNull() != currentStamp) {
                        dexOutDir.deleteRecursively()
                    }
                    dexOutDir.mkdirs()
                    runCatching { stampFile.writeText(currentStamp) }

                    val classLoader = DexClassLoader(
                        file.absolutePath,
                        dexOutDir.absolutePath,
                        null,
                        context.classLoader
                    )

                    val manifest = readManifest(classLoader, file)
                    val className = manifest.pluginClassName
                        ?.takeIf { it.isNotBlank() }
                        ?: error("Missing pluginClassName in manifest.json")

                    @Suppress("UNCHECKED_CAST")
                    val pluginClass = classLoader.loadClass(className) as Class<out BasePlugin>
                    val instance = pluginClass.getDeclaredConstructor().newInstance()
                    instance.filename = file.absolutePath

                    if (manifest.requiresResources && instance is Plugin) {
                        attachPluginResources(file, instance)
                    }

                    val beforeApis = APIHolder.allProviders.toSet()

                    if (instance is Plugin) {
                        instance.load(context)
                    } else {
                        instance.load()
                    }

                    val bySourcePlugin = APIHolder.allProviders.filter { it.sourcePlugin == file.absolutePath }
                    val newlyAdded = APIHolder.allProviders.filter { it !in beforeApis }
                    val matchingApis = (bySourcePlugin + newlyAdded).distinct()

                    val adapters = matchingApis.mapIndexed { idx, api ->
                        if (api.sourcePlugin.isNullOrBlank()) {
                            api.sourcePlugin = file.absolutePath
                        }
                        val providerId = "${plugin.id}:${api.name.ifBlank { "provider_$idx" }}"
                        CloudStreamAdapter(
                            pluginId = plugin.id,
                            providerId = providerId,
                            api = api,
                            sandbox = sandbox
                        )
                    }

                    adapters.forEach { adapterRegistry.register(it) }
                    loadedByPluginId[plugin.id] = LoadedPluginRecord(
                        pluginId = plugin.id,
                        filePath = file.absolutePath,
                        instance = instance,
                        providers = adapters
                    )
                    sandbox.markHealthy(plugin.id)
                    Log.i(TAG, "Loaded ${plugin.displayName} (${adapters.size} MainAPI provider(s))")
                    adapters
                }.onFailure { err ->
                    Log.e(TAG, "Failed to load ${plugin.displayName}", err)
                    sandbox.markError(plugin.id, err.message ?: "Load failed")
                    unloadInternal(plugin.id)
                }
            }
        }

    override suspend fun unloadPlugin(pluginId: String) {
        withContext(ioDispatcher) {
            mutex.withLock {
                unloadInternal(pluginId)
            }
        }
    }

    override fun getProvider(providerId: String): SourceProvider? =
        adapterRegistry.get(providerId)

    private fun unloadInternal(pluginId: String) {
        val record = loadedByPluginId.remove(pluginId)
        adapterRegistry.unregisterByPlugin(pluginId)
        if (record != null) {
            runCatching { record.instance.beforeUnload() }
            APIHolder.allProviders.removeAll { it.sourcePlugin == record.filePath }
            extractorApis.removeAll { it.sourcePlugin == record.filePath }
        }
    }

    private fun readManifest(loader: ClassLoader, file: File): BasePlugin.Manifest {
        val rawText = loader.getResourceAsStream("manifest.json")?.bufferedReader()?.use { it.readText() }
            ?: ZipFile(file).use { zip ->
                val entry = zip.getEntry("manifest.json")
                    ?: error("No manifest.json in ${file.name}")
                zip.getInputStream(entry).bufferedReader().use { it.readText() }
            }
        return json.decodeFromString(BasePlugin.Manifest.serializer(), rawText)
    }

    private fun attachPluginResources(file: File, plugin: Plugin) {
        runCatching {
            val assets = AssetManager::class.java.getDeclaredConstructor().newInstance()
            val addAssetPath = AssetManager::class.java.getMethod("addAssetPath", String::class.java)
            addAssetPath.invoke(assets, file.absolutePath)
            @Suppress("DEPRECATION")
            plugin.resources = Resources(
                assets,
                context.resources.displayMetrics,
                context.resources.configuration
            )
        }.onFailure {
            Log.w(TAG, "Could not attach resources for ${file.name}: ${it.message}")
        }
    }

    companion object {
        private const val TAG = "Cs3PluginLoader"
    }
}
