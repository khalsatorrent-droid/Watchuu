package com.watchuu.musicbox.core

import android.webkit.CookieManager
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * Small HTTP layer used by every provider.
 *
 * Cookies live in the WebView cookie jar (android.webkit.CookieManager), so a login done
 * in the settings screen is immediately visible to provider requests, and cookies a
 * provider sets are visible to the login screen. That shared jar is the reason the
 * authorization flow below works without asking the user to paste session cookies.
 */
object Http {
    var userAgent: String =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    class Res(
        val code: Int,
        val body: String,
        val headers: Map<String, List<String>>,
        val url: String,
    ) {
        val ok: Boolean get() = code in 200..299
        fun header(name: String): String? {
            for ((k, v) in headers) if (k.equals(name, true) && v.isNotEmpty()) return v.first()
            return null
        }
        fun json(): org.json.JSONObject? = jsonOrNull(body)
    }

    fun get(url: String, headers: Map<String, String> = emptyMap(), timeoutMs: Int = 20000): Res =
        request("GET", url, headers, null, null, timeoutMs)

    fun postJson(
        url: String,
        json: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Int = 20000,
    ): Res = request(
        "POST", url,
        headers + mapOf("Content-Type" to "application/json"),
        json.toByteArray(Charsets.UTF_8), "application/json", timeoutMs
    )

    fun postForm(
        url: String,
        form: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Int = 20000,
    ): Res = request(
        "POST", url,
        headers + mapOf("Content-Type" to "application/x-www-form-urlencoded"),
        form.toByteArray(Charsets.UTF_8), "application/x-www-form-urlencoded", timeoutMs
    )

    fun request(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: ByteArray?,
        contentType: String?,
        timeoutMs: Int = 20000,
    ): Res {
        var current = url
        var redirects = 0
        while (true) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", headers["User-Agent"] ?: userAgent)
                setRequestProperty("Accept-Encoding", "gzip")
                if (contentType != null) setRequestProperty("Content-Type", contentType)
                for ((k, v) in headers) {
                    if (k.equals("User-Agent", true) || k.equals("Cookie", true)) continue
                    setRequestProperty(k, v)
                }
                val jarCookie = runCatching {
                    CookieManager.getInstance().getCookie(current)
                }.getOrNull()
                val cookie = headers["Cookie"] ?: jarCookie
                if (!cookie.isNullOrBlank()) setRequestProperty("Cookie", cookie)
                if (body != null) doOutput = true
            }
            try {
                if (body != null) conn.outputStream.use { it.write(body) }
                val code = conn.responseCode
                val raw = (if (code in 200..299) conn.inputStream else conn.errorStream)
                var bytes = raw?.use { readAll(it, conn.contentEncoding) } ?: ByteArray(0)

                // Keep the WebView jar in sync with what the provider set.
                runCatching {
                    val setCookies = conn.headerFields
                        .filterKeys { it != null && it.equals("Set-Cookie", true) }
                        .values.flatten()
                    val cm = CookieManager.getInstance()
                    for (c in setCookies) cm.setCookie(current, c)
                }

                val location = conn.getHeaderField("Location")
                if (code in intArrayOf(301, 302, 303, 307, 308) && !location.isNullOrBlank() && redirects < 5) {
                    current = if (location.startsWith("http")) location else URL(URL(current), location).toString()
                    redirects++
                    continue
                }
                return Res(code, String(bytes, Charsets.UTF_8), conn.headerFields ?: emptyMap(), current)
            } finally {
                conn.disconnect()
            }
        }
    }

    private fun readAll(input: java.io.InputStream, encoding: String?): ByteArray {
        val stream = if (encoding != null && encoding.contains("gzip", true)) GZIPInputStream(input) else input
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = stream.read(buf)
            if (n <= 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /** Cookies the WebView jar holds for a host, as a Cookie header value. */
    fun cookiesFor(url: String): String? = runCatching {
        CookieManager.getInstance().getCookie(url)?.takeIf { it.isNotBlank() }
    }.getOrNull()

    fun clearCookies(url: String? = null) {
        runCatching {
            val cm = CookieManager.getInstance()
            if (url == null) cm.removeAllCookies(null) else {
                val existing = cm.getCookie(url) ?: return
                for (pair in existing.split(";")) {
                    val name = pair.substringBefore("=").trim()
                    if (name.isNotEmpty()) cm.setCookie(url, "$name=; Max-Age=0; Path=/")
                }
            }
            cm.flush()
        }
    }
}
