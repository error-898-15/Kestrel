// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.data.remote

import com.kestrel.domain.model.PluginInstallState
import com.kestrel.domain.model.RepoType
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RepoJsonParserTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val parser = RepoJsonParser(json)

    @Test
    fun `parses cloudstream repo and plugins ignoring unknown fields and expanding icon size`() {
        val repoJson = """
            {
              "name": "Sample CS3 Repo",
              "description": "Test repo",
              "manifestVersion": 1,
              "pluginLists": ["./plugins.json"]
            }
        """.trimIndent()

        val parsed = parser.parseRepositoryManifest(
            rawInputUrl = "https://example.com/repo.json",
            resolvedUrl = "https://example.com/repo.json",
            rawJson = repoJson
        ) as ParsedRepoPayload.CloudStream

        assertEquals(RepoType.CLOUDSTREAM, parsed.repo.type)
        assertEquals("https://example.com/plugins.json", parsed.pluginListUrls.first())

        val pluginsJson = """
            [
              {
                "url": "./builds/SampleProvider.cs3",
                "name": "Sample Provider",
                "internalName": "SampleProvider",
                "version": 4,
                "status": 1,
                "language": "en",
                "authors": ["Tester"],
                "description": "Example provider",
                "iconUrl": "https://cdn.example.com/icon-%size%.png",
                "tvTypes": ["Movie", "TvSeries"],
                "apiVersion": 1,
                "fileSize": 12345,
                "repositoryUrl": "https://github.com/example/repo",
                "jarUrl": "https://example.com/ignored.jar",
                "jarFileSize": 99999,
                "jarHash": "deadbeef"
              },
              {
                "url": "./builds/FutureProvider.cs3",
                "name": "Future Provider",
                "internalName": "FutureProvider",
                "version": 1,
                "apiVersion": 99
              }
            ]
        """.trimIndent()

        val plugins = parser.parseCloudStreamPluginsList(
            repoId = parsed.repo.id,
            listUrl = parsed.pluginListUrls.first(),
            rawJson = pluginsJson,
            supportedApiVersion = 1
        )

        assertEquals(2, plugins.size)
        val first = plugins[0]
        assertEquals("https://example.com/builds/SampleProvider.cs3", first.downloadUrl)
        assertEquals("https://cdn.example.com/icon-128.png", first.iconUrl)
        assertEquals(PluginInstallState.NOT_INSTALLED, first.installState)

        val second = plugins[1]
        assertEquals(PluginInstallState.UNSUPPORTED_API, second.installState)
        assertFalse(second.isEnabled)
    }

    @Test
    fun `parses nuvio manifest with scrapers defaulting to disabled`() {
        val nuvioJson = """
            {
              "name": "Nuvio Test Repo",
              "scrapers": [
                {
                  "id": "demo_scraper",
                  "name": "Demo Scraper",
                  "filename": "demo.js",
                  "version": "2",
                  "supportedTypes": ["movie", "series"],
                  "enabled": true
                }
              ]
            }
        """.trimIndent()

        val parsed = parser.parseRepositoryManifest(
            rawInputUrl = "https://example.com/nuvio/manifest.json",
            resolvedUrl = "https://example.com/nuvio/manifest.json",
            rawJson = nuvioJson
        ) as ParsedRepoPayload.Nuvio

        assertEquals(RepoType.NUVIO, parsed.repo.type)
        assertEquals(1, parsed.plugins.size)
        assertFalse(parsed.plugins.first().isEnabled)
        assertEquals("https://example.com/nuvio/demo.js", parsed.plugins.first().downloadUrl)
    }

    @Test
    fun `parses nuvio manifest with manifestVersion providers numeric version and contentLanguage array`() {
        val nuvioJson = """
            {
              "id": "community-nuvio",
              "name": "Community Nuvio",
              "manifestVersion": 1,
              "providers": [
                {
                  "id": "uhd-scraper",
                  "name": "UHD Scraper",
                  "file": "providers/uhd.js",
                  "version": 3,
                  "author": "Dev",
                  "contentLanguage": ["hi", "en"],
                  "supportedTypes": ["movie", "tv"]
                }
              ]
            }
        """.trimIndent()

        val parsed = parser.parseRepositoryManifest(
            rawInputUrl = "https://raw.githubusercontent.com/dev/nuvio/main/manifest.json",
            resolvedUrl = "https://raw.githubusercontent.com/dev/nuvio/main/manifest.json",
            rawJson = nuvioJson
        ) as ParsedRepoPayload.Nuvio

        assertEquals(RepoType.NUVIO, parsed.repo.type)
        assertEquals(1, parsed.plugins.size)
        val plugin = parsed.plugins.first()
        assertEquals("hi", plugin.language)
        assertEquals(3, plugin.version)
        assertEquals("https://raw.githubusercontent.com/dev/nuvio/main/providers/uhd.js", plugin.downloadUrl)
        assertFalse(plugin.isEnabled)
    }

    @Test
    fun `parses direct cloudstream plugins json array URL`() {
        val pluginsJson = """
            [
              {
                "url": "./builds/Direct.cs3",
                "name": "Direct Provider",
                "internalName": "DirectProvider",
                "version": 2,
                "apiVersion": 1
              }
            ]
        """.trimIndent()

        val parsed = parser.parseRepositoryManifest(
            rawInputUrl = "https://example.com/repo/plugins.json",
            resolvedUrl = "https://example.com/repo/plugins.json",
            rawJson = pluginsJson
        ) as ParsedRepoPayload.CloudStream

        assertEquals(1, parsed.inlinePlugins.size)
        assertEquals("https://example.com/repo/builds/Direct.cs3", parsed.inlinePlugins.first().downloadUrl)
    }

    @Test
    fun `auto detects stremio manifest`() {
        val stremioJson = """
            {
              "id": "com.example.addon",
              "name": "Example Stremio",
              "version": "1.2.0",
              "resources": ["catalog", "meta", "stream"],
              "types": ["movie", "series"],
              "catalogs": []
            }
        """.trimIndent()

        val parsed = parser.parseRepositoryManifest(
            rawInputUrl = "https://addon.example.com/manifest.json",
            resolvedUrl = "https://addon.example.com/manifest.json",
            rawJson = stremioJson
        ) as ParsedRepoPayload.Stremio

        assertEquals(RepoType.STREMIO, parsed.repo.type)
        assertTrue(parsed.addonPlugin.isEnabled)
        assertEquals(PluginInstallState.INSTALLED, parsed.addonPlugin.installState)
    }
}
