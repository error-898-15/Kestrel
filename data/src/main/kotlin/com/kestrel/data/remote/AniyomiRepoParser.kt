// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.data.remote

import com.kestrel.domain.model.MangaExtensionInstallState
import com.kestrel.domain.model.MangaExtensionMetadata
import com.kestrel.domain.model.MangaRepoConfig
import com.kestrel.domain.model.MangaSourceDescriptor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

data class ParsedAniyomiRepo(
    val repo: MangaRepoConfig,
    val extensions: List<MangaExtensionMetadata>
)

@Singleton
class AniyomiRepoParser @Inject constructor(
    private val json: Json
) {
    fun parse(
        rawInputUrl: String,
        resolvedUrl: String,
        rawJson: String,
        nowEpochMs: Long = System.currentTimeMillis()
    ): ParsedAniyomiRepo {
        val repoId = stableId("MANGA:$resolvedUrl")
        val repoBase = resolvedUrl.substringBeforeLast('/')
        val root = json.parseToJsonElement(rawJson)

        if (root is JsonArray) {
            val name = deriveRepoName(resolvedUrl)
            val extensions = root.mapIndexedNotNull { idx, el ->
                val obj = el as? JsonObject ?: return@mapIndexedNotNull null
                val pkg = obj.str("pkg") ?: obj.str("packageName") ?: return@mapIndexedNotNull null
                val rawName = obj.str("name").orEmpty().ifBlank { pkg }
                val displayName = rawName.replace(Regex("^(Aniyomi|Tachiyomi):\\s*", RegexOption.IGNORE_CASE), "")
                    .ifBlank { rawName }
                val apk = obj.str("apk").orEmpty()
                val apkUrl = when {
                    apk.startsWith("http", ignoreCase = true) -> apk
                    apk.isNotBlank() -> "$repoBase/apk/$apk"
                    else -> ""
                }
                val iconUrl = obj.str("iconUrl") ?: "$repoBase/icon/$pkg.png"
                val lang = obj.str("lang")?.uppercase() ?: "ALL"
                val version = obj.str("version") ?: "1.0"
                val code = obj.int("code") ?: 1
                val nsfw = (obj.int("nsfw") ?: 0) == 1
                val sources = parseSources(obj["sources"] as? JsonArray, displayName, lang, pkg)

                MangaExtensionMetadata(
                    id = stableId("$repoId:$pkg"),
                    repoId = repoId,
                    name = displayName,
                    packageName = pkg,
                    apkUrl = apkUrl,
                    iconUrl = iconUrl,
                    versionName = version,
                    versionCode = code,
                    lang = lang,
                    isNsfw = nsfw,
                    sources = sources,
                    installState = MangaExtensionInstallState.NOT_INSTALLED
                )
            }
            return ParsedAniyomiRepo(
                repo = MangaRepoConfig(
                    id = repoId,
                    url = rawInputUrl,
                    resolvedUrl = resolvedUrl,
                    name = name,
                    description = "Aniyomi / Tachiyomi extension index repository",
                    badgeLabel = name.take(3).uppercase(),
                    addedAtEpochMs = nowEpochMs,
                    lastCheckedEpochMs = nowEpochMs,
                    isHealthy = true
                ),
                extensions = extensions
            )
        }

        val obj = root.jsonObject
        val rawExts = (obj["extensionList"] as? JsonObject)?.get("extensions")?.jsonArray
            ?: obj["extensions"]?.jsonArray
            ?: error("Unrecognized Aniyomi repository JSON schema")

        val rawName = obj.str("name") ?: deriveRepoName(resolvedUrl)
        val displayRepoName = if (rawName.equals("Keiyoushi", ignoreCase = true)) {
            "Keiyoushi (manga)"
        } else {
            rawName
        }

        val extensions = rawExts.mapIndexedNotNull { idx, el ->
            val extObj = el as? JsonObject ?: return@mapIndexedNotNull null
            val pkg = extObj.str("packageName") ?: extObj.str("pkg") ?: "ext.$idx"
            val name = extObj.str("name")?.ifBlank { null } ?: pkg
            val res = extObj["resources"] as? JsonObject
            val apkUrl = res?.str("apkUrl") ?: extObj.str("apkUrl").orEmpty()
            val jarUrl = res?.str("jarUrl")
            val iconUrl = res?.str("iconUrl") ?: "$repoBase/icon/$pkg.png"
            val versionName = extObj.str("versionName") ?: extObj.str("version") ?: "1.0"
            val versionCode = extObj.str("versionCode")?.toIntOrNull()
                ?: extObj.int("versionCode")
                ?: 1
            val contentWarning = extObj.str("contentWarning").orEmpty()
            val nsfw = contentWarning.contains("NSFW", ignoreCase = true) || contentWarning.contains("18")
            val sources = parseSources(extObj["sources"] as? JsonArray, name, "ALL", pkg)
            val distinctLangs = sources.map { it.lang.uppercase() }.filter { it.isNotBlank() }.distinct()
            val lang = when (distinctLangs.size) {
                1 -> distinctLangs.first()
                else -> "ALL"
            }

            MangaExtensionMetadata(
                id = stableId("$repoId:$pkg"),
                repoId = repoId,
                name = name,
                packageName = pkg,
                apkUrl = apkUrl,
                jarUrl = jarUrl,
                iconUrl = iconUrl,
                versionName = versionName,
                versionCode = versionCode,
                lang = lang,
                isNsfw = nsfw,
                sources = sources,
                installState = MangaExtensionInstallState.NOT_INSTALLED
            )
        }

        return ParsedAniyomiRepo(
            repo = MangaRepoConfig(
                id = repoId,
                url = rawInputUrl,
                resolvedUrl = resolvedUrl,
                name = displayRepoName,
                description = if (rawName.equals("Keiyoushi", ignoreCase = true)) {
                    "The community manga extension repo for Tachiyomi / Mihon / Aniyomi"
                } else {
                    "Aniyomi / Keiyoushi extension repository (${extensions.size} extensions)"
                },
                badgeLabel = obj.str("badgeLabel") ?: displayRepoName.take(3).uppercase(),
                addedAtEpochMs = nowEpochMs,
                lastCheckedEpochMs = nowEpochMs,
                isHealthy = true
            ),
            extensions = extensions
        )
    }

    private fun parseSources(
        array: JsonArray?,
        fallbackName: String,
        fallbackLang: String,
        pkg: String
    ): List<MangaSourceDescriptor> {
        if (array == null) return emptyList()
        return array.mapIndexedNotNull { idx, el ->
            val s = el as? JsonObject ?: return@mapIndexedNotNull null
            MangaSourceDescriptor(
                id = s.str("id") ?: "${pkg}_$idx",
                name = s.str("name") ?: fallbackName,
                lang = s.str("lang") ?: fallbackLang,
                baseUrl = s.str("baseUrl").orEmpty()
            )
        }
    }

    private fun deriveRepoName(url: String): String = when {
        "aniyomiorg" in url -> "Aniyomi Extensions"
        "keiyoushi" in url -> "Keiyoushi (manga)"
        "salmanbappi" in url -> "SalmanBappi Extensions"
        "bubz727" in url -> "Anime Repo ID (Aniyomi)"
        else -> url.substringAfter("://").substringBefore('/').ifBlank { "Aniyomi Repository" }
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.intOrNull

    private fun stableId(seed: String): String =
        MessageDigest.getInstance("SHA-256").digest(seed.toByteArray())
            .take(12).joinToString("") { "%02x".format(it) }
}
