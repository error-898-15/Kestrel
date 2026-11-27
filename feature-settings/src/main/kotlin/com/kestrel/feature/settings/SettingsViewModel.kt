// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kestrel.domain.model.PlaybackStartMode
import com.kestrel.domain.model.PluginInstallState
import com.kestrel.domain.model.PluginMetadata
import com.kestrel.domain.model.ProviderSettingsUiTarget
import com.kestrel.domain.model.RepoConfig
import com.kestrel.domain.model.RepoType
import com.kestrel.domain.model.ServerSearchSettings
import com.kestrel.domain.repository.CatalogRepository
import com.kestrel.domain.repository.ExtensionRepository
import com.kestrel.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
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

enum class PluginFilterMode {
    ALL,
    INSTALLED,
    UPDATES
}

data class SettingsActionState(
    val settingsSearchQuery: String = "",
    val addonInput: String = "",
    val isAddingAddon: Boolean = false,
    val pluginRepoInput: String = "",
    val isAddingPluginRepo: Boolean = false,
    val pluginSearchQuery: String = "",
    val selectedPluginRepoId: String? = null,
    val pluginFilterMode: PluginFilterMode = PluginFilterMode.ALL,
    val refreshingRepoIds: Set<String> = emptySet(),
    val installingRepoIds: Set<String> = emptySet(),
    val busyPluginIds: Set<String> = emptySet(),
    val loadingSettingsPluginIds: Set<String> = emptySet(),
    val activeSettingsTarget: ProviderSettingsUiTarget? = null,
    val isSavingSettings: Boolean = false,
    val settingsError: String? = null,
    val bannerMessage: String? = null,
    val bannerIsError: Boolean = false
)

