// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.feature.details

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kestrel.domain.model.Episode
import com.kestrel.domain.model.LibraryEntry
import com.kestrel.domain.model.MediaDetails
import com.kestrel.domain.model.ResolvedLinks
import com.kestrel.domain.model.StreamSource
import com.kestrel.domain.playback.PlaybackSession
import com.kestrel.domain.playback.PlaybackSessionManager
import com.kestrel.domain.repository.CatalogRepository
import com.kestrel.domain.repository.LibraryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.net.URLDecoder
import javax.inject.Inject

data class DetailsUiState(
    val isLoading: Boolean = true,
    val details: MediaDetails? = null,
    val errorMessage: String? = null,
    val selectedSeason: Int? = null,
    val libraryEntry: LibraryEntry? = null,
    val isResolvingLinks: Boolean = false,
    val resolvingEpisode: Episode? = null,
    val resolvedLinks: ResolvedLinks? = null,
    val linkError: String? = null
)

@HiltViewModel
class DetailsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val catalogRepository: CatalogRepository,
    private val libraryRepository: LibraryRepository,
    private val playbackSessionManager: PlaybackSessionManager
) : ViewModel() {

    private val providerId: String = decodeArg(savedStateHandle["providerId"])
    private val mediaUrl: String = decodeArg(savedStateHandle["url"])

    private val _uiState = MutableStateFlow(DetailsUiState())
    val uiState: StateFlow<DetailsUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    fun load() {
        if (providerId.isBlank() || mediaUrl.isBlank()) {
            _uiState.update { it.copy(isLoading = false, errorMessage = "Missing media reference") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            catalogRepository.loadDetails(providerId, mediaUrl)
                .onSuccess { details ->
                    val firstSeason = details.episodes.mapNotNull { it.season }.minOrNull()
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            details = details,
                            selectedSeason = firstSeason
                        )
                    }
                    observeLibrary(details.id)
                }
                .onFailure { err ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = err.message ?: "Failed to load details"
                        )
                    }
                }
        }
    }

    private fun observeLibrary(mediaId: String) {
        viewModelScope.launch {
            libraryRepository.observeEntry(mediaId).collectLatest { entry ->
                _uiState.update { it.copy(libraryEntry = entry) }
            }
        }
    }

    fun selectSeason(season: Int) {
        _uiState.update { it.copy(selectedSeason = season) }
    }

    fun toggleFavorite() {
        val details = _uiState.value.details ?: return
        viewModelScope.launch {
            libraryRepository.toggleFavorite(details)
        }
    }

    fun resolveForPlay(episode: Episode? = null) {
        val details = _uiState.value.details ?: return
        val dataUrl = episode?.dataUrl
            ?: details.playDataUrl
            ?: details.episodes.firstOrNull()?.dataUrl
            ?: details.url

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isResolvingLinks = true,
                    resolvingEpisode = episode ?: details.episodes.firstOrNull(),
                    resolvedLinks = null,
                    linkError = null
                )
            }
            catalogRepository.resolveLinks(details.providerId, dataUrl)
                .onSuccess { links ->
                    _uiState.update {
                        it.copy(
                            isResolvingLinks = false,
                            resolvedLinks = links,
                            linkError = if (links.streams.isEmpty()) "No playable streams found" else null
                        )
                    }
                }
                .onFailure { err ->
                    _uiState.update {
                        it.copy(
                            isResolvingLinks = false,
                            linkError = err.message ?: "Failed to resolve streams"
                        )
                    }
                }
        }
    }

    fun dismissSourcePicker() {
        _uiState.update { it.copy(resolvedLinks = null, linkError = null) }
    }

    fun startPlayback(source: StreamSource) {
        val state = _uiState.value
        val details = state.details ?: return
        val links = state.resolvedLinks ?: return
        val episode = state.resolvingEpisode
        val resumeMs = if (episode == null || episode.id == state.libraryEntry?.lastEpisodeId) {
            state.libraryEntry?.lastPositionMs ?: 0L
        } else {
            0L
        }

        playbackSessionManager.startSession(
            PlaybackSession(
                details = details,
                selectedEpisode = episode,
                selectedSource = source,
                links = links,
                resumePositionMs = resumeMs
            )
        )
        dismissSourcePicker()
    }

    private fun decodeArg(raw: String?): String =
        if (raw.isNullOrBlank()) "" else runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
}
