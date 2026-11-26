// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class MediaSummary(
    val id: String,
    val providerId: String,
    val providerName: String,
    val title: String,
    val url: String,
    val type: MediaType = MediaType.MOVIE,
    val posterUrl: String? = null,
    val posterHeaders: Map<String, String> = emptyMap(),
    val year: Int? = null,
    val rating: Double? = null,
    val qualityLabel: String? = null
)

@Serializable
data class CatalogRow(
    val id: String,
    val providerId: String,
    val providerName: String,
    val title: String,
    val items: List<MediaSummary>,
    val hasNextPage: Boolean = false
)

@Serializable
data class Episode(
    val id: String,
    val name: String,
    val season: Int? = null,
    val episodeNumber: Int? = null,
    val dataUrl: String,
    val posterUrl: String? = null,
    val description: String? = null,
    val runtimeMinutes: Int? = null,
    val rating: Double? = null,
    val airDateEpochMs: Long? = null
)

@Serializable
data class ActorCredit(
    val name: String,
    val role: String? = null,
    val imageUrl: String? = null
)

@Serializable
data class TrailerInfo(
    val url: String,
    val title: String = "Trailer",
    val thumbnailUrl: String? = null
)

@Serializable
data class SeasonInfo(
    val season: Int,
    val name: String? = null,
    val posterUrl: String? = null
)

@Serializable
data class MediaDetails(
    val id: String,
    val providerId: String,
    val providerName: String,
    val title: String,
    val url: String,
    val type: MediaType,
    val plot: String? = null,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val logoUrl: String? = null,
    val posterHeaders: Map<String, String> = emptyMap(),
    val year: Int? = null,
    val durationMinutes: Int? = null,
    val rating: Double? = null,
    val contentRating: String? = null,
    val showStatus: String? = null,
    val director: String? = null,
    val tags: List<String> = emptyList(),
    val actors: List<ActorCredit> = emptyList(),
    val trailers: List<TrailerInfo> = emptyList(),
    val seasons: List<SeasonInfo> = emptyList(),
    val episodes: List<Episode> = emptyList(),
    val recommendations: List<MediaSummary> = emptyList(),
    val playDataUrl: String? = null
)

@Serializable
enum class StreamFormat {
    HLS,
    DASH,
    MP4,
    OTHER
}

@Serializable
data class SubtitleTrack(
    val url: String,
    val language: String,
    val label: String? = null,
    val mimeType: String? = null,
    val headers: Map<String, String> = emptyMap()
)

@Serializable
data class StreamSource(
    val id: String,
    val providerId: String,
    val providerName: String,
    val extractorName: String,
    val url: String,
    val qualityLabel: String = "Auto",
    val qualityScore: Int = 0,
    val format: StreamFormat = StreamFormat.OTHER,
    val headers: Map<String, String> = emptyMap(),
    val referer: String? = null,
    val subtitles: List<SubtitleTrack> = emptyList()
)

@Serializable
data class ResolvedLinks(
    val streams: List<StreamSource> = emptyList(),
    val subtitles: List<SubtitleTrack> = emptyList()
)

@Serializable
data class LibraryEntry(
    val mediaId: String,
    val providerId: String,
    val providerName: String,
    val title: String,
    val url: String,
    val type: MediaType,
    val posterUrl: String? = null,
    val isFavorite: Boolean = false,
    val lastPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val lastEpisodeId: String? = null,
    val lastEpisodeLabel: String? = null,
    val updatedAtEpochMs: Long = 0L
)
