package com.watchuu.octave

import android.content.Context
import android.content.SharedPreferences
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.webkit.WebSettings
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Octave (music.octavestreaming.com).
 *
 * Everything here was checked against the site's own traffic, not inferred:
 *
 *   GET /api/lastfm/global-chart-tracks?limit=N  200, no cookie needed   {tracks:[…]}
 *   GET /api/mix?limit=N&artists=<ids>           200, no cookie needed   {tracks:[…]}
 *   GET /api/track/<id>/radio                    200, no cookie needed   {tracks:[…]}
 *   GET /api/track/<id>?quality=lossless         200, no cookie needed   {url, preview, gated}
 *   GET /api/playback-token  + Bearer <key>      200                    {token, expiresIn}
 *   GET /audio/lossless?track=…&k=<token>        the flac
 *   GET /audio/lossless?track=…  (no k)          403
 *
 * So: browsing needs nothing, a 30-second preview needs nothing, and lossless needs a token, which
 * needs an account key. The catalogue is Deezer, so Deezer's public api is used whenever the site
 * refuses a call - same ids, same previews, no credentials.
 */
object OctaveApi {
    const val SITE = "https://music.octavestreaming.com"
    const val API = "https://api.octavestreaming.com"
    const val DEEZER = "https://api.deezer.com"
    const val BUILD = "v38"

    private const val UA_FALLBACK =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    private var prefs: SharedPreferences? = null
    private var appContext: Context? = null
    @Volatile private var uaCache: String? = null
    @Volatile private var token: String? = null
    @Volatile private var tokenExp = 0L
    @Volatile var lastReport: String = "no request yet"
    /** the raw answer from the token endpoint, first 200 chars - the only thing that says why */
    @Volatile var lastTokenAnswer: String = ""

    fun attach(context: Context) { appContext = context.applicationContext }

    private fun restoreToken() {
        if (token != null) return
        val t = str("token")
        val exp = str("token_exp").toLongOrNull() ?: 0L
        if (t.isNotBlank() && exp > System.currentTimeMillis()) {
            token = t
            tokenExp = exp
        }
    }

