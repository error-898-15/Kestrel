// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kestrel.domain.model.Episode
import com.kestrel.domain.model.LibraryEntry
import com.kestrel.domain.model.StreamSource
import com.kestrel.domain.playback.PlaybackSession
import com.kestrel.domain.playback.PlaybackSessionManager
import com.kestrel.domain.repository.CatalogRepository
import com.kestrel.domain.repository.LibraryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import javax.inject.Inject

data class TrackOption(
    val id: String,
    val label: String,
    val groupIndex: Int,
    val trackIndex: Int,
    val isSelected: Boolean
)

data class PlayerUiState(
    val session: PlaybackSession? = null,
    val playbackSpeed: Float = 1.0f,
    val isResolvingNextEpisode: Boolean = false,
    val errorMessage: String? = null
)

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val playbackSessionManager: PlaybackSessionManager,
    private val catalogRepository: CatalogRepository,
    private val libraryRepository: LibraryRepository,
    val okHttpClient: OkHttpClient
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlayerUiState(session = playbackSessionManager.session.value))
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            playbackSessionManager.session.collect { s ->
                _uiState.update { it.copy(session = s) }
            }
        }
    }

    fun selectStreamSource(source: StreamSource, currentPositionMs: Long) {
        playbackSessionManager.updateSession { current ->
            current.copy(
                selectedSource = source,
                resumePositionMs = currentPositionMs
            )
        }
    }

    fun setPlaybackSpeed(speed: Float) {
        _uiState.update { it.copy(playbackSpeed = speed) }
    }

    fun saveProgress(positionMs: Long, durationMs: Long) {
        val session = _uiState.value.session ?: return
        if (positionMs <= 0L) return
        val details = session.details
        val ep = session.selectedEpisode
        val epLabel = ep?.let {
            if (it.season != null && it.episodeNumber != null) {
                "S${it.season}E${it.episodeNumber} • ${it.name}"
            } else {
                it.name
            }
        }

        viewModelScope.launch {
            libraryRepository.saveProgress(
                LibraryEntry(
                    mediaId = details.id,
                    providerId = details.providerId,
                    providerName = details.providerName,
                    title = details.title,
                    url = details.url,
                    type = details.type,
                    posterUrl = details.posterUrl,
                    lastPositionMs = positionMs,
                    durationMs = durationMs,
                    lastEpisodeId = ep?.id,
                    lastEpisodeLabel = epLabel
                )
            )
        }
    }

    fun playEpisode(episode: Episode) {
        val session = _uiState.value.session ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isResolvingNextEpisode = true, errorMessage = null) }
            catalogRepository.resolveLinks(session.details.providerId, episode.dataUrl)
                .onSuccess { links ->
                    val first = links.streams.firstOrNull()
                    if (first == null) {
                        _uiState.update {
                            it.copy(
                                isResolvingNextEpisode = false,
                                errorMessage = "No streams found for ${episode.name}"
                            )
                        }
                    } else {
                        playbackSessionManager.startSession(
                            session.copy(
                                selectedEpisode = episode,
                                selectedSource = first,
                                links = links,
                                resumePositionMs = 0L
                            )
                        )
                        _uiState.update { it.copy(isResolvingNextEpisode = false) }
                    }
                }
                .onFailure { err ->
                    _uiState.update {
                        it.copy(
                            isResolvingNextEpisode = false,
                            errorMessage = err.message ?: "Failed to load episode"
                        )
                    }
                }
        }
    }

    fun nextEpisode(): Episode? {
        val session = _uiState.value.session ?: return null
        val episodes = session.details.episodes
        val currentId = session.selectedEpisode?.id ?: return null
        val idx = episodes.indexOfFirst { it.id == currentId }
        return if (idx >= 0 && idx + 1 < episodes.size) episodes[idx + 1] else null
    }
}
