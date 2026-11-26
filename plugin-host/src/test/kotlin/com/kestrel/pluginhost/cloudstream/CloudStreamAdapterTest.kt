// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.pluginhost.cloudstream

import com.kestrel.domain.model.MediaType
import com.kestrel.domain.model.StreamFormat
import com.kestrel.pluginhost.PluginSandbox
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudStreamAdapterTest {

    private class FakeMainApi(private val shouldThrow: Boolean = false) : MainAPI() {
        override var mainUrl = "https://example.invalid"
        override var name = "FakeProvider"
        override var lang = "en"
        override val hasMainPage = true
        override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
        override val mainPage = listOf(MainPageData("Featured", "https://example.invalid/featured"))

        override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
            if (shouldThrow) error("Simulated provider failure")
            val item = newMovieSearchResponse("Sample Film", "https://example.invalid/film/1") {
                year = 2024
            }
            return newHomePageResponse(HomePageList("Featured", listOf(item)), hasNext = true)
        }

        override suspend fun search(query: String): List<SearchResponse> {
            return listOf(
                newMovieSearchResponse("Match: $query", "https://example.invalid/search/1") {
                    year = 2025
                }
            )
        }

        override suspend fun load(url: String): LoadResponse {
            return if (url.contains("series")) {
                newTvSeriesLoadResponse("Sample Show", url, TvType.TvSeries, listOf(
                    newEpisode("https://example.invalid/ep/1") {
                        name = "Pilot"
                        season = 1
                        episode = 1
                    }
                )) {
                    plot = "Series plot"
                }
            } else {
                newMovieLoadResponse("Sample Film", url, TvType.Movie, "https://example.invalid/play/1") {
                    plot = "Movie plot"
                    year = 2024
                }
            }
        }

        override suspend fun loadLinks(
            data: String,
            isCasting: Boolean,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit
        ): Boolean {
            subtitleCallback(newSubtitleFile("en", "https://example.invalid/sub.vtt"))
            callback(
                newExtractorLink(
                    source = name,
                    name = "Primary HLS",
                    url = "https://example.invalid/master.m3u8",
                    type = ExtractorLinkType.M3U8
                ) {
                    quality = Qualities.P1080.value
                    referer = mainUrl
                }
            )
            return true
        }
    }

    @Test
    fun `maps catalog, search, details, and links cleanly`() = runTest {
        val sandbox = PluginSandbox()
        val adapter = CloudStreamAdapter(
            pluginId = "plugin-1",
            providerId = "plugin-1:FakeProvider",
            api = FakeMainApi(),
            sandbox = sandbox
        )

        val catalog = adapter.getCatalog(1).getOrThrow()
        assertEquals(1, catalog.size)
        assertEquals("Sample Film", catalog.first().items.first().title)

        val search = adapter.search("kestrel").getOrThrow()
        assertEquals("Match: kestrel", search.first().title)

        val series = adapter.load("https://example.invalid/series/1").getOrThrow()
        assertEquals(MediaType.TV_SERIES, series.type)
        assertEquals(1, series.episodes.size)
        assertEquals("Pilot", series.episodes.first().name)

        val links = adapter.getLinks("https://example.invalid/play/1").getOrThrow()
        assertEquals(1, links.streams.size)
        assertEquals(StreamFormat.HLS, links.streams.first().format)
        assertEquals("1080p", links.streams.first().qualityLabel)
        assertEquals(1, links.subtitles.size)
        assertTrue(sandbox.healthByPlugin.value["plugin-1"]?.isHealthy == true)
    }

    @Test
    fun `sandbox isolates plugin exceptions and marks health error`() = runTest {
        val sandbox = PluginSandbox()
        val adapter = CloudStreamAdapter(
            pluginId = "broken-plugin",
            providerId = "broken-plugin:FakeProvider",
            api = FakeMainApi(shouldThrow = true),
            sandbox = sandbox
        )

        val result = adapter.getCatalog(1)
        assertTrue(result.isFailure)
        assertFalse(sandbox.healthByPlugin.value["broken-plugin"]?.isHealthy ?: true)

        val failingLoad = CloudStreamAdapter(
            pluginId = "broken-plugin",
            providerId = "broken-plugin:FakeProvider",
            api = object : MainAPI() {
                override var name = "Broken"
                override suspend fun load(url: String): LoadResponse? {
                    error("Boom")
                }
            },
            sandbox = sandbox
        ).load("https://example.invalid/boom")

        assertTrue(failingLoad.isFailure)
        assertFalse(sandbox.healthByPlugin.value["broken-plugin"]?.isHealthy ?: true)
    }
}
