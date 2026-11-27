// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.pluginhost.hikari

import android.content.Context
import android.util.Log
import com.hikari.ext.HikariProvider
import com.kestrel.core.di.IoDispatcher
import com.kestrel.domain.model.PluginMetadata
import com.kestrel.domain.model.RepoType
import com.kestrel.domain.provider.SourceProvider
import com.kestrel.pluginhost.AdapterRegistry
import com.kestrel.pluginhost.PluginSandbox
import dagger.hilt.android.qualifiers.ApplicationContext
import dalvik.system.DexClassLoader
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Dynamically loads `.hiki` DEX extension archives via [DexClassLoader] and registers
 * their [HikariProvider] implementation as a Kestrel [SourceProvider].
 */
@Singleton
class HikiPluginLoader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val adapterRegistry: AdapterRegistry,
    private val sandbox: PluginSandbox,
    private val json: Json,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    private data class LoadedHikiRecord(
        val pluginId: String,
        val filePath: String,
        val providers: List<SourceProvider>
    )

    private val mutex = Mutex()
    private val loadedByPluginId = ConcurrentHashMap<String, LoadedHikiRecord>()

    suspend fun loadPlugin(plugin: PluginMetadata): Result<List<SourceProvider>> =
        withContext(ioDispatcher) {
            if (plugin.repoType != RepoType.HIKARI) {
                return@withContext Result.success(emptyList())
            }
            val path = plugin.localFilePath
            if (path.isNullOrBlank()) {
                return@withContext Result.failure(IllegalStateException("Hikari plugin file is not installed"))
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
                    require(file.exists() && file.length() > 0L) { "Missing .hiki file at $path" }
                    // Android 14+ refuses dynamic DEX loading unless the archive is read-only.
                    runCatching { file.setReadOnly() }

                    val dexOutDir = File(context.codeCacheDir, "hiki_dex/${plugin.id}")
                    val stampFile = File(dexOutDir, ".hiki_stamp")
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

                    val mainClassName = readMainClass(classLoader, file)
                    val loadedClass = classLoader.loadClass(mainClassName)
                    val instance = instantiateProvider(loadedClass)

                    val providerId = "${plugin.id}:${instance.id.ifBlank { plugin.internalName }}"
                    val adapter = HikariAdapter(
                        plugin = plugin,
                        providerId = providerId,
                        provider = instance,
                        sandbox = sandbox
                    )
                    adapterRegistry.register(adapter)
                    loadedByPluginId[plugin.id] = LoadedHikiRecord(
                        pluginId = plugin.id,
                        filePath = file.absolutePath,
                        providers = listOf(adapter)
                    )
                    sandbox.markHealthy(plugin.id)
                    Log.i(TAG, "Loaded Hikari extension ${plugin.displayName} ($mainClassName)")
                    listOf<SourceProvider>(adapter)
                }.onFailure { err ->
                    Log.e(TAG, "Failed to load Hikari plugin ${plugin.displayName}", err)
                    sandbox.markError(plugin.id, err.message ?: "Hikari DEX load failed")
                    unloadInternal(plugin.id)
                }
            }
        }

    suspend fun unloadPlugin(pluginId: String) {
        withContext(ioDispatcher) {
            mutex.withLock {
                unloadInternal(pluginId)
            }
        }
    }

    private fun unloadInternal(pluginId: String) {
        loadedByPluginId.remove(pluginId)
        adapterRegistry.unregisterByPlugin(pluginId)
    }

    private fun readMainClass(loader: ClassLoader, file: File): String {
        val rawText = loader.getResourceAsStream("manifest.json")?.bufferedReader()?.use { it.readText() }
            ?: ZipFile(file).use { zip ->
                val entry = zip.getEntry("manifest.json")
                    ?: error("Missing manifest.json in ${file.name}")
                zip.getInputStream(entry).bufferedReader().use { it.readText() }
            }
        val obj = json.parseToJsonElement(rawText).jsonObject
        val mainClass = (obj["mainClass"] as? JsonPrimitive)?.contentOrNull
            ?: (obj["pluginClassName"] as? JsonPrimitive)?.contentOrNull
        return mainClass?.takeIf { it.isNotBlank() }
            ?: error("Missing mainClass in Hikari manifest.json")
    }

    private fun instantiateProvider(clazz: Class<*>): HikariProvider {
        val instance = runCatching {
            val ctor = clazz.getDeclaredConstructor()
            ctor.isAccessible = true
            ctor.newInstance()
        }.getOrElse { ctorErr ->
            runCatching {
                clazz.getDeclaredField("INSTANCE").apply { isAccessible = true }.get(null)
            }.getOrElse {
                throw IllegalStateException("Cannot instantiate ${clazz.name}: ${ctorErr.message}", ctorErr)
            }
        }
        return instance as? HikariProvider
            ?: error("${clazz.name} does not implement com.hikari.ext.HikariProvider")
    }

    companion object {
        private const val TAG = "HikiPluginLoader"
    }
}
