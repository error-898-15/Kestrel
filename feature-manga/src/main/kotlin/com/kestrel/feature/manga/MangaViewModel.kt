// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.feature.manga

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kestrel.domain.model.MangaChapter
import com.kestrel.domain.model.MangaDetails
import com.kestrel.domain.model.MangaExtensionInstallState
import com.kestrel.domain.model.MangaExtensionMetadata
import com.kestrel.domain.model.MangaPage
import com.kestrel.domain.model.MangaReaderSettings
import com.kestrel.domain.model.MangaSummary
import com.kestrel.domain.repository.MangaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.net.URLDecoder
import javax.inject.Inject

data class MangaBrowseUiState(
    val installedExtensions: List<MangaExtensionMetadata> = emptyList(),
    val selectedSourceId: String? = null,
    val query: String = "",
    val items: List<MangaSummary> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null
)

@HiltViewModel
class MangaViewModel @Inject constructor(
    private val mangaRepository: MangaRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(MangaBrowseUiState())
    val uiState: StateFlow<MangaBrowseUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            mangaRepository.ensureDefaultMangaRepositoriesSeeded()
        }
        viewModelScope.launch {
            mangaRepository.extensions.collect { exts ->
                val installed = exts.filter { it.installState == MangaExtensionInstallState.INSTALLED }
                _uiState.update { it.copy(installedExtensions = installed) }
            }
        }
        loadCatalog()
    }

    fun onQueryChanged(q: String) {
        _uiState.update { it.copy(query = q) }
    }

    fun selectSource(sourceId: String?) {
        _uiState.update { it.copy(selectedSourceId = sourceId) }
        loadCatalog()
    }

    fun loadCatalog() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            val q = _uiState.value.query.trim()
            val src = _uiState.value.selectedSourceId
            val result = if (q.isBlank()) {
                mangaRepository.browsePopular(src, 1)
            } else {
                mangaRepository.searchManga(q, src, 1)
            }
            result.onSuccess { items ->
                _uiState.update { it.copy(items = items, isLoading = false) }
            }.onFailure { err ->
                _uiState.update { it.copy(isLoading = false, error = err.message) }
            }
        }
    }
}

data class MangaDetailsUiState(
    val details: MangaDetails? = null,
    val isLoading: Boolean = true,
    val error: String? = null
)

@HiltViewModel
class MangaDetailsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val mangaRepository: MangaRepository
) : ViewModel() {
    val extensionId: String = URLDecoder.decode(savedStateHandle.get<String>("extensionId").orEmpty(), "UTF-8")
    val sourceId: String = URLDecoder.decode(savedStateHandle.get<String>("sourceId").orEmpty(), "UTF-8")
    val mangaUrl: String = URLDecoder.decode(savedStateHandle.get<String>("mangaUrl").orEmpty(), "UTF-8")

    private val _uiState = MutableStateFlow(MangaDetailsUiState())
    val uiState: StateFlow<MangaDetailsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            mangaRepository.loadMangaDetails(extensionId, sourceId, mangaUrl)
                .onSuccess { d -> _uiState.update { it.copy(details = d, isLoading = false) } }
                .onFailure { e -> _uiState.update { it.copy(isLoading = false, error = e.message) } }
        }
    }

    fun toggleFavorite() {
        val summary = _uiState.value.details?.summary ?: return
        viewModelScope.launch {
            mangaRepository.toggleFavorite(summary)
            _uiState.update { s ->
                s.copy(details = s.details?.copy(isFavorite = !s.details.isFavorite))
            }
        }
    }
}

data class MangaReaderUiState(
    val mangaTitle: String = "",
    val chapters: List<MangaChapter> = emptyList(),
    val currentChapterIndex: Int = 0,
    val currentChapter: MangaChapter? = null,
    val pages: List<MangaPage> = emptyList(),
    val isLoadingPages: Boolean = true,
    val readerSettings: MangaReaderSettings = MangaReaderSettings()
)

@HiltViewModel
class MangaReaderViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val mangaRepository: MangaRepository
) : ViewModel() {
    private val extensionId: String = URLDecoder.decode(savedStateHandle.get<String>("extensionId").orEmpty(), "UTF-8")
    private val sourceId: String = URLDecoder.decode(savedStateHandle.get<String>("sourceId").orEmpty(), "UTF-8")
    private val mangaUrl: String = URLDecoder.decode(savedStateHandle.get<String>("mangaUrl").orEmpty(), "UTF-8")
    private val initialChapterUrl: String = URLDecoder.decode(savedStateHandle.get<String>("chapterUrl").orEmpty(), "UTF-8")

    private val _uiState = MutableStateFlow(MangaReaderUiState())
    val uiState: StateFlow<MangaReaderUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            mangaRepository.readerSettings.collect { rs ->
                _uiState.update { it.copy(readerSettings = rs) }
            }
        }
        viewModelScope.launch {
            mangaRepository.loadMangaDetails(extensionId, sourceId, mangaUrl)
                .onSuccess { details ->
                    val idx = details.chapters.indexOfFirst { it.url == initialChapterUrl }.coerceAtLeast(0)
                    _uiState.update {
                        it.copy(
                            mangaTitle = details.summary.title,
                            chapters = details.chapters
                        )
                    }
                    selectChapter(idx)
                }
        }
    }

    fun selectChapter(index: Int) {
        val chapters = _uiState.value.chapters
        val chapter = chapters.getOrNull(index) ?: return
        _uiState.update {
            it.copy(currentChapterIndex = index, currentChapter = chapter, isLoadingPages = true)
        }
        viewModelScope.launch {
            mangaRepository.loadChapterPages(extensionId, sourceId, chapter)
                .onSuccess { pages ->
                    _uiState.update { it.copy(pages = pages, isLoadingPages = false) }
                }
                .onFailure {
                    _uiState.update { it.copy(isLoadingPages = false) }
                }
        }
    }

    fun previousChapter() {
        val prev = _uiState.value.currentChapterIndex - 1
        if (prev >= 0) selectChapter(prev)
    }

    fun nextChapter() {
        val next = _uiState.value.currentChapterIndex + 1
        if (next < _uiState.value.chapters.size) selectChapter(next)
    }

    fun updateReaderSettings(transform: (MangaReaderSettings) -> MangaReaderSettings) {
        viewModelScope.launch {
            mangaRepository.updateReaderSettings(transform)
        }
    }
}
