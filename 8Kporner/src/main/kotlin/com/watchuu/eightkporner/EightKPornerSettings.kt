package com.watchuu.eightkporner

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

/** Cloudflare bypass settings, shown from Extensions -> 8KPorner -> settings (gear). */
object EightKPornerSettings {
    enum class Mode(val label: String, val help: String) {
        AUTO("Auto (recommended)", "Hidden browser first; if Cloudflare wants a click, the solver window opens for you."),
        SOLVER("Always use solver window", "Skip the hidden browser and open the visible solver window whenever blocked."),
        CLOUDSTREAM("Cloudstream built-in bypass", "Use Cloudstream's own CloudflareKiller (works for non-interactive checks)."),
        HIDDEN("Hidden browser only", "Never open a window; only the hidden browser is tried."),
        COOKIE("Manual cookie only", "Only use the cf_clearance cookie + user agent you paste below."),
    }

    private var prefs: SharedPreferences? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    const val DEFAULT_URL = "https://8kporner.com"

    fun init(context: Context) {
        if (prefs == null) prefs = context.applicationContext.getSharedPreferences("eightkporner_settings", Context.MODE_PRIVATE)
    }

    private fun str(k: String, d: String = "") = prefs?.getString(k, d) ?: d
    private fun bool(k: String, d: Boolean) = prefs?.getBoolean(k, d) ?: d
    private fun put(block: SharedPreferences.Editor.() -> Unit) {
        prefs?.edit()?.apply { block(); apply() }
    }

    var mode: Mode
        get() = try { Mode.valueOf(str("mode", Mode.AUTO.name)) } catch (_: Throwable) { Mode.AUTO }
        set(v) = put { putString("mode", v.name) }

    var autoPrompt: Boolean
        get() = bool("auto_prompt", true)
        set(v) = put { putBoolean("auto_prompt", v) }

    var stripWv: Boolean
        get() = bool("strip_wv", true)
        set(v) = put { putBoolean("strip_wv", v) }

    var lightweight: Boolean
        get() = bool("lightweight", false)
        set(v) = put { putBoolean("lightweight", v) }

    var customUa: String
        get() = str("custom_ua").trim()
        set(v) = put { putString("custom_ua", v.trim()) }

    /** UA that was active when cf_clearance was obtained - requests must reuse it. */
    var solvedUa: String
        get() = str("solved_ua").trim()
        set(v) = put { putString("solved_ua", v.trim()) }

    var manualCookie: String
        get() = str("manual_cookie").trim()
        set(v) = put { putString("manual_cookie", v.trim()) }

    var timeoutSec: Int
        get() = str("timeout", "35").toIntOrNull()?.coerceIn(5, 180) ?: 35
        set(v) = put { putString("timeout", v.toString()) }

    var siteUrl: String
        get() = str("site_url", DEFAULT_URL).trim().trimEnd('/').ifBlank { DEFAULT_URL }
        set(v) = put { putString("site_url", v.trim().trimEnd('/')) }

    @Volatile
    var lastLinkReport: String = "Nothing played yet."

    fun currentActivity(ctx: Context? = null): Activity? =
        (ctx as? Activity) ?: try { CommonActivity.activity } catch (_: Throwable) { null }

