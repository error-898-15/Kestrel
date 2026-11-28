// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.pluginhost.aniyomi

import android.content.Context
import com.kestrel.core.di.IoDispatcher
import com.kestrel.domain.model.MangaChapter
import com.kestrel.domain.model.MangaDetails
import com.kestrel.domain.model.MangaExtensionMetadata
import com.kestrel.domain.model.MangaPage
import com.kestrel.domain.model.MangaSummary
import com.kestrel.domain.provider.MangaSourceProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import dalvik.system.PathClassLoader
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Loads installed Aniyomi / Tachiyomi extension APKs via isolated PathClassLoader
 * and bridges CatalogueSource / HttpSource / SourceFactory instances into
 * Kestrel's [MangaSourceProvider] contract.
 */
@Singleton
class AniyomiExtensionLoader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    private val loadedProviders = ConcurrentHashMap<String, List<MangaSourceProvider>>()

    fun getProvidersForExtension(extensionId: String): List<MangaSourceProvider> =
        loadedProviders[extensionId].orEmpty()

    fun getAllProviders(): List<MangaSourceProvider> =
        loadedProviders.values.flatten()

    fun findProvider(extensionId: String, sourceId: String): MangaSourceProvider? =
        loadedProviders[extensionId]?.firstOrNull { it.sourceId == sourceId }
            ?: loadedProviders[extensionId]?.firstOrNull()

    suspend fun loadExtension(metadata: MangaExtensionMetadata): Result<List<MangaSourceProvider>> =
        withContext(ioDispatcher) {
            runCatching {
                val apkFile = metadata.localApkPath?.let { File(it) }?.takeIf { it.exists() }
                val dexProviders = if (apkFile != null) {
                    runCatching { loadSourcesFromApk(metadata, apkFile) }.getOrDefault(emptyList())
                } else {
                    emptyList()
                }
                val resolvedProviders = dexProviders.ifEmpty {
                    // Fallback bridge using declared source descriptors / MangaDex-compatible catalog
                    val descriptors = metadata.sources.ifEmpty {
                        listOf(
                            com.kestrel.domain.model.MangaSourceDescriptor(
                                id = metadata.id,
                                name = metadata.name,
                                lang = metadata.lang,
                                baseUrl = ""
                            )
                        )
                    }
                    descriptors.map { desc ->
                        WebFallbackMangaProvider(
                            sourceId = desc.id,
                            extensionId = metadata.id,
                            name = desc.name,
                            lang = desc.lang,
                            baseUrl = desc.baseUrl,
                            okHttpClient = okHttpClient,
                            ioDispatcher = ioDispatcher
                        )
                    }
                }
                loadedProviders[metadata.id] = resolvedProviders
                resolvedProviders
            }
        }

    fun unloadExtension(extensionId: String) {
        loadedProviders.remove(extensionId)
    }

    private fun loadSourcesFromApk(
        metadata: MangaExtensionMetadata,
        apkFile: File
    ): List<MangaSourceProvider> {
        apkFile.setReadOnly()
        val classLoader = PathClassLoader(apkFile.absolutePath, context.classLoader)
        val pm = context.packageManager
        val pkgInfo = pm.getPackageArchiveInfo(
            apkFile.absolutePath,
            android.content.pm.PackageManager.GET_META_DATA
        )
        val mainClassName = pkgInfo?.applicationInfo?.metaData
            ?.getString("tachiyomi.extension.class")
            ?.let { if (it.startsWith(".")) "${metadata.packageName}$it" else it }
            ?: return emptyList()

        val clazz = classLoader.loadClass(mainClassName)
        val instance = clazz.getDeclaredConstructor().apply { isAccessible = true }.newInstance()

        // Supports eu.kanade.tachiyomi.source.SourceFactory (getSources()) and direct HttpSource
        val sourceList: List<Any> = runCatching {
            val method = clazz.getMethod("getSources")
            @Suppress("UNCHECKED_CAST")
            method.invoke(instance) as? List<Any> ?: emptyList()
        }.getOrElse { listOf(instance) }

        return sourceList.mapIndexed { idx, src ->
            ReflectiveAniyomiSourceProvider(
                sourceObj = src,
                extensionId = metadata.id,
                fallbackId = "${metadata.id}_$idx",
                fallbackName = metadata.name,
                fallbackLang = metadata.lang,
                ioDispatcher = ioDispatcher
            )
        }
    }
}

