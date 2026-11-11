package com.watchuu.cinesrc

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.Collections
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * The embed page solves its own protections - DDoS-Guard cookies, the wasm proof of work at
 * /api/c/*, the Next.js server-action hashes - and then asks for a playlist. Those playlist
 * requests are what this watches for, so none of that has to be reimplemented or kept in step
 * with their next deploy.
 *
 * Every /api/playlist/ request the page makes while it settles is recorded, which is how more
 * than one server is picked up: the page fetches a playlist per server it is asked for, and the
 * injected script asks for each in turn.
 */
object CineSrcBrowser {
    @Volatile
    private var context: Context? = null

    fun init(ctx: Context) {
        context = ctx.applicationContext
    }

    private val ua =
        "Mozilla/5.0 (Linux; Android 16; SM-S911B Build/BP4A.251205.006) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.47 Mobile Safari/537.36"

    /**
     * Loads the embed page and returns every playlist url it asks for, in the order they arrived.
     * The page is nudged to try each server it lists, then given a moment to finish.
     */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun playlists(embedUrl: String, waitMs: Long = 20_000L): List<String> =
        suspendCancellableCoroutine { cont ->
            val ctx = context
            if (ctx == null) {
                cont.resume(emptyList())
                return@suspendCancellableCoroutine
            }

            val found = Collections.synchronizedList(ArrayList<String>())
            val handler = Handler(Looper.getMainLooper())
            var finished = false

            fun finish() {
                if (finished) return
                finished = true
                handler.post { cont.resume(found.toList()) }
            }

            handler.post {
                val wv = WebView(ctx)
                wv.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    mediaPlaybackRequiresUserGesture = false
                    userAgentString = ua
                }
                android.webkit.CookieManager.getInstance()
                    .setAcceptThirdPartyCookies(wv, true)

                wv.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        val url = request?.url?.toString().orEmpty()
                        if (url.contains("/api/playlist/") && !found.contains(url)) {
                            found.add(url)
                        }
                        return null
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        // ask the page for every server it offers, one after another
                        view?.evaluateJavascript(
                            """
                            (function() {
                              try {
                                var nodes = Array.prototype.slice.call(
                                  document.querySelectorAll('button, [role=button], [data-server], li, a')
                                );
                                var seen = {};
                                var i = 0;
                                function next() {
                                  if (i >= nodes.length) return;
                                  var el = nodes[i++];
                                  var t = (el.innerText || el.textContent || '').trim().toLowerCase();
                                  if (!t || t.length > 40) { return next(); }
                                  if (!/nebula|light|wave|orbit|storm|aurora|flux|river|surge|mist|tempest|thunder|paris|brisa|server/.test(t)) {
                                    return next();
                                  }
                                  if (seen[t]) { return next(); }
                                  seen[t] = 1;
                                  try { el.click(); } catch (e) {}
                                  setTimeout(next, 1500);
                                }
                                next();
                              } catch (e) {}
                            })();
                            """.trimIndent(),
                            null
                        )
                    }
                }

                wv.loadUrl(embedUrl)
                handler.postDelayed({
                    runCatching { wv.stopLoading() }
                    runCatching { wv.destroy() }
                    finish()
                }, waitMs)
            }

            cont.invokeOnCancellation {
                if (!finished) {
                    finished = true
                    handler.post { cont.resume(emptyList()) }
                }
            }
        }
}
