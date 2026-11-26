// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.data.repository

import com.kestrel.core.di.IoDispatcher
import com.kestrel.data.local.LibraryDao
import com.kestrel.data.local.LibraryEntity
import com.kestrel.data.local.toDomain
import com.kestrel.data.local.toEntity
import com.kestrel.domain.model.LibraryEntry
import com.kestrel.domain.model.MediaDetails
import com.kestrel.domain.repository.LibraryRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LibraryRepositoryImpl @Inject constructor(
    private val libraryDao: LibraryDao,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : LibraryRepository {

    override val favorites: Flow<List<LibraryEntry>> =
        libraryDao.observeFavorites().map { rows -> rows.map { it.toDomain() } }

    override val history: Flow<List<LibraryEntry>> =
        libraryDao.observeHistory().map { rows -> rows.map { it.toDomain() } }

    override val continueWatching: Flow<List<LibraryEntry>> =
        libraryDao.observeContinueWatching().map { rows -> rows.map { it.toDomain() } }

    override fun observeEntry(mediaId: String): Flow<LibraryEntry?> =
        libraryDao.observeById(mediaId).map { it?.toDomain() }

    override suspend fun toggleFavorite(details: MediaDetails): Boolean =
        withContext(ioDispatcher) {
            val existing = libraryDao.getById(details.id)
            val newFavorite = !(existing?.isFavorite ?: false)
            val now = System.currentTimeMillis()
            val updated = existing?.copy(
                isFavorite = newFavorite,
                updatedAtEpochMs = now
            ) ?: LibraryEntity(
                mediaId = details.id,
                providerId = details.providerId,
                providerName = details.providerName,
                title = details.title,
                url = details.url,
                type = details.type.name,
                posterUrl = details.posterUrl,
                isFavorite = newFavorite,
                lastPositionMs = 0L,
                durationMs = 0L,
                lastEpisodeId = null,
                lastEpisodeLabel = null,
                updatedAtEpochMs = now
            )
            libraryDao.upsert(updated)
            newFavorite
        }

    override suspend fun saveProgress(entry: LibraryEntry) = withContext(ioDispatcher) {
        val existing = libraryDao.getById(entry.mediaId)
        val merged = entry.copy(
            isFavorite = existing?.isFavorite ?: entry.isFavorite,
            updatedAtEpochMs = System.currentTimeMillis()
        )
        libraryDao.upsert(merged.toEntity())
    }

    override suspend fun removeFromHistory(mediaId: String) = withContext(ioDispatcher) {
        libraryDao.clearProgress(mediaId)
    }

    override suspend fun clearHistory() = withContext(ioDispatcher) {
        libraryDao.deleteNonFavorites()
        libraryDao.clearAllProgress()
    }
}
