// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.domain.provider

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

    suspend fun getPopular(page: Int = 1): Result<List<MangaSummary>>
    suspend fun getLatest(page: Int = 1): Result<List<MangaSummary>>
    suspend fun search(query: String, page: Int = 1): Result<List<MangaSummary>>
    suspend fun getDetails(manga: MangaSummary): Result<MangaDetails>
    suspend fun getChapters(manga: MangaSummary): Result<List<MangaChapter>>
    suspend fun getPages(chapter: MangaChapter): Result<List<MangaPage>>
}
