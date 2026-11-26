// SPDX-License-Identifier: GPL-3.0-or-later
package com.lagradost.cloudstream3.syncproviders.providers

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.syncproviders.SyncAPI
import com.lagradost.cloudstream3.syncproviders.SyncIdName
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

class AniListApi : SyncAPI() {
    override val name = "AniList"
    override val idPrefix = "anilist"
    override val mainUrl = "https://anilist.co"
    override val syncIdName = SyncIdName.Anilist

    @Serializable
    data class CoverImage(
        @JsonProperty("medium") @SerialName("medium") val medium: String? = null,
        @JsonProperty("large") @SerialName("large") val large: String? = null,
        @JsonProperty("extraLarge") @SerialName("extraLarge") val extraLarge: String? = null
    )

    @Serializable
    data class LikePageInfo(
        @JsonProperty("total") @SerialName("total") val total: Int? = null,
        @JsonProperty("currentPage") @SerialName("currentPage") val currentPage: Int? = null,
        @JsonProperty("lastPage") @SerialName("lastPage") val lastPage: Int? = null,
        @JsonProperty("perPage") @SerialName("perPage") val perPage: Int? = null,
        @JsonProperty("hasNextPage") @SerialName("hasNextPage") val hasNextPage: Boolean? = null
    )

    @Serializable
    data class MediaCoverImage(
        @JsonProperty("extraLarge") @SerialName("extraLarge") val extraLarge: String? = null,
        @JsonProperty("large") @SerialName("large") val large: String? = null,
        @JsonProperty("medium") @SerialName("medium") val medium: String? = null,
        @JsonProperty("color") @SerialName("color") val color: String? = null
    )

    @Serializable
    data class MediaTitle(
        @JsonProperty("romaji") @SerialName("romaji") val romaji: String? = null,
        @JsonProperty("english") @SerialName("english") val english: String? = null,
        @JsonProperty("native") @SerialName("native") val native: String? = null,
        @JsonProperty("userPreferred") @SerialName("userPreferred") val userPreferred: String? = null
    )

    @Serializable
    data class Title(
        @JsonProperty("english") @SerialName("english") val english: String? = null,
        @JsonProperty("romaji") @SerialName("romaji") val romaji: String? = null
    )

    @Serializable
    data class RecommendedMedia(
        @JsonProperty("id") @SerialName("id") val id: Int? = null,
        @JsonProperty("title") @SerialName("title") val title: MediaTitle? = null,
        @JsonProperty("coverImage") @SerialName("coverImage") val coverImage: MediaCoverImage? = null
    )

    @Serializable
    data class Recommendation(
        @JsonProperty("mediaRecommendation") @SerialName("mediaRecommendation") val mediaRecommendation: RecommendedMedia? = null
    )

    @Serializable
    data class RecommendationEdge(
        @JsonProperty("node") @SerialName("node") val node: Recommendation
    )

    @Serializable
    data class RecommendationConnection(
        @JsonProperty("edges") @SerialName("edges") val edges: List<RecommendationEdge> = emptyList(),
        @JsonProperty("nodes") @SerialName("nodes") val nodes: List<Recommendation> = emptyList()
    )

    @Serializable
    data class SeasonNextAiringEpisode(
        @JsonProperty("episode") @SerialName("episode") val episode: Int? = null,
        @JsonProperty("timeUntilAiring") @SerialName("timeUntilAiring") val timeUntilAiring: Int? = null
    )
}

class SimklApi : SyncAPI() {
    override val name = "Simkl"
    override val idPrefix = "simkl"
    override val mainUrl = "https://api.simkl.com"
    override val syncIdName = SyncIdName.Simkl
}

class MALApi : SyncAPI() {
    override val name = "MAL"
    override val idPrefix = "mal"
    override val mainUrl = "https://myanimelist.net"
    override val syncIdName = SyncIdName.MyAnimeList
}

class KitsuApi : SyncAPI() {
    override val name = "Kitsu"
    override val idPrefix = "kitsu"
    override val mainUrl = "https://kitsu.io"
    override val syncIdName = SyncIdName.Kitsu
}

class LocalList : SyncAPI() {
    override val name = "Local"
    override val idPrefix = "local"
    override val requiresLogin = false
    override val syncIdName = SyncIdName.LocalList
}
