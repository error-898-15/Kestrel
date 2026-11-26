// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.data.remote

import com.kestrel.domain.model.PluginInstallState
import com.kestrel.pluginhost.PluginSandbox
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * End-to-end integration test simulating a CloudStream TestPlugins repository
 * served over HTTP via MockWebServer: repo.json -> plugins.json -> .cs3 archive
 * verification (classes.dex + manifest.json).
 */
class TestPluginsRepoIntegrationTest {

    private lateinit var server: MockWebServer
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    @Before
    fun setup() {
        server = MockWebServer()
        val cs3Bytes = buildMockTestPluginCs3()

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (request.path) {
                    "/repo.json" -> MockResponse().setBody(
                        """
                        {
                          "name": "TestPlugins Repo",
                          "description": "CloudStream TestPlugins integration fixture",
                          "manifestVersion": 1,
                          "pluginLists": ["/plugins.json"]
                        }
                        """.trimIndent()
                    )
                    "/plugins.json" -> MockResponse().setBody(
                        """
                        [
                          {
                            "url": "/builds/ExampleProvider.cs3",
                            "name": "ExampleProvider",
                            "internalName": "ExampleProvider",
                            "version": 1,
                            "status": 1,
                            "language": "en",
                            "authors": ["TestPlugins"],
                            "description": "Integration test plugin",
                            "iconUrl": "https://example.invalid/icon-%size%.png",
                            "tvTypes": ["Movie"],
                            "apiVersion": 1
                          }
                        ]
                        """.trimIndent()
                    )
                    "/builds/ExampleProvider.cs3" -> MockResponse().setBody(Buffer().write(cs3Bytes))
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
    fun `parses repo, fetches plugins list, and verifies downloaded TestPlugins cs3 archive`() {
        val resolver = RepoUrlResolver(OkHttpClient())
        val parser = RepoJsonParser(json)

        val (resolvedRepoUrl, repoBody) = resolver.fetchText(server.url("/repo.json").toString())
        val parsedRepo = parser.parseRepositoryManifest(
            rawInputUrl = resolvedRepoUrl,
            resolvedUrl = resolvedRepoUrl,
            rawJson = repoBody
        ) as ParsedRepoPayload.CloudStream

        val (resolvedPluginsUrl, pluginsBody) = resolver.fetchText(parsedRepo.pluginListUrls.first())
        val plugins = parser.parseCloudStreamPluginsList(
            repoId = parsedRepo.repo.id,
            listUrl = resolvedPluginsUrl,
            rawJson = pluginsBody,
            supportedApiVersion = PluginSandbox.SUPPORTED_CLOUDSTREAM_API_VERSION
        )

        assertEquals(1, plugins.size)
        val plugin = plugins.first()
        assertEquals("ExampleProvider", plugin.internalName)
        assertEquals(PluginInstallState.NOT_INSTALLED, plugin.installState)

        val tempDir = Files.createTempDirectory("kestrel_cs3_test").toFile()
        try {
            val outFile = File(tempDir, "ExampleProvider.cs3")
            val req = okhttp3.Request.Builder().url(plugin.downloadUrl).get().build()
            OkHttpClient().newCall(req).execute().use { resp ->
                assertTrue(resp.isSuccessful)
                outFile.writeBytes(resp.body!!.bytes())
            }

            java.util.zip.ZipFile(outFile).use { zip ->
                assertTrue(zip.getEntry("classes.dex") != null)
                val manifestEntry = zip.getEntry("manifest.json")
                assertTrue(manifestEntry != null)
                val manifestText = zip.getInputStream(manifestEntry).bufferedReader().readText()
                assertTrue(manifestText.contains("com.example.ExamplePlugin"))
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun buildMockTestPluginCs3(): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zip ->
            zip.putNextEntry(ZipEntry("classes.dex"))
            zip.write(byteArrayOf(0x64, 0x65, 0x78, 0x0A, 0x30, 0x33, 0x35, 0x00))
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(
                """
                {
                  "name": "ExampleProvider",
                  "pluginClassName": "com.example.ExamplePlugin",
                  "requiresResources": false,
                  "version": 1
                }
                """.trimIndent().toByteArray()
            )
            zip.closeEntry()
        }
        return baos.toByteArray()
    }
}
