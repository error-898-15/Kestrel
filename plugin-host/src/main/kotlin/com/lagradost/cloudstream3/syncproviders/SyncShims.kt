// SPDX-License-Identifier: GPL-3.0-or-later
package com.lagradost.cloudstream3.syncproviders

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.syncproviders.providers.AniListApi
import com.lagradost.cloudstream3.syncproviders.providers.KitsuApi
import com.lagradost.cloudstream3.syncproviders.providers.LocalList
import com.lagradost.cloudstream3.syncproviders.providers.MALApi
import com.lagradost.cloudstream3.syncproviders.providers.SimklApi
import com.lagradost.cloudstream3.ui.SyncWatchType
import com.lagradost.cloudstream3.ui.library.ListSorting
import com.lagradost.cloudstream3.utils.UiText
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.Date

data class AuthLoginPage(
    val url: String,
    val payload: String? = null
)

@Serializable
data class AuthToken(
    @JsonProperty("accessToken") @SerialName("accessToken") val accessToken: String? = null,
    @JsonProperty("refreshToken") @SerialName("refreshToken") val refreshToken: String? = null,
    @JsonProperty("accessTokenLifetime") @SerialName("accessTokenLifetime") val accessTokenLifetime: Long? = null,
    @JsonProperty("refreshTokenLifetime") @SerialName("refreshTokenLifetime") val refreshTokenLifetime: Long? = null,
    @JsonProperty("payload") @SerialName("payload") val payload: String? = null
)

@Serializable
data class AuthUser(
    @JsonProperty("name") @SerialName("name") val name: String? = null,
    @JsonProperty("id") @SerialName("id") val id: Int = -1,
    @JsonProperty("profilePicture") @SerialName("profilePicture") val profilePicture: String? = null
)

@Serializable
data class AuthData(
    @JsonProperty("user") @SerialName("user") val user: AuthUser,
    @JsonProperty("token") @SerialName("token") val token: AuthToken
)

data class AuthPinData(
    val deviceCode: String,
    val userCode: String,
    val verificationUrl: String,
    val expiresIn: Int,
    val interval: Int
)

abstract class AuthAPI {
    open val name: String = "NONE"
    open val idPrefix: String = "NONE"
    open val redirectUrlIdentifier: String? = null
    open val icon: Int? = null
    open val requiresLogin: Boolean = true
    open val createAccountUrl: String? = null
    open val hasOAuth2: Boolean = false
    open val hasPin: Boolean = false
    open val hasInApp: Boolean = false

    open fun isValidRedirectUrl(url: String): Boolean = false
    open suspend fun login(redirectUrl: String, payload: String?): AuthToken? = null
    open suspend fun user(auth: AuthToken?): AuthUser? = null
}

abstract class AuthRepo(open val api: AuthAPI) {
    fun isValidRedirectUrl(url: String): Boolean = api.isValidRedirectUrl(url)
    val idPrefix: String get() = api.idPrefix
    val name: String get() = api.name
    val icon: Int? get() = api.icon
    val requiresLogin: Boolean get() = api.requiresLogin
    val createAccountUrl: String? get() = api.createAccountUrl
    val hasOAuth2: Boolean get() = api.hasOAuth2
    val hasPin: Boolean get() = api.hasPin
    val hasInApp: Boolean get() = api.hasInApp
    val isAvailable: Boolean get() = !api.requiresLogin || authUser() != null

    open fun authUser(): AuthUser? = null
    open fun authData(): AuthData? = null
    open fun logOut() {}
}

class PlainAuthRepo(api: AuthAPI) : AuthRepo(api)

abstract class SyncAPI : AuthAPI() {
    open var requireLibraryRefresh: Boolean = true
    open val mainUrl: String = "NONE"
    open val supportedWatchTypes: Set<SyncWatchType> = SyncWatchType.entries.toSet()
    open val syncIdName: SyncIdName? = null

    open suspend fun updateStatus(auth: AuthData?, id: String, newStatus: AbstractSyncStatus): Boolean = false
    open suspend fun status(auth: AuthData?, id: String): AbstractSyncStatus? = null
    open suspend fun load(auth: AuthData?, id: String): SyncResult? = null
    open suspend fun search(auth: AuthData?, query: String): List<SyncSearchResult>? = null
    open suspend fun library(auth: AuthData?): LibraryMetadata? = null
    open fun urlToId(url: String): String? = null

