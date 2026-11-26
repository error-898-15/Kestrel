// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.domain.repository

import com.kestrel.domain.model.PluginMetadata
import com.kestrel.domain.provider.SourceProvider
import kotlinx.coroutines.flow.Flow

/**
 * Abstracts runtime loading/unloading of installed plugins behind :domain
 * so :data and :app never import third-party plugin APIs directly.
 */
interface PluginRuntimeLoader {
    val activeProviders: Flow<List<SourceProvider>>

    suspend fun loadPlugin(plugin: PluginMetadata): Result<List<SourceProvider>>
    suspend fun unloadPlugin(pluginId: String)
    fun getProvider(providerId: String): SourceProvider?
}
