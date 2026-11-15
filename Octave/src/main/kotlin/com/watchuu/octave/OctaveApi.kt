package com.watchuu.octave

import android.content.Context
import android.content.SharedPreferences
import android.webkit.CookieManager
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
    const val BUILD = "v30"

    private const val UA_FALLBACK =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    private var prefs: SharedPreferences? = null
    private var appContext: Context? = null
    @Volatile private var uaCache: String? = null
    @Volatile private var token: String? = null
    @Volatile private var tokenExp = 0L
    @Volatile var lastReport: String = "no request yet"

    fun attach(context: Context) { appContext = context.applicationContext }

    fun init(context: Context) {
        appContext = context.applicationContext
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences("octave_prefs", Context.MODE_PRIVATE)
        }
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

    /** headers a player must send for the audio url */
    fun playerHeaders(): Map<String, String> = headers().toMutableMap().apply { remove("Accept") }

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
    suspend fun playbackToken(forceNew: Boolean = false): String? {
        val now = System.currentTimeMillis()
        token?.let { if (!forceNew && now < tokenExp - 60_000L) return it }

        suspend fun ask(withKey: Boolean): Pair<Int, String> {
            val extra = if (withKey && accountKey.isNotBlank()) mapOf("Authorization" to "Bearer ${accountKey}") else emptyMap()
            return fetch("$API/api/playback-token", extra)
        }

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
            t
        }.getOrNull()
    }

    /** checks a key against the endpoint that decides it; null means it works */
    suspend fun validateKey(key: String): String? {
        if (key.isBlank()) return "no key"
        return try {
            val r = app.get(
                "$API/api/playback-token",
                headers = headers() + mapOf("Authorization" to "Bearer ${key.trim()}"),
                timeout = 20L
            )
            when {
                r.code in 200..299 -> {
                    runCatching {
                        val o = JSONObject(r.text)
                        val t = o.optString("token")
                        if (t.isNotBlank()) {
                            token = t
                            tokenExp = System.currentTimeMillis() + o.optLong("expiresIn", 1800L) * 1000L
                        }
                    }
                    null
                }
                else -> "rejected (HTTP ${r.code})"
            }
        } catch (e: Throwable) {
            e.message ?: "failed"
        }
    }

    /** a one-byte range request: answers "will this url serve" without downloading it */
    suspend fun serves(url: String): Int = try {
        app.get(url, headers = playerHeaders() + mapOf("Range" to "bytes=0-0"), timeout = 15L).code
    } catch (_: Throwable) {
        -1
    }

    suspend fun quickToken(): String? = withTimeoutOrNull(12_000L) { playbackToken() }

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
            "User agent: ${userAgent().take(110)}\n\n$advice"
    }
}
