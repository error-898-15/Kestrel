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
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
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
        timeoutMs: Long = 28_000L
    ): JSONObject {
        val callScript = """
            (async function() {
              try {
                var target = (typeof module !== 'undefined' && module.exports) ? module.exports : globalThis;
                var fn = target.getStreams ||
                  (typeof target === 'function' ? target : null) ||
                  (target.default && (target.default.getStreams || (typeof target.default === 'function' ? target.default : null))) ||
                  globalThis.getStreams ||
                  (globalThis.getStreams && globalThis.getStreams.getStreams);
                if (typeof fn !== 'function') {
                  __nuvioResult({ streams: [], error: 'Scraper exports no getStreams() function' });
                  return;
                }
                var out = await fn(${JSONObject.quote(tmdbId)}, ${JSONObject.quote(mediaType)}, ${season ?: "null"}, ${episode ?: "null"});
                var streams = Array.isArray(out) ? out : (out && Array.isArray(out.streams) ? out.streams : (out && (out.url || out.streamUrl || out.stream || out.file || out.link) ? [out] : []));
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

    suspend fun executeGetCatalog(
        pluginId: String,
        scraperId: String,
        scriptCode: String,
        page: Int,
        timeoutMs: Long = 12_000L
    ): JSONObject {
        val callScript = """
            (async function() {
              try {
                var target = (typeof module !== 'undefined' && module.exports) ? module.exports : globalThis;
                var fn = target.getCatalog || target.getHome ||
                  (target.default && (target.default.getCatalog || target.default.getHome)) ||
                  globalThis.getCatalog || globalThis.getHome;
                if (typeof fn !== 'function') {
                  __nuvioResult({ hasCustomCatalog: false, sections: [] });
                  return;
                }
                var out = await fn($page);
                if (!out) {
                  __nuvioResult({ hasCustomCatalog: true, sections: [] });
                  return;
                }
                var sections = [];
                if (Array.isArray(out)) {
                  if (out.length > 0 && Array.isArray(out[0] && out[0].items)) {
                    sections = out;
                  } else if (out.length > 0) {
                    sections = [{ id: 'catalog', title: 'Featured', items: out }];
                  }
                } else if (Array.isArray(out.sections)) {
                  sections = out.sections;
                } else if (Array.isArray(out.catalogs)) {
                  sections = out.catalogs;
                } else if (Array.isArray(out.items)) {
                  sections = [{ id: 'catalog', title: out.title || 'Featured', items: out.items }];
                }
                __nuvioResult({ hasCustomCatalog: true, sections: sections });
              } catch (e) {
                __nuvioResult({ hasCustomCatalog: false, sections: [], error: (e && e.message) ? String(e.message) : String(e) });
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

    suspend fun executeSearch(
        pluginId: String,
        scraperId: String,
        scriptCode: String,
        query: String,
        page: Int,
        timeoutMs: Long = 12_000L
    ): JSONObject {
        val callScript = """
            (async function() {
              try {
                var target = (typeof module !== 'undefined' && module.exports) ? module.exports : globalThis;
                var fn = target.search ||
                  (target.default && target.default.search) ||
                  globalThis.search;
                if (typeof fn !== 'function') {
                  __nuvioResult({ hasCustomSearch: false, items: [] });
                  return;
                }
                var out = await fn(${JSONObject.quote(query)}, $page);
                var items = Array.isArray(out) ? out : (out && Array.isArray(out.items) ? out.items : (out && Array.isArray(out.results) ? out.results : []));
                __nuvioResult({ hasCustomSearch: true, items: items });
              } catch (e) {
                __nuvioResult({ hasCustomSearch: false, items: [], error: (e && e.message) ? String(e.message) : String(e) });
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

    suspend fun executeGetDetails(
        pluginId: String,
        scraperId: String,
        scriptCode: String,
        itemUrlOrId: String,
        timeoutMs: Long = 12_000L
    ): JSONObject {
        val callScript = """
            (async function() {
              try {
                var target = (typeof module !== 'undefined' && module.exports) ? module.exports : globalThis;
                var fn = target.getItemDetails || target.getDetails || target.getMeta ||
                  (target.default && (target.default.getItemDetails || target.default.getDetails || target.default.getMeta)) ||
                  globalThis.getItemDetails || globalThis.getDetails;
                if (typeof fn !== 'function') {
                  __nuvioResult({ hasCustomDetails: false });
                  return;
                }
                var out = await fn(${JSONObject.quote(itemUrlOrId)});
                __nuvioResult({ hasCustomDetails: Boolean(out), details: out || null });
              } catch (e) {
                __nuvioResult({ hasCustomDetails: false, error: (e && e.message) ? String(e.message) : String(e) });
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
        val domRegistry = DomRegistry()

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
                                domRegistry = domRegistry,
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
                val preparedCode = prepareNuvioScript(scriptCode)
                val scriptError = runCatching {
                    evaluate<Any?>(preparedCode, "$scraperId.js")
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
        domRegistry: DomRegistry,
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
                    val rawStr = req.optString("value")
                    val bytes = ByteArray(rawStr.length) { i -> (rawStr[i].code and 0xFF).toByte() }
                    val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    JSONObject().put("value", encoded).toString()
                }
                "atob" -> {
                    val cleanB64 = req.optString("value")
                        .replace("-", "+")
                        .replace("_", "/")
                        .replace(Regex("\\s+"), "")
                    val decoded = String(
                        Base64.decode(cleanB64, Base64.DEFAULT),
                        Charsets.ISO_8859_1
                    )
                    JSONObject().put("value", decoded).toString()
                }
                "cryptoRandom" -> {
                    val size = req.optInt("size", 16).coerceIn(1, 1024)
                    val bytes = ByteArray(size)
                    SecureRandom().nextBytes(bytes)
                    JSONObject().put("hex", bytesToHex(bytes)).toString()
                }
                "cryptoDigest" -> handleCryptoDigest(req)
                "cryptoHmac" -> handleCryptoHmac(req)
                "cryptoAes" -> handleCryptoAes(req)
                "domLoad" -> domRegistry.load(req.optString("html"))
                "domSelect" -> domRegistry.select(
                    docId = req.optInt("docId", 0),
                    selector = req.optString("selector"),
                    contextNodeIds = req.optJSONArray("contextNodeIds"),
                    legacyHtml = optNullableString(req, "html")
                )
                "domTraverse" -> domRegistry.traverse(
                    kind = req.optString("kind"),
                    nodeIds = req.optJSONArray("nodeIds"),
                    selector = optNullableString(req, "selector")
                )
                "domFilter" -> domRegistry.filter(
                    nodeIds = req.optJSONArray("nodeIds"),
                    selector = req.optString("selector"),
                    invert = req.optBoolean("invert", false)
                )
                "domIs" -> domRegistry.matchesIs(
                    nodeIds = req.optJSONArray("nodeIds"),
                    selector = req.optString("selector")
                )
                "domIndex" -> domRegistry.indexOf(req.optInt("nodeId", -1))
                else -> "{}"
            }
        } catch (t: Throwable) {
            JSONObject().put("error", t.message ?: "bridge error").toString()
        }
    }

    private fun handleCryptoDigest(req: JSONObject): String {
        val algo = req.optString("algo", "SHA-256")
        val input = hexToBytes(req.optString("hex"))
        val digest = MessageDigest.getInstance(algo).digest(input)
        return JSONObject().put("hex", bytesToHex(digest)).toString()
    }

    private fun handleCryptoHmac(req: JSONObject): String {
        val algo = req.optString("algo", "HmacSHA256")
        val data = hexToBytes(req.optString("dataHex"))
        val key = hexToBytes(req.optString("keyHex"))
        val mac = Mac.getInstance(algo)
        mac.init(SecretKeySpec(key, algo))
        return JSONObject().put("hex", bytesToHex(mac.doFinal(data))).toString()
    }

    private fun handleCryptoAes(req: JSONObject): String {
        val action = req.optString("action", "decrypt").lowercase()
        val mode = req.optString("cipherMode", "CBC").uppercase()
        val padding = req.optString("padding", "Pkcs7")
        val passphrase = optNullableString(req, "passphrase")
        val keyHex = optNullableString(req, "keyHex")
        val ivHex = optNullableString(req, "ivHex")

        val transformation = aesTransformation(mode, padding)

        if (action == "encrypt") {
            val plain = hexToBytes(req.optString("inputHex"))
            val cipher = Cipher.getInstance(transformation)

            if (passphrase != null) {
                val salt = ByteArray(8)
                SecureRandom().nextBytes(salt)
                val (derivedKey, derivedIv) = evpBytesToKey(
                    keyLen = 32,
                    ivLen = if (mode == "ECB") 0 else 16,
                    data = passphrase.toByteArray(Charsets.UTF_8),
                    salt = salt
                )
                val keySpec = SecretKeySpec(derivedKey, "AES")
                if (mode == "ECB") {
                    cipher.init(Cipher.ENCRYPT_MODE, keySpec)
                } else {
                    cipher.init(Cipher.ENCRYPT_MODE, keySpec, IvParameterSpec(derivedIv))
                }
                val cipherBytes = cipher.doFinal(plain)
                val openSslBytes = "Salted__".toByteArray(Charsets.US_ASCII) + salt + cipherBytes
                val base64 = Base64.encodeToString(openSslBytes, Base64.NO_WRAP)
                return JSONObject()
                    .put("hex", bytesToHex(cipherBytes))
                    .put("base64", base64)
                    .toString()
            } else {
                val keyBytes = normalizeAesKey(hexToBytes(keyHex.orEmpty()))
                val keySpec = SecretKeySpec(keyBytes, "AES")
                if (mode == "ECB") {
                    cipher.init(Cipher.ENCRYPT_MODE, keySpec)
                } else {
                    val ivBytes = ivHex?.let(::hexToBytes)?.let { adjustBlock16(it) } ?: ByteArray(16)
                    cipher.init(Cipher.ENCRYPT_MODE, keySpec, IvParameterSpec(ivBytes))
                }
                val cipherBytes = cipher.doFinal(plain)
                val base64 = Base64.encodeToString(cipherBytes, Base64.NO_WRAP)
                return JSONObject()
                    .put("hex", bytesToHex(cipherBytes))
                    .put("base64", base64)
                    .toString()
            }
        } else {
            val rawCipherBytes = when {
                !req.isNull("inputHex") -> hexToBytes(req.optString("inputHex"))
                !req.isNull("inputBase64") -> Base64.decode(req.optString("inputBase64"), Base64.DEFAULT)
                else -> ByteArray(0)
            }
            if (rawCipherBytes.isEmpty()) {
                return JSONObject().put("hex", "").toString()
            }

            val cipher = Cipher.getInstance(transformation)
            val plainBytes: ByteArray = if (passphrase != null) {
                val magic = "Salted__".toByteArray(Charsets.US_ASCII)
                val hasSalt = rawCipherBytes.size >= 16 &&
                    rawCipherBytes.copyOfRange(0, 8).contentEquals(magic)
                val salt = if (hasSalt) rawCipherBytes.copyOfRange(8, 16) else ByteArray(0)
                val cipherBytes = if (hasSalt) {
                    rawCipherBytes.copyOfRange(16, rawCipherBytes.size)
                } else {
                    rawCipherBytes
                }
                val (derivedKey, derivedIv) = evpBytesToKey(
                    keyLen = 32,
                    ivLen = if (mode == "ECB") 0 else 16,
                    data = passphrase.toByteArray(Charsets.UTF_8),
                    salt = salt
                )
                val keySpec = SecretKeySpec(derivedKey, "AES")
                if (mode == "ECB") {
                    cipher.init(Cipher.DECRYPT_MODE, keySpec)
                } else {
                    cipher.init(Cipher.DECRYPT_MODE, keySpec, IvParameterSpec(derivedIv))
                }
                cipher.doFinal(cipherBytes)
            } else {
                val keyBytes = normalizeAesKey(hexToBytes(keyHex.orEmpty()))
                val keySpec = SecretKeySpec(keyBytes, "AES")
                if (mode == "ECB") {
                    cipher.init(Cipher.DECRYPT_MODE, keySpec)
                } else {
                    val ivBytes = ivHex?.let(::hexToBytes)?.let { adjustBlock16(it) } ?: ByteArray(16)
                    cipher.init(Cipher.DECRYPT_MODE, keySpec, IvParameterSpec(ivBytes))
                }
                cipher.doFinal(rawCipherBytes)
            }
            return JSONObject().put("hex", bytesToHex(plainBytes)).toString()
        }
    }

    private fun aesTransformation(mode: String, padding: String): String {
        val jceMode = when (mode) {
            "ECB" -> "ECB"
            "CTR" -> "CTR"
            "CFB" -> "CFB"
            "OFB" -> "OFB"
            else -> "CBC"
        }
        val jcePad = when {
            jceMode == "CTR" || jceMode == "CFB" || jceMode == "OFB" -> "NoPadding"
            padding.equals("NoPadding", ignoreCase = true) -> "NoPadding"
            padding.equals("ZeroPadding", ignoreCase = true) -> "NoPadding"
            else -> "PKCS5Padding"
        }
        return "AES/$jceMode/$jcePad"
    }

    /** OpenSSL EVP_BytesToKey with MD5 (1 iteration), matching CryptoJS EvpKDF defaults. */
    private fun evpBytesToKey(
        keyLen: Int,
        ivLen: Int,
        data: ByteArray,
        salt: ByteArray
    ): Pair<ByteArray, ByteArray> {
        val total = keyLen + ivLen
        val out = ByteArray(total)
        val md5 = MessageDigest.getInstance("MD5")
        var block: ByteArray? = null
        var filled = 0
        while (filled < total) {
            md5.reset()
            if (block != null) md5.update(block)
            md5.update(data)
            if (salt.isNotEmpty()) md5.update(salt)
            block = md5.digest()
            val toCopy = minOf(block.size, total - filled)
            System.arraycopy(block, 0, out, filled, toCopy)
            filled += toCopy
        }
        return out.copyOfRange(0, keyLen) to out.copyOfRange(keyLen, total)
    }

    private fun normalizeAesKey(bytes: ByteArray): ByteArray = when (bytes.size) {
        16, 24, 32 -> bytes
        in 0..15 -> bytes.copyOf(16)
        in 17..23 -> bytes.copyOf(24)
        else -> bytes.copyOf(32)
    }

    private fun adjustBlock16(bytes: ByteArray): ByteArray =
        if (bytes.size == 16) bytes else bytes.copyOf(16)

    private fun optNullableString(obj: JSONObject, key: String): String? =
        if (!obj.has(key) || obj.isNull(key)) null else obj.optString(key)

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim()
        if (clean.isEmpty()) return ByteArray(0)
        val len = clean.length / 2
        val out = ByteArray(len)
        for (i in 0 until len) {
            val hi = clean[i * 2].digitToIntOrNull(16) ?: 0
            val lo = clean[i * 2 + 1].digitToIntOrNull(16) ?: 0
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(HEX_CHARS[(b.toInt() ushr 4) and 0x0F])
            sb.append(HEX_CHARS[b.toInt() and 0x0F])
        }
        return sb.toString()
    }

    private class DomRegistry {
        private val nextDocId = AtomicInteger(1)
        private val nextNodeId = AtomicInteger(1)
        private val docs = HashMap<Int, Document>()
        private val nodesById = HashMap<Int, Element>()
        private val idsByNode = IdentityHashMap<Element, Int>()

        fun load(html: String): String {
            val trimmed = html.trimStart()
            val doc: Document = when {
                trimmed.startsWith("<tr", ignoreCase = true) ||
                    trimmed.startsWith("<td", ignoreCase = true) ||
                    trimmed.startsWith("<th", ignoreCase = true) ||
                    trimmed.startsWith("<tbody", ignoreCase = true) ||
                    trimmed.startsWith("<thead", ignoreCase = true) ||
                    trimmed.startsWith("<tfoot", ignoreCase = true) -> {
                    val wrapped = Jsoup.parseBodyFragment("<table>$html</table>")
                    wrapped
                }
                trimmed.startsWith("<html", ignoreCase = true) ||
                    trimmed.startsWith("<!doctype", ignoreCase = true) ||
                    trimmed.contains("<body", ignoreCase = true) -> Jsoup.parse(html)
                else -> Jsoup.parseBodyFragment(html)
            }
            val docId = nextDocId.getAndIncrement()
            docs[docId] = doc
            return JSONObject().put("docId", docId).toString()
        }

        fun select(
            docId: Int,
            selector: String,
            contextNodeIds: JSONArray?,
            legacyHtml: String?
        ): String {
            if (selector.isBlank()) return "[]"
            if (contextNodeIds != null && contextNodeIds.length() > 0) {
                val combined = Elements()
                val seen = IdentityHashMap<Element, Boolean>()
                for (i in 0 until contextNodeIds.length()) {
                    val el = nodesById[contextNodeIds.optInt(i)] ?: continue
                    val matched = runCatching { el.select(selector) }.getOrNull() ?: continue
                    for (m in matched) {
                        if (seen.put(m, true) == null) combined.add(m)
                    }
                }
                return serializeElements(combined)
            }

            val doc = docs[docId] ?: if (!legacyHtml.isNullOrEmpty()) {
                Jsoup.parse(legacyHtml)
            } else {
                return "[]"
            }
            val matched = runCatching { doc.select(selector) }.getOrElse { Elements() }
            return serializeElements(matched)
        }

        fun traverse(
            kind: String,
            nodeIds: JSONArray?,
            selector: String?
        ): String {
            if (nodeIds == null || nodeIds.length() == 0) return "[]"
            val out = Elements()
            val seen = IdentityHashMap<Element, Boolean>()
            val selFilter = selector?.trim()?.takeIf { it.isNotEmpty() }

            fun addCandidate(el: Element?) {
                if (el == null) return
                if (selFilter != null) {
                    val matches = runCatching { el.`is`(selFilter) }.getOrDefault(false)
                    if (!matches) return
                }
                if (seen.put(el, true) == null) {
                    out.add(el)
                }
            }

            for (i in 0 until nodeIds.length()) {
                val el = nodesById[nodeIds.optInt(i)] ?: continue
                when (kind) {
                    "children" -> el.children().forEach(::addCandidate)
                    "parent" -> addCandidate(el.parent())
                    "parents" -> el.parents().forEach(::addCandidate)
                    "closest" -> {
                        if (selFilter != null) {
                            addCandidate(runCatching { el.closest(selFilter) }.getOrNull())
                        } else {
                            addCandidate(el.parent())
                        }
                    }
                    "next" -> addCandidate(el.nextElementSibling())
                    "nextAll" -> el.nextElementSiblings().forEach(::addCandidate)
                    "prev" -> addCandidate(el.previousElementSibling())
                    "prevAll" -> el.previousElementSiblings().forEach(::addCandidate)
                    "siblings" -> el.siblingElements().forEach(::addCandidate)
                }
            }
            return serializeElements(out)
        }

        fun filter(
            nodeIds: JSONArray?,
            selector: String,
            invert: Boolean
        ): String {
            if (nodeIds == null || nodeIds.length() == 0) return "[]"
            val out = Elements()
            val cleanSel = selector.trim()
            for (i in 0 until nodeIds.length()) {
                val el = nodesById[nodeIds.optInt(i)] ?: continue
                val matches = if (cleanSel.isEmpty()) true else runCatching { el.`is`(cleanSel) }.getOrDefault(false)
                if (if (invert) !matches else matches) {
                    out.add(el)
                }
            }
            return serializeElements(out)
        }

        fun matchesIs(nodeIds: JSONArray?, selector: String): String {
            val cleanSel = selector.trim()
            if (nodeIds == null || cleanSel.isEmpty()) {
                return JSONObject().put("match", false).toString()
            }
            var anyMatch = false
            for (i in 0 until nodeIds.length()) {
                val el = nodesById[nodeIds.optInt(i)] ?: continue
                if (runCatching { el.`is`(cleanSel) }.getOrDefault(false)) {
                    anyMatch = true
                    break
                }
            }
            return JSONObject().put("match", anyMatch).toString()
        }

        fun indexOf(nodeId: Int): String {
            val el = nodesById[nodeId]
            val idx = el?.elementSiblingIndex() ?: -1
            return JSONObject().put("index", idx).toString()
        }

        private fun serializeElements(elements: Elements): String {
            val arr = JSONArray()
            for (el in elements) {
                val id = idsByNode[el] ?: nextNodeId.getAndIncrement().also { created ->
                    idsByNode[el] = created
                    nodesById[created] = el
                }
                val attrsObj = JSONObject()
                el.attributes().forEach { attr -> attrsObj.put(attr.key, attr.value) }
                arr.put(
                    JSONObject()
                        .put("nodeId", id)
                        .put("tagName", el.tagName())
                        .put("text", el.text())
                        .put("html", el.html())
                        .put("outerHtml", el.outerHtml())
                        .put("attrs", attrsObj)
                )
            }
            return arr.toString()
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
                    if (headersObj.isNull(k)) continue
                    val v = headersObj.optString(k)
                    if (v.isBlank()) continue
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

            val redirectMode = payload.optString("redirect", "follow").lowercase()
            val callClient = if (redirectMode == "manual" || redirectMode == "error") {
                okHttpClient.newBuilder()
                    .followRedirects(false)
                    .followSslRedirects(false)
                    .build()
            } else {
                okHttpClient
            }

            callClient.newCall(builder.build()).execute().use { resp ->
                val respHeaders = JSONObject()
                resp.headers.names().forEach { hName ->
                    val values = resp.headers.values(hName)
                    if (values.isNotEmpty()) {
                        respHeaders.put(hName, values.joinToString("; "))
                    }
                }
                val bodyText = resp.body?.string().orEmpty().take(MAX_BODY_CHARS)
                JSONObject()
                    .put("status", resp.code)
                    .put("ok", resp.isSuccessful)
                    .put("statusText", resp.message)
                    .put("url", resp.request.url.toString())
                    .put("redirected", resp.priorResponse != null)
                    .put("headers", respHeaders)
                    .put("body", bodyText)
                    .toString()
            }
        } catch (t: Throwable) {
            JSONObject().put("error", t.message ?: "fetch error").toString()
        }
    }

    private fun prepareNuvioScript(scriptCode: String): String {
        var code = scriptCode
        // Support ESM export syntax if a scraper uses `export async function getStreams` or `export default`
        code = code
            .replace(Regex("(?m)^\\s*export\\s+default\\s+"), "module.exports = ")
            .replace(Regex("(?m)^\\s*export\\s+(async\\s+function|function|const|let|var)\\s+"), "$1 ")
            .replace(Regex("(?m)^\\s*export\\s*\\{[^}]*\\};?\\s*$"), "")
            .replace(
                Regex("(?m)^\\s*import\\s+(\\w+)\\s+from\\s+['\"]([^'\"]+)['\"];?\\s*$"),
                "const $1 = require('$2');"
            )
        val bridgeTail = """
            ;try { if (typeof getStreams === 'function' && !globalThis.getStreams) globalThis.getStreams = getStreams; } catch (_) {}
            try { if (typeof getCatalog === 'function' && !globalThis.getCatalog) globalThis.getCatalog = getCatalog; } catch (_) {}
            try { if (typeof getHome === 'function' && !globalThis.getHome) globalThis.getHome = getHome; } catch (_) {}
            try { if (typeof search === 'function' && !globalThis.search) globalThis.search = search; } catch (_) {}
            try { if (typeof getItemDetails === 'function' && !globalThis.getItemDetails) globalThis.getItemDetails = getItemDetails; } catch (_) {}
            try { if (typeof getDetails === 'function' && !globalThis.getDetails) globalThis.getDetails = getDetails; } catch (_) {}
            try { if (typeof onSettings === 'function' && !globalThis.onSettings) globalThis.onSettings = onSettings; } catch (_) {}
        """.trimIndent()
        return code + "\n" + bridgeTail
    }

    companion object {
        private const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
        private const val MAX_BODY_CHARS = 2_000_000
        private val HEX_CHARS = "0123456789abcdef".toCharArray()
    }
}