    fun init(context: Context) {
        appContext = context.applicationContext
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences("octave_prefs", Context.MODE_PRIVATE)
        }
        restoreToken()
    }

    private fun str(k: String) = prefs?.getString(k, "")?.trim().orEmpty()
    private fun put(k: String, v: String) {
        prefs?.edit()?.putString(k, v.trim())?.apply()
    }

    // ------------------------------------------------------------------ settings

    /** the octv_… key the site issues to a logged-in session; lossless playback needs it */
    var accountKey: String
        get() = str("account_key")
        set(v) { put("account_key", v); token = null }

    /** a cf_clearance cookie pasted from a browser, used when the in-app window cannot get one */
    var manualCookie: String
        get() = str("manual_cookie")
        set(v) = put("manual_cookie", v)

    /** a pasted user agent; cf_clearance only works with the exact UA it was issued to */
    var customUa: String
        get() = str("custom_ua")
        set(v) { put("custom_ua", v); uaCache = null }

    /** snapshot of whatever the login window ended with */
    var savedCookies: String
        get() = str("saved_cookies")
        set(v) = put("saved_cookies", v)

    fun clearCookies() {
        runCatching {
            val cm = CookieManager.getInstance()
            listOf(API, SITE, "https://octavestreaming.com").forEach { cm.setCookie(it, "cf_clearance=; Max-Age=0") }
            cm.flush()
        }
        savedCookies = ""
        manualCookie = ""
        token = null
    }

    fun userAgent(): String {
        customUa.takeIf { it.isNotBlank() }?.let { return it }
        uaCache?.let { return it }
        val base = runCatching {
            appContext?.let { WebSettings.getDefaultUserAgent(it) }
        }.getOrNull() ?: UA_FALLBACK
        // the "; wv" marker makes Cloudflare stricter, and cf_clearance is bound to this string
        val clean = base.replace("; wv)", ")").replace(" Version/4.0", "")
        uaCache = clean
        return clean
    }

    // ------------------------------------------------------------------ cookies

    /** every cookie the device holds for the site and its api, manual ones first */
    fun cookieHeader(): String {
        val jar = LinkedHashMap<String, String>()
        fun add(raw: String?) {
            raw?.split(";")?.map { it.trim() }?.filter { it.contains("=") }?.forEach {
                val n = it.substringBefore("=")
                if (n.isNotBlank() && !jar.containsKey(n)) jar[n] = it.substringAfter("=")
            }
        }
        add(manualCookie)
        add(savedCookies)
        runCatching {
            val cm = CookieManager.getInstance()
            add(cm.getCookie(API)); add(cm.getCookie(SITE)); add(cm.getCookie("https://octavestreaming.com"))
        }
        return jar.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    fun hasClearance(): Boolean = cookieHeader().contains("cf_clearance")

    fun headers(): Map<String, String> {
        val h = LinkedHashMap<String, String>()
        h["User-Agent"] = userAgent()
        h["Origin"] = SITE
        h["Referer"] = "$SITE/"
        h["Accept"] = "application/json"
        val c = cookieHeader()
        if (c.isNotBlank()) h["Cookie"] = c
        return h
    }

    /**
     * Headers for the audio url, copied from the request the site's own player makes:
     * User-Agent, Origin, Accept: *\/\* - and no cookie, because the k token is the whole
     * authorisation. The capture shows that request returning 200 with content-type: audio/flac.
     */
    fun playerHeaders(): Map<String, String> {
        val h = LinkedHashMap<String, String>()
        h["User-Agent"] = userAgent()
        h["Origin"] = SITE
        h["Accept"] = "*/*"
        return h
    }

    // ------------------------------------------------------------------ requests

    private suspend fun fetch(url: String, extra: Map<String, String> = emptyMap()): Pair<Int, String> {
        return try {
            val r = app.get(url, headers = headers() + extra, timeout = 20L)
            lastReport = "GET ${url.substringBefore("?").takeLast(46)} -> ${r.code}"
            r.code to r.text
        } catch (e: Throwable) {
            lastReport = "GET ${url.substringBefore("?").takeLast(46)} threw ${e.message}"
            -1 to ""
        }
    }

    suspend fun json(url: String, extra: Map<String, String> = emptyMap()): JSONObject? {
        val (code, text) = fetch(url, extra)
        if (code !in 200..299) return null
        return runCatching { JSONObject(text) }.getOrNull()
    }

    // ------------------------------------------------------------------ playback token

    /**
     * The token that unlocks lossless. Tries the account key first, then anonymous - some accounts
     * are allowed to play without one, and a refusal with a key present is not the same as a refusal
     * without it. Cached until shortly before it expires.
     */
    /**
     * The token, fetched by NAVIGATING a WebView to the endpoint.
     *
     * The site's token endpoint refuses anything that is not the browser itself: a plain http call
     * gets 403 before the Authorization header is even read (tested - it answers 403 with no key at
     * all, so it is the client being refused, not the credential). Calling fetch() from inside the
     * page fails too, because the api is a different origin and CORS blocks it - that was the
     * "TypeError: Failed to fetch" in the old reports.
     *
     * A navigation is not subject to CORS. So the WebView is pointed straight at the endpoint with
     * the Authorization header attached; the browser supplies its own cf_clearance and user agent,
     * and the JSON answer is read back off the page.
     */
    /**
     * Any api url, fetched the same way the token is: by navigating the WebView to it.
     *
     * This is what makes the site's own endpoints usable at all. They refuse plain http - the client
     * is rejected before the request is read - but a navigation is the browser itself, so search,
     * albums and artists answer normally. Deezer is kept only as a last resort.
     */
    suspend fun pageJson(url: String, headers: Map<String, String> = emptyMap()): JSONObject? {
        val body = pageText(url, headers) ?: return null
        return runCatching { JSONObject(body) }.getOrNull()
    }

    /** the raw body of a url, read from a WebView navigation */
    suspend fun pageText(url: String, headers: Map<String, String> = emptyMap()): String? =
        withContext(Dispatchers.Main) {
            val ctx = appContext ?: return@withContext null
            val done = CompletableDeferred<String?>()
            val wv = WebView(ctx)
            try {
                wv.settings.javaScriptEnabled = true
                wv.settings.domStorageEnabled = true
                wv.settings.userAgentString = userAgent()
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
                wv.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, u: String?) {
                        view?.evaluateJavascript("document.body.innerText") { raw ->
                            val text = (raw ?: "").removeSurrounding("\"").replace("\\\"", "\"").replace("\\n", " ")
                            if (!done.isCompleted) done.complete(text)
                        }
                    }
                }
                wv.loadUrl(url, headers)
                val out = withTimeoutOrNull(25_000L) { done.await() }
                lastReport = "page ${url.substringBefore("?").takeLast(44)} -> ${out?.take(40) ?: "no answer"}"
                out?.takeIf { it.trimStart().startsWith("{") || it.trimStart().startsWith("[") }
            } catch (e: Throwable) {
                lastReport = "page threw ${e.message}"
                null
            } finally {
                runCatching { wv.stopLoading(); wv.destroy() }
            }
        }

    private suspend fun tokenViaPage(key: String): String? = withContext(Dispatchers.Main) {
        val ctx = appContext ?: return@withContext null
        val done = CompletableDeferred<String?>()
        val wv = WebView(ctx)
        try {
            wv.settings.javaScriptEnabled = true
            wv.settings.domStorageEnabled = true
            wv.settings.userAgentString = userAgent()
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    view?.evaluateJavascript("document.body.innerText") { raw ->
                        val text = (raw ?: "")
                            .removeSurrounding("\"")
                            .replace("\\\"", "\"")
                            .replace("\\n", " ")
                        if (!done.isCompleted) done.complete(text)
                    }
                }
            }
            val headers = if (key.isNotBlank()) mapOf("Authorization" to "Bearer $key") else emptyMap()
            wv.loadUrl("$API/api/playback-token", headers)
            val body = withTimeoutOrNull(20_000L) { done.await() } ?: return@withContext null
            lastTokenAnswer = body.take(200)
            lastReport = "token via page -> ${body.take(60)}"
            runCatching {
                val o = JSONObject(body)
                val t = o.optString("token").ifBlank { return@withContext null }
                token = t
                tokenExp = System.currentTimeMillis() + o.optLong("expiresIn", 1800L) * 1000L
                put("token", t)
                put("token_exp", tokenExp.toString())
                t
            }.getOrNull()
        } catch (e: Throwable) {
            lastReport = "token via page threw ${e.message}"
            null
        } finally {
            runCatching { wv.stopLoading(); wv.destroy() }
        }
    }

    suspend fun playbackToken(forceNew: Boolean = false): String? {
        val now = System.currentTimeMillis()
        token?.let { if (!forceNew && now < tokenExp - 60_000L) return it }

        suspend fun ask(withKey: Boolean): Pair<Int, String> {
            val extra = if (withKey && accountKey.isNotBlank()) mapOf("Authorization" to "Bearer ${accountKey}") else emptyMap()
            return fetch("$API/api/playback-token", extra)
        }

        // the page is the only client the endpoint accepts; http is the fallback for other setups
        tokenViaPage(accountKey)?.let { return it }

        var (code, text) = ask(true)
        if (code !in 200..299 && accountKey.isNotBlank()) {
            val anon = ask(false)
            if (anon.first in 200..299) {
                code = anon.first; text = anon.second
            }
        }
        if (code !in 200..299) return null

        return runCatching {
            val o = JSONObject(text)
            val t = o.optString("token").ifBlank { return null }
            token = t
            tokenExp = now + (o.optLong("expiresIn", 1800L) * 1000L)
            put("token", t)
            put("token_exp", tokenExp.toString())
            t
        }.getOrNull()
    }

    /** the key's shape only: octv_ followed by 48 hex characters is what the site issues */
    fun keyShape(key: String): String {
        val k = key.trim()
        if (k.isBlank()) return "empty"
        if (!k.startsWith("octv_")) return "wrong prefix (should start octv_)"
        val hex = k.removePrefix("octv_")
        if (hex.length != 48) return "wrong length (${hex.length} hex chars, the site issues 48)"
        if (!hex.all { it in "0123456789abcdefABCDEF" }) return "not hex"
        return "looks right"
    }

    /**
     * Checks a key the same way playback will: through the page. A 403 from a plain http call says
     * nothing about the key - the endpoint refuses non-browser clients outright - so reporting that
     * as "rejected" was wrong and is why a correct key looked broken.
     */
    suspend fun validateKey(key: String): String? {
        if (key.isBlank()) return "no key"
        if (tokenViaPage(key.trim()) != null) return null
        return if (!hasClearance()) "not checked - the site needs a Cloudflare cookie before it will answer"
        else "refused by the site (check the key in the browser)"
    }

    /** a one-byte range request: answers "will this url serve" without downloading it */
    suspend fun serves(url: String): Int = try {
        app.get(url, headers = playerHeaders() + mapOf("Range" to "bytes=0-0"), timeout = 15L).code
    } catch (_: Throwable) {
        -1
    }

    /** the page route is slow by nature - a WebView has to be built and loaded before it answers */
    suspend fun quickToken(): String? = withTimeoutOrNull(40_000L) { playbackToken() }

    fun report(): String {
        val clearance = hasClearance()
        val advice = when {
            clearance && accountKey.isNotBlank() -> "Cookie and key present - lossless should work."
            clearance -> "Cookie present, no account key - previews only. Paste a key from the site."
            accountKey.isNotBlank() -> "Key present, no Cloudflare cookie - the token call will be refused. " +
                "Paste cf_clearance into the cookie field, or use the login window."
            else -> "Nothing configured - browsing and previews work, lossless does not."
        }
        return "Octave build: $BUILD\n" +
            "Last request: $lastReport\n" +
            "cf_clearance: ${if (clearance) "yes" else "NO"}, account key: ${if (accountKey.isNotBlank()) "set" else "missing"}\n" +
            "User agent: ${userAgent().take(110)}\n" +
            "Token endpoint said: ${lastTokenAnswer.ifBlank { "not asked yet" }}\n\n$advice"
    }
}
