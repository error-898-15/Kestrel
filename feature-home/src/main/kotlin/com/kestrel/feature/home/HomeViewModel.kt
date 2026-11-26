// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kestrel.domain.model.CatalogRow
import com.kestrel.domain.repository.CatalogRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ProviderOption(
    val providerId: String,
    val name: String
)

data class HomeUiState(
    val isLoading: Boolean = false,
    val providers: List<ProviderOption> = emptyList(),
    val selectedProviderId: String? = null,
    val rows: List<CatalogRow> = emptyList(),
    val providerErrors: List<String> = emptyList()
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val catalogRepository: CatalogRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState(isLoading = true))
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private var allCachedRows: List<CatalogRow> = emptyList()
    private var allCachedErrors: Map<String, String> = emptyMap()
    private var lastLoadedProviderIds: Set<String> = emptySet()
    private var fetchJob: Job? = null

    init {
        viewModelScope.launch {
            catalogRepository.syncEnabledPlugins(force = false)
            catalogRepository.activeProviders
                .map { list ->
                    list.filter { it.hasCatalog }
                        .map { ProviderOption(providerId = it.providerId, name = it.name) }
                }
                .distinctUntilChanged()
                .collectLatest { options ->
                    val optionIds = options.map { it.providerId }.toSet()
                    val currentSelected = _uiState.value.selectedProviderId
                        ?.takeIf { it in optionIds }

                    _uiState.update {
                        it.copy(
                            providers = options,
                            selectedProviderId = currentSelected
                        )
                    }

                    if (optionIds.isEmpty()) {
                        allCachedRows = emptyList()
                        allCachedErrors = emptyMap()
                        lastLoadedProviderIds = emptySet()
                        _uiState.update { it.copy(isLoading = false, rows = emptyList(), providerErrors = emptyList()) }
                    } else if (optionIds != lastLoadedProviderIds) {
                        lastLoadedProviderIds = optionIds
                        refreshCatalogs()
                    }
                }
        }
    }

    fun selectProvider(providerId: String?) {
        val nextSelected = if (_uiState.value.selectedProviderId == providerId) null else providerId
        _uiState.update { state ->
            state.copy(
                selectedProviderId = nextSelected,
                rows = filterRows(allCachedRows, nextSelected),
                providerErrors = filterErrors(allCachedErrors, nextSelected)
            )
        }
        // Only hit network if we have never fetched rows for the selected provider yet.
        if (nextSelected != null && allCachedRows.none { it.providerId == nextSelected } && nextSelected !in allCachedErrors) {
            refreshCatalogs()
        }
    }

    fun refreshCatalogs() {
        fetchJob?.cancel()
        val selectedAtStart = _uiState.value.selectedProviderId
        fetchJob = viewModelScope.launch {
            if (selectedAtStart == null) {
                allCachedRows = emptyList()
                allCachedErrors = emptyMap()
            } else {
                allCachedRows = allCachedRows.filterNot { it.providerId == selectedAtStart }
                allCachedErrors = allCachedErrors - selectedAtStart
            }

            _uiState.update {
                it.copy(
                    isLoading = true,
                    rows = filterRows(allCachedRows, selectedAtStart),
                    providerErrors = filterErrors(allCachedErrors, selectedAtStart)
                )
            }

            catalogRepository.fetchHomeCatalogsStream(
                providerFilterId = selectedAtStart,
                page = 1
            ).collect { result ->
                allCachedRows = (allCachedRows.filterNot { it.providerId == result.providerId } + result.rows)
                allCachedErrors = if (result.errorMessage != null) {
                    allCachedErrors + (result.providerId to "${result.providerName}: ${result.errorMessage}")
                } else {
                    allCachedErrors - result.providerId
                }
                val currentFilter = _uiState.value.selectedProviderId
                _uiState.update {
                    it.copy(
                        rows = filterRows(allCachedRows, currentFilter),
                        providerErrors = filterErrors(allCachedErrors, currentFilter)
                    )
                }
            }

            val finalFilter = _uiState.value.selectedProviderId
            _uiState.update {
                it.copy(
                    isLoading = false,
                    rows = filterRows(allCachedRows, finalFilter),
                    providerErrors = filterErrors(allCachedErrors, finalFilter)
                )
            }
        }
    }

    private fun filterRows(rows: List<CatalogRow>, providerId: String?): List<CatalogRow> =
        if (providerId == null) rows else rows.filter { it.providerId == providerId }

    private fun filterErrors(errors: Map<String, String>, providerId: String?): List<String> =
        if (providerId == null) errors.values.toList() else listOfNotNull(errors[providerId])
}
