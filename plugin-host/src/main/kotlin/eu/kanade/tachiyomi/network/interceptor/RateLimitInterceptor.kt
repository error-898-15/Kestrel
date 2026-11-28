@file:Suppress("unused")

package eu.kanade.tachiyomi.network.interceptor

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.util.concurrent.TimeUnit

class RateLimitInterceptor(
    private val host: String? = null,
    private val permits: Int = 5,
    private val period: Long = 1,
    private val unit: TimeUnit = TimeUnit.SECONDS,
) : Interceptor {
    private val windowMillis = unit.toMillis(period).coerceAtLeast(1L)
    private val timestamps = ArrayDeque<Long>()

    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        if (host != null && req.url.host != host) {
            return chain.proceed(req)
        }
        var sleepMs = 0L
        synchronized(timestamps) {
            val now = System.currentTimeMillis()
            while (timestamps.isNotEmpty() && now - timestamps.first() >= windowMillis) {
                timestamps.removeFirst()
            }
            if (timestamps.size >= permits.coerceAtLeast(1)) {
                val oldest = timestamps.first()
                sleepMs = (oldest + windowMillis) - now
            }
        }
        if (sleepMs > 0) {
            Thread.sleep(sleepMs)
        }
        synchronized(timestamps) {
            timestamps.addLast(System.currentTimeMillis())
        }
        return chain.proceed(req)
    }
}

fun OkHttpClient.Builder.rateLimit(
    permits: Int,
    period: Long = 1,
    unit: TimeUnit = TimeUnit.SECONDS,
): OkHttpClient.Builder = addInterceptor(RateLimitInterceptor(null, permits, period, unit))

fun OkHttpClient.Builder.rateLimitHost(
    url: String,
    permits: Int,
    period: Long = 1,
    unit: TimeUnit = TimeUnit.SECONDS,
): OkHttpClient.Builder {
    val host = runCatching { okhttp3.HttpUrl.Builder().let { java.net.URI(url).host } }.getOrNull()
    return addInterceptor(RateLimitInterceptor(host, permits, period, unit))
}
