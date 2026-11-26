// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.domain.provider

import com.kestrel.domain.model.CatalogRow
import com.kestrel.domain.model.MediaDetails
import com.kestrel.domain.model.MediaSummary
import com.kestrel.domain.model.MediaType
import com.kestrel.domain.model.RepoType
import com.kestrel.domain.model.ResolvedLinks

/**
 * Unified content provider contract implemented inside :plugin-host by
 * CloudStreamAdapter, NuvioAdapter, and StremioAdapter.
 * Feature modules depend strictly on this interface and domain models.
 */
interface SourceProvider {
    val providerId: String
    val pluginId: String
    val name: String
    val language: String
    val repoType: RepoType
    val supportedTypes: Set<MediaType>
    val hasCatalog: Boolean

    suspend fun getCatalog(page: Int = 1): Result<List<CatalogRow>>
    suspend fun search(query: String, page: Int = 1): Result<List<MediaSummary>>
    suspend fun load(url: String): Result<MediaDetails>
    suspend fun getLinks(data: String): Result<ResolvedLinks>
}
