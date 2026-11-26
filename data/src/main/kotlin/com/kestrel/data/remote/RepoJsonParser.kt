// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.data.remote

import com.kestrel.domain.model.PluginInstallState
import com.kestrel.domain.model.PluginMetadata
import com.kestrel.domain.model.RepoConfig
import com.kestrel.domain.model.RepoType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class CloudStreamRepoDto(
    val name: String = "Unnamed Repository",
    val description: String = "",
    val manifestVersion: Int = 1,
    val pluginLists: List<String> = emptyList()
)

@Serializable
data class CloudStreamPluginDto(
    val url: String = "",
    val name: String = "",
    val internalName: String = "",
    val version: Int = 1,
    val status: Int = 1,
    val language: String = "en",
    val authors: List<String> = emptyList(),
    val description: String = "",
    val iconUrl: String? = null,
    val tvTypes: List<String> = emptyList(),
    val apiVersion: Int = 1,
    val fileSize: Long? = null,
    val repositoryUrl: String? = null
)

@Serializable
data class NuvioManifestDto(
    val name: String = "Nuvio Repository",
    val description: String = "",
    val version: String? = null,
    val scrapers: List<NuvioScraperDto> = emptyList()
)

@Serializable
data class NuvioScraperDto(
    val id: String = "",
    val name: String = "",
    val filename: String = "",
    val url: String = "",
    val version: String = "1",
    val description: String = "",
    val supportedTypes: List<String> = emptyList(),
    val logo: String? = null,
    val language: String = "en",
    val enabled: Boolean = false
)

@Serializable
data class StremioManifestDto(
    val id: String = "",
    val name: String = "",
    val version: String = "1.0.0",
    val description: String = "",
    val logo: String? = null,
    val types: List<String> = emptyList(),
    val catalogs: List<JsonElement> = emptyList(),
    val resources: List<JsonElement> = emptyList()
)

sealed interface ParsedRepoPayload {
    val repo: RepoConfig

    data class CloudStream(
        override val repo: RepoConfig,
        val pluginListUrls: List<String>
    ) : ParsedRepoPayload

    data class Nuvio(
        override val repo: RepoConfig,
        val plugins: List<PluginMetadata>
    ) : ParsedRepoPayload

    data class Stremio(
        override val repo: RepoConfig,
        val addonPlugin: PluginMetadata
    ) : ParsedRepoPayload
}

