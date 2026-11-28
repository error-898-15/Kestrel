// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.domain.repository

import com.kestrel.domain.model.MangaChapter
import com.kestrel.domain.model.MangaDetails
import com.kestrel.domain.model.MangaExtensionMetadata
import com.kestrel.domain.model.MangaExtensionTestResult
import com.kestrel.domain.model.MangaPage
import com.kestrel.domain.model.MangaReaderSettings
import com.kestrel.domain.model.MangaRepoConfig
import com.kestrel.domain.model.MangaSummary
import kotlinx.coroutines.flow.Flow

interface MangaRepository {
    val repositories: Flow<List<MangaRepoConfig>>
    val extensions: Flow<List<MangaExtensionMetadata>>
    val readerSettings: Flow<MangaReaderSettings>

    suspend fun ensureDefaultMangaRepositoriesSeeded()
    suspend fun restoreDefaultMangaRepositories(): Result<Int>
    suspend fun addRepository(rawInput: String): Result<MangaRepoConfig>
    suspend fun refreshRepository(repoId: String): Result<MangaRepoConfig>
    suspend fun removeRepository(repoId: String)

    suspend fun installExtension(extensionId: String): Result<MangaExtensionMetadata>
    suspend fun installAllInRepo(repoId: String): Result<Int>
    suspend fun uninstallExtension(extensionId: String)
    suspend fun testExtension(extensionId: String): MangaExtensionTestResult

    suspend fun browsePopular(sourceId: String?, page: Int = 1): Result<List<MangaSummary>>
    suspend fun searchManga(query: String, sourceId: String? = null, page: Int = 1): Result<List<MangaSummary>>
    suspend fun loadMangaDetails(extensionId: String, sourceId: String, mangaUrl: String): Result<MangaDetails>
    suspend fun loadChapterPages(extensionId: String, sourceId: String, chapter: MangaChapter): Result<List<MangaPage>>

    suspend fun toggleFavorite(manga: MangaSummary)
    suspend fun saveChapterProgress(manga: MangaSummary, chapter: MangaChapter, pageIndex: Int, totalPages: Int)
    suspend fun updateReaderSettings(transform: (MangaReaderSettings) -> MangaReaderSettings)
}
