// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.pluginhost.stremio

import kotlinx.serialization.json.Json
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
    fun baseUrlOf(manifestUrl: String): String =
        manifestUrl.removeSuffix("/manifest.json").trimEnd('/')

    fun fetchManifest(manifestUrl: String): StremioAddonManifest {
        val raw = get(manifestUrl)
        return json.decodeFromString(StremioAddonManifest.serializer(), raw)
    }

    fun fetchCatalog(
        baseUrl: String,
        type: String,
        catalogId: String,
        searchQuery: String? = null
    ): StremioCatalogResponse {
        val encodedType = encode(type)
        val encodedId = encode(catalogId)
        val url = if (!searchQuery.isNullOrBlank()) {
            "$baseUrl/catalog/$encodedType/$encodedId/search=${encode(searchQuery)}.json"
        } else {
            "$baseUrl/catalog/$encodedType/$encodedId.json"
        }
        val raw = get(url)
        return json.decodeFromString(StremioCatalogResponse.serializer(), raw)
    }

    fun fetchMeta(baseUrl: String, type: String, id: String): StremioMetaResponse {
        val url = "$baseUrl/meta/${encode(type)}/${encode(id)}.json"
        val raw = get(url)
        return json.decodeFromString(StremioMetaResponse.serializer(), raw)
    }

    fun fetchStreams(baseUrl: String, type: String, id: String): StremioStreamsResponse {
        val url = "$baseUrl/stream/${encode(type)}/${encode(id)}.json"
        val raw = get(url)
        return json.decodeFromString(StremioStreamsResponse.serializer(), raw)
    }

    private fun get(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Kestrel/0.1 (Android)")
            .get()
            .build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code} from $url")
            return response.body?.string().orEmpty()
        }
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
