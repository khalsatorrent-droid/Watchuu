package com.watchuu.octave

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.view.Window
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.app
import com.lagradost.nicehttp.NiceResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Cloudflare + auth handling for music.octavestreaming.com / api.octavestreaming.com.
 *
 * The site sits behind a Cloudflare check. The cf_clearance cookie has to be earned by the user
 * ONCE, in a real browser window (the "solver"): it opens the site, the user ticks the box, the
 * window closes by itself and the cookie is kept by the system WebView. Every request then goes out
 * with that cookie and the SAME user agent (cf_clearance is bound to it).
 *
 * Playback additionally needs a short-lived key (GET /api/playback-token, ~30 min).
 */
object OctaveCf {
    const val SITE = "https://music.octavestreaming.com"

    /** shown in the report, so an outdated install is obvious at a glance */
    const val BUILD_TAG = "v27"
    const val API = "https://api.octavestreaming.com"

    private const val FALLBACK_UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    private var appContext: Context? = null
    private var prefs: SharedPreferences? = null
    private val main = Handler(Looper.getMainLooper())
    private val solveLock = Mutex()
    private val tokenLock = Mutex()

    @Volatile private var solving: CompletableDeferred<Boolean>? = null
    @Volatile private var lastSolvedAt = 0L
    @Volatile private var defaultUa: String? = null

