// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.domain.provider

import com.kestrel.domain.model.MangaCatalogPage
import com.kestrel.domain.model.MangaChapter
import com.kestrel.domain.model.MangaDetails
import com.kestrel.domain.model.MangaPage
import com.kestrel.domain.model.MangaSummary

interface MangaSourceProvider {
    val sourceId: String
    val extensionId: String
    val name: String
    val lang: String
    val baseUrl: String
    val supportsLatest: Boolean get() = true

    suspend fun getPopular(page: Int = 1): Result<MangaCatalogPage>
    suspend fun getLatest(page: Int = 1): Result<MangaCatalogPage>
    suspend fun search(query: String, page: Int = 1): Result<MangaCatalogPage>
    suspend fun getDetails(manga: MangaSummary): Result<MangaDetails>
    suspend fun getChapters(manga: MangaSummary): Result<List<MangaChapter>>
    suspend fun getPages(chapter: MangaChapter): Result<List<MangaPage>>
}
