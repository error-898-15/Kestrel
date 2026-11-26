// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.kestrel.domain.model.PluginInstallState
import com.kestrel.domain.model.PluginMetadata
import com.kestrel.domain.model.RepoConfig
import com.kestrel.domain.model.RepoType
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Entity(tableName = "repositories")
data class RepoEntity(
    @PrimaryKey val id: String,
    val url: String,
    val resolvedUrl: String,
    val name: String,
    val description: String,
    val type: String,
    val manifestVersion: Int,
    val pluginListUrlsJson: String,
    val addedAtEpochMs: Long,
    val lastCheckedEpochMs: Long?,
    val isHealthy: Boolean,
    val lastError: String?
)

@Entity(tableName = "plugins")
data class PluginEntity(
    @PrimaryKey val id: String,
    val repoId: String,
    val repoType: String,
    val internalName: String,
    val displayName: String,
    val downloadUrl: String,
    val version: Int,
    val apiVersion: Int,
    val status: Int,
    val language: String,
    val authorsJson: String,
    val description: String,
    val iconUrl: String?,
    val tvTypesJson: String,
    val fileSizeBytes: Long?,
    val repositoryUrl: String?,
    val localFilePath: String?,
    val isEnabled: Boolean,
    val installState: String,
    val lastError: String?
)

internal fun RepoConfig.toEntity(json: Json): RepoEntity = RepoEntity(
    id = id,
    url = url,
    resolvedUrl = resolvedUrl,
    name = name,
    description = description,
    type = type.name,
    manifestVersion = manifestVersion,
    pluginListUrlsJson = json.encodeToString(pluginListUrls),
    addedAtEpochMs = addedAtEpochMs,
    lastCheckedEpochMs = lastCheckedEpochMs,
    isHealthy = isHealthy,
    lastError = lastError
)

internal fun RepoEntity.toDomain(json: Json): RepoConfig = RepoConfig(
    id = id,
    url = url,
    resolvedUrl = resolvedUrl,
    name = name,
    description = description,
    type = RepoType.valueOf(type),
    manifestVersion = manifestVersion,
    pluginListUrls = runCatching { json.decodeFromString<List<String>>(pluginListUrlsJson) }.getOrDefault(emptyList()),
    addedAtEpochMs = addedAtEpochMs,
    lastCheckedEpochMs = lastCheckedEpochMs,
    isHealthy = isHealthy,
    lastError = lastError
)

internal fun PluginMetadata.toEntity(json: Json): PluginEntity = PluginEntity(
    id = id,
    repoId = repoId,
    repoType = repoType.name,
    internalName = internalName,
    displayName = displayName,
    downloadUrl = downloadUrl,
    version = version,
    apiVersion = apiVersion,
    status = status,
    language = language,
    authorsJson = json.encodeToString(authors),
    description = description,
    iconUrl = iconUrl,
    tvTypesJson = json.encodeToString(tvTypes),
    fileSizeBytes = fileSizeBytes,
    repositoryUrl = repositoryUrl,
    localFilePath = localFilePath,
    isEnabled = isEnabled,
    installState = installState.name,
    lastError = lastError
)

internal fun PluginEntity.toDomain(json: Json): PluginMetadata = PluginMetadata(
    id = id,
    repoId = repoId,
    repoType = RepoType.valueOf(repoType),
    internalName = internalName,
    displayName = displayName,
    downloadUrl = downloadUrl,
    version = version,
    apiVersion = apiVersion,
    status = status,
    language = language,
    authors = runCatching { json.decodeFromString<List<String>>(authorsJson) }.getOrDefault(emptyList()),
    description = description,
    iconUrl = iconUrl,
    tvTypes = runCatching { json.decodeFromString<List<String>>(tvTypesJson) }.getOrDefault(emptyList()),
    fileSizeBytes = fileSizeBytes,
    repositoryUrl = repositoryUrl,
    localFilePath = localFilePath,
    isEnabled = isEnabled,
    installState = PluginInstallState.valueOf(installState),
    lastError = lastError
)