@Singleton
class RepoJsonParser @Inject constructor(
    private val json: Json
) {
    fun detectType(rawJson: String, resolvedUrl: String = ""): RepoType {
        val obj: JsonObject = json.parseToJsonElement(rawJson).jsonObject
        return when {
            "pluginLists" in obj || "manifestVersion" in obj -> RepoType.CLOUDSTREAM
            "scrapers" in obj -> RepoType.NUVIO
            ("resources" in obj || "catalogs" in obj) && "id" in obj -> RepoType.STREMIO
            resolvedUrl.endsWith("/manifest.json", ignoreCase = true) && "id" in obj -> RepoType.STREMIO
            else -> throw IllegalArgumentException("Unrecognized repository manifest format")
        }
    }

    fun parseRepositoryManifest(
        rawInputUrl: String,
        resolvedUrl: String,
        rawJson: String,
        nowEpochMs: Long = System.currentTimeMillis()
    ): ParsedRepoPayload {
        val type = detectType(rawJson, resolvedUrl)
        val repoId = stableId("${type.name}:$resolvedUrl")

        return when (type) {
            RepoType.CLOUDSTREAM -> {
                val dto = json.decodeFromString(CloudStreamRepoDto.serializer(), rawJson)
                val resolvedLists = dto.pluginLists.map { resolveRelativeUrl(resolvedUrl, it) }
                ParsedRepoPayload.CloudStream(
                    repo = RepoConfig(
                        id = repoId,
                        url = rawInputUrl,
                        resolvedUrl = resolvedUrl,
                        name = dto.name.ifBlank { "CloudStream Repository" },
                        description = dto.description,
                        type = RepoType.CLOUDSTREAM,
                        manifestVersion = dto.manifestVersion,
                        pluginListUrls = resolvedLists,
                        addedAtEpochMs = nowEpochMs,
                        lastCheckedEpochMs = nowEpochMs,
                        isHealthy = true
                    ),
                    pluginListUrls = resolvedLists
                )
            }

            RepoType.NUVIO -> {
                val dto = json.decodeFromString(NuvioManifestDto.serializer(), rawJson)
                val repo = RepoConfig(
                    id = repoId,
                    url = rawInputUrl,
                    resolvedUrl = resolvedUrl,
                    name = dto.name.ifBlank { "Nuvio Scrapers" },
                    description = dto.description,
                    type = RepoType.NUVIO,
                    manifestVersion = 1,
                    addedAtEpochMs = nowEpochMs,
                    lastCheckedEpochMs = nowEpochMs,
                    isHealthy = true
                )
                val plugins = dto.scrapers.mapIndexed { index, scraper ->
                    val scriptPath = scraper.url.ifBlank { scraper.filename }
                    val scriptUrl = resolveRelativeUrl(resolvedUrl, scriptPath)
                    val internal = scraper.id.ifBlank { scraper.name.ifBlank { "scraper_$index" } }
                    PluginMetadata(
                        id = stableId("$repoId:$internal"),
                        repoId = repoId,
                        repoType = RepoType.NUVIO,
                        internalName = internal,
                        displayName = scraper.name.ifBlank { internal },
                        downloadUrl = scriptUrl,
                        version = scraper.version.filter { it.isDigit() }.toIntOrNull() ?: 1,
                        apiVersion = 1,
                        status = 1,
                        language = scraper.language.ifBlank { "en" },
                        description = scraper.description,
                        iconUrl = scraper.logo?.let { expandIconSize(resolveRelativeUrl(resolvedUrl, it)) },
                        tvTypes = scraper.supportedTypes,
                        // Nuvio scrapers always start disabled until the user explicitly enables them.
                        isEnabled = false,
                        installState = PluginInstallState.NOT_INSTALLED
                    )
                }
                ParsedRepoPayload.Nuvio(repo = repo, plugins = plugins)
            }

            RepoType.STREMIO -> {
                val dto = json.decodeFromString(StremioManifestDto.serializer(), rawJson)
                val repo = RepoConfig(
                    id = repoId,
                    url = rawInputUrl,
                    resolvedUrl = resolvedUrl,
                    name = dto.name.ifBlank { dto.id.ifBlank { "Stremio Add-on" } },
                    description = dto.description,
                    type = RepoType.STREMIO,
                    manifestVersion = 1,
                    addedAtEpochMs = nowEpochMs,
                    lastCheckedEpochMs = nowEpochMs,
                    isHealthy = true
                )
                val internal = dto.id.ifBlank { repoId }
                val addon = PluginMetadata(
                    id = stableId("$repoId:$internal"),
                    repoId = repoId,
                    repoType = RepoType.STREMIO,
                    internalName = internal,
                    displayName = dto.name.ifBlank { internal },
                    downloadUrl = resolvedUrl,
                    version = parseSemverMajorMinor(dto.version),
                    apiVersion = 1,
                    status = 1,
                    language = "multi",
                    description = dto.description,
                    iconUrl = dto.logo?.let { expandIconSize(resolveRelativeUrl(resolvedUrl, it)) },
                    tvTypes = dto.types,
                    isEnabled = true,
                    installState = PluginInstallState.INSTALLED
                )
                ParsedRepoPayload.Stremio(repo = repo, addonPlugin = addon)
            }
        }
    }

    fun parseCloudStreamPluginsList(
        repoId: String,
        listUrl: String,
        rawJson: String,
        supportedApiVersion: Int
    ): List<PluginMetadata> {
        val entries = json.decodeFromString<List<CloudStreamPluginDto>>(rawJson)
        return entries
            .filter { it.url.isNotBlank() && (it.internalName.isNotBlank() || it.name.isNotBlank()) }
            .map { dto ->
                val internal = dto.internalName.ifBlank { dto.name }
                val display = dto.name.ifBlank { internal }
                val resolvedDownload = resolveRelativeUrl(listUrl, dto.url)
                val supported = dto.apiVersion in 1..supportedApiVersion
                PluginMetadata(
                    id = stableId("$repoId:$internal"),
                    repoId = repoId,
                    repoType = RepoType.CLOUDSTREAM,
                    internalName = internal,
                    displayName = display,
                    downloadUrl = resolvedDownload,
                    version = dto.version,
                    apiVersion = dto.apiVersion,
                    status = dto.status,
                    language = dto.language.ifBlank { "en" },
                    authors = dto.authors,
                    description = dto.description,
                    iconUrl = dto.iconUrl?.let { expandIconSize(resolveRelativeUrl(listUrl, it)) },
                    tvTypes = dto.tvTypes,
                    fileSizeBytes = dto.fileSize,
                    repositoryUrl = dto.repositoryUrl,
                    isEnabled = false,
                    installState = if (supported) {
                        PluginInstallState.NOT_INSTALLED
                    } else {
                        PluginInstallState.UNSUPPORTED_API
                    },
                    lastError = if (supported) null else "Requires API v${dto.apiVersion} (supported: v$supportedApiVersion)"
                )
            }
    }

    fun expandIconSize(url: String, sizePx: Int = 128): String =
        url.replace("%size%", sizePx.toString())

    fun resolveRelativeUrl(baseUrl: String, target: String): String {
        val trimmed = target.trim()
        if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            return trimmed
        }
        val base = baseUrl.substringBeforeLast('/', missingDelimiterValue = baseUrl)
        val cleanTarget = trimmed.removePrefix("./").removePrefix("/")
        return "$base/$cleanTarget"
    }

    private fun parseSemverMajorMinor(version: String): Int {
        val parts = version.split('.').mapNotNull { it.filter(Char::isDigit).toIntOrNull() }
        return when {
            parts.size >= 2 -> parts[0] * 100 + parts[1]
            parts.size == 1 -> parts[0]
            else -> 1
        }
    }

    private fun stableId(seed: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray())
        return digest.take(12).joinToString("") { "%02x".format(it) }
    }
}