private class ReflectiveAniyomiSourceProvider(
    private val sourceObj: Any,
    override val extensionId: String,
    fallbackId: String,
    fallbackName: String,
    fallbackLang: String,
    private val ioDispatcher: CoroutineDispatcher
) : MangaSourceProvider {
    private val cls = sourceObj.javaClass

    override val sourceId: String = runCatching {
        cls.getMethod("getId").invoke(sourceObj)?.toString()
    }.getOrNull() ?: fallbackId

    override val name: String = runCatching {
        cls.getMethod("getName").invoke(sourceObj)?.toString()
    }.getOrNull() ?: fallbackName

    override val lang: String = runCatching {
        cls.getMethod("getLang").invoke(sourceObj)?.toString()
    }.getOrNull() ?: fallbackLang

    override val baseUrl: String = runCatching {
        cls.getMethod("getBaseUrl").invoke(sourceObj)?.toString()
    }.getOrNull().orEmpty()

    override suspend fun getPopular(page: Int): Result<List<MangaSummary>> = withContext(ioDispatcher) {
        runCatching {
            val method = cls.getMethod("fetchPopularManga", Int::class.javaPrimitiveType)
            val obs = method.invoke(sourceObj, page)
            extractMangaList(obs)
        }
    }

    override suspend fun getLatest(page: Int): Result<List<MangaSummary>> = withContext(ioDispatcher) {
        runCatching {
            val method = cls.getMethod("fetchLatestUpdates", Int::class.javaPrimitiveType)
            val obs = method.invoke(sourceObj, page)
            extractMangaList(obs)
        }
    }

    override suspend fun search(query: String, page: Int): Result<List<MangaSummary>> = withContext(ioDispatcher) {
        runCatching {
            val filterListClass = cls.classLoader?.loadClass("eu.kanade.tachiyomi.source.model.FilterList")
            val emptyFilters = filterListClass?.getDeclaredConstructor()?.newInstance()
            val method = cls.methods.first { it.name == "fetchSearchManga" && it.parameterTypes.size == 3 }
            val obs = method.invoke(sourceObj, page, query, emptyFilters)
            extractMangaList(obs)
        }
    }

    override suspend fun getDetails(manga: MangaSummary): Result<MangaDetails> = withContext(ioDispatcher) {
        runCatching {
            val sManga = buildSManga(manga)
            val detailMethod = cls.methods.first { it.name == "fetchMangaDetails" }
            val obs = detailMethod.invoke(sourceObj, sManga)
            val resolved = awaitRxSingleOrFirst(obs) ?: sManga
            val chapters = getChapters(manga).getOrDefault(emptyList())
            MangaDetails(
                summary = manga.copy(
                    author = readProp(resolved, "getAuthor") ?: manga.author,
                    coverUrl = readProp(resolved, "getThumbnail_url") ?: manga.coverUrl
                ),
                artist = readProp(resolved, "getArtist"),
                description = readProp(resolved, "getDescription"),
                genres = readProp(resolved, "getGenre")
                    ?.split(',')
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    .orEmpty(),
                chapters = chapters
            )
        }
    }

    override suspend fun getChapters(manga: MangaSummary): Result<List<MangaChapter>> = withContext(ioDispatcher) {
        runCatching {
            val sManga = buildSManga(manga)
            val method = cls.methods.first { it.name == "fetchChapterList" }
            val obs = method.invoke(sourceObj, sManga)
            val list = awaitRxSingleOrFirst(obs) as? List<*> ?: emptyList<Any>()
            list.mapIndexedNotNull { idx, item ->
                if (item == null) return@mapIndexedNotNull null
                val url = readProp(item, "getUrl").orEmpty()
                val title = readProp(item, "getName") ?: "Chapter ${idx + 1}"
                val chNum = runCatching {
                    item.javaClass.getMethod("getChapter_number").invoke(item) as? Float
                }.getOrNull() ?: idx.toFloat()
                MangaChapter(
                    id = "$sourceId:$url",
                    name = title,
                    url = url,
                    chapterNumber = chNum,
                    scanlator = readProp(item, "getScanlator")
                )
            }
        }
    }

    override suspend fun getPages(chapter: MangaChapter): Result<List<MangaPage>> = withContext(ioDispatcher) {
        runCatching {
            val sChapterCls = cls.classLoader!!.loadClass("eu.kanade.tachiyomi.source.model.SChapter")
            val createMethod = sChapterCls.getMethod("create")
            val sChapter = createMethod.invoke(null)
            sChapterCls.getMethod("setUrl", String::class.java).invoke(sChapter, chapter.url)
            sChapterCls.getMethod("setName", String::class.java).invoke(sChapter, chapter.name)

            val method = cls.methods.first { it.name == "fetchPageList" }
            val obs = method.invoke(sourceObj, sChapter)
            val list = awaitRxSingleOrFirst(obs) as? List<*> ?: emptyList<Any>()
            list.mapIndexedNotNull { idx, p ->
                if (p == null) return@mapIndexedNotNull null
                val imgUrl = readProp(p, "getImageUrl") ?: readProp(p, "getUrl") ?: return@mapIndexedNotNull null
                MangaPage(index = idx, imageUrl = imgUrl)
            }
        }
    }

    private fun extractMangaList(obs: Any?): List<MangaSummary> {
        val pageObj = awaitRxSingleOrFirst(obs) ?: return emptyList()
        val mangas = runCatching {
            pageObj.javaClass.getMethod("getMangas").invoke(pageObj) as? List<*>
        }.getOrNull() ?: return emptyList()
        return mangas.mapNotNull { item ->
            if (item == null) return@mapNotNull null
            val url = readProp(item, "getUrl") ?: return@mapNotNull null
            val title = readProp(item, "getTitle") ?: "Untitled"
            MangaSummary(
                id = "$sourceId:$url",
                extensionId = extensionId,
                sourceId = sourceId,
                sourceName = name,
                title = title,
                url = url,
                coverUrl = readProp(item, "getThumbnail_url"),
                author = readProp(item, "getAuthor")
            )
        }
    }

    private fun buildSManga(summary: MangaSummary): Any {
        val sMangaCls = cls.classLoader!!.loadClass("eu.kanade.tachiyomi.source.model.SManga")
        val sManga = sMangaCls.getMethod("create").invoke(null)
        sMangaCls.getMethod("setUrl", String::class.java).invoke(sManga, summary.url)
        sMangaCls.getMethod("setTitle", String::class.java).invoke(sManga, summary.title)
        return sManga
    }

    private fun awaitRxSingleOrFirst(obs: Any?): Any? {
        if (obs == null) return null
        return runCatching {
            val toBlocking = obs.javaClass.getMethod("toBlocking")
            val blocking = toBlocking.invoke(obs)
            blocking.javaClass.getMethod("first").invoke(blocking)
        }.getOrNull()
    }

    private fun readProp(target: Any, getter: String): String? =
        runCatching { target.javaClass.getMethod(getter).invoke(target)?.toString() }.getOrNull()
}

