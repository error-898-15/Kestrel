// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.pluginhost.nuvio

import android.util.Base64
import android.util.Log
import com.dokar.quickjs.binding.AsyncFunctionBinding
import com.dokar.quickjs.binding.FunctionBinding
import com.dokar.quickjs.quickJs
import com.kestrel.domain.model.ProviderSettingField
import com.kestrel.domain.model.ProviderSettingOption
import com.kestrel.domain.model.ProviderSettingType
import com.kestrel.pluginhost.settings.ProviderSettingsStore
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
    private val okHttpClient: OkHttpClient,
    private val settingsStore: ProviderSettingsStore
) {
    suspend fun executeGetStreams(
        pluginId: String,
        scraperId: String,
        scriptCode: String,
        tmdbId: String,
        mediaType: String,
        season: Int?,
        episode: Int?,
        timeoutMs: Long = 22_000L
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
            pluginId = pluginId,
            scraperId = scraperId,
            scriptCode = scriptCode,
            callScript = callScript,
            timeoutMs = timeoutMs
        )
    }

    suspend fun inspectSettingsSchema(
        pluginId: String,
        scraperId: String,
        scriptCode: String,
        timeoutMs: Long = 8_000L
    ): List<ProviderSettingField> {
        val callScript = """
            (async function() {
              try {
                var target = (typeof module !== 'undefined' && module.exports) ? module.exports : globalThis;
                var fn = target.onSettings || globalThis.onSettings || (target.default && target.default.onSettings);
                if (typeof fn !== 'function') {
                  __nuvioResult({ fields: [] });
                  return;
                }
                var out = await fn();
                __nuvioResult({ fields: Array.isArray(out) ? out : [] });
              } catch (e) {
                __nuvioResult({ fields: [], error: (e && e.message) ? String(e.message) : String(e) });
              }
            })();
        """.trimIndent()

        val output = runScript(
            pluginId = pluginId,
            scraperId = scraperId,
            scriptCode = scriptCode,
            callScript = callScript,
            timeoutMs = timeoutMs
        )

        val savedValues = settingsStore.getValues(pluginId)
        val arr = output.optJSONArray("fields") ?: return emptyList()
        val parsed = mutableListOf<ProviderSettingField>()

        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val rawType = obj.optString("type", "text").trim().lowercase()
            val type = when (rawType) {
                "header", "section", "title" -> ProviderSettingType.HEADER
                "password", "secret" -> ProviderSettingType.PASSWORD
                "number", "int", "integer" -> ProviderSettingType.NUMBER
                "toggle", "boolean", "switch", "checkbox" -> ProviderSettingType.TOGGLE
                "select", "dropdown", "enum" -> ProviderSettingType.SELECT
                else -> ProviderSettingType.TEXT
            }
            val key = obj.optString("key")
                .ifBlank { obj.optString("id") }
                .ifBlank { obj.optString("name") }
                .ifBlank { "setting_$i" }
            val label = obj.optString("label")
                .ifBlank { obj.optString("title") }
                .ifBlank { key }
            val description = obj.optString("description")
            val defaultValue = when {
                obj.has("defaultValue") && !obj.isNull("defaultValue") -> obj.opt("defaultValue")?.toString().orEmpty()
                obj.has("default") && !obj.isNull("default") -> obj.opt("default")?.toString().orEmpty()
                else -> ""
            }

            val options = mutableListOf<ProviderSettingOption>()
            val optsArr = obj.optJSONArray("options")
            if (optsArr != null) {
                for (j in 0 until optsArr.length()) {
                    val item = optsArr.opt(j) ?: continue
                    if (item is JSONObject) {
                        val value = item.optString("value").ifBlank { item.optString("label") }
                        val optLabel = item.optString("label").ifBlank { value }
                        if (value.isNotBlank()) options.add(ProviderSettingOption(label = optLabel, value = value))
                    } else {
                        val s = item.toString()
                        if (s.isNotBlank()) options.add(ProviderSettingOption(label = s, value = s))
                    }
                }
            }

            val currentValue = savedValues[key] ?: defaultValue
            parsed.add(
                ProviderSettingField(
                    key = key,
                    type = type,
                    label = label,
                    description = description,
                    defaultValue = defaultValue,
                    currentValue = currentValue,
                    options = options,
                    required = obj.optBoolean("required", false)
                )
            )
        }
        return parsed
    }

    private suspend fun runScript(
        pluginId: String,
        scraperId: String,
        scriptCode: String,
        callScript: String,
        timeoutMs: Long
    ): JSONObject {
        val resultDeferred = CompletableDeferred<String>()
        val capturedJson = AtomicReference<String?>(null)

        val settingsJsonObj = JSONObject(settingsStore.getValues(pluginId))
        val headersJsonObj = JSONObject(settingsStore.getHeaders(pluginId))
        val settingsInjection = """
            globalThis.SCRAPER_SETTINGS = ${settingsJsonObj};
            globalThis.__kestrelCustomHeaders = ${headersJsonObj};
        """.trimIndent()

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
                evaluate<Any?>(settingsInjection, "nuvio-settings.js")
                val scriptError = runCatching {
                    evaluate<Any?>(scriptCode, "$scraperId.js")
                }.exceptionOrNull()
                if (scriptError != null) {
                    return@quickJs JSONObject()
                        .put("error", scriptError.message ?: "Nuvio script evaluation error")
                        .toString()
                }
                val invokeError = runCatching {
                    evaluate<Any?>(callScript, "nuvio-invoke.js")
                }.exceptionOrNull()
                if (invokeError != null && capturedJson.get() == null) {
                    return@quickJs JSONObject()
                        .put("error", invokeError.message ?: "Nuvio script invocation error")
                        .toString()
                }

                capturedJson.get() ?: resultDeferred.await()
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
