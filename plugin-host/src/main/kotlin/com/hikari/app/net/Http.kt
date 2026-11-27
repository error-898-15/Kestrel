// SPDX-License-Identifier: GPL-3.0-or-later
package com.hikari.app.net

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.hikari.app.HikariApp
import com.hikari.ext.HikariWebViewResult
import com.lagradost.cloudstream3.CommonActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONTokener
import java.util.Collections
import java.util.concurrent.TimeUnit

object Http {
    const val UA =
        "Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    @Volatile
    private var client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context, okHttpClient: OkHttpClient) {
        appContext = context.applicationContext
        client = okHttpClient
    }

    fun get(url: String, headers: Map<String, String> = emptyMap()): Response {
        val builder = Request.Builder().url(url).get()
        applyHeaders(builder, headers, url)
        return client.newCall(builder.build()).execute()
    }

    fun getString(url: String, headers: Map<String, String> = emptyMap()): String? =
        runCatching {
            get(url, headers).use { resp ->
                if (!resp.isSuccessful) return@use null
                resp.body?.string()
            }
        }.getOrNull()

    fun postString(
        url: String,
        body: String,
        headers: Map<String, String> = emptyMap(),
        contentType: String = "application/json; charset=utf-8"
    ): String? = runCatching {
        val mediaType = headers.entries
            .firstOrNull { it.key.equals("content-type", ignoreCase = true) }
            ?.value
            ?.toMediaTypeOrNull()
            ?: contentType.toMediaTypeOrNull()
        val builder = Request.Builder()
            .url(url)
            .post(body.toRequestBody(mediaType))
        applyHeaders(builder, headers, url)
        client.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) return@use null
            resp.body?.string()
        }
    }.getOrNull()

    fun getBytes(url: String, headers: Map<String, String> = emptyMap()): ByteArray? =
        runCatching {
            get(url, headers).use { resp ->
                if (!resp.isSuccessful) return@use null
                resp.body?.bytes()
            }
        }.getOrNull()

    fun getStringSmart(url: String, headers: Map<String, String> = emptyMap()): String? {
        val direct = runCatching {
            get(url, headers).use { resp ->
                val text = resp.body?.string()
                if (resp.isSuccessful && !text.isNullOrBlank() && !isWafChallenge(text)) {
                    return text
                }
                null
            }
        }.getOrNull()
        if (!direct.isNullOrBlank()) return direct
        return getStringRendered(url, 22_000L)
    }

    fun getStringRendered(url: String, timeoutMs: Long = 25_000L): String? = runBlocking {
        val ctx = CommonActivity.activity ?: HikariApp.mainActivity ?: appContext ?: return@runBlocking null
        withTimeoutOrNull(timeoutMs.coerceIn(5_000L, 45_000L)) {
            renderHtmlInWebView(ctx, url)
        }
    }

    suspend fun resolveWithWebView(
        url: String,
        capture: Regex,
        additional: List<Regex>,
        timeoutMs: Long,
        script: String?
    ): List<HikariWebViewResult> {
        val ctx = CommonActivity.activity ?: HikariApp.mainActivity ?: appContext ?: return emptyList()
        val matchers = listOf(capture) + additional
        return withTimeoutOrNull(timeoutMs.coerceIn(5_000L, 60_000L)) {
            captureRequestsInWebView(ctx, url, matchers, script)
        }.orEmpty()
    }

    private fun applyHeaders(builder: Request.Builder, headers: Map<String, String>, url: String) {
        var hasUa = false
        headers.forEach { (k, v) ->
            if (v.isBlank()) return@forEach
            if (k.equals("user-agent", ignoreCase = true)) hasUa = true
            builder.header(k, v)
        }
        if (!hasUa) {
            builder.header("User-Agent", UA)
        }
        if (headers.keys.none { it.equals("cookie", ignoreCase = true) }) {
            val cookie = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()
            if (!cookie.isNullOrBlank()) {
                builder.header("Cookie", cookie)
            }
        }
    }

    private fun isWafChallenge(html: String): Boolean {
        val head = html.take(4096).lowercase()
        return ("just a moment" in head && ("cloudflare" in head || "cf-browser-verification" in head || "__cf_chl" in head)) ||
            "challenge-platform" in head
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun renderHtmlInWebView(context: Context, url: String): String? =
        withContext(Dispatchers.Main) {
            val deferred = CompletableDeferred<String?>()
            var webView: WebView? = null
            val mainHandler = Handler(Looper.getMainLooper())
            try {
                webView = WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.userAgentString = UA
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                }
                val finish = { html: String? ->
                    if (!deferred.isCompleted) deferred.complete(html)
                }
                fun pollRendered(view: WebView, attemptsLeft: Int) {
                    if (deferred.isCompleted) return
                    view.evaluateJavascript(
                        "(function(){return document.documentElement ? document.documentElement.outerHTML : '';})();"
                    ) { raw ->
                        val decoded = decodeJsString(raw)
                        if (!decoded.isNullOrBlank() && !isWafChallenge(decoded)) {
                            runCatching { CookieManager.getInstance().flush() }
                            finish(decoded)
                        } else if (attemptsLeft > 0) {
                            mainHandler.postDelayed({ pollRendered(view, attemptsLeft - 1) }, 750L)
                        } else {
                            finish(decoded)
                        }
                    }
                }
                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                        super.onPageFinished(view, loadedUrl)
                        if (view != null && !deferred.isCompleted) {
                            mainHandler.postDelayed({ pollRendered(view, 12) }, 600L)
                        }
                    }
                }
                webView.loadUrl(url)
                deferred.await()
            } catch (_: Throwable) {
                null
            } finally {
                runCatching {
                    webView?.stopLoading()
                    webView?.destroy()
                }
            }
        }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun captureRequestsInWebView(
        context: Context,
        url: String,
        matchers: List<Regex>,
        script: String?
    ): List<HikariWebViewResult> = withContext(Dispatchers.Main) {
        val deferred = CompletableDeferred<List<HikariWebViewResult>>()
        val captured = Collections.synchronizedList(mutableListOf<HikariWebViewResult>())
        val seenUrls = Collections.synchronizedSet(HashSet<String>())
        var webView: WebView? = null
        val mainHandler = Handler(Looper.getMainLooper())
        try {
            webView = WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                settings.userAgentString = UA
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            }
            val completeNow = Runnable {
                if (!deferred.isCompleted) {
                    deferred.complete(captured.toList())
                }
            }
            webView.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    val reqUrl = request?.url?.toString().orEmpty()
                    if (reqUrl.isNotBlank() && matchers.any { it.containsMatchIn(reqUrl) }) {
                        if (seenUrls.add(reqUrl)) {
                            val hdrs = request?.requestHeaders
                                ?.filterValues { it.isNotBlank() }
                                .orEmpty()
                            captured.add(HikariWebViewResult(reqUrl, hdrs))
                            // Give page 400ms to fire secondary/additional matches before completing.
                            mainHandler.removeCallbacks(completeNow)
                            mainHandler.postDelayed(completeNow, 400L)
                        }
                    }
                    return super.shouldInterceptRequest(view, request)
                }

                override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                    super.onPageFinished(view, loadedUrl)
                    if (!script.isNullOrBlank() && view != null) {
                        view.evaluateJavascript(script, null)
                    }
                }
            }
            val hostHeaders = buildMap {
                Uri.parse(url).host?.let { host ->
                    put("Referer", "https://$host/")
                }
            }
            webView.loadUrl(url, hostHeaders)
            deferred.await()
        } catch (_: Throwable) {
            captured.toList()
        } finally {
            mainHandler.removeCallbacksAndMessages(null)
            runCatching {
                webView?.stopLoading()
                webView?.destroy()
            }
        }
    }

    private fun decodeJsString(raw: String?): String? {
        if (raw.isNullOrBlank() || raw == "null") return null
        return runCatching {
            JSONTokener(raw).nextValue() as? String
        }.getOrNull() ?: raw.trim('"')
    }
}
