// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.data.remote

import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLDecoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Normalizes repository inputs across:
 * - Direct HTTP/HTTPS URLs (JSON, min.json, GZIP+Protobuf index.pb)
 * - Custom schemes: cloudstreamrepo://, nuvio://, stremio://, vegarepo://, zangetsurepo://,
 *   skystreamrepo://, hikarirepo://, tachiyomi://add-repo?url=..., aniyomi://add-repo?url=...
 * - GitHub / Codeberg web URLs (blob, tree, repo root -> raw manifest)
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
            trimmed.startsWith("tachiyomi://add-repo", ignoreCase = true) ||
                trimmed.startsWith("aniyomi://add-repo", ignoreCase = true) ->
                extractQueryParam(trimmed, "url") ?: trimmed
            trimmed.startsWith("cloudstreamrepo://", ignoreCase = true) ->
                stripSchemeWithHttps(trimmed, "cloudstreamrepo://")
            trimmed.startsWith("nuvio://", ignoreCase = true) ->
                stripSchemeWithHttps(trimmed, "nuvio://")
            trimmed.startsWith("stremio://", ignoreCase = true) ->
                stripSchemeWithHttps(trimmed, "stremio://")
            trimmed.startsWith("vegarepo://", ignoreCase = true) ->
                stripSchemeWithHttps(trimmed, "vegarepo://")
            trimmed.startsWith("zangetsurepo://", ignoreCase = true) ->
                stripSchemeWithHttps(trimmed, "zangetsurepo://")
            trimmed.startsWith("skystreamrepo://", ignoreCase = true) ->
                stripSchemeWithHttps(trimmed, "skystreamrepo://")
            trimmed.startsWith("hikarirepo://", ignoreCase = true) ->
                stripSchemeWithHttps(trimmed, "hikarirepo://")
            !trimmed.contains("://") && !trimmed.contains('/') && !trimmed.contains('.') ->
                "$SHORTCODE_BASE_URL/$trimmed"
            !trimmed.contains("://") -> "https://$trimmed"
            else -> trimmed
        }

        return rewriteGitHostUrl(schemeNormalized).replace(" ", "%20")
    }

    private fun extractQueryParam(url: String, key: String): String? {
        val query = url.substringAfter('?', "")
        if (query.isBlank()) return null
        return query.split('&')
            .mapNotNull { part ->
                val idx = part.indexOf('=')
                if (idx <= 0) null else part.substring(0, idx) to part.substring(idx + 1)
            }
            .firstOrNull { (k, _) -> k.equals(key, ignoreCase = true) }
            ?.second
            ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
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

        val ghBlobRegex = Regex("^https?://github\\.com/([^/]+)/([^/]+)/(?:blob|raw)/([^/]+)/(.*)$")
        ghBlobRegex.matchEntire(clean)?.let { match ->
            val (owner, repo, branch, path) = match.destructured
            return "https://raw.githubusercontent.com/$owner/$repo/$branch/$path"
        }

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

    fun fetchText(rawInput: String): Pair<String, String> {
        val (finalUrl, bytes) = fetchBytes(rawInput)
        return finalUrl to bytes.toString(Charsets.UTF_8)
    }

    /**
     * Resolves redirects, bare GitHub/Codeberg URLs, and jsDelivr mirrors, returning raw response bytes
     * so binary repositories (e.g. GZIP+Protobuf index.pb) are never corrupted by UTF-8 decoding.
     */
    fun fetchBytes(rawInput: String): Pair<String, ByteArray> {
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
                val (finalUrl, bytes) = executeGetBytes(candidate)
                if (candidates.size > 1 && looksLikeHtml(bytes)) {
                    continue
                }
                return finalUrl to bytes
            } catch (t: Throwable) {
                lastFailure = t
            }
        }
        throw lastFailure ?: IllegalStateException("Failed to fetch repository manifest from $normalized")
    }

    private fun looksLikeHtml(bytes: ByteArray): Boolean {
        var i = 0
        while (i < bytes.size && i < 64 && (bytes[i] == 0x20.toByte() || bytes[i] == 0x09.toByte() || bytes[i] == 0x0a.toByte() || bytes[i] == 0x0d.toByte())) {
            i++
        }
        return i < bytes.size && bytes[i] == '<'.code.toByte()
    }

    private fun toJsDelivrMirror(url: String): String? {
        val rawRegex = Regex("^https?://raw\\.githubusercontent\\.com/([^/]+)/([^/]+)/(?:refs/heads/)?([^/]+)/(.*)$")
        val match = rawRegex.matchEntire(url) ?: return null
        val (owner, repo, branch, path) = match.destructured
        return "https://cdn.jsdelivr.net/gh/$owner/$repo@$branch/$path"
    }

    private fun hasManifestFileExtension(url: String): Boolean {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        return path.endsWith(".json") ||
            path.endsWith(".json.gz") ||
            path.endsWith(".pb") ||
            path.endsWith(".pb.gz") ||
            path.endsWith(".gz")
    }

    private fun expandManifestCandidates(normalized: String): List<String> {
        val clean = normalized.removeSuffix("/")

        val ghRootRegex = Regex("^https?://github\\.com/([^/]+)/([^/]+)$")
        ghRootRegex.matchEntire(clean)?.let { match ->
            val owner = match.groupValues[1]
            val repo = match.groupValues[2].removeSuffix(".git")
            return listOf(
                "https://raw.githubusercontent.com/$owner/$repo/repo/index.min.json",
                "https://raw.githubusercontent.com/$owner/$repo/repo/index.json",
                "https://raw.githubusercontent.com/$owner/$repo/repo/index.pb",
                "https://raw.githubusercontent.com/$owner/$repo/main/manifest.json",
                "https://raw.githubusercontent.com/$owner/$repo/main/repo.json",
                "https://raw.githubusercontent.com/$owner/$repo/main/index.json",
                "https://raw.githubusercontent.com/$owner/$repo/main/mega_repo.json",
                "https://raw.githubusercontent.com/$owner/$repo/builds/repo.json",
                "https://raw.githubusercontent.com/$owner/$repo/master/manifest.json",
                "https://raw.githubusercontent.com/$owner/$repo/master/repo.json",
                "https://raw.githubusercontent.com/$owner/$repo/master/index.json",
                "https://raw.githubusercontent.com/$owner/$repo/main/plugins.json",
                "https://raw.githubusercontent.com/$owner/$repo/builds/plugins.json",
                "https://raw.githubusercontent.com/$owner/$repo/master/plugins.json"
            )
        }

        val cbRootRegex = Regex("^https?://codeberg\\.org/([^/]+)/([^/]+)$")
        cbRootRegex.matchEntire(clean)?.let { match ->
            val owner = match.groupValues[1]
            val repo = match.groupValues[2].removeSuffix(".git")
            return listOf(
                "https://codeberg.org/$owner/$repo/raw/branch/main/manifest.json",
                "https://codeberg.org/$owner/$repo/raw/branch/main/repo.json",
                "https://codeberg.org/$owner/$repo/raw/branch/main/index.json",
                "https://codeberg.org/$owner/$repo/raw/branch/master/manifest.json",
                "https://codeberg.org/$owner/$repo/raw/branch/master/repo.json",
                "https://codeberg.org/$owner/$repo/raw/branch/master/index.json"
            )
        }

        if ((clean.startsWith("https://raw.githubusercontent.com/") ||
                clean.startsWith("https://codeberg.org/")) &&
            !hasManifestFileExtension(clean)
        ) {
            return listOf(
                "$clean/index.min.json",
                "$clean/index.json",
                "$clean/index.pb",
                "$clean/manifest.json",
                "$clean/repo.json",
                "$clean/mega_repo.json",
                "$clean/plugins.json",
                normalized
            )
        }

        return listOf(normalized)
    }

    private fun executeGetBytes(url: String): Pair<String, ByteArray> {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", BROWSER_USER_AGENT)
            .header("Accept", "application/octet-stream, application/json, text/plain, */*")
            .get()
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("HTTP ${response.code} fetching $url")
            }
            val finalUrl = rewriteGitHostUrl(response.request.url.toString())
            val bytes = response.body?.bytes() ?: ByteArray(0)
            require(bytes.isNotEmpty()) { "Empty response from $finalUrl" }
            return finalUrl to bytes
        }
    }

    companion object {
        const val SHORTCODE_BASE_URL = "https://cs3.to"
        const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    }
}
