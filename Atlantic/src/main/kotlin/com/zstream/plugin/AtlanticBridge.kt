package com.zstream.plugin

import android.util.Log
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.ConcurrentHashMap

/**
 * The calls the native library makes back into the host, answered with Cloudstream's http client.
 * This is the only place the extension touches the network on the library's behalf.
 *
 * The library calls these synchronously from its own thread, so they block (runBlocking) instead of
 * suspending; the resolvers are always run off the main thread.
 */
object AtlanticBridge {
    private const val TAG = "Atlantic"

    /** host -> cookie name -> value */
    private val cookieJar = ConcurrentHashMap<String, MutableMap<String, String>>()

    /**
     * Adds the cookies from a response's Set-Cookie headers to the host's jar. A new cookie replaces
     * the old one of the same name; the rest are kept, because a site sets them over several replies.
     */
    fun rememberCookies(url: String, setCookies: List<String>) {
        if (setCookies.isEmpty()) return
        val jar = cookieJar.getOrPut(hostOf(url)) { ConcurrentHashMap() }
        for (line in setCookies) {
            val pair = line.substringBefore(';')
            val name = pair.substringBefore('=', "").trim()
            if (name.isEmpty()) continue
            val value = pair.substringAfter('=', "").trim()
            // an empty value is the server expiring the cookie
            if (value.isEmpty()) jar.remove(name) else jar[name] = value
        }
    }

    fun cookies(url: String): String =
        cookieJar[hostOf(url)]?.entries?.joinToString("; ") { "${it.key}=${it.value}" }.orEmpty()

    private fun hostOf(url: String): String =
        runCatching { java.net.URI(url).host.orEmpty() }.getOrDefault("")

    /** nicehttp takes seconds, the library hands over milliseconds */
    private fun seconds(timeoutMs: Long): Long = (timeoutMs / 1000).coerceIn(5L, 30L)

    private fun headersFor(url: String, referer: String, contentType: String? = null): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        headers["Accept"] = "*/*"
        headers["User-Agent"] = HostFetcher.userAgentValue
        if (contentType != null) headers["Content-Type"] = contentType
        if (referer.isNotBlank()) headers["Referer"] = referer
        cookies(url).takeIf { it.isNotBlank() }?.let { headers["Cookie"] = it }
        return headers
    }

    fun fetch(url: String, referer: String, script: String, timeoutMs: Long): String {
        return try {
            runBlocking {
                val r = app.get(url, headers = headersFor(url, referer), timeout = seconds(timeoutMs))
                rememberCookies(url, r.headers.values("set-cookie"))
                r.text
            }
        } catch (e: Throwable) {
            Log.d(TAG, "fetch failed ${hostOf(url)}: ${e.javaClass.simpleName} ${e.message}")
            ""
        }
    }

    /** a POST helper, in case a source needs one through the same bridge */
    fun post(url: String, referer: String, body: String, contentType: String, timeoutMs: Long): String {
        return try {
            runBlocking {
                val r = app.post(
                    url,
                    headers = headersFor(url, referer, contentType),
                    requestBody = body.toRequestBody(contentType.toMediaType()),
                    timeout = seconds(timeoutMs),
                )
                rememberCookies(url, r.headers.values("set-cookie"))
                r.text
            }
        } catch (e: Throwable) {
            Log.d(TAG, "post failed ${hostOf(url)}: ${e.javaClass.simpleName} ${e.message}")
            ""
        }
    }
}
