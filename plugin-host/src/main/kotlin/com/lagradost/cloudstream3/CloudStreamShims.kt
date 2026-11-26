// SPDX-License-Identifier: GPL-3.0-or-later
package com.lagradost.cloudstream3

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import com.kestrel.core.network.KestrelCookieJar
import com.kestrel.core.network.KestrelDohDns
import com.lagradost.cloudstream3.utils.DataStore
import com.lagradost.cloudstream3.utils.Event
import okhttp3.Cache
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.TimeUnit

/**
 * Host-level compatibility shims matching CloudStream3's `:app` runtime API
 * (`CloudStreamApp.Companion`, `AcraApplication.Companion`, `CommonActivity`, `MainActivity`).
 * Compiled with Jetpack Compose enabled so `$stable` fields match `.cs3` expectations.
 */
open class CloudStreamApp : Application() {
    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        context = base
    }

    companion object {
        private const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

        @Volatile
        private var _context: WeakReference<Context>? = null

        @Volatile
        private var appContext: Context? = null

        var context: Context?
            get() = _context?.get() ?: appContext
            private set(value) {
                appContext = value?.applicationContext ?: appContext
                _context = value?.let { WeakReference(it) }
            }

        tailrec fun Context.getActivity(): Activity? = when (this) {
            is Activity -> this
            is ContextWrapper -> baseContext.getActivity()
            else -> CommonActivity.activity
        }

        fun init(context: Context) {
            val ctx = context.applicationContext
            appContext = ctx
            _context = WeakReference(ctx)
            configureCloudStreamNetworking(ctx)
        }

        private fun configureCloudStreamNetworking(context: Context) {
            runCatching {
                val mainApiKt = Class.forName("com.lagradost.cloudstream3.MainAPIKt")
                val getApp = mainApiKt.methods.firstOrNull { it.name == "getApp" && it.parameterCount == 0 }
                val requestsObj = getApp?.invoke(null) ?: return
                val reqClass = requestsObj.javaClass

                runCatching {
                    val setHeaders = reqClass.methods.firstOrNull {
                        it.name == "setDefaultHeaders" && it.parameterCount == 1
                    }
                    setHeaders?.invoke(
                        requestsObj,
                        mapOf(
                            "User-Agent" to BROWSER_UA,
                            "Accept-Language" to "en-US,en;q=0.9"
                        )
                    )
                }

                runCatching {
                    val getBaseClient = reqClass.methods.firstOrNull {
                        it.name == "getBaseClient" && it.parameterCount == 0
                    }
                    val setBaseClient = reqClass.methods.firstOrNull {
                        it.name == "setBaseClient" && it.parameterCount == 1
                    }
                    val client = getBaseClient?.invoke(requestsObj) as? okhttp3.OkHttpClient
                    if (client != null && setBaseClient != null) {
                        val cacheDir = File(context.cacheDir, "cs3_http_cache").apply { mkdirs() }
                        val updated = client.newBuilder()
                            .dns(KestrelDohDns)
                            .cookieJar(KestrelCookieJar)
                            .connectTimeout(25, TimeUnit.SECONDS)
                            .readTimeout(35, TimeUnit.SECONDS)
                            .writeTimeout(25, TimeUnit.SECONDS)
                            .retryOnConnectionFailure(true)
                            .followRedirects(true)
                            .followSslRedirects(true)
                            .cache(Cache(cacheDir, 30L * 1024L * 1024L))
                            .build()
                        setBaseClient.invoke(requestsObj, updated)
                    }
                }
            }.onFailure {
                Log.w("KestrelCS3", "CloudStream NiceHttp tuning skipped: ${it.message}")
            }
        }

        fun <T : Any> getKeyClass(path: String, valueType: Class<T>): T? =
            DataStore.getKey(path, valueType)

        fun <T : Any> setKeyClass(path: String, value: T) {
            DataStore.setKey(path, value)
        }

        fun <T : Any> getKey(path: String, valueType: Class<T>): T? =
            DataStore.getKey(path, valueType)

        fun <T> getKey(folder: String, path: String, valueType: Class<T>): T? =
            DataStore.getKey(folder, path, valueType)

        inline fun <reified T : Any> getKey(path: String): T? =
            getKeyClass(path, T::class.java)

        inline fun <reified T : Any> getKey(path: String, defVal: T?): T? =
            getKeyClass(path, T::class.java) ?: defVal

        inline fun <reified T : Any> getKey(folder: String, path: String): T? =
            getKey(folder, path, T::class.java)

        inline fun <reified T : Any> getKey(folder: String, path: String, defVal: T?): T? =
            getKey(folder, path, T::class.java) ?: defVal

        fun <T> setKey(path: String, value: T) {
            DataStore.setKey(path, value)
        }

        fun <T> setKey(folder: String, path: String, value: T) {
            DataStore.setKey(folder, path, value)
        }

        fun getKeys(folder: String): List<String>? =
            DataStore.getKeys(folder)

        fun removeKeys(folder: String): Int? =
            DataStore.removeKeys(folder)

        fun removeKey(path: String) {
            DataStore.removeKey(path)
        }

        fun removeKey(folder: String, path: String) {
            DataStore.removeKey(folder, path)
        }

        @Suppress("UNUSED_PARAMETER")
        fun openBrowser(url: String, fallbackWebView: Boolean = false, fragment: Fragment? = null) {
            val ctx = CommonActivity.activity ?: context ?: return
            runCatching {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ctx.startActivity(intent)
            }.onFailure {
                Log.w("KestrelCS3", "openBrowser failed for $url: ${it.message}")
            }
        }

        fun openBrowser(url: String, activity: Activity?) {
            val ctx = activity ?: CommonActivity.activity ?: context ?: return
            runCatching {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ctx.startActivity(intent)
            }.onFailure {
                Log.w("KestrelCS3", "openBrowser failed for $url: ${it.message}")
            }
        }
    }
}

