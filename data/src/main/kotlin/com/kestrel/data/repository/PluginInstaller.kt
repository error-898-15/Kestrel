// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.data.repository

import android.content.Context
import com.kestrel.domain.model.PluginMetadata
import com.kestrel.domain.model.RepoType
import com.kestrel.pluginhost.PluginSandbox
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
private data class Cs3ArchiveManifest(
    val name: String? = null,
    val pluginClassName: String? = null,
    val requiresResources: Boolean = false,
    val version: Int? = null
)

@Singleton
class PluginInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val json: Json
) {
    private fun pluginDirectory(repoId: String): File =
        File(context.filesDir, "plugins/$repoId").apply { mkdirs() }

    fun targetFile(plugin: PluginMetadata): File {
        val ext = when (plugin.repoType) {
            RepoType.CLOUDSTREAM -> "cs3"
            RepoType.NUVIO -> "js"
            RepoType.STREMIO -> "json"
        }
        val safeName = plugin.internalName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(pluginDirectory(plugin.repoId), "$safeName.$ext")
    }

    fun downloadAndVerify(plugin: PluginMetadata): File {
        require(plugin.apiVersion in 1..PluginSandbox.SUPPORTED_CLOUDSTREAM_API_VERSION) {
            "Unsupported plugin API version ${plugin.apiVersion}"
        }

        val destFile = targetFile(plugin)
        val tmpFile = File(destFile.parentFile, "${destFile.name}.tmp")
        if (tmpFile.exists()) tmpFile.delete()

        val request = Request.Builder()
            .url(plugin.downloadUrl)
            .header("User-Agent", "Kestrel/0.1 (Android)")
            .get()
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("Download failed with HTTP ${response.code}")
            }
            val body = response.body ?: error("Empty download response")
            tmpFile.outputStream().use { out ->
                body.byteStream().copyTo(out)
            }
        }

        when (plugin.repoType) {
            RepoType.CLOUDSTREAM -> verifyCs3Archive(tmpFile)
            RepoType.NUVIO -> {
                require(tmpFile.length() > 0L) { "Downloaded Nuvio scraper is empty" }
            }
            RepoType.STREMIO -> Unit
        }

        if (destFile.exists()) {
            destFile.setWritable(true)
            destFile.delete()
        }
        if (!tmpFile.renameTo(destFile)) {
            tmpFile.copyTo(destFile, overwrite = true)
            tmpFile.delete()
        }

        // Required by Android 14+ before loading DEX code via PathClassLoader/DexClassLoader.
        if (plugin.repoType == RepoType.CLOUDSTREAM) {
            destFile.setReadOnly()
        }

        return destFile
    }

    fun verifyCs3Archive(file: File) {
        require(file.exists() && file.length() > 4) { "Invalid .cs3 archive: file is empty" }
        ZipFile(file).use { zip ->
            val dexEntry = zip.getEntry("classes.dex")
                ?: error("Invalid .cs3 archive: missing classes.dex")
            require(dexEntry.size > 0 || dexEntry.compressedSize > 0) {
                "Invalid .cs3 archive: empty classes.dex"
            }
            val manifestEntry = zip.getEntry("manifest.json")
                ?: error("Invalid .cs3 archive: missing manifest.json")
            val manifestText = zip.getInputStream(manifestEntry).bufferedReader().use { it.readText() }
            val manifest = json.decodeFromString(Cs3ArchiveManifest.serializer(), manifestText)
            require(!manifest.pluginClassName.isNullOrBlank()) {
                "Invalid .cs3 manifest.json: missing pluginClassName"
            }
        }
    }

    fun deletePluginFile(localFilePath: String?) {
        if (localFilePath.isNullOrBlank()) return
        val file = File(localFilePath)
        if (file.exists()) {
            file.setWritable(true)
            file.delete()
        }
    }

    fun deleteRepoDirectory(repoId: String) {
        val dir = File(context.filesDir, "plugins/$repoId")
        if (dir.exists()) {
            dir.walkBottomUp().forEach { it.setWritable(true) }
            dir.deleteRecursively()
        }
    }
}
