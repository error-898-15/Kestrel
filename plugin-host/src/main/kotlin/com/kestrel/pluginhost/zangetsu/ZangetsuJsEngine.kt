// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.pluginhost.zangetsu

import com.kestrel.pluginhost.nuvio.NuvioJsEngine
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ZangetsuJsEngine @Inject constructor(
    private val nuvioJsEngine: NuvioJsEngine
) {
    private fun baseSetup(sourceId: String): String = """
        globalThis.__SOURCE_ID = ${JSONObject.quote(sourceId)};
        var __SOURCE_ID = ${JSONObject.quote(sourceId)};
    """.trimIndent()

    suspend fun getHome(
        pluginId: String,
        sourceId: String,
        scriptCode: String,
        page: Int,
        timeoutMs: Long = 22_000L
    ): JSONObject {
        val callScript = """
            (async function() {
              try {
                if (typeof getHome !== 'function') {
                  __nuvioResult({ rows: [], error: 'Zangetsu source defines no getHome()' });
                  return;
                }
                var opts = { page: $page };
                var homeOut = await Promise.resolve(getHome(opts));
                var rows = Array.isArray(homeOut) ? homeOut : (homeOut ? [homeOut] : []);
                if ($page > 1 && typeof getHomePage === 'function') {
                  var pagedRows = [];
                  for (var i = 0; i < rows.length; i++) {
                    var r = rows[i];
                    if (!r || !r.id) continue;
                    try {
                      var pageItems = await Promise.resolve(getHomePage(r.id, $page, opts));
                      if (Array.isArray(pageItems) && pageItems.length > 0) {
                        pagedRows.push({ id: r.id, title: r.title || r.id, items: pageItems });
                      }
                    } catch (_) {}
                  }
                  rows = pagedRows;
                } else if ($page > 1) {
                  rows = [];
                }
                __nuvioResult({ rows: rows });
              } catch (e) {
                __nuvioResult({ rows: [], error: (e && e.message) ? String(e.message) : String(e) });
              }
            })();
        """.trimIndent()

        return nuvioJsEngine.evaluateCustomScript(
            pluginId = pluginId,
            scraperId = "zangetsu-$sourceId",
            setupScript = baseSetup(sourceId),
            scriptCode = scriptCode,
            callScript = callScript,
            timeoutMs = timeoutMs
        )
    }

    suspend fun search(
        pluginId: String,
        sourceId: String,
        scriptCode: String,
        query: String,
        page: Int,
        timeoutMs: Long = 22_000L
    ): JSONObject {
        val callScript = """
            (async function() {
              try {
                if (typeof search !== 'function') {
                  __nuvioResult({ items: [], error: 'Zangetsu source defines no search()' });
                  return;
                }
                var out = await Promise.resolve(search(${JSONObject.quote(query)}, $page, { page: $page }));
                var items = Array.isArray(out) ? out : (out && Array.isArray(out.items) ? out.items : []);
                __nuvioResult({ items: items });
              } catch (e) {
                __nuvioResult({ items: [], error: (e && e.message) ? String(e.message) : String(e) });
              }
            })();
        """.trimIndent()

        return nuvioJsEngine.evaluateCustomScript(
            pluginId = pluginId,
            scraperId = "zangetsu-$sourceId",
            setupScript = baseSetup(sourceId),
            scriptCode = scriptCode,
            callScript = callScript,
            timeoutMs = timeoutMs
        )
    }

    suspend fun getDetail(
        pluginId: String,
        sourceId: String,
        scriptCode: String,
        url: String,
        timeoutMs: Long = 25_000L
    ): JSONObject {
        val callScript = """
            (async function() {
              try {
                if (typeof getDetail !== 'function') {
                  __nuvioResult({ detail: null, error: 'Zangetsu source defines no getDetail()' });
                  return;
                }
                var detail = await Promise.resolve(getDetail(${JSONObject.quote(url)}, {}));
                if (detail && (!Array.isArray(detail.episodes) || detail.episodes.length === 0) && typeof getEpisodes === 'function') {
                  try {
                    var eps = await Promise.resolve(getEpisodes(${JSONObject.quote(url)}, {}));
                    if (Array.isArray(eps)) detail.episodes = eps;
                  } catch (_) {}
                }
                __nuvioResult({ detail: detail || null });
              } catch (e) {
                __nuvioResult({ detail: null, error: (e && e.message) ? String(e.message) : String(e) });
              }
            })();
        """.trimIndent()

        return nuvioJsEngine.evaluateCustomScript(
            pluginId = pluginId,
            scraperId = "zangetsu-$sourceId",
            setupScript = baseSetup(sourceId),
            scriptCode = scriptCode,
            callScript = callScript,
            timeoutMs = timeoutMs
        )
    }

    suspend fun getVideoSources(
        pluginId: String,
        sourceId: String,
        scriptCode: String,
        episodeUrl: String,
        timeoutMs: Long = 35_000L
    ): JSONObject {
        val callScript = """
            (async function() {
              try {
                if (typeof getVideoSources !== 'function') {
                  __nuvioResult({ sources: [], error: 'Zangetsu source defines no getVideoSources()' });
                  return;
                }
                var out = await Promise.resolve(getVideoSources(${JSONObject.quote(episodeUrl)}));
                var sources = Array.isArray(out) ? out : (out && Array.isArray(out.sources) ? out.sources : []);
                __nuvioResult({ sources: sources });
              } catch (e) {
                __nuvioResult({ sources: [], error: (e && e.message) ? String(e.message) : String(e) });
              }
            })();
        """.trimIndent()

        return nuvioJsEngine.evaluateCustomScript(
            pluginId = pluginId,
            scraperId = "zangetsu-$sourceId",
            setupScript = baseSetup(sourceId),
            scriptCode = scriptCode,
            callScript = callScript,
            timeoutMs = timeoutMs
        )
    }
}
