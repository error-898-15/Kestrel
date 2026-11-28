@file:Suppress("unused")

package eu.kanade.tachiyomi.util

import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

fun Response.asJsoup(html: String? = null): Document {
    return use { resp ->
        val bodyStr = html ?: resp.body?.string().orEmpty()
        Jsoup.parse(bodyStr, resp.request.url.toString())
    }
}