/**
 * Legacy alias expected by CloudStream .cs3 plugins that access `AcraApplication.Companion`.
 */
class AcraApplication {
    companion object {
        val context: Context?
            get() = CloudStreamApp.context

        fun <T : Any> getKeyClass(path: String, valueType: Class<T>): T? =
            CloudStreamApp.getKeyClass(path, valueType)

        fun <T : Any> setKeyClass(path: String, value: T) {
            CloudStreamApp.setKeyClass(path, value)
        }

        fun <T : Any> getKey(path: String, valueType: Class<T>): T? =
            CloudStreamApp.getKey(path, valueType)

        fun <T> getKey(folder: String, path: String, valueType: Class<T>): T? =
            CloudStreamApp.getKey(folder, path, valueType)

        inline fun <reified T : Any> getKey(path: String): T? =
            CloudStreamApp.getKey(path)

        inline fun <reified T : Any> getKey(path: String, defVal: T?): T? =
            CloudStreamApp.getKey(path, defVal)

        inline fun <reified T : Any> getKey(folder: String, path: String): T? =
            CloudStreamApp.getKey(folder, path)

        inline fun <reified T : Any> getKey(folder: String, path: String, defVal: T?): T? =
            CloudStreamApp.getKey(folder, path, defVal)

        fun <T> setKey(path: String, value: T) =
            CloudStreamApp.setKey(path, value)

        fun <T> setKey(folder: String, path: String, value: T) =
            CloudStreamApp.setKey(folder, path, value)

        fun getKeys(folder: String): List<String>? =
            CloudStreamApp.getKeys(folder)

        fun removeKeys(folder: String): Int? =
            CloudStreamApp.removeKeys(folder)

        fun removeKey(path: String) =
            CloudStreamApp.removeKey(path)

        fun removeKey(folder: String, path: String) =
            CloudStreamApp.removeKey(folder, path)
    }
}

object CommonActivity {
    @Volatile
    private var activityRef: WeakReference<Activity>? = null

    var activity: Activity?
        get() = activityRef?.get()
        private set(value) {
            activityRef = value?.let { WeakReference(it) }
        }

    fun setActivityInstance(instance: Activity?) {
        activity = instance
    }

    fun showToast(message: String?, duration: Int? = Toast.LENGTH_SHORT) {
        val ctx = activity ?: CloudStreamApp.context ?: return
        if (message.isNullOrBlank()) return
        val toastDuration = duration ?: Toast.LENGTH_SHORT
        Handler(Looper.getMainLooper()).post {
            runCatching { Toast.makeText(ctx, message, toastDuration).show() }
        }
    }

    fun showToast(@StringRes message: Int, duration: Int? = Toast.LENGTH_SHORT) {
        val ctx = activity ?: CloudStreamApp.context ?: return
        val toastDuration = duration ?: Toast.LENGTH_SHORT
        Handler(Looper.getMainLooper()).post {
            runCatching { Toast.makeText(ctx, message, toastDuration).show() }
        }
    }

    fun showToast(act: Activity?, message: String?, duration: Int? = Toast.LENGTH_SHORT) {
        val ctx = act ?: activity ?: CloudStreamApp.context ?: return
        if (message.isNullOrBlank()) return
        val toastDuration = duration ?: Toast.LENGTH_SHORT
        Handler(Looper.getMainLooper()).post {
            runCatching { Toast.makeText(ctx, message, toastDuration).show() }
        }
    }
}

/**
 * Compatibility shim for CloudStream's `:app` `MainActivity.Companion` events and fields.
 */
class MainActivity {
    companion object {
        const val TAG = "MAINACT"
        const val ANIMATED_OUTLINE: Boolean = false
        const val API_NAME_EXTRA_KEY = "API_NAME_EXTRA_KEY"

        var activityResultLauncher: Any? = null
        var lastError: String? = null
        var nextSearchQuery: String? = null

        val afterPluginsLoadedEvent = Event<Boolean>()
        val mainPluginsLoadedEvent = Event<Boolean>()
        val afterRepositoryLoadedEvent = Event<Boolean>()
        val reloadHomeEvent = Event<Boolean>()
        val reloadAccountEvent = Event<Boolean>()
        val bookmarksUpdatedEvent = Event<Boolean>()

        fun setLastError(context: Context) {
            runCatching {
                val errorFile = context.filesDir.resolve("last_error")
                if (errorFile.exists() && errorFile.isFile) {
                    lastError = errorFile.readText()
                    errorFile.delete()
                }
            }
        }

        fun deleteFileOnExit(file: File) {
            runCatching { file.deleteOnExit() }
        }
    }
}
