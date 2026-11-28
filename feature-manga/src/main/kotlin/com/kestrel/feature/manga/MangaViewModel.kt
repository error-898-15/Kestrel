// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.feature.manga

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kestrel.domain.model.MangaBrowseMode
import com.kestrel.domain.model.MangaChapter
import com.kestrel.domain.model.MangaDetails
import com.kestrel.domain.model.MangaExtensionInstallState
import com.kestrel.domain.model.MangaExtensionMetadata
import com.kestrel.domain.model.MangaPage
import com.kestrel.domain.model.MangaProgressInfo
import com.kestrel.domain.model.MangaReaderSettings
import com.kestrel.domain.model.MangaSectionRow
import com.kestrel.domain.model.MangaSummary
import com.kestrel.domain.repository.MangaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.net.URLDecoder
import javax.inject.Inject

data class MangaBrowseUiState(
    val installedExtensions: List<MangaExtensionMetadata> = emptyList(),
    val selectedSourceId: String? = null,
    val browseMode: MangaBrowseMode = MangaBrowseMode.POPULAR,
    val seeAllTitle: String? = null,
    val query: String = "",
    val items: List<MangaSummary> = emptyList(),
    val sectionRows: List<MangaSectionRow> = emptyList(),
    val libraryEntries: List<MangaProgressInfo> = emptyList(),
    val currentPage: Int = 1,
    val hasNextPage: Boolean = false,
    val isLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class MangaViewModel @Inject constructor(
    private val mangaRepository: MangaRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(MangaBrowseUiState())
    val uiState: StateFlow<MangaBrowseUiState> = _uiState.asStateFlow()

    private var catalogJob: Job? = null
    private var loadMoreJob: Job? = null

    init {
        viewModelScope.launch {
            mangaRepository.ensureDefaultMangaRepositoriesSeeded()
        }
        viewModelScope.launch {
            var previousIds: Set<String>? = null
            mangaRepository.extensions.collect { exts ->
                val installedManga = exts.filter { ext ->
                    ext.installState == MangaExtensionInstallState.INSTALLED &&
                        (ext.sources.isEmpty() || ext.sources.any { !it.isVideo })
                }
                val newIds = installedManga.map { it.id }.toSet()
                val shouldReload = previousIds != null && previousIds != newIds
                previousIds = newIds
                _uiState.update { it.copy(installedExtensions = installedManga) }
                if (shouldReload) {
                    loadCatalog()
                }
            }
        }
        viewModelScope.launch {
            mangaRepository.libraryEntries.collect { entries ->
                _uiState.update { it.copy(libraryEntries = entries) }
            }
        }
        loadCatalog()
    }

    fun onQueryChanged(q: String) {
        _uiState.update { it.copy(query = q) }
    }

    fun selectSource(sourceId: String?) {
        loadMoreJob?.cancel()
        catalogJob?.cancel()
        _uiState.update {
            it.copy(
                selectedSourceId = sourceId,
                seeAllTitle = null,
                items = emptyList(),
                sectionRows = emptyList(),
                currentPage = 1,
                hasNextPage = false,
                isLoading = true,
                isLoadingMore = false,
                error = null
            )
        }
        loadCatalog()
    }

    fun selectBrowseMode(mode: MangaBrowseMode) {
        if (_uiState.value.browseMode == mode) return
        loadMoreJob?.cancel()
        catalogJob?.cancel()
        _uiState.update {
            it.copy(
                browseMode = mode,
                items = emptyList(),
                currentPage = 1,
                hasNextPage = false,
                isLoading = true,
                isLoadingMore = false,
                error = null
            )
        }
        loadCatalog()
    }

    fun openSeeAll(row: MangaSectionRow) {
        loadMoreJob?.cancel()
        catalogJob?.cancel()
        _uiState.update {
            it.copy(
                selectedSourceId = row.sourceId,
                browseMode = row.browseMode,
                seeAllTitle = row.title,
                items = row.items,
                currentPage = 1,
                hasNextPage = row.hasNextPage,
                isLoading = false,
                isLoadingMore = false,
                error = null
            )
        }
    }

    fun closeSeeAll() {
        selectSource(null)
    }

    fun loadCatalog() {
        loadMoreJob?.cancel()
        catalogJob?.cancel()
        val snapshotSource = _uiState.value.selectedSourceId
        val snapshotMode = _uiState.value.browseMode
        val snapshotQuery = _uiState.value.query.trim()
        val snapshotSeeAll = _uiState.value.seeAllTitle

        catalogJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    items = emptyList(),
                    sectionRows = emptyList(),
                    currentPage = 1,
                    hasNextPage = false,
                    isLoading = true,
                    isLoadingMore = false,
                    error = null
                )
            }

            if (snapshotSource == null && snapshotQuery.isBlank() && snapshotSeeAll == null) {
                val rowsResult = mangaRepository.loadAllCatalogRows()
                if (_uiState.value.selectedSourceId != null ||
                    _uiState.value.query.trim().isNotEmpty() ||
                    _uiState.value.seeAllTitle != null
                ) return@launch

                rowsResult.onSuccess { rows ->
                    val merged = rows.flatMap { it.items }.distinctBy { it.id }
                    _uiState.update {
                        it.copy(
                            sectionRows = rows,
                            items = merged,
                            currentPage = 1,
                            hasNextPage = rows.any { r -> r.hasNextPage },
                            isLoading = false,
                            error = null
                        )
                    }
                }.onFailure { err ->
                    _uiState.update {
                        it.copy(
                            sectionRows = emptyList(),
                            items = emptyList(),
                            isLoading = false,
                            error = err.message ?: "Failed to load manga catalogs"
                        )
                    }
                }
            } else {
                val result = when {
                    snapshotQuery.isNotBlank() -> mangaRepository.searchManga(snapshotQuery, snapshotSource, 1)
                    snapshotMode == MangaBrowseMode.LATEST -> mangaRepository.browseLatest(snapshotSource, 1)
                    else -> mangaRepository.browsePopular(snapshotSource, 1)
                }
                if (_uiState.value.selectedSourceId != snapshotSource ||
                    _uiState.value.browseMode != snapshotMode ||
                    _uiState.value.query.trim() != snapshotQuery
                ) return@launch

                result.onSuccess { page ->
                    _uiState.update {
                        it.copy(
                            items = page.items.distinctBy { m -> m.id },
                            currentPage = 1,
                            hasNextPage = page.hasNextPage,
                            isLoading = false,
                            error = null
                        )
                    }
                }.onFailure { err ->
                    _uiState.update {
                        it.copy(
                            items = emptyList(),
                            currentPage = 1,
                            hasNextPage = false,
                            isLoading = false,
                            error = err.message ?: "Failed to load catalog"
                        )
                    }
                }
            }
        }
    }

    fun loadNextPage() {
        val st = _uiState.value
        if (st.isLoading || st.isLoadingMore || !st.hasNextPage) return
        val nextPage = st.currentPage + 1
        val snapshotSource = st.selectedSourceId
        val snapshotMode = st.browseMode
        val snapshotQuery = st.query.trim()

        loadMoreJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoadingMore = true) }
            val result = when {
                snapshotQuery.isNotBlank() -> mangaRepository.searchManga(snapshotQuery, snapshotSource, nextPage)
                snapshotMode == MangaBrowseMode.LATEST -> mangaRepository.browseLatest(snapshotSource, nextPage)
                else -> mangaRepository.browsePopular(snapshotSource, nextPage)
            }
            if (_uiState.value.selectedSourceId != snapshotSource ||
                _uiState.value.browseMode != snapshotMode ||
                _uiState.value.query.trim() != snapshotQuery
            ) return@launch

            result.onSuccess { page ->
                _uiState.update { current ->
                    val existingIds = current.items.asSequence().map { it.id }.toHashSet()
                    val newUnique = page.items.filter { it.id !in existingIds }
                    current.copy(
                        items = current.items + newUnique,
                        currentPage = nextPage,
                        hasNextPage = page.hasNextPage && newUnique.isNotEmpty(),
                        isLoadingMore = false
                    )
                }
            }.onFailure {
                _uiState.update { it.copy(isLoadingMore = false, hasNextPage = false) }
            }
        }
    }
}

