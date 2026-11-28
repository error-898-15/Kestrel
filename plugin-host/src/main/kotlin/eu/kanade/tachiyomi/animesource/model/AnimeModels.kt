@file:Suppress("unused", "PropertyName", "JavaDefaultMethodsNotOverriddenByDelegation")

package eu.kanade.tachiyomi.animesource.model

import android.net.Uri
import kotlinx.serialization.json.JsonObject
import okhttp3.Headers

enum class AnimeUpdateStrategy {
    ALWAYS_UPDATE,
    ONLY_FETCH_ONCE,
}

interface SAnime {
    var url: String
    var title: String
    var artist: String?
    var author: String?
    var description: String?
    var genre: String?
    var status: Int
    var thumbnail_url: String?
    var update_strategy: AnimeUpdateStrategy
    var initialized: Boolean
    var memo: JsonObject

    fun copyFrom(other: SAnime) {
        if (other.url.isNotBlank()) url = other.url
        if (other.title.isNotBlank()) title = other.title
        other.artist?.let { artist = it }
        other.author?.let { author = it }
        other.description?.let { description = it }
        other.genre?.let { genre = it }
        if (other.status != UNKNOWN) status = other.status
        other.thumbnail_url?.let { thumbnail_url = it }
        initialized = other.initialized
    }

    companion object {
        const val UNKNOWN = 0
        const val ONGOING = 1
        const val COMPLETED = 2
        const val LICENSED = 3
        const val PUBLISHING_FINISHED = 4
        const val CANCELLED = 5
        const val ON_HIATUS = 6

        fun create(): SAnime = SAnimeImpl()
    }
}

class SAnimeImpl : SAnime {
    override var url: String = ""
    override var title: String = ""
    override var artist: String? = null
    override var author: String? = null
    override var description: String? = null
    override var genre: String? = null
    override var status: Int = SAnime.UNKNOWN
    override var thumbnail_url: String? = null
    override var update_strategy: AnimeUpdateStrategy = AnimeUpdateStrategy.ALWAYS_UPDATE
    override var initialized: Boolean = false
    override var memo: JsonObject = JsonObject(emptyMap())
}

interface SEpisode {
    var url: String
    var name: String
    var date_upload: Long
    var episode_number: Float
    var scanlator: String?
    var memo: JsonObject

    fun copyFrom(other: SEpisode) {
        if (other.url.isNotBlank()) url = other.url
        if (other.name.isNotBlank()) name = other.name
        if (other.date_upload != 0L) date_upload = other.date_upload
        if (other.episode_number >= 0f) episode_number = other.episode_number
        other.scanlator?.let { scanlator = it }
    }

    companion object {
        fun create(): SEpisode = SEpisodeImpl()
    }
}

class SEpisodeImpl : SEpisode {
    override var url: String = ""
    override var name: String = ""
    override var date_upload: Long = 0L
    override var episode_number: Float = -1f
    override var scanlator: String? = null
    override var memo: JsonObject = JsonObject(emptyMap())
}

data class AnimesPage(
    val animes: List<SAnime>,
    val hasNextPage: Boolean
)

data class Track @JvmOverloads constructor(
    val url: String,
    val lang: String = ""
)

open class Video @JvmOverloads constructor(
    val url: String = "",
    val quality: String = "",
    var videoUrl: String? = url,
    val headers: Headers? = null,
    val subtitleTracks: List<Track> = emptyList(),
    val audioTracks: List<Track> = emptyList(),
    val internalData: String = ""
) {
    constructor(
        url: String,
        quality: String,
        videoUrl: String?,
        uri: Uri?,
        headers: Headers?
    ) : this(
        url = url,
        quality = quality,
        videoUrl = videoUrl ?: uri?.toString(),
        headers = headers
    )

    constructor(
        url: String,
        quality: String,
        videoUrl: String?,
        headers: Headers?,
        subtitleTracks: List<Track>
    ) : this(
        url = url,
        quality = quality,
        videoUrl = videoUrl,
        headers = headers,
        subtitleTracks = subtitleTracks,
        audioTracks = emptyList()
    )

    var status: State = State.QUEUE
    var initialized: Boolean = false

    enum class State {
        QUEUE,
        LOAD_VIDEO,
        READY,
        ERROR
    }
}

sealed class AnimeFilter<T>(val name: String, var state: T) {
    open class Header(name: String) : AnimeFilter<Any>(name, 0)
    open class Separator(name: String = "") : AnimeFilter<Any>(name, 0)
    abstract class Select<V>(name: String, val values: Array<V>, state: Int = 0) : AnimeFilter<Int>(name, state)
    abstract class Text(name: String, state: String = "") : AnimeFilter<String>(name, state)
    abstract class CheckBox(name: String, state: Boolean = false) : AnimeFilter<Boolean>(name, state)
    abstract class TriState(name: String, state: Int = STATE_IGNORE) : AnimeFilter<Int>(name, state) {
        fun isIgnored() = state == STATE_IGNORE
        fun isIncluded() = state == STATE_INCLUDE
        fun isExcluded() = state == STATE_EXCLUDE

        companion object {
            const val STATE_IGNORE = 0
            const val STATE_INCLUDE = 1
            const val STATE_EXCLUDE = 2
        }
    }

    abstract class Group<V>(name: String, state: List<V>) : AnimeFilter<List<V>>(name, state)
    abstract class Sort(
        name: String,
        val values: Array<String>,
        state: Selection? = null,
    ) : AnimeFilter<Sort.Selection?>(name, state) {
        data class Selection(val index: Int, val ascending: Boolean)
    }
}

data class AnimeFilterList(val list: List<AnimeFilter<*>>) : List<AnimeFilter<*>> by list {
    constructor(vararg fs: AnimeFilter<*>) : this(if (fs.isNotEmpty()) fs.asList() else emptyList())
}
