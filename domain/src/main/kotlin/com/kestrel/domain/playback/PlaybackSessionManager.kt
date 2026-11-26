// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.domain.playback

import com.kestrel.domain.model.Episode
import com.kestrel.domain.model.MediaDetails
import com.kestrel.domain.model.ResolvedLinks
import com.kestrel.domain.model.StreamSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class PlaybackSession(
    val details: MediaDetails,
    val selectedEpisode: Episode?,
    val selectedSource: StreamSource,
    val links: ResolvedLinks,
    val resumePositionMs: Long = 0L
)

/**
 * Coordinates the active playback payload between Details/Library and Media3 PlayerScreen.
 */
@Singleton
class PlaybackSessionManager @Inject constructor() {
    private val _session = MutableStateFlow<PlaybackSession?>(null)
    val session: StateFlow<PlaybackSession?> = _session.asStateFlow()

    fun startSession(session: PlaybackSession) {
        _session.value = session
    }

    fun updateSession(transform: (PlaybackSession) -> PlaybackSession) {
        _session.value = _session.value?.let(transform)
    }

    fun clear() {
        _session.value = null
    }
}
