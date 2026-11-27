// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class PlaybackStartMode {
    FIRST_FOUND,
    WAIT_FOR_MORE
}

@Serializable
data class ServerSearchSettings(
    val searchAllInstalled: Boolean = false,
    val searchEveryNuvio: Boolean = true,
    val searchEveryStremio: Boolean = true,
    val searchEveryCloudStream: Boolean = false,
    val selectedNuvioIds: Set<String> = emptySet(),
    val selectedStremioIds: Set<String> = emptySet(),
    val selectedCloudStreamIds: Set<String> = emptySet(),
    val sameSearchTimeForAll: Boolean = true,
    val globalSearchTimeoutSec: Int = 25,
    val perExtensionTimeoutsSec: Map<String, Int> = emptyMap(),
    val playbackStartMode: PlaybackStartMode = PlaybackStartMode.WAIT_FOR_MORE,
    val alwaysShowServerList: Boolean = true,
    val askWhereToPlay: Boolean = false
)

data class ServerSearchTarget(
    val originProviderId: String,
    val originDataUrl: String,
    val title: String,
    val mediaType: MediaType,
    val year: Int? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val rawMediaUrl: String? = null
)

data class ProviderServerProgress(
    val providerId: String,
    val providerName: String,
    val repoType: RepoType,
    val isFetching: Boolean,
    val streams: List<StreamSource> = emptyList(),
    val subtitles: List<SubtitleTrack> = emptyList(),
    val errorMessage: String? = null
)

data class MultiServerResolutionUpdate(
    val providers: List<ProviderServerProgress> = emptyList(),
    val allStreams: List<StreamSource> = emptyList(),
    val allSubtitles: List<SubtitleTrack> = emptyList(),
    val isComplete: Boolean = false,
    val completedCount: Int = 0,
    val totalCount: Int = 0
)
