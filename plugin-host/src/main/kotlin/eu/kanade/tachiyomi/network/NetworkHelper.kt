@file:Suppress("unused")

package eu.kanade.tachiyomi.network

import android.content.Context
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class NetworkHelper(context: Context) {
    val client: OkHttpClient = hostClient ?: OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(2, TimeUnit.MINUTES)
        .build()

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
