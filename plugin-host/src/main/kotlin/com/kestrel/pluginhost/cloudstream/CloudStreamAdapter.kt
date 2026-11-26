// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.pluginhost.cloudstream

import com.kestrel.domain.model.CatalogRow
import com.kestrel.domain.model.MediaDetails
import com.kestrel.domain.model.MediaSummary
import com.kestrel.domain.model.MediaType
import com.kestrel.domain.model.RepoType
import com.kestrel.domain.model.ResolvedLinks
import com.kestrel.domain.model.StreamSource
import com.kestrel.domain.model.SubtitleTrack
import com.kestrel.domain.provider.SourceProvider
import com.kestrel.pluginhost.PluginSandbox
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.Collections

/**
 * Adapts a single CloudStream [MainAPI] instance to Kestrel's [SourceProvider],
 * isolating every call inside [PluginSandbox].
 */
class CloudStreamAdapter(
    override val pluginId: String,
    override val providerId: String,
    private val api: MainAPI,
    private val sandbox: PluginSandbox
) : SourceProvider {

    override val name: String
        get() = api.name

    override val language: String
        get() = api.lang

    override val repoType: RepoType = RepoType.CLOUDSTREAM

    override val supportedTypes: Set<MediaType>
        get() = api.supportedTypes.map { CloudStreamMappers.mapTvType(it) }.toSet()

    override val hasCatalog: Boolean
        get() = api.hasMainPage

    override suspend fun getCatalog(page: Int): Result<List<CatalogRow>> =
        sandbox.execute(
            pluginId = pluginId,
            timeoutMs = api.getMainPageTimeoutMs ?: PluginSandbox.DEFAULT_TIMEOUT_MS
        ) {
            if (!api.hasMainPage) return@execute emptyList()
            val sections = api.mainPage
                .filter { it.name.isNotBlank() || it.data.isNotBlank() }
                .ifEmpty { listOf(MainPageData("", "", false)) }

            val sectionErrors = Collections.synchronizedList(mutableListOf<Throwable>())
            val sectionSemaphore = Semaphore(3)

            val rows = coroutineScope {
                sections.map { section ->
                    async {
                        sectionSemaphore.withPermit {
                            val req = MainPageRequest(
                                name = section.name,
                                data = section.data,
                                horizontalImages = section.horizontalImages
                            )
                            val response = try {
                                api.getMainPage(page, req)
                            } catch (t: Throwable) {
                                sectionErrors.add(t)
                                null
                            }
                            response?.items.orEmpty().map { list ->
                                CloudStreamMappers.mapHomePageList(
                                    list = list,
                                    providerId = providerId,
                                    providerName = name,
                                    hasNext = response?.hasNext == true
                                )
                            }
                        }
                    }
                }.awaitAll().flatten().filter { it.items.isNotEmpty() }
            }

            if (rows.isEmpty() && sectionErrors.isNotEmpty()) {
                throw sectionErrors.first()
            }
            rows
        }

    override suspend fun search(query: String, page: Int): Result<List<MediaSummary>> =
        sandbox.execute(pluginId = pluginId) {
            val paged = try {
                api.search(query, page)?.items
            } catch (_: NotImplementedError) {
                if (page == 1) api.search(query) else emptyList()
            }
            paged.orEmpty().map {
                CloudStreamMappers.mapSearchResponse(it, providerId, name)
            }
        }

    override suspend fun load(url: String): Result<MediaDetails> =
        sandbox.execute(pluginId = pluginId) {
            val loaded = api.load(url) ?: error("$name returned no details for $url")
            CloudStreamMappers.mapLoadResponse(loaded, providerId, name)
        }

    override suspend fun getLinks(data: String): Result<ResolvedLinks> =
        sandbox.execute(pluginId = pluginId, timeoutMs = 30_000L) {
            val collectedStreams = Collections.synchronizedList(mutableListOf<StreamSource>())
            val collectedSubtitles = Collections.synchronizedList(mutableListOf<SubtitleTrack>())

            api.loadLinks(
                data = data,
                isCasting = false,
                subtitleCallback = { sub ->
                    collectedSubtitles.add(CloudStreamMappers.mapSubtitleFile(sub))
                },
                callback = { link ->
                    collectedStreams.add(CloudStreamMappers.mapExtractorLink(link, providerId, name))
                }
            )

            ResolvedLinks(
                streams = collectedStreams.distinctBy { "${it.url}|${it.extractorName}" }
                    .sortedByDescending { it.qualityScore },
                subtitles = collectedSubtitles.distinctBy { "${it.url}|${it.language}" }
            )
        }
}