data class MangaDetailsUiState(
    val details: MangaDetails? = null,
    val progress: MangaProgressInfo? = null,
    val downloadedChapterIds: Set<String> = emptySet(),
    val downloadingChapterIds: Set<String> = emptySet(),
    val recommendations: List<MangaSummary> = emptyList(),
    val wrongTitleResults: List<MangaSummary> = emptyList(),
    val isSearchingWrongTitle: Boolean = false,
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

    private val mangaId: String = "$sourceId:$mangaUrl"
    private val _uiState = MutableStateFlow(MangaDetailsUiState())
    val uiState: StateFlow<MangaDetailsUiState> = _uiState.asStateFlow()

    init {
        load()
        viewModelScope.launch {
            mangaRepository.libraryEntries.collect { entries ->
                val match = entries.firstOrNull { it.mangaId == mangaId }
                _uiState.update { state ->
                    val updatedDetails = state.details?.let { d ->
                        val readIds = match?.readChapterIds.orEmpty()
                        d.copy(
                            isFavorite = match?.isFavorite ?: d.isFavorite,
                            chapters = d.chapters.map { ch -> ch.copy(isRead = ch.id in readIds) }
                        )
                    }
                    state.copy(progress = match, details = updatedDetails)
                }
            }
        }
    }

    fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            val downloaded = mangaRepository.getDownloadedChapterIds(mangaId)
            mangaRepository.loadMangaDetails(extensionId, sourceId, mangaUrl)
                .onSuccess { d ->
                    _uiState.update {
                        it.copy(
                            details = d,
                            downloadedChapterIds = downloaded,
                            isLoading = false,
                            error = null
                        )
                    }
                    loadRecommendations()
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(
                            downloadedChapterIds = downloaded,
                            isLoading = false,
                            error = e.message ?: "Failed to load manga details"
                        )
                    }
                }
        }
    }

    private fun loadRecommendations() {
        viewModelScope.launch {
            mangaRepository.browsePopular(sourceId, 1).onSuccess { page ->
                val filtered = page.items.filter { it.url != mangaUrl }.take(12)
                _uiState.update { it.copy(recommendations = filtered) }
            }
        }
    }

    fun toggleFavorite() {
        val summary = _uiState.value.details?.summary ?: return
        viewModelScope.launch {
            mangaRepository.toggleFavorite(summary)
        }
    }

    fun downloadChapter(chapter: MangaChapter) {
        if (chapter.id in _uiState.value.downloadingChapterIds) return
        viewModelScope.launch {
            _uiState.update { it.copy(downloadingChapterIds = it.downloadingChapterIds + chapter.id) }
            mangaRepository.downloadChapter(extensionId, sourceId, mangaId, chapter)
                .onSuccess {
                    val refreshed = mangaRepository.getDownloadedChapterIds(mangaId)
                    _uiState.update {
                        it.copy(
                            downloadedChapterIds = refreshed,
                            downloadingChapterIds = it.downloadingChapterIds - chapter.id
                        )
                    }
                }
                .onFailure {
                    _uiState.update { it.copy(downloadingChapterIds = it.downloadingChapterIds - chapter.id) }
                }
        }
    }

    fun downloadAllChapters() {
        val chapters = _uiState.value.details?.chapters.orEmpty()
        val downloaded = _uiState.value.downloadedChapterIds
        chapters.filter { it.id !in downloaded }.forEach { ch ->
            downloadChapter(ch)
        }
    }

    fun deleteDownloadedChapter(chapterId: String) {
        viewModelScope.launch {
            mangaRepository.deleteDownloadedChapter(mangaId, chapterId)
            val refreshed = mangaRepository.getDownloadedChapterIds(mangaId)
            _uiState.update { it.copy(downloadedChapterIds = refreshed) }
        }
    }

    fun searchAlternateTitle(query: String) {
        val q = query.trim()
        if (q.isBlank()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isSearchingWrongTitle = true) }
            mangaRepository.searchManga(q, null, 1)
                .onSuccess { page ->
                    _uiState.update {
                        it.copy(wrongTitleResults = page.items, isSearchingWrongTitle = false)
                    }
                }
                .onFailure {
                    _uiState.update { it.copy(isSearchingWrongTitle = false) }
                }
        }
    }
}

