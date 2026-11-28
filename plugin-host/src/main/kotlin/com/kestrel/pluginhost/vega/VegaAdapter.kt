// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.pluginhost.vega

import com.hikari.app.net.Http
import com.kestrel.domain.model.ActorCredit
import com.kestrel.domain.model.CatalogRow
import com.kestrel.domain.model.Episode
import com.kestrel.domain.model.MediaDetails
import com.kestrel.domain.model.MediaSummary
import com.kestrel.domain.model.MediaType
import com.kestrel.domain.model.PluginMetadata
import com.kestrel.domain.model.RepoType
import com.kestrel.domain.model.ResolvedLinks
import com.kestrel.domain.model.SeasonInfo
import com.kestrel.domain.model.StreamFormat
import com.kestrel.domain.model.StreamSource
import com.kestrel.domain.model.SubtitleTrack
import com.kestrel.domain.provider.SourceProvider
import com.kestrel.pluginhost.PluginSandbox
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference

class VegaAdapter(
    private val plugin: PluginMetadata,
    private val engine: VegaJsEngine,
    private val sandbox: PluginSandbox
) : SourceProvider {

    override val pluginId: String = plugin.id
    override val providerId: String = "${plugin.id}:${plugin.internalName}"
    override val name: String = plugin.displayName
    override val language: String = plugin.language.ifBlank { "en" }
    override val repoType: RepoType = RepoType.VEGA

    override val supportedTypes: Set<MediaType> = plugin.tvTypes.map {
        when (it.lowercase()) {
            "movie" -> MediaType.MOVIE
            "series", "tv", "tvseries" -> MediaType.TV_SERIES
            "anime" -> MediaType.ANIME
            else -> MediaType.OTHER
        }
    }.toSet().ifEmpty { setOf(MediaType.MOVIE, MediaType.TV_SERIES) }

    override val hasCatalog: Boolean = true

    private fun loadBundle(): VegaBundle {
        val path = plugin.localFilePath ?: error("Missing Vega bundle path for ${plugin.displayName}")
        val file = File(path)
        require(file.exists() && file.length() > 0L) { "Missing Vega bundle file for ${plugin.displayName}" }
        return VegaBundle.parse(file.readText())
    }

    override suspend fun getCatalog(page: Int): Result<List<CatalogRow>> =
        sandbox.execute(pluginId = pluginId, timeoutMs = 45_000L) {
            val bundle = loadBundle()
            val safePage = page.coerceAtLeast(1)
            val catJson = engine.getCatalogDefinitions(pluginId, bundle)
            val catErr = catJson.optString("error").takeIf { it.isNotBlank() }
            val catArray = catJson.optJSONArray("catalog") ?: JSONArray()

            val entries = buildList {
                for (i in 0 until catArray.length()) {
                    val obj = catArray.optJSONObject(i) ?: continue
                    val title = obj.optString("title").ifBlank { obj.optString("name") }
                    val filter = obj.optString("filter")
                    if (title.isNotBlank() || filter.isNotBlank()) {
                        add(title.ifBlank { "Catalog ${i + 1}" } to filter)
                    }
                }
            }.ifEmpty {
                listOf("Latest" to "")
            }

            val semaphore = Semaphore(2)
            val firstError = AtomicReference<String?>(catErr)
            val rows = coroutineScope {
                entries.map { (title, filter) ->
                    async {
                        semaphore.withPermit {
                            val out = try {
                                engine.getPosts(
                                    pluginId = pluginId,
                                    bundle = bundle,
                                    filter = filter,
                                    page = safePage
                                )
                            } catch (ce: CancellationException) {
                                throw ce
                            } catch (t: Throwable) {
                                firstError.compareAndSet(null, t.message ?: "getPosts failed")
                                null
                            } ?: return@withPermit null

                            val rowErr = out.optString("error").takeIf { it.isNotBlank() }
                            if (rowErr != null) firstError.compareAndSet(null, rowErr)

                            val posts = parsePosts(out.optJSONArray("posts"))
                            if (posts.isEmpty()) null else {
                                CatalogRow(
                                    id = stableId("$providerId:$filter:$safePage"),
                                    providerId = providerId,
                                    providerName = name,
                                    title = title,
                                    items = posts,
                                    hasNextPage = posts.size >= 8
                                )
                            }
                        }
                    }
                }.awaitAll().filterNotNull()
            }
            if (rows.isEmpty() && !firstError.get().isNullOrBlank()) {
                error(firstError.get()!!)
            }
            rows
        }

    override suspend fun search(query: String, page: Int): Result<List<MediaSummary>> =
        sandbox.execute(pluginId = pluginId, timeoutMs = 25_000L) {
            val trimmed = query.trim()
            if (trimmed.isBlank()) return@execute emptyList()
            val bundle = loadBundle()
            val out = engine.getSearchPosts(
                pluginId = pluginId,
                bundle = bundle,
                searchQuery = trimmed,
                page = page.coerceAtLeast(1)
            )
            val err = out.optString("error")
            val posts = parsePosts(out.optJSONArray("posts"))
            if (posts.isEmpty() && err.isNotBlank()) error(err)
            posts
        }

    override suspend fun load(url: String): Result<MediaDetails> =
        sandbox.execute(pluginId = pluginId, timeoutMs = 35_000L) {
            val bundle = loadBundle()
            val out = engine.getMeta(pluginId, bundle, url)
            val meta = out.optJSONObject("meta")
                ?: error(out.optString("error").ifBlank { "$name returned no details for $url" })

            val title = meta.optString("title").ifBlank { meta.optString("name") }.ifBlank { url }
            val rawType = meta.optString("type", "movie").lowercase()
            val isSeries = rawType == "series" || rawType == "tv" || rawType == "anime"
            val synopsis = optNullableString(meta, "synopsis")
                ?: optNullableString(meta, "description")
                ?: optNullableString(meta, "plot")
            val poster = optNullableString(meta, "poster") ?: optNullableString(meta, "image")
            val backdrop = optNullableString(meta, "image") ?: poster
            val logo = optNullableString(meta, "logo")
            val rating = meta.optDouble("rating").takeIf { !it.isNaN() && it > 0.0 }
            val tags = buildList {
                val arr = meta.optJSONArray("tags")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val t = arr.optString(i).trim()
                        if (t.isNotBlank()) add(t)
                    }
                }
            }
            val actors = buildList {
                val arr = meta.optJSONArray("cast")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val el = arr.opt(i)
                        if (el is String && el.isNotBlank()) {
                            add(ActorCredit(name = el))
                        } else if (el is JSONObject) {
                            val actorName = el.optString("name").trim()
                            if (actorName.isNotBlank()) {
                                add(
                                    ActorCredit(
                                        name = actorName,
                                        role = optNullableString(el, "character") ?: optNullableString(el, "role"),
                                        imageUrl = optNullableString(el, "image")
                                    )
                                )
                            }
                        }
                    }
                }
            }

            val linkList = meta.optJSONArray("linkList") ?: JSONArray()
            val directPlayTargets = mutableListOf<JSONObject>()
            val episodes = mutableListOf<Episode>()
            val epFetchJobs = mutableListOf<Triple<Int, String, String>>() // (seasonGuess, label, episodesLink)

            for (i in 0 until linkList.length()) {
                val entry = linkList.optJSONObject(i) ?: continue
                val entryTitle = entry.optString("title").ifBlank { "Option ${i + 1}" }
                val seasonNum = Regex("\\b(?:season|s)\\s*(\\d+)", RegexOption.IGNORE_CASE)
                    .find(entryTitle)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: (i + 1)

                val directLinks = entry.optJSONArray("directLinks")
                if (directLinks != null && directLinks.length() > 0) {
                    for (j in 0 until directLinks.length()) {
                        val dl = directLinks.optJSONObject(j) ?: continue
                        val dlLink = dl.optString("link").ifBlank { dl.optString("url") }
                        if (dlLink.isBlank()) continue
                        val dlTitle = dl.optString("title").ifBlank { entryTitle }
                        val dlType = dl.optString("type").ifBlank { rawType }

                        if (isSeries || directLinks.length() > 1) {
                            val epNum = Regex("\\b(?:ep(?:isode)?|e)\\s*(\\d+)", RegexOption.IGNORE_CASE)
                                .find(dlTitle)?.groupValues?.getOrNull(1)?.toIntOrNull()
                                ?: (j + 1)
                            episodes.add(
                                Episode(
                                    id = stableId("$providerId:$url:$seasonNum:$epNum:$dlLink"),
                                    name = dlTitle,
                                    season = seasonNum,
                                    episodeNumber = epNum,
                                    dataUrl = encodeStreamTarget(dlLink, dlType)
                                )
                            )
                        } else {
                            directPlayTargets.add(
                                JSONObject().put("link", dlLink).put("type", dlType)
                            )
                        }
                    }
                }

                val epLink = optNullableString(entry, "episodesLink")
                if (!epLink.isNullOrBlank()) {
                    epFetchJobs.add(Triple(seasonNum, entryTitle, epLink))
                }
            }

            if (epFetchJobs.isNotEmpty()) {
                val semaphore = Semaphore(2)
                val fetched = coroutineScope {
                    epFetchJobs.map { (seasonNum, label, epLink) ->
                        async {
                            semaphore.withPermit {
                                val epOut = runCatching {
                                    engine.getEpisodes(pluginId, bundle, epLink)
                                }.getOrNull() ?: return@withPermit emptyList()
                                val arr = epOut.optJSONArray("episodes") ?: return@withPermit emptyList()
                                buildList {
                                    for (k in 0 until arr.length()) {
                                        val epObj = arr.optJSONObject(k) ?: continue
                                        val link = epObj.optString("link").ifBlank { epObj.optString("url") }
                                        if (link.isBlank()) continue
                                        val epTitle = epObj.optString("title").ifBlank { "Episode ${k + 1}" }
                                        val epNum = Regex("\\b(?:ep(?:isode)?|e)\\s*(\\d+)", RegexOption.IGNORE_CASE)
                                            .find(epTitle)?.groupValues?.getOrNull(1)?.toIntOrNull()
                                            ?: (k + 1)
                                        add(
                                            Episode(
                                                id = stableId("$providerId:$url:$seasonNum:$epNum:$k:$link"),
                                                name = if (epFetchJobs.size > 1) "$epTitle ($label)" else epTitle,
                                                season = seasonNum,
                                                episodeNumber = epNum,
                                                dataUrl = encodeStreamTarget(link, rawType.ifBlank { "series" }),
                                                posterUrl = optNullableString(epObj, "image")
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }.awaitAll().flatten()
                }
                episodes.addAll(fetched)
            }

            val seasons = episodes.mapNotNull { it.season }
                .distinct()
                .sorted()
                .map { s -> SeasonInfo(season = s, name = "Season $s") }

            val playDataUrl = when {
                episodes.isNotEmpty() -> null
                directPlayTargets.isNotEmpty() -> MULTI_PREFIX + JSONArray(directPlayTargets).toString()
                else -> encodeStreamTarget(url, rawType.ifBlank { "movie" })
            }

            MediaDetails(
                id = stableId("$providerId:$url"),
                providerId = providerId,
                providerName = name,
                title = title,
                url = url,
                type = if (isSeries || episodes.size > 1) MediaType.TV_SERIES else MediaType.MOVIE,
                plot = synopsis,
                posterUrl = poster,
                backdropUrl = backdrop,
                logoUrl = logo,
                rating = rating,
                tags = tags,
                actors = actors,
                seasons = seasons,
                episodes = episodes,
                playDataUrl = playDataUrl
            )
        }

    override suspend fun getLinks(data: String): Result<ResolvedLinks> =
        getLinks(data = data, timeoutMs = 45_000L)

    override suspend fun getLinks(data: String, timeoutMs: Long): Result<ResolvedLinks> =
        sandbox.execute(pluginId = pluginId, timeoutMs = timeoutMs.coerceAtLeast(8_000L)) {
            val bundle = loadBundle()
            val targets = parseTargets(data)
            val semaphore = Semaphore(2)
            val collectedStreams = mutableListOf<StreamSource>()
            val collectedSubtitles = mutableListOf<SubtitleTrack>()

            val results = coroutineScope {
                targets.map { (link, type) ->
                    async {
                        semaphore.withPermit {
                            runCatching {
                                engine.getStream(pluginId, bundle, link, type, timeoutMs = timeoutMs)
                            }.getOrNull()
                        }
                    }
                }.awaitAll().filterNotNull()
            }

            val firstError = results.firstNotNullOfOrNull { it.optString("error").takeIf { e -> e.isNotBlank() } }
            var iframeAttempts = 0

            results.forEach { obj ->
                val arr = obj.optJSONArray("streams") ?: return@forEach
                for (i in 0 until arr.length()) {
                    val s = arr.optJSONObject(i) ?: continue
                    val streamUrl = s.optString("link").ifBlank { s.optString("url") }.trim()
                    if (streamUrl.isBlank()) continue
                    val headers = parseHeaders(s.optJSONObject("headers"))
                    val subs = parseSubtitles(s.optJSONArray("subtitles"))
                    collectedSubtitles.addAll(subs)
                    val server = s.optString("server").ifBlank { s.optString("name") }.ifBlank { name }
                    val qualityStr = s.optString("quality")
                    val typeStr = s.optString("type").lowercase()

                    if (typeStr == "iframe" || typeStr == "embed") {
                        if (iframeAttempts < MAX_IFRAME_RESOLVES) {
                            iframeAttempts++
                            val captured = Http.resolveWithWebView(
                                url = streamUrl,
                                capture = VIDEO_CAPTURE_REGEX,
                                additional = emptyList(),
                                timeoutMs = 12_000L,
                                script = null
                            )
                            captured.forEachIndexed { idx, hit ->
                                val mergedHeaders = LinkedHashMap<String, String>().apply {
                                    putAll(headers)
                                    putAll(hit.headers)
                                }
                                val hitFormat = inferFormatFromUrl(hit.url)
                                val hitScore = parseQualityScore("$qualityStr $server ${hit.url}")
                                val hitLabel = if (hitScore > 0) "${hitScore}p" else qualityStr.ifBlank { "Auto" }
                                collectedStreams.add(
                                    StreamSource(
                                        id = stableId("$providerId:${hit.url}:$server:$i:$idx"),
                                        providerId = providerId,
                                        providerName = name,
                                        extractorName = server,
                                        url = hit.url,
                                        qualityLabel = hitLabel,
                                        qualityScore = hitScore,
                                        format = hitFormat,
                                        headers = mergedHeaders,
                                        referer = mergedHeaders.entries.firstOrNull {
                                            it.key.equals("referer", ignoreCase = true)
                                        }?.value ?: streamUrl,
                                        subtitles = subs
                                    )
                                )
                            }
                        }
                        continue
                    }

                    val format = when {
                        "m3u8" in typeStr || "hls" in typeStr -> StreamFormat.HLS
                        "dash" in typeStr || "mpd" in typeStr -> StreamFormat.DASH
                        "mp4" in typeStr || "mkv" in typeStr || "webm" in typeStr -> StreamFormat.MP4
                        else -> inferFormatFromUrl(streamUrl)
                    }
                    val score = parseQualityScore("$qualityStr $server $streamUrl")
                    val label = if (score > 0) "${score}p" else qualityStr.ifBlank { "Auto" }

                    collectedStreams.add(
                        StreamSource(
                            id = stableId("$providerId:$streamUrl:$server:$i"),
                            providerId = providerId,
                            providerName = name,
                            extractorName = server,
                            url = streamUrl,
                            qualityLabel = label,
                            qualityScore = score,
                            format = format,
                            headers = headers,
                            referer = headers.entries.firstOrNull { it.key.equals("referer", ignoreCase = true) }?.value,
                            subtitles = subs
                        )
                    )
                }
            }

            if (collectedStreams.isEmpty() && !firstError.isNullOrBlank()) {
                error(firstError)
            }

            ResolvedLinks(
                streams = collectedStreams.distinctBy { "${it.url}|${it.extractorName}" }
                    .sortedByDescending { it.qualityScore },
                subtitles = collectedSubtitles.distinctBy { "${it.url}|${it.language}" }
            )
        }

    private fun parsePosts(arr: JSONArray?): List<MediaSummary> {
        if (arr == null) return emptyList()
        val out = mutableListOf<MediaSummary>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val title = obj.optString("title").ifBlank { obj.optString("name") }.trim()
            val link = obj.optString("link").ifBlank { obj.optString("url") }.trim()
            if (title.isBlank() || link.isBlank()) continue
            val image = optNullableString(obj, "image") ?: optNullableString(obj, "poster")
            val typeStr = obj.optString("type").lowercase()
            val mediaType = when {
                "series" in typeStr || "tv" in typeStr -> MediaType.TV_SERIES
                "anime" in typeStr -> MediaType.ANIME
                else -> MediaType.MOVIE
            }
            out.add(
                MediaSummary(
                    id = stableId("$providerId:$link"),
                    providerId = providerId,
                    providerName = name,
                    title = title,
                    url = link,
                    type = mediaType,
                    posterUrl = image
                )
            )
        }
        return out
    }

    private fun encodeStreamTarget(link: String, type: String): String =
        SINGLE_PREFIX + JSONObject().put("link", link).put("type", type).toString()

    private fun parseTargets(data: String): List<Pair<String, String>> = when {
        data.startsWith(MULTI_PREFIX) -> {
            val arr = JSONArray(data.removePrefix(MULTI_PREFIX))
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val link = o.optString("link")
                    if (link.isNotBlank()) add(link to o.optString("type", "movie"))
                }
            }
        }
        data.startsWith(SINGLE_PREFIX) -> {
            val o = JSONObject(data.removePrefix(SINGLE_PREFIX))
            val link = o.optString("link")
            if (link.isNotBlank()) listOf(link to o.optString("type", "movie")) else emptyList()
        }
        else -> listOf(data to "movie")
    }

    private fun parseHeaders(obj: JSONObject?): Map<String, String> {
        if (obj == null) return emptyMap()
        val map = LinkedHashMap<String, String>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val v = obj.optString(k)
            if (k.isNotBlank() && v.isNotBlank()) map[k] = v
        }
        return map
    }

    private fun parseSubtitles(arr: JSONArray?): List<SubtitleTrack> {
        if (arr == null) return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val url = o.optString("uri").ifBlank { o.optString("url") }.trim()
                if (url.isBlank()) continue
                val lang = o.optString("language").ifBlank { o.optString("lang") }.ifBlank { "en" }
                val title = o.optString("title").ifBlank { lang }
                add(SubtitleTrack(url = url, language = lang, label = title))
            }
        }
    }

    private fun optNullableString(obj: JSONObject, key: String): String? =
        if (!obj.has(key) || obj.isNull(key)) null else obj.optString(key).trim().takeIf { it.isNotBlank() }

    private fun inferFormatFromUrl(url: String): StreamFormat {
        val lower = url.lowercase()
        return when {
            ".m3u8" in lower || "/hls/" in lower || "m3u8" in lower -> StreamFormat.HLS
            ".mpd" in lower || "/dash/" in lower -> StreamFormat.DASH
            ".mp4" in lower || ".mkv" in lower || ".webm" in lower -> StreamFormat.MP4
            else -> StreamFormat.OTHER
        }
    }

    private fun parseQualityScore(text: String): Int {
        val lower = text.lowercase()
        return when {
            "2160" in lower || "4k" in lower -> 2160
            "1440" in lower || "2k" in lower -> 1440
            "1080" in lower -> 1080
            "720" in lower -> 720
            "480" in lower -> 480
            "360" in lower -> 360
            else -> 0
        }
    }

    private fun stableId(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val SINGLE_PREFIX = "vega://one/"
        private const val MULTI_PREFIX = "vega://multi/"
        private const val MAX_IFRAME_RESOLVES = 4
        private val VIDEO_CAPTURE_REGEX = Regex(
            """\.(m3u8|mpd|mp4|mkv|webm)(\?|$|#)|/hls/|master\.txt""",
            RegexOption.IGNORE_CASE
        )
    }
}
