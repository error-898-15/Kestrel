// SPDX-License-Identifier: GPL-3.0-or-later
package com.lagradost.cloudstream3.network

import android.webkit.CookieManager
import okhttp3.Headers
import okhttp3.Headers.Companion.toHeaders
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Compatibility shim for CloudStream's `:app` CloudflareKiller interceptor.
 */
class CloudflareKiller : Interceptor {
    companion object {
        const val TAG = "CloudflareKiller"

        fun parseCookieMap(cookie: String): Map<String, String> {
            return cookie.split(";").associate {
                val split = it.split("=", limit = 2)
                (split.getOrNull(0)?.trim() ?: "") to (split.getOrNull(1)?.trim() ?: "")
            }.filter { it.key.isNotBlank() && it.value.isNotBlank() }
        }
    }

    val savedCookies: MutableMap<String, Map<String, String>> = mutableMapOf()

    fun getCookieHeaders(url: String): Headers {
        val host = runCatching { java.net.URI(url).host }.getOrNull().orEmpty()
        val cached = savedCookies[host].orEmpty().toMutableMap()
        runCatching {
            val cm = CookieManager.getInstance()
            val raw = cm?.getCookie(url)
            if (!raw.isNullOrBlank()) {
                cached.putAll(parseCookieMap(raw))
            }
        }
        val headerMap = if (cached.isNotEmpty()) {
            mapOf("Cookie" to cached.entries.joinToString("; ") { "${it.key}=${it.value}" })
        } else {
            emptyMap()
        }
        return headerMap.toHeaders()
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val host = request.url.host
        val existing = savedCookies[host]
        val finalReq = if (!existing.isNullOrEmpty() && request.header("Cookie") == null) {
            request.newBuilder()
                .header("Cookie", existing.entries.joinToString("; ") { "${it.key}=${it.value}" })
                .build()
        } else {
            request
        }
        return chain.proceed(finalReq)
    }
}
