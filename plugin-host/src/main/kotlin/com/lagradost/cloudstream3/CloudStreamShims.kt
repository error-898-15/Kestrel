// SPDX-License-Identifier: GPL-3.0-or-later
package com.lagradost.cloudstream3

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import okhttp3.Cache
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.TimeUnit

/**
 * Minimal host-level shims for CloudStream app helpers that compiled .cs3 plugins
 * may call at runtime. Missing UI interactions log safely rather than crashing.
 */
object CloudStreamApp {
    @Volatile
    private var appContext: Context? = null

    private const val BROWSER_UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

    private val mapper: ObjectMapper by lazy {
        ObjectMapper()
            .registerModule(KotlinModule.Builder().build())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }

    fun init(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
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
                setHeaders?.invoke(requestsObj, mapOf("User-Agent" to BROWSER_UA))
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
                        .connectTimeout(20, TimeUnit.SECONDS)
                        .readTimeout(25, TimeUnit.SECONDS)
                        .followRedirects(true)
                        .followSslRedirects(true)
                        .cache(Cache(cacheDir, 25L * 1024L * 1024L))
                        .build()
                    setBaseClient.invoke(requestsObj, updated)
                }
            }
        }.onFailure {
            Log.w("KestrelCS3", "CloudStream NiceHttp tuning skipped: ${it.message}")
        }
    }

    val context: Context?
        get() = appContext

    private fun prefs(): SharedPreferences? =
        appContext?.getSharedPreferences("kestrel_cs3_datastore", Context.MODE_PRIVATE)

    fun <T : Any> getKey(path: String, valueType: Class<T>): T? {
        return try {
            val raw = prefs()?.getString(path, null) ?: return null
            mapper.readValue(raw, valueType)
        } catch (t: Throwable) {
            Log.w("KestrelCS3", "getKey($path) failed: ${t.message}")
            null
        }
    }

    fun <T> getKey(folder: String, path: String, valueType: Class<T>): T? {
        return try {
            val raw = prefs()?.getString("$folder/$path", null) ?: return null
            mapper.readValue(raw, valueType)
        } catch (t: Throwable) {
            Log.w("KestrelCS3", "getKey($folder/$path) failed: ${t.message}")
            null
        }
    }

    fun <T> setKey(path: String, value: T) {
        try {
            val encoded = mapper.writeValueAsString(value)
            prefs()?.edit()?.putString(path, encoded)?.apply()
        } catch (t: Throwable) {
            Log.w("KestrelCS3", "setKey($path) failed: ${t.message}")
        }
    }

    fun <T> setKey(folder: String, path: String, value: T) {
        setKey("$folder/$path", value)
    }

    fun removeKey(path: String) {
        prefs()?.edit()?.remove(path)?.apply()
    }

    fun removeKey(folder: String, path: String) {
        removeKey("$folder/$path")
    }
}

/**
 * Legacy alias expected by older CloudStream .cs3 plugins that reference AcraApplication directly.
 */
object AcraApplication {
    val context: Context?
        get() = CloudStreamApp.context

    fun <T : Any> getKey(path: String, valueType: Class<T>): T? =
        CloudStreamApp.getKey(path, valueType)

    fun <T> getKey(folder: String, path: String, valueType: Class<T>): T? =
        CloudStreamApp.getKey(folder, path, valueType)

    fun <T> setKey(path: String, value: T) =
        CloudStreamApp.setKey(path, value)

    fun <T> setKey(folder: String, path: String, value: T) =
        CloudStreamApp.setKey(folder, path, value)

    fun removeKey(path: String) =
        CloudStreamApp.removeKey(path)

    fun removeKey(folder: String, path: String) =
        CloudStreamApp.removeKey(folder, path)
}

object CommonActivity {
    @Volatile
    private var activityRef: WeakReference<Activity>? = null

    val activity: Activity?
        get() = activityRef?.get()

    fun setActivityInstance(instance: Activity?) {
        activityRef = instance?.let { WeakReference(it) }
    }

    fun showToast(message: String?, duration: Int = Toast.LENGTH_SHORT) {
        val ctx = activity ?: CloudStreamApp.context ?: return
        if (message.isNullOrBlank()) return
        Handler(Looper.getMainLooper()).post {
            runCatching { Toast.makeText(ctx, message, duration).show() }
        }
    }
}
