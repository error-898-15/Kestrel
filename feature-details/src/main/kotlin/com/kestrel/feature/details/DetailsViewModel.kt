// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.feature.details

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kestrel.domain.model.Episode
import com.kestrel.domain.model.LibraryEntry
import com.kestrel.domain.model.MediaDetails
import com.kestrel.domain.model.PlaybackStartMode
import com.kestrel.domain.model.ProviderServerProgress
import com.kestrel.domain.model.ResolvedLinks
import com.kestrel.domain.model.ServerSearchSettings
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
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
    val linkError: String? = null,
    val isSourceSheetOpen: Boolean = false,
    val isAskWhereToPlayOpen: Boolean = false,
    val pendingPlayEpisode: Episode? = null,
    val serverSearchProgress: List<ProviderServerProgress> = emptyList(),
    val selectedServerFilterProviderId: String? = null,
    val completedServerCount: Int = 0,
    val totalServerCount: Int = 0,
    val isWaitingForAutoPlay: Boolean = false,
    val autoPlayEvent: Int = 0
)

@HiltViewModel
class DetailsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val catalogRepository: CatalogRepository,
    private val libraryRepository: LibraryRepository,
    private val settingsRepository: SettingsRepository,
    private val playbackSessionManager: PlaybackSessionManager
) : ViewModel() {

    private val providerId: String = decodeArg(savedStateHandle["providerId"])
    private val mediaUrl: String = decodeArg(savedStateHandle["url"])

    private val serverSettings: StateFlow<ServerSearchSettings> = settingsRepository.serverSearchSettings
        .stateIn(viewModelScope, SharingStarted.Eagerly, ServerSearchSettings())

    private val _uiState = MutableStateFlow(DetailsUiState())
    val uiState: StateFlow<DetailsUiState> = _uiState.asStateFlow()

    private var resolutionJob: Job? = null
    private var lastOnlyOriginProvider: Boolean = false
    private var autoPlayedInCurrentSearch: Boolean = false

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

    fun markWatched() {
        val details = _uiState.value.details ?: return
        val existing = _uiState.value.libraryEntry
        val firstEp = details.episodes.firstOrNull()
        viewModelScope.launch {
            if (existing != null && existing.lastPositionMs > 0L) {
                libraryRepository.removeFromHistory(details.id)
            } else {
                libraryRepository.saveProgress(
                    LibraryEntry(
                        mediaId = details.id,
                        providerId = details.providerId,
                        providerName = details.providerName,
                        title = details.title,
                        url = details.url,
                        type = details.type,
                        posterUrl = details.posterUrl,
                        isFavorite = existing?.isFavorite ?: false,
                        lastPositionMs = 60_000L,
                        durationMs = 60_000L,
                        lastEpisodeId = firstEp?.id,
                        lastEpisodeLabel = firstEp?.let {
                            if (it.season != null && it.episodeNumber != null) {
                                "S${it.season}E${it.episodeNumber} • ${it.name}"
                            } else it.name
                        }
                    )
                )
            }
        }
    }

    fun resolveForPlay(
        episode: Episode? = null,
        forceOriginOnly: Boolean? = null,
        forceShowSheet: Boolean = false
    ) {
        val details = _uiState.value.details ?: return
        val targetEpisode = episode ?: details.episodes.firstOrNull()
        val settings = serverSettings.value

        if (settings.askWhereToPlay && forceOriginOnly == null && !forceShowSheet) {
            _uiState.update {
                it.copy(
                    isAskWhereToPlayOpen = true,
                    pendingPlayEpisode = targetEpisode
                )
            }
            return
        }

        val onlyOrigin = forceOriginOnly ?: false
        lastOnlyOriginProvider = onlyOrigin
        autoPlayedInCurrentSearch = false

        val dataUrl = targetEpisode?.dataUrl
            ?: details.playDataUrl
            ?: details.url

        val searchTarget = ServerSearchTarget(
            originProviderId = details.providerId,
            originDataUrl = dataUrl,
            title = details.title,
            mediaType = details.type,
            year = details.year,
            season = targetEpisode?.season,
            episode = targetEpisode?.episodeNumber,
            rawMediaUrl = details.url
        )

        val openSheetImmediately = forceShowSheet ||
            settings.alwaysShowServerList ||
            settings.playbackStartMode == PlaybackStartMode.WAIT_FOR_MORE

        val waitForAutoPlay = !forceShowSheet &&
            !settings.alwaysShowServerList &&
            settings.playbackStartMode == PlaybackStartMode.FIRST_FOUND

        resolutionJob?.cancel()
        resolutionJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isAskWhereToPlayOpen = false,
                    pendingPlayEpisode = null,
                    isResolvingLinks = true,
                    resolvingEpisode = targetEpisode,
                    resolvedLinks = null,
                    linkError = null,
                    isSourceSheetOpen = openSheetImmediately,
                    serverSearchProgress = emptyList(),
                    selectedServerFilterProviderId = null,
                    completedServerCount = 0,
                    totalServerCount = 0,
                    isWaitingForAutoPlay = waitForAutoPlay
                )
            }

            catalogRepository.resolveLinksMultiServer(
                target = searchTarget,
                settings = settings,
                onlyOriginProvider = onlyOrigin
            )
                .catch { err ->
                    _uiState.update {
                        it.copy(
                            isResolvingLinks = false,
                            isWaitingForAutoPlay = false,
                            linkError = err.message ?: "Failed to resolve streams"
                        )
                    }
                }
                .collect { update ->
                    val links = ResolvedLinks(
                        streams = update.allStreams,
                        subtitles = update.allSubtitles
                    )
                    val shouldAutoPlayNow = !autoPlayedInCurrentSearch &&
                        _uiState.value.isWaitingForAutoPlay &&
                        update.allStreams.isNotEmpty()

                    val noStreamsAtEnd = update.isComplete && update.allStreams.isEmpty()
                    val errorMsg = if (noStreamsAtEnd) {
                        buildNoStreamsErrorMessage(update.providers)
                    } else null

                    _uiState.update { state ->
                        val activeFilter = state.selectedServerFilterProviderId
                        val keepFilter = activeFilter != null && update.providers.any { it.providerId == activeFilter }
                        state.copy(
                            isResolvingLinks = !update.isComplete,
                            resolvedLinks = if (update.allStreams.isNotEmpty() || update.allSubtitles.isNotEmpty()) links else null,
                            serverSearchProgress = update.providers,
                            selectedServerFilterProviderId = if (keepFilter) activeFilter else null,
                            completedServerCount = update.completedCount,
                            totalServerCount = update.totalCount,
                            linkError = errorMsg,
                            isWaitingForAutoPlay = if (shouldAutoPlayNow || update.isComplete) false else state.isWaitingForAutoPlay,
                            isSourceSheetOpen = when {
                                shouldAutoPlayNow -> false
                                noStreamsAtEnd && state.isWaitingForAutoPlay -> true
                                else -> state.isSourceSheetOpen
                            }
                        )
                    }

                    if (shouldAutoPlayNow) {
                        autoPlayedInCurrentSearch = true
                        val firstSource = update.allStreams.first()
                        val stateNow = _uiState.value
                        val resumeMs = if (targetEpisode == null || targetEpisode.id == stateNow.libraryEntry?.lastEpisodeId) {
                            stateNow.libraryEntry?.lastPositionMs ?: 0L
                        } else 0L
                        playbackSessionManager.startSession(
                            PlaybackSession(
                                details = details,
                                selectedEpisode = targetEpisode,
                                selectedSource = firstSource,
                                links = links,
                                resumePositionMs = resumeMs
                            )
                        )
                        _uiState.update { it.copy(autoPlayEvent = it.autoPlayEvent + 1) }
                    } else if (autoPlayedInCurrentSearch && update.allStreams.isNotEmpty()) {
                        // Stream newly discovered servers into active Player session for instant fallback / source switching.
                        playbackSessionManager.updateSession { current ->
                            if (current.details.id == details.id && current.selectedEpisode?.id == targetEpisode?.id) {
                                current.copy(links = links)
                            } else {
                                current
                            }
                        }
                    }
                }
        }
    }

    fun confirmAskWhereToPlay(onlyOriginProvider: Boolean) {
        val ep = _uiState.value.pendingPlayEpisode
        _uiState.update { it.copy(isAskWhereToPlayOpen = false, pendingPlayEpisode = null) }
        resolveForPlay(episode = ep, forceOriginOnly = onlyOriginProvider, forceShowSheet = false)
    }

    fun dismissAskWhereToPlay() {
        _uiState.update { it.copy(isAskWhereToPlayOpen = false, pendingPlayEpisode = null) }
    }

    fun refreshStreams() {
        val currentEp = _uiState.value.resolvingEpisode
        resolveForPlay(
            episode = currentEp,
            forceOriginOnly = lastOnlyOriginProvider,
            forceShowSheet = true
        )
    }

    fun selectServerFilter(providerId: String?) {
        _uiState.update { it.copy(selectedServerFilterProviderId = providerId) }
    }

    fun dismissSourcePicker() {
        if (!autoPlayedInCurrentSearch) {
            resolutionJob?.cancel()
            resolutionJob = null
        }
        _uiState.update {
            it.copy(
                isSourceSheetOpen = false,
                isResolvingLinks = false,
                isWaitingForAutoPlay = false,
                resolvedLinks = null,
                linkError = null
            )
        }
    }

    fun startPlayback(source: StreamSource) {
        val state = _uiState.value
        val details = state.details ?: return
        val links = state.resolvedLinks ?: ResolvedLinks(streams = listOf(source))
        val episode = state.resolvingEpisode
        val resumeMs = if (episode == null || episode.id == state.libraryEntry?.lastEpisodeId) {
            state.libraryEntry?.lastPositionMs ?: 0L
        } else {
            0L
        }
        autoPlayedInCurrentSearch = true

        playbackSessionManager.startSession(
            PlaybackSession(
                details = details,
                selectedEpisode = episode,
                selectedSource = source,
                links = links,
                resumePositionMs = resumeMs
            )
        )
        _uiState.update { it.copy(isSourceSheetOpen = false) }
    }

    override fun onCleared() {
        resolutionJob?.cancel()
        super.onCleared()
    }

    private fun buildNoStreamsErrorMessage(providers: List<ProviderServerProgress>): String {
        if (providers.isEmpty()) {
            return "No enabled stream providers matched your server search settings."
        }
        val errors = providers.mapNotNull { p ->
            p.errorMessage?.takeIf { it.isNotBlank() }?.let { "${p.providerName}: $it" }
        }
        return if (errors.isNotEmpty()) {
            "No playable streams found across ${providers.size} server(s). ${errors.take(2).joinToString(" • ")}"
        } else {
            "No playable streams found across ${providers.size} server(s)."
        }
    }

    private fun decodeArg(raw: String?): String =
        if (raw.isNullOrBlank()) "" else runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
}
