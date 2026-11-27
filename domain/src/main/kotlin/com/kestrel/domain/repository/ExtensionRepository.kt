// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.domain.repository

import com.kestrel.domain.model.PluginMetadata
import com.kestrel.domain.model.ProviderSettingsUiTarget
import com.kestrel.domain.model.RepoConfig
import kotlinx.coroutines.flow.Flow

interface ExtensionRepository {
    val repositories: Flow<List<RepoConfig>>
    val plugins: Flow<List<PluginMetadata>>
    val configurablePluginIds: Flow<Set<String>>

    suspend fun ensureDefaultRepositoriesSeeded()
    suspend fun restoreDefaultRepositories(): Result<Int>
    suspend fun addRepository(rawInput: String): Result<RepoConfig>
    suspend fun refreshRepository(repoId: String): Result<RepoConfig>
    suspend fun removeRepository(repoId: String)

    suspend fun installPlugin(pluginId: String): Result<PluginMetadata>
    suspend fun installAllInRepo(repoId: String): Result<Int>
    suspend fun updatePlugin(pluginId: String): Result<PluginMetadata>
    suspend fun uninstallPlugin(pluginId: String)
    suspend fun setPluginEnabled(pluginId: String, enabled: Boolean): Result<Unit>
    suspend fun reconcileMissingFiles()

    suspend fun openOrLoadSettings(pluginId: String): Result<ProviderSettingsUiTarget>
    suspend fun saveNuvioSettings(
        pluginId: String,
        values: Map<String, String>,
        customHeaders: Map<String, String>
    ): Result<Unit>

    suspend fun saveStremioSettings(
        pluginId: String,
        manifestUrl: String,
        configValues: Map<String, String>,
        customHeaders: Map<String, String>
    ): Result<PluginMetadata>
}
