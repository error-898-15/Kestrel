// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.pluginhost.skystream

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
import com.kestrel.domain.model.TrailerInfo
import com.kestrel.domain.provider.SourceProvider
import com.kestrel.pluginhost.PluginSandbox
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

class SkyStreamAdapter(
    private val plugin: PluginMetadata,
    private val engine: SkyStreamJsEngine,
    private val sandbox: PluginSandbox
) : SourceProvider {

    override val pluginId: String = plugin.id
    override val providerId: String = "${plugin.id}:${plugin.internalName}"
    override val name: String = plugin.displayName
    override val language: String = plugin.language.ifBlank { "en" }
    override val repoType: RepoType = RepoType.SKYSTREAM

    override val supportedTypes: Set<MediaType> = plugin.tvTypes.map {
        when (it.lowercase()) {
            "movie", "movies" -> MediaType.MOVIE
            "series", "tv", "tvseries", "tv series" -> MediaType.TV_SERIES
            "anime" -> MediaType.ANIME
            "live" -> MediaType.LIVE
            else -> MediaType.OTHER
        }
    }.toSet().ifEmpty { setOf(MediaType.MOVIE, MediaType.TV_SERIES) }

    override val hasCatalog: Boolean = true

    private fun readPayload(): SkyArchivePayload {
        val path = plugin.localFilePath ?: error("Missing SkyStream archive path for ${plugin.displayName}")
        val file = File(path)
        require(file.exists() && file.length() > 0L) { "Missing .sky archive for ${plugin.displayName}" }
        return engine.readArchive(file)
    }

    override suspend fun getCatalog(page: Int): Result<List<CatalogRow>> =
        sandbox.execute(pluginId = pluginId, timeoutMs = 35_000L) {
            if (page > 1) return@execute emptyList()
            val out = engine.getHome(
                pluginId = pluginId,
                packageName = plugin.internalName,
                payload = readPayload()
            )
            if (out.has("success") && !out.optBoolean("success", true)) {
                val msg = out.optString("message").ifBlank { out.optString("error") }
                if (msg.isNotBlank()) error(msg)
            }
            val data = out.opt("data") ?: return@execute emptyList()
            val rows = mutableListOf<CatalogRow>()
            when (data) {
                is JSONObject -> {
                    val keys = data.keys()
                    while (keys.hasNext()) {
                        val sectionTitle = keys.next()
                        val arr = data.optJSONArray(sectionTitle) ?: continue
                        val items = parseItemList(arr)
                        if (items.isNotEmpty()) {
                            rows.add(
                                CatalogRow(
                                    id = stableId("$providerId:$sectionTitle"),
                                    providerId = providerId,
                                    providerName = name,
                                    title = sectionTitle,
                                    items = items,
                                    hasNextPage = false
                                )
                            )
                        }
                    }
                }
                is JSONArray -> {
                    val first = data.optJSONObject(0)
                    if (first != null && (first.has("items") || first.has("list") || first.has("data"))) {
                        for (i in 0 until data.length()) {
                            val sec = data.optJSONObject(i) ?: continue
                            val title = sec.optString("title").ifBlank { sec.optString("name") }.ifBlank { "Row ${i + 1}" }
                            val arr = sec.optJSONArray("items") ?: sec.optJSONArray("list") ?: sec.optJSONArray("data")
                            val items = parseItemList(arr)
                            if (items.isNotEmpty()) {
                                rows.add(
                                    CatalogRow(
                                        id = stableId("$providerId:$title:$i"),
                                        providerId = providerId,
                                        providerName = name,
                                        title = title,
                                        items = items,
                                        hasNextPage = false
                                    )
                                )
                            }
                        }
                    } else {
                        val items = parseItemList(data)
                        if (items.isNotEmpty()) {
                            rows.add(
                                CatalogRow(
                                    id = stableId("$providerId:featured"),
                                    providerId = providerId,
                                    providerName = name,
                                    title = "Featured",
                                    items = items,
                                    hasNextPage = false
                                )
                            )
                        }
                    }
                }
            }
            rows
        }

    override suspend fun search(query: String, page: Int): Result<List<MediaSummary>> =
        sandbox.execute(pluginId = pluginId, timeoutMs = 28_000L) {
            if (page > 1) return@execute emptyList()
            val trimmed = query.trim()
            if (trimmed.isBlank()) return@execute emptyList()
            val out = engine.search(
                pluginId = pluginId,
                packageName = plugin.internalName,
                payload = readPayload(),
                query = trimmed
            )
            if (out.has("success") && !out.optBoolean("success", true)) {
                val msg = out.optString("message").ifBlank { out.optString("error") }
                if (msg.isNotBlank()) error(msg)
            }
            parseItemList(out.optJSONArray("data"))
        }

    override suspend fun load(url: String): Result<MediaDetails> =
        sandbox.execute(pluginId = pluginId, timeoutMs = 35_000L) {
            val out = engine.load(
                pluginId = pluginId,
                packageName = plugin.internalName,
                payload = readPayload(),
                url = url
            )
            if (out.has("success") && !out.optBoolean("success", true)) {
                val msg = out.optString("message").ifBlank { out.optString("error") }
                error(msg.ifBlank { "$name failed to load details for $url" })
            }
            val data = out.optJSONObject("data")
                ?: error(out.optString("message").ifBlank { "$name returned no details for $url" })

            val title = data.optString("title").ifBlank { data.optString("name") }.ifBlank { url }
            val resolvedUrl = data.optString("url").ifBlank { url }
            val poster = optNullableString(data, "posterUrl") ?: optNullableString(data, "poster")
            val backdrop = optNullableString(data, "bannerUrl")
                ?: optNullableString(data, "backdropUrl")
                ?: poster
            val logo = optNullableString(data, "logoUrl")
            val plot = optNullableString(data, "description")
                ?: optNullableString(data, "plot")
                ?: optNullableString(data, "synopsis")
            val year = data.optInt("year").takeIf { it > 1900 }
            val rating = data.optDouble("score").takeIf { !it.isNaN() && it > 0.0 }
                ?: data.optDouble("rating").takeIf { !it.isNaN() && it > 0.0 }

            val tags = buildList {
                val arr = data.optJSONArray("tags") ?: data.optJSONArray("genres")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val t = arr.optString(i).trim()
                        if (t.isNotBlank()) add(t)
                    }
                }
            }

            val actors = buildList {
                val arr = data.optJSONArray("actors")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val actorName = o.optString("name").trim()
                        if (actorName.isNotBlank()) {
                            add(
                                ActorCredit(
                                    name = actorName,
                                    role = optNullableString(o, "role"),
                                    imageUrl = optNullableString(o, "imageUrl") ?: optNullableString(o, "image")
                                )
                            )
                        }
                    }
                }
            }

            val trailers = buildList {
                val arr = data.optJSONArray("trailers")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val tUrl = o.optString("url").trim()
                        if (tUrl.isNotBlank()) {
                            add(
                                TrailerInfo(
                                    url = tUrl,
                                    title = o.optString("name").ifBlank { o.optString("title") }.ifBlank { "Trailer" },
                                    thumbnailUrl = optNullableString(o, "thumbnailUrl") ?: poster
                                )
                            )
                        }
                    }
                }
            }

            val epArr = data.optJSONArray("episodes") ?: JSONArray()
            val episodes = buildList {
                for (i in 0 until epArr.length()) {
                    val o = epArr.optJSONObject(i) ?: continue
                    val epUrl = o.optString("url").ifBlank { o.optString("dataUrl") }.ifBlank { o.optString("data") }
                    if (epUrl.isBlank()) continue
                    val season = o.optInt("season").takeIf { it > 0 } ?: 1
                    val epNum = o.optInt("episode").takeIf { it > 0 }
                        ?: o.optInt("episodeNumber").takeIf { it > 0 }
                        ?: (i + 1)
                    val epName = optNullableString(o, "name")
                        ?: optNullableString(o, "title")
                        ?: "Episode $epNum"
                    add(
                        Episode(
                            id = stableId("$providerId:$resolvedUrl:$season:$epNum:$epUrl"),
                            name = epName,
                            season = season,
                            episodeNumber = epNum,
                            dataUrl = epUrl,
                            posterUrl = optNullableString(o, "posterUrl") ?: optNullableString(o, "thumbnailUrl"),
                            description = optNullableString(o, "description")
                        )
                    )
                }
            }

            val seasons = episodes.mapNotNull { it.season }
                .distinct()
                .sorted()
                .map { s -> SeasonInfo(season = s, name = "Season $s") }

            val rawType = data.optString("type").lowercase()
            val mediaType = when {
                "anime" in rawType -> MediaType.ANIME
                episodes.size > 1 || "series" in rawType || "tv" in rawType -> MediaType.TV_SERIES
                "live" in rawType -> MediaType.LIVE
                else -> MediaType.MOVIE
            }

            val playDataUrl = when {
                episodes.size == 1 && mediaType == MediaType.MOVIE -> episodes.first().dataUrl
                episodes.isNotEmpty() -> null
                else -> optNullableString(data, "data")
                    ?: optNullableString(data, "playData")
                    ?: resolvedUrl
            }

            MediaDetails(
                id = stableId("$providerId:$resolvedUrl"),
                providerId = providerId,
                providerName = name,
                title = title,
                url = resolvedUrl,
                type = mediaType,
                plot = plot,
                posterUrl = poster,
                backdropUrl = backdrop,
                logoUrl = logo,
                year = year,
                rating = rating,
                tags = tags,
                actors = actors,
                trailers = trailers,
                seasons = seasons,
                episodes = episodes,
                playDataUrl = playDataUrl
            )
        }

    override suspend fun getLinks(data: String): Result<ResolvedLinks> =
        getLinks(data = data, timeoutMs = 45_000L)

    override suspend fun getLinks(data: String, timeoutMs: Long): Result<ResolvedLinks> =
        sandbox.execute(pluginId = pluginId, timeoutMs = timeoutMs.coerceAtLeast(8_000L)) {
            val out = engine.loadStreams(
                pluginId = pluginId,
                packageName = plugin.internalName,
                payload = readPayload(),
                url = data,
                timeoutMs = timeoutMs
            )
            if (out.has("success") && !out.optBoolean("success", true)) {
                val msg = out.optString("message").ifBlank { out.optString("error") }
                if (msg.isNotBlank()) error(msg)
            }
            val arr = out.optJSONArray("data") ?: JSONArray()
            val streams = mutableListOf<StreamSource>()
            val allSubtitles = mutableListOf<SubtitleTrack>()

            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val streamUrl = o.optString("url").trim()
                if (streamUrl.isBlank()) continue
                val headers = parseHeaders(o.optJSONObject("headers"))
                val subs = parseSubtitles(o.optJSONArray("subtitles"))
                allSubtitles.addAll(subs)

                val sourceName = o.optString("name")
                    .ifBlank { o.optString("source") }
                    .ifBlank { name }
                val isM3u8 = o.optBoolean("isM3U8", false) || o.optBoolean("isM3u8", false)
                val typeStr = o.optString("type").lowercase()
                val format = when {
                    isM3u8 || "m3u8" in typeStr || "hls" in typeStr -> StreamFormat.HLS
                    "mpd" in typeStr || "dash" in typeStr -> StreamFormat.DASH
                    "mp4" in typeStr || "mkv" in typeStr || "webm" in typeStr -> StreamFormat.MP4
                    else -> inferFormatFromUrl(streamUrl)
                }
                val qRaw = optNullableString(o, "quality") ?: ""
                val score = parseQualityScore("$qRaw $sourceName $streamUrl")
                val qualityLabel = if (score > 0) "${score}p" else qRaw.ifBlank { "Auto" }

                streams.add(
                    StreamSource(
                        id = stableId("$providerId:$streamUrl:$sourceName:$i"),
                        providerId = providerId,
                        providerName = name,
                        extractorName = sourceName,
                        url = streamUrl,
                        qualityLabel = qualityLabel,
                        qualityScore = score,
                        format = format,
                        headers = headers,
                        referer = headers.entries.firstOrNull { it.key.equals("referer", ignoreCase = true) }?.value,
                        subtitles = subs
                    )
                )
            }

            ResolvedLinks(
                streams = streams.distinctBy { "${it.url}|${it.extractorName}" }
                    .sortedByDescending { it.qualityScore },
                subtitles = allSubtitles.distinctBy { "${it.url}|${it.language}" }
            )
        }

    private fun parseItemList(arr: JSONArray?): List<MediaSummary> {
        if (arr == null) return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val title = o.optString("title").ifBlank { o.optString("name") }.trim()
                val itemUrl = o.optString("url").trim()
                if (title.isBlank() || itemUrl.isBlank()) continue
                val poster = optNullableString(o, "posterUrl")
                    ?: optNullableString(o, "poster")
                    ?: optNullableString(o, "image")
                val rawType = o.optString("type").lowercase()
                val mediaType = when {
                    "anime" in rawType -> MediaType.ANIME
                    "series" in rawType || "tv" in rawType -> MediaType.TV_SERIES
                    "live" in rawType -> MediaType.LIVE
                    else -> MediaType.MOVIE
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
}
