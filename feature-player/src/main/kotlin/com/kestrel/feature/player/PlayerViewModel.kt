// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kestrel.domain.model.Episode
import com.kestrel.domain.model.LibraryEntry
import com.kestrel.domain.model.ResolvedLinks
import com.kestrel.domain.model.ServerSearchTarget
import com.kestrel.domain.model.StreamSource
import com.kestrel.domain.playback.PlaybackSession
import com.kestrel.domain.playback.PlaybackSessionManager
import com.kestrel.domain.repository.CatalogRepository
import com.kestrel.domain.repository.LibraryRepository
import com.kestrel.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
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
    private val settingsRepository: SettingsRepository,
    val okHttpClient: OkHttpClient
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlayerUiState(session = playbackSessionManager.session.value))
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private val failedSourceIds = mutableSetOf<String>()
    private var lastEpisodeKey: String? = null
    private var nextEpisodeJob: Job? = null
    private var userOverrodeSpeed = false

    init {
        viewModelScope.launch {
            playbackSessionManager.session.collect { s ->
                val epKey = "${s?.details?.id.orEmpty()}|${s?.selectedEpisode?.id.orEmpty()}"
                if (epKey != lastEpisodeKey) {
                    lastEpisodeKey = epKey
                    failedSourceIds.clear()
                }
                _uiState.update { it.copy(session = s) }
            }
        }
        viewModelScope.launch {
            settingsRepository.defaultPlaybackSpeed.collect { speed ->
                if (!userOverrodeSpeed) {
                    _uiState.update { it.copy(playbackSpeed = speed.coerceIn(0.5f, 2.5f)) }
                }
            }
        }
    }

    fun selectStreamSource(source: StreamSource, currentPositionMs: Long) {
        failedSourceIds.remove(source.id)
        playbackSessionManager.updateSession { current ->
            current.copy(
                selectedSource = source,
                resumePositionMs = currentPositionMs
            )
        }
    }

    fun tryNextStreamSource(currentPositionMs: Long): StreamSource? {
        val session = _uiState.value.session ?: return null
        failedSourceIds.add(session.selectedSource.id)
        val next = session.links.streams.firstOrNull { it.id !in failedSourceIds } ?: return null
        playbackSessionManager.updateSession { current ->
            current.copy(
                selectedSource = next,
                resumePositionMs = currentPositionMs
            )
        }
        return next
    }

    fun setPlaybackSpeed(speed: Float) {
        userOverrodeSpeed = true
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
        val details = session.details
        nextEpisodeJob?.cancel()
        nextEpisodeJob = viewModelScope.launch {
            _uiState.update { it.copy(isResolvingNextEpisode = true, errorMessage = null) }
            val settings = settingsRepository.serverSearchSettings.first()
            val target = ServerSearchTarget(
                originProviderId = details.providerId,
                originDataUrl = episode.dataUrl,
                title = details.title,
                mediaType = details.type,
                year = details.year,
                season = episode.season,
                episode = episode.episodeNumber,
                rawMediaUrl = details.url
            )
            var startedEpisodeSession = false
            catalogRepository.resolveLinksMultiServer(target = target, settings = settings)
                .catch { err ->
                    _uiState.update {
                        it.copy(
                            isResolvingNextEpisode = false,
                            errorMessage = err.message ?: "Failed to load episode"
                        )
                    }
                }
                .collect { update ->
                    val links = ResolvedLinks(
                        streams = update.allStreams,
                        subtitles = update.allSubtitles
                    )
                    if (!startedEpisodeSession && update.allStreams.isNotEmpty()) {
                        startedEpisodeSession = true
                        failedSourceIds.clear()
                        playbackSessionManager.startSession(
                            session.copy(
                                selectedEpisode = episode,
                                selectedSource = update.allStreams.first(),
                                links = links,
                                resumePositionMs = 0L
                            )
                        )
                        _uiState.update { it.copy(isResolvingNextEpisode = false) }
                    } else if (startedEpisodeSession && update.allStreams.isNotEmpty()) {
                        playbackSessionManager.updateSession { current ->
                            if (current.selectedEpisode?.id == episode.id) {
                                current.copy(links = links)
                            } else {
                                current
                            }
                        }
                    } else if (update.isComplete && !startedEpisodeSession) {
                        _uiState.update {
                            it.copy(
                                isResolvingNextEpisode = false,
                                errorMessage = "No streams found for ${episode.name}"
                            )
                        }
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

    override fun onCleared() {
        nextEpisodeJob?.cancel()
        super.onCleared()
    }
}
