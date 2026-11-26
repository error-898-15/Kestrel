// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.pluginhost.stremio

import com.kestrel.domain.model.MediaType
import com.kestrel.domain.model.PluginMetadata
import com.kestrel.domain.model.RepoType
import com.kestrel.domain.model.StreamFormat
import com.kestrel.pluginhost.PluginSandbox
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class StremioAdapterTest {

    private lateinit var server: MockWebServer
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Before
    fun setup() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (request.path) {
                    "/manifest.json" -> MockResponse().setBody(
                        """
                        {
                          "id": "org.example.stremio",
                          "name": "Example Stremio",
                          "version": "1.0.0",
                          "types": ["movie", "series"],
                          "catalogs": [{ "type": "movie", "id": "top", "name": "Top Movies" }],
                          "resources": ["catalog", "meta", "stream"]
                        }
                        """.trimIndent()
                    )
                    "/catalog/movie/top.json" -> MockResponse().setBody(
                        """
                        {
                          "metas": [
                            { "id": "tt0001", "type": "movie", "name": "Test Movie", "releaseInfo": "2024" }
                          ]
                        }
                        """.trimIndent()
                    )
                    "/catalog/movie/top/search=kestrel.json" -> MockResponse().setBody(
                        """
                        {
                          "metas": [
                            { "id": "tt0002", "type": "movie", "name": "Kestrel Match" }
                          ]
                        }
                        """.trimIndent()
                    )
                    "/meta/movie/tt0001.json" -> MockResponse().setBody(
                        """
                        {
                          "meta": {
                            "id": "tt0001",
                            "type": "movie",
                            "name": "Test Movie",
                            "description": "A test description"
                          }
                        }
                        """.trimIndent()
                    )
                    "/stream/movie/tt0001.json" -> MockResponse().setBody(
                        """
                        {
                          "streams": [
                            {
                              "name": "CDN 1080p",
                              "url": "https://cdn.example.invalid/stream.m3u8",
                              "subtitles": [{ "url": "https://cdn.example.invalid/en.vtt", "lang": "en" }]
                            }
                          ]
                        }
                        """.trimIndent()
                    )
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @After
    fun teardown() {
        server.shutdown()
    }

    @Test
    fun `stremio adapter resolves catalog, search, meta, and streams`() = runTest {
        val manifestUrl = server.url("/manifest.json").toString()
        val client = StremioClient(OkHttpClient(), json)
        val manifest = client.fetchManifest(manifestUrl)
        val plugin = PluginMetadata(
            id = "stremio-1",
            repoId = "repo-1",
            repoType = RepoType.STREMIO,
            internalName = manifest.id,
            displayName = manifest.name,
            downloadUrl = manifestUrl,
            isEnabled = true
        )
        val adapter = StremioAdapter(plugin, manifest, client, PluginSandbox())

        val rows = adapter.getCatalog(1).getOrThrow()
        assertEquals(1, rows.size)
        assertEquals("Test Movie", rows.first().items.first().title)

        val hits = adapter.search("kestrel").getOrThrow()
        assertEquals("Kestrel Match", hits.first().title)

        val details = adapter.load("movie::tt0001").getOrThrow()
        assertEquals(MediaType.MOVIE, details.type)
        assertEquals("A test description", details.plot)

        val links = adapter.getLinks("movie::tt0001").getOrThrow()
        assertEquals(StreamFormat.HLS, links.streams.first().format)
        assertEquals("1080p", links.streams.first().qualityLabel)
        assertEquals(1, links.subtitles.size)
    }
}
