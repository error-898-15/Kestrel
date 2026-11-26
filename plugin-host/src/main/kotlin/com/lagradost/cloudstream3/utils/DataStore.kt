// SPDX-License-Identifier: GPL-3.0-or-later
package com.lagradost.cloudstream3.utils

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.lagradost.cloudstream3.CloudStreamApp
import kotlin.reflect.KClass
import kotlin.reflect.KProperty

const val DOWNLOAD_HEADER_CACHE = "download_header_cache"
const val DOWNLOAD_HEADER_CACHE_BACKUP = "BACKUP_download_header_cache"
const val DOWNLOAD_EPISODE_CACHE = "download_episode_cache"
const val DOWNLOAD_EPISODE_CACHE_BACKUP = "BACKUP_download_episode_cache"
const val VIDEO_PLAYER_BRIGHTNESS = "video_player_alpha_key"
const val USER_SELECTED_HOMEPAGE_API = "home_api_used"
const val USER_PROVIDER_API = "user_custom_sites"
const val PREFERENCES_NAME = "rebuild_preference"

class PreferenceDelegate<T : Any>(
    val key: String,
    val default: T
) {
    private val klass: KClass<out T> = default::class
    private var cache: T? = null

    operator fun getValue(self: Any?, property: KProperty<*>): T =
        cache ?: CloudStreamApp.getKeyClass(key, klass.java)?.also { cache = it } ?: default

    operator fun setValue(self: Any?, property: KProperty<*>, t: T?) {
        cache = t
        if (t == null) {
            CloudStreamApp.removeKey(key)
        } else {
            CloudStreamApp.setKeyClass(key, t)
        }
    }
}

data class Editor(val editor: SharedPreferences.Editor) {
    fun <T> setKeyRaw(path: String, value: T) {
        @Suppress("UNCHECKED_CAST")
        if (value is Set<*> && value.all { it is String }) {
            editor.putStringSet(path, value as Set<String>)
        } else {
            when (value) {
                is Boolean -> editor.putBoolean(path, value)
                is Int -> editor.putInt(path, value)
                is String -> editor.putString(path, value)
                is Float -> editor.putFloat(path, value)
                is Long -> editor.putLong(path, value)
            }
        }
    }

    fun apply() {
        editor.apply()
    }
}

object DataStore {
    val mapper: ObjectMapper by lazy {
        ObjectMapper()
            .registerModule(KotlinModule.Builder().build())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }

    private fun getPreferences(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private fun prefs(): SharedPreferences? =
        CloudStreamApp.context?.let { getPreferences(it) }

    fun Context.getSharedPrefs(): SharedPreferences = getPreferences(this)

    fun Context.getDefaultSharedPrefs(): SharedPreferences =
        getSharedPreferences("${packageName}_preferences", Context.MODE_PRIVATE)

    fun getFolderName(folder: String, path: String): String = "$folder/$path"

    fun editor(context: Context, isEditingAppSettings: Boolean = false): Editor {
        val sp = if (isEditingAppSettings) context.getDefaultSharedPrefs() else context.getSharedPrefs()
        return Editor(sp.edit())
    }

    fun Context.getKeys(folder: String): List<String> {
        val fixedFolder = folder.trimEnd('/') + "/"
        return getSharedPrefs().all.keys.filter { it.startsWith(fixedFolder) }
    }

    fun getKeys(folder: String): List<String>? =
        CloudStreamApp.context?.getKeys(folder)

    fun Context.containsKey(folder: String, path: String): Boolean =
        containsKey(getFolderName(folder, path))

    fun Context.containsKey(path: String): Boolean =
        getSharedPrefs().contains(path)

    fun Context.removeKey(folder: String, path: String) {
        removeKey(getFolderName(folder, path))
    }

    fun Context.removeKey(path: String) {
        runCatching {
            val sp = getSharedPrefs()
            if (sp.contains(path)) {
                sp.edit().remove(path).apply()
            }
        }
    }

    fun Context.removeKeys(folder: String): Int {
        val keys = getKeys(folder)
        return try {
            val edit = getSharedPrefs().edit()
            keys.forEach { edit.remove(it) }
            edit.apply()
            keys.size
        } catch (t: Throwable) {
            Log.w("KestrelCS3", "removeKeys($folder) failed: ${t.message}")
            0
        }
    }

    fun removeKeys(folder: String): Int? =
        CloudStreamApp.context?.removeKeys(folder)

    fun <T> Context.setKey(path: String, value: T) {
        try {
            val encoded = mapper.writeValueAsString(value)
            getSharedPrefs().edit().putString(path, encoded).apply()
        } catch (t: Throwable) {
            Log.w("KestrelCS3", "setKey($path) failed: ${t.message}")
        }
    }

    fun <T> Context.setKey(folder: String, path: String, value: T) {
        setKey(getFolderName(folder, path), value)
    }

    fun <T> Context.getKey(path: String, valueType: Class<T>): T? {
        return try {
            val raw = getSharedPrefs().getString(path, null) ?: return null
            mapper.readValue(raw, valueType)
        } catch (t: Throwable) {
            Log.w("KestrelCS3", "getKey($path) failed: ${t.message}")
            null
        }
    }

    inline fun <reified T : Any> Context.getKey(path: String, defVal: T?): T? =
        getKey(path, T::class.java) ?: defVal

    inline fun <reified T : Any> Context.getKey(path: String): T? =
        getKey(path, T::class.java)

    inline fun <reified T : Any> Context.getKey(folder: String, path: String): T? =
        getKey(getFolderName(folder, path), T::class.java)

    inline fun <reified T : Any> Context.getKey(folder: String, path: String, defVal: T?): T? =
        getKey(getFolderName(folder, path), T::class.java) ?: defVal

    fun <T> getKey(path: String, valueType: Class<T>): T? {
        return try {
            val raw = prefs()?.getString(path, null) ?: return null
            mapper.readValue(raw, valueType)
        } catch (t: Throwable) {
            Log.w("KestrelCS3", "getKey($path) failed: ${t.message}")
            null
        }
    }

    fun <T> getKey(folder: String, path: String, valueType: Class<T>): T? =
        getKey(getFolderName(folder, path), valueType)

    inline fun <reified T : Any> getKey(path: String): T? =
        getKey(path, T::class.java)

    inline fun <reified T : Any> getKey(folder: String, path: String): T? =
        getKey(folder, path, T::class.java)

    fun <T> setKey(path: String, value: T) {
        try {
            val encoded = mapper.writeValueAsString(value)
            prefs()?.edit()?.putString(path, encoded)?.apply()
        } catch (t: Throwable) {
            Log.w("KestrelCS3", "setKey($path) failed: ${t.message}")
        }
    }

    fun <T> setKey(folder: String, path: String, value: T) {
        setKey(getFolderName(folder, path), value)
    }

    fun removeKey(path: String) {
        prefs()?.edit()?.remove(path)?.apply()
    }

    fun removeKey(folder: String, path: String) {
        removeKey(getFolderName(folder, path))
    }
}
