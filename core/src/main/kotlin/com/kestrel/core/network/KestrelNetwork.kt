// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.core.network

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Thread-safe in-memory CookieJar so scraping providers, Cloudflare cookies,
 * and video CDNs retain session/verification cookies across redirects.
 */
object KestrelCookieJar : CookieJar {
    private val store = ConcurrentHashMap<String, MutableList<Cookie>>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        val key = url.host.lowercase()
        val existing = store.getOrPut(key) { mutableListOf() }
        synchronized(existing) {
            val now = System.currentTimeMillis()
            existing.removeAll { it.expiresAt < now }
            for (incoming in cookies) {
                existing.removeAll { it.name == incoming.name && it.domain == incoming.domain && it.path == incoming.path }
                if (incoming.expiresAt >= now) {
                    existing.add(incoming)
                }
            }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        val host = url.host.lowercase()
        val matched = mutableListOf<Cookie>()
        for ((domainKey, list) in store) {
            if (host == domainKey || host.endsWith(".$domainKey")) {
                synchronized(list) {
                    list.removeAll { it.expiresAt < now }
                    for (c in list) {
                        if (c.matches(url)) matched.add(c)
                    }
                }
            }
        }
        return matched
    }
}

/**
 * DNS-over-HTTPS (Cloudflare + Google JSON DoH) with short-lived cache and automatic
 * fallback to system DNS. Prevents ISP DNS poisoning/blocking on provider domains.
 */
object KestrelDohDns : Dns {
    private data class CacheEntry(val addresses: List<InetAddress>, val expiresAtMs: Long)

    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val bootstrapClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(Dns.SYSTEM)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    private val dohEndpoints = listOf(
        "https://1.1.1.1/dns-query",
        "https://dns.google/resolve"
    )

    override fun lookup(hostname: String): List<InetAddress> {
        val cleanHost = hostname.trim().lowercase()
        if (cleanHost.isEmpty()) throw UnknownHostException(hostname)

        // Direct IP literals or DoH bootstrap hosts go straight to system DNS.
        if (cleanHost == "1.1.1.1" || cleanHost == "dns.google" ||
            cleanHost == "cloudflare-dns.com" || isIpLiteral(cleanHost)
        ) {
            return Dns.SYSTEM.lookup(hostname)
        }

        val now = System.currentTimeMillis()
        cache[cleanHost]?.let { entry ->
            if (entry.expiresAtMs > now && entry.addresses.isNotEmpty()) {
                return entry.addresses
            }
        }

        // Try system DNS first; if it resolves to an obviously hijacked loopback/unspecified
        // address or throws UnknownHostException, resolve cleanly via DoH.
        val systemResult = runCatching { Dns.SYSTEM.lookup(hostname) }.getOrNull()
        val validSystem = systemResult?.filterNot {
            it.isLoopbackAddress || it.isAnyLocalAddress || it.hostAddress == "0.0.0.0"
        }.orEmpty()

        val dohResult = resolveViaDoh(cleanHost)
        val finalList = when {
            dohResult.isNotEmpty() -> dohResult
            validSystem.isNotEmpty() -> validSystem
            else -> systemResult ?: emptyList()
        }

        if (finalList.isEmpty()) {
            throw UnknownHostException("Unable to resolve host $hostname via DoH or System DNS")
        }

        cache[cleanHost] = CacheEntry(finalList, now + 5 * 60_000L)
        return finalList
    }

    private fun resolveViaDoh(hostname: String): List<InetAddress> {
        for (endpoint in dohEndpoints) {
            val resolved = queryEndpoint(endpoint, hostname)
            if (resolved.isNotEmpty()) return resolved
        }
        return emptyList()
    }

    private fun queryEndpoint(endpoint: String, hostname: String): List<InetAddress> {
        return runCatching {
            val req = Request.Builder()
                .url("$endpoint?name=$hostname&type=A")
                .header("Accept", "application/dns-json")
                .get()
                .build()
            bootstrapClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val body = resp.body?.string() ?: return emptyList()
                val json = JSONObject(body)
                val answers = json.optJSONArray("Answer") ?: return emptyList()
                val out = mutableListOf<InetAddress>()
                for (i in 0 until answers.length()) {
                    val ans = answers.optJSONObject(i) ?: continue
                    if (ans.optInt("type") == 1) {
                        val ip = ans.optString("data").trim()
                        if (ip.isNotBlank() && ip != "0.0.0.0" && ip != "127.0.0.1") {
                            runCatching { InetAddress.getByName(ip) }.getOrNull()?.let(out::add)
                        }
                    }
                }
                out
            }
        }.getOrDefault(emptyList())
    }

    private fun isIpLiteral(host: String): Boolean {
        return host.all { it.isDigit() || it == '.' } || host.contains(':')
    }
}
