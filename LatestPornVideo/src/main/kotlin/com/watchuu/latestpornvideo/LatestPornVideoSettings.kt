package com.watchuu.latestpornvideo

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.lagradost.cloudstream3.CommonActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Cloudflare settings, shown from Extensions -> LatestPornVideo -> the gear icon.
 *
 * Same model as the FamilyPornHD extension in this repo: pick how the challenge is handled, and
 * there are buttons to solve it now, test the connection, and clear the stored cookies.
 */
object LatestPornVideoSettings {
    enum class Mode(val label: String, val help: String) {
        AUTO("Auto (recommended)", "Plain request first, then Cloudstream's solver, then a hidden browser, and only then the solver window."),
        SOLVER("Solver window first", "Open the site so the box can be ticked before anything else is tried. Slowest, most reliable."),
        CLOUDSTREAM("Cloudstream built-in bypass", "Only use Cloudstream's CloudflareKiller - no windows, no hidden browser."),
        HIDDEN("Hidden browser only", "Never open a window; render the page in the background and read it back."),
        COOKIE("Pasted cookie only", "Use only the cookie and user agent you paste below. Nothing else is tried.")
    }

    private var prefs: SharedPreferences? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    const val DEFAULT_URL = "https://latestpornvideo.com"

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext
                .getSharedPreferences("latestpornvideo_settings", Context.MODE_PRIVATE)
        }
    }

    private fun str(k: String, d: String = ""): String = prefs?.getString(k, d) ?: d
    private fun bool(k: String, d: Boolean): Boolean = prefs?.getBoolean(k, d) ?: d
    private fun put(block: SharedPreferences.Editor.() -> Unit) {
        prefs?.edit()?.apply { block(); apply() }
    }

    var mode: Mode
        get() = try { Mode.valueOf(str("mode", Mode.AUTO.name)) } catch (_: Throwable) { Mode.AUTO }
        set(v) = put { putString("mode", v.name) }

    var autoPrompt: Boolean
        get() = bool("auto_prompt", true)
        set(v) = put { putBoolean("auto_prompt", v) }

    var customUa: String
        get() = str("custom_ua").trim()
        set(v) = put { putString("custom_ua", v.trim()) }

    var manualCookie: String
        get() = str("manual_cookie").trim()
        set(v) = put { putString("manual_cookie", v.trim()) }

    /** Alias kept so the browser reads one name. */
    var cookie: String
        get() = manualCookie
        set(v) { manualCookie = v }

    var userAgent: String
        get() = customUa
        set(v) { customUa = v }

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

    private fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    fun show(context: Context) {
        val act = currentActivity(context) ?: return
        init(act)
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

        fun note(t: String) = TextView(act).apply {
            text = t
            textSize = 12f
            alpha = 0.75f
        }

        fun field(hint: String, value: String, multi: Boolean = false) = EditText(act).apply {
            this.hint = hint
            setText(value)
            textSize = 13f
            inputType = if (multi) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            } else {
                InputType.TYPE_CLASS_TEXT
            }
            if (multi) { minLines = 2; maxLines = 5 } else isSingleLine = true
        }

        fun button(t: String, onClick: () -> Unit) = Button(act).apply {
            text = t
            isAllCaps = false
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val status = TextView(act).apply {
            textSize = 13f
            setPadding(0, dp(act, 4), 0, dp(act, 4))
        }
        fun refreshStatus() {
            val has = LatestPornVideoBrowser.hasClearance()
            status.text = if (has) {
                "Status: cf_clearance cookie saved"
            } else {
                "Status: no Cloudflare cookie yet - tap \"Solve Cloudflare now\""
            }
            status.setTextColor(if (has) Color.rgb(76, 175, 80) else Color.rgb(239, 154, 0))
        }
        refreshStatus()

        root.addView(TextView(act).apply {
            text = "LatestPornVideo - Cloudflare bypass"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(status)

        root.addView(button("Solve Cloudflare now (opens the site, tick the box)") {
            scope.launch {
                val ok = LatestPornVideoBrowser.solveInteractive(act)
                withContext(Dispatchers.Main) {
                    refreshStatus()
                    Toast.makeText(act, if (ok) "Cloudflare passed" else "Not solved", Toast.LENGTH_SHORT).show()
                }
            }
        })
        root.addView(button("Test connection") {
            Toast.makeText(act, "Testing...", Toast.LENGTH_SHORT).show()
            scope.launch {
                val msg = LatestPornVideoBrowser.test()
                withContext(Dispatchers.Main) {
                    refreshStatus()
                    AlertDialog.Builder(act)
                        .setTitle("LatestPornVideo test")
                        .setMessage(msg)
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        })
        root.addView(button("Clear Cloudflare cookies") {
            scope.launch {
                LatestPornVideoBrowser.reset(clearCookies = true)
                withContext(Dispatchers.Main) {
                    refreshStatus()
                    Toast.makeText(act, "Cookies cleared", Toast.LENGTH_SHORT).show()
                }
            }
        })

        root.addView(header("Bypass mode"))
        val modeHelp = note(mode.help)
        val group = RadioGroup(act)
        Mode.values().forEach { m ->
            group.addView(RadioButton(act).apply {
                id = View.generateViewId()
                text = m.label
                tag = m
                isChecked = m == mode
            })
        }
        group.setOnCheckedChangeListener { g, id ->
            (g.findViewById<RadioButton>(id)?.tag as? Mode)?.let { modeHelp.text = it.help }
        }
        root.addView(group)
        root.addView(modeHelp)

        root.addView(header("Options"))
        val cbPrompt = CheckBox(act).apply {
            text = "Open the solver window automatically when needed"
            isChecked = autoPrompt
        }
        root.addView(cbPrompt)

        root.addView(header("Custom user agent"))
        root.addView(note("Leave empty to use this device's browser user agent. If you paste a cookie from another browser, paste that browser's exact user agent here."))
        val etUa = field("Mozilla/5.0 ...", customUa, multi = true)
        root.addView(etUa)

        root.addView(header("Manual cookie"))
        root.addView(note("Optional, e.g. cf_clearance=xxxx  (from a browser on the SAME network, with the same user agent as above)."))
        val etCookie = field("cf_clearance=...", manualCookie, multi = true)
        root.addView(etCookie)

        root.addView(note("Current user agent:\n" + LatestPornVideoBrowser.userAgent())
            .apply { setPadding(0, dp(act, 12), 0, 0) })

        val scroll = ScrollView(act).apply { addView(root) }
        AlertDialog.Builder(act)
            .setView(scroll)
            .setPositiveButton("Save") { _, _ ->
                (group.findViewById<RadioButton>(group.checkedRadioButtonId)?.tag as? Mode)?.let { mode = it }
                autoPrompt = cbPrompt.isChecked
                customUa = etUa.text.toString()
                manualCookie = etCookie.text.toString()
                scope.launch { LatestPornVideoBrowser.reset(clearCookies = false) }
                Toast.makeText(act, "Saved", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Close", null)
            .show()
    }
}
