// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kestrel.domain.model.LibraryEntry
import com.kestrel.domain.repository.LibraryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class LibraryTab(val label: String) {
    CONTINUE_WATCHING("Continue"),
    FAVORITES("Favorites"),
    HISTORY("History")
}

data class LibraryUiState(
    val selectedTab: LibraryTab = LibraryTab.CONTINUE_WATCHING,
    val continueWatching: List<LibraryEntry> = emptyList(),
    val favorites: List<LibraryEntry> = emptyList(),
    val history: List<LibraryEntry> = emptyList()
)

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository
) : ViewModel() {

    private val selectedTab = MutableStateFlow(LibraryTab.CONTINUE_WATCHING)

    val uiState: StateFlow<LibraryUiState> = combine(
        selectedTab,
        libraryRepository.continueWatching,
        libraryRepository.favorites,
        libraryRepository.history
    ) { tab, cw, favs, hist ->
        LibraryUiState(
            selectedTab = tab,
            continueWatching = cw,
            favorites = favs,
            history = hist
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = LibraryUiState()
    )

    fun selectTab(tab: LibraryTab) {
        selectedTab.value = tab
    }

    fun removeFromHistory(mediaId: String) {
        viewModelScope.launch {
            libraryRepository.removeFromHistory(mediaId)
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            libraryRepository.clearHistory()
        }
    }
}
