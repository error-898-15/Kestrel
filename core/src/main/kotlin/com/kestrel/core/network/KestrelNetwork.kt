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
 * Fast DNS with automatic DNS-over-HTTPS fallback.
 * Uses System DNS immediately when valid (zero extra latency on healthy networks),
 * and falls back to DoH (with short timeouts & dead-endpoint cooldown) when
 * system DNS fails or returns hijacked loopback/unspecified addresses.
 */
object KestrelDohDns : Dns {
    private data class CacheEntry(val addresses: List<InetAddress>, val expiresAtMs: Long)

    private const val CACHE_TTL_MS = 5 * 60_000L
    private const val ENDPOINT_COOLDOWN_MS = 2 * 60_000L
    private const val MAX_CACHE_ENTRIES = 512

    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val endpointCooldownUntil = ConcurrentHashMap<String, Long>()

    private val bootstrapClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(Dns.SYSTEM)
            .connectTimeout(2500, TimeUnit.MILLISECONDS)
            .readTimeout(2500, TimeUnit.MILLISECONDS)
            .callTimeout(3000, TimeUnit.MILLISECONDS)
            .build()
    }

    private val dohEndpoints = listOf(
        "https://dns.google/resolve",
        "https://1.1.1.1/dns-query"
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

        // Try system DNS first; return immediately if valid so healthy networks never block on DoH.
        val systemResult = runCatching { Dns.SYSTEM.lookup(hostname) }.getOrNull()
        val validSystem = systemResult?.filterNot {
            it.isLoopbackAddress || it.isAnyLocalAddress || it.hostAddress == "0.0.0.0"
        }.orEmpty()

        val finalList = if (validSystem.isNotEmpty()) {
            validSystem
        } else {
            val dohResult = resolveViaDoh(cleanHost, now)
            when {
                dohResult.isNotEmpty() -> dohResult
                !systemResult.isNullOrEmpty() -> systemResult
                else -> emptyList()
            }
        }

        if (finalList.isEmpty()) {
            throw UnknownHostException("Unable to resolve host $hostname via System DNS or DoH")
        }

        if (cache.size > MAX_CACHE_ENTRIES) {
            cache.entries.removeIf { it.value.expiresAtMs <= now }
            if (cache.size > MAX_CACHE_ENTRIES) cache.clear()
        }
        cache[cleanHost] = CacheEntry(finalList, now + CACHE_TTL_MS)
        return finalList
    }

    private fun resolveViaDoh(hostname: String, nowMs: Long): List<InetAddress> {
        for (endpoint in dohEndpoints) {
            val blockedUntil = endpointCooldownUntil[endpoint] ?: 0L
            if (blockedUntil > nowMs) continue
            val resolved = queryEndpoint(endpoint, hostname)
            if (resolved.isNotEmpty()) return resolved
            endpointCooldownUntil[endpoint] = System.currentTimeMillis() + ENDPOINT_COOLDOWN_MS
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