data class MangaReaderUiState(
    val mangaSummary: MangaSummary? = null,
    val mangaTitle: String = "",
    val chapters: List<MangaChapter> = emptyList(),
    val currentChapterIndex: Int = 0,
    val currentChapter: MangaChapter? = null,
    val pages: List<MangaPage> = emptyList(),
    val isLoadingPages: Boolean = true,
    val pagesError: String? = null,
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
                            mangaSummary = details.summary,
                            mangaTitle = details.summary.title,
                            chapters = details.chapters
                        )
                    }
                    selectChapter(idx)
                }
                .onFailure { err ->
                    _uiState.update {
                        it.copy(isLoadingPages = false, pagesError = err.message ?: "Failed to load chapter")
                    }
                }
        }
    }

    fun selectChapter(index: Int) {
        val chapters = _uiState.value.chapters
        val chapter = chapters.getOrNull(index) ?: return
        _uiState.update {
            it.copy(
                currentChapterIndex = index,
                currentChapter = chapter,
                isLoadingPages = true,
                pagesError = null,
                pages = emptyList()
            )
        }
        viewModelScope.launch {
            mangaRepository.loadChapterPages(extensionId, sourceId, chapter)
                .onSuccess { pages ->
                    _uiState.update { it.copy(pages = pages, isLoadingPages = false, pagesError = null) }
                    val summary = _uiState.value.mangaSummary
                    if (summary != null) {
                        mangaRepository.saveChapterProgress(summary, chapter, 0, pages.size)
                    }
                }
                .onFailure { err ->
                    _uiState.update {
                        it.copy(
                            isLoadingPages = false,
                            pagesError = err.message ?: "Failed to load chapter pages"
                        )
                    }
                }
        }
    }

    fun reloadCurrentChapter() {
        selectChapter(_uiState.value.currentChapterIndex)
    }

    fun onPageChanged(pageIndex: Int) {
        val st = _uiState.value
        val summary = st.mangaSummary ?: return
        val chapter = st.currentChapter ?: return
        val total = st.pages.size
        if (total <= 0) return
        viewModelScope.launch {
            mangaRepository.saveChapterProgress(summary, chapter, pageIndex, total)
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
