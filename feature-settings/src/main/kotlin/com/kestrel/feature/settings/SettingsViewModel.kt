// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kestrel.domain.model.PlaybackStartMode
import com.kestrel.domain.model.RepoType
import com.kestrel.domain.model.ServerSearchSettings
import com.kestrel.domain.repository.CatalogRepository
import com.kestrel.domain.repository.ExtensionRepository
import com.kestrel.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

data class InstalledServerExtension(
    val id: String,
    val name: String,
    val repoType: RepoType,
    val language: String = ""
)

data class SettingsUiState(
    val settings: ServerSearchSettings = ServerSearchSettings(),
    val defaultPlaybackSpeed: Float = 1.0f,
    val nuvioExtensions: List<InstalledServerExtension> = emptyList(),
    val stremioExtensions: List<InstalledServerExtension> = emptyList(),
    val cloudStreamExtensions: List<InstalledServerExtension> = emptyList(),
    val totalActiveExtensions: Int = 0,
    val bannerMessage: String? = null
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val extensionRepository: ExtensionRepository,
    private val catalogRepository: CatalogRepository
) : ViewModel() {

    private val _bannerMessage = MutableStateFlow<String?>(null)
    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            catalogRepository.syncEnabledPlugins(force = false)
        }
        viewModelScope.launch {
            combine(
                settingsRepository.serverSearchSettings,
                settingsRepository.defaultPlaybackSpeed,
                extensionRepository.plugins,
                catalogRepository.activeProviders,
                _bannerMessage
            ) { serverSettings, playbackSpeed, plugins, activeProviders, banner ->
                val byPluginId = LinkedHashMap<String, InstalledServerExtension>()

                activeProviders.forEach { provider ->
                    byPluginId[provider.pluginId] = InstalledServerExtension(
                        id = provider.pluginId,
                        name = provider.name,
                        repoType = provider.repoType,
                        language = provider.language
                    )
                }

                plugins
                    .filter { it.localFilePath != null && it.isEnabled }
                    .forEach { plugin ->
                        if (plugin.id !in byPluginId) {
                            byPluginId[plugin.id] = InstalledServerExtension(
                                id = plugin.id,
                                name = plugin.displayName.ifBlank { plugin.internalName },
                                repoType = plugin.repoType,
                                language = plugin.language
                            )
                        }
                    }

                val sorted = byPluginId.values.sortedBy { it.name.lowercase(Locale.US) }
                SettingsUiState(
                    settings = serverSettings,
                    defaultPlaybackSpeed = playbackSpeed,
                    nuvioExtensions = sorted.filter { it.repoType == RepoType.NUVIO },
                    stremioExtensions = sorted.filter { it.repoType == RepoType.STREMIO },
                    cloudStreamExtensions = sorted.filter { it.repoType == RepoType.CLOUDSTREAM },
                    totalActiveExtensions = sorted.size,
                    bannerMessage = banner
                )
            }.collect { state ->
                _uiState.value = state
            }
        }
    }

    fun setSearchAllInstalled(enabled: Boolean) {
        updateSettings { it.copy(searchAllInstalled = enabled) }
    }

    fun setSearchEvery(repoType: RepoType, enabled: Boolean) {
        updateSettings { s ->
            when (repoType) {
                RepoType.NUVIO -> s.copy(searchEveryNuvio = enabled)
                RepoType.STREMIO -> s.copy(searchEveryStremio = enabled)
                RepoType.CLOUDSTREAM -> s.copy(searchEveryCloudStream = enabled)
            }
        }
    }

    fun toggleExtensionSelected(repoType: RepoType, extensionId: String) {
        updateSettings { s ->
            val current = when (repoType) {
                RepoType.NUVIO -> s.selectedNuvioIds
                RepoType.STREMIO -> s.selectedStremioIds
                RepoType.CLOUDSTREAM -> s.selectedCloudStreamIds
            }
            val updated = if (extensionId in current) current - extensionId else current + extensionId
            when (repoType) {
                RepoType.NUVIO -> s.copy(selectedNuvioIds = updated)
                RepoType.STREMIO -> s.copy(selectedStremioIds = updated)
                RepoType.CLOUDSTREAM -> s.copy(selectedCloudStreamIds = updated)
            }
        }
    }

    fun selectAllForFamily(repoType: RepoType) {
        val ids = when (repoType) {
            RepoType.NUVIO -> _uiState.value.nuvioExtensions.map { it.id }.toSet()
            RepoType.STREMIO -> _uiState.value.stremioExtensions.map { it.id }.toSet()
            RepoType.CLOUDSTREAM -> _uiState.value.cloudStreamExtensions.map { it.id }.toSet()
        }
        updateSettings { s ->
            when (repoType) {
                RepoType.NUVIO -> s.copy(selectedNuvioIds = ids)
                RepoType.STREMIO -> s.copy(selectedStremioIds = ids)
                RepoType.CLOUDSTREAM -> s.copy(selectedCloudStreamIds = ids)
            }
        }
    }

    fun clearAllForFamily(repoType: RepoType) {
        updateSettings { s ->
            when (repoType) {
                RepoType.NUVIO -> s.copy(selectedNuvioIds = emptySet())
                RepoType.STREMIO -> s.copy(selectedStremioIds = emptySet())
                RepoType.CLOUDSTREAM -> s.copy(selectedCloudStreamIds = emptySet())
            }
        }
    }

    fun setSameSearchTimeForAll(sameTime: Boolean) {
        updateSettings { it.copy(sameSearchTimeForAll = sameTime) }
    }

    fun setGlobalSearchTimeoutSec(seconds: Int) {
        val clamped = seconds.coerceIn(5, 60)
        updateSettings { it.copy(globalSearchTimeoutSec = clamped) }
    }

    fun setExtensionTimeoutSec(extensionId: String, seconds: Int) {
        val clamped = seconds.coerceIn(5, 60)
        updateSettings { s ->
            val next = s.perExtensionTimeoutsSec.toMutableMap()
            next[extensionId] = clamped
            s.copy(perExtensionTimeoutsSec = next)
        }
    }

    fun setPlaybackStartMode(mode: PlaybackStartMode) {
        updateSettings { it.copy(playbackStartMode = mode) }
    }

    fun setAlwaysShowServerList(alwaysShow: Boolean) {
        updateSettings { it.copy(alwaysShowServerList = alwaysShow) }
    }

    fun setAskWhereToPlay(ask: Boolean) {
        updateSettings { it.copy(askWhereToPlay = ask) }
    }

    fun setDefaultPlaybackSpeed(speed: Float) {
        viewModelScope.launch {
            settingsRepository.setDefaultPlaybackSpeed(speed)
        }
    }

    fun resetDisclaimer() {
        viewModelScope.launch {
            settingsRepository.resetDisclaimer()
            _bannerMessage.value = "First-launch disclaimer will show again on next restart."
        }
    }

    fun dismissBanner() {
        _bannerMessage.value = null
    }

    private fun updateSettings(transform: (ServerSearchSettings) -> ServerSearchSettings) {
        viewModelScope.launch {
            settingsRepository.updateServerSearchSettings(transform)
        }
    }
}