    fun toast(msg: String) {
        scope.launch(Dispatchers.Main) {
            try {
                CommonActivity.showToast(msg, Toast.LENGTH_LONG)
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
            inputType = if (multi) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            if (multi) { minLines = 2; maxLines = 5 } else isSingleLine = true
        }

        fun button(t: String, onClick: () -> Unit) = Button(act).apply {
            text = t
            isAllCaps = false
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val status = TextView(act).apply {
            textSize = 13f
            setPadding(0, dp(act, 4), 0, dp(act, 4))
        }
        fun refreshStatus() {
            val has = EightKPornerBrowser.hasClearance()
            status.text = if (has) "✅ cf_clearance present  (method: ${EightKPornerBrowser.lastMethod})" else "⚠️ No cf_clearance yet"
            status.setTextColor(if (has) Color.rgb(80, 200, 80) else Color.rgb(255, 180, 50))
        }
        refreshStatus()

        // ── Site URL ──────────────────────────────────────────────────────────
        root.addView(header("Site URL"))
        val urlField = field("https://8kporner.com", siteUrl)
        root.addView(urlField)
        root.addView(note("Change only if the site moves to a new domain."))

        // ── Bypass mode ───────────────────────────────────────────────────────
        root.addView(header("Cloudflare bypass mode"))
        val rg = RadioGroup(act).apply { orientation = RadioGroup.VERTICAL }
        Mode.entries.forEach { m ->
            val rb = RadioButton(act).apply {
                text = "${m.label}\n"
                textSize = 13f
                tag = m
                isChecked = mode == m
            }
            rg.addView(rb)
        }
        root.addView(rg)
        val modeNote = note(mode.help)
        root.addView(modeNote)
        rg.setOnCheckedChangeListener { _, id ->
            val sel = rg.findViewById<RadioButton>(id)?.tag as? Mode ?: return@setOnCheckedChangeListener
            mode = sel
            modeNote.text = sel.help
        }

        // ── Options ───────────────────────────────────────────────────────────
        root.addView(header("Options"))

        val cbPrompt = CheckBox(act).apply {
            text = "Auto-open solver window when blocked"
            isChecked = autoPrompt
        }
        root.addView(cbPrompt)

        val cbStrip = CheckBox(act).apply {
            text = "Strip WebView markers from user agent"
            isChecked = stripWv
        }
        root.addView(cbStrip)

        val cbLight = CheckBox(act).apply {
            text = "Lightweight WebView (no JS, images off)"
            isChecked = lightweight
        }
        root.addView(cbLight)

        root.addView(note("Timeout (seconds):"))
        val timeoutField = field("35", timeoutSec.toString())
        root.addView(timeoutField)

        // ── Custom UA ─────────────────────────────────────────────────────────
        root.addView(header("Custom user agent"))
        val uaField = field("Leave blank to use system default", customUa)
        root.addView(uaField)

        // ── Manual cookie ─────────────────────────────────────────────────────
        root.addView(header("Manual cf_clearance cookie"))
        root.addView(note("Paste the full cookie string from your browser (DevTools → Application → Cookies → 8kporner.com)."))
        val cookieField = field("cf_clearance=…; PHPSESSID=…", manualCookie, multi = true)
        root.addView(cookieField)

        // ── Status / actions ──────────────────────────────────────────────────
        root.addView(header("Status"))
        root.addView(status)

        root.addView(button("Solve Cloudflare now") {
            toast("Opening Cloudflare solver…")
            scope.launch {
                val ok = EightKPornerBrowser.solveInteractive(act)
                withContext(Dispatchers.Main) { refreshStatus() }
                toast(if (ok) "8KPorner: Cloudflare passed ✓" else "8KPorner: not solved")
            }
        })

        root.addView(button("Clear saved cookie") {
            try {
                android.webkit.CookieManager.getInstance().let { cm ->
                    cm.setCookie("https://8kporner.com", "cf_clearance=; Max-Age=0")
                    cm.flush()
                }
                put { remove("manual_cookie") }
                cookieField.setText("")
                refreshStatus()
                toast("Cookie cleared")
            } catch (_: Throwable) { }
        })

        root.addView(header("Last link info"))
        root.addView(TextView(act).apply {
            text = lastLinkReport
            textSize = 12f
        })

        // ── Trace ─────────────────────────────────────────────────────────────
        val trace = EightKPornerBrowser.lastTrace
        if (trace.isNotBlank()) {
            root.addView(header("Last bypass trace"))
            root.addView(TextView(act).apply { text = trace; textSize = 11f; alpha = 0.7f })
        }

        val sv = ScrollView(act)
        sv.addView(root)

        AlertDialog.Builder(act)
            .setTitle("8KPorner — Cloudflare settings")
            .setView(sv)
            .setPositiveButton("Save") { _, _ ->
                val newUrl = urlField.text.toString().trim().trimEnd('/').ifBlank { DEFAULT_URL }
                if (newUrl != siteUrl) { siteUrl = newUrl; toast("Site URL updated — restart recommended") }
                mode = (rg.findViewById<RadioButton>(rg.checkedRadioButtonId)?.tag as? Mode) ?: mode
                autoPrompt = cbPrompt.isChecked
                stripWv = cbStrip.isChecked
                lightweight = cbLight.isChecked
                customUa = uaField.text.toString()
                manualCookie = cookieField.text.toString()
                timeoutSec = timeoutField.text.toString().toIntOrNull()?.coerceIn(5, 180) ?: 35
                toast("Settings saved")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