private class WebFallbackMangaProvider(
    override val sourceId: String,
    override val extensionId: String,
    override val name: String,
    override val lang: String,
    override val baseUrl: String,
    private val okHttpClient: OkHttpClient,
    private val ioDispatcher: CoroutineDispatcher
) : MangaSourceProvider {

    override suspend fun getPopular(page: Int): Result<List<MangaSummary>> = search("", page)
    override suspend fun getLatest(page: Int): Result<List<MangaSummary>> = search("", page)

    private fun httpGet(url: String): String {
        val req = Request.Builder().url(url).header("User-Agent", "Kestrel/1.0").build()
        return okHttpClient.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("HTTP ${resp.code} from ${resp.request.url.host}")
            body
        }
    }

    private fun pickLocalized(obj: JSONObject?): String {
        if (obj == null) return ""
        val en = obj.optString("en")
        if (en.isNotBlank()) return en
        val firstKey = obj.keys().asSequence().firstOrNull() ?: return ""
        return obj.optString(firstKey)
    }

    private fun coverUrlFor(mangaId: String, relationships: JSONArray?): String? {
        if (relationships == null) return null
        for (r in 0 until relationships.length()) {
            val rel = relationships.optJSONObject(r) ?: continue
            if (rel.optString("type") == "cover_art") {
                val file = rel.optJSONObject("attributes")?.optString("fileName").orEmpty()
                if (file.isNotBlank()) return "https://uploads.mangadex.org/covers/$mangaId/$file.512.jpg"
            }
        }
        return null
    }

    private fun relationName(relationships: JSONArray?, type: String): String? {
        if (relationships == null) return null
        for (r in 0 until relationships.length()) {
            val rel = relationships.optJSONObject(r) ?: continue
            if (rel.optString("type") == type) {
                val n = rel.optJSONObject("attributes")?.optString("name").orEmpty()
                if (n.isNotBlank()) return n
            }
        }
        return null
    }

    override suspend fun search(query: String, page: Int): Result<List<MangaSummary>> =
        withContext(ioDispatcher) {
            runCatching {
                val enc = URLEncoder.encode(query, "UTF-8")
                val offset = (page - 1).coerceAtLeast(0) * 20
                val url = buildString {
                    append("https://api.mangadex.org/manga?limit=20&offset=$offset")
                    append("&includes[]=cover_art&contentRating[]=safe&contentRating[]=suggestive&hasAvailableChapters=true")
                    if (query.isNotBlank()) append("&title=$enc") else append("&order[followedCount]=desc")
                }
                val data = JSONObject(httpGet(url)).optJSONArray("data") ?: JSONArray()
                buildList {
                    for (i in 0 until data.length()) {
                        val m = data.optJSONObject(i) ?: continue
                        val id = m.optString("id")
                        if (id.isBlank()) continue
                        val attrs = m.optJSONObject("attributes") ?: continue
                        val title = pickLocalized(attrs.optJSONObject("title"))
                        add(
                            MangaSummary(
                                id = id,
                                extensionId = extensionId,
                                sourceId = sourceId,
                                sourceName = name,
                                title = title.ifBlank { "Untitled" },
                                url = id,
                                coverUrl = coverUrlFor(id, m.optJSONArray("relationships")),
                                status = attrs.optString("status").ifBlank { null }
                            )
                        )
                    }
                }
            }
        }

    override suspend fun getDetails(manga: MangaSummary): Result<MangaDetails> =
        withContext(ioDispatcher) {
            runCatching {
                val body = httpGet(
                    "https://api.mangadex.org/manga/${manga.url}?includes[]=cover_art&includes[]=author&includes[]=artist"
                )
                val data = JSONObject(body).getJSONObject("data")
                val attrs = data.getJSONObject("attributes")
                val rels = data.optJSONArray("relationships")
                val tags = attrs.optJSONArray("tags") ?: JSONArray()
                val genres = buildList {
                    for (i in 0 until tags.length()) {
                        val tagName = pickLocalized(
                            tags.optJSONObject(i)?.optJSONObject("attributes")?.optJSONObject("name")
                        )
                        if (tagName.isNotBlank()) add(tagName)
                    }
                }
                val resolved = manga.copy(
                    title = pickLocalized(attrs.optJSONObject("title")).ifBlank { manga.title },
                    coverUrl = coverUrlFor(manga.url, rels) ?: manga.coverUrl,
                    author = relationName(rels, "author") ?: manga.author,
                    status = attrs.optString("status").ifBlank { manga.status }
                )
                val chapters = getChapters(resolved).getOrDefault(emptyList())
                MangaDetails(
                    summary = resolved,
                    artist = relationName(rels, "artist"),
                    description = pickLocalized(attrs.optJSONObject("description")).ifBlank { null },
                    genres = genres,
                    chapters = chapters
                )
            }
        }

    override suspend fun getChapters(manga: MangaSummary): Result<List<MangaChapter>> =
        withContext(ioDispatcher) {
            runCatching {
                val url = "https://api.mangadex.org/manga/${manga.url}/feed" +
                    "?limit=500&translatedLanguage[]=en&order[chapter]=asc"
                val data = JSONObject(httpGet(url)).optJSONArray("data") ?: JSONArray()
                val seen = HashSet<String>()
                buildList {
                    for (i in 0 until data.length()) {
                        val c = data.optJSONObject(i) ?: continue
                        val id = c.optString("id")
                        if (id.isBlank() || !seen.add(id)) continue
                        val attrs = c.optJSONObject("attributes") ?: continue
                        if (!attrs.isNull("externalUrl") && attrs.optString("externalUrl").isNotBlank()) continue
                        if (attrs.optInt("pages", 1) == 0) continue
                        val chNum = attrs.optString("chapter").ifBlank { "${i + 1}" }
                        val chTitle = attrs.optString("title").takeIf { it.isNotBlank() && it != "null" }
                        val displayTitle = manga.title.ifBlank { "Chapter" }
                        add(
                            MangaChapter(
                                id = id,
                                name = if (chTitle != null) {
                                    "$displayTitle, Ch. $chNum · $chTitle"
                                } else {
                                    "$displayTitle, Chapter $chNum"
                                },
                                url = id,
                                chapterNumber = chNum.toFloatOrNull() ?: i.toFloat()
                            )
                        )
                    }
                }
            }
        }

    override suspend fun getPages(chapter: MangaChapter): Result<List<MangaPage>> =
        withContext(ioDispatcher) {
            runCatching {
                val json = JSONObject(httpGet("https://api.mangadex.org/at-home/server/${chapter.url}"))
                val base = json.getString("baseUrl")
                val ch = json.getJSONObject("chapter")
                val hash = ch.getString("hash")
                val files = ch.optJSONArray("data") ?: JSONArray()
                buildList {
                    for (i in 0 until files.length()) {
                        add(
                            MangaPage(
                                index = i,
                                imageUrl = "$base/data/$hash/${files.getString(i)}",
                                headers = mapOf("Referer" to "https://mangadex.org/")
                            )
                        )
                    }
                }
            }
        }
}
