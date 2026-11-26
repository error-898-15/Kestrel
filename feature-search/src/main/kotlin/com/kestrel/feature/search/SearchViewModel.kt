// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kestrel.domain.repository.CatalogRepository
import com.kestrel.domain.repository.ProviderSearchResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SearchUiState(
    val query: String = "",
    val isSearching: Boolean = false,
    val activeProviderCount: Int = 0,
    val groups: List<ProviderSearchResult> = emptyList(),
    val hasSearched: Boolean = false
)

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val catalogRepository: CatalogRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            catalogRepository.syncEnabledPlugins()
            catalogRepository.activeProviders.collectLatest { providers ->
                _uiState.update { it.copy(activeProviderCount = providers.size) }
            }
        }
    }

    fun onQueryChanged(query: String) {
        _uiState.update { it.copy(query = query) }
    }

    fun submitSearch() {
        val q = _uiState.value.query.trim()
        if (q.isBlank()) return
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _uiState.update { it.copy(isSearching = true, hasSearched = true) }
            val results = catalogRepository.searchAll(query = q, page = 1)
            _uiState.update {
                it.copy(
                    isSearching = false,
                    groups = results
                )
            }
        }
    }
}
