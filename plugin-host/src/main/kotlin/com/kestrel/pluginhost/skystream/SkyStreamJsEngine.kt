// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.pluginhost.skystream

import com.kestrel.pluginhost.nuvio.NuvioJsEngine
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton

data class SkyArchivePayload(
    val manifestJson: String,
    val scriptCode: String
)

@Singleton
class SkyStreamJsEngine @Inject constructor(
    private val nuvioJsEngine: NuvioJsEngine
) {
    fun readArchive(archiveFile: File): SkyArchivePayload {
        ZipFile(archiveFile).use { zip ->
            val manifestJson = zip.getEntry("plugin.json")?.let { entry ->
                zip.getInputStream(entry).bufferedReader().use { it.readText() }
            } ?: "{}"
            val jsEntry = zip.getEntry("plugin.js")
                ?: error("Missing plugin.js in ${archiveFile.name}")
            val scriptCode = zip.getInputStream(jsEntry).bufferedReader().use { it.readText() }
            return SkyArchivePayload(manifestJson = manifestJson, scriptCode = scriptCode)
        }
    }

    private fun buildSetupScript(manifestJson: String): String {
        val safeManifest = manifestJson.trim().ifBlank { "{}" }
        return """
            globalThis.manifest = $safeManifest;
            globalThis.MultimediaItem = function(data) { Object.assign(this, data || {}); };
            globalThis.Episode = function(data) { Object.assign(this, data || {}); };
            globalThis.StreamResult = function(data) { Object.assign(this, data || {}); };
            globalThis.Actor = function(data) { Object.assign(this, data || {}); };
            globalThis.Trailer = function(data) { Object.assign(this, data || {}); };

            globalThis.__skyHeadersToObj = function(headers) {
              var out = {};
              if (headers && typeof headers.forEach === 'function') {
                headers.forEach(function(v, k) { out[k] = v; });
              }
              return out;
            };

            globalThis.http_get = async function(url, headers) {
              try {
                var mergedHeaders = Object.assign({}, globalThis.__kestrelCustomHeaders || {}, headers || {});
                var resp = await globalThis.fetch(String(url), { method: 'GET', headers: mergedHeaders });
                var body = await resp.text();
                return {
                  status: resp.status,
                  ok: resp.ok,
                  url: resp.url || String(url),
                  headers: globalThis.__skyHeadersToObj(resp.headers),
                  body: body
                };
              } catch (e) {
                return { status: 0, ok: false, url: String(url), headers: {}, body: '', error: String(e && e.message || e) };
              }
            };

            globalThis.http_post = async function(url, arg2, arg3) {
              try {
                var headers = {};
                var body = '';
                if (typeof arg2 === 'string') {
                  body = arg2;
                  headers = (arg3 && typeof arg3 === 'object') ? arg3 : {};
                } else if (arg2 && typeof arg2 === 'object') {
                  headers = arg2;
                  if (typeof arg3 === 'string') body = arg3;
                  else if (arg3 !== undefined && arg3 !== null) body = JSON.stringify(arg3);
                }
                var mergedHeaders = Object.assign({}, globalThis.__kestrelCustomHeaders || {}, headers || {});
                var resp = await globalThis.fetch(String(url), { method: 'POST', headers: mergedHeaders, body: body });
                var respBody = await resp.text();
                return {
                  status: resp.status,
                  ok: resp.ok,
                  url: resp.url || String(url),
                  headers: globalThis.__skyHeadersToObj(resp.headers),
                  body: respBody
                };
              } catch (e) {
                return { status: 0, ok: false, url: String(url), headers: {}, body: '', error: String(e && e.message || e) };
              }
            };

            globalThis.http_parallel = async function(requests) {
              var list = Array.isArray(requests) ? requests : [];
              return Promise.all(list.map(async function(req) {
                if (typeof req === 'string') {
                  return await globalThis.http_get(req, {});
                }
                var targetUrl = String((req && req.url) || '');
                var method = String((req && req.method) || 'GET').toUpperCase();
                var res = method === 'POST'
                  ? await globalThis.http_post(targetUrl, req.headers || {}, req.body || '')
                  : await globalThis.http_get(targetUrl, req.headers || {});
                if (req && req.meta !== undefined) res.meta = req.meta;
                return res;
              }));
            };

            globalThis.__invokeSkyMethod = function(fnName, argList) {
              return new Promise(function(resolve) {
                try {
                  var fn = globalThis[fnName];
                  if (typeof fn !== 'function') {
                    resolve({ success: false, message: 'SkyStream method ' + fnName + ' is not defined' });
                    return;
                  }
                  var settled = false;
                  var done = function(payload) {
                    if (settled) return;
                    settled = true;
                    resolve(payload || { success: false, message: 'Empty SkyStream response' });
                  };
                  var callArgs = argList.concat([done]);
                  var maybePromise = fn.apply(globalThis, callArgs);
                  if (maybePromise && typeof maybePromise.then === 'function') {
                    maybePromise.then(function(directVal) {
                      if (!settled && directVal && typeof directVal === 'object' && ('success' in directVal || 'data' in directVal)) {
                        done(directVal.success !== undefined ? directVal : { success: true, data: directVal });
                      }
                    }).catch(function(err) {
                      if (!settled) done({ success: false, message: (err && err.message) ? String(err.message) : String(err) });
                    });
                  }
                } catch (e) {
                  resolve({ success: false, message: (e && e.message) ? String(e.message) : String(e) });
                }
              });
            };
        """.trimIndent()
    }

    suspend fun getHome(
        pluginId: String,
        packageName: String,
        payload: SkyArchivePayload,
        timeoutMs: Long = 28_000L
    ): JSONObject {
        val callScript = """
            (async function() {
              var res = await globalThis.__invokeSkyMethod('getHome', []);
              __nuvioResult(res);
            })();
        """.trimIndent()
        return nuvioJsEngine.evaluateCustomScript(
            pluginId = pluginId,
            scraperId = "sky-$packageName",
            setupScript = buildSetupScript(payload.manifestJson),
            scriptCode = payload.scriptCode,
            callScript = callScript,
            timeoutMs = timeoutMs
        )
    }

    suspend fun search(
        pluginId: String,
        packageName: String,
        payload: SkyArchivePayload,
        query: String,
        timeoutMs: Long = 24_000L
    ): JSONObject {
        val callScript = """
            (async function() {
              var res = await globalThis.__invokeSkyMethod('search', [${JSONObject.quote(query)}]);
              __nuvioResult(res);
            })();
        """.trimIndent()
        return nuvioJsEngine.evaluateCustomScript(
            pluginId = pluginId,
            scraperId = "sky-$packageName",
            setupScript = buildSetupScript(payload.manifestJson),
            scriptCode = payload.scriptCode,
            callScript = callScript,
            timeoutMs = timeoutMs
        )
    }

    suspend fun load(
        pluginId: String,
        packageName: String,
        payload: SkyArchivePayload,
        url: String,
        timeoutMs: Long = 30_000L
    ): JSONObject {
        val callScript = """
            (async function() {
              var res = await globalThis.__invokeSkyMethod('load', [${JSONObject.quote(url)}]);
              __nuvioResult(res);
            })();
        """.trimIndent()
        return nuvioJsEngine.evaluateCustomScript(
            pluginId = pluginId,
            scraperId = "sky-$packageName",
            setupScript = buildSetupScript(payload.manifestJson),
            scriptCode = payload.scriptCode,
            callScript = callScript,
            timeoutMs = timeoutMs
        )
    }

    suspend fun loadStreams(
        pluginId: String,
        packageName: String,
        payload: SkyArchivePayload,
        url: String,
        timeoutMs: Long = 40_000L
    ): JSONObject {
        val callScript = """
            (async function() {
              var res = await globalThis.__invokeSkyMethod('loadStreams', [${JSONObject.quote(url)}]);
              __nuvioResult(res);
            })();
        """.trimIndent()
        return nuvioJsEngine.evaluateCustomScript(
            pluginId = pluginId,
            scraperId = "sky-$packageName",
            setupScript = buildSetupScript(payload.manifestJson),
            scriptCode = payload.scriptCode,
            callScript = callScript,
            timeoutMs = timeoutMs
        )
    }
}
