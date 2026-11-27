// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class RepoType {
    CLOUDSTREAM,
    NUVIO,
    STREMIO,
    VEGA,
    ZANGETSU,
    SKYSTREAM,
    HIKARI
}

@Serializable
enum class MediaType {
    MOVIE,
    TV_SERIES,
    ANIME,
    LIVE,
    OVA,
    CARTOON,
    DOCUMENTARY,
    OTHER
}

@Serializable
enum class PluginInstallState {
    NOT_INSTALLED,
    INSTALLING,
    INSTALLED,
    UPDATE_AVAILABLE,
    UNSUPPORTED_API,
    ERROR
}

@Serializable
data class RepoConfig(
    val id: String,
    val url: String,
    val resolvedUrl: String,
    val name: String,
    val description: String = "",
    val type: RepoType,
    val manifestVersion: Int = 1,
    val pluginListUrls: List<String> = emptyList(),
    val addedAtEpochMs: Long = 0L,
    val lastCheckedEpochMs: Long? = null,
    val isHealthy: Boolean = true,
    val lastError: String? = null
)

@Serializable
data class PluginMetadata(
    val id: String,
    val repoId: String,
    val repoType: RepoType,
    val internalName: String,
    val displayName: String,
    val downloadUrl: String,
    val version: Int = 1,
    val apiVersion: Int = 1,
    val status: Int = 1,
    val language: String = "en",
    val authors: List<String> = emptyList(),
    val description: String = "",
    val iconUrl: String? = null,
    val tvTypes: List<String> = emptyList(),
    val fileSizeBytes: Long? = null,
    val repositoryUrl: String? = null,
    val localFilePath: String? = null,
    val isEnabled: Boolean = false,
    val installState: PluginInstallState = PluginInstallState.NOT_INSTALLED,
    val lastError: String? = null
)

@Serializable
data class PluginHealth(
    val pluginId: String,
    val isHealthy: Boolean,
    val message: String? = null,
    val updatedAtEpochMs: Long = 0L
)
