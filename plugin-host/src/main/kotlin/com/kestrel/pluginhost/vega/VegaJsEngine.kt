// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.pluginhost.vega

import com.hikari.app.net.Http
import com.kestrel.pluginhost.nuvio.NuvioJsEngine
import org.json.JSONObject
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
    private val nuvioJsEngine: NuvioJsEngine
) {
    suspend fun getCatalogDefinitions(
        pluginId: String,
        bundle: VegaBundle,
        timeoutMs: Long = 10_000L
    ): JSONObject {
        if (bundle.catalogJs.isBlank()) {
            return JSONObject().put("catalog", org.json.JSONArray())
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
            return JSONObject().put("episodes", org.json.JSONArray())
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
                  var s = globalThis.SCRAPER_SETTINGS || {};
                  return s[key] !== undefined ? s[key] : null;
                },
                set: async function() {},
                delete: async function() {},
                getAll: async function() { return globalThis.SCRAPER_SETTINGS || {}; }
              },
              getBaseUrl: async function(val) {
                try {
                  var res = await globalThis.fetch('https://himanshu8443.github.io/providers/modflix.json');
                  var j = await res.json();
                  var key = String(val || '');
                  var entry = j[key] || j[key.toLowerCase()];
                  if (!entry) {
                    var keys = Object.keys(j);
                    for (var i = 0; i < keys.length; i++) {
                      if (keys[i].toLowerCase() === key.toLowerCase()) { entry = j[keys[i]]; break; }
                    }
                  }
                  if (entry && entry.url) return String(entry.url).replace(/\/+$/, '');
                } catch (_) {}
                return '';
              },
              openWebView: async function(url) {
                return await __vegaOpenWebViewAsync(String(url || ''));
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
                "__vegaOpenWebViewAsync" to { args ->
                    val targetUrl = args.firstOrNull() as? String ?: ""
                    if (targetUrl.isBlank()) "" else Http.getStringRendered(targetUrl, 20_000L).orEmpty()
                }
            )
        )
    }
}
