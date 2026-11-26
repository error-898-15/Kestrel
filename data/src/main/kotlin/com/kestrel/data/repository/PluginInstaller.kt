// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.data.repository

import android.content.Context
import com.kestrel.data.remote.RepoUrlResolver
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
    private val urlResolver: RepoUrlResolver,
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

    fun isInstalledOnDisk(plugin: PluginMetadata): Boolean {
        if (plugin.repoType == RepoType.STREMIO) return true
        val path = plugin.localFilePath ?: return false
        val file = File(path)
        return file.exists() && file.length() > 0L
    }

    fun downloadAndVerify(plugin: PluginMetadata): File {
        if (plugin.repoType == RepoType.CLOUDSTREAM) {
            require(plugin.apiVersion in 0..PluginSandbox.SUPPORTED_CLOUDSTREAM_API_VERSION) {
                "Unsupported plugin API version ${plugin.apiVersion}"
            }
        }

        val destFile = targetFile(plugin)
        val tmpFile = File(destFile.parentFile, "${destFile.name}.tmp")
        if (tmpFile.exists()) tmpFile.delete()

        val candidates = buildDownloadCandidates(plugin)
        var lastError: Throwable? = null
        var downloaded = false

        for (candidateUrl in candidates) {
            try {
                downloadToFile(candidateUrl, tmpFile)
                downloaded = true
                break
            } catch (t: Throwable) {
                lastError = t
                if (tmpFile.exists()) tmpFile.delete()
            }
        }

        if (!downloaded) {
            throw lastError ?: IllegalStateException("Download failed for ${plugin.displayName}")
        }

        when (plugin.repoType) {
            RepoType.CLOUDSTREAM -> verifyCs3Archive(tmpFile)
            RepoType.NUVIO -> verifyNuvioScript(tmpFile)
            RepoType.STREMIO -> require(tmpFile.length() > 0L) { "Empty Stremio manifest" }
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

    private fun buildDownloadCandidates(plugin: PluginMetadata): List<String> {
        val primary = urlResolver.rewriteGitHostUrl(plugin.downloadUrl.trim()).replace(" ", "%20")
        if (plugin.repoType != RepoType.NUVIO) return listOf(primary)

        val candidates = mutableListOf(primary)
        val base = primary.substringBeforeLast('/')
        val fileName = primary.substringAfterLast('/')
        if (!primary.contains("/providers/", ignoreCase = true) && fileName.isNotBlank()) {
            candidates.add("$base/providers/$fileName")
        }
        return candidates.distinct()
    }

    private fun downloadToFile(url: String, outFile: File) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", RepoUrlResolver.BROWSER_USER_AGENT)
            .header("Accept", "*/*")
            .get()
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("HTTP ${response.code} downloading $url")
            }
            val body = response.body ?: error("Empty download response from $url")
            outFile.outputStream().use { out ->
                body.byteStream().copyTo(out)
            }
        }
    }

    private fun verifyNuvioScript(file: File) {
        require(file.exists() && file.length() > 0L) { "Downloaded Nuvio scraper is empty" }
        val preview = file.bufferedReader().use { reader ->
            val buf = CharArray(512)
            val read = reader.read(buf)
            if (read > 0) String(buf, 0, read).trimStart() else ""
        }
        require(
            !preview.startsWith("<!DOCTYPE html", ignoreCase = true) &&
                !preview.startsWith("<html", ignoreCase = true)
        ) {
            "Nuvio scraper URL returned an HTML error page instead of JavaScript"
        }
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
