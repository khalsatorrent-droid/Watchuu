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
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.CloudflareKiller
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

    private fun applyManualCookie() {
        val raw = manualCookie
        if (raw.isBlank()) return
        try {
            val cm = CookieManager.getInstance()
            raw.split(";").map { it.trim() }.filter { it.contains("=") }.forEach {
                cm.setCookie(SITE, "$it; path=/; secure")
                cm.setCookie(API, "$it; path=/; secure")
            }
            cm.flush()
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

    fun isChallenge(r: NiceResponse): Boolean {
        if (r.headers["cf-mitigated"]?.contains("challenge", true) == true) return true
        if (r.code !in listOf(403, 429, 503)) return false
        val head = r.text.take(4000)
        return head.contains("Just a moment", true) || head.contains("challenge-platform", true) ||
            head.contains("bot challenge", true) || head.contains("Attention Required", true) ||
            r.headers["content-type"]?.contains("text/html", true) == true || head.isBlank()
    }

    // ------------------------------------------------------------------ requests

    /** GET with cookie + UA. When Cloudflare answers with a challenge, the user is asked to solve it, then retried once. */
    suspend fun get(url: String, json: Boolean = true, extra: Map<String, String> = emptyMap()): NiceResponse {
        var r = app.get(url, headers = headers(json) + extra, timeout = 30L)
        if (isChallenge(r)) {
            r = retryAfterSolve { app.get(url, headers = headers(json) + extra, timeout = 30L) } ?: r
        }
        if (r.code >= 500 && !isChallenge(r)) { // the origin sometimes answers 502 - once more
            kotlinx.coroutines.delay(600L)
            r = app.get(url, headers = headers(json) + extra, timeout = 30L)
        }
        return r
    }

    suspend fun postJson(url: String, body: String): NiceResponse {
        suspend fun go() = app.post(
            url,
            headers = headers() + mapOf("Content-Type" to "application/json"),
            requestBody = body.toRequestBody("application/json".toMediaType()),
            timeout = 40L
        )
        var r = go()
        if (isChallenge(r)) r = retryAfterSolve { go() } ?: r
        return r
    }

    private suspend fun retryAfterSolve(block: suspend () -> NiceResponse): NiceResponse? {
        // 1) Cloudstream's own non-interactive bypass (works when no click is needed)
        if (!hasClearance()) {
            try {
                val k = CloudflareKiller()
                val r = app.get(SITE, interceptor = k, timeout = 30L)
                if (!isChallenge(r) && hasClearance()) return block()
            } catch (_: Throwable) {
            }
        }
        // 2) the user ticks the box in the solver window
        if (!solveInteractive(null)) {
            toast("Octave: Cloudflare check not done - open Extensions -> Octave -> settings -> Solve Cloudflare")
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
    suspend fun playbackToken(forceNew: Boolean = false): String? = tokenLock.withLock {
        val now = System.currentTimeMillis()
        token?.let { if (!forceNew && now < tokenExp - 60_000L) return@withLock it }

        suspend fun ask(): NiceResponse {
            val key = accountKey
            return get("$API/api/playback-token", true, if (key.isNotBlank()) mapOf("Authorization" to "Bearer $key") else emptyMap())
        }

        var r = ask()
        if (r.code in listOf(401, 403) && !isChallenge(r) && accountKey.isBlank()) {
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
        if (result) lastSolvedAt = System.currentTimeMillis()
        return result
    }

    private const val READY_JS =
        "(function(){try{return !/just a moment|attention required|security verification|un instant|einen moment/i.test(document.title)&&" +
            "document.readyState!=='loading'&&!document.querySelector('#challenge-form,#cf-challenge-running,#challenge-stage,iframe[src*=\"challenges.cloudflare.com\"]')&&" +
            "document.body&&document.body.innerText.length>50}catch(e){return false}})()"

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
            text = "Octave: tick \"Verify you are human\" below. This window closes by itself when done."
            setTextColor(Color.WHITE)
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val close = Button(act).apply { text = "Close"; isAllCaps = false }
        bar.addView(info)
        bar.addView(close)

        val wv = WebView(act)
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = true
            userAgentString = userAgent()
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
        wv.webChromeClient = WebChromeClient()
        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = false
        }
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(wv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        dialog.setContentView(root)

        var finished = false
        fun finish(ok: Boolean) {
            if (finished) return
            finished = true
            try { CookieManager.getInstance().flush() } catch (_: Throwable) {}
            try { wv.stopLoading(); wv.destroy() } catch (_: Throwable) {}
            try { if (dialog.isShowing) dialog.dismiss() } catch (_: Throwable) {}
            done.complete(ok)
            toast(if (ok) "Octave: Cloudflare passed" else "Octave: Cloudflare not solved")
        }
        close.setOnClickListener { finish(hasClearance()) }
        dialog.setOnDismissListener { finish(hasClearance()) }

        var readyCount = 0
        val poll = object : Runnable {
            override fun run() {
                if (finished) return
                try {
                    wv.evaluateJavascript(READY_JS) { r ->
                        readyCount = if (r == "true") readyCount + 1 else 0
                        if (r == "true" && (hasClearance() || readyCount >= 5)) {
                            info.text = "Passed - closing..."
                            main.postDelayed({ finish(true) }, 800L)
                        } else if (!finished) main.postDelayed(this, 1000L)
                    }
                } catch (_: Throwable) {
                    if (!finished) main.postDelayed(this, 1000L)
                }
            }
        }
        try {
            dialog.show()
            dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            // the app package name in X-Requested-With gives a WebView away
            wv.loadUrl("$SITE/", mapOf("X-Requested-With" to ""))
            main.postDelayed(poll, 2500L)
        } catch (_: Throwable) {
            finish(false)
        }
    }
}
