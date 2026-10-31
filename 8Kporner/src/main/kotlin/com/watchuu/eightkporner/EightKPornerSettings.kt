package com.watchuu.eightkporner

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Typeface
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.lagradost.cloudstream3.CommonActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Settings for 8kporner.
 *
 * The site answers a plain HTTP client with a Cloudflare challenge and the same URL with 200 for a
 * real browser, and a WebView cannot reliably beat Cloudflare's fingerprinting. So the dependable
 * route is the one every Cloudflare-blocked extension ends up using: paste the Cookie header once
 * from a browser that already has clearance, and every request carries it from then on.
 *
 * Open the site in Chrome, copy the whole Cookie header (it must contain cf_clearance), and paste
 * it here. The user agent only needs setting if the cookie was issued to a different one -
 * cf_clearance is bound to the UA that earned it.
 */
object EightKPornerSettings {
    private const val SITE = "https://8kporner.com"

    private var prefs: SharedPreferences? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext
                .getSharedPreferences("8kporner_settings", Context.MODE_PRIVATE)
        }
    }

    private fun str(key: String, default: String = ""): String =
        prefs?.getString(key, default) ?: default

    private fun put(block: SharedPreferences.Editor.() -> Unit) {
        prefs?.edit()?.apply { block(); apply() }
    }

    /** Whole Cookie header from the browser, e.g. "cf_clearance=...; pop_up_18=yes". */
    var cookie: String
        get() = str("cookie").trim()
        set(v) = put { putString("cookie", v.trim()) }

    /** Only needed if the pasted cookie was issued to a different user agent. */
    var userAgent: String
        get() = str("ua").trim()
        set(v) = put { putString("ua", v.trim()) }

    fun currentActivity(ctx: Context? = null): Activity? =
        (ctx as? Activity) ?: try { CommonActivity.activity } catch (_: Throwable) { null }

    fun toast(message: String) {
        scope.launch(Dispatchers.Main) {
            try {
                CommonActivity.showToast(message, Toast.LENGTH_LONG)
            } catch (_: Throwable) {
            }
        }
    }

    fun show(context: Context) {
        val act = currentActivity(context) ?: return
        init(act)
        val pad = (16 * act.resources.displayMetrics.density).toInt()

        val root = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        val info = TextView(act).apply {
            text = "8KPorner sits behind Cloudflare, which a plugin cannot pass on its own.\n\n" +
                "1. Open $SITE in Chrome and pass the check.\n" +
                "2. Copy the whole Cookie header for the site (it must contain cf_clearance).\n" +
                "3. Paste it below and save.\n\n" +
                "The user agent is only needed if the cookie came from a different one - " +
                "cf_clearance is tied to the user agent that earned it."
            textSize = 13f
            setTypeface(typeface, Typeface.NORMAL)
        }

        val cookieInput = EditText(act).apply {
            setText(cookie)
            hint = "cf_clearance=...; pop_up_18=yes"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
        }

        val uaInput = EditText(act).apply {
            setText(userAgent)
            hint = "Mozilla/5.0 (...) Chrome/156.0.0.0 Mobile Safari/537.36  (optional)"
            inputType = InputType.TYPE_CLASS_TEXT
            minLines = 2
        }

        root.addView(info)
        root.addView(cookieInput)
        root.addView(uaInput)

        AlertDialog.Builder(act)
            .setTitle("8KPorner - Cloudflare cookie")
            .setView(root)
            .setPositiveButton("Save") { _, _ ->
                cookie = cookieInput.text.toString()
                userAgent = uaInput.text.toString()
                toast(if (cookie.isBlank()) "Cleared" else "Saved")
            }
            .setNeutralButton("Clear") { _, _ ->
                cookie = ""
                userAgent = ""
                toast("Cleared")
            }
            .setNegativeButton("Cancel", null)
            .create()
            .show()
    }
}
