// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kestrel.domain.model.CatalogRow
import com.kestrel.domain.repository.CatalogRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
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

    init {
        viewModelScope.launch {
            catalogRepository.syncEnabledPlugins()
            catalogRepository.activeProviders.collectLatest { providers ->
                val options = providers.filter { it.hasCatalog }.map {
                    ProviderOption(providerId = it.providerId, name = it.name)
                }
                _uiState.update { it.copy(providers = options) }
                refreshCatalogs()
            }
        }
    }

    fun selectProvider(providerId: String?) {
        _uiState.update {
            it.copy(selectedProviderId = if (it.selectedProviderId == providerId) null else providerId)
        }
        refreshCatalogs()
    }

    fun refreshCatalogs() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, providerErrors = emptyList()) }
            val results = catalogRepository.fetchHomeCatalogs(
                providerFilterId = _uiState.value.selectedProviderId,
                page = 1
            )
            val rows = results.flatMap { it.rows }
            val errors = results.mapNotNull { res ->
                res.errorMessage?.let { "${res.providerName}: $it" }
            }
            _uiState.update {
                it.copy(
                    isLoading = false,
                    rows = rows,
                    providerErrors = errors
                )
            }
        }
    }
}
