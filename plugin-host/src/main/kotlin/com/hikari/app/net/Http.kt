// SPDX-License-Identifier: GPL-3.0-or-later
package com.hikari.app.net

import android.annotation.SuppressLint
import android.app.Activity
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

data class RenderedWebPage(
    val html: String,
    val cookies: String,
    val userAgent: String,
    val finalUrl: String
)

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

    private fun resolveWebViewContext(): Context? {
        val activity = CommonActivity.activity ?: HikariApp.mainActivity
        if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
            return activity
        }
        return appContext
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
        getStringRenderedAsync(url, timeoutMs)
    }

    suspend fun getStringRenderedAsync(url: String, timeoutMs: Long = 25_000L): String? {
        val ctx = resolveWebViewContext() ?: return null
        return withTimeoutOrNull(timeoutMs.coerceIn(5_000L, 45_000L)) {
            renderHtmlInWebView(ctx, url)
        }
    }

    suspend fun renderPageForWaf(
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Long = 22_000L
    ): RenderedWebPage {
        val ctx = resolveWebViewContext()
            ?: return RenderedWebPage(html = "", cookies = "", userAgent = UA, finalUrl = url)
        return withTimeoutOrNull(timeoutMs.coerceIn(5_000L, 45_000L)) {
            renderPageInWebView(ctx, url, headers)
        } ?: RenderedWebPage(
            html = "",
            cookies = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull().orEmpty(),
            userAgent = UA,
            finalUrl = url
        )
    }

    suspend fun resolveWithWebView(
        url: String,
        capture: Regex,
        additional: List<Regex>,
        timeoutMs: Long,
        script: String?
    ): List<HikariWebViewResult> {
        val ctx = resolveWebViewContext() ?: return emptyList()
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
        renderPageInWebView(context, url, emptyMap()).html.takeIf { it.isNotBlank() }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun renderPageInWebView(
        context: Context,
        url: String,
        headers: Map<String, String>
    ): RenderedWebPage = withContext(Dispatchers.Main) {
        val deferred = CompletableDeferred<RenderedWebPage>()
        var webView: WebView? = null
        var destroyed = false
        val mainHandler = Handler(Looper.getMainLooper())
        try {
            runCatching { CookieManager.getInstance().setAcceptCookie(true) }
            webView = WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.userAgentString = headers.entries
                    .firstOrNull { it.key.equals("user-agent", ignoreCase = true) }
                    ?.value
                    ?.takeIf { it.isNotBlank() }
                    ?: UA
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            }
            val finish = { html: String?, loadedUrl: String? ->
                if (!deferred.isCompleted) {
                    val target = loadedUrl?.takeIf { it.isNotBlank() } ?: url
                    val cookies = runCatching {
                        CookieManager.getInstance().flush()
                        CookieManager.getInstance().getCookie(target)
                            ?: CookieManager.getInstance().getCookie(url)
                    }.getOrNull().orEmpty()
                    val ua = runCatching { webView?.settings?.userAgentString }.getOrNull() ?: UA
                    deferred.complete(
                        RenderedWebPage(
                            html = html.orEmpty(),
                            cookies = cookies,
                            userAgent = ua,
                            finalUrl = target
                        )
                    )
                }
            }
            fun pollRendered(view: WebView, attemptsLeft: Int) {
                if (destroyed || deferred.isCompleted) return
                runCatching {
                    view.evaluateJavascript(
                        "(function(){return document.documentElement ? document.documentElement.outerHTML : '';})();"
                    ) { raw ->
                        if (destroyed || deferred.isCompleted) return@evaluateJavascript
                        val decoded = decodeJsString(raw)
                        if (!decoded.isNullOrBlank() && !isWafChallenge(decoded)) {
                            finish(decoded, view.url)
                        } else if (attemptsLeft > 0) {
                            mainHandler.postDelayed({ pollRendered(view, attemptsLeft - 1) }, 750L)
                        } else {
                            finish(decoded, view.url)
                        }
                    }
                }.onFailure {
                    finish(null, url)
                }
            }
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                    super.onPageFinished(view, loadedUrl)
                    if (!destroyed && view != null && !deferred.isCompleted) {
                        mainHandler.postDelayed({ pollRendered(view, 12) }, 600L)
                    }
                }
            }
            if (headers.isNotEmpty()) {
                webView.loadUrl(url, headers)
            } else {
                webView.loadUrl(url)
            }
            deferred.await()
        } catch (_: Throwable) {
            RenderedWebPage(html = "", cookies = "", userAgent = UA, finalUrl = url)
        } finally {
            destroyed = true
            mainHandler.removeCallbacksAndMessages(null)
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
        var destroyed = false
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
                            mainHandler.removeCallbacks(completeNow)
                            mainHandler.postDelayed(completeNow, 400L)
                        }
                    }
                    return super.shouldInterceptRequest(view, request)
                }

                override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                    super.onPageFinished(view, loadedUrl)
                    if (!destroyed && !script.isNullOrBlank() && view != null) {
                        runCatching { view.evaluateJavascript(script, null) }
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
            destroyed = true
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
