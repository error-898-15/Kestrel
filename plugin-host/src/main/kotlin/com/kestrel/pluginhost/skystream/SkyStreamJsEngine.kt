// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.pluginhost.skystream

import android.util.Base64
import android.util.Log
import com.kestrel.pluginhost.nuvio.NuvioJsEngine
import com.kestrel.pluginhost.settings.ProviderSettingsStore
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.File
import java.util.zip.ZipFile
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.pow

data class SkyArchivePayload(
    val manifestJson: String,
    val scriptCode: String
)

@Singleton
class SkyStreamJsEngine @Inject constructor(
    private val nuvioJsEngine: NuvioJsEngine,
    private val settingsStore: ProviderSettingsStore,
    private val localProxyService: SkyLocalProxyService
) {
    fun resolveMagicUrl(url: String, headers: Map<String, String> = emptyMap()): String =
        localProxyService.resolveMagicUrl(url, headers)

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
            globalThis.MultimediaItem = function(data) { Object.assign(this, { type: 'movie', status: 'unknown', playbackPolicy: 'none', isAdult: false, streams: [], syncData: {} }, data || {}); };
            globalThis.Episode = function(data) { Object.assign(this, { season: 0, episode: 0, dubStatus: 'none', playbackPolicy: 'none', streams: [] }, data || {}); };
            globalThis.StreamResult = function(data) { Object.assign(this, { source: 'Auto' }, data || {}); };
            globalThis.Actor = function(data) { Object.assign(this, data || {}); };
            globalThis.Trailer = function(data) { Object.assign(this, data || {}); };
            globalThis.NextAiring = function(data) { Object.assign(this, data || {}); };
            globalThis.CloudStream = {
              getLanguage: function() { return 'en'; },
              getRegion: function() { return 'US'; }
            };
            if (typeof globalThis.log !== 'function') {
              globalThis.log = function(msg) {
                if (globalThis.console && typeof globalThis.console.log === 'function') {
                  globalThis.console.log(msg);
                }
              };
            }

            globalThis.__skyHeadersToObj = function(headers) {
              var out = {};
              if (headers && typeof headers.forEach === 'function') {
                headers.forEach(function(v, k) { out[k] = v; });
              }
              return out;
            };

            globalThis.http_get = async function(url, headers, cb) {
              if (typeof headers === 'function') {
                cb = headers;
                headers = {};
              }
              var res;
              try {
                var mergedHeaders = Object.assign({}, globalThis.__kestrelCustomHeaders || {}, headers || {});
                var resp = await globalThis.fetch(String(url), { method: 'GET', headers: mergedHeaders });
                var body = await resp.text();
                var finalUrl = resp.url || String(url);
                res = {
                  status: resp.status,
                  statusCode: resp.status,
                  code: resp.status,
                  ok: resp.ok,
                  url: finalUrl,
                  finalUrl: finalUrl,
                  headers: globalThis.__skyHeadersToObj(resp.headers),
                  body: body
                };
              } catch (e) {
                var target = String(url);
                res = {
                  status: 0,
                  statusCode: 0,
                  code: 0,
                  ok: false,
                  url: target,
                  finalUrl: target,
                  headers: {},
                  body: '',
                  error: String((e && e.message) || e)
                };
              }
              if (typeof cb === 'function') {
                try { cb(res); } catch (_) {}
              }
              return res;
            };

            globalThis.http_post = async function(url, arg2, arg3, cb) {
              var headers = {};
              var body = '';
              var inferredJson = false;
              if (typeof arg2 === 'function') {
                cb = arg2;
              } else if (typeof arg2 === 'string') {
                body = arg2;
                if (typeof arg3 === 'function') cb = arg3;
                else headers = (arg3 && typeof arg3 === 'object') ? arg3 : {};
              } else if (arg2 && typeof arg2 === 'object') {
                if (typeof arg3 === 'function') {
                  cb = arg3;
                  arg3 = undefined;
                }
                if (arg3 === undefined && ('body' in arg2 || 'headers' in arg2)) {
                  headers = (arg2.headers && typeof arg2.headers === 'object') ? arg2.headers : {};
                  if (arg2.body !== undefined && arg2.body !== null) {
                    if (typeof arg2.body === 'string') {
                      body = arg2.body;
                    } else {
                      body = JSON.stringify(arg2.body);
                      inferredJson = true;
                    }
                  }
                } else {
                  headers = arg2;
                  if (typeof arg3 === 'string') {
                    body = arg3;
                  } else if (arg3 !== undefined && arg3 !== null) {
                    body = JSON.stringify(arg3);
                    inferredJson = true;
                  }
                }
              }
              var res;
              try {
                var mergedHeaders = Object.assign({}, globalThis.__kestrelCustomHeaders || {}, headers || {});
                var hasContentType = Object.keys(mergedHeaders).some(function(k) {
                  return k.toLowerCase() === 'content-type';
                });
                if (!hasContentType && body) {
                  mergedHeaders['Content-Type'] = inferredJson
                    ? 'application/json'
                    : 'application/x-www-form-urlencoded';
                }
                var resp = await globalThis.fetch(String(url), { method: 'POST', headers: mergedHeaders, body: body });
                var respBody = await resp.text();
                var finalUrl = resp.url || String(url);
                res = {
                  status: resp.status,
                  statusCode: resp.status,
                  code: resp.status,
                  ok: resp.ok,
                  url: finalUrl,
                  finalUrl: finalUrl,
                  headers: globalThis.__skyHeadersToObj(resp.headers),
                  body: respBody
                };
              } catch (e) {
                var target = String(url);
                res = {
                  status: 0,
                  statusCode: 0,
                  code: 0,
                  ok: false,
                  url: target,
                  finalUrl: target,
                  headers: {},
                  body: '',
                  error: String((e && e.message) || e)
                };
              }
              if (typeof cb === 'function') {
                try { cb(res); } catch (_) {}
              }
              return res;
            };

            globalThis.http_parallel = async function(requests) {
              var list = Array.isArray(requests) ? requests : [];
              return Promise.all(list.map(async function(req) {
                try {
                  if (typeof req === 'string') {
                    return await globalThis.http_get(req, {});
                  }
                  var targetUrl = String((req && req.url) || '');
                  var method = String((req && req.method) || 'GET').toUpperCase();
                  var reqBody = (req && req.body !== undefined) ? req.body : (req ? req.data : undefined);
                  var res = method === 'POST'
                    ? await globalThis.http_post(targetUrl, (req && req.headers) || {}, reqBody !== undefined ? reqBody : '')
                    : await globalThis.http_get(targetUrl, (req && req.headers) || {});
                  if (req && req.meta !== undefined) res.meta = req.meta;
                  return res;
                } catch (e) {
                  var u = (req && typeof req === 'object') ? String(req.url || '') : String(req || '');
                  var fail = { status: 0, statusCode: 0, code: 0, ok: false, url: u, finalUrl: u, headers: {}, body: '', error: String((e && e.message) || e) };
                  if (req && typeof req === 'object' && req.meta !== undefined) fail.meta = req.meta;
                  return fail;
                }
              }));
            };

            globalThis.getAndUnpack = function(js) {
              try {
                return __skyUnpackJs(String(js || ''));
              } catch (_) {
                return String(js || '');
              }
            };

            globalThis.parse_html = async function(html, selector, attr) {
              var raw = await __skyParseHtmlAsync(
                String(html || ''),
                String(selector || '*'),
                attr === undefined || attr === null ? '' : String(attr)
              );
              return JSON.parse(raw || '[]');
            };

            globalThis.getPreference = function(key) {
              return __skyGetPref(String(key || ''));
            };

            globalThis.setPreference = function(key, value) {
              __skySetPref(
                String(key || ''),
                value === undefined || value === null ? null : (typeof value === 'string' ? value : JSON.stringify(value))
              );
            };

            globalThis.__skySettingsSchema = [];
            globalThis.registerSettings = function(schema) {
              if (Array.isArray(schema)) globalThis.__skySettingsSchema = schema;
            };

            globalThis.crypto = Object.assign(globalThis.crypto || {}, {
              decryptAES: async function(data, key, iv, options) {
                var mode = (options && options.mode) ? String(options.mode) : 'cbc';
                var raw = await __skyDecryptAesAsync(
                  String(data || ''),
                  String(key || ''),
                  String(iv || ''),
                  mode
                );
                var parsed = JSON.parse(raw || '{}');
                if (!parsed.ok) throw new Error(parsed.error || 'AES decryption failed');
                return parsed.result;
              },
              pbkdf2: async function(password, salt, iterations, keyLength) {
                var raw = await __skyPbkdf2Async(
                  String(password || ''),
                  String(salt || ''),
                  Number(iterations || 10000),
                  Number(keyLength || 32)
                );
                var parsed = JSON.parse(raw || '{}');
                if (!parsed.ok) throw new Error(parsed.error || 'PBKDF2 derivation failed');
                return parsed.result;
              }
            });

            globalThis.nativeMd5 = function(input) {
              try {
                if (globalThis.CryptoJS && globalThis.CryptoJS.MD5) {
                  return globalThis.CryptoJS.MD5(String(input)).toString();
                }
              } catch (_) {}
              return undefined;
            };
            globalThis.nativeSha256 = function(input) {
              try {
                if (globalThis.CryptoJS && globalThis.CryptoJS.SHA256) {
                  return globalThis.CryptoJS.SHA256(String(input)).toString();
                }
              } catch (_) {}
              return undefined;
            };

            globalThis.sendMessage = function(channel, arg) {
              var ch = String(channel || '');
              var payload = arg;
              if (typeof arg === 'string') {
                try { payload = JSON.parse(arg); } catch (_) { payload = {}; }
              } else if (!payload || typeof payload !== 'object') {
                payload = {};
              }
              if (ch === 'crypto_decrypt_aes') {
                return __skyDecryptAesSync(
                  String(payload.data || ''),
                  String(payload.key || ''),
                  String(payload.iv || ''),
                  String(payload.mode || 'cbc')
                );
              }
              if (ch === 'crypto_pbkdf2') {
                return __skyPbkdf2Sync(
                  String(payload.password || ''),
                  String(payload.salt || ''),
                  Number(payload.iterations || 10000),
                  Number(payload.keyLength || 32)
                );
              }
              if (ch === 'js_unpack') {
                return globalThis.getAndUnpack(typeof arg === 'string' ? arg : (payload.js || ''));
              }
              if (ch === 'get_preference' || ch === 'get_storage') {
                return globalThis.getPreference(payload.key);
              }
              if (ch === 'set_preference' || ch === 'set_storage') {
                globalThis.setPreference(payload.key, payload.value);
                return null;
              }
              if (ch === 'crypto_md5') {
                return globalThis.nativeMd5(typeof payload === 'string' ? payload : String(arg || ''));
              }
              if (ch === 'crypto_sha256') {
                return globalThis.nativeSha256(typeof payload === 'string' ? payload : String(arg || ''));
              }
              if (ch === 'console_log' && globalThis.console && globalThis.console.log) {
                globalThis.console.log(arg);
              } else if (ch === 'console_error' && globalThis.console && globalThis.console.error) {
                globalThis.console.error(arg);
              }
              return null;
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
                    if (payload && typeof payload === 'object' && !Array.isArray(payload) && ('success' in payload)) {
                      resolve(payload);
                    } else if (payload !== undefined && payload !== null) {
                      resolve({ success: true, data: payload });
                    } else {
                      resolve({ success: false, message: 'Empty SkyStream response' });
                    }
                  };
                  var callArgs = argList.concat([done]);
                  var maybePromise = fn.apply(globalThis, callArgs);
                  if (maybePromise && typeof maybePromise.then === 'function') {
                    maybePromise.then(function(directVal) {
                      if (!settled && directVal !== undefined) {
                        done(directVal);
                      }
                    }).catch(function(err) {
                      if (!settled) {
                        settled = true;
                        resolve({ success: false, message: (err && err.message) ? String(err.message) : String(err) });
                      }
                    });
                  } else if (maybePromise !== undefined && !settled) {
                    done(maybePromise);
                  }
                } catch (e) {
                  resolve({ success: false, message: (e && e.message) ? String(e.message) : String(e) });
                }
              });
            };
        """.trimIndent()
    }

    private fun buildSyncBindings(pluginId: String): Map<String, (Array<Any?>) -> Any?> {
        val prefCache = HashMap<String, String>(settingsStore.getValues(pluginId))
        return mapOf(
            "__skyUnpackJs" to { args ->
                val raw = args.firstOrNull()?.toString().orEmpty()
                unpackPackerJs(raw)
            },
            "__skyGetPref" to { args ->
                val key = args.firstOrNull()?.toString().orEmpty()
                if (key.isEmpty()) null else prefCache[key]
            },
            "__skySetPref" to { args ->
                val key = args.getOrNull(0)?.toString().orEmpty()
                if (key.isNotEmpty()) {
                    val value = args.getOrNull(1)?.toString()
                    if (value == null) {
                        prefCache.remove(key)
                    } else {
                        prefCache[key] = value
                    }
                    settingsStore.saveValues(pluginId, prefCache.toMap())
                }
                null
            },
            "__skyDecryptAesSync" to { args ->
                runCatching {
                    aesDecryptBase64(
                        dataB64 = args.getOrNull(0)?.toString().orEmpty(),
                        keyB64 = args.getOrNull(1)?.toString().orEmpty(),
                        ivB64 = args.getOrNull(2)?.toString().orEmpty(),
                        mode = args.getOrNull(3)?.toString() ?: "cbc"
                    )
                }.getOrNull()
            },
            "__skyPbkdf2Sync" to { args ->
                runCatching {
                    pbkdf2Sha256Base64(
                        password = args.getOrNull(0)?.toString().orEmpty(),
                        saltB64 = args.getOrNull(1)?.toString().orEmpty(),
                        iterations = (args.getOrNull(2) as? Number)?.toInt() ?: 10000,
                        keyLength = (args.getOrNull(3) as? Number)?.toInt() ?: 32
                    )
                }.getOrNull()
            }
        )
    }

    private fun buildAsyncBindings(): Map<String, suspend (Array<Any?>) -> String> = mapOf(
        "__skyDecryptAesAsync" to { args ->
            runCatching {
                val plain = aesDecryptBase64(
                    dataB64 = args.getOrNull(0)?.toString().orEmpty(),
                    keyB64 = args.getOrNull(1)?.toString().orEmpty(),
                    ivB64 = args.getOrNull(2)?.toString().orEmpty(),
                    mode = args.getOrNull(3)?.toString() ?: "cbc"
                )
                JSONObject().put("ok", true).put("result", plain).toString()
            }.getOrElse { err ->
                Log.w("SkyStreamJS", "crypto.decryptAES failed: ${err.message}")
                JSONObject().put("ok", false).put("error", err.message ?: "AES decryption failed").toString()
            }
        },
        "__skyPbkdf2Async" to { args ->
            runCatching {
                val b64 = pbkdf2Sha256Base64(
                    password = args.getOrNull(0)?.toString().orEmpty(),
                    saltB64 = args.getOrNull(1)?.toString().orEmpty(),
                    iterations = (args.getOrNull(2) as? Number)?.toInt() ?: 10000,
                    keyLength = (args.getOrNull(3) as? Number)?.toInt() ?: 32
                )
                JSONObject().put("ok", true).put("result", b64).toString()
            }.getOrElse { err ->
                JSONObject().put("ok", false).put("error", err.message ?: "PBKDF2 failed").toString()
            }
        },
        "__skyParseHtmlAsync" to { args ->
            parseHtmlElements(
                html = args.getOrNull(0)?.toString().orEmpty(),
                selector = args.getOrNull(1)?.toString()?.ifBlank { "*" } ?: "*",
                attr = args.getOrNull(2)?.toString()?.takeIf { it.isNotBlank() }
            )
        }
    )

    suspend fun getHome(
        pluginId: String,
        packageName: String,
        payload: SkyArchivePayload,
        timeoutMs: Long = 40_000L
    ): JSONObject {
        val callScript = """
            (async function() {
              try {
                var res = await globalThis.__invokeSkyMethod('getHome', []);
                __nuvioResult(res);
              } catch (e) {
                __nuvioResult({ success: false, message: (e && e.message) ? String(e.message) : String(e) });
              }
            })();
        """.trimIndent()
        return nuvioJsEngine.evaluateCustomScript(
            pluginId = pluginId,
            scraperId = "sky-$packageName",
            setupScript = buildSetupScript(payload.manifestJson),
            scriptCode = payload.scriptCode,
            callScript = callScript,
            timeoutMs = timeoutMs,
            extraAsyncBindings = buildAsyncBindings(),
            extraSyncBindings = buildSyncBindings(pluginId)
        )
    }

    suspend fun search(
        pluginId: String,
        packageName: String,
        payload: SkyArchivePayload,
        query: String,
        timeoutMs: Long = 30_000L
    ): JSONObject {
        val callScript = """
            (async function() {
              try {
                var res = await globalThis.__invokeSkyMethod('search', [${JSONObject.quote(query)}]);
                __nuvioResult(res);
              } catch (e) {
                __nuvioResult({ success: false, message: (e && e.message) ? String(e.message) : String(e) });
              }
            })();
        """.trimIndent()
        return nuvioJsEngine.evaluateCustomScript(
            pluginId = pluginId,
            scraperId = "sky-$packageName",
            setupScript = buildSetupScript(payload.manifestJson),
            scriptCode = payload.scriptCode,
            callScript = callScript,
            timeoutMs = timeoutMs,
            extraAsyncBindings = buildAsyncBindings(),
            extraSyncBindings = buildSyncBindings(pluginId)
        )
    }

    suspend fun load(
        pluginId: String,
        packageName: String,
        payload: SkyArchivePayload,
        url: String,
        timeoutMs: Long = 32_000L
    ): JSONObject {
        val callScript = """
            (async function() {
              try {
                var res = await globalThis.__invokeSkyMethod('load', [${JSONObject.quote(url)}]);
                __nuvioResult(res);
              } catch (e) {
                __nuvioResult({ success: false, message: (e && e.message) ? String(e.message) : String(e) });
              }
            })();
        """.trimIndent()
        return nuvioJsEngine.evaluateCustomScript(
            pluginId = pluginId,
            scraperId = "sky-$packageName",
            setupScript = buildSetupScript(payload.manifestJson),
            scriptCode = payload.scriptCode,
            callScript = callScript,
            timeoutMs = timeoutMs,
            extraAsyncBindings = buildAsyncBindings(),
            extraSyncBindings = buildSyncBindings(pluginId)
        )
    }

    suspend fun loadStreams(
        pluginId: String,
        packageName: String,
        payload: SkyArchivePayload,
        url: String,
        timeoutMs: Long = 45_000L
    ): JSONObject {
        val callScript = """
            (async function() {
              try {
                var res = await globalThis.__invokeSkyMethod('loadStreams', [${JSONObject.quote(url)}]);
                __nuvioResult(res);
              } catch (e) {
                __nuvioResult({ success: false, message: (e && e.message) ? String(e.message) : String(e) });
              }
            })();
        """.trimIndent()
        return nuvioJsEngine.evaluateCustomScript(
            pluginId = pluginId,
            scraperId = "sky-$packageName",
            setupScript = buildSetupScript(payload.manifestJson),
            scriptCode = payload.scriptCode,
            callScript = callScript,
            timeoutMs = timeoutMs,
            extraAsyncBindings = buildAsyncBindings(),
            extraSyncBindings = buildSyncBindings(pluginId)
        )
    }

    private fun normalizeBase64(raw: String): String {
        val stripped = raw.replace(Regex("\\s+"), "")
        val rem = stripped.length % 4
        return if (rem == 0) stripped else stripped + "=".repeat(4 - rem)
    }

    private fun aesDecryptBase64(
        dataB64: String,
        keyB64: String,
        ivB64: String,
        mode: String
    ): String {
        val keyBytes = Base64.decode(normalizeBase64(keyB64), Base64.DEFAULT)
        val ivBytes = Base64.decode(normalizeBase64(ivB64), Base64.DEFAULT)
        val cipherBytes = Base64.decode(normalizeBase64(dataB64), Base64.DEFAULT)
        val keySpec = SecretKeySpec(keyBytes, "AES")
        val plainBytes = if (mode.equals("gcm", ignoreCase = true)) {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(128, ivBytes))
            cipher.doFinal(cipherBytes)
        } else {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, keySpec, IvParameterSpec(ivBytes))
            cipher.doFinal(cipherBytes)
        }
        return String(plainBytes, Charsets.UTF_8)
    }

    private fun pbkdf2Sha256Base64(
        password: String,
        saltB64: String,
        iterations: Int,
        keyLength: Int
    ): String {
        val saltBytes = Base64.decode(normalizeBase64(saltB64), Base64.DEFAULT)
        val derived = pbkdf2HmacSha256(
            password = password.toByteArray(Charsets.UTF_8),
            salt = saltBytes,
            iterations = iterations.coerceAtLeast(1),
            keyLengthBytes = keyLength.coerceIn(1, 256)
        )
        return Base64.encodeToString(derived, Base64.NO_WRAP)
    }

    private fun pbkdf2HmacSha256(
        password: ByteArray,
        salt: ByteArray,
        iterations: Int,
        keyLengthBytes: Int
    ): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(password, "HmacSHA256"))
        val hLen = mac.macLength
        val blockCount = (keyLengthBytes + hLen - 1) / hLen
        val out = ByteArray(keyLengthBytes)
        var offset = 0
        for (blockIndex in 1..blockCount) {
            val blockInt = byteArrayOf(
                (blockIndex ushr 24).toByte(),
                (blockIndex ushr 16).toByte(),
                (blockIndex ushr 8).toByte(),
                blockIndex.toByte()
            )
            mac.reset()
            mac.update(salt)
            mac.update(blockInt)
            var u = mac.doFinal()
            val t = u.copyOf()
            for (iter in 2..iterations) {
                mac.reset()
                u = mac.doFinal(u)
                for (k in t.indices) {
                    t[k] = (t[k].toInt() xor u[k].toInt()).toByte()
                }
            }
            val copyLen = minOf(hLen, keyLengthBytes - offset)
            System.arraycopy(t, 0, out, offset, copyLen)
            offset += copyLen
        }
        return out
    }

    private fun parseHtmlElements(html: String, selector: String, attr: String?): String {
        return runCatching {
            val doc = Jsoup.parse(html)
            val elements = doc.select(selector)
            val arr = JSONArray()
            for (el in elements) {
                val attrValue = when (attr?.lowercase()) {
                    null, "" -> ""
                    "text", "textcontent" -> el.text()
                    "html", "innerhtml" -> el.html()
                    "outerhtml" -> el.outerHtml()
                    else -> el.attr(attr)
                }
                arr.put(
                    JSONObject()
                        .put("text", el.text())
                        .put("html", el.html())
                        .put("attr", attrValue)
                )
            }
            arr.toString()
        }.getOrDefault("[]")
    }

    private fun unpackPackerJs(source: String): String {
        return runCatching {
            val regex = Regex(
                """\}\s*\('(.*)',\s*(\d+),\s*(\d+),\s*'(.*?)'\.split\('\|'\)""",
                setOf(RegexOption.DOT_MATCHES_ALL)
            )
            val match = regex.find(source) ?: return source
            val payload = match.groupValues[1].replace("\\'", "'")
            val radix = match.groupValues[2].toIntOrNull() ?: 36
            val count = match.groupValues[3].toIntOrNull() ?: 0
            val symtab = match.groupValues[4].split('|')
            if (symtab.size != count) return source

            val unbaser = PackerUnbaser(radix)
            Regex("""\b[a-zA-Z0-9_]+\b""").replace(payload) { m ->
                val word = m.value
                val idx = unbaser.unbase(word)
                val replacement = if (idx in symtab.indices) symtab[idx] else null
                if (!replacement.isNullOrEmpty()) replacement else word
            }
        }.getOrDefault(source)
    }

    private class PackerUnbaser(private val radix: Int) {
        private val dict: Map<Char, Int>? = when {
            radix in 37..62 -> ALPHABET_62.substring(0, radix).withIndex().associate { it.value to it.index }
            radix in 63..95 -> ALPHABET_95.substring(0, radix.coerceAtMost(ALPHABET_95.length))
                .withIndex().associate { it.value to it.index }
            else -> null
        }

        fun unbase(word: String): Int {
            val d = dict ?: return word.toIntOrNull(radix.coerceIn(2, 36)) ?: -1
            var result = 0
            val reversed = word.reversed()
            for (i in reversed.indices) {
                val v = d[reversed[i]] ?: 0
                result += (radix.toDouble().pow(i.toDouble()) * v).toInt()
            }
            return result
        }

        companion object {
            private const val ALPHABET_62 = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
            private const val ALPHABET_95 =
                """ !"#$%&'()*+,-./0123456789:;<=>?@ABCDEFGHIJKLMNOPQRSTUVWXYZ[\]^_`abcdefghijklmnopqrstuvwxyz{|}~"""
        }
    }
}
