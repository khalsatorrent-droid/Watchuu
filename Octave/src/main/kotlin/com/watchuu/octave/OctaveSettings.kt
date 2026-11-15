package com.watchuu.octave

import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Settings dialog: Extensions -> Octave -> gear. The Cloudflare check is done here by the user. */
object OctaveSettings {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    fun show(context: Context) {
        val act = OctaveCf.currentActivity() ?: (context as? android.app.Activity) ?: return
        OctaveCf.init(act)
        val pad = dp(act, 16)
        val root = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        fun header(t: String) = TextView(act).apply {
            text = t
            setTypeface(typeface, Typeface.BOLD)
            textSize = 15f
            setPadding(0, dp(act, 14), 0, dp(act, 4))
        }
        fun note(t: String) = TextView(act).apply { text = t; textSize = 12f; alpha = 0.75f }
        fun field(hint: String, value: String) = EditText(act).apply {
            this.hint = hint
            setText(value)
            textSize = 13f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 1; maxLines = 4
        }
        fun button(t: String, onClick: () -> Unit) = Button(act).apply {
            text = t; isAllCaps = false; setOnClickListener { onClick() }
        }

        val status = TextView(act).apply { textSize = 14f }
        fun refresh() {
            status.text = if (OctaveCf.hasClearance()) "Status: cf_clearance cookie saved"
            else "Status: no Cloudflare cookie yet - tap \"Cloudflare check + login\""
        }
        refresh()

        root.addView(header("Cloudflare"))
        root.addView(status)
        root.addView(note("Octave is protected by Cloudflare. Open the site, tick the box and log in to your Octave account. The window stays open until you press Back; then the cookies and your login are saved on this device only. Repeat when it expires (playback or search start failing)."))
        root.addView(button("Cloudflare check + login") {
            scope.launch {
                OctaveCf.solveInteractive(act)
                withContext(Dispatchers.Main) { refresh() }
            }
        })
        root.addView(button("Test connection") {
            scope.launch {
                val msg = try {
                    val r = OctaveCf.get("${OctaveCf.API}/api/lastfm/global-chart-tracks?limit=1")
                    val t = OctaveCf.playbackToken(true)
                    "API: HTTP ${r.code}, playback key: ${if (t != null) "OK" else "failed (set an account key below?)"}"
                } catch (e: Throwable) {
                    "Test failed: ${e.message}"
                }
                OctaveCf.toast("Octave: $msg")
                withContext(Dispatchers.Main) { refresh() }
            }
        })
        root.addView(button("Clear Cloudflare cookies") {
            OctaveCf.clearCookies(); refresh()
            OctaveCf.toast("Octave: cookies cleared")
        })

        root.addView(header("Account key (optional)"))
        root.addView(note("Created automatically when the site allows it. If playback says 'no playback key', paste the key from the website here (starts with octv_)."))
        val keyField = field("octv_...", OctaveCf.accountKey)
        root.addView(keyField)

        root.addView(header("Manual cookie (optional)"))
        root.addView(note("Only if you solved Cloudflare in another browser: paste its cf_clearance cookie AND that browser's exact user agent."))
        val cookieField = field("cf_clearance=...", OctaveCf.manualCookie)
        val uaField = field("User agent (leave empty to use this device's)", OctaveCf.customUa)
        root.addView(cookieField)
        root.addView(uaField)

        AlertDialog.Builder(act)
            .setTitle("Octave settings")
            .setView(ScrollView(act).apply { addView(root) })
            .setPositiveButton("Save") { _, _ ->
                OctaveCf.accountKey = keyField.text.toString()
                OctaveCf.manualCookie = cookieField.text.toString()
                OctaveCf.customUa = uaField.text.toString()
                OctaveCf.toast("Octave: saved")
            }
            .setNegativeButton("Close", null)
            .show()
    }
}
