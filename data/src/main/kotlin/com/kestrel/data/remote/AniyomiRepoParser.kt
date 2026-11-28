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
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
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
    /**
     * Parses raw bytes from any Aniyomi / Tachiyomi / Keiyoushi / Mihon repository index:
     * - GZIP-compressed Protobuf (`index.pb`)
     * - Uncompressed Protobuf (`index.pb`)
     * - Legacy JSON array (`index.min.json`, `index.json`)
     * - Keiyoushi v2 JSON object (`index.json`)
     */
    fun parseBytes(
        rawInputUrl: String,
        resolvedUrl: String,
        rawBytes: ByteArray,
        nowEpochMs: Long = System.currentTimeMillis()
    ): ParsedAniyomiRepo {
        val payload = if (rawBytes.size >= 2 &&
            rawBytes[0] == 0x1f.toByte() &&
            rawBytes[1] == 0x8b.toByte()
        ) {
            GZIPInputStream(ByteArrayInputStream(rawBytes)).use { it.readBytes() }
        } else {
            rawBytes
        }

        var idx = 0
        if (payload.size >= 3 &&
            payload[0] == 0xEF.toByte() &&
            payload[1] == 0xBB.toByte() &&
            payload[2] == 0xBF.toByte()
        ) {
            idx = 3
        }
        while (idx < payload.size &&
            (payload[idx] == ' '.code.toByte() ||
                payload[idx] == '\t'.code.toByte() ||
                payload[idx] == '\r'.code.toByte() ||
                payload[idx] == '\n'.code.toByte())
        ) {
            idx++
        }

        val firstByte = payload.getOrNull(idx)
        return if (firstByte == '{'.code.toByte() || firstByte == '['.code.toByte()) {
            parse(rawInputUrl, resolvedUrl, payload.decodeToString(), nowEpochMs)
        } else {
            parseProtobuf(rawInputUrl, resolvedUrl, payload, nowEpochMs)
        }
    }

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
            val extensions = root.mapIndexedNotNull { _, el ->
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

    private fun parseProtobuf(
        rawInputUrl: String,
        resolvedUrl: String,
        bytes: ByteArray,
        nowEpochMs: Long
    ): ParsedAniyomiRepo {
        val repoId = stableId("MANGA:$resolvedUrl")
        val repoBase = resolvedUrl.substringBeforeLast('/')
        val root = decodeProtoMessage(bytes)

        val rawName = protoString(root, 1).ifBlank { deriveRepoName(resolvedUrl) }
        val displayRepoName = if (rawName.equals("Keiyoushi", ignoreCase = true)) {
            "Keiyoushi (manga)"
        } else {
            rawName
        }
        val badgeLabel = protoString(root, 2).ifBlank { displayRepoName.take(3) }.uppercase()

        val extListBytes = root[101]?.firstOrNull()?.bytes
        val extRecords = if (extListBytes != null) {
            decodeProtoMessage(extListBytes)[1].orEmpty()
        } else {
            root[1].orEmpty().filter { it.bytes != null && decodeProtoMessage(it.bytes).containsKey(2) }
        }

        val extensions = extRecords.mapNotNull { rec ->
            val extBytes = rec.bytes ?: return@mapNotNull null
            val ext = decodeProtoMessage(extBytes)
            val pkg = protoString(ext, 2).ifBlank { return@mapNotNull null }
            val name = protoString(ext, 1).ifBlank { pkg }
                .replace(Regex("^(Aniyomi|Tachiyomi):\\s*", RegexOption.IGNORE_CASE), "")
                .ifBlank { pkg }

            val resBytes = ext[3]?.firstOrNull()?.bytes
            val res = if (resBytes != null) decodeProtoMessage(resBytes) else emptyMap()
            val rawApk = protoString(res, 1)
            val apkUrl = when {
                rawApk.startsWith("http", ignoreCase = true) -> rawApk
                rawApk.isNotBlank() -> "$repoBase/apk/$rawApk"
                else -> ""
            }
            val iconUrl = protoString(res, 2).ifBlank { "$repoBase/icon/$pkg.png" }
            val jarUrl = protoString(res, 501).ifBlank { null }
            val versionCode = (ext[5]?.firstOrNull()?.varint ?: 1L).toInt()
            val versionName = protoString(ext, 6).ifBlank { "1.0" }
            val contentWarning = ext[7]?.firstOrNull()?.varint ?: 0L
            val nsfw = contentWarning >= 3L

            val sources = ext[8].orEmpty().mapIndexedNotNull { sIdx, sRec ->
                val sBytes = sRec.bytes ?: return@mapIndexedNotNull null
                val s = decodeProtoMessage(sBytes)
                val sid = s[1]?.firstOrNull()?.varint?.toString() ?: "${pkg}_$sIdx"
                MangaSourceDescriptor(
                    id = sid,
                    name = protoString(s, 2).ifBlank { name },
                    lang = protoString(s, 3).ifBlank { "all" },
                    baseUrl = protoString(s, 4)
                )
            }

            val distinctLangs = sources.map { it.lang.uppercase() }.filter { it.isNotBlank() }.distinct()
            val lang = if (distinctLangs.size == 1) distinctLangs.first() else "ALL"

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

        require(extensions.isNotEmpty()) {
            "Unrecognized or empty Protobuf repository at $resolvedUrl"
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
                badgeLabel = badgeLabel,
                addedAtEpochMs = nowEpochMs,
                lastCheckedEpochMs = nowEpochMs,
                isHealthy = true
            ),
            extensions = extensions
        )
    }

    private class ProtoVal(val wireType: Int, val varint: Long = 0L, val bytes: ByteArray? = null)

    private fun decodeProtoMessage(buf: ByteArray): Map<Int, List<ProtoVal>> {
        val fields = HashMap<Int, MutableList<ProtoVal>>()
        var pos = 0
        while (pos < buf.size) {
            val (tag, p1) = readVarint(buf, pos)
            pos = p1
            val fieldNum = (tag ushr 3).toInt()
            val wireType = (tag and 0x7L).toInt()
            val value = when (wireType) {
                0 -> {
                    val (v, p2) = readVarint(buf, pos)
                    pos = p2
                    ProtoVal(wireType, varint = v)
                }
                2 -> {
                    val (lenLong, p2) = readVarint(buf, pos)
                    val len = lenLong.toInt().coerceAtLeast(0)
                    val end = (p2 + len).coerceAtMost(buf.size)
                    val slice = buf.copyOfRange(p2, end)
                    pos = end
                    ProtoVal(wireType, bytes = slice)
                }
                1 -> {
                    pos = (pos + 8).coerceAtMost(buf.size)
                    ProtoVal(wireType)
                }
                5 -> {
                    pos = (pos + 4).coerceAtMost(buf.size)
                    ProtoVal(wireType)
                }
                else -> return fields
            }
            fields.getOrPut(fieldNum) { ArrayList() }.add(value)
        }
        return fields
    }

    private fun readVarint(buf: ByteArray, start: Int): Pair<Long, Int> {
        var result = 0L
        var shift = 0
        var pos = start
        while (pos < buf.size && shift < 64) {
            val b = buf[pos++].toInt() and 0xFF
            result = result or ((b.toLong() and 0x7FL) shl shift)
            if ((b and 0x80) == 0) return result to pos
            shift += 7
        }
        return result to pos
    }

    private fun protoString(fields: Map<Int, List<ProtoVal>>, fieldNum: Int): String =
        fields[fieldNum]?.firstOrNull()?.bytes?.decodeToString().orEmpty()

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
                lang = s.str("language") ?: s.str("lang") ?: fallbackLang,
                baseUrl = s.str("homeUrl") ?: s.str("baseUrl").orEmpty()
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
