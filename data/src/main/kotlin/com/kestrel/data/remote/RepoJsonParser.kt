// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.data.remote

import com.kestrel.domain.model.PluginInstallState
import com.kestrel.domain.model.PluginMetadata
import com.kestrel.domain.model.RepoConfig
import com.kestrel.domain.model.RepoType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
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
        val pluginListUrls: List<String>,
        val inlinePlugins: List<PluginMetadata> = emptyList()
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
        val root = json.parseToJsonElement(rawJson)
        if (root is JsonArray) {
            val firstObj = root.firstOrNull() as? JsonObject
            if (firstObj != null && ("filename" in firstObj || "file" in firstObj || "script" in firstObj || "supportedTypes" in firstObj) &&
                "internalName" !in firstObj && "apiVersion" !in firstObj
            ) {
                return RepoType.NUVIO
            }
            return RepoType.CLOUDSTREAM
        }

        val obj: JsonObject = root.jsonObject
        return when {
            // Check Nuvio keys BEFORE manifestVersion, because some Nuvio manifests include manifestVersion.
            "scrapers" in obj || "providers" in obj -> RepoType.NUVIO
            "pluginLists" in obj || "manifestVersion" in obj -> RepoType.CLOUDSTREAM
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
        val root = json.parseToJsonElement(rawJson)

        return when (type) {
            RepoType.CLOUDSTREAM -> {
                if (root is JsonArray) {
                    // Direct plugins.json URL provided by the user.
                    val repoName = resolvedUrl.substringAfter("://")
                        .split('/')
                        .getOrNull(2)
                        ?.takeIf { it.isNotBlank() }
                        ?: "CloudStream Plugins"
                    val repo = RepoConfig(
                        id = repoId,
                        url = rawInputUrl,
                        resolvedUrl = resolvedUrl,
                        name = repoName,
                        description = "Direct plugins.json repository",
                        type = RepoType.CLOUDSTREAM,
                        manifestVersion = 1,
                        pluginListUrls = listOf(resolvedUrl),
                        addedAtEpochMs = nowEpochMs,
                        lastCheckedEpochMs = nowEpochMs,
                        isHealthy = true
                    )
                    val inline = parseCloudStreamPluginsList(
                        repoId = repoId,
                        listUrl = resolvedUrl,
                        rawJson = rawJson,
                        supportedApiVersion = 1
                    )
                    return ParsedRepoPayload.CloudStream(
                        repo = repo,
                        pluginListUrls = emptyList(),
                        inlinePlugins = inline
                    )
                }

                val dto = json.decodeFromString(CloudStreamRepoDto.serializer(), rawJson)
                val resolvedLists = dto.pluginLists.map { rewriteGitUrl(resolveRelativeUrl(resolvedUrl, it)) }
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

            RepoType.NUVIO -> parseNuvioPayload(
                repoId = repoId,
                rawInputUrl = rawInputUrl,
                resolvedUrl = resolvedUrl,
                root = root,
                nowEpochMs = nowEpochMs
            )

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

    private fun parseNuvioPayload(
        repoId: String,
        rawInputUrl: String,
        resolvedUrl: String,
        root: JsonElement,
        nowEpochMs: Long
    ): ParsedRepoPayload.Nuvio {
        val obj = root as? JsonObject
        val repoName = obj?.stringField("name")?.ifBlank { null } ?: "Nuvio Scrapers"
        val repoDesc = obj?.stringField("description").orEmpty()
        val manifestBaseUrl = obj?.stringField("baseUrl")?.takeIf { it.isNotBlank() } ?: resolvedUrl
        val scraperArray = when {
            root is JsonArray -> root
            obj != null && "scrapers" in obj && obj["scrapers"] is JsonArray -> obj["scrapers"]!!.jsonArray
            obj != null && "providers" in obj && obj["providers"] is JsonArray -> obj["providers"]!!.jsonArray
            else -> JsonArray(emptyList())
        }

        val repo = RepoConfig(
            id = repoId,
            url = rawInputUrl,
            resolvedUrl = resolvedUrl,
            name = repoName,
            description = repoDesc,
            type = RepoType.NUVIO,
            manifestVersion = 1,
            addedAtEpochMs = nowEpochMs,
            lastCheckedEpochMs = nowEpochMs,
            isHealthy = true
        )

        val plugins = scraperArray.mapIndexedNotNull { index, el ->
            val item = el as? JsonObject ?: return@mapIndexedNotNull null
            val scriptPath = listOf("url", "rawUrl", "scriptUrl", "filename", "file", "script", "path")
                .firstNotNullOfOrNull { key -> item.stringField(key)?.takeIf { it.isNotBlank() } }
                ?: return@mapIndexedNotNull null
            val scriptUrl = rewriteGitUrl(resolveRelativeUrl(manifestBaseUrl, scriptPath))
            val name = item.stringField("name")?.ifBlank { null }
            val internal = item.stringField("id")?.ifBlank { null }
                ?: name
                ?: scriptPath.substringAfterLast('/').removeSuffix(".js").ifBlank { "scraper_$index" }
            val langs = item.stringListField("contentLanguage") + item.stringListField("language")
            val primaryLang = langs.firstOrNull { it.isNotBlank() }
                ?: item.stringField("language")?.ifBlank { null }
                ?: "en"
            val authors = (item.stringListField("authors") + listOfNotNull(item.stringField("author")))
                .filter { it.isNotBlank() }
                .distinct()
            val supportedTypes = (item.stringListField("supportedTypes") +
                item.stringListField("tvTypes") +
                item.stringListField("types")).distinct()
            val logoPath = item.stringField("logo") ?: item.stringField("icon") ?: item.stringField("iconUrl")
            val iconUrl = logoPath?.takeIf { it.isNotBlank() }?.let {
                expandIconSize(rewriteGitUrl(resolveRelativeUrl(manifestBaseUrl, it)))
            }
            val versionStr = item.primitiveText("version") ?: "1"
            val versionInt = parseSemverMajorMinor(versionStr)

            PluginMetadata(
                id = stableId("$repoId:$internal"),
                repoId = repoId,
                repoType = RepoType.NUVIO,
                internalName = internal,
                displayName = name ?: internal,
                downloadUrl = scriptUrl,
                version = versionInt,
                apiVersion = 1,
                status = 1,
                language = primaryLang,
                authors = authors,
                description = item.stringField("description").orEmpty(),
                iconUrl = iconUrl,
                tvTypes = supportedTypes,
                isEnabled = false,
                installState = PluginInstallState.NOT_INSTALLED
            )
        }
        return ParsedRepoPayload.Nuvio(repo = repo, plugins = plugins)
    }

    fun parseCloudStreamPluginsList(
        repoId: String,
        listUrl: String,
        rawJson: String,
        supportedApiVersion: Int
    ): List<PluginMetadata> {
        val array = json.parseToJsonElement(rawJson).jsonArray
        return array.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val url = obj.stringField("url").orEmpty()
            val name = obj.stringField("name").orEmpty()
            val internalName = obj.stringField("internalName").orEmpty()
            if (url.isBlank() || (internalName.isBlank() && name.isBlank())) {
                return@mapNotNull null
            }

            val internal = internalName.ifBlank { name }
            val display = name.ifBlank { internal }
            val resolvedDownload = rewriteGitUrl(resolveRelativeUrl(listUrl, url))
            val apiVersion = (obj["apiVersion"] as? JsonPrimitive)?.intOrNull ?: 1
            val version = (obj["version"] as? JsonPrimitive)?.intOrNull
                ?: obj.primitiveText("version")?.let { parseSemverMajorMinor(it) }
                ?: 1
            val status = (obj["status"] as? JsonPrimitive)?.intOrNull ?: 1
            val language = obj.stringField("language")?.ifBlank { null } ?: "en"
            val authors = obj.stringListField("authors")
            val description = obj.stringField("description").orEmpty()
            val iconUrl = obj.stringField("iconUrl")?.takeIf { it.isNotBlank() }?.let {
                expandIconSize(rewriteGitUrl(resolveRelativeUrl(listUrl, it)))
            }
            val tvTypes = obj.stringListField("tvTypes")
            val fileSize = (obj["fileSize"] as? JsonPrimitive)?.longOrNull
            val repositoryUrl = obj.stringField("repositoryUrl")?.takeIf { it.isNotBlank() }
            val supported = apiVersion in 0..supportedApiVersion

            PluginMetadata(
                id = stableId("$repoId:$internal"),
                repoId = repoId,
                repoType = RepoType.CLOUDSTREAM,
                internalName = internal,
                displayName = display,
                downloadUrl = resolvedDownload,
                version = version,
                apiVersion = apiVersion,
                status = status,
                language = language,
                authors = authors,
                description = description,
                iconUrl = iconUrl,
                tvTypes = tvTypes,
                fileSizeBytes = fileSize,
                repositoryUrl = repositoryUrl,
                isEnabled = false,
                installState = if (supported) {
                    PluginInstallState.NOT_INSTALLED
                } else {
                    PluginInstallState.UNSUPPORTED_API
                },
                lastError = if (supported) null else "Requires API v$apiVersion (supported: v$supportedApiVersion)"
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
            return trimmed.replace(" ", "%20")
        }
        val base = baseUrl.substringBeforeLast('/', missingDelimiterValue = baseUrl)
        val cleanTarget = trimmed.removePrefix("./").removePrefix("/")
        return "$base/$cleanTarget".replace(" ", "%20")
    }

    private fun rewriteGitUrl(url: String): String {
        val ghBlob = Regex("^https?://github\\.com/([^/]+)/([^/]+)/(?:blob|raw)/([^/]+)/(.*)$")
        ghBlob.matchEntire(url)?.let { m ->
            return "https://raw.githubusercontent.com/${m.groupValues[1]}/${m.groupValues[2]}/${m.groupValues[3]}/${m.groupValues[4]}"
        }
        val cbSrc = Regex("^https?://codeberg\\.org/([^/]+)/([^/]+)/src/(branch|commit|tag)/([^/]+)(?:/(.*))?$")
        cbSrc.matchEntire(url)?.let { m ->
            val path = m.groupValues[5].trim('/')
            return if (path.isEmpty()) {
                "https://codeberg.org/${m.groupValues[1]}/${m.groupValues[2]}/raw/${m.groupValues[3]}/${m.groupValues[4]}"
            } else {
                "https://codeberg.org/${m.groupValues[1]}/${m.groupValues[2]}/raw/${m.groupValues[3]}/${m.groupValues[4]}/$path"
            }
        }
        return url
    }

    private fun JsonObject.stringField(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonObject.primitiveText(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.stringListField(key: String): List<String> {
        val el = this[key] ?: return emptyList()
        return when (el) {
            is JsonArray -> el.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            is JsonPrimitive -> el.contentOrNull?.let { listOf(it) } ?: emptyList()
            else -> emptyList()
        }
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
