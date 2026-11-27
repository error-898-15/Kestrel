// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.pluginhost.stremio

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class StremioCatalogDescriptor(
    val type: String = "movie",
    val id: String = "top",
    val name: String = ""
)

@Serializable
data class StremioManifestBehaviorHints(
    val configurable: Boolean = false,
    val configurationRequired: Boolean = false,
    val configurationURL: String? = null,
    val adult: Boolean = false
)

@Serializable
data class StremioConfigField(
    val key: String = "",
    val type: String = "text",
    val title: String = "",
    val default: JsonElement? = null,
    val required: Boolean = false,
    val options: List<JsonElement> = emptyList()
)

@Serializable
data class StremioAddonManifest(
    val id: String = "",
    val name: String = "",
    val version: String = "1.0.0",
    val description: String = "",
    val logo: String? = null,
    val types: List<String> = emptyList(),
    val catalogs: List<StremioCatalogDescriptor> = emptyList(),
    val resources: List<JsonElement> = emptyList(),
    val behaviorHints: StremioManifestBehaviorHints? = null,
    val config: List<StremioConfigField> = emptyList()
)

@Serializable
data class StremioMetaPreview(
    val id: String = "",
    val type: String = "movie",
    val name: String = "",
    val poster: String? = null,
    val background: String? = null,
    val description: String? = null,
    val releaseInfo: String? = null,
    val imdbRating: String? = null,
    val genres: List<String> = emptyList()
)

@Serializable
data class StremioCatalogResponse(
    val metas: List<StremioMetaPreview> = emptyList()
)

@Serializable
data class StremioVideoEntry(
    val id: String = "",
    val title: String? = null,
    val name: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val thumbnail: String? = null,
    val overview: String? = null
)

@Serializable
data class StremioMetaDetail(
    val id: String = "",
    val type: String = "movie",
    val name: String = "",
    val poster: String? = null,
    val background: String? = null,
    val description: String? = null,
    val releaseInfo: String? = null,
    val runtime: String? = null,
    val imdbRating: String? = null,
    val genres: List<String> = emptyList(),
    val cast: List<String> = emptyList(),
    val videos: List<StremioVideoEntry> = emptyList()
)

@Serializable
data class StremioMetaResponse(
    val meta: StremioMetaDetail? = null
)

@Serializable
data class StremioSubtitleDto(
    val url: String = "",
    val lang: String = "und"
)

@Serializable
data class StremioProxyHeaders(
    val request: Map<String, String> = emptyMap()
)

@Serializable
data class StremioBehaviorHints(
    val proxyHeaders: StremioProxyHeaders? = null
)

@Serializable
data class StremioStreamDto(
    val name: String? = null,
    val title: String? = null,
    val description: String? = null,
    val url: String? = null,
    val subtitles: List<StremioSubtitleDto> = emptyList(),
    val behaviorHints: StremioBehaviorHints? = null
)

@Serializable
data class StremioStreamsResponse(
    val streams: List<StremioStreamDto> = emptyList()
)
