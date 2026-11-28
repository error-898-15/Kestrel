// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.pluginhost.zangetsu

import com.hikari.app.net.Http
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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

class ZangetsuAdapter(
    private val plugin: PluginMetadata,
    private val engine: ZangetsuJsEngine,
    private val sandbox: PluginSandbox
) : SourceProvider {

    override val pluginId: String = plugin.id
    override val providerId: String = "${plugin.id}:${plugin.internalName}"
    override val name: String = plugin.displayName
    override val language: String = plugin.language.ifBlank { "en" }
    override val repoType: RepoType = RepoType.ZANGETSU

    override val supportedTypes: Set<MediaType> = plugin.tvTypes.map {
        when (it.lowercase()) {
            "movie" -> MediaType.MOVIE
            "series", "tv", "tvseries" -> MediaType.TV_SERIES
            "anime" -> MediaType.ANIME
            else -> MediaType.OTHER
        }
    }.toSet().ifEmpty { setOf(MediaType.MOVIE, MediaType.TV_SERIES, MediaType.ANIME) }

    override val hasCatalog: Boolean = true

    private fun readScript(): String {
        val path = plugin.localFilePath ?: error("Missing Zangetsu script path for ${plugin.displayName}")
        val file = File(path)
        require(file.exists() && file.length() > 0L) { "Missing Zangetsu script file for ${plugin.displayName}" }
        return file.readText()
    }

    override suspend fun getCatalog(page: Int): Result<List<CatalogRow>> =
        sandbox.execute(pluginId = pluginId, timeoutMs = 28_000L) {
            val out = engine.getHome(
                pluginId = pluginId,
                sourceId = plugin.internalName,
                scriptCode = readScript(),
                page = page.coerceAtLeast(1)
            )
            val err = out.optString("error")
            val arr = out.optJSONArray("rows") ?: JSONArray()
            val rows = buildList {
                for (i in 0 until arr.length()) {
                    val rowObj = arr.optJSONObject(i) ?: continue
                    val rowId = rowObj.optString("id").ifBlank { "row_$i" }
                    val rowTitle = rowObj.optString("title").ifBlank { name }
                    val items = parseItems(rowObj.optJSONArray("items"))
                    if (items.isNotEmpty()) {
                        add(
                            CatalogRow(
                                id = stableId("$providerId:$rowId:$page"),
                                providerId = providerId,
                                providerName = name,
                                title = rowTitle,
                                items = items,
                                hasNextPage = items.size >= 8
                            )
                        )
                    }
                }
            }
            if (rows.isEmpty() && err.isNotBlank()) error(err)
            rows
        }

    override suspend fun search(query: String, page: Int): Result<List<MediaSummary>> =
        sandbox.execute(pluginId = pluginId, timeoutMs = 25_000L) {
            val trimmed = query.trim()
            if (trimmed.isBlank()) return@execute emptyList()
            val out = engine.search(
                pluginId = pluginId,
                sourceId = plugin.internalName,
                scriptCode = readScript(),
                query = trimmed,
                page = page.coerceAtLeast(1)
            )
            val items = parseItems(out.optJSONArray("items"))
            val err = out.optString("error")
            if (items.isEmpty() && err.isNotBlank()) error(err)
            items
        }

    override suspend fun load(url: String): Result<MediaDetails> =
        sandbox.execute(pluginId = pluginId, timeoutMs = 30_000L) {
            val out = engine.getDetail(
                pluginId = pluginId,
                sourceId = plugin.internalName,
                scriptCode = readScript(),
                url = url
            )
            val detail = out.optJSONObject("detail")
                ?: error(out.optString("error").ifBlank { "$name returned no details for $url" })

            val title = detail.optString("title").ifBlank { detail.optString("name") }.ifBlank { url }
            val detailUrl = detail.optString("url").ifBlank { url }
            val poster = optNullableString(detail, "cover")
                ?: optNullableString(detail, "poster")
                ?: optNullableString(detail, "image")
            val backdrop = optNullableString(detail, "banner")
                ?: optNullableString(detail, "backdrop")
                ?: poster
            val description = optNullableString(detail, "description")
                ?: optNullableString(detail, "synopsis")
                ?: optNullableString(detail, "plot")
            val status = optNullableString(detail, "status")
            val year = detail.optInt("year").takeIf { it > 1900 }
            val genres = buildList {
                val gArr = detail.optJSONArray("genres")
                if (gArr != null) {
                    for (i in 0 until gArr.length()) {
                        val g = gArr.optString(i).trim()
                        if (g.isNotBlank()) add(g)
                    }
                }
            }

            val epArray = detail.optJSONArray("episodes") ?: JSONArray()
            val episodes = buildList {
                for (i in 0 until epArray.length()) {
                    val epObj = epArray.optJSONObject(i) ?: continue
                    val epUrl = epObj.optString("url").ifBlank { epObj.optString("dataUrl") }.ifBlank { epObj.optString("id") }
                    if (epUrl.isBlank()) continue
                    val number = epObj.optInt("number").takeIf { it > 0 } ?: (i + 1)
                    val season = epObj.optInt("season").takeIf { it > 0 } ?: 1
                    val epTitle = optNullableString(epObj, "title")
                        ?: optNullableString(epObj, "name")
                        ?: "Episode $number"
                    add(
                        Episode(
                            id = stableId("$providerId:$detailUrl:$season:$number:$epUrl"),
                            name = epTitle,
                            season = season,
                            episodeNumber = number,
                            dataUrl = epUrl,
                            posterUrl = optNullableString(epObj, "thumbnail") ?: optNullableString(epObj, "poster")
                        )
                    )
                }
            }

            val seasons = episodes.mapNotNull { it.season }
                .distinct()
                .sorted()
                .map { s -> SeasonInfo(season = s, name = "Season $s") }

            val rawType = detail.optString("type").lowercase()
            val inferredType = when {
                "anime" in rawType || MediaType.ANIME in supportedTypes -> MediaType.ANIME
                episodes.size > 1 || "series" in rawType || "tv" in rawType -> MediaType.TV_SERIES
                else -> MediaType.MOVIE
            }

            val playDataUrl = if (episodes.size == 1 && inferredType == MediaType.MOVIE) {
                episodes.first().dataUrl
            } else if (episodes.isEmpty()) {
                detailUrl
            } else {
                null
            }

            MediaDetails(
                id = stableId("$providerId:$detailUrl"),
                providerId = providerId,
                providerName = name,
                title = title,
                url = detailUrl,
                type = inferredType,
                plot = description,
                posterUrl = poster,
                backdropUrl = backdrop,
                year = year,
                showStatus = status,
                tags = genres,
                seasons = seasons,
                episodes = episodes,
                playDataUrl = playDataUrl
            )
        }

    override suspend fun getLinks(data: String): Result<ResolvedLinks> =
        getLinks(data = data, timeoutMs = 40_000L)

    override suspend fun getLinks(data: String, timeoutMs: Long): Result<ResolvedLinks> =
        sandbox.execute(pluginId = pluginId, timeoutMs = timeoutMs.coerceAtLeast(8_000L)) {
            val out = engine.getVideoSources(
                pluginId = pluginId,
                sourceId = plugin.internalName,
                scriptCode = readScript(),
                episodeUrl = data,
                timeoutMs = timeoutMs
            )
            val err = out.optString("error")
            val arr = out.optJSONArray("sources") ?: JSONArray()
            val streams = mutableListOf<StreamSource>()
            val allSubtitles = mutableListOf<SubtitleTrack>()
            var iframeAttempts = 0

            for (i in 0 until arr.length()) {
                val src = arr.optJSONObject(i) ?: continue
                val url = src.optString("url").trim()
                if (url.isBlank()) continue
                val headers = parseHeaders(src.optJSONObject("headers"))
                val subs = parseSubtitles(src.optJSONArray("subtitles"))
                allSubtitles.addAll(subs)

                val quality = src.optString("quality")
                val labelBase = src.optString("label").ifBlank { name }
                val kind = src.optString("kind").uppercase()
                val container = src.optString("container").lowercase()
                val extractorLabel = if (kind.isNotBlank() && kind !in labelBase.uppercase()) {
                    "$labelBase [$kind]"
                } else {
                    labelBase
                }

                if (kind == "IFRAME" || kind == "EMBED" || container == "iframe" || container == "embed") {
                    if (iframeAttempts < MAX_IFRAME_RESOLVES) {
                        iframeAttempts++
                        val captured = Http.resolveWithWebView(
                            url = url,
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
                            val hitScore = parseQualityScore("$quality $extractorLabel ${hit.url}")
                            val hitLabel = if (hitScore > 0) "${hitScore}p" else quality.ifBlank { "Auto" }
                            streams.add(
                                StreamSource(
                                    id = stableId("$providerId:${hit.url}:$extractorLabel:$i:$idx"),
                                    providerId = providerId,
                                    providerName = name,
                                    extractorName = extractorLabel,
                                    url = hit.url,
                                    qualityLabel = hitLabel,
                                    qualityScore = hitScore,
                                    format = inferFormatFromUrl(hit.url),
                                    headers = mergedHeaders,
                                    referer = mergedHeaders.entries.firstOrNull {
                                        it.key.equals("referer", ignoreCase = true)
                                    }?.value ?: url,
                                    subtitles = subs
                                )
                            )
                        }
                    }
                    continue
                }

                val format = when {
                    "hls" in container || "m3u8" in container -> StreamFormat.HLS
                    "dash" in container || "mpd" in container -> StreamFormat.DASH
                    "mp4" in container || "mkv" in container || "webm" in container -> StreamFormat.MP4
                    else -> inferFormatFromUrl(url)
                }
                val score = parseQualityScore("$quality $extractorLabel $url")
                val qualityLabel = if (score > 0) "${score}p" else quality.ifBlank { "Auto" }

                streams.add(
                    StreamSource(
                        id = stableId("$providerId:$url:$extractorLabel:$i"),
                        providerId = providerId,
                        providerName = name,
                        extractorName = extractorLabel,
                        url = url,
                        qualityLabel = qualityLabel,
                        qualityScore = score,
                        format = format,
                        headers = headers,
                        referer = headers.entries.firstOrNull { it.key.equals("referer", ignoreCase = true) }?.value,
                        subtitles = subs
                    )
                )
            }

            if (streams.isEmpty() && err.isNotBlank()) error(err)

            ResolvedLinks(
                streams = streams.distinctBy { "${it.url}|${it.extractorName}" }
                    .sortedByDescending { it.qualityScore },
                subtitles = allSubtitles.distinctBy { "${it.url}|${it.language}" }
            )
        }

    private fun parseItems(arr: JSONArray?): List<MediaSummary> {
        if (arr == null) return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val title = o.optString("title").ifBlank { o.optString("name") }.trim()
                val itemUrl = o.optString("url").ifBlank { o.optString("id") }.trim()
                if (title.isBlank() || itemUrl.isBlank()) continue
                val poster = optNullableString(o, "cover")
                    ?: optNullableString(o, "poster")
                    ?: optNullableString(o, "image")
                val rawType = o.optString("type").lowercase()
                val mediaType = when {
                    "anime" in rawType -> MediaType.ANIME
                    "series" in rawType || "tv" in rawType -> MediaType.TV_SERIES
                    "movie" in rawType -> MediaType.MOVIE
                    else -> supportedTypes.firstOrNull() ?: MediaType.MOVIE
                }
                add(
                    MediaSummary(
                        id = stableId("$providerId:$itemUrl"),
                        providerId = providerId,
                        providerName = name,
                        title = title,
                        url = itemUrl,
                        type = mediaType,
                        posterUrl = poster,
                        year = o.optInt("year").takeIf { it > 1900 }
                    )
                )
            }
        }
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
                val url = o.optString("url").trim()
                if (url.isBlank()) continue
                val lang = o.optString("lang").ifBlank { o.optString("language") }.ifBlank { "en" }
                val label = o.optString("label").ifBlank { lang }
                add(SubtitleTrack(url = url, language = lang, label = label))
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
        private const val MAX_IFRAME_RESOLVES = 4
        private val VIDEO_CAPTURE_REGEX = Regex(
            """\.(m3u8|mpd|mp4|mkv|webm)(\?|$|#)|/hls/|master\.txt""",
            RegexOption.IGNORE_CASE
        )
    }
}
