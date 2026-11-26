// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.data.remote

import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Normalizes user-provided repository inputs:
 * - https:// / http:// direct links
 * - cloudstreamrepo:// links
 * - GitHub / Codeberg web URLs (blob -> raw)
 * - CloudStream shortcodes (e.g. "hexated", "cs3:abc")
 */
@Singleton
class RepoUrlResolver @Inject constructor(
    private val okHttpClient: OkHttpClient
) {
    fun normalizeInput(rawInput: String): String {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return ""

        val schemeNormalized = when {
            trimmed.startsWith("cloudstreamrepo://", ignoreCase = true) -> {
                val remainder = trimmed.removePrefix("cloudstreamrepo://")
                if (remainder.startsWith("http://", ignoreCase = true) ||
                    remainder.startsWith("https://", ignoreCase = true)
                ) {
                    remainder
                } else {
                    "https://$remainder"
                }
            }
            !trimmed.contains("://") && !trimmed.contains('/') && !trimmed.contains('.') -> {
                // Shortcode format (single alphanumeric token)
                "$SHORTCODE_BASE_URL/$trimmed"
            }
            !trimmed.contains("://") -> "https://$trimmed"
            else -> trimmed
        }

        return rewriteGitHostUrl(schemeNormalized)
    }

    fun rewriteGitHostUrl(url: String): String {
        // GitHub: https://github.com/user/repo/blob/branch/path -> https://raw.githubusercontent.com/user/repo/branch/path
        val ghBlobRegex = Regex("^https?://github\\.com/([^/]+)/([^/]+)/blob/([^/]+)/(.*)$")
        ghBlobRegex.matchEntire(url)?.let { match ->
            val (owner, repo, branch, path) = match.destructured
            return "https://raw.githubusercontent.com/$owner/$repo/$branch/$path"
        }

        // Codeberg: https://codeberg.org/user/repo/src/branch/main/path -> https://codeberg.org/user/repo/raw/branch/main/path
        val cbSrcRegex = Regex("^https?://codeberg\\.org/([^/]+)/([^/]+)/src/(branch|commit|tag)/([^/]+)/(.*)$")
        cbSrcRegex.matchEntire(url)?.let { match ->
            val (owner, repo, refType, refName, path) = match.destructured
            return "https://codeberg.org/$owner/$repo/raw/$refType/$refName/$path"
        }

        return url
    }

    /**
     * Resolves redirects (including shortcodes) and returns the final HTTP URL + response body.
     */
    fun fetchText(rawInput: String): Pair<String, String> {
        val normalized = normalizeInput(rawInput)
        require(normalized.isNotBlank()) { "Repository URL cannot be blank" }

        val request = Request.Builder()
            .url(normalized)
            .header("User-Agent", USER_AGENT)
            .get()
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("HTTP ${response.code} fetching $normalized")
            }
            val finalUrl = rewriteGitHostUrl(response.request.url.toString())
            val body = response.body?.string().orEmpty()
            require(body.isNotBlank()) { "Empty response from $finalUrl" }
            return finalUrl to body
        }
    }

    companion object {
        const val SHORTCODE_BASE_URL = "https://cs3.to"
        const val USER_AGENT = "Kestrel/0.1 (Android)"
    }
}
