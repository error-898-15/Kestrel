// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.core.designsystem

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.crossfade
import okhttp3.OkHttpClient
import java.io.File

/**
 * Installs a bounded Coil 3 ImageLoader backed by Kestrel's shared OkHttpClient
 * to keep memory and disk usage smooth while scrolling catalog rows.
 */
object KestrelImageLoading {
    fun install(context: Context, okHttpClient: OkHttpClient) {
        val appCtx = context.applicationContext
        SingletonImageLoader.setSafe {
            ImageLoader.Builder(appCtx)
                .components {
                    add(OkHttpNetworkFetcherFactory(callFactory = { okHttpClient }))
                }
                .memoryCache {
                    MemoryCache.Builder()
                        .maxSizePercent(appCtx, 0.20)
                        .build()
                }
                .diskCache {
                    DiskCache.Builder()
                        .directory(File(appCtx.cacheDir, "image_cache"))
                        .maxSizeBytes(100L * 1024L * 1024L)
                        .build()
                }
                .crossfade(120)
                .allowHardware(true)
                .build()
        }
    }
}

/**
 * Builds (and remembers) a Coil ImageRequest for the given url, optional HTTP headers and
 * target pixel size. Returns null when the url is null or blank.
 */
@Composable
fun rememberKestrelImageRequest(
    url: String?,
    headers: Map<String, String>,
    width: Int,
    height: Int
): ImageRequest? {
    val context = LocalContext.current
    return remember(url, headers, width, height) {
        if (url.isNullOrBlank()) {
            null
        } else {
            ImageRequest.Builder(context)
                .data(url)
                .size(width, height)
                .apply {
                    if (headers.isNotEmpty()) {
                        val hb = NetworkHeaders.Builder()
                        headers.forEach { (k, v) -> hb[k] = v }
                        httpHeaders(hb.build())
                    }
                }
                .build()
        }
    }
}