    @Volatile private var token: String? = null
    @Volatile private var tokenExp = 0L

    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
        OctaveBrowser.init(context)
        if (prefs == null) prefs = context.applicationContext.getSharedPreferences("octave_settings", Context.MODE_PRIVATE)
    }

    // ------------------------------------------------------------------ settings storage

    private fun str(k: String) = prefs?.getString(k, "")?.trim() ?: ""
    private fun put(k: String, v: String) { prefs?.edit()?.putString(k, v.trim())?.apply() }

    /** Cookie string pasted by the user (e.g. "cf_clearance=...") - optional. */
    var manualCookie: String
        get() = str("manual_cookie")
        set(v) = put("manual_cookie", v)

    /** User agent override - needed when a cookie from another browser is pasted. */
    var customUa: String
        get() = str("custom_ua")
        set(v) = put("custom_ua", v)

    /** Account key (octv_...). Created automatically when the site allows it, or pasted by the user. */
    var accountKey: String
        get() = str("account_key")
        set(v) { put("account_key", v); token = null }

    // ------------------------------------------------------------------ user agent + cookies

    /** One user agent for everything - cf_clearance is bound to it. */
    fun userAgent(): String {
        customUa.takeIf { it.isNotBlank() }?.let { return it }
        defaultUa?.let { return it }
        val ua = try {
            appContext?.let { WebSettings.getDefaultUserAgent(it) }
        } catch (_: Throwable) {
            null
        } ?: FALLBACK_UA
        // the "; wv" WebView marker makes Cloudflare stricter
        return ua.replace("; wv)", ")").replace(" Version/4.0", "").also { defaultUa = it }
    }

    /** Snapshot of every cookie the solver/login window ended with (kept on this device only). */
    private var savedCookies: String
        get() = str("saved_cookies")
        set(v) = put("saved_cookies", v)

    private fun applyManualCookie() {
        // the user's pasted cookie wins; the saved snapshot only fills what the WebView lost
        val raw = listOf(savedCookies, manualCookie).filter { it.isNotBlank() }.joinToString("; ")
        if (raw.isBlank()) return
        try {
            val cm = CookieManager.getInstance()
            val have = (cm.getCookie(API) ?: "") + ";" + (cm.getCookie(SITE) ?: "")
            raw.split(";").map { it.trim() }.filter { it.contains("=") }.forEach {
                val name = it.substringBefore("=")
                if (name in manualNames() || !have.contains("$name=")) {
                    cm.setCookie(SITE, "$it; path=/; secure")
                    cm.setCookie(API, "$it; path=/; secure")
                }
            }
            cm.flush()
        } catch (_: Throwable) {
        }
    }

    private fun manualNames() = manualCookie.split(";").map { it.trim().substringBefore("=") }.filter { it.isNotBlank() }.toSet()

    /** Keep everything the WebView holds for the site (Cloudflare + login cookies) in app storage. */
    internal fun snapshotCookies() {
        try {
            val cm = CookieManager.getInstance()
            cm.flush()
            val jar = LinkedHashMap<String, String>()
            listOf(cm.getCookie(API), cm.getCookie(SITE), cm.getCookie("https://octavestreaming.com"))
                .filterNotNull().forEach { c ->
                    c.split(";").map { it.trim() }.filter { it.contains("=") }.forEach {
                        jar[it.substringBefore("=")] = it.substringAfter("=")
                    }
                }
            if (jar.isNotEmpty()) savedCookies = jar.entries.joinToString("; ") { "${it.key}=${it.value}" }
        } catch (_: Throwable) {
        }
    }

    /** Cookie header for api.* (falls back to / merges with the site's cookies). */
    fun cookies(): String? {
        applyManualCookie()
        return try {
            val cm = CookieManager.getInstance()
            val jar = LinkedHashMap<String, String>()
            listOf(cm.getCookie(API), cm.getCookie(SITE), cm.getCookie("https://octavestreaming.com"))
                .filterNotNull().forEach { c ->
                    c.split(";").map { it.trim() }.filter { it.contains("=") }.forEach {
                        jar.putIfAbsent(it.substringBefore("="), it.substringAfter("="))
                    }
                }
            jar.entries.joinToString("; ") { "${it.key}=${it.value}" }.ifBlank { null }
        } catch (_: Throwable) {
            null
        }
    }

    fun hasClearance(): Boolean = cookies()?.contains("cf_clearance") == true

    /** names only - which cookies the jars actually hold right now */
    fun cookieSummary(): String = OctaveBrowser.cookieNames()

    fun headers(json: Boolean = true): Map<String, String> {
        val h = LinkedHashMap<String, String>()
        h["User-Agent"] = userAgent()
        h["Origin"] = SITE
        h["Referer"] = "$SITE/"
        h["Accept"] = if (json) "application/json" else "*/*"
        cookies()?.let { h["Cookie"] = it }
        return h
    }

    /** Headers the player must send with the audio request. */
    fun playerHeaders(): Map<String, String> = headers(false)

    fun looksLikeChallenge(code: Int, body: String): Boolean {
        if (code !in listOf(403, 429, 503)) return false
        val head = body.take(4000)
        return head.contains("Just a moment", true) || head.contains("challenge-platform", true) ||
            head.contains("bot challenge", true) || head.contains("Attention Required", true) ||
            head.trimStart().startsWith("<") || head.isBlank()
    }

    fun isChallenge(r: OResp): Boolean = r.challenge

    private fun fromNice(r: NiceResponse): OResp {
        val mitigated = r.headers["cf-mitigated"]?.contains("challenge", true) == true
        return OResp(r.code, r.text, mitigated || looksLikeChallenge(r.code, r.text))
    }

    // ------------------------------------------------------------------ requests

    /**
     * Every request goes through the hidden WebView first (same login + cookies, made from inside
     * the site's own page); plain HTTP with the saved cookie is only the fallback. When Cloudflare
     * still blocks, the user is asked to solve it in the window, then the call is retried once.
     */
    /**
     * Plain http first, the hidden WebView only when Cloudflare actually blocks.
     *
     * The order used to be the other way round - every request went through the WebView first - and
     * that path can spend 15s waiting for clearance, reload, wait 25s more, then try again. With
     * nine home rows making several calls each, the page never finished rendering: it looked like
     * the home page "does not load", when in fact it was still waiting.
     *
     * A cookie-carrying GET takes about a second. The browser is the exception now, not the rule,
     * and it is capped so it can never stall a row.
     */
    private suspend fun once(
        method: String,
        url: String,
        body: String?,
        extra: Map<String, String>,
        viaPage: Boolean = false,
    ): OResp {
        val hdr = LinkedHashMap<String, String>()
        hdr["Accept"] = "application/json"
        if (body != null) hdr["Content-Type"] = "application/json"
        hdr.putAll(extra)
        val tag = "$method ${url.substringBefore("?").takeLast(50)}"

        // Some endpoints are refused to anything that is not the page itself. The token endpoint is
        // one: Cloudflare answers 403 Forbidden to OkHttp even with a valid cf_clearance and a valid
        // Authorization header, while the identical request made from inside the page returns 200.
        // For those, the WebView is tried first.
        if (viaPage) {
            val b = try {
                withTimeoutOrNull(15_000L) { OctaveBrowser.fetch(method, url, body, hdr) }
            } catch (_: Throwable) {
                null
            }
            if (b != null && b.code in 200..299) {
                lastReport = "$tag via page -> HTTP ${b.code}"
                snapshotCookies()
                return b
            }
            lastReport = "$tag page: ${b?.code ?: "unavailable"}"
        }

        val key = accountKey
        val h = headers() + hdr + (if (key.isNotBlank() && !hdr.containsKey("Authorization")) mapOf("Authorization" to "Bearer $key") else emptyMap())

        val n = fromNice(
            if (method == "GET") app.get(url, headers = h, timeout = 20L)
            else app.post(url, headers = h, requestBody = (body ?: "").toRequestBody("application/json".toMediaType()), timeout = 25L)
        )
        val bodyHint = if (n.code in 200..299) "" else " " + n.text.trim().take(140).replace("\n", " ")
        lastReport = "$tag http -> ${n.code}${if (n.challenge) " (Cloudflare)" else ""}$bodyHint"
        if (!n.challenge) {
            if (n.code in 200..299) snapshotCookies()
            return n
        }

        // blocked: the browser is the only thing that can carry a fresh clearance cookie
        try {
            val b = withTimeoutOrNull(12_000L) { OctaveBrowser.fetch(method, url, body, hdr) }
            if (b != null && !b.challenge && b.code != -1) {
                lastReport = "$tag via page -> HTTP ${b.code}"
                return b
            }
            lastReport += " | page: ${b?.code ?: "unavailable"}"
        } catch (e: Throwable) {
            lastReport += " | page threw ${e.message}"
        }
        return n
    }

    /**
     * @param allowSolve open the Cloudflare window when the request is blocked.
     *
     * Only playback and the settings screen pass true. The home page and search do not: a blocked
     * row used to call solveInteractive() and wait up to five minutes for a human, which is why the
     * home page appeared to hang instead of coming back empty.
     */
    private suspend fun request(
        method: String,
        url: String,
        body: String?,
        extra: Map<String, String>,
        allowSolve: Boolean = false,
        viaPage: Boolean = false,
    ): OResp {
        var r = once(method, url, body, extra, viaPage)
        if (r.challenge && allowSolve) {
            r = retryAfterSolve { once(method, url, body, extra, viaPage) } ?: r
        } else if (r.code >= 500) { // the origin sometimes answers 502 - once more
            kotlinx.coroutines.delay(600L)
            r = once(method, url, body, extra, viaPage)
        }
        return r
    }

    suspend fun get(
        url: String,
        json: Boolean = true,
        extra: Map<String, String> = emptyMap(),
        allowSolve: Boolean = false,
        viaPage: Boolean = false,
    ): OResp = request("GET", url, null, extra, allowSolve, viaPage)

    suspend fun postJson(url: String, body: String, allowSolve: Boolean = false, viaPage: Boolean = false): OResp =
        request("POST", url, body, emptyMap(), allowSolve, viaPage)

    /**
     * Fast check that an audio url will serve, without the hidden WebView.
     *
     * head() goes through the page, which is right for the solver but wrong for playback: it can take
     * tens of seconds. A one-byte range request answers the same question in about a second.
     * 200/206/416 all mean the url exists and is reachable.
     */
    suspend fun headFast(url: String): Int = try {
        val r = app.get(url, headers = headers(false) + mapOf("Range" to "bytes=0-0"), timeout = 12L)
        r.code
    } catch (e: Throwable) {
        -1
    }

    /** Status of a HEAD request made from the page (is the song file ready?). -1 = could not tell. */
    suspend fun head(url: String): Int = try {
        OctaveBrowser.fetch("HEAD", url, null, emptyMap())?.code ?: -1
    } catch (_: Throwable) {
        -1
    }

    /** Plain-language trace of the last request (browser step + http step). */
    @Volatile var lastReport: String = "no request yet"

    fun report(): String {
        val clearance = hasClearance() || savedCookies.contains("cf_clearance") || manualCookie.contains("cf_clearance")
        val advice = when {
            clearance ->
                "Cloudflare cookie present. If playback still answers 403 with a JSON body (not a Cloudflare " +
                    "page), the account key was refused - open the site, copy the octv_ key from its storage and " +
                    "paste it into Account key. A 403 with an HTML body means the cookie has expired: run " +
                    "\"Cloudflare check + login\" again."
            accountKey.isNotBlank() ->
                "No Cloudflare cookie. Browsing the home page still works (its rows do not need one), but " +
                    "search, albums and lossless playback answer 403 until you run \"Cloudflare check + login\". " +
                    "Until then playback gives the 30-second preview."
            else ->
                "No Cloudflare cookie and no account key. Run \"Cloudflare check + login\" once: it saves both."
        }
        return "Octave build: $BUILD_TAG\n" +
            "Hidden page: ${OctaveBrowser.lastInfo}\nLast request: $lastReport\n" +
            "cf_clearance: ${if (clearance) "yes" else "NO"}, account key: ${if (accountKey.isNotBlank()) "set" else "missing"}, " +
            "saved cookies: ${if (savedCookies.isNotBlank()) "yes" else "no"}\n" +
            "Cookies seen: ${OctaveBrowser.cookieNames()}\n" +
            "User agent sent: ${userAgent().take(120)}\n\n$advice"
    }

    private suspend fun retryAfterSolve(block: suspend () -> OResp): OResp? {
        // just solved/logged in and still blocked: opening the window again will not help
        if (System.currentTimeMillis() - lastSolvedAt < 120_000L && hasClearance()) {
            toast("Octave: still blocked after login - Settings -> Show last report")
            return null
        }
        // the user passes Cloudflare (and logs in) in the window; nothing else can click the box
        if (!solveInteractive(null)) {
            toast("Octave: Cloudflare check not done - open Extensions -> Octave -> settings -> Cloudflare check + login")
            return null
        }
        return block()
    }

    // ------------------------------------------------------------------ playback key

    /**
     * Short-lived playback key. Uses the account key when there is one; otherwise asks anonymously,
     * and if the server wants an account, creates one (Turnstile-protected accounts need the key
     * pasted in the settings instead).
     */
    /**
     * Re-reads the account key from the logged-in page.
     *
     * The key is what /api/playback-token authenticates with, and it changes when the site reissues
     * it. A stale key answers 403 - exactly like Cloudflare - so on a refusal we ask the page for
     * the current one instead of trusting what was saved months ago.
     */
    suspend fun refreshAccountKey(): String? {
        val fresh = try {
            withTimeoutOrNull(8_000L) { OctaveBrowser.evaluateKey() }
        } catch (_: Throwable) {
            null
        }?.trim().orEmpty()
        if (fresh.startsWith("octv_") && fresh != accountKey) {
            accountKey = fresh
            return fresh
        }
        return null
    }

    /**
     * Checks a key against the endpoint that actually matters.
     *
     * /audio/lossless answers 403 without a valid token, and the token comes from
     * /api/playback-token authenticated with this key - so one call to that endpoint decides whether
     * a key is good, without playing anything. Returns null when the key works, or a short reason.
     */
    suspend fun validateKey(key: String): String? {
        if (key.isBlank()) return "no key"
        return try {
            val r = get("$API/api/playback-token", true, mapOf("Authorization" to "Bearer ${key.trim()}"), viaPage = true)
            when {
                r.code in 200..299 -> {
                    // the answer carries a token; adopt it so the first play does not ask again
                    runCatching {
                        val o = org.json.JSONObject(r.text)
                        val t = o.optString("token")
                        if (t.isNotBlank()) {
                            token = t
                            tokenExp = System.currentTimeMillis() + o.optLong("expiresIn", 1800L) * 1000L
                        }
                    }
                    null
                }
                r.code == 403 || r.code == 401 -> "rejected (HTTP ${r.code})"
                else -> "HTTP ${r.code}"
            }
        } catch (e: Throwable) {
            e.message ?: "failed"
        }
    }

    /**
     * Logs in and adopts the key the page ends up with.
     *
     * The account key is the one credential the extension cannot invent: the site issues it to a
     * logged-in session, and playback is refused without it. This opens the site, lets the user log
     * in, then takes the key from the page and proves it works before saving it.
     */
    suspend fun loginAndAdoptKey(activity: Activity?): String {
        val solved = solveInteractive(activity)
        val fresh = refreshAccountKey()
        val candidate = fresh ?: accountKey
        if (candidate.isBlank()) {
            return if (solved) "window closed, but no account key found - log in to your Octave account, not just the Cloudflare box"
            else "window closed without a result"
        }
        val problem = validateKey(candidate)
        return if (problem == null) "key verified - playback should work"
        else "key $problem - log in again in the window that opened"
    }

    suspend fun playbackToken(forceNew: Boolean = false, allowRefresh: Boolean = false): String? = tokenLock.withLock {
        val now = System.currentTimeMillis()
        token?.let { if (!forceNew && now < tokenExp - 60_000L) return@withLock it }

        suspend fun ask(anonymous: Boolean = false): OResp {
            val key = accountKey
            // playback is the one place where opening the Cloudflare window makes sense
            // never interactive here: playback is a tap, not a settings visit. A window that waits
            // for a human made every play look like it had hung.
            return get(
                "$API/api/playback-token",
                true,
                if (!anonymous && key.isNotBlank()) mapOf("Authorization" to "Bearer $key") else emptyMap(),
                allowSolve = false,
                viaPage = true
            )
        }

        var r = ask()

        // A stale key answers exactly like Cloudflare. First try the key the logged-in page holds
        // right now, which is what the site's own player uses.
        if (r.code in listOf(401, 403) && !r.challenge && allowRefresh) {
            val fresh = refreshAccountKey()
            if (fresh != null) {
                lastReport += " | token: key refreshed from the page"
                r = ask()
            }
        }

        // still refused: ask without the header at all, to separate a bad key from a bad cookie
        if (r.code in listOf(401, 403) && !r.challenge && accountKey.isNotBlank()) {
            val anon = ask(anonymous = true)
            if (anon.code in 200..299) {
                lastReport += " | token: key rejected, anonymous accepted"
                r = anon
            } else {
                lastReport += " | token: key and anonymous both refused (${anon.code})"
            }
        }
        if (r.code in listOf(401, 403) && !r.challenge && accountKey.isBlank()) {
            // anonymous not allowed -> try to create an account
            try {
                val c = postJson("$API/api/account/create", "{}")
                if (c.code in 200..299) {
                    val o = org.json.JSONObject(c.text)
                    val key = o.optString("key").ifBlank { o.optString("apiKey") }.ifBlank { o.optString("token") }
                    if (key.startsWith("octv_")) {
                        accountKey = key
                        r = ask()
                    }
                }
            } catch (_: Throwable) {
            }
        }
        if (r.code !in 200..299) return@withLock null
        return@withLock try {
            val o = org.json.JSONObject(r.text)
            val t = o.optString("token").ifBlank { null } ?: return@withLock null
            token = t
            tokenExp = now + (o.optLong("expiresIn", 1800L) * 1000L)
            t
        } catch (_: Throwable) {
            null
        }
    }

    // ------------------------------------------------------------------ solver window

    fun currentActivity(): Activity? = try { CommonActivity.activity } catch (_: Throwable) { null }

    fun toast(msg: String) {
        main.post {
            try {
                CommonActivity.showToast(msg, Toast.LENGTH_LONG)
            } catch (_: Throwable) {
            }
        }
    }

    @Volatile private var lastToastAt = 0L

    /** Like [toast] but at most once every 2 minutes - for failures that repeat on every row. */
    fun toastOnce(msg: String) {
        val now = System.currentTimeMillis()
        if (now - lastToastAt < 120_000L) return
        lastToastAt = now
        toast(msg)
    }

    fun clearCookies() {
        try {
            val cm = CookieManager.getInstance()
            listOf(API, SITE, "https://octavestreaming.com").forEach { u ->
                cm.getCookie(u)?.split(";")?.map { it.trim().substringBefore("=") }?.forEach {
                    cm.setCookie(u, "$it=; Max-Age=0; path=/")
                }
            }
            cm.flush()
        } catch (_: Throwable) {
        }
        savedCookies = ""
        token = null
    }

    /**
     * Shows the real site full-screen so the user can tick the Cloudflare box.
     * Closes by itself once the page is through and a cf_clearance cookie exists.
     */
    suspend fun solveInteractive(activity: Activity?): Boolean {
        solving?.let { return it.await() }
        val result = solveLock.withLock {
            solving?.let { return@withLock it.await() }
            if (System.currentTimeMillis() - lastSolvedAt < 15_000L && hasClearance()) return@withLock true
            val act = activity ?: currentActivity() ?: return@withLock false
            val d = CompletableDeferred<Boolean>()
            solving = d
            withContext(Dispatchers.Main) { openSolver(act, d) }
            val ok = withTimeoutOrNull(5 * 60_000L) { d.await() } ?: false
            solving = null
            ok
        }
        OctaveBrowser.afterSolve() // the SAME page keeps serving requests - nothing is rebuilt
        if (result) {
            lastSolvedAt = System.currentTimeMillis()
        }
        return result
    }

    /** Finds the site's account key (octv_...) in the page's local storage, i.e. the logged-in account. */
    internal const val KEY_JS =
        "(function(){try{var f=function(s){var m=/octv_[0-9a-f]{16,}/.exec(s||'');return m?m[0]:null};" +
            "for(var i=0;i<localStorage.length;i++){var k=localStorage.key(i);var r=f(localStorage.getItem(k))||f(k);if(r)return r}" +
            "for(var j=0;j<sessionStorage.length;j++){var r2=f(sessionStorage.getItem(sessionStorage.key(j)));if(r2)return r2}" +
            "return f(document.cookie)||''}catch(e){return ''}})()"

    /**
     * Shows the real site full-screen so the user can pass Cloudflare AND log in.
     * It stays open until the user presses Back (or "Done"); then every cookie is saved and the
     * logged-in account key is read from the page. Nothing closes it automatically.
     */
    @SuppressLint("SetTextI18n", "SetJavaScriptEnabled")
    private fun openSolver(act: Activity, done: CompletableDeferred<Boolean>) {
        if (act.isFinishing) {
            done.complete(false); return
        }
        applyManualCookie()
        val density = act.resources.displayMetrics.density
        val dialog = Dialog(act, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val root = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        val bar = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            val p = (10 * density).toInt()
            setPadding(p, p, p, p)
            setBackgroundColor(Color.rgb(30, 30, 30))
        }
        val info = TextView(act).apply {
            text = "Octave: tick \"Verify you are human\", then log in to your account. When you are done press Back (or Done) - this window does not close by itself."
            setTextColor(Color.WHITE)
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val close = Button(act).apply { text = "Done"; isAllCaps = false }
        bar.addView(info)
        bar.addView(close)

        // THE page - the one hidden browser that makes every API call - is moved into this window, so
        // the Cloudflare check and the login happen in the very WebView that is used afterwards.
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val wv = OctaveBrowser.attach(act, root)
        if (wv == null) {
            done.complete(false); return
        }
        dialog.setContentView(root)

        var finished = false
        fun finish() {
            if (finished) return
            finished = true
            var cleaned = false
            fun cleanup() {
                if (cleaned) return
                cleaned = true
                snapshotCookies()
                OctaveBrowser.detach() // back to hidden duty, alive and logged in
                try { if (dialog.isShowing) dialog.dismiss() } catch (_: Throwable) {}
                val ok = hasClearance()
                done.complete(ok)
                toast(
                    if (!ok) "Octave: Cloudflare not solved"
                    else if (accountKey.isNotBlank()) "Octave: saved (Cloudflare + login)"
                    else "Octave: Cloudflare saved - not logged in (playback may need login)"
                )
            }
            // read the logged-in account key one last time, then save everything and close
            try {
                wv.evaluateJavascript(KEY_JS) { r -> saveKeyFrom(r); cleanup() }
                main.postDelayed({ cleanup() }, 2500L) // in case the page never answers
            } catch (_: Throwable) {
                cleanup()
            }
        }
        close.setOnClickListener { finish() }
        // Back button / swipe: leaves the window (this is the ONLY way out besides Done)
        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == android.view.KeyEvent.KEYCODE_BACK && event.action == android.view.KeyEvent.ACTION_UP) {
                finish(); true
            } else false
        }
        dialog.setOnCancelListener { finish() }
        dialog.setOnDismissListener { OctaveBrowser.detach() } // never leave the page parked in a dead window

        try {
            dialog.setCancelable(true)
            dialog.show()
            dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        } catch (_: Throwable) {
            finish()
        }
    }

    internal fun saveKeyFrom(jsResult: String?) {
        val k = jsResult?.trim('"')?.trim() ?: return
        if (k.startsWith("octv_") && k != accountKey) accountKey = k
    }
}
