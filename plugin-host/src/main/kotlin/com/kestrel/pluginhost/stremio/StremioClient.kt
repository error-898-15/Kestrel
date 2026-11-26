// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.pluginhost.stremio

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StremioClient @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val json: Json
) {
    fun normalizeManifestUrl(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        val withHttp = when {
            trimmed.startsWith("stremio://", ignoreCase = true) ->
                "https://" + trimmed.removePrefix("stremio://").removePrefix("STREMIO://").trimStart('/')
            !trimmed.contains("://") -> "https://$trimmed"
            else -> trimmed
        }
        return if (withHttp.endsWith("/manifest.json", ignoreCase = true)) {
            withHttp
        } else {
            "${withHttp.trimEnd('/')}/manifest.json"
        }
    }

    fun baseUrlOf(manifestUrl: String): String =
        manifestUrl.removeSuffix("/manifest.json").trimEnd('/')

    fun configureUrlOf(manifestUrl: String): String =
        "${baseUrlOf(manifestUrl)}/configure"

    fun buildConfigEncodedManifestUrl(manifestUrl: String, configValues: Map<String, String>): String {
        val parsed = manifestUrl.toHttpUrlOrNull() ?: return manifestUrl
        val origin = buildString {
            append(parsed.scheme)
            append("://")
            append(parsed.host)
            val defaultPort = if (parsed.scheme == "https") 443 else 80
            if (parsed.port != defaultPort) {
                append(":").append(parsed.port)
            }
        }
        val clean = configValues.filterValues { it.isNotBlank() }
        if (clean.isEmpty()) return "$origin/manifest.json"
        val jsonStr = json.encodeToString(
            JsonObject.serializer(),
            JsonObject(clean.mapValues { JsonPrimitive(it.value) })
        )
        val encoded = URLEncoder.encode(jsonStr, "UTF-8").replace("+", "%20")
        return "$origin/$encoded/manifest.json"
    }

    fun fetchManifest(
        manifestUrl: String,
        customHeaders: Map<String, String> = emptyMap()
    ): StremioAddonManifest {
        val raw = get(manifestUrl, customHeaders)
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
        val url = if (!searchQuery.isNullOrBlank()) {
            "$baseUrl/catalog/$encodedType/$encodedId/search=${encode(searchQuery)}.json"
        } else {
            "$baseUrl/catalog/$encodedType/$encodedId.json"
        }
        val raw = get(url, customHeaders)
        return json.decodeFromString(StremioCatalogResponse.serializer(), raw)
    }

    fun fetchMeta(
        baseUrl: String,
        type: String,
        id: String,
        customHeaders: Map<String, String> = emptyMap()
    ): StremioMetaResponse {
        val url = "$baseUrl/meta/${encode(type)}/${encode(id)}.json"
        val raw = get(url, customHeaders)
        return json.decodeFromString(StremioMetaResponse.serializer(), raw)
    }

    fun fetchStreams(
        baseUrl: String,
        type: String,
        id: String,
        customHeaders: Map<String, String> = emptyMap()
    ): StremioStreamsResponse {
        val url = "$baseUrl/stream/${encode(type)}/${encode(id)}.json"
        val raw = get(url, customHeaders)
        return json.decodeFromString(StremioStreamsResponse.serializer(), raw)
    }

    private fun get(url: String, customHeaders: Map<String, String>): String {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", BROWSER_USER_AGENT)
            .header("Accept", "application/json, text/plain, */*")
            .get()

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
