// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.kestrel.domain.model.MangaExtensionInstallState
import com.kestrel.domain.model.MangaExtensionMetadata
import com.kestrel.domain.model.MangaRepoConfig
import com.kestrel.domain.model.MangaSourceDescriptor
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Entity(tableName = "manga_repositories")
data class MangaRepoEntity(
    @PrimaryKey val id: String,
    val url: String,
    val resolvedUrl: String,
    val name: String,
    val description: String,
    val badgeLabel: String,
    val addedAtEpochMs: Long,
    val lastCheckedEpochMs: Long?,
    val isHealthy: Boolean,
    val lastError: String?
)

@Entity(tableName = "manga_extensions")
data class MangaExtensionEntity(
    @PrimaryKey val id: String,
    val repoId: String,
    val name: String,
    val packageName: String,
    val apkUrl: String,
    val jarUrl: String?,
    val iconUrl: String?,
    val versionName: String,
    val versionCode: Int,
    val lang: String,
    val isNsfw: Boolean,
    val sourcesJson: String,
    val localApkPath: String?,
    val isEnabled: Boolean,
    val installState: String,
    val lastError: String?
)

@Entity(tableName = "manga_library")
data class MangaLibraryEntity(
    @PrimaryKey val mangaId: String,
    val extensionId: String,
    val sourceId: String,
    val sourceName: String,
    val title: String,
    val url: String,
    val coverUrl: String?,
    val isFavorite: Boolean,
    val lastChapterId: String?,
    val lastChapterName: String?,
    val lastPageRead: Int,
    val totalPages: Int,
    val readChapterIdsJson: String,
    val updatedAtEpochMs: Long
)

@Dao
interface MangaRepoDao {
    @Query("SELECT * FROM manga_repositories ORDER BY addedAtEpochMs DESC")
    fun observeAll(): Flow<List<MangaRepoEntity>>

    @Query("SELECT * FROM manga_repositories WHERE id = :repoId LIMIT 1")
    suspend fun getById(repoId: String): MangaRepoEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: MangaRepoEntity)

    @Query("DELETE FROM manga_repositories WHERE id = :repoId")
    suspend fun deleteById(repoId: String)
}

@Dao
interface MangaExtensionDao {
    @Query("SELECT * FROM manga_extensions ORDER BY name ASC")
    fun observeAll(): Flow<List<MangaExtensionEntity>>

    @Query("SELECT * FROM manga_extensions WHERE repoId = :repoId")
    suspend fun getByRepo(repoId: String): List<MangaExtensionEntity>

    @Query("SELECT * FROM manga_extensions WHERE id = :extensionId LIMIT 1")
    suspend fun getById(extensionId: String): MangaExtensionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: MangaExtensionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<MangaExtensionEntity>)

    @Query("DELETE FROM manga_extensions WHERE repoId = :repoId")
    suspend fun deleteByRepo(repoId: String)
}

@Dao
interface MangaLibraryDao {
    @Query("SELECT * FROM manga_library ORDER BY updatedAtEpochMs DESC")
    fun observeAll(): Flow<List<MangaLibraryEntity>>

    @Query("SELECT * FROM manga_library WHERE mangaId = :mangaId LIMIT 1")
    suspend fun getById(mangaId: String): MangaLibraryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: MangaLibraryEntity)
}

internal fun MangaRepoConfig.toEntity(): MangaRepoEntity = MangaRepoEntity(
    id = id,
    url = url,
    resolvedUrl = resolvedUrl,
    name = name,
    description = description,
    badgeLabel = badgeLabel,
    addedAtEpochMs = addedAtEpochMs,
    lastCheckedEpochMs = lastCheckedEpochMs,
    isHealthy = isHealthy,
    lastError = lastError
)

internal fun MangaRepoEntity.toDomain(): MangaRepoConfig = MangaRepoConfig(
    id = id,
    url = url,
    resolvedUrl = resolvedUrl,
    name = name,
    description = description,
    badgeLabel = badgeLabel,
    addedAtEpochMs = addedAtEpochMs,
    lastCheckedEpochMs = lastCheckedEpochMs,
    isHealthy = isHealthy,
    lastError = lastError
)

internal fun MangaExtensionMetadata.toEntity(json: Json): MangaExtensionEntity = MangaExtensionEntity(
    id = id,
    repoId = repoId,
    name = name,
    packageName = packageName,
    apkUrl = apkUrl,
    jarUrl = jarUrl,
    iconUrl = iconUrl,
    versionName = versionName,
    versionCode = versionCode,
    lang = lang,
    isNsfw = isNsfw,
    sourcesJson = json.encodeToString(sources),
    localApkPath = localApkPath,
    isEnabled = isEnabled,
    installState = installState.name,
    lastError = lastError
)

internal fun MangaExtensionEntity.toDomain(json: Json): MangaExtensionMetadata = MangaExtensionMetadata(
    id = id,
    repoId = repoId,
    name = name,
    packageName = packageName,
    apkUrl = apkUrl,
    jarUrl = jarUrl,
    iconUrl = iconUrl,
    versionName = versionName,
    versionCode = versionCode,
    lang = lang,
    isNsfw = isNsfw,
    sources = runCatching { json.decodeFromString<List<MangaSourceDescriptor>>(sourcesJson) }
        .getOrDefault(emptyList()),
    localApkPath = localApkPath,
    isEnabled = isEnabled,
    installState = runCatching { MangaExtensionInstallState.valueOf(installState) }
        .getOrDefault(MangaExtensionInstallState.NOT_INSTALLED),
    lastError = lastError
)
