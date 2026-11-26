// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.pluginhost.cloudstream

import com.kestrel.domain.model.ActorCredit
import com.kestrel.domain.model.CatalogRow
import com.kestrel.domain.model.Episode
import com.kestrel.domain.model.MediaDetails
import com.kestrel.domain.model.MediaSummary
import com.kestrel.domain.model.MediaType
import com.kestrel.domain.model.StreamFormat
import com.kestrel.domain.model.StreamSource
import com.kestrel.domain.model.SubtitleTrack
import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.LiveStreamLoadResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TorrentLoadResponse
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.TvSeriesSearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import java.security.MessageDigest

internal object CloudStreamMappers {

    fun mapTvType(type: TvType?): MediaType = when (type) {
        TvType.Movie, TvType.AnimeMovie -> MediaType.MOVIE
        TvType.TvSeries, TvType.AsianDrama -> MediaType.TV_SERIES
        TvType.Anime -> MediaType.ANIME
        TvType.Live -> MediaType.LIVE
        TvType.OVA -> MediaType.OVA
        TvType.Cartoon -> MediaType.CARTOON
        TvType.Documentary -> MediaType.DOCUMENTARY
        else -> MediaType.OTHER
    }

    fun mapHomePageList(
        list: HomePageList,
        providerId: String,
        providerName: String,
        hasNext: Boolean
    ): CatalogRow = CatalogRow(
        id = stableId("$providerId:${list.name}"),
        providerId = providerId,
        providerName = providerName,
        title = list.name,
        items = list.list.map { mapSearchResponse(it, providerId, providerName) },
        hasNextPage = hasNext
    )

    fun mapSearchResponse(
        item: SearchResponse,
        providerId: String,
        providerName: String
    ): MediaSummary {
        val year = when (item) {
            is MovieSearchResponse -> item.year
            is TvSeriesSearchResponse -> item.year
            is AnimeSearchResponse -> item.year
            else -> null
        }
        return MediaSummary(
            id = stableId("$providerId:${item.url}"),
            providerId = providerId,
            providerName = providerName,
            title = item.name,
            url = item.url,
            type = mapTvType(item.type),
            posterUrl = item.posterUrl,
            posterHeaders = item.posterHeaders.orEmpty(),
            year = year,
            rating = item.score?.toDouble(10)?.takeIf { it >= 0.1 },
            qualityLabel = item.quality?.name
        )
    }

    fun mapLoadResponse(
        response: LoadResponse,
        providerId: String,
        providerName: String
    ): MediaDetails {
        val mappedEpisodes = when (response) {
            is TvSeriesLoadResponse -> response.episodes.mapIndexed { idx, ep -> mapEpisode(ep, idx) }
            is AnimeLoadResponse -> response.episodes.entries
                .flatMap { (dubStatus, list) ->
                    list.mapIndexed { idx, ep ->
                        val base = mapEpisode(ep, idx)
                        base.copy(name = "[${dubStatus.name}] ${base.name}")
                    }
                }
            else -> emptyList()
        }

        val playData = when (response) {
            is MovieLoadResponse -> response.dataUrl
            is LiveStreamLoadResponse -> response.dataUrl
            is TorrentLoadResponse -> response.magnet ?: response.torrent
            else -> mappedEpisodes.firstOrNull()?.dataUrl
        }

        return MediaDetails(
            id = stableId("$providerId:${response.url}"),
            providerId = providerId,
            providerName = providerName,
            title = response.name,
            url = response.url,
            type = mapTvType(response.type),
            plot = response.plot,
            posterUrl = response.posterUrl,
            backdropUrl = response.backgroundPosterUrl ?: response.posterUrl,
            posterHeaders = response.posterHeaders.orEmpty(),
            year = response.year,
            durationMinutes = response.duration,
            rating = response.score?.toDouble(10)?.takeIf { it >= 0.1 },
            tags = response.tags.orEmpty(),
            actors = response.actors.orEmpty().map { actorData ->
                ActorCredit(
                    name = actorData.actor.name,
                    role = actorData.roleString ?: actorData.role?.name,
                    imageUrl = actorData.actor.image
                )
            },
            episodes = mappedEpisodes,
            recommendations = response.recommendations.orEmpty().map {
                mapSearchResponse(it, providerId, providerName)
            },
            playDataUrl = playData
        )
    }

    private fun mapEpisode(
        ep: com.lagradost.cloudstream3.Episode,
        index: Int
    ): Episode = Episode(
        id = stableId("${ep.data}:${ep.season}:${ep.episode}:$index"),
        name = ep.name?.takeIf { it.isNotBlank() }
            ?: "Episode ${ep.episode ?: (index + 1)}",
        season = ep.season,
        episodeNumber = ep.episode ?: (index + 1),
        dataUrl = ep.data,
        posterUrl = ep.posterUrl,
        description = ep.description,
        runtimeMinutes = ep.runTime
    )

    fun mapExtractorLink(
        link: ExtractorLink,
        providerId: String,
        providerName: String
    ): StreamSource {
        val format = when (link.type) {
            ExtractorLinkType.M3U8 -> StreamFormat.HLS
            ExtractorLinkType.DASH -> StreamFormat.DASH
            ExtractorLinkType.VIDEO -> StreamFormat.MP4
            else -> inferFormatFromUrl(link.url)
        }
        val qualityLabel = Qualities.entries.firstOrNull { it.value == link.quality }?.let {
            if (it == Qualities.Unknown) "Auto" else "${it.value}p"
        } ?: if (link.quality > 0) "${link.quality}p" else "Auto"

        return StreamSource(
            id = stableId("$providerId:${link.url}:${link.name}"),
            providerId = providerId,
            providerName = providerName,
            extractorName = link.name.ifBlank { link.source },
            url = link.url,
            qualityLabel = qualityLabel,
            qualityScore = link.quality,
            format = format,
            headers = link.headers,
            referer = link.referer.takeIf { it.isNotBlank() }
        )
    }

    fun mapSubtitleFile(sub: SubtitleFile): SubtitleTrack = SubtitleTrack(
        url = sub.url,
        language = sub.langTag ?: sub.lang,
        label = sub.lang,
        headers = sub.headers.orEmpty()
    )

    private fun inferFormatFromUrl(url: String): StreamFormat {
        val lower = url.lowercase()
        return when {
            ".m3u8" in lower -> StreamFormat.HLS
            ".mpd" in lower -> StreamFormat.DASH
            ".mp4" in lower || ".mkv" in lower -> StreamFormat.MP4
            else -> StreamFormat.OTHER
        }
    }

    private fun stableId(seed: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray())
        return bytes.take(12).joinToString("") { "%02x".format(it) }
    }
}
