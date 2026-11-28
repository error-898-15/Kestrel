@file:Suppress("unused")

package eu.kanade.tachiyomi.network.interceptor

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Required by Keiyoushi / Tachiyomi 1.6 extensions in NetworkHelper's default client.
 * Passes normal responses through unchanged, and attempts headless WebView cookie clearance
 * when Cloudflare returns an interactive challenge (403/503 + cf-mitigated: challenge).
 */
class CloudflareInterceptor(
    private val context: Context,
    private val defaultUserAgentProvider: () -> String = { DEFAULT_UA },
) : Interceptor {

    private val handler = Handler(Looper.getMainLooper())

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)

        val server = response.header("Server").orEmpty()
        val isCloudflare = (response.code == 403 || response.code == 503) &&
            (response.header("cf-mitigated") == "challenge" ||
                server.startsWith("cloudflare", ignoreCase = true))

        if (!isCloudflare) {
            return response
        }

        response.close()

        val url = request.url.toString()
        val solvedCookies = solveWithWebView(url, request.header("User-Agent") ?: defaultUserAgentProvider())
            ?: throw IOException("Cloudflare protection blocked ${request.url.host}")

        val retryRequest = request.newBuilder()
            .header("Cookie", mergeCookies(request.header("Cookie"), solvedCookies))
            .build()
        return chain.proceed(retryRequest)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun solveWithWebView(url: String, userAgent: String): String? {
        val latch = CountDownLatch(1)
        var resolvedCookie: String? = null
        var webView: WebView? = null

        handler.post {
            runCatching {
                val cm = CookieManager.getInstance()
                cm.setAcceptCookie(true)
                val wv = WebView(context.applicationContext)
                webView = wv
                cm.setAcceptThirdPartyCookies(wv, true)
                wv.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    cacheMode = WebSettings.LOAD_DEFAULT
                    userAgentString = userAgent
                }
                wv.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                        val cookie = cm.getCookie(finishedUrl ?: url).orEmpty()
                        if (cookie.contains("cf_clearance")) {
                            resolvedCookie = cookie
                            latch.countDown()
                        }
                    }
                }
                wv.loadUrl(url)
            }.onFailure {
                latch.countDown()
            }
        }

        latch.await(12, TimeUnit.SECONDS)

        handler.post {
            runCatching {
                if (resolvedCookie == null) {
                    resolvedCookie = CookieManager.getInstance().getCookie(url)
                        ?.takeIf { it.contains("cf_clearance") }
                }
                webView?.stopLoading()
                webView?.destroy()
            }
        }
        return resolvedCookie
    }

    private fun mergeCookies(existing: String?, solved: String): String {
        if (existing.isNullOrBlank()) return solved
        return "$existing; $solved"
    }

    companion object {
        private const val DEFAULT_UA =
            "Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    }
}