    interface AbstractSyncStatus {
        val status: SyncWatchType
        val score: Score?
        val watchedEpisodes: Int?
        val isFavorite: Boolean?
        val maxEpisodes: Int?
    }

    data class SyncResult(
        val id: String,
        val totalEpisodes: Int? = null,
        val title: String? = null,
        val publicScore: Score? = null,
        val duration: Int? = null,
        val synopsis: String? = null,
        val airStatus: ShowStatus? = null,
        val posterUrl: String? = null,
        val backgroundPosterUrl: String? = null
    )

    data class SyncSearchResult(
        override val name: String,
        override val apiName: String,
        var syncId: String,
        override val url: String,
        override var posterUrl: String?,
        override var type: TvType? = null,
        override var quality: SearchQuality? = null,
        override var posterHeaders: Map<String, String>? = null,
        override var id: Int? = null,
        override var score: Score? = null
    ) : SearchResponse

    data class LibraryMetadata(
        val allLibraryLists: List<LibraryList>,
        val supportedListSorting: Set<ListSorting> = emptySet()
    )

    data class LibraryList(
        val name: UiText,
        val items: List<LibraryItem>
    )

    data class LibraryItem(
        override val name: String,
        override val url: String,
        val syncId: String,
        val episodesCompleted: Int? = null,
        val episodesTotal: Int? = null,
        val personalRating: Score? = null,
        val lastUpdatedUnixTime: Long? = null,
        override val apiName: String = "",
        override var type: TvType? = null,
        override var posterUrl: String? = null,
        override var posterHeaders: Map<String, String>? = null,
        override var quality: SearchQuality? = null,
        val releaseDate: Date? = null,
        override var id: Int? = null,
        val plot: String? = null,
        override var score: Score? = null,
        val tags: List<String>? = null
    ) : SearchResponse
}

class SyncRepo(override val api: SyncAPI) : AuthRepo(api) {
    val syncIdName: SyncIdName? = api.syncIdName
    var requireLibraryRefresh: Boolean
        get() = api.requireLibraryRefresh
        set(value) {
            api.requireLibraryRefresh = value
        }

    suspend fun updateStatus(id: String, newStatus: SyncAPI.AbstractSyncStatus): Result<Boolean> =
        runCatching { api.updateStatus(authData(), id, newStatus) }

    suspend fun status(id: String): Result<SyncAPI.AbstractSyncStatus?> =
        runCatching { api.status(authData(), id) }

    suspend fun load(id: String): Result<SyncAPI.SyncResult?> =
        runCatching { api.load(authData(), id) }

    suspend fun library(): Result<SyncAPI.LibraryMetadata?> =
        runCatching { api.library(authData()) }
}

abstract class AccountManager {
    companion object {
        const val NONE_ID: Int = -1
        const val APP_STRING = "cloudstream"
        const val ACCOUNT_TOKEN = "auth_tokens"
        const val ACCOUNT_IDS = "auth_ids"

        val malApi = MALApi()
        val kitsuApi = KitsuApi()
        val aniListApi = AniListApi()
        val simklApi = SimklApi()
        val localListApi = LocalList()

        var cachedAccounts: MutableMap<String, Array<AuthData>> = mutableMapOf()
        var cachedAccountIds: MutableMap<String, Int> = mutableMapOf()

        val allApis: Array<AuthRepo> = arrayOf(
            SyncRepo(malApi),
            SyncRepo(kitsuApi),
            SyncRepo(aniListApi),
            SyncRepo(simklApi),
            SyncRepo(localListApi)
        )

        fun accounts(prefix: String): Array<AuthData> = cachedAccounts[prefix] ?: emptyArray()
        fun updateAccounts(prefix: String, array: Array<AuthData>) {
            cachedAccounts[prefix] = array
        }
        fun updateAccountsId(prefix: String, id: Int) {
            cachedAccountIds[prefix] = id
        }
        fun updateAccountIds() {}
    }
}
