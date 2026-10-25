package com.watchuu.vivarium

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
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.lagradost.cloudstream3.app
import java.io.ByteArrayInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * vivarium.su sits behind Cloudflare ("Checking your browser..." / Turnstile) and signs its own
 * API requests in JavaScript. The extension never signs anything itself: it loads the real site in
 * a WebView so the site's own JS passes Cloudflare, signs the calls and decrypts the stream list,
 * then captures the HLS manifest the player loads.
 *
 * Ways through, chosen in the extension settings (see [VivariumSettings.Mode]):
 *  1. a hidden WebView that loads the site and passes non-interactive checks on its own
 *  2. Cloudstream is not used for the signed API, so the browser is the only path
 *  3. a VISIBLE solver window: the real site full-screen, the user ticks the Turnstile box once,
 *     it closes by itself once the cf_clearance cookie appears
 *  4. a manual cf_clearance cookie pasted in the settings
 */
object VivariumBrowser {
    private const val IDLE_CLOSE_MS = 3 * 60_000L
    private const val FALLBACK_UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    private var appContext: Context? = null
    private var webView: WebView? = null
    private val lock = Mutex()
    private val solveLock = Mutex()
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var lastUse = 0L
    @Volatile private var defaultUa: String? = null

    @Volatile var lastTrace = ""
        private set
    @Volatile var lastMethod = "-"
        private set

    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
        VivariumSettings.init(context)
    }

    private fun site() = VivariumSettings.siteUrl
    private fun home() = site() + "/"

    // ------------------------------------------------------------------ user agent + cookies

    fun userAgent(): String {
        val custom = VivariumSettings.customUa
        var ua = custom.ifBlank {
            defaultUa ?: (try {
                appContext?.let { WebSettings.getDefaultUserAgent(it) }
            } catch (_: Throwable) {
                null
            } ?: FALLBACK_UA).also { defaultUa = it }
        }
        if (custom.isBlank() && VivariumSettings.stripWv) {
            ua = ua.replace("; wv)", ")").replace(" Version/4.0", "")
        }
        return ua
    }

    private fun applyManualCookie() {
        val raw = VivariumSettings.manualCookie
        if (raw.isBlank()) return
        try {
            val cm = CookieManager.getInstance()
            raw.split(";").map { it.trim() }.filter { it.contains("=") }.forEach {
                cm.setCookie(site(), "$it; path=/; secure")
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
        val manual = VivariumSettings.manualCookie
        return listOfNotNull(fromWv?.ifBlank { null }, manual.ifBlank { null }).joinToString("; ").ifBlank { null }
    }

    fun hasClearance(): Boolean = cookies()?.contains("cf_clearance") == true

    private fun isChallengeTitle(title: String): Boolean =
        Regex("just a moment|attention required|security verification|checking your browser|un instant|einen moment", RegexOption.IGNORE_CASE)
            .containsMatchIn(title)

    // ------------------------------------------------------------------ shared WebView setup

    @SuppressLint("SetJavaScriptEnabled")
    private fun configure(wv: WebView, light: Boolean) {
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = true
            loadsImagesAutomatically = !light
            blockNetworkImage = light
            userAgentString = userAgent()
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
    }

    private fun loadSite(wv: WebView) = wv.loadUrl(home(), mapOf("X-Requested-With" to ""))

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

    private const val READY_JS =
        "(function(){try{return !/just a moment|attention required|security verification|checking your browser|un instant|einen moment/i.test(document.title)&&" +
            "document.readyState!=='loading'&&!document.querySelector('#challenge-form,#cf-challenge-running,#challenge-stage')}catch(e){return false}})()"

    private suspend fun pageReady(wv: WebView?): Boolean = eval(wv, READY_JS) == "true"

    // ------------------------------------------------------------------ hidden browser (CF pass)

    private fun createHidden(ctx: Context): WebView {
        applyManualCookie()
        val wv = WebView(ctx)
        configure(wv, VivariumSettings.lightweight)
        val dm = ctx.resources.displayMetrics
        wv.layout(0, 0, dm.widthPixels.coerceAtLeast(360), dm.heightPixels.coerceAtLeast(640))
        wv.webViewClient = WebViewClient()
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

    /** Opens (or reuses) the hidden page and waits until Cloudflare lets it through. */
    suspend fun ensure(timeoutMs: Long = VivariumSettings.timeoutSec * 1000L): Boolean = lock.withLock {
        val ctx = appContext ?: return@withLock false
        lastUse = System.currentTimeMillis()
        if (webView == null) {
            withContext(Dispatchers.Main) { webView = createHidden(ctx) }
            scheduleIdleClose()
        }
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (pageReady(webView)) {
                try {
                    CookieManager.getInstance().flush()
                } catch (_: Throwable) {
                }
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

    suspend fun reset(clearCookies: Boolean, uaChanged: Boolean = false) {
        withContext(Dispatchers.Main) {
            destroyHidden()
            if (clearCookies || uaChanged) {
                try {
                    val cm = CookieManager.getInstance()
                    cm.getCookie(site())?.split(";")?.map { it.substringBefore("=").trim() }?.forEach { name ->
                        cm.setCookie(site(), "$name=; Max-Age=0; path=/")
                        cm.setCookie(site(), "$name=; Max-Age=0; path=/; domain=.${site().substringAfter("://")}")
                    }
                    cm.flush()
                } catch (_: Throwable) {
                }
            }
        }
        lastMethod = "-"
    }

    /** Make sure Cloudflare is passed before a sniff: hidden browser, then (if allowed) the solver window. */
    private suspend fun prepare(trace: StringBuilder): Boolean {
        val mode = VivariumSettings.mode
        if (mode == VivariumSettings.Mode.COOKIE) {
            if (hasClearance()) { trace.append("manual cookie; "); return true }
            trace.append("no manual cookie; "); return false
        }
        if (ensure()) { trace.append("hidden browser passed; "); return true }
        trace.append("hidden browser did not pass in ").append(VivariumSettings.timeoutSec).append("s; ")
        val prompt = mode == VivariumSettings.Mode.SOLVER ||
            (VivariumSettings.autoPrompt && mode == VivariumSettings.Mode.AUTO)
        if (prompt) {
            trace.append("solver window; ")
            if (solveInteractive(null)) { trace.append("solved; "); return true }
            trace.append("not solved; ")
        }
        return hasClearance()
    }

    // ------------------------------------------------------------------ player harvester

    class Sub(val url: String, val lang: String)
    class Stream(val url: String, val quality: Int?, val provider: String)
    class Sniffed(val streams: List<Stream>, val subs: List<Sub>)

    /**
     * The player opens GET /api/es?id=&type=&s=&e= as a fetch() SSE stream and reads
     *   event: source
     *   data: {"provider":"Hermes","streams":[{"url":".../c?t=...","quality":"2160p","type":"hls","subs":[...]}]}
     * events. Those data lines carry every provider's stream + subtitle URLs. We never need to
     * sign anything: a fetch() wrapper installed before the page runs tees the /api/es body (the
     * request itself is still made, and signed, by the site). hls manifests seen on the network
     * are kept as a fallback.
     */
    private class HarvestBridge(val es: StringBuilder, val media: MutableList<String>) {
        @JavascriptInterface
        fun onEs(chunk: String) {
            synchronized(es) { es.append(chunk) }
        }

        @JavascriptInterface
        fun onMedia(url: String) {
            if (url.startsWith("http") && !media.contains(url)) media.add(url)
        }
    }

    // installed at document start so the site's own /api/es fetch is teed as it streams in
    private const val ES_HOOK = "(function(){if(window.__vvHook)return;window.__vvHook=1;try{var of=window.fetch;" +
        "window.fetch=function(i,o){var u=(typeof i==='string')?i:(i&&i.url)||'';var p=of.apply(this,arguments);" +
        "try{if(/\\/api\\/es\\b/.test(String(u))){p.then(function(r){try{var rc=r.clone();var rd=rc.body.getReader();" +
        "var dec=new TextDecoder();(function go(){rd.read().then(function(x){try{if(x.value)vvSniff.onEs(dec.decode(x.value,{stream:true}));}catch(e){}if(!x.done)go();}).catch(function(){});})();}catch(e){}}).catch(function(){});}}catch(e){}" +
        "return p};}catch(e){}})()"

    private const val POKE =
        "(function(){try{var v=document.querySelector('video');if(v){try{v.muted=true;v.play()}catch(e){}}" +
            "var b=document.querySelector('button[aria-label*=\"play\" i],[data-play],.play-button,.vjs-big-play-button');if(b)b.click();}catch(e){}})()"

    private fun isStreamHost(u: String): Boolean {
        val host = u.lowercase().substringAfter("://").substringBefore("/").substringBefore(":")
        return host.endsWith("itsmeowenthankyouforyourservice.online")
    }

    private fun isManifest(u: String): Boolean {
        val l = u.lowercase()
        if (l.contains(".m3u8") || l.substringBefore("?").endsWith(".mpd")) return true
        // tokenized manifest on the stream host: /c?t=...  (segments carry &p=, subs/thumbs don't start playback)
        return isStreamHost(u) && u.contains("/api/cs/") && l.contains("master")
    }

    private fun isSegment(u: String): Boolean {
        val p = u.lowercase().substringBefore("?")
        if (p.endsWith(".ts") || p.endsWith(".m4s") || p.endsWith(".mp4") || p.endsWith(".m4v")) return true
        // tokenized segments look like /c?t=...&p=1
        return isStreamHost(u) && u.contains("/c?") && Regex("[?&]p=").containsMatchIn(u)
    }

    private fun qualityOf(s: String): Int? =
        Regex("""(?i)(2160|1440|1080|720|540|480|360)""").find(s)?.groupValues?.get(1)?.toIntOrNull()

    /** Parse the accumulated SSE text into streams + subtitles. */
    private fun parseEs(text: String): Sniffed {
        val streams = LinkedHashMap<String, Stream>()
        val subs = LinkedHashMap<String, Sub>()
        // each "data: {...}" line is a JSON object; the ones with "streams" carry the players
        Regex("""data:\s*(\{.*\})""").findAll(text).forEach { m ->
            val obj = try { JSONObject(m.groupValues[1]) } catch (_: Throwable) { return@forEach }
            val provider = obj.optString("provider", "Vivarium").ifBlank { "Vivarium" }
            val arr = obj.optJSONArray("streams") ?: return@forEach
            for (i in 0 until arr.length()) {
                val st = arr.optJSONObject(i) ?: continue
                val url = st.optString("url").takeIf { it.startsWith("http") } ?: continue
                val prov = st.optString("provider", provider).ifBlank { provider }
                val q = st.optString("quality").takeIf { it.isNotBlank() }?.let { qualityOf(it) }
                streams.putIfAbsent(url, Stream(url, q, prov))
                st.optJSONArray("subs")?.let { subsArr ->
                    for (j in 0 until subsArr.length()) {
                        val sub = subsArr.optJSONObject(j) ?: continue
                        val su = sub.optString("url").takeIf { it.startsWith("http") } ?: continue
                        val lang = sub.optString("label").ifBlank { sub.optString("lang") }.ifBlank { "Subtitle" }
                            .trim().trimEnd('(').trim()
                        subs.putIfAbsent(su, Sub(su, lang))
                    }
                }
            }
        }
        return Sniffed(streams.values.toList(), subs.values.toList())
    }

    /** Opens the player page (after passing Cloudflare) and harvests the /api/es stream list. */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun sniffPlayer(playerUrl: String, timeoutMs: Long = VivariumSettings.timeoutSec * 1000L): Sniffed {
        val ctx = appContext ?: return Sniffed(emptyList(), emptyList())
        val trace = StringBuilder()
        prepare(trace)
        lastMethod = if (hasClearance()) "browser + cookie" else "browser"

        val es = StringBuilder()
        val media = java.util.Collections.synchronizedList(ArrayList<String>())
        var wv: WebView? = null
        withContext(Dispatchers.Main) {
            applyManualCookie()
            val w = WebView(ctx)
            configure(w, light = false)
            w.settings.mediaPlaybackRequiresUserGesture = false
            val dm = ctx.resources.displayMetrics
            w.layout(0, 0, dm.widthPixels.coerceAtLeast(360), dm.heightPixels.coerceAtLeast(640))
            w.addJavascriptInterface(HarvestBridge(es, media), "vvSniff")
            w.webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    view?.evaluateJavascript(ES_HOOK, null)
                }

                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    val u = request?.url?.toString() ?: return null
                    if (isManifest(u) && !media.contains(u)) media.add(u)
                    if (isSegment(u)) return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                    return null
                }
            }
            w.webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    if (newProgress in 3..95) view?.evaluateJavascript(ES_HOOK, null)
                }
            }
            w.loadUrl(playerUrl, mapOf("X-Requested-With" to ""))
            wv = w
        }
        try {
            val end = System.currentTimeMillis() + timeoutMs
            var sawAt = 0L
            var lastLen = 0
            while (System.currentTimeMillis() < end) {
                delay(700L)
                eval(wv, ES_HOOK)
                eval(wv, POKE)
                val len = synchronized(es) { es.length }
                val hasSource = len > 0 && synchronized(es) { es.indexOf("\"streams\"") >= 0 }
                if (hasSource) {
                    if (sawAt == 0L) sawAt = System.currentTimeMillis()
                    // stop once the stream list stops growing for ~2.5s (providers all delivered)
                    if (len == lastLen && System.currentTimeMillis() - sawAt > 2_500L) break
                }
                lastLen = len
            }
        } finally {
            withContext(Dispatchers.Main) {
                try {
                    wv?.stopLoading(); wv?.loadUrl("about:blank"); wv?.removeJavascriptInterface("vvSniff"); wv?.destroy()
                } catch (_: Throwable) {
                }
            }
        }
        val parsed = parseEs(synchronized(es) { es.toString() })
        // fall back to any manifest caught on the wire if the SSE gave nothing
        val streams = if (parsed.streams.isNotEmpty()) parsed.streams
        else media.map { Stream(it, qualityOf(it), "Vivarium") }
        trace.append("es=").append(synchronized(es) { es.length }).append("B, streams=")
            .append(streams.size).append(", subs=").append(parsed.subs.size).append(", media=").append(media.size)
        lastTrace = trace.toString()
        return Sniffed(streams, parsed.subs)
    }

    /** For the settings "Test connection" button: can we reach the site past Cloudflare? */
    suspend fun test(): String {
        val start = System.currentTimeMillis()
        val trace = StringBuilder()
        val ok = prepare(trace)
        val secs = (System.currentTimeMillis() - start) / 1000
        return if (ok)
            "OK in ${secs}s\nCloudflare passed: ${hasClearance()}\n$trace\n\nUser agent:\n${userAgent()}"
        else
            "Could not pass Cloudflare in ${secs}s.\n$trace\n\nTry \"Solve Cloudflare now\", or paste a cf_clearance cookie.\n\nUser agent:\n${userAgent()}"
    }

    // ------------------------------------------------------------------ visible solver window

    @Volatile private var solving: CompletableDeferred<Boolean>? = null
    @Volatile private var lastSolvedAt = 0L

    suspend fun solveInteractive(activity: Activity?): Boolean {
        solving?.let { return it.await() }
        val result = solveLock.withLock {
            solving?.let { return@withLock it.await() }
            if (System.currentTimeMillis() - lastSolvedAt < 15_000L) return@withLock true
            val act = activity ?: VivariumSettings.currentActivity() ?: return@withLock false
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
            VivariumSettings.solvedUa = userAgent()
        }
        return result
    }

    @SuppressLint("SetTextI18n")
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
            text = "Vivarium: tick \"Verify you are human\" below (if shown). This window closes by itself when done."
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
        }
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(wv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        dialog.setContentView(root)

        var finished = false
        fun finish(ok: Boolean) {
            if (finished) return
            finished = true
            try {
                CookieManager.getInstance().flush()
            } catch (_: Throwable) {
            }
            try {
                wv.stopLoading(); wv.destroy()
            } catch (_: Throwable) {
            }
            try {
                if (dialog.isShowing) dialog.dismiss()
            } catch (_: Throwable) {
            }
            done.complete(ok)
            VivariumSettings.toast(if (ok) "Vivarium: Cloudflare passed" else "Vivarium: Cloudflare not solved")
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
            loadSite(wv)
            main.postDelayed(poll, 2000L)
        } catch (_: Throwable) {
            finish(false)
        }
    }
}
