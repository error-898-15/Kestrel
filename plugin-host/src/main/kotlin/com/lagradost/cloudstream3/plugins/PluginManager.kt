// SPDX-License-Identifier: GPL-3.0-or-later
package com.lagradost.cloudstream3.plugins

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.APIHolder
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PluginData(
    @JsonProperty("internalName") @SerialName("internalName") val internalName: String = "",
    @JsonProperty("url") @SerialName("url") val url: String? = null,
    @JsonProperty("isOnline") @SerialName("isOnline") val isOnline: Boolean = true,
    @JsonProperty("filePath") @SerialName("filePath") val filePath: String = "",
    @JsonProperty("version") @SerialName("version") val version: Int = 1
)

object PluginManager {
    val plugins: MutableMap<String, BasePlugin> = LinkedHashMap()

    @Volatile
    var onlinePluginsList: List<PluginData> = emptyList()

    fun getPluginsOnline(): Array<PluginData> = onlinePluginsList.toTypedArray()

    fun getPluginsLocal(): Array<PluginData> = emptyArray()

    fun unloadPlugin(absolutePath: String) {
        val plugin = plugins.remove(absolutePath) ?: return
        runCatching { plugin.beforeUnload() }
        APIHolder.allProviders.removeAll { it.sourcePlugin == plugin.filename }
    }
}
