package com.watchuu.musicbox

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * A challenge window, the same idea as the other modules in this repo.
 *
 * Some of these services put a Cloudflare interstitial in front of their pages, and the clearance
 * cookie it earns is per host. A hidden WebView cannot tap a Turnstile, so this opens a real window
 * the user can solve it in, then keeps the cookie it earned.
 *
 * The cookie is then attached to that host's requests, and to the stream links, by the provider.
 */
object MusicBoxBrowser {
    @Volatile
    private var context: Context? = null

    private val ua =
        "Mozilla/5.0 (Linux; Android 16; SM-S911B Build/BP4A.251205.006) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.47 Mobile Safari/537.36"

    /** the services that can challenge, in the order they appear in settings */
    val hosts = listOf(
        "soundcloud" to "https://soundcloud.com/",
        "deezer" to "https://www.deezer.com/",
        "qobuz" to "https://play.qobuz.com/",
        "tidal" to "https://listen.tidal.com/",
        "apple" to "https://music.apple.com/",
        "youtube" to "https://music.youtube.com/"
    )

    fun init(ctx: Context) {
        context = ctx.applicationContext
    }

    fun cookiesFor(url: String): String? = runCatching {
        CookieManager.getInstance().getCookie(url)
    }.getOrNull()?.ifBlank { null }

    fun hasClearance(url: String): Boolean =
        cookiesFor(url)?.contains("cf_clearance", true) == true

    /**
     * Opens the site in a visible WebView so the challenge can be solved by hand, and keeps
     * whatever cookies result.
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun solve(activity: Activity, url: String) {
        activity.runOnUiThread {
            val wv = WebView(activity)
            wv.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                userAgentString = ua
            }
            runCatching { CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true) }
            wv.webViewClient = WebViewClient()

            val dialog = AlertDialog.Builder(activity)
                .setTitle("Solve the challenge")
                .setView(wv)
                .setPositiveButton("Done") { d, _ ->
                    runCatching { CookieManager.getInstance().flush() }
                    d.dismiss()
                }
                .setNegativeButton("Cancel") { d, _ -> d.dismiss() }
                .create()

            dialog.setOnDismissListener { runCatching { wv.destroy() } }
            dialog.show()
            wv.loadUrl(url)
        }
    }
}
