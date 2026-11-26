// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.pluginhost.nuvio

import android.util.Base64
import android.util.Log
import com.dokar.quickjs.binding.AsyncFunctionBinding
import com.dokar.quickjs.binding.FunctionBinding
import com.dokar.quickjs.quickJs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sandboxed QuickJS runtime for Nuvio JS scrapers using dokar3/quickjs-kt.
 */
@Singleton
class NuvioJsEngine @Inject constructor(
    private val okHttpClient: OkHttpClient
) {
    suspend fun executeGetStreams(
        scraperId: String,
        scriptCode: String,
        tmdbId: String,
        mediaType: String,
        season: Int?,
        episode: Int?,
        timeoutMs: Long = 20_000L
    ): JSONObject {
        val callScript = """
            (async function() {
              try {
                var target = (typeof module !== 'undefined' && module.exports) ? module.exports : globalThis;
                var fn = target.getStreams || globalThis.getStreams || (typeof target.default === 'function' ? target.default : null);
                if (typeof fn !== 'function') {
                  __nuvioResult({ streams: [], error: 'Scraper exports no getStreams() function' });
                  return;
                }
                var out = await fn(${JSONObject.quote(tmdbId)}, ${JSONObject.quote(mediaType)}, ${season ?: "null"}, ${episode ?: "null"});
                var streams = Array.isArray(out) ? out : (out && Array.isArray(out.streams) ? out.streams : []);
                __nuvioResult({ streams: streams });
              } catch (e) {
                __nuvioResult({ streams: [], error: (e && e.message) ? String(e.message) : String(e) });
              }
            })();
        """.trimIndent()

        return runScript(
            scraperId = scraperId,
            scriptCode = scriptCode,
            callScript = callScript,
            timeoutMs = timeoutMs
        )
    }

    private suspend fun runScript(
        scraperId: String,
        scriptCode: String,
        callScript: String,
        timeoutMs: Long
    ): JSONObject {
        val resultDeferred = CompletableDeferred<String>()
        val capturedJson = AtomicReference<String?>(null)

        val raw = withTimeoutOrNull(timeoutMs) {
            quickJs(Dispatchers.Default) {
                defineBinding(
                    "__nuvioCall",
                    object : FunctionBinding<String?> {
                        override fun invoke(args: Array<Any?>): String? {
                            val reqJson = args.firstOrNull() as? String ?: "{}"
                            return handleSyncCall(
                                scraperId = scraperId,
                                reqJson = reqJson,
                                onResult = { data ->
                                    capturedJson.set(data)
                                    if (!resultDeferred.isCompleted) resultDeferred.complete(data)
                                }
                            )
                        }
                    }
                )

                defineBinding(
                    "__nuvioFetchAsync",
                    object : AsyncFunctionBinding<String> {
                        override suspend fun invoke(args: Array<Any?>): String =
                            performFetch(args.firstOrNull() as? String ?: "{}")
                    }
                )

                defineBinding(
                    "__nuvioSleepAsync",
                    object : AsyncFunctionBinding<Any?> {
                        override suspend fun invoke(args: Array<Any?>): Any? {
                            val ms = (args.firstOrNull() as? Number)?.toLong() ?: 0L
                            delay(ms.coerceIn(0L, 15_000L))
                            return null
                        }
                    }
                )

                evaluate<Any?>(NuvioPolyfill.build(), "nuvio-polyfill.js")
                evaluate<Any?>(scriptCode, "$scraperId.js")
                runCatching { evaluate<Any?>(callScript, "nuvio-invoke.js") }

                capturedJson.get() ?: if (resultDeferred.isCompleted) resultDeferred.await() else null
            }
        } ?: capturedJson.get()

        if (raw.isNullOrBlank()) {
            return JSONObject().put("error", "Nuvio scraper timed out or returned no output")
        }
        return runCatching { JSONObject(raw) }
            .getOrElse { JSONObject().put("error", "Malformed JSON from scraper") }
    }

    private fun handleSyncCall(
        scraperId: String,
        reqJson: String,
        onResult: (String) -> Unit
    ): String {
        return try {
            val req = JSONObject(reqJson)
            when (req.optString("op")) {
                "result" -> {
                    onResult(req.optString("data", "{}"))
                    "{}"
                }
                "log" -> {
                    Log.d("NuvioJS", "[$scraperId] ${req.optString("message")}")
                    "{}"
                }
                "btoa" -> {
                    val encoded = Base64.encodeToString(
                        req.optString("value").toByteArray(Charsets.UTF_8),
                        Base64.NO_WRAP
                    )
                    JSONObject().put("value", encoded).toString()
                }
                "atob" -> {
                    val decoded = String(
                        Base64.decode(req.optString("value"), Base64.DEFAULT),
                        Charsets.UTF_8
                    )
                    JSONObject().put("value", decoded).toString()
                }
                "domSelect" -> {
                    val doc = Jsoup.parse(req.optString("html"))
                    val elements = doc.select(req.optString("selector"))
                    val arr = JSONArray()
                    for (el in elements) {
                        val attrsObj = JSONObject()
                        el.attributes().forEach { attr -> attrsObj.put(attr.key, attr.value) }
                        arr.put(
                            JSONObject()
                                .put("text", el.text())
                                .put("html", el.html())
                                .put("outerHtml", el.outerHtml())
                                .put("attrs", attrsObj)
                        )
                    }
                    arr.toString()
                }
                else -> "{}"
            }
        } catch (t: Throwable) {
            JSONObject().put("error", t.message ?: "bridge error").toString()
        }
    }

    private suspend fun performFetch(reqJson: String): String = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject(reqJson)
            val url = payload.optString("url")
            val method = payload.optString("method", "GET").uppercase()
            val builder = Request.Builder().url(url)
            var hasUa = false
            var contentType: String? = null
            val headersObj = payload.optJSONObject("headers")
            if (headersObj != null) {
                val keys = headersObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = headersObj.optString(k)
                    if (k.equals("user-agent", ignoreCase = true)) hasUa = true
                    if (k.equals("content-type", ignoreCase = true)) contentType = v
                    builder.header(k, v)
                }
            }
            if (!hasUa) {
                builder.header("User-Agent", DEFAULT_USER_AGENT)
            }
            val bodyStr = if (payload.has("body") && !payload.isNull("body")) {
                payload.optString("body")
            } else null

            when (method) {
                "GET" -> builder.get()
                "HEAD" -> builder.head()
                "DELETE" -> if (bodyStr != null) {
                    builder.delete(bodyStr.toRequestBody(contentType?.toMediaTypeOrNull()))
                } else {
                    builder.delete()
                }
                else -> builder.method(
                    method,
                    (bodyStr ?: "").toRequestBody(contentType?.toMediaTypeOrNull())
                )
            }

            okHttpClient.newCall(builder.build()).execute().use { resp ->
                val respHeaders = JSONObject()
                resp.headers.names().forEach { hName ->
                    if (!hName.equals("set-cookie", ignoreCase = true)) {
                        respHeaders.put(hName, resp.header(hName).orEmpty())
                    }
                }
                val bodyText = resp.body?.string().orEmpty().take(MAX_BODY_CHARS)
                JSONObject()
                    .put("status", resp.code)
                    .put("statusText", resp.message)
                    .put("url", resp.request.url.toString())
                    .put("headers", respHeaders)
                    .put("body", bodyText)
                    .toString()
            }
        } catch (t: Throwable) {
            JSONObject().put("error", t.message ?: "fetch error").toString()
        }
    }

    companion object {
        private const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
        private const val MAX_BODY_CHARS = 2_000_000
    }
}
