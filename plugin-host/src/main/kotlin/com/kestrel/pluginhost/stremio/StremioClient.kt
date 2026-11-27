// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.pluginhost.stremio

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLDecoder
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StremioClient @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val json: Json
) {
    fun normalizeManifestUrl(rawUrl: String): String {
        val extracted = extractWebInstallAddonUrl(rawUrl.trim()) ?: rawUrl.trim()
        val withHttp = when {
            extracted.startsWith("stremio://", ignoreCase = true) -> {
                val remainder = extracted.substring("stremio://".length).trimStart('/')
                if (remainder.startsWith("http://", ignoreCase = true) ||
                    remainder.startsWith("https://", ignoreCase = true)
                ) {
                    remainder
                } else {
                    "https://$remainder"
                }
            }
            !extracted.contains("://") -> "https://$extracted"
            else -> extracted
        }

        val parsed = withHttp.toHttpUrlOrNull()
        if (parsed != null) {
            val encodedPath = parsed.encodedPath
            return if (encodedPath.endsWith("/manifest.json", ignoreCase = true)) {
                withHttp
            } else {
                val cleanPath = encodedPath.trimEnd('/')
                parsed.newBuilder()
                    .encodedPath("$cleanPath/manifest.json")
                    .build()
                    .toString()
            }
        }

        return if (withHttp.endsWith("/manifest.json", ignoreCase = true)) {
            withHttp
        } else {
            "${withHttp.trimEnd('/')}/manifest.json"
        }
    }

    /**
     * Returns the addon base URL (stripping `/manifest.json` from the path while preserving
     * any query parameters such as `?token=...` so endpoint calls resolve `/catalog/...?...` cleanly).
     */
    fun baseUrlOf(manifestUrl: String): String {
        val parsed = manifestUrl.toHttpUrlOrNull()
        if (parsed != null) {
            val path = parsed.encodedPath
            val basePath = if (path.endsWith("/manifest.json", ignoreCase = true)) {
                path.dropLast("/manifest.json".length)
            } else {
                path.trimEnd('/')
            }
            return parsed.newBuilder()
                .encodedPath(basePath.ifEmpty { "/" })
                .build()
                .toString()
                .let { built ->
                    val qIdx = built.indexOf('?')
                    if (qIdx >= 0) {
                        built.substring(0, qIdx).trimEnd('/') + built.substring(qIdx)
                    } else {
                        built.trimEnd('/')
                    }
                }
        }
        return manifestUrl.removeSuffix("/manifest.json").trimEnd('/')
    }

    fun configureUrlOf(manifestUrl: String, manifest: StremioAddonManifest? = null): String {
        val declared = manifest?.behaviorHints?.configurationURL?.trim().orEmpty()
        if (declared.isNotEmpty()) {
            if (declared.startsWith("http://", ignoreCase = true) ||
                declared.startsWith("https://", ignoreCase = true)
            ) {
                return declared
            }
            return appendEndpoint(baseUrlOf(manifestUrl), "/${declared.trimStart('/')}")
        }
        return appendEndpoint(baseUrlOf(manifestUrl), "/configure")
    }

    fun buildConfigEncodedManifestUrl(manifestUrl: String, configValues: Map<String, String>): String {
        val normalized = normalizeManifestUrl(manifestUrl)
        val parsed = normalized.toHttpUrlOrNull() ?: return normalized

        val segments = parsed.pathSegments.filter { it.isNotEmpty() }.toMutableList()
        if (segments.lastOrNull()?.equals("manifest.json", ignoreCase = true) == true) {
            segments.removeAt(segments.lastIndex)
        }
        // Strip an existing Stremio JSON-encoded config path segment if present while preserving any static subpath.
        segments.lastOrNull()?.let { last ->
            val decodedLast = runCatching { URLDecoder.decode(last, "UTF-8") }.getOrDefault(last).trim()
            if (decodedLast.startsWith("{") && decodedLast.endsWith("}")) {
                segments.removeAt(segments.lastIndex)
            }
        }

        val clean = configValues.filterValues { it.isNotBlank() }
        val builder = parsed.newBuilder()
        val basePath = if (segments.isEmpty()) "" else "/" + segments.joinToString("/") { encode(it) }

        if (clean.isEmpty()) {
            return builder.encodedPath("$basePath/manifest.json").build().toString()
        }

        val jsonStr = json.encodeToString(
            JsonObject.serializer(),
            JsonObject(clean.mapValues { JsonPrimitive(it.value) })
        )
        val encodedConfig = encode(jsonStr)
        return builder.encodedPath("$basePath/$encodedConfig/manifest.json").build().toString()
    }

    fun fetchManifest(
        manifestUrl: String,
        customHeaders: Map<String, String> = emptyMap()
    ): StremioAddonManifest {
        val raw = get(manifestUrl, customHeaders, noCache = true)
        return json.decodeFromString(StremioAddonManifest.serializer(), raw)
    }

    fun fetchCatalog(
        baseUrl: String,
        type: String,
        catalogId: String,
        searchQuery: String? = null,
        customHeaders: Map<String, String> = emptyMap()
    ): StremioCatalogResponse {
        val encodedType = encode(type)
        val encodedId = encode(catalogId)
        val endpoint = if (!searchQuery.isNullOrBlank()) {
            "/catalog/$encodedType/$encodedId/search=${encode(searchQuery)}.json"
        } else {
            "/catalog/$encodedType/$encodedId.json"
        }
        val url = appendEndpoint(baseUrl, endpoint)
        val raw = get(url, customHeaders)
        return json.decodeFromString(StremioCatalogResponse.serializer(), raw)
    }

    fun fetchMeta(
        baseUrl: String,
        type: String,
        id: String,
        customHeaders: Map<String, String> = emptyMap()
    ): StremioMetaResponse {
        val url = appendEndpoint(baseUrl, "/meta/${encode(type)}/${encode(id)}.json")
        val raw = get(url, customHeaders)
        return json.decodeFromString(StremioMetaResponse.serializer(), raw)
    }

    fun fetchStreams(
        baseUrl: String,
        type: String,
        id: String,
        customHeaders: Map<String, String> = emptyMap()
    ): StremioStreamsResponse {
        val url = appendEndpoint(baseUrl, "/stream/${encode(type)}/${encode(id)}.json")
        val raw = get(url, customHeaders)
        return json.decodeFromString(StremioStreamsResponse.serializer(), raw)
    }

    private fun appendEndpoint(baseUrl: String, endpointPath: String): String {
        val qIdx = baseUrl.indexOf('?')
        return if (qIdx >= 0) {
            val base = baseUrl.substring(0, qIdx).trimEnd('/')
            val query = baseUrl.substring(qIdx)
            "$base$endpointPath$query"
        } else {
            "${baseUrl.trimEnd('/')}$endpointPath"
        }
    }

    private fun extractWebInstallAddonUrl(raw: String): String? {
        if (!raw.contains("addon=", ignoreCase = true)) return null
        val afterAddon = raw.substringAfter("addon=").substringBefore('&')
        if (afterAddon.isBlank()) return null
        val decoded = runCatching { URLDecoder.decode(afterAddon, "UTF-8") }.getOrNull()?.trim()
        return decoded?.takeIf {
            it.startsWith("http://", ignoreCase = true) ||
                it.startsWith("https://", ignoreCase = true) ||
                it.startsWith("stremio://", ignoreCase = true)
        }
    }

    private fun get(
        url: String,
        customHeaders: Map<String, String>,
        noCache: Boolean = false
    ): String {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", BROWSER_USER_AGENT)
            .header("Accept", "application/json, text/plain, */*")
            .get()

        if (noCache) {
            builder.header("Cache-Control", "no-cache, no-store, must-revalidate")
            builder.header("Pragma", "no-cache")
        }

        customHeaders.forEach { (k, v) ->
            val key = k.trim()
            val value = v.trim()
            if (key.isNotEmpty() && value.isNotEmpty()) {
                builder.header(key, value)
            }
        }

        okHttpClient.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code} from $url")
            return response.body?.string().orEmpty()
        }
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    companion object {
        private const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
    }
}
