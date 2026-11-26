// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.data.remote

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test

class RepoUrlResolverTest {

    private val resolver = RepoUrlResolver(OkHttpClient())

    @Test
    fun `normalizes cloudstreamrepo and nuvio schemes to https`() {
        assertEquals(
            "https://example.com/repo.json",
            resolver.normalizeInput("cloudstreamrepo://example.com/repo.json")
        )
        assertEquals(
            "https://example.com/manifest.json",
            resolver.normalizeInput("nuvio://example.com/manifest.json")
        )
    }

    @Test
    fun `rewrites github blob and tree urls to raw githubusercontent`() {
        assertEquals(
            "https://raw.githubusercontent.com/user/my-plugins/main/repo.json",
            resolver.normalizeInput("https://github.com/user/my-plugins/blob/main/repo.json")
        )
        assertEquals(
            "https://raw.githubusercontent.com/user/nuvio-providers/main",
            resolver.normalizeInput("https://github.com/user/nuvio-providers/tree/main")
        )
    }

    @Test
    fun `rewrites codeberg src url to raw`() {
        val result = resolver.normalizeInput(
            "https://codeberg.org/user/repo/src/branch/main/repo.json"
        )
        assertEquals(
            "https://codeberg.org/user/repo/raw/branch/main/repo.json",
            result
        )
    }

    @Test
    fun `expands shortcode to cs3 redirect url`() {
        val result = resolver.normalizeInput("testcode")
        assertEquals("${RepoUrlResolver.SHORTCODE_BASE_URL}/testcode", result)
    }
}
