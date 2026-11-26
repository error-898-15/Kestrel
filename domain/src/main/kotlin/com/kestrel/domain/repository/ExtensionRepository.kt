// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.domain.repository

import com.kestrel.domain.model.PluginMetadata
import com.kestrel.domain.model.RepoConfig
import kotlinx.coroutines.flow.Flow

interface ExtensionRepository {
    val repositories: Flow<List<RepoConfig>>
    val plugins: Flow<List<PluginMetadata>>

    suspend fun addRepository(rawInput: String): Result<RepoConfig>
    suspend fun refreshRepository(repoId: String): Result<RepoConfig>
    suspend fun removeRepository(repoId: String)

    suspend fun installPlugin(pluginId: String): Result<PluginMetadata>
    suspend fun installAllInRepo(repoId: String): Result<Int>
    suspend fun updatePlugin(pluginId: String): Result<PluginMetadata>
    suspend fun uninstallPlugin(pluginId: String)
    suspend fun setPluginEnabled(pluginId: String, enabled: Boolean): Result<Unit>
    suspend fun reconcileMissingFiles()
}
