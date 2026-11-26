// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.kestrel.domain.model.LibraryEntry
import com.kestrel.domain.model.MediaType
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "library")
data class LibraryEntity(
    @PrimaryKey val mediaId: String,
    val providerId: String,
    val providerName: String,
    val title: String,
    val url: String,
    val type: String,
    val posterUrl: String?,
    val isFavorite: Boolean,
    val lastPositionMs: Long,
    val durationMs: Long,
    val lastEpisodeId: String?,
    val lastEpisodeLabel: String?,
    val updatedAtEpochMs: Long
)

@Dao
interface LibraryDao {
    @Query("SELECT * FROM library WHERE isFavorite = 1 ORDER BY updatedAtEpochMs DESC")
    fun observeFavorites(): Flow<List<LibraryEntity>>

    @Query("SELECT * FROM library WHERE lastPositionMs > 0 OR lastEpisodeId IS NOT NULL ORDER BY updatedAtEpochMs DESC")
    fun observeHistory(): Flow<List<LibraryEntity>>

    @Query("SELECT * FROM library WHERE lastPositionMs > 0 AND (durationMs <= 0 OR (lastPositionMs * 100 / durationMs) < 92) ORDER BY updatedAtEpochMs DESC")
    fun observeContinueWatching(): Flow<List<LibraryEntity>>

    @Query("SELECT * FROM library WHERE mediaId = :mediaId LIMIT 1")
    fun observeById(mediaId: String): Flow<LibraryEntity?>

    @Query("SELECT * FROM library WHERE mediaId = :mediaId LIMIT 1")
    suspend fun getById(mediaId: String): LibraryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: LibraryEntity)

    @Query("UPDATE library SET lastPositionMs = 0, durationMs = 0, lastEpisodeId = NULL, lastEpisodeLabel = NULL WHERE mediaId = :mediaId")
    suspend fun clearProgress(mediaId: String)

    @Query("DELETE FROM library WHERE isFavorite = 0")
    suspend fun deleteNonFavorites()

    @Query("UPDATE library SET lastPositionMs = 0, durationMs = 0, lastEpisodeId = NULL, lastEpisodeLabel = NULL")
    suspend fun clearAllProgress()
}

internal fun LibraryEntity.toDomain(): LibraryEntry = LibraryEntry(
    mediaId = mediaId,
    providerId = providerId,
    providerName = providerName,
    title = title,
    url = url,
    type = runCatching { MediaType.valueOf(type) }.getOrDefault(MediaType.MOVIE),
    posterUrl = posterUrl,
    isFavorite = isFavorite,
    lastPositionMs = lastPositionMs,
    durationMs = durationMs,
    lastEpisodeId = lastEpisodeId,
    lastEpisodeLabel = lastEpisodeLabel,
    updatedAtEpochMs = updatedAtEpochMs
)

internal fun LibraryEntry.toEntity(): LibraryEntity = LibraryEntity(
    mediaId = mediaId,
    providerId = providerId,
    providerName = providerName,
    title = title,
    url = url,
    type = type.name,
    posterUrl = posterUrl,
    isFavorite = isFavorite,
    lastPositionMs = lastPositionMs,
    durationMs = durationMs,
    lastEpisodeId = lastEpisodeId,
    lastEpisodeLabel = lastEpisodeLabel,
    updatedAtEpochMs = updatedAtEpochMs
)
