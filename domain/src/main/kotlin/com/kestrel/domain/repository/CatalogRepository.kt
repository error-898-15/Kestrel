// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.domain.repository

import com.kestrel.domain.model.CatalogRow
import com.kestrel.domain.model.MediaDetails
import com.kestrel.domain.model.MediaSummary
import com.kestrel.domain.model.ResolvedLinks
import com.kestrel.domain.provider.SourceProvider
import kotlinx.coroutines.flow.Flow

data class ProviderCatalogResult(
    val providerId: String,
    val providerName: String,
    val rows: List<CatalogRow> = emptyList(),
    val errorMessage: String? = null
)

data class ProviderSearchResult(
    val providerId: String,
    val providerName: String,
    val items: List<MediaSummary> = emptyList(),
    val errorMessage: String? = null
)

interface CatalogRepository {
    val activeProviders: Flow<List<SourceProvider>>

    suspend fun syncEnabledPlugins()
    suspend fun fetchHomeCatalogs(
        providerFilterId: String? = null,
        page: Int = 1
    ): List<ProviderCatalogResult>

    suspend fun searchAll(
        query: String,
        page: Int = 1
    ): List<ProviderSearchResult>

    suspend fun loadDetails(providerId: String, url: String): Result<MediaDetails>
    suspend fun resolveLinks(providerId: String, dataUrl: String): Result<ResolvedLinks>
}