data class SettingsUiState(
    val settings: ServerSearchSettings = ServerSearchSettings(),
    val defaultPlaybackSpeed: Float = 1.0f,
    val nuvioExtensions: List<InstalledServerExtension> = emptyList(),
    val stremioExtensions: List<InstalledServerExtension> = emptyList(),
    val cloudStreamExtensions: List<InstalledServerExtension> = emptyList(),
    val totalActiveExtensions: Int = 0,
    val stremioRepos: List<RepoConfig> = emptyList(),
    val stremioAddons: List<PluginMetadata> = emptyList(),
    val pluginRepos: List<RepoConfig> = emptyList(),
    val allNonStremioPlugins: List<PluginMetadata> = emptyList(),
    val filteredNonStremioPlugins: List<PluginMetadata> = emptyList(),
    val repoNameById: Map<String, String> = emptyMap(),
    val configurablePluginIds: Set<String> = emptySet(),
    val actions: SettingsActionState = SettingsActionState()
) {
    val bannerMessage: String? get() = actions.bannerMessage
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val extensionRepository: ExtensionRepository,
    private val catalogRepository: CatalogRepository
) : ViewModel() {

    private val actionState = MutableStateFlow(SettingsActionState())
    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            extensionRepository.reconcileMissingFiles()
            catalogRepository.syncEnabledPlugins(force = false)
        }
        viewModelScope.launch {
            val serverSliceFlow = combine(
                settingsRepository.serverSearchSettings,
                settingsRepository.defaultPlaybackSpeed,
                catalogRepository.activeProviders
            ) { serverSettings, playbackSpeed, activeProviders ->
                Triple(serverSettings, playbackSpeed, activeProviders)
            }

            val repoSliceFlow = combine(
                extensionRepository.repositories,
                extensionRepository.plugins,
                extensionRepository.configurablePluginIds
            ) { repos, plugins, configurableIds ->
                Triple(repos, plugins, configurableIds)
            }

            combine(
                serverSliceFlow,
                repoSliceFlow,
                actionState
            ) { (serverSettings, playbackSpeed, activeProviders), (repos, allPlugins, configurableIds), actions ->
                val byPluginId = LinkedHashMap<String, InstalledServerExtension>()

                activeProviders.forEach { provider ->
                    byPluginId[provider.pluginId] = InstalledServerExtension(
                        id = provider.pluginId,
                        name = provider.name,
                        repoType = provider.repoType,
                        language = provider.language
                    )
                }

                allPlugins
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

                val sortedActive = byPluginId.values.sortedBy { it.name.lowercase(Locale.US) }

                val stremioRepos = repos.filter { it.type == RepoType.STREMIO }
                val stremioAddons = allPlugins
                    .filter { it.repoType == RepoType.STREMIO }
                    .sortedBy { it.displayName.lowercase(Locale.US) }

                val pluginRepos = repos.filter { it.type != RepoType.STREMIO }
                val repoNameById = repos.associate { it.id to it.name }

                val allNonStremioPlugins = allPlugins
                    .filter { it.repoType != RepoType.STREMIO }
                    .sortedWith(
                        compareBy<PluginMetadata>(
                            { it.installState != PluginInstallState.INSTALLED && it.installState != PluginInstallState.UPDATE_AVAILABLE },
                            { it.displayName.lowercase(Locale.US) }
                        )
                    )

                val query = actions.pluginSearchQuery.trim().lowercase(Locale.US)
                val filteredPlugins = allNonStremioPlugins.filter { plugin ->
                    val matchesRepo = actions.selectedPluginRepoId == null ||
                        plugin.repoId == actions.selectedPluginRepoId
                    val matchesMode = when (actions.pluginFilterMode) {
                        PluginFilterMode.ALL -> true
                        PluginFilterMode.INSTALLED -> plugin.installState == PluginInstallState.INSTALLED ||
                            plugin.installState == PluginInstallState.UPDATE_AVAILABLE
                        PluginFilterMode.UPDATES -> plugin.installState == PluginInstallState.UPDATE_AVAILABLE
                    }
                    val repoName = repoNameById[plugin.repoId].orEmpty()
                    val matchesQuery = query.isEmpty() ||
                        plugin.displayName.lowercase(Locale.US).contains(query) ||
                        plugin.internalName.lowercase(Locale.US).contains(query) ||
                        plugin.description.lowercase(Locale.US).contains(query) ||
                        repoName.lowercase(Locale.US).contains(query) ||
                        plugin.tvTypes.any { it.lowercase(Locale.US).contains(query) }
                    matchesRepo && matchesMode && matchesQuery
                }

                SettingsUiState(
                    settings = serverSettings,
                    defaultPlaybackSpeed = playbackSpeed,
                    nuvioExtensions = sortedActive.filter { it.repoType == RepoType.NUVIO },
                    stremioExtensions = sortedActive.filter { it.repoType == RepoType.STREMIO },
                    cloudStreamExtensions = sortedActive.filter { it.repoType == RepoType.CLOUDSTREAM },
                    totalActiveExtensions = sortedActive.size,
                    stremioRepos = stremioRepos,
                    stremioAddons = stremioAddons,
                    pluginRepos = pluginRepos,
                    allNonStremioPlugins = allNonStremioPlugins,
                    filteredNonStremioPlugins = filteredPlugins,
                    repoNameById = repoNameById,
                    configurablePluginIds = configurableIds,
                    actions = actions
                )
            }
                .flowOn(Dispatchers.Default)
                .collect { state ->
                    _uiState.value = state
                }
        }
    }

    // --- Hub Search ---

    fun onSettingsSearchChanged(query: String) {
        actionState.update { it.copy(settingsSearchQuery = query) }
    }

    // --- Stremio Addons ---

    fun onAddonInputChanged(value: String) {
        actionState.update { it.copy(addonInput = value) }
    }

    fun installAddonUrl() {
        val raw = actionState.value.addonInput.trim()
        if (raw.isEmpty()) return
        viewModelScope.launch {
            actionState.update { it.copy(isAddingAddon = true, bannerMessage = null, bannerIsError = false) }
            extensionRepository.addRepository(raw)
                .onSuccess { repo ->
                    if (repo.type == RepoType.STREMIO) {
                        extensionRepository.installAllInRepo(repo.id)
                    }
                    actionState.update {
                        it.copy(
                            isAddingAddon = false,
                            addonInput = "",
                            bannerMessage = if (repo.type == RepoType.STREMIO) {
                                "Installed addon ${repo.name}"
                            } else {
                                "Added ${repo.name} (${repo.type.name})"
                            },
                            bannerIsError = false
                        )
                    }
                }
                .onFailure { err ->
                    actionState.update {
                        it.copy(
                            isAddingAddon = false,
                            bannerMessage = err.message ?: "Failed to install addon",
                            bannerIsError = true
                        )
                    }
                }
        }
    }

    // --- Plugin Repositories & Plugins ---

    fun onPluginRepoInputChanged(value: String) {
        actionState.update { it.copy(pluginRepoInput = value) }
    }

    fun onPluginSearchChanged(value: String) {
        actionState.update { it.copy(pluginSearchQuery = value) }
    }

    fun selectPluginRepoFilter(repoId: String?) {
        actionState.update {
            it.copy(selectedPluginRepoId = if (it.selectedPluginRepoId == repoId) null else repoId)
        }
    }

    fun setPluginFilterMode(mode: PluginFilterMode) {
        actionState.update { it.copy(pluginFilterMode = mode) }
    }

    fun addPluginRepository() {
        val raw = actionState.value.pluginRepoInput.trim()
        if (raw.isEmpty()) return
        viewModelScope.launch {
            actionState.update { it.copy(isAddingPluginRepo = true, bannerMessage = null, bannerIsError = false) }
            extensionRepository.addRepository(raw)
                .onSuccess { repo ->
                    if (repo.type == RepoType.STREMIO) {
                        extensionRepository.installAllInRepo(repo.id)
                    }
                    actionState.update {
                        it.copy(
                            isAddingPluginRepo = false,
                            pluginRepoInput = "",
                            bannerMessage = "Added ${repo.name} (${repo.type.name})",
                            bannerIsError = false
                        )
                    }
                }
                .onFailure { err ->
                    actionState.update {
                        it.copy(
                            isAddingPluginRepo = false,
                            bannerMessage = err.message ?: "Failed to add repository",
                            bannerIsError = true
                        )
                    }
                }
        }
    }

    fun refreshRepository(repoId: String) {
        viewModelScope.launch {
            actionState.update { it.copy(refreshingRepoIds = it.refreshingRepoIds + repoId) }
            extensionRepository.refreshRepository(repoId)
                .onSuccess { repo ->
                    actionState.update {
                        it.copy(
                            refreshingRepoIds = it.refreshingRepoIds - repoId,
                            bannerMessage = "${repo.name} is up to date",
                            bannerIsError = false
                        )
                    }
                }
                .onFailure { err ->
                    actionState.update {
                        it.copy(
                            refreshingRepoIds = it.refreshingRepoIds - repoId,
                            bannerMessage = err.message ?: "Refresh failed",
                            bannerIsError = true
                        )
                    }
                }
        }
    }

    fun installAllInRepo(repoId: String) {
        viewModelScope.launch {
            actionState.update { it.copy(installingRepoIds = it.installingRepoIds + repoId) }
            extensionRepository.installAllInRepo(repoId)
                .onSuccess { count ->
                    actionState.update {
                        it.copy(
                            installingRepoIds = it.installingRepoIds - repoId,
                            bannerMessage = if (count > 0) "Installed/updated $count plugin(s)" else "All compatible plugins already up to date",
                            bannerIsError = false
                        )
                    }
                }
                .onFailure { err ->
                    actionState.update {
                        it.copy(
                            installingRepoIds = it.installingRepoIds - repoId,
                            bannerMessage = err.message ?: "Batch install failed",
                            bannerIsError = true
                        )
                    }
                }
        }
    }

    fun removeRepository(repoId: String) {
        viewModelScope.launch {
            extensionRepository.removeRepository(repoId)
            actionState.update {
                it.copy(
                    selectedPluginRepoId = if (it.selectedPluginRepoId == repoId) null else it.selectedPluginRepoId,
                    bannerMessage = "Removed successfully",
                    bannerIsError = false
                )
            }
        }
    }

    fun installOrUpdatePlugin(plugin: PluginMetadata) {
        viewModelScope.launch {
            actionState.update { it.copy(busyPluginIds = it.busyPluginIds + plugin.id) }
            val result = if (plugin.installState == PluginInstallState.UPDATE_AVAILABLE) {
                extensionRepository.updatePlugin(plugin.id)
            } else {
                extensionRepository.installPlugin(plugin.id)
            }
            result
                .onSuccess { installed ->
                    actionState.update {
                        if (!installed.lastError.isNullOrBlank()) {
                            it.copy(
                                busyPluginIds = it.busyPluginIds - plugin.id,
                                bannerMessage = "${plugin.displayName}: ${installed.lastError}",
                                bannerIsError = true
                            )
                        } else {
                            it.copy(
                                busyPluginIds = it.busyPluginIds - plugin.id,
                                bannerMessage = "Installed ${plugin.displayName}",
                                bannerIsError = false
                            )
                        }
                    }
                }
                .onFailure { err ->
                    actionState.update {
                        it.copy(
                            busyPluginIds = it.busyPluginIds - plugin.id,
                            bannerMessage = "${plugin.displayName}: ${err.message}",
                            bannerIsError = true
                        )
                    }
                }
        }
    }

    fun uninstallPlugin(pluginId: String) {
        viewModelScope.launch {
            extensionRepository.uninstallPlugin(pluginId)
            actionState.update {
                it.copy(bannerMessage = "Plugin uninstalled", bannerIsError = false)
            }
        }
    }

    fun togglePluginEnabled(pluginId: String, enabled: Boolean) {
        viewModelScope.launch {
            val target = (_uiState.value.stremioAddons + _uiState.value.allNonStremioPlugins)
                .firstOrNull { it.id == pluginId }
            if (enabled && target != null &&
                (target.installState == PluginInstallState.NOT_INSTALLED ||
                    target.installState == PluginInstallState.ERROR)
            ) {
                installOrUpdatePlugin(target)
                return@launch
            }
            extensionRepository.setPluginEnabled(pluginId, enabled)
                .onFailure { err ->
                    actionState.update {
                        it.copy(
                            bannerMessage = err.message ?: "Could not toggle extension",
                            bannerIsError = true
                        )
                    }
                }
        }
    }

    fun openPluginSettings(plugin: PluginMetadata) {
        viewModelScope.launch {
            actionState.update {
                it.copy(
                    loadingSettingsPluginIds = it.loadingSettingsPluginIds + plugin.id,
                    settingsError = null
                )
            }
            extensionRepository.openOrLoadSettings(plugin.id)
                .onSuccess { target ->
                    actionState.update {
                        when (target) {
                            is ProviderSettingsUiTarget.NativeCloudStreamOpened -> it.copy(
                                loadingSettingsPluginIds = it.loadingSettingsPluginIds - plugin.id
                            )
                            is ProviderSettingsUiTarget.NuvioConfig,
                            is ProviderSettingsUiTarget.StremioConfig -> it.copy(
                                loadingSettingsPluginIds = it.loadingSettingsPluginIds - plugin.id,
                                activeSettingsTarget = target,
                                settingsError = null
                            )
                        }
                    }
                }
                .onFailure { err ->
                    actionState.update {
                        it.copy(
                            loadingSettingsPluginIds = it.loadingSettingsPluginIds - plugin.id,
                            bannerMessage = err.message ?: "Could not open settings for ${plugin.displayName}",
                            bannerIsError = true
                        )
                    }
                }
        }
    }

    fun dismissSettingsDialog() {
        actionState.update {
            it.copy(
                activeSettingsTarget = null,
                isSavingSettings = false,
                settingsError = null
            )
        }
    }

    fun saveNuvioSettings(
        pluginId: String,
        values: Map<String, String>,
        customHeaders: Map<String, String>
    ) {
        viewModelScope.launch {
            actionState.update { it.copy(isSavingSettings = true, settingsError = null) }
            extensionRepository.saveNuvioSettings(pluginId, values, customHeaders)
                .onSuccess {
                    actionState.update {
                        it.copy(
                            isSavingSettings = false,
                            activeSettingsTarget = null,
                            bannerMessage = "Provider settings saved",
                            bannerIsError = false
                        )
                    }
                }
                .onFailure { err ->
                    actionState.update {
                        it.copy(
                            isSavingSettings = false,
                            settingsError = err.message ?: "Failed to save settings"
                        )
                    }
                }
        }
    }

    fun saveStremioSettings(
        pluginId: String,
        manifestUrl: String,
        configValues: Map<String, String>,
        customHeaders: Map<String, String>
    ) {
        viewModelScope.launch {
            actionState.update { it.copy(isSavingSettings = true, settingsError = null) }
            extensionRepository.saveStremioSettings(pluginId, manifestUrl, configValues, customHeaders)
                .onSuccess { updated ->
                    actionState.update {
                        it.copy(
                            isSavingSettings = false,
                            activeSettingsTarget = null,
                            bannerMessage = "Saved settings for ${updated.displayName}",
                            bannerIsError = false
                        )
                    }
                }
                .onFailure { err ->
                    actionState.update {
                        it.copy(
                            isSavingSettings = false,
                            settingsError = err.message ?: "Failed to apply Stremio settings"
                        )
                    }
                }
        }
    }

    // --- Server Search & Playback ---

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
            actionState.update {
                it.copy(
                    bannerMessage = "First-launch disclaimer will show again on next restart.",
                    bannerIsError = false
                )
            }
        }
    }

    fun dismissBanner() {
        actionState.update { it.copy(bannerMessage = null, bannerIsError = false) }
    }

    private fun updateSettings(transform: (ServerSearchSettings) -> ServerSearchSettings) {
        viewModelScope.launch {
            settingsRepository.updateServerSearchSettings(transform)
        }
    }
}
