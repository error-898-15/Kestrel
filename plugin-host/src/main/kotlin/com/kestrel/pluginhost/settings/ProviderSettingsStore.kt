// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.pluginhost.settings

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists per-provider settings values and custom HTTP headers for Nuvio and Stremio plugins.
 */
@Singleton
class ProviderSettingsStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val json: Json
) {
    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getValues(pluginId: String): Map<String, String> =
        readMap(KEY_VALUES_PREFIX + pluginId)

    fun saveValues(pluginId: String, values: Map<String, String>) {
        writeMap(KEY_VALUES_PREFIX + pluginId, values)
    }

    fun getHeaders(pluginId: String): Map<String, String> =
        readMap(KEY_HEADERS_PREFIX + pluginId)

    fun saveHeaders(pluginId: String, headers: Map<String, String>) {
        val cleaned = headers.entries
            .mapNotNull { (k, v) ->
                val key = k.trim()
                val value = v.trim()
                if (key.isNotEmpty() && value.isNotEmpty()) key to value else null
            }
            .toMap()
        writeMap(KEY_HEADERS_PREFIX + pluginId, cleaned)
    }

    fun clear(pluginId: String) {
        prefs.edit()
            .remove(KEY_VALUES_PREFIX + pluginId)
            .remove(KEY_HEADERS_PREFIX + pluginId)
            .apply()
    }

    private fun readMap(key: String): Map<String, String> {
        val raw = prefs.getString(key, null)?.takeIf { it.isNotBlank() } ?: return emptyMap()
        return runCatching {
            val obj = json.parseToJsonElement(raw).jsonObject
            obj.entries.mapNotNull { (k, v) ->
                val value = (v as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                k to value
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    private fun writeMap(key: String, values: Map<String, String>) {
        if (values.isEmpty()) {
            prefs.edit().remove(key).apply()
            return
        }
        val obj = JsonObject(values.mapValues { JsonPrimitive(it.value) })
        prefs.edit().putString(key, json.encodeToString(JsonObject.serializer(), obj)).apply()
    }

    companion object {
        private const val PREFS_NAME = "kestrel_provider_settings"
        private const val KEY_VALUES_PREFIX = "values:"
        private const val KEY_HEADERS_PREFIX = "headers:"
    }
}
