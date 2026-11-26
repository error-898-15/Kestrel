// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.data.remote

import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Normalizes user-provided repository inputs:
 * - https:// / http:// direct links
 * - cloudstreamrepo://, nuvio://, stremio:// links
 * - GitHub / Codeberg repo, tree, or blob web URLs (auto-resolving raw manifest.json / repo.json)
 * - CloudStream shortcodes (e.g. "hexated")
 */
@Singleton
class RepoUrlResolver @Inject constructor(
    private val okHttpClient: OkHttpClient
) {
    fun normalizeInput(rawInput: String): String {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return ""

        val schemeNormalized = when {
            trimmed.startsWith("cloudstreamrepo://", ignoreCase = true) ->
                stripSchemeWithHttps(trimmed, "cloudstreamrepo://")
            trimmed.startsWith("nuvio://", ignoreCase = true) ->
                stripSchemeWithHttps(trimmed, "nuvio://")
            trimmed.startsWith("stremio://", ignoreCase = true) ->
                stripSchemeWithHttps(trimmed, "stremio://")
            !trimmed.contains("://") && !trimmed.contains('/') && !trimmed.contains('.') ->
                "$SHORTCODE_BASE_URL/$trimmed"
            !trimmed.contains("://") -> "https://$trimmed"
            else -> trimmed
        }

        return rewriteGitHostUrl(schemeNormalized).replace(" ", "%20")
    }

    private fun stripSchemeWithHttps(input: String, prefix: String): String {
        val remainder = input.substring(prefix.length)
        return if (remainder.startsWith("http://", ignoreCase = true) ||
            remainder.startsWith("https://", ignoreCase = true)
        ) {
            remainder
        } else {
            "https://$remainder"
        }
    }

    fun rewriteGitHostUrl(url: String): String {
        val clean = url.trim().removeSuffix("/")

        // GitHub blob/raw: https://github.com/user/repo/(blob|raw)/branch/path -> https://raw.githubusercontent.com/user/repo/branch/path
        val ghBlobRegex = Regex("^https?://github\\.com/([^/]+)/([^/]+)/(?:blob|raw)/([^/]+)/(.*)$")
        ghBlobRegex.matchEntire(clean)?.let { match ->
            val (owner, repo, branch, path) = match.destructured
            return "https://raw.githubusercontent.com/$owner/$repo/$branch/$path"
        }

        // GitHub tree: https://github.com/user/repo/tree/branch(/subpath) -> raw base URL
        val ghTreeRegex = Regex("^https?://github\\.com/([^/]+)/([^/]+)/tree/([^/]+)(?:/(.*))?$")
        ghTreeRegex.matchEntire(clean)?.let { match ->
            val owner = match.groupValues[1]
            val repo = match.groupValues[2]
            val branch = match.groupValues[3]
            val sub = match.groupValues[4].trim('/')
            return if (sub.isEmpty()) {
                "https://raw.githubusercontent.com/$owner/$repo/$branch"
            } else {
                "https://raw.githubusercontent.com/$owner/$repo/$branch/$sub"
            }
        }

        // Codeberg src: https://codeberg.org/user/repo/src/branch/main/path -> https://codeberg.org/user/repo/raw/branch/main/path
        val cbSrcRegex = Regex("^https?://codeberg\\.org/([^/]+)/([^/]+)/src/(branch|commit|tag)/([^/]+)(?:/(.*))?$")
        cbSrcRegex.matchEntire(clean)?.let { match ->
            val owner = match.groupValues[1]
            val repo = match.groupValues[2]
            val refType = match.groupValues[3]
            val refName = match.groupValues[4]
            val path = match.groupValues[5].trim('/')
            return if (path.isEmpty()) {
                "https://codeberg.org/$owner/$repo/raw/$refType/$refName"
            } else {
                "https://codeberg.org/$owner/$repo/raw/$refType/$refName/$path"
            }
        }

        return url
    }

    /**
     * Resolves redirects, bare GitHub/Codeberg repo URLs, and returns the final JSON URL + body.
     */
    fun fetchText(rawInput: String): Pair<String, String> {
        val normalized = normalizeInput(rawInput)
        require(normalized.isNotBlank()) { "Repository URL cannot be blank" }

        val baseCandidates = expandManifestCandidates(normalized)
        val candidates = buildList {
            for (c in baseCandidates) {
                add(c)
                toJsDelivrMirror(c)?.let { add(it) }
            }
        }.distinct()
        var lastFailure: Throwable? = null

        for (candidate in candidates) {
            try {
                val pair = executeGet(candidate)
                val trimmedBody = pair.second.trimStart()
                // Skip HTML landing pages if probing a repo root and try the raw manifest candidates next.
                if (candidates.size > 1 && trimmedBody.startsWith("<")) {
                    continue
                }
                return pair
            } catch (t: Throwable) {
                lastFailure = t
            }
        }

        throw lastFailure ?: IllegalStateException("Failed to fetch repository manifest from $normalized")
    }

    private fun toJsDelivrMirror(url: String): String? {
        val rawRegex = Regex("^https?://raw\\.githubusercontent\\.com/([^/]+)/([^/]+)/([^/]+)/(.*)$")
        val match = rawRegex.matchEntire(url) ?: return null
        val (owner, repo, branch, path) = match.destructured
        return "https://cdn.jsdelivr.net/gh/$owner/$repo@$branch/$path"
    }

    private fun expandManifestCandidates(normalized: String): List<String> {
        val clean = normalized.removeSuffix("/")

        // Bare GitHub repo URL: https://github.com/owner/repo
        val ghRootRegex = Regex("^https?://github\\.com/([^/]+)/([^/]+)$")
        ghRootRegex.matchEntire(clean)?.let { match ->
            val owner = match.groupValues[1]
            val repo = match.groupValues[2].removeSuffix(".git")
            return listOf(
                "https://raw.githubusercontent.com/$owner/$repo/main/manifest.json",
                "https://raw.githubusercontent.com/$owner/$repo/main/repo.json",
                "https://raw.githubusercontent.com/$owner/$repo/master/manifest.json",
                "https://raw.githubusercontent.com/$owner/$repo/master/repo.json",
                "https://raw.githubusercontent.com/$owner/$repo/main/plugins.json",
                "https://raw.githubusercontent.com/$owner/$repo/master/plugins.json"
            )
        }

        // Bare Codeberg repo URL: https://codeberg.org/owner/repo
        val cbRootRegex = Regex("^https?://codeberg\\.org/([^/]+)/([^/]+)$")
        cbRootRegex.matchEntire(clean)?.let { match ->
            val owner = match.groupValues[1]
            val repo = match.groupValues[2].removeSuffix(".git")
            return listOf(
                "https://codeberg.org/$owner/$repo/raw/branch/main/manifest.json",
                "https://codeberg.org/$owner/$repo/raw/branch/main/repo.json",
                "https://codeberg.org/$owner/$repo/raw/branch/master/manifest.json",
                "https://codeberg.org/$owner/$repo/raw/branch/master/repo.json"
            )
        }

        // Directory URL on raw.githubusercontent or codeberg raw without .json filename
        if ((clean.startsWith("https://raw.githubusercontent.com/") ||
                clean.startsWith("https://codeberg.org/")) &&
            !clean.endsWith(".json", ignoreCase = true)
        ) {
            return listOf(
                "$clean/manifest.json",
                "$clean/repo.json",
                "$clean/plugins.json",
                normalized
            )
        }

        return listOf(normalized)
    }

    private fun executeGet(url: String): Pair<String, String> {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", BROWSER_USER_AGENT)
            .header("Accept", "application/json, text/plain, */*")
            .get()
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("HTTP ${response.code} fetching $url")
            }
            val finalUrl = rewriteGitHostUrl(response.request.url.toString())
            val body = response.body?.string().orEmpty()
            require(body.isNotBlank()) { "Empty response from $finalUrl" }
            return finalUrl to body
        }
    }

    companion object {
        const val SHORTCODE_BASE_URL = "https://cs3.to"
        const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    }
}
