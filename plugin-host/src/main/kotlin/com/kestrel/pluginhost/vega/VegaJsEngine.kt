// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.pluginhost.vega

import com.hikari.app.net.Http
import com.kestrel.pluginhost.nuvio.NuvioJsEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

data class VegaBundle(
    val value: String,
    val displayName: String,
    val distBaseUrl: String,
    val catalogJs: String,
    val postsJs: String,
    val metaJs: String,
    val episodesJs: String,
    val streamJs: String
) {
    companion object {
        fun parse(rawJson: String): VegaBundle {
            val obj = JSONObject(rawJson)
            return VegaBundle(
                value = obj.optString("value"),
                displayName = obj.optString("displayName"),
                distBaseUrl = obj.optString("distBaseUrl"),
                catalogJs = obj.optString("catalogJs"),
                postsJs = obj.optString("postsJs"),
                metaJs = obj.optString("metaJs"),
                episodesJs = obj.optString("episodesJs"),
                streamJs = obj.optString("streamJs")
            )
        }
    }
}

@Singleton
class VegaJsEngine @Inject constructor(
    private val nuvioJsEngine: NuvioJsEngine,
    private val okHttpClient: OkHttpClient
) {
    private data class CachedUrlMap(
        val fetchedAtMs: Long,
        val map: JSONObject?
    )

    private val urlMapCache = ConcurrentHashMap<String, CachedUrlMap>()
    private val urlMapMutex = Mutex()

    suspend fun getCatalogDefinitions(
        pluginId: String,
        bundle: VegaBundle,
        timeoutMs: Long = 10_000L
    ): JSONObject {
        if (bundle.catalogJs.isBlank()) {
            return JSONObject().put("catalog", JSONArray())
        }
        val callScript = """
            (async function() {
              try {
                var exp = (typeof module !== 'undefined' && module.exports) ? module.exports : exports;
                var cats = Array.isArray(exp.catalog) ? exp.catalog : (Array.isArray(globalThis.catalog) ? globalThis.catalog : []);
                var genres = Array.isArray(exp.genres) ? exp.genres : (Array.isArray(globalThis.genres) ? globalThis.genres : []);
                __nuvioResult({ catalog: cats, genres: genres });
              } catch (e) {
                __nuvioResult({ catalog: [], error: (e && e.message) ? String(e.message) : String(e) });
              }
            })();
        """.trimIndent()
        return evaluateVega(
            pluginId = pluginId,
            bundle = bundle,
            moduleTag = "${bundle.value}-catalog",
            moduleCode = bundle.catalogJs,
            callScript = callScript,
            timeoutMs = timeoutMs
        )
    }

    suspend fun getPosts(
        pluginId: String,
        bundle: VegaBundle,
        filter: String,
        page: Int,
        timeoutMs: Long = 18_000L
    ): JSONObject {
        val callScript = """
            (async function() {
              try {
                var exp = (typeof module !== 'undefined' && module.exports) ? module.exports : exports;
                var fn = exp.getPosts || (exp.default && exp.default.getPosts) || globalThis.getPosts;
                if (typeof fn !== 'function') {
                  __nuvioResult({ posts: [], error: 'Vega provider exports no getPosts()' });
                  return;
                }
                var ctrl = new globalThis.AbortController();
                var out = await fn({
                  filter: ${JSONObject.quote(filter)},
                  page: $page,
                  providerValue: ${JSONObject.quote(bundle.value)},
                  signal: ctrl.signal,
                  providerContext: globalThis.__vegaContext
                });
                __nuvioResult({ posts: Array.isArray(out) ? out : [] });
              } catch (e) {
                __nuvioResult({ posts: [], error: (e && e.message) ? String(e.message) : String(e) });
              }
            })();
        """.trimIndent()
        return evaluateVega(
            pluginId = pluginId,
            bundle = bundle,
            moduleTag = "${bundle.value}-posts",
            moduleCode = bundle.postsJs,
            callScript = callScript,
            timeoutMs = timeoutMs
        )
    }

    suspend fun getSearchPosts(
        pluginId: String,
        bundle: VegaBundle,
        searchQuery: String,
        page: Int,
        timeoutMs: Long = 20_000L
    ): JSONObject {
        val callScript = """
            (async function() {
              try {
                var exp = (typeof module !== 'undefined' && module.exports) ? module.exports : exports;
                var fn = exp.getSearchPosts || (exp.default && exp.default.getSearchPosts) || globalThis.getSearchPosts;
                if (typeof fn !== 'function') {
                  __nuvioResult({ posts: [], error: 'Vega provider exports no getSearchPosts()' });
                  return;
                }
                var ctrl = new globalThis.AbortController();
                var out = await fn({
                  searchQuery: ${JSONObject.quote(searchQuery)},
                  page: $page,
                  providerValue: ${JSONObject.quote(bundle.value)},
                  signal: ctrl.signal,
                  providerContext: globalThis.__vegaContext
                });
                __nuvioResult({ posts: Array.isArray(out) ? out : [] });
              } catch (e) {
                __nuvioResult({ posts: [], error: (e && e.message) ? String(e.message) : String(e) });
              }
            })();
        """.trimIndent()
        return evaluateVega(
            pluginId = pluginId,
            bundle = bundle,
            moduleTag = "${bundle.value}-search",
            moduleCode = bundle.postsJs,
            callScript = callScript,
            timeoutMs = timeoutMs
        )
    }

    suspend fun getMeta(
        pluginId: String,
        bundle: VegaBundle,
        link: String,
        timeoutMs: Long = 25_000L
    ): JSONObject {
        val callScript = """
            (async function() {
              try {
                var exp = (typeof module !== 'undefined' && module.exports) ? module.exports : exports;
                var fn = exp.getMeta || (exp.default && exp.default.getMeta) || globalThis.getMeta;
                if (typeof fn !== 'function') {
                  __nuvioResult({ meta: null, error: 'Vega provider exports no getMeta()' });
                  return;
                }
                var out = await fn({
                  link: ${JSONObject.quote(link)},
                  providerValue: ${JSONObject.quote(bundle.value)},
                  providerContext: globalThis.__vegaContext
                });
                __nuvioResult({ meta: out || null });
              } catch (e) {
                __nuvioResult({ meta: null, error: (e && e.message) ? String(e.message) : String(e) });
              }
            })();
        """.trimIndent()
        return evaluateVega(
            pluginId = pluginId,
            bundle = bundle,
            moduleTag = "${bundle.value}-meta",
            moduleCode = bundle.metaJs,
            callScript = callScript,
            timeoutMs = timeoutMs
        )
    }

    suspend fun getEpisodes(
        pluginId: String,
        bundle: VegaBundle,
        episodesUrl: String,
        timeoutMs: Long = 25_000L
    ): JSONObject {
        if (bundle.episodesJs.isBlank()) {
            return JSONObject().put("episodes", JSONArray())
        }
        val callScript = """
            (async function() {
              try {
                var exp = (typeof module !== 'undefined' && module.exports) ? module.exports : exports;
                var fn = exp.getEpisodes || (exp.default && exp.default.getEpisodes) || globalThis.getEpisodes;
                if (typeof fn !== 'function') {
                  __nuvioResult({ episodes: [] });
                  return;
                }
                var out = await fn({
                  url: ${JSONObject.quote(episodesUrl)},
                  providerValue: ${JSONObject.quote(bundle.value)},
                  providerContext: globalThis.__vegaContext
                });
                __nuvioResult({ episodes: Array.isArray(out) ? out : [] });
              } catch (e) {
                __nuvioResult({ episodes: [], error: (e && e.message) ? String(e.message) : String(e) });
              }
            })();
        """.trimIndent()
        return evaluateVega(
            pluginId = pluginId,
            bundle = bundle,
            moduleTag = "${bundle.value}-episodes",
            moduleCode = bundle.episodesJs,
            callScript = callScript,
            timeoutMs = timeoutMs
        )
    }

    suspend fun getStream(
        pluginId: String,
        bundle: VegaBundle,
        link: String,
        type: String,
        timeoutMs: Long = 35_000L
    ): JSONObject {
        val callScript = """
            (async function() {
              try {
                var exp = (typeof module !== 'undefined' && module.exports) ? module.exports : exports;
                var fn = exp.getStream || (exp.default && exp.default.getStream) || globalThis.getStream;
                if (typeof fn !== 'function') {
                  __nuvioResult({ streams: [], error: 'Vega provider exports no getStream()' });
                  return;
                }
                var ctrl = new globalThis.AbortController();
                var out = await fn({
                  link: ${JSONObject.quote(link)},
                  type: ${JSONObject.quote(type)},
                  signal: ctrl.signal,
                  providerValue: ${JSONObject.quote(bundle.value)},
                  providerContext: globalThis.__vegaContext,
                  isDownload: false
                });
                __nuvioResult({ streams: Array.isArray(out) ? out : [] });
              } catch (e) {
                __nuvioResult({ streams: [], error: (e && e.message) ? String(e.message) : String(e) });
              }
            })();
        """.trimIndent()
        return evaluateVega(
            pluginId = pluginId,
            bundle = bundle,
            moduleTag = "${bundle.value}-stream",
            moduleCode = bundle.streamJs,
            callScript = callScript,
            timeoutMs = timeoutMs
        )
    }

    private suspend fun resolveBaseUrl(distBaseUrl: String, key: String): String {
        val cleanKey = key.trim()
        if (cleanKey.isBlank()) return ""

        val candidates = buildList {
            if ("/dist" in distBaseUrl) {
                val repoRoot = distBaseUrl.substringBefore("/dist").trimEnd('/')
                if (repoRoot.startsWith("http")) {
                    add("$repoRoot/urls.json")
                }
            }
            add("https://raw.githubusercontent.com/Zenda-Cross/vega-providers/main/urls.json")
            add("https://raw.githubusercontent.com/DHR-Store/vega-providers/main/urls.json")
            add("https://cdn.jsdelivr.net/gh/Zenda-Cross/vega-providers@main/urls.json")
            add("https://cdn.jsdelivr.net/gh/DHR-Store/vega-providers@main/urls.json")
            add("https://himanshu8443.github.io/providers/modflix.json")
        }.distinct()

        for (mapUrl in candidates) {
            val mapObj = loadUrlMap(mapUrl) ?: continue
            val resolved = extractMappedUrl(mapObj, cleanKey)
            if (resolved.isNotBlank()) return resolved
        }
        return ""
    }

    private suspend fun loadUrlMap(mapUrl: String): JSONObject? {
        val now = System.currentTimeMillis()
        val cached = urlMapCache[mapUrl]
        if (cached != null) {
            val ttl = if (cached.map != null) POSITIVE_CACHE_TTL_MS else NEGATIVE_CACHE_TTL_MS
            if (now - cached.fetchedAtMs < ttl) {
                return cached.map
            }
        }
        return urlMapMutex.withLock {
            val insideNow = System.currentTimeMillis()
            val insideCached = urlMapCache[mapUrl]
            if (insideCached != null) {
                val ttl = if (insideCached.map != null) POSITIVE_CACHE_TTL_MS else NEGATIVE_CACHE_TTL_MS
                if (insideNow - insideCached.fetchedAtMs < ttl) {
                    return@withLock insideCached.map
                }
            }
            val fetched = withContext(Dispatchers.IO) {
                runCatching {
                    val client = okHttpClient.newBuilder()
                        .connectTimeout(6_000L, TimeUnit.MILLISECONDS)
                        .readTimeout(8_000L, TimeUnit.MILLISECONDS)
                        .callTimeout(10_000L, TimeUnit.MILLISECONDS)
                        .build()
                    val req = Request.Builder()
                        .url(mapUrl)
                        .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                        .build()
                    client.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) return@use null
                        val body = resp.body?.string()?.trim().orEmpty()
                        if (body.startsWith("{")) JSONObject(body) else null
                    }
                }.getOrNull()
            }
            urlMapCache[mapUrl] = CachedUrlMap(fetchedAtMs = System.currentTimeMillis(), map = fetched)
            fetched
        }
    }

    private fun extractMappedUrl(mapObj: JSONObject, key: String): String {
        var matchedKey: String? = when {
            mapObj.has(key) -> key
            mapObj.has(key.lowercase()) -> key.lowercase()
            else -> null
        }
        if (matchedKey == null) {
            val keys = mapObj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                if (k.equals(key, ignoreCase = true)) {
                    matchedKey = k
                    break
                }
            }
        }
        val k = matchedKey ?: return ""
        val rawVal = mapObj.opt(k)
        val rawUrl = when (rawVal) {
            is JSONObject -> rawVal.optString("url").ifBlank { rawVal.optString("domain") }
            is String -> rawVal
            else -> ""
        }.trim().trimEnd('/')
        return if (rawUrl.startsWith("http://", ignoreCase = true) || rawUrl.startsWith("https://", ignoreCase = true)) {
            rawUrl
        } else {
            ""
        }
    }

    private suspend fun evaluateVega(
        pluginId: String,
        bundle: VegaBundle,
        moduleTag: String,
        moduleCode: String,
        callScript: String,
        timeoutMs: Long
    ): JSONObject {
        val setupScript = """
            if (typeof globalThis.AbortController === 'undefined') {
              globalThis.AbortController = function() {
                this.signal = {
                  aborted: false,
                  addEventListener: function() {},
                  removeEventListener: function() {},
                  dispatchEvent: function() { return true; }
                };
                this.abort = function() { this.signal.aborted = true; };
              };
            }
            globalThis.__vegaKv = Object.assign({}, globalThis.SCRAPER_SETTINGS || {});
            globalThis.__vegaContext = {
              axios: globalThis.require('axios'),
              cheerio: globalThis.cheerio,
              Crypto: globalThis.CryptoJS,
              commonHeaders: Object.assign({
                'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36',
                'Accept': '*/*',
                'Accept-Language': 'en-US,en;q=0.9'
              }, globalThis.__kestrelCustomHeaders || {}),
              kvStore: {
                get: async function(key) {
                  return globalThis.__vegaKv[key] !== undefined ? globalThis.__vegaKv[key] : null;
                },
                set: async function(key, val) {
                  globalThis.__vegaKv[key] = val;
                },
                delete: async function(key) {
                  delete globalThis.__vegaKv[key];
                },
                getAll: async function() { return Object.assign({}, globalThis.__vegaKv); }
              },
              getBaseUrl: async function(val) {
                return await __vegaGetBaseUrlAsync(String(val || ''));
              },
              openWebView: async function(url, options) {
                try {
                  var raw = await __vegaOpenWebViewAsync(String(url || ''), JSON.stringify(options || {}));
                  var parsed = JSON.parse(raw || '{}');
                  return {
                    cookies: parsed.cookies || '',
                    userAgent: parsed.userAgent || '',
                    html: parsed.html || '',
                    url: parsed.url || String(url || '')
                  };
                } catch (_) {
                  return { cookies: '', userAgent: '', html: '', url: String(url || '') };
                }
              },
              extractors: {}
            };
        """.trimIndent()

        return nuvioJsEngine.evaluateCustomScript(
            pluginId = pluginId,
            scraperId = moduleTag,
            setupScript = setupScript,
            scriptCode = moduleCode,
            callScript = callScript,
            timeoutMs = timeoutMs,
            extraAsyncBindings = mapOf(
                "__vegaGetBaseUrlAsync" to { args ->
                    val providerKey = args.firstOrNull() as? String ?: ""
                    resolveBaseUrl(bundle.distBaseUrl, providerKey)
                },
                "__vegaOpenWebViewAsync" to { args ->
                    val targetUrl = args.getOrNull(0) as? String ?: ""
                    val optsJson = args.getOrNull(1) as? String ?: "{}"
                    if (targetUrl.isBlank()) {
                        JSONObject()
                            .put("cookies", "")
                            .put("userAgent", "")
                            .put("html", "")
                            .put("url", "")
                            .toString()
                    } else {
                        val optsObj = runCatching { JSONObject(optsJson) }.getOrNull()
                        val hdrObj = optsObj?.optJSONObject("headers")
                        val headers = LinkedHashMap<String, String>()
                        if (hdrObj != null) {
                            val keys = hdrObj.keys()
                            while (keys.hasNext()) {
                                val k = keys.next()
                                val v = hdrObj.optString(k)
                                if (k.isNotBlank() && v.isNotBlank()) headers[k] = v
                            }
                        }
                        val rendered = Http.renderPageForWaf(
                            url = targetUrl,
                            headers = headers,
                            timeoutMs = 22_000L
                        )
                        JSONObject()
                            .put("cookies", rendered.cookies)
                            .put("userAgent", rendered.userAgent)
                            .put("html", rendered.html)
                            .put("url", rendered.finalUrl)
                            .toString()
                    }
                }
            )
        )
    }

    companion object {
        private const val POSITIVE_CACHE_TTL_MS = 10L * 60L * 1000L
        private const val NEGATIVE_CACHE_TTL_MS = 2L * 60L * 1000L
    }
}
