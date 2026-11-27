// SPDX-License-Identifier: GPL-3.0-or-later
package com.hikari.ext

/** Media type used across the Hikari extension API. */
enum class HikariMediaType { MOVIE, SERIES, UNKNOWN }

/** A searchable/browsable catalog row shown on the Home screen. */
data class HikariCatalog(
    val id: String,
    val name: String,
    val type: HikariMediaType,
    val rawType: String = ""
)

/** A title in a list (search results, catalogs, home rows). */
data class HikariMedia(
    val id: String,
    val title: String,
    val type: HikariMediaType,
    val posterUrl: String? = null,
    val year: Int? = null,
    val overview: String? = null,
    val genres: List<String> = emptyList(),
    val backdropUrl: String? = null,
    val rawType: String = ""
)

data class HikariEpisode(
    val number: Int,
    val id: String,
    val name: String? = null,
    val image: String? = null,
    val season: Int = 1
)

data class HikariSubtitle(
    val lang: String,
    val url: String
)

/**
 * A playable source produced by a [HikariProvider].
 */
data class HikariStream(
    val name: String,
    val url: String = "",
    val headers: Map<String, String> = emptyMap(),
    val subtitles: List<HikariSubtitle> = emptyList(),
    val isM3u8: Boolean = false,
    val isMpd: Boolean = false,
    val isTorrent: Boolean = false,
    val infoHash: String? = null,
    val fileIdx: Int? = null,
    val trackers: List<String> = emptyList(),
    val ytId: String? = null,
    val externalUrl: Boolean = false
)

/**
 * Binary-compatible contract expected by `.hiki` extension DEX payloads.
 */
interface HikariProvider {
    val id: String
    val name: String
    val mainUrl: String
    val description: String get() = ""
    val version: Int get() = 1
    val iconUrl: String? get() = null
    val tvTypes: Set<HikariMediaType>
        get() = setOf(HikariMediaType.MOVIE, HikariMediaType.SERIES)

    fun catalogs(): List<HikariCatalog> = emptyList()
    suspend fun getCatalog(catalog: HikariCatalog, page: Int): List<HikariMedia> = emptyList()
    suspend fun search(query: String, page: Int): List<HikariMedia> = emptyList()
    suspend fun getMeta(media: HikariMedia): HikariMedia = media
    suspend fun getEpisodes(media: HikariMedia): List<HikariEpisode>? = null
    suspend fun getStreams(media: HikariMedia, episode: HikariEpisode?): List<HikariStream>
}
