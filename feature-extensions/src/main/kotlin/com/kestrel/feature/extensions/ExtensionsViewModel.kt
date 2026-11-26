// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.feature.extensions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kestrel.domain.model.PluginMetadata
import com.kestrel.domain.model.RepoConfig
import com.kestrel.domain.repository.ExtensionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ExtensionsUiState(
    val repositories: List<RepoConfig> = emptyList(),
    val plugins: List<PluginMetadata> = emptyList(),
    val availableLanguages: List<String> = emptyList(),
    val availableTvTypes: List<String> = emptyList(),
    val selectedLanguage: String? = null,
    val selectedTvType: String? = null,
    val searchQuery: String = "",
    val repoInput: String = "",
    val isAddingRepo: Boolean = false,
    val refreshingRepoIds: Set<String> = emptySet(),
    val installingRepoIds: Set<String> = emptySet(),
    val busyPluginIds: Set<String> = emptySet(),
    val bannerMessage: String? = null,
    val bannerIsError: Boolean = false
)

@HiltViewModel
class ExtensionsViewModel @Inject constructor(
    private val repository: ExtensionRepository
) : ViewModel() {

    private data class QueryFilterState(
        val selectedLanguage: String? = null,
        val selectedTvType: String? = null,
        val searchQuery: String = ""
    )

    private data class ActionState(
        val repoInput: String = "",
        val isAddingRepo: Boolean = false,
        val refreshingRepoIds: Set<String> = emptySet(),
        val installingRepoIds: Set<String> = emptySet(),
        val busyPluginIds: Set<String> = emptySet(),
        val bannerMessage: String? = null,
        val bannerIsError: Boolean = false
    )

    private data class FilteredPluginSlice(
        val repositories: List<RepoConfig> = emptyList(),
        val plugins: List<PluginMetadata> = emptyList(),
        val availableLanguages: List<String> = emptyList(),
        val availableTvTypes: List<String> = emptyList(),
        val queryState: QueryFilterState = QueryFilterState()
    )

    private val queryFilterState = MutableStateFlow(QueryFilterState())
    private val actionState = MutableStateFlow(ActionState())

    init {
        viewModelScope.launch {
            repository.reconcileMissingFiles()
        }
    }

    private val filteredSlice = combine(
        repository.repositories,
        repository.plugins,
        queryFilterState
    ) { repos, allPlugins, query ->
        val langs = allPlugins.map { it.language }.filter { it.isNotBlank() }.distinct().sorted()
        val types = allPlugins.flatMap { it.tvTypes }.filter { it.isNotBlank() }.distinct().sorted()

        val filtered = allPlugins.filter { plugin ->
            val matchesLang = query.selectedLanguage == null ||
                plugin.language.equals(query.selectedLanguage, ignoreCase = true)
            val matchesType = query.selectedTvType == null ||
                plugin.tvTypes.any { it.equals(query.selectedTvType, ignoreCase = true) }
            val matchesQuery = query.searchQuery.isBlank() ||
                plugin.displayName.contains(query.searchQuery, ignoreCase = true) ||
                plugin.internalName.contains(query.searchQuery, ignoreCase = true) ||
                plugin.description.contains(query.searchQuery, ignoreCase = true)
            matchesLang && matchesType && matchesQuery
        }

        FilteredPluginSlice(
            repositories = repos,
            plugins = filtered,
            availableLanguages = langs,
            availableTvTypes = types,
            queryState = query
        )
    }.flowOn(Dispatchers.Default)

    val uiState: StateFlow<ExtensionsUiState> = combine(
        filteredSlice,
        actionState
    ) { slice, actions ->
        ExtensionsUiState(
            repositories = slice.repositories,
            plugins = slice.plugins,
            availableLanguages = slice.availableLanguages,
            availableTvTypes = slice.availableTvTypes,
            selectedLanguage = slice.queryState.selectedLanguage,
            selectedTvType = slice.queryState.selectedTvType,
            searchQuery = slice.queryState.searchQuery,
            repoInput = actions.repoInput,
            isAddingRepo = actions.isAddingRepo,
            refreshingRepoIds = actions.refreshingRepoIds,
            installingRepoIds = actions.installingRepoIds,
            busyPluginIds = actions.busyPluginIds,
            bannerMessage = actions.bannerMessage,
            bannerIsError = actions.bannerIsError
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ExtensionsUiState()
    )

    fun onRepoInputChanged(value: String) {
        actionState.update { it.copy(repoInput = value) }
    }

    fun onSearchQueryChanged(value: String) {
        queryFilterState.update { it.copy(searchQuery = value) }
    }

    fun selectLanguage(lang: String?) {
        queryFilterState.update {
            it.copy(selectedLanguage = if (it.selectedLanguage == lang) null else lang)
        }
    }

    fun selectTvType(tvType: String?) {
        queryFilterState.update {
            it.copy(selectedTvType = if (it.selectedTvType == tvType) null else tvType)
        }
    }

    fun dismissBanner() {
        actionState.update { it.copy(bannerMessage = null) }
    }

    fun addRepository() {
        val raw = actionState.value.repoInput.trim()
        if (raw.isEmpty()) return
        viewModelScope.launch {
            actionState.update { it.copy(isAddingRepo = true, bannerMessage = null) }
            repository.addRepository(raw)
                .onSuccess { repo ->
                    actionState.update {
                        it.copy(
                            isAddingRepo = false,
                            repoInput = "",
                            bannerMessage = "Added ${repo.name} (${repo.type.name})",
                            bannerIsError = false
                        )
                    }
                }
                .onFailure { err ->
                    actionState.update {
                        it.copy(
                            isAddingRepo = false,
                            bannerMessage = err.message ?: "Failed to add repository",
                            bannerIsError = true
                        )
                    }
                }
        }
    }

    fun validateAndRefreshRepo(repoId: String) {
        viewModelScope.launch {
            actionState.update { it.copy(refreshingRepoIds = it.refreshingRepoIds + repoId) }
            repository.refreshRepository(repoId)
                .onSuccess { repo ->
                    actionState.update {
                        it.copy(
                            refreshingRepoIds = it.refreshingRepoIds - repoId,
                            bannerMessage = "${repo.name} is valid and up to date",
                            bannerIsError = false
                        )
                    }
                }
                .onFailure { err ->
                    actionState.update {
                        it.copy(
                            refreshingRepoIds = it.refreshingRepoIds - repoId,
                            bannerMessage = err.message ?: "Repository validation failed",
                            bannerIsError = true
                        )
                    }
                }
        }
    }

    fun installAllInRepo(repoId: String) {
        viewModelScope.launch {
            actionState.update { it.copy(installingRepoIds = it.installingRepoIds + repoId) }
            repository.installAllInRepo(repoId)
                .onSuccess { count ->
                    actionState.update {
                        it.copy(
                            installingRepoIds = it.installingRepoIds - repoId,
                            bannerMessage = if (count > 0) {
                                "Installed/updated $count plugin(s)"
                            } else {
                                "No compatible pending plugins to install"
                            },
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
            repository.removeRepository(repoId)
            actionState.update {
                it.copy(bannerMessage = "Repository removed", bannerIsError = false)
            }
        }
    }

    fun installOrUpdatePlugin(plugin: PluginMetadata) {
        viewModelScope.launch {
            actionState.update { it.copy(busyPluginIds = it.busyPluginIds + plugin.id) }
            val result = if (plugin.installState.name == "UPDATE_AVAILABLE") {
                repository.updatePlugin(plugin.id)
            } else {
                repository.installPlugin(plugin.id)
            }
            result
                .onSuccess {
                    actionState.update {
                        it.copy(
                            busyPluginIds = it.busyPluginIds - plugin.id,
                            bannerMessage = "Installed ${plugin.displayName}",
                            bannerIsError = false
                        )
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
            repository.uninstallPlugin(pluginId)
        }
    }

    fun togglePluginEnabled(pluginId: String, enabled: Boolean) {
        viewModelScope.launch {
            repository.setPluginEnabled(pluginId, enabled)
                .onFailure { err ->
                    actionState.update {
                        it.copy(
                            bannerMessage = err.message ?: "Could not toggle plugin",
                            bannerIsError = true
                        )
                    }
                }
        }
    }
}
