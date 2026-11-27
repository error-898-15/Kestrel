// SPDX-License-Identifier: GPL-3.0-or-later
package com.hikari.ext

import android.util.Base64
import com.hikari.app.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** A request the page fired that matched the capture regex. */
data class HikariWebViewResult(
    val url: String,
    val headers: Map<String, String> = emptyMap()
)

/** Full HTTP response summary (for callers that must inspect errors). */
data class HikariResponse(
    val status: Int,
    val url: String,
    val body: String? = null
)

/**
 * Runtime network helper library resolved parent-first by `.hiki` DEX plugins.
 */
object HikariNet {
    val browserHeaders: Map<String, String> = mapOf(
        "User-Agent" to Http.UA,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9"
    )

    suspend fun getString(url: String, headers: Map<String, String> = emptyMap()): String? =
        withContext(Dispatchers.IO) {
            Http.getString(url, headers)
        }

    suspend fun postString(
        url: String,
        body: String,
        headers: Map<String, String> = emptyMap(),
        contentType: String = "application/json; charset=utf-8"
    ): String? = withContext(Dispatchers.IO) {
        Http.postString(url, body, headers, contentType)
    }

    suspend fun postJson(
        url: String,
        body: JSONObject,
        headers: Map<String, String> = emptyMap()
    ): JSONObject? = withContext(Dispatchers.IO) {
        postString(
            url,
            body.toString(),
            headers + mapOf("Content-Type" to "application/json; charset=utf-8")
        )?.let { runCatching { JSONObject(it) }.getOrNull() }
    }

    fun base64Decode(s: String): ByteArray? = runCatching {
        Base64.decode(s, Base64.DEFAULT)
    }.getOrNull()

    fun base64Encode(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.NO_WRAP)

    suspend fun getStringSmart(url: String, headers: Map<String, String> = emptyMap()): String? =
        withContext(Dispatchers.IO) {
            Http.getStringSmart(url, headers)
        }

    suspend fun getStringRendered(url: String, timeoutMs: Long = 25_000): String? =
        withContext(Dispatchers.IO) {
            Http.getStringRendered(url, timeoutMs)
        }

    suspend fun getJson(url: String, headers: Map<String, String> = emptyMap()): JSONObject? =
        withContext(Dispatchers.IO) {
            getString(url, headers)?.let { runCatching { JSONObject(it) }.getOrNull() }
        }

    suspend fun getBytes(url: String, headers: Map<String, String> = emptyMap()): ByteArray? =
        withContext(Dispatchers.IO) {
            Http.getBytes(url, headers)
        }

    suspend fun fetch(url: String, headers: Map<String, String> = emptyMap()): HikariResponse? =
        withContext(Dispatchers.IO) {
            runCatching {
                Http.get(url, headers).use { r ->
                    HikariResponse(r.code, r.request.url.toString(), r.body?.string())
                }
            }.getOrNull()
        }

    suspend fun resolveWithWebView(
        url: String,
        capture: Regex,
        additional: List<Regex> = emptyList(),
        timeoutMs: Long = 60_000,
        script: String? = null
    ): List<HikariWebViewResult> =
        Http.resolveWithWebView(
            url = url,
            capture = capture,
            additional = additional,
            timeoutMs = timeoutMs,
            script = script
        )
}
