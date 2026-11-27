// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.pluginhost.skystream

import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lightweight localhost HTTP proxy for SkyStream `magic_m3u8:`, `MAGIC_PROXY_v1`,
 * `MAGIC_PROXY:`, and `MAGIC_PROXY_v2` URLs so ExoPlayer can play inline manifests
 * and header-authenticated streams without custom DataSource plumbing.
 */
@Singleton
class SkyLocalProxyService @Inject constructor(
    private val okHttpClient: OkHttpClient
) {
    private data class ProxyTarget(
        val url: String,
        val headers: Map<String, String>
    )

    private val startLock = Any()
    @Volatile
    private var serverSocket: ServerSocket? = null
    @Volatile
    private var port: Int = -1
    private val acceptExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "SkyProxy-Accept").apply { isDaemon = true }
    }
    private val clientExecutor: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(r, "SkyProxy-Worker").apply { isDaemon = true }
    }

    private val m3u8Store = ConcurrentHashMap<String, String>()
    private val m3u8Order = ConcurrentLinkedQueue<String>()
    private val proxyStore = ConcurrentHashMap<String, ProxyTarget>()
    private val proxyOrder = ConcurrentLinkedQueue<String>()

    private val proxyClient: OkHttpClient by lazy {
        okHttpClient.newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    fun resolveMagicUrl(rawUrl: String, defaultHeaders: Map<String, String> = emptyMap()): String {
        val url = rawUrl.trim()
        return when {
            url.startsWith("magic_m3u8:") -> {
                val b64 = url.removePrefix("magic_m3u8:")
                val decoded = runCatching {
                    String(decodeBase64(b64), Charsets.UTF_8)
                }.getOrNull() ?: return url
                val basePort = ensureStarted()
                if (basePort <= 0) return url
                val rewritten = rewriteM3u8Content(decoded, defaultHeaders)
                val id = UUID.randomUUID().toString().replace("-", "")
                putBounded(m3u8Store, m3u8Order, id, rewritten)
                "http://127.0.0.1:$basePort/m3u8/$id.m3u8"
            }

            url.startsWith("MAGIC_PROXY_v1") || url.startsWith("MAGIC_PROXY:") -> {
                val b64 = if (url.startsWith("MAGIC_PROXY_v1")) {
                    url.removePrefix("MAGIC_PROXY_v1")
                } else {
                    url.removePrefix("MAGIC_PROXY:")
                }
                val realUrl = runCatching {
                    String(decodeBase64(b64), Charsets.UTF_8).trim()
                }.getOrNull()?.takeIf { it.startsWith("http", ignoreCase = true) } ?: return url
                registerProxyTarget(realUrl, defaultHeaders)
            }

            url.startsWith("MAGIC_PROXY_v2") -> {
                val b64 = url.removePrefix("MAGIC_PROXY_v2")
                val cfg = runCatching {
                    JSONObject(String(decodeBase64(b64), Charsets.UTF_8))
                }.getOrNull() ?: return url
                val realUrl = cfg.optString("url").trim()
                if (!realUrl.startsWith("http", ignoreCase = true)) return url
                val mergedHeaders = LinkedHashMap<String, String>(defaultHeaders)
                val hdrObj = cfg.optJSONObject("headers")
                if (hdrObj != null) {
                    val keys = hdrObj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        val v = hdrObj.optString(k)
                        if (k.isNotBlank() && v.isNotBlank()) mergedHeaders[k] = v
                    }
                }
                registerProxyTarget(realUrl, mergedHeaders)
            }

            else -> url
        }
    }

    private fun registerProxyTarget(realUrl: String, headers: Map<String, String>): String {
        val basePort = ensureStarted()
        if (basePort <= 0) return realUrl
        val id = UUID.randomUUID().toString().replace("-", "")
        putBounded(proxyStore, proxyOrder, id, ProxyTarget(realUrl, headers))
        return "http://127.0.0.1:$basePort/proxy/$id"
    }

    private fun rewriteM3u8Content(content: String, headers: Map<String, String>): String {
        val uriRegex = Regex("URI=\"(MAGIC_PROXY[^\"]+)\"")
        return content.lineSequence().joinToString("\n") { line ->
            val trimmed = line.trim()
            when {
                trimmed.startsWith("MAGIC_PROXY_v1") ||
                    trimmed.startsWith("MAGIC_PROXY:") ||
                    trimmed.startsWith("MAGIC_PROXY_v2") -> resolveMagicUrl(trimmed, headers)
                trimmed.contains("MAGIC_PROXY") -> uriRegex.replace(line) { m ->
                    "URI=\"${resolveMagicUrl(m.groupValues[1], headers)}\""
                }
                else -> line
            }
        }
    }

    private fun ensureStarted(): Int {
        if (port > 0 && serverSocket?.isClosed == false) return port
        synchronized(startLock) {
            if (port > 0 && serverSocket?.isClosed == false) return port
            return runCatching {
                val socket = ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"))
                serverSocket = socket
                port = socket.localPort
                acceptExecutor.execute { acceptLoop(socket) }
                port
            }.getOrDefault(-1)
        }
    }

    private fun acceptLoop(server: ServerSocket) {
        while (!server.isClosed) {
            val client = runCatching { server.accept() }.getOrNull() ?: break
            clientExecutor.execute { handleClient(client) }
        }
    }

    private fun handleClient(socket: Socket) {
        socket.use { s ->
            runCatching {
                s.soTimeout = 30_000
                val input = BufferedInputStream(s.getInputStream())
                val out = BufferedOutputStream(s.getOutputStream())
                val headerText = readHttpHeaderBlock(input) ?: return
                val lines = headerText.split("\r\n")
                val requestLine = lines.firstOrNull().orEmpty().split(" ")
                if (requestLine.size < 2) return
                val method = requestLine[0].uppercase()
                val rawPath = requestLine[1].substringBefore("?")

                val reqHeaders = LinkedHashMap<String, String>()
                for (i in 1 until lines.size) {
                    val line = lines[i]
                    val idx = line.indexOf(':')
                    if (idx > 0) {
                        val k = line.substring(0, idx).trim()
                        val v = line.substring(idx + 1).trim()
                        reqHeaders[k] = v
                    }
                }

                when {
                    rawPath.startsWith("/m3u8/") -> {
                        val id = rawPath.removePrefix("/m3u8/").removeSuffix(".m3u8")
                        val playlist = m3u8Store[id]
                        if (playlist == null) {
                            writeError(out, 404, "Not Found")
                        } else {
                            val bytes = playlist.toByteArray(Charsets.UTF_8)
                            val head = buildString {
                                append("HTTP/1.1 200 OK\r\n")
                                append("Content-Type: application/vnd.apple.mpegurl; charset=utf-8\r\n")
                                append("Content-Length: ${bytes.size}\r\n")
                                append("Access-Control-Allow-Origin: *\r\n")
                                append("Connection: close\r\n\r\n")
                            }
                            out.write(head.toByteArray(Charsets.US_ASCII))
                            if (method != "HEAD") out.write(bytes)
                            out.flush()
                        }
                    }

                    rawPath.startsWith("/proxy/") -> {
                        val id = rawPath.removePrefix("/proxy/")
                        val target = proxyStore[id]
                        if (target == null) {
                            writeError(out, 404, "Not Found")
                            return
                        }
                        val reqBuilder = Request.Builder().url(target.url)
                        if (method == "HEAD") reqBuilder.head() else reqBuilder.get()
                        target.headers.forEach { (k, v) ->
                            runCatching { reqBuilder.header(k, v) }
                        }
                        reqHeaders.entries.firstOrNull { it.key.equals("range", ignoreCase = true) }?.let {
                            reqBuilder.header("Range", it.value)
                        }
                        proxyClient.newCall(reqBuilder.build()).execute().use { resp ->
                            val statusMsg = resp.message.ifBlank { "OK" }
                            val contentType = resp.header("Content-Type") ?: "application/octet-stream"
                            val contentLength = resp.body?.contentLength() ?: -1L
                            val contentRange = resp.header("Content-Range")
                            val acceptRanges = resp.header("Accept-Ranges")
                            val head = buildString {
                                append("HTTP/1.1 ${resp.code} $statusMsg\r\n")
                                append("Content-Type: $contentType\r\n")
                                if (contentLength >= 0L) append("Content-Length: $contentLength\r\n")
                                if (!contentRange.isNullOrBlank()) append("Content-Range: $contentRange\r\n")
                                if (!acceptRanges.isNullOrBlank()) append("Accept-Ranges: $acceptRanges\r\n")
                                append("Access-Control-Allow-Origin: *\r\n")
                                append("Connection: close\r\n\r\n")
                            }
                            out.write(head.toByteArray(Charsets.US_ASCII))
                            if (method != "HEAD") {
                                resp.body?.byteStream()?.use { bodyStream ->
                                    val buf = ByteArray(16 * 1024)
                                    while (true) {
                                        val read = bodyStream.read(buf)
                                        if (read <= 0) break
                                        out.write(buf, 0, read)
                                    }
                                }
                            }
                            out.flush()
                        }
                    }

                    else -> writeError(out, 404, "Not Found")
                }
            }
        }
    }

    private fun readHttpHeaderBlock(input: BufferedInputStream): String? {
        val baos = ByteArrayOutputStream()
        var prev3 = 0
        var prev2 = 0
        var prev1 = 0
        while (baos.size() < 16 * 1024) {
            val b = input.read()
            if (b < 0) return null
            baos.write(b)
            if (prev3 == '\r'.code && prev2 == '\n'.code && prev1 == '\r'.code && b == '\n'.code) {
                return baos.toString(Charsets.US_ASCII.name())
            }
            prev3 = prev2
            prev2 = prev1
            prev1 = b
        }
        return null
    }

    private fun writeError(out: BufferedOutputStream, code: Int, reason: String) {
        val body = reason.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $code $reason\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.US_ASCII))
        out.write(body)
        out.flush()
    }

    private fun decodeBase64(raw: String): ByteArray {
        val stripped = raw.replace(Regex("\\s+"), "").replace('-', '+').replace('_', '/')
        val rem = stripped.length % 4
        val padded = if (rem == 0) stripped else stripped + "=".repeat(4 - rem)
        return Base64.decode(padded, Base64.DEFAULT)
    }

    private fun <V : Any> putBounded(
        map: ConcurrentHashMap<String, V>,
        order: ConcurrentLinkedQueue<String>,
        key: String,
        value: V,
        maxSize: Int = 512
    ) {
        map[key] = value
        order.add(key)
        while (order.size > maxSize) {
            val oldest = order.poll() ?: break
            map.remove(oldest)
        }
    }
}
