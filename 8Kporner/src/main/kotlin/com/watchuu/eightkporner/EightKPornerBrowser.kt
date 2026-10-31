package com.watchuu.eightkporner

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
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
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.CloudflareKiller
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 8kporner.com sits behind Cloudflare ("Just a moment…" / Turnstile). Same bypass design as
 * the other Watchuu extensions (FamilyPornHD/NartoDrama), chosen in the extension settings:
 *  1. plain HTTP with the saved cf_clearance cookie + the exact same user agent (fast path)
 *  2. Cloudstream's own CloudflareKiller
 *  3. a hidden WebView that loads the site like a browser
 *  4. a VISIBLE solver window: the real site full-screen, the user ticks the Turnstile box once,
 *     it closes by itself as soon as the cf_clearance cookie appears
 *  5. a manual cookie pasted in the settings
 */
object EightKPornerBrowser {
    private const val IDLE_CLOSE_MS = 3 * 60_000L
    private const val FALLBACK_UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    /**
     * 8kporner gates the content behind an "18+" consent popup (`div#pop_up_18` with a
     * `button#pop_up_18_yes`), and remembers the answer in the `pop_up_18=yes` cookie — it is in
     * 93 of the captured request Cookie headers, right after the user clicked Yes.
     * `adb_user` / `ads_pageview` / `pageViews` / `isfadshow` / `mode` are the other cookies the
     * site sets while walking through that popup; sending them means the wall never appears.
     */
    private val consentCookies = linkedMapOf(
        "pop_up_18" to "yes",
        "adb_user" to "true",
        "ads_pageview" to "1",
        "pageViews" to "1",
        "isfadshow" to "1",
        "mode" to "night"
    )

    private val consentCookieString: String
        get() = consentCookies.entries.joinToString("; ") { "${it.key}=${it.value}" }

    /** Clicks "Yes" on the 18+ popup if it is on screen, and hides it either way. */
    private const val CONSENT_JS =
        "(function(){try{" +
            "var b=document.querySelector('#pop_up_18_yes,button[submit]#pop_up_18_yes,div#pop_up_18 button');" +
            "if(b){try{b.click()}catch(e){}}" +
            "var p=document.getElementById('pop_up_18');if(p){try{p.style.display='none'}catch(e){};try{p.remove()}catch(e){}}" +
            "document.cookie='pop_up_18=yes;path=/';" +
            "document.cookie='adb_user=true;path=/';" +
            "document.cookie='ads_pageview=1;path=/';" +
            "}catch(e){}return true})()"

    private var appContext: Context? = null
    private var webView: WebView? = null
    private val lock = Mutex()
    private val domLock = Mutex()
    private val solveLock = Mutex()
    private val main = Handler(Looper.getMainLooper())
    private val killer by lazy { CloudflareKiller() }

    @Volatile private var lastUse = 0L
    @Volatile private var defaultUa: String? = null
    @Volatile private var lastFinishedUrl: String? = null

    @Volatile
    var lastTrace = ""
        private set

