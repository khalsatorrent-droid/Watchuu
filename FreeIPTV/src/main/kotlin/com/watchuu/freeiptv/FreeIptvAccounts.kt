package com.watchuu.freeiptv

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import com.lagradost.cloudstream3.CommonActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONTokener
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

data class FreeIptvAccount(
    val server: String,
    val user: String,
    val pass: String,
    val expiresAt: Long,
)

/**
 * Keeps one working account. The site hands out a 24 hour Xtream account behind a Cloudflare
 * Turnstile check, so a plain HTTP call cannot create one: a WebView opens the page, waits for the
 * check to pass, presses "Create free IPTV account" and reads the result. The account is renewed
 * whenever less than [REFRESH_MARGIN_MS] is left, so playback never starts on a dying account.
 */
class FreeIptvAccounts(
    private val appContext: Context,
    private val prefs: SharedPreferences?,
) {
    private val mutex = Mutex()
    @Volatile private var cached: FreeIptvAccount? = null
    @Volatile var lastError: String? = null
        private set

    fun saved(): FreeIptvAccount? {
        cached?.let { return it }
        val p = prefs ?: return null
        val user = p.getString("user", null) ?: return null
        val pass = p.getString("pass", null) ?: return null
        val server = p.getString("server", null) ?: return null
        return FreeIptvAccount(server, user, pass, p.getLong("expires", 0L)).also { cached = it }
    }

    private fun save(a: FreeIptvAccount) {
        cached = a
        prefs?.edit()
            ?.putString("server", a.server)?.putString("user", a.user)?.putString("pass", a.pass)
            ?.putLong("expires", a.expiresAt)?.apply()
    }

    fun forget() {
        cached = null
        prefs?.edit()?.clear()?.apply()
    }

    /** The account to use now; makes a new one when there is none, it is expiring, or [force]. */
    suspend fun current(force: Boolean = false): FreeIptvAccount? = mutex.withLock {
        val have = saved()
        if (!force && have != null && have.expiresAt - System.currentTimeMillis() > REFRESH_MARGIN_MS) return have
        val fresh = runCatching { createWithWebView() }
            .onFailure { lastError = "${it.javaClass.simpleName} ${it.message}"; Log.d(TAG, "create failed: $lastError") }
            .getOrNull()
        if (fresh != null) {
            lastError = null
            save(fresh)
            return fresh
        }
        // keep a still-valid account if renewing failed
        if (have != null && have.expiresAt > System.currentTimeMillis()) have else null
    }

    // ---- WebView account creation

    private data class PageState(val user: String, val pass: String, val server: String, val exp: String, val enabled: Boolean)

    private suspend fun createWithWebView(): FreeIptvAccount? = withContext(Dispatchers.Main) {
        val act: Activity? = CommonActivity.activity
        val wv = WebView(act ?: appContext)
        var dialog: AlertDialog? = null
        var cancelled = false
        try {
            wv.settings.javaScriptEnabled = true
            wv.settings.domStorageEnabled = true
            wv.settings.userAgentString = UA
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
            wv.loadUrl("$SITE/")

            val start = System.currentTimeMillis()
            var clickedAt = 0L
            var reloadedAt = 0L
            while (System.currentTimeMillis() - start < TIMEOUT_MS && !cancelled) {
                delay(700)
                val st = readState(wv) ?: continue
                val now = System.currentTimeMillis()

                if (st.user.isNotBlank() && st.pass.isNotBlank() && st.server.isNotBlank()) {
                    val exp = parseExpiry(st.exp) ?: (now + 24L * 3600_000L - 10 * 60_000L)
                    if (exp - now > REFRESH_MARGIN_MS) {
                        return@withContext FreeIptvAccount(st.server.trimEnd('/'), st.user, st.pass, exp)
                    }
                    // the page still shows an old account: go back to the form
                    if (now - reloadedAt > 8_000L) { reloadedAt = now; wv.loadUrl("$SITE/") }
                } else if (st.enabled && now - clickedAt > 20_000L) {
                    clickedAt = now
                    wv.evaluateJavascript("document.getElementById('create-btn').click()", null)
                }

                // the check sometimes wants a tap: show the page after a while
                if (dialog == null && act != null && now - start > SHOW_AFTER_MS) {
                    dialog = runCatching {
                        wv.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (420 * act.resources.displayMetrics.density).toInt())
                        AlertDialog.Builder(act)
                            .setTitle("Free IPTV - one moment")
                            .setMessage("Getting a fresh free account. If a check box shows below, tick it.")
                            .setView(wv)
                            .setNegativeButton("Cancel") { _, _ -> cancelled = true }
                            .show()
                    }.getOrNull()
                }
            }
            null
        } finally {
            runCatching { dialog?.dismiss() }
            runCatching { (wv.parent as? ViewGroup)?.removeView(wv) }
            runCatching { wv.destroy() }
        }
    }

    private suspend fun readState(wv: WebView): PageState? {
        val js = """(function(){var g=function(i){var e=document.getElementById(i);return e?e.value:''};
            var b=document.getElementById('create-btn');
            return JSON.stringify({user:g('accUser'),pass:g('accPass'),server:g('serverUrl'),exp:g('accExp'),enabled:!!b&&!b.disabled});})()"""
        val result = kotlinx.coroutines.CompletableDeferred<String?>()
        wv.evaluateJavascript(js) { result.complete(it) }
        val raw = result.await() ?: return null
        return runCatching {
            val inner = JSONTokener(raw).nextValue() as? String ?: return null
            val o = JSONObject(inner)
            PageState(o.optString("user"), o.optString("pass"), o.optString("server"), o.optString("exp"), o.optBoolean("enabled"))
        }.getOrNull()
    }

    companion object {
        const val SITE = "https://freeiptv2023-d.ottc.xyz"
        const val TAG = "FreeIPTV"
        const val PREFS_NAME = "FreeIPTV"
        const val REFRESH_MARGIN_MS = 30L * 60_000L
        private const val TIMEOUT_MS = 120_000L
        private const val SHOW_AFTER_MS = 12_000L
        private const val UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

        /** "23-11-2026 10:05:17 am" - the site's clock is UTC. */
        fun parseExpiry(text: String): Long? = runCatching {
            SimpleDateFormat("dd-MM-yyyy hh:mm:ss a", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                .parse(text.trim())?.time
        }.getOrNull()
    }
}
