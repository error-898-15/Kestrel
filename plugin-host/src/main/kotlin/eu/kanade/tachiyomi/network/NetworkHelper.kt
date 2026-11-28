@file:Suppress("unused")

package eu.kanade.tachiyomi.network

import android.content.Context
import eu.kanade.tachiyomi.network.interceptor.CloudflareInterceptor
import eu.kanade.tachiyomi.network.interceptor.UncaughtExceptionInterceptor
import eu.kanade.tachiyomi.network.interceptor.UserAgentInterceptor
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class NetworkHelper(context: Context) {
    private val appContext: Context = context.applicationContext

    val client: OkHttpClient by lazy {
        val builder = hostClient?.newBuilder() ?: OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(2, TimeUnit.MINUTES)

        // Strip conflicting/legacy interceptors and enforce Mihon's expected default pipeline
        val blockedNames = setOf(
            "UncaughtExceptionInterceptor",
            "UserAgentInterceptor",
            "CloudflareInterceptor",
            "BrotliInterceptor",
            "IgnoreGzipInterceptor",
        )
        val preserved = builder.interceptors().filter { it.javaClass.simpleName !in blockedNames }
        builder.interceptors().clear()
        builder.networkInterceptors().removeAll {
            it.javaClass.simpleName == "BrotliInterceptor" ||
                it.javaClass.simpleName == "IgnoreGzipInterceptor"
        }

        builder
            .addInterceptor(UncaughtExceptionInterceptor())
            .addInterceptor(UserAgentInterceptor(::defaultUserAgentProvider))
            .addInterceptor(CloudflareInterceptor(appContext, ::defaultUserAgentProvider))
        preserved.forEach { builder.addInterceptor(it) }

        builder.build()
    }

    @Deprecated("The regular client handles Cloudflare by default")
    val cloudflareClient: OkHttpClient
        get() = client

    fun defaultUserAgentProvider(): String =
        "Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    companion object {
        @Volatile
        private var hostClient: OkHttpClient? = null

        fun create(context: Context, okHttpClient: OkHttpClient?): NetworkHelper {
            if (okHttpClient != null) {
                hostClient = okHttpClient
            }
            return NetworkHelper(context)
        }
    }
}