    @Volatile
    var lastMethod = "-"
        private set

    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
        EightKPornerSettings.init(context)
    }

    private fun site() = EightKPornerSettings.siteUrl
    private fun home() = site() + "/"

    // ------------------------------------------------------------------ user agent + cookies

    /** One user agent for everything - cf_clearance is bound to it. */
    fun userAgent(): String {
        val custom = EightKPornerSettings.customUa
        var ua = custom.ifBlank {
            defaultUa ?: (try {
                appContext?.let { WebSettings.getDefaultUserAgent(it) }
            } catch (_: Throwable) {
                null
            } ?: FALLBACK_UA).also { defaultUa = it }
        }
        if (custom.isBlank() && EightKPornerSettings.stripWv) {
            ua = ua.replace("; wv)", ")").replace(" Version/4.0", "")
        }
        return ua
    }

    private fun applyManualCookie() {
        try {
            val cm = CookieManager.getInstance()
            // Accept the 18+ gate up front so the popup never blocks the page.
            consentCookies.forEach { (k, v) ->
                try { cm.setCookie(site(), "$k=$v; path=/") } catch (_: Throwable) {}
            }
            val raw = EightKPornerSettings.manualCookie
            if (raw.isNotBlank()) {
                raw.split(";").map { it.trim() }.filter { it.contains("=") }.forEach {
                    cm.setCookie(site(), "$it; path=/; secure")
                }
            }
            cm.flush()
        } catch (_: Throwable) {
        }
    }

    fun cookies(): String? {
        applyManualCookie()
        val fromWv = try {
            CookieManager.getInstance().getCookie(site())
        } catch (_: Throwable) {
            null
        }
        val manual = EightKPornerSettings.manualCookie
        return listOfNotNull(
            consentCookieString,
            fromWv?.ifBlank { null },
            manual.ifBlank { null }
        ).joinToString("; ").ifBlank { null }
    }

    fun hasClearance(): Boolean = cookies()?.contains("cf_clearance") == true

    fun isChallenge(code: Int, body: String): Boolean {
        val head = body.take(4000)
        return code == 403 || code == 503 || code == 429 ||
            head.contains("<title>Just a moment", true) ||
            head.contains("Performing security verification", true) ||
            head.contains("Checking your browser", true) ||
            (head.contains("challenge-platform") && head.contains("cf_chl", true))
    }

    // ------------------------------------------------------------------ WebView config

    @SuppressLint("SetJavaScriptEnabled")
    private fun configure(wv: WebView, light: Boolean) {
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            userAgentString = userAgent()
            if (light) {
                blockNetworkImage = true
                loadsImagesAutomatically = false
            }
        }
        try {
            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(wv, true)
            }
        } catch (_: Throwable) {}
    }

    private fun loadSite(wv: WebView) {
        wv.loadUrl(home(), mapOf("Referer" to home()))
    }

    /**
     * The 18+ popup is rendered by the site's own JS, which can land after onPageFinished, so a
     * single click can miss it. Keep poking it for a few seconds; CONSENT_JS is idempotent.
     */
    private fun acceptConsent(wv: WebView?) {
        if (wv == null) return
        var tries = 0
        val poke = object : Runnable {
            override fun run() {
                try {
                    wv.evaluateJavascript(CONSENT_JS, null)
                } catch (_: Throwable) {
                }
                if (++tries < 10) main.postDelayed(this, 500L)
            }
        }
        main.postDelayed(poke, 300L)
    }

    // ------------------------------------------------------------------ hidden browser

    private fun createHidden(ctx: Context): WebView {
        applyManualCookie()
        val wv = WebView(ctx)
        configure(wv, EightKPornerSettings.lightweight)
        val dm = ctx.resources.displayMetrics
        wv.layout(0, 0, dm.widthPixels.coerceAtLeast(360), dm.heightPixels.coerceAtLeast(640))
        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                lastFinishedUrl = url
                acceptConsent(view)
            }
        }
        loadSite(wv)
        return wv
    }

    private fun destroyHidden() {
        val wv = webView ?: return
        webView = null
        try {
            wv.stopLoading()
            wv.loadUrl("about:blank")
            wv.destroy()
        } catch (_: Throwable) {
        }
    }

    private suspend fun eval(wv: WebView?, js: String): String? = withContext(Dispatchers.Main) {
        if (wv == null) return@withContext null
        val d = CompletableDeferred<String?>()
        try {
            wv.evaluateJavascript(js) { d.complete(it) }
        } catch (_: Throwable) {
            d.complete(null)
        }
        withTimeoutOrNull(5_000L) { d.await() }
    }

    // 8kporner uses the same "Just a moment…" Cloudflare check; we look for
    // its real content once the challenge is gone
    private const val READY_JS =
        "(function(){try{return !/just a moment|attention required|security verification|un instant|einen moment/i.test(document.title)&&" +
            "document.readyState!=='loading'&&!document.querySelector('#challenge-form,#cf-challenge-running,#challenge-stage')&&" +
            "!/Checking your browser/i.test(document.body?document.body.innerText.slice(0,300):'')&&" +
            "!!document.querySelector('div.keep-padding,div.pt_srch_vids,h4,.ra-thumb,.container')}catch(e){return false}})()"

    private suspend fun pageReady(wv: WebView?): Boolean = eval(wv, READY_JS) == "true"

    /** Like eval(), but a full page DOM can take longer than the 5s default to serialise. */
    private suspend fun evalLong(wv: WebView?, js: String, timeoutMs: Long): String? =
        withContext(Dispatchers.Main) {
            if (wv == null) return@withContext null
            val d = CompletableDeferred<String?>()
            try {
                wv.evaluateJavascript(js) { d.complete(it) }
            } catch (_: Throwable) {
                d.complete(null)
            }
            withTimeoutOrNull(timeoutMs) { d.await() }
        }

    /**
     * Load the page in the hidden browser and hand back its rendered DOM.
     *
     * The capture shows the site only ever answers a real browser: / returns 503 + a Cloudflare
     * challenge to the client and 200 to the WebView, and the 18+ gate plus the listing grid are
     * both produced by the site's own JS. So for document pages the WebView is not a fallback, it
     * is the only thing that can see the content.
     */
    /**
     * Bounded on purpose. The first version gave every step its own full timeout, so one page
     * could take 60s (killer) + 35s (ensure) + 35s (navigation) + 20s (DOM) and the home screen
     * just span forever. Now the whole attempt has a fixed budget, and a navigation already
     * showing the wanted page is reused instead of reloaded.
     */
    private suspend fun viaWebViewHtml(url: String, trace: StringBuilder): String? {
        trace.append("webview dom; ")
        // The app asks for several home sections at once and they all drive this one WebView,
        // which raced on lastFinishedUrl and never returned. One navigation at a time.
        return domLock.withLock {
            try {
                if (!ensure(timeoutMs = 12_000L)) {
                    trace.append("webview not ready; ")
                    return@withLock null
                }
                val wv = webView ?: return@withLock null

                val wanted = url.substringAfter(site()).ifBlank { "/" }
                val alreadyThere = lastFinishedUrl?.contains(wanted) == true
                if (!alreadyThere) {
                    lastFinishedUrl = null
                    withContext(Dispatchers.Main) {
                        try {
                            wv.loadUrl(url, mapOf("Referer" to home()))
                        } catch (_: Throwable) {
                        }
                    }
                    val navDeadline = System.currentTimeMillis() + 15_000L
                    var navigated = false
                    while (System.currentTimeMillis() < navDeadline) {
                        delay(350L)
                        val finished = lastFinishedUrl
                        if (finished != null && (finished == url || finished.contains(wanted))) {
                            navigated = true
                            break
                        }
                    }
                    if (!navigated) {
                        trace.append("webview nav timeout; ")
                        return@withLock null
                    }
                    // Let the site's own JS build the grid before the DOM is taken.
                    delay(700L)
                }

                val raw = evalLong(wv, "document.documentElement.outerHTML", 10_000L)
                val html = raw?.let { decodeJsString(it) }
                if (html.isNullOrBlank() || html.length < 500) {
                    trace.append("webview empty; ")
                    return@withLock null
                }
                try {
                    CookieManager.getInstance().flush()
                } catch (_: Throwable) {
                }
                html
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                trace.append("webview error ").append(e.javaClass.simpleName).append("; ")
                null
            }
        }
    }

    /** evaluateJavascript hands back a JSON string literal; turn it back into plain HTML. */
    private fun decodeJsString(raw: String): String {
        var s = raw
        if (s.length >= 2 && s.startsWith("\"") && s.endsWith("\"")) s = s.substring(1, s.length - 1)
        if (!s.contains('\\')) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val n = s[i + 1]) {
                    'n' -> { sb.append('\n'); i += 2 }
                    'r' -> { sb.append('\r'); i += 2 }
                    't' -> { sb.append('\t'); i += 2 }
                    'b' -> { sb.append('\b'); i += 2 }
                    'f' -> { sb.append('\u000C'); i += 2 }
                    'u' -> {
                        val hex = s.substring(i + 2, minOf(i + 6, s.length))
                        val code = hex.toIntOrNull(16)
                        if (code != null) sb.append(code.toChar())
                        i += 6
                    }
                    else -> { sb.append(n); i += 2 }
                }
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    /** Opens (or reuses) the hidden page and waits until Cloudflare lets it through. */
    suspend fun ensure(timeoutMs: Long = EightKPornerSettings.timeoutSec * 1000L): Boolean = lock.withLock {
        val ctx = appContext ?: return@withLock false
        lastUse = System.currentTimeMillis()
        if (webView == null) {
            withContext(Dispatchers.Main) { webView = createHidden(ctx) }
            scheduleIdleClose()
        }
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (pageReady(webView)) {
                try { CookieManager.getInstance().flush() } catch (_: Throwable) {}
                return@withLock true
            }
            delay(700L)
        }
        withContext(Dispatchers.Main) { destroyHidden() }
        false
    }

    private fun scheduleIdleClose() {
        main.postDelayed(object : Runnable {
            override fun run() {
                if (webView == null) return
                if (System.currentTimeMillis() - lastUse >= IDLE_CLOSE_MS) destroyHidden()
                else main.postDelayed(this, 30_000L)
            }
        }, 30_000L)
    }

    // ------------------------------------------------------------------ visible solver window

    @Volatile private var solving: CompletableDeferred<Boolean>? = null
    @Volatile private var lastSolvedAt = 0L
    @Volatile private var lastPromptAt = 0L

    /** Once a solve has worked, the clearance lasts a while - do not re-prompt for it. */
    private const val SOLVED_TRUST_MS = 10 * 60_000L
    /** After a window is dismissed unsolved, do not nag again immediately. */
    private const val PROMPT_BACKOFF_MS = 60_000L

    /**
     * Shows the real site full-screen so the user can tick the Cloudflare box.
     * Closes by itself once the page is through and a cf_clearance cookie exists.
     */
    suspend fun solveInteractive(activity: Activity?): Boolean {
        solving?.let { return it.await() }
        val result = solveLock.withLock {
            solving?.let { return@withLock it.await() }
            // The home screen asks for eight sections; without this every failed section opened its
            // own challenge window, which is the "keeps repeating" behaviour. One solve is trusted
            // for ten minutes, and a dismissed window buys a minute of quiet.
            if (System.currentTimeMillis() - lastSolvedAt < SOLVED_TRUST_MS) return@withLock true
            if (System.currentTimeMillis() - lastPromptAt < PROMPT_BACKOFF_MS) return@withLock false
            lastPromptAt = System.currentTimeMillis()
            // CommonActivity.activity is null until the host activity is attached; retry briefly
            // instead of failing straight away, which used to end as "Cloudflare not passed".
            var act = activity ?: EightKPornerSettings.currentActivity()
            var waited = 0
            while (act == null && waited < 6_000) {
                delay(500L)
                waited += 500
                act = EightKPornerSettings.currentActivity()
            }
            if (act == null) return@withLock false
            val d = CompletableDeferred<Boolean>()
            solving = d
            withContext(Dispatchers.Main) { openSolver(act, d) }
            val ok = withTimeoutOrNull(5 * 60_000L) { d.await() } ?: false
            solving = null
            ok
        }
        if (result) {
            lastSolvedAt = System.currentTimeMillis()
            withContext(Dispatchers.Main) { destroyHidden() }
            lastMethod = "-"
            EightKPornerSettings.solvedUa = userAgent()
        }
        return result
    }

    @SuppressLint("SetTextI18n")
    private fun openSolver(act: Activity, done: CompletableDeferred<Boolean>) {
        if (act.isFinishing) { done.complete(false); return }
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
            text = "8KPorner: tick \"Verify you are human\" below (if shown). This window closes by itself when done."
            setTextColor(Color.WHITE)
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val close = Button(act).apply {
            text = "Close"
            isAllCaps = false
        }
        bar.addView(info)
        bar.addView(close)

        val wv = WebView(act)
        configure(wv, light = false)
        wv.webChromeClient = WebChromeClient()
        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = false

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                acceptConsent(view)
            }
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
            EightKPornerSettings.toast(if (ok) "8KPorner: Cloudflare passed ✓" else "8KPorner: Cloudflare not solved")
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
                        if (r == "true" && (hasClearance() || readyCount >= 4)) {
                            info.text = "Passed - closing…"
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
            loadSite(wv)
            main.postDelayed(poll, 2000L)
        } catch (_: Throwable) {
            finish(false)
        }
    }

    // ------------------------------------------------------------------ main entry

    /**
     * The 18+ gate is server side: the page calls GET /aj/main/yes_18?hash=<40 hex> and only then
     * do the listing pages render cards. Sending pop_up_18 as a cookie is not enough, so the first
     * successful response is used to lift the hash out of the page and the gate is accepted
     * explicitly, after which the same URL is fetched again.
     */
    @Volatile private var consentDone = false
    private val consentHashRx = Regex("[0-9a-f]{40}")

    /** GET an 8kporner url as text, going through Cloudflare the way the settings say. */
    suspend fun get(url: String, referer: String): String {
        val body = getRaw(url, referer)
        if (consentDone) return body

        // If the page already has cards there is nothing to unlock, so skip the extra round-trip.
        if (body.contains("keep-padding") || body.contains("/watch/")) {
            consentDone = true
            return body
        }
        val hash = consentHashRx.find(body)?.value ?: return body
        val accepted = runCatching {
            val h = HashMap<String, String>()
            h["User-Agent"] = userAgent()
            h["Referer"] = referer
            h["X-Requested-With"] = "XMLHttpRequest"
            cookies()?.let { h["Cookie"] = it }
            app.get("${site()}/aj/main/yes_18?hash=$hash", headers = h, timeout = 20L).code in 200..299
        }.getOrDefault(false)

        if (!accepted) return body
        consentDone = true
        try { CookieManager.getInstance().flush() } catch (_: Throwable) {}
        // The body we just read was the gate page; fetch the real one now that it is accepted.
        return runCatching { getRaw(url, referer) }.getOrElse { body }
    }

    private suspend fun getRaw(url: String, referer: String): String {
        val mode = EightKPornerSettings.mode
        val trace = StringBuilder()
        fun done(method: String, body: String): String {
            lastMethod = method
            trace.append("-> ok via ").append(method)
            lastTrace = trace.toString()
            return body
        }
        direct(url, referer, trace)?.let { return done("cookie (fast)", it) }

        when (mode) {
            EightKPornerSettings.Mode.COOKIE -> {}
            EightKPornerSettings.Mode.CLOUDSTREAM -> viaKiller(url, referer, trace)?.let { return done("Cloudstream bypass", it) }
            // Auto: Cloudstream's own killer is cheap and often enough; when it is not, render the
            // page in the WebView and read the DOM, because this site only serves content to a
            // real browser (the client gets a 503 challenge on the very same URL).
            EightKPornerSettings.Mode.AUTO -> {
                // The WebView is tried first in Auto: CloudstreamKiller spends up to a minute on
                // its own WebView and the capture shows it never gets this site through anyway.
                viaWebViewHtml(url, trace)?.let { return done("webview DOM", it) }
                viaKiller(url, referer, trace)?.let { return done("Cloudstream bypass", it) }
            }
            EightKPornerSettings.Mode.HIDDEN -> {
                viaWebViewHtml(url, trace)?.let { return done("webview DOM", it) }
                viaHidden(url, trace)?.let { return done("hidden browser", it) }
            }
            EightKPornerSettings.Mode.SOLVER -> {}
        }

        val prompt = mode == EightKPornerSettings.Mode.SOLVER ||
            (EightKPornerSettings.autoPrompt && (mode == EightKPornerSettings.Mode.AUTO || mode == EightKPornerSettings.Mode.CLOUDSTREAM))
        if (prompt) {
            trace.append("solver window; ")
            if (solveInteractive(null)) {
                direct(url, referer, trace)?.let { return done("solver + cookie", it) }
                viaWebViewHtml(url, trace)?.let { return done("solver + webview DOM", it) }
                viaHidden(url, trace)?.let { return done("solver + hidden browser", it) }
            } else trace.append("not solved; ")
        }
        lastTrace = trace.toString()
        throw ErrorLoadingException(
            if (mode == EightKPornerSettings.Mode.COOKIE)
                "8KPorner: the manual cookie was rejected. Paste a fresh cf_clearance + matching user agent in the extension settings."
            else "8KPorner: Cloudflare not passed. Open Extensions > 8KPorner > settings and tap \"Solve Cloudflare now\"."
        )
    }

    private suspend fun direct(url: String, referer: String, trace: StringBuilder): String? {
        val cookie = cookies()
        return try {
            val h = HashMap<String, String>()
            h["User-Agent"] = userAgent()
            h["Referer"] = referer
            h["Accept-Language"] = "en-US,en;q=0.9"
            h["Accept"] = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            if (!cookie.isNullOrBlank()) h["Cookie"] = cookie
            val r = app.get(url, headers = h, timeout = 20L)
            val body = r.text
            if (r.code in 200..299 && !isChallenge(r.code, body)) body
            else {
                trace.append("direct HTTP ").append(r.code).append(if (isChallenge(r.code, body)) " (challenge)" else "").append("; ")
                null
            }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            trace.append("direct error ").append(e.javaClass.simpleName).append("; ")
            null
        }
    }

    private suspend fun viaKiller(url: String, referer: String, trace: StringBuilder): String? = try {
        val h = HashMap<String, String>()
        h["Referer"] = referer
        h["User-Agent"] = userAgent()
        cookies()?.let { h["Cookie"] = it }
        val r = app.get(url, headers = h, interceptor = killer, timeout = 20L)
        val body = r.text
        if (r.code in 200..299 && !isChallenge(r.code, body)) body
        else { trace.append("cloudstream HTTP ").append(r.code).append("; "); null }
    } catch (e: Throwable) {
        if (e is CancellationException) throw e
        trace.append("cloudstream error ").append(e.javaClass.simpleName).append("; ")
        null
    }

    private suspend fun viaHidden(url: String, trace: StringBuilder): String? {
        trace.append("hidden browser; ")
        return try {
            val ok = ensure()
            if (!ok) { trace.append("timeout; "); return null }
            val cookie = cookies()
            if (cookie.isNullOrBlank()) { trace.append("no cookie; "); return null }
            val h = HashMap<String, String>()
            h["User-Agent"] = userAgent()
            h["Referer"] = home()
            h["Cookie"] = cookie
            val r = app.get(url, headers = h, timeout = 20L)
            val body = r.text
            if (r.code in 200..299 && !isChallenge(r.code, body)) body
            else { trace.append("hidden HTTP ").append(r.code).append("; "); null }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            trace.append("hidden error ").append(e.javaClass.simpleName).append("; ")
            null
        }
    }

}
