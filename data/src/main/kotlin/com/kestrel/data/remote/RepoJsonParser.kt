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
import kotlinx.serialization.json.booleanOrNull
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

    data class Vega(
        override val repo: RepoConfig,
        val plugins: List<PluginMetadata>
    ) : ParsedRepoPayload

    data class Zangetsu(
        override val repo: RepoConfig,
        val plugins: List<PluginMetadata>
    ) : ParsedRepoPayload

    data class SkyStream(
        override val repo: RepoConfig,
        val pluginListUrls: List<String>,
        val inlinePlugins: List<PluginMetadata> = emptyList()
    ) : ParsedRepoPayload

    data class SkyStreamMega(
        override val repo: RepoConfig,
        val childRepoUrls: List<String>
    ) : ParsedRepoPayload

    data class Hikari(
        override val repo: RepoConfig,
        val plugins: List<PluginMetadata>
    ) : ParsedRepoPayload
}

@Singleton
class RepoJsonParser @Inject constructor(
    private val json: Json
) {
    fun detectType(rawJson: String, resolvedUrl: String = ""): RepoType {
        val root = json.parseToJsonElement(rawJson)
        if (root is JsonArray) {
            val objects = root.mapNotNull { it as? JsonObject }
            val firstObj = objects.firstOrNull()
            if (firstObj != null) {
                if ("display_name" in firstObj && "value" in firstObj) {
                    return RepoType.VEGA
                }
                if (objects.any { obj ->
                        val itemUrl = obj.stringField("url").orEmpty()
                        "packageName" in obj || itemUrl.endsWith(".sky", ignoreCase = true)
                    }
                ) {
                    return RepoType.SKYSTREAM
                }
                if (("filename" in firstObj || "file" in firstObj || "script" in firstObj || "supportedTypes" in firstObj) &&
                    "internalName" !in firstObj && "apiVersion" !in firstObj
                ) {
                    return RepoType.NUVIO
                }
            }
            return RepoType.CLOUDSTREAM
        }

        val obj: JsonObject = root.jsonObject
        return when {
            // Zangetsu index.json
            "sources" in obj && obj["sources"] is JsonArray -> RepoType.ZANGETSU
            // SkyStream mega_repo.json
            "repos" in obj && obj["repos"] is JsonArray -> RepoType.SKYSTREAM
            // Hikari repo.json
            "plugins" in obj && obj["plugins"] is JsonArray -> RepoType.HIKARI
            // Nuvio manifests (check before manifestVersion because some Nuvio manifests include manifestVersion)
            "scrapers" in obj || "providers" in obj -> RepoType.NUVIO
            // SkyStream repo.json (uses packageName or id + pluginLists)
            "pluginLists" in obj && ("packageName" in obj || "id" in obj) -> RepoType.SKYSTREAM
            // CloudStream repo.json (note: SkyStream repos without packageName are auto-upgraded when pluginLists contain .sky)
            "pluginLists" in obj || "manifestVersion" in obj -> RepoType.CLOUDSTREAM
            // Stremio addon manifest
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
                    val repoName = deriveRepoLabel(resolvedUrl, "CloudStream Plugins")
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

            RepoType.VEGA -> parseVegaPayload(
                repoId = repoId,
                rawInputUrl = rawInputUrl,
                resolvedUrl = resolvedUrl,
                array = root.jsonArray,
                nowEpochMs = nowEpochMs
            )

            RepoType.ZANGETSU -> parseZangetsuPayload(
                repoId = repoId,
                rawInputUrl = rawInputUrl,
                resolvedUrl = resolvedUrl,
                obj = root.jsonObject,
                nowEpochMs = nowEpochMs
            )

            RepoType.SKYSTREAM -> parseSkyStreamPayload(
                repoId = repoId,
                rawInputUrl = rawInputUrl,
                resolvedUrl = resolvedUrl,
                root = root,
                nowEpochMs = nowEpochMs
            )

            RepoType.HIKARI -> parseHikariPayload(
                repoId = repoId,
                rawInputUrl = rawInputUrl,
                resolvedUrl = resolvedUrl,
                obj = root.jsonObject,
                nowEpochMs = nowEpochMs
            )
        }
    }

    private fun parseVegaPayload(
        repoId: String,
        rawInputUrl: String,
        resolvedUrl: String,
        array: JsonArray,
        nowEpochMs: Long
    ): ParsedRepoPayload.Vega {
        val baseUrl = resolvedUrl.substringBeforeLast('/', missingDelimiterValue = resolvedUrl)
        val ownerName = extractRepoOwner(resolvedUrl)
        val repoName = if (!ownerName.isNullOrBlank()) "$ownerName Vega Providers" else "Vega Providers"

        val repo = RepoConfig(
            id = repoId,
            url = rawInputUrl,
            resolvedUrl = resolvedUrl,
            name = repoName,
            description = "Vega modular JavaScript providers",
            type = RepoType.VEGA,
            manifestVersion = 1,
            pluginListUrls = listOf(resolvedUrl),
            addedAtEpochMs = nowEpochMs,
            lastCheckedEpochMs = nowEpochMs,
            isHealthy = true
        )

        val plugins = array.mapIndexedNotNull { _, el ->
            val item = el as? JsonObject ?: return@mapIndexedNotNull null
            val disabled = (item["disabled"] as? JsonPrimitive)?.booleanOrNull ?: false
            if (disabled) return@mapIndexedNotNull null
            val value = item.stringField("value")?.trim()?.takeIf { it.isNotBlank() }
                ?: return@mapIndexedNotNull null
            val displayName = item.stringField("display_name")?.trim()?.ifBlank { value } ?: value
            val versionStr = item.primitiveText("version") ?: "1"
            val providerType = item.stringField("type")?.trim().orEmpty()
            val iconUrl = item.stringField("icon")?.takeIf { it.isNotBlank() }?.let {
                expandIconSize(rewriteGitUrl(resolveRelativeUrl(resolvedUrl, it)))
            }

            PluginMetadata(
                id = stableId("$repoId:$value"),
                repoId = repoId,
                repoType = RepoType.VEGA,
                internalName = value,
                displayName = displayName,
                downloadUrl = "vega://$baseUrl/dist/$value",
                version = parseSemverMajorMinor(versionStr),
                apiVersion = 1,
                status = 1,
                language = "en",
                description = if (providerType.isNotBlank()) "Vega $providerType provider ($value)" else "Vega provider",
                iconUrl = iconUrl,
                tvTypes = listOfNotNull(providerType.takeIf { it.isNotBlank() }),
                isEnabled = false,
                installState = PluginInstallState.NOT_INSTALLED
            )
        }

        return ParsedRepoPayload.Vega(repo = repo, plugins = plugins)
    }

    private fun parseZangetsuPayload(
        repoId: String,
        rawInputUrl: String,
        resolvedUrl: String,
        obj: JsonObject,
        nowEpochMs: Long
    ): ParsedRepoPayload.Zangetsu {
        val repoName = obj.stringField("name")?.ifBlank { null } ?: "Zangetsu Providers"
        val repoDesc = obj.stringField("description").orEmpty()
        val sources = obj["sources"]?.jsonArray ?: JsonArray(emptyList())

        val repo = RepoConfig(
            id = repoId,
            url = rawInputUrl,
            resolvedUrl = resolvedUrl,
            name = repoName,
            description = repoDesc,
            type = RepoType.ZANGETSU,
            manifestVersion = 1,
            pluginListUrls = listOf(resolvedUrl),
            addedAtEpochMs = nowEpochMs,
            lastCheckedEpochMs = nowEpochMs,
            isHealthy = true
        )

        val plugins = sources.mapIndexedNotNull { index, el ->
            val item = el as? JsonObject ?: return@mapIndexedNotNull null
            val filePath = item.stringField("file")?.trim()?.takeIf { it.isNotBlank() }
                ?: return@mapIndexedNotNull null
            val internal = item.stringField("id")?.trim()?.ifBlank { null }
                ?: filePath.substringAfterLast('/').removeSuffix(".js").ifBlank { "zangetsu_$index" }
            val displayName = item.stringField("name")?.trim()?.ifBlank { internal } ?: internal
            val downloadUrl = rewriteGitUrl(resolveRelativeUrl(resolvedUrl, filePath))
            val logoPath = item.stringField("logo")?.takeIf { it.isNotBlank() }
            val iconUrl = logoPath?.let { expandIconSize(rewriteGitUrl(resolveRelativeUrl(resolvedUrl, it))) }
            val versionStr = item.primitiveText("version") ?: "1"
            val lang = item.stringField("lang")?.ifBlank { null } ?: "en"
            val mediaType = item.stringField("type")?.trim()?.takeIf { it.isNotBlank() }
            val isNsfw = (item["nsfw"] as? JsonPrimitive)?.booleanOrNull ?: false

            PluginMetadata(
                id = stableId("$repoId:$internal"),
                repoId = repoId,
                repoType = RepoType.ZANGETSU,
                internalName = internal,
                displayName = displayName,
                downloadUrl = downloadUrl,
                version = parseSemverMajorMinor(versionStr),
                apiVersion = 1,
                status = 1,
                language = lang,
                description = if (isNsfw) "NSFW · Zangetsu provider" else "Zangetsu provider",
                iconUrl = iconUrl,
                tvTypes = listOfNotNull(mediaType),
                isEnabled = false,
                installState = PluginInstallState.NOT_INSTALLED
            )
        }

        return ParsedRepoPayload.Zangetsu(repo = repo, plugins = plugins)
    }

    private fun parseSkyStreamPayload(
        repoId: String,
        rawInputUrl: String,
        resolvedUrl: String,
        root: JsonElement,
        nowEpochMs: Long
    ): ParsedRepoPayload {
        if (root is JsonArray) {
            val repoName = deriveRepoLabel(resolvedUrl, "SkyStream Plugins")
            val repo = RepoConfig(
                id = repoId,
                url = rawInputUrl,
                resolvedUrl = resolvedUrl,
                name = repoName,
                description = "SkyStream plugins repository",
                type = RepoType.SKYSTREAM,
                manifestVersion = 1,
                pluginListUrls = listOf(resolvedUrl),
                addedAtEpochMs = nowEpochMs,
                lastCheckedEpochMs = nowEpochMs,
                isHealthy = true
            )
            val inline = parseSkyStreamPluginsList(
                repoId = repoId,
                listUrl = resolvedUrl,
                rawJson = root.toString()
            )
            return ParsedRepoPayload.SkyStream(
                repo = repo,
                pluginListUrls = emptyList(),
                inlinePlugins = inline
            )
        }

        val obj = root.jsonObject
        val name = obj.stringField("name")?.ifBlank { null } ?: "SkyStream Repository"
        val description = obj.stringField("description").orEmpty()
        val manifestVersion = (obj["manifestVersion"] as? JsonPrimitive)?.intOrNull ?: 1

        if ("repos" in obj && obj["repos"] is JsonArray) {
            val childRepos = obj.stringListField("repos").map {
                rewriteGitUrl(resolveRelativeUrl(resolvedUrl, it))
            }
            val repo = RepoConfig(
                id = repoId,
                url = rawInputUrl,
                resolvedUrl = resolvedUrl,
                name = name,
                description = description,
                type = RepoType.SKYSTREAM,
                manifestVersion = manifestVersion,
                pluginListUrls = childRepos,
                addedAtEpochMs = nowEpochMs,
                lastCheckedEpochMs = nowEpochMs,
                isHealthy = true
            )
            return ParsedRepoPayload.SkyStreamMega(
                repo = repo,
                childRepoUrls = childRepos
            )
        }

        val resolvedLists = obj.stringListField("pluginLists").map {
            rewriteGitUrl(resolveRelativeUrl(resolvedUrl, it))
        }
        val repo = RepoConfig(
            id = repoId,
            url = rawInputUrl,
            resolvedUrl = resolvedUrl,
            name = name,
            description = description,
            type = RepoType.SKYSTREAM,
            manifestVersion = manifestVersion,
            pluginListUrls = resolvedLists,
            addedAtEpochMs = nowEpochMs,
            lastCheckedEpochMs = nowEpochMs,
            isHealthy = true
        )
        return ParsedRepoPayload.SkyStream(
            repo = repo,
            pluginListUrls = resolvedLists
        )
    }

    private fun parseHikariPayload(
        repoId: String,
        rawInputUrl: String,
        resolvedUrl: String,
        obj: JsonObject,
        nowEpochMs: Long
    ): ParsedRepoPayload.Hikari {
        val repoName = obj.stringField("name")?.ifBlank { null } ?: "Hikari Extensions"
        val repoDesc = obj.stringField("description").orEmpty()
        val pluginsArr = obj["plugins"]?.jsonArray ?: JsonArray(emptyList())

        val repo = RepoConfig(
            id = repoId,
            url = rawInputUrl,
            resolvedUrl = resolvedUrl,
            name = repoName,
            description = repoDesc,
            type = RepoType.HIKARI,
            manifestVersion = 1,
            pluginListUrls = listOf(resolvedUrl),
            addedAtEpochMs = nowEpochMs,
            lastCheckedEpochMs = nowEpochMs,
            isHealthy = true
        )

        val plugins = pluginsArr.mapIndexedNotNull { index, el ->
            val item = el as? JsonObject ?: return@mapIndexedNotNull null
            val url = item.stringField("url")?.trim()?.takeIf { it.isNotBlank() }
                ?: return@mapIndexedNotNull null
            val displayName = item.stringField("name")?.trim()?.ifBlank { null }
                ?: url.substringAfterLast('/').removeSuffix(".hiki").ifBlank { "hikari_$index" }
            val internal = url.substringAfterLast('/').removeSuffix(".hiki").ifBlank { displayName }
            val resolvedDownload = rewriteGitUrl(resolveRelativeUrl(resolvedUrl, url))
            val version = (item["version"] as? JsonPrimitive)?.intOrNull
                ?: item.primitiveText("version")?.let { parseSemverMajorMinor(it) }
                ?: 1
            val iconUrl = item.stringField("iconUrl")?.takeIf { it.isNotBlank() }?.let {
                expandIconSize(rewriteGitUrl(resolveRelativeUrl(resolvedUrl, it)))
            }

            PluginMetadata(
                id = stableId("$repoId:$internal"),
                repoId = repoId,
                repoType = RepoType.HIKARI,
                internalName = internal,
                displayName = displayName,
                downloadUrl = resolvedDownload,
                version = version,
                apiVersion = 1,
                status = 1,
                language = item.stringField("language")?.ifBlank { null } ?: "en",
                authors = item.stringListField("authors"),
                description = item.stringField("description").orEmpty(),
                iconUrl = iconUrl,
                tvTypes = item.stringListField("tvTypes"),
                isEnabled = false,
                installState = PluginInstallState.NOT_INSTALLED
            )
        }

        return ParsedRepoPayload.Hikari(repo = repo, plugins = plugins)
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

    fun isSkyStreamPluginsList(rawJson: String): Boolean {
        val root = runCatching { json.parseToJsonElement(rawJson) }.getOrNull() as? JsonArray ?: return false
        return root.any { el ->
            val obj = el as? JsonObject ?: return@any false
            val url = obj.stringField("url").orEmpty()
            "packageName" in obj || url.endsWith(".sky", ignoreCase = true)
        }
    }

    fun parseSkyStreamPluginsList(
        repoId: String,
        listUrl: String,
        rawJson: String
    ): List<PluginMetadata> {
        val array = json.parseToJsonElement(rawJson).jsonArray
        return array.mapIndexedNotNull { index, el ->
            val obj = el as? JsonObject ?: return@mapIndexedNotNull null
            val url = obj.stringField("url").orEmpty()
            val name = obj.stringField("name").orEmpty()
            val pkg = obj.stringField("packageName").orEmpty()
            if (url.isBlank() || (pkg.isBlank() && name.isBlank())) {
                return@mapIndexedNotNull null
            }
            val internal = pkg.ifBlank { name.ifBlank { "sky_$index" } }
            val display = name.ifBlank { internal }
            val resolvedDownload = rewriteGitUrl(resolveRelativeUrl(listUrl, url))
            val version = (obj["version"] as? JsonPrimitive)?.intOrNull
                ?: obj.primitiveText("version")?.let { parseSemverMajorMinor(it) }
                ?: 1
            val langs = obj.stringListField("languages") + listOfNotNull(obj.stringField("language"))
            val primaryLang = langs.firstOrNull { it.isNotBlank() } ?: "en"
            val authors = obj.stringListField("authors")
            val description = obj.stringField("description").orEmpty()
            val iconUrl = (obj.stringField("iconUrl") ?: obj.stringField("icon"))
                ?.takeIf { it.isNotBlank() }
                ?.let { expandIconSize(rewriteGitUrl(resolveRelativeUrl(listUrl, it))) }
            val tvTypes = (obj.stringListField("categories") + obj.stringListField("tvTypes")).distinct()
            val repositoryUrl = obj.stringField("baseUrl")?.takeIf { it.isNotBlank() }

            PluginMetadata(
                id = stableId("$repoId:$internal"),
                repoId = repoId,
                repoType = RepoType.SKYSTREAM,
                internalName = internal,
                displayName = display,
                downloadUrl = resolvedDownload,
                version = version,
                apiVersion = 1,
                status = 1,
                language = primaryLang,
                authors = authors,
                description = description,
                iconUrl = iconUrl,
                tvTypes = tvTypes,
                repositoryUrl = repositoryUrl,
                isEnabled = false,
                installState = PluginInstallState.NOT_INSTALLED
            )
        }
    }

    fun parseCloudStreamPluginsList(
        repoId: String,
        listUrl: String,
        rawJson: String,
        supportedApiVersion: Int
    ): List<PluginMetadata> {
        val array = json.parseToJsonElement(rawJson).jsonArray
        return array.mapIndexedNotNull { index, el ->
            val obj = el as? JsonObject ?: return@mapIndexedNotNull null
            val url = obj.stringField("url").orEmpty()
            val name = obj.stringField("name").orEmpty()
            val internalName = obj.stringField("internalName").orEmpty()
            if (url.isBlank() || (internalName.isBlank() && name.isBlank())) {
                return@mapIndexedNotNull null
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

    private fun deriveRepoLabel(resolvedUrl: String, fallback: String): String =
        resolvedUrl.substringAfter("://")
            .split('/')
            .getOrNull(2)
            ?.takeIf { it.isNotBlank() }
            ?: fallback

    private fun extractRepoOwner(resolvedUrl: String): String? =
        resolvedUrl.substringAfter("://")
            .split('/')
            .getOrNull(1)
            ?.takeIf { it.isNotBlank() }

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
