// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.domain.repository

import com.kestrel.domain.model.LibraryEntry
import com.kestrel.domain.model.MediaDetails
import kotlinx.coroutines.flow.Flow

interface LibraryRepository {
    val favorites: Flow<List<LibraryEntry>>
    val history: Flow<List<LibraryEntry>>
    val continueWatching: Flow<List<LibraryEntry>>

    fun observeEntry(mediaId: String): Flow<LibraryEntry?>
    suspend fun toggleFavorite(details: MediaDetails): Boolean
    suspend fun saveProgress(entry: LibraryEntry)
    suspend fun removeFromHistory(mediaId: String)
    suspend fun clearHistory()
}
