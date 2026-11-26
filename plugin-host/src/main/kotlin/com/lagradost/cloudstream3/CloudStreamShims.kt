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
import java.lang.ref.WeakReference

/**
 * Minimal host-level shims for CloudStream app helpers that compiled .cs3 plugins
 * may call at runtime. Missing UI interactions log safely rather than crashing.
 */
object CloudStreamApp {
    @Volatile
    private var appContext: Context? = null

    private val mapper: ObjectMapper by lazy {
        ObjectMapper()
            .registerModule(KotlinModule.Builder().build())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }

    fun init(context: Context) {
        appContext = context.applicationContext
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
