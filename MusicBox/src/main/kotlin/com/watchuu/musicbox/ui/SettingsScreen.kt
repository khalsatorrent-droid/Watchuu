package com.watchuu.musicbox.ui

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.watchuu.musicbox.core.Async
import com.watchuu.musicbox.core.AuthSpecs
import com.watchuu.musicbox.core.Creds
import com.watchuu.musicbox.core.Diag
import com.watchuu.musicbox.core.Field
import com.watchuu.musicbox.core.Http
import com.watchuu.musicbox.core.WebAuth
import com.watchuu.musicbox.core.Zarz
import com.watchuu.musicbox.providers.Providers

/**
 * Settings and authorization screen.
 *
 * The ZARZ gateway rate limits every session, so this screen is built around spending as
 * little of it as possible: one catalogue, one signed-session download source, and an
 * authorize button for that source only. Session-free providers are managed separately.
 */
object SettingsScreen {

    private val main = Handler(Looper.getMainLooper())

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    /**
     * Text colours come from the theme the dialog is shown in. They used to be fixed dark greys that
     * only read on a light dialog, so on a dark theme the labels were nearly invisible.
     */
    private fun themed(context: Context, attr: Int, fallback: Int): Int {
        val tv = TypedValue()
        if (!context.theme.resolveAttribute(attr, tv, true)) return fallback
        if (tv.resourceId != 0) {
            @Suppress("DEPRECATION")
            return runCatching { context.resources.getColorStateList(tv.resourceId).defaultColor }.getOrDefault(fallback)
        }
        return tv.data
    }

    private fun primary(context: Context): Int = themed(context, android.R.attr.textColorPrimary, Color.parseColor("#1B1B1F"))
    private fun secondary(context: Context): Int = themed(context, android.R.attr.textColorSecondary, Color.parseColor("#4A4A55"))

    /** light text means the dialog is dark */
    private fun isDark(context: Context): Boolean {
        val c = primary(context)
        return (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000 > 128
    }

    private fun accent(context: Context): Int = Color.parseColor(if (isDark(context)) "#5FD6A8" else "#0B6E4F")
    private fun warn(context: Context): Int = Color.parseColor(if (isDark(context)) "#FFB74D" else "#8A5A00")

    private fun title(context: Context, text: String): TextView = TextView(context).apply {
        this.text = text
        setTextColor(primary(context))
        textSize = 17f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(context, 18), 0, dp(context, 6))
    }

    private fun body(context: Context, text: String): TextView = TextView(context).apply {
        this.text = text
        setTextColor(secondary(context))
        textSize = 13.5f
        setPadding(0, 0, 0, dp(context, 6))
    }

    private fun note(context: Context, text: String): TextView = TextView(context).apply {
        this.text = text
        setTextColor(warn(context))
        textSize = 13f
        setPadding(0, dp(context, 4), 0, dp(context, 6))
    }

    private fun button(context: Context, label: String, onClick: () -> Unit): Button =
        Button(context).apply {
            text = label
            textSize = 12f
            isAllCaps = false
            setOnClickListener { onClick() }
        }

    private fun row(context: Context, vararg views: View): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            for (v in views) addView(v)
        }

    private fun divider(context: Context): View = View(context).apply {
        setBackgroundColor((secondary(context) and 0x00FFFFFF) or 0x40000000)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 1))
    }

    fun show(context: Context) {
        Creds.init(context)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 18), dp(context, 10), dp(context, 18), dp(context, 24))
        }

        // ------------------------------------------------------------ 1. catalogue

        fun sectionCatalogue() {
            val current = Providers.byId(Providers.catalogueId())
            root.addView(divider(context))
            root.addView(title(context, "1. Catalogue"))
            root.addView(
                body(
                    context,
                    "Search, albums and artists come from this one source. Nothing else is " +
                        "queried, so a catalogue that needs a session costs nothing until you play something."
                )
            )
            root.addView(body(context, "Current catalogue: ${current?.label ?: "not set"}"))
            root.addView(
                button(context, "Choose catalogue") {
                    val options = Providers.catalogueCandidates()
                    val labels = options.map {
                        if (Providers.catalogueNeedsSession(it.id)) "${it.label}  (uses the session)" else it.label
                    }
                    AlertDialog.Builder(context)
                        .setTitle("Catalogue")
                        .setItems(labels.toTypedArray()) { _, which ->
                            val picked = options[which]
                            Providers.setCatalogueId(picked.id)
                            if (Providers.catalogueNeedsSession(picked.id)) {
                                // Its metadata is signed, so it has to be the authorized source.
                                Providers.setDownloadSourceId(picked.id)
                                toast(context, "${picked.label} also serves metadata through the session, so it is now your download source too")
                            } else {
                                toast(context, "Catalogue: ${picked.label}")
                            }
                            show(context)
                        }
                        .show()
                }
            )
            if (Providers.catalogueNeedsSession(Providers.catalogueId())) {
                root.addView(
                    note(
                        context,
                        "This catalogue answers through the signed session, so its searches count " +
                            "against the same limit as downloads. That is why it is also set as the download source."
                    )
                )
            }
        }

        // ------------------------------------------------------------ 2. download source

        fun sectionDownloadSource() {
            root.addView(divider(context))
            root.addView(title(context, "2. Download source (ZARZ session)"))
            root.addView(
                note(
                    context,
                    "The ZARZ gateway rate limits each session. Pick the one source you actually " +
                        "download from — only that session is authorized, and the other signed " +
                        "sources are never contacted."
                )
            )

            val candidates = Providers.zarzCandidates()
            val chosenId = Providers.downloadSourceId()

            for (provider in candidates) {
                val spec = AuthSpecs.byId(provider.id)
                val appVersion = spec?.signedSessionAppVersion
                val selected = provider.id == chosenId
                val status = if (appVersion != null) Zarz.statusLine(appVersion) else "no session"
                val label = TextView(context).apply {
                    text = "${if (selected) "●" else "○"}  ${provider.label}  —  $status"
                    textSize = 14f
                    setTextColor(if (selected) accent(context) else primary(context))
                    setTypeface(typeface, if (selected) Typeface.BOLD else Typeface.NORMAL)
                }
                root.addView(
                    row(
                        context,
                        label,
                        button(context, if (selected) "Selected" else "Use this") {
                            if (!selected) {
                                Providers.setDownloadSourceId(provider.id)
                                toast(context, "Download source: ${provider.label}")
                                show(context)
                            }
                        }
                    )
                )
            }

            val chosenProvider = chosenId?.let { Providers.byId(it) }
            val chosenAppVersion = chosenId?.let { AuthSpecs.byId(it)?.signedSessionAppVersion }

            if (chosenProvider == null || chosenAppVersion == null) {
                root.addView(note(context, "No download source selected. Lossless downloads stay off until you pick one."))
            } else {
                root.addView(body(context, "Selected: ${chosenProvider.label} (${chosenAppVersion})"))
                root.addView(
                    row(
                        context,
                        button(context, "Authorize") {
                            authorizeSession(context, chosenProvider.label, chosenAppVersion) { show(context) }
                        },
                        button(context, "Refresh") {
                            Thread {
                                val result = runCatching { Zarz.refresh(chosenAppVersion) }
                                main.post {
                                    toast(
                                        context,
                                        if (result.isSuccess) "Refreshed: ${Zarz.statusLine(chosenAppVersion)}"
                                        else "Refresh failed: ${result.exceptionOrNull()?.message}"
                                    )
                                    show(context)
                                }
                            }.start()
                        },
                        button(context, "Clear") {
                            Zarz.clear(chosenAppVersion)
                            toast(context, "${chosenProvider.label} session cleared")
                            show(context)
                        }
                    )
                )
                root.addView(
                    row(
                        context,
                        button(context, "Paste grant") { pasteGrant(context, chosenAppVersion) },
                        button(context, "Clear unused sessions") {
                            var cleared = 0
                            for (spec in AuthSpecs.zarzBacked()) {
                                val version = spec.signedSessionAppVersion ?: continue
                                if (version == chosenAppVersion) continue
                                if (Zarz.load(version) != null) {
                                    Zarz.clear(version)
                                    cleared++
                                }
                            }
                            toast(context, if (cleared == 0) "No other sessions stored" else "Cleared $cleared unused session(s)")
                            show(context)
                        }
                    )
                )
            }

            // Leftover sessions are quota the user is paying for and not using.
            val leftovers = AuthSpecs.zarzBacked().filter { spec ->
                val version = spec.signedSessionAppVersion ?: return@filter false
                version != chosenAppVersion && Zarz.load(version) != null
            }
            if (leftovers.isNotEmpty()) {
                root.addView(
                    note(
                        context,
                        "Stored sessions for: " + leftovers.joinToString(", ") { it.label } +
                            ". These are not used by the current selection — clear them to stop them counting against your limit."
                    )
                )
            }
            root.addView(body(context, "Install id: ${Zarz.installId().take(24)}…"))
        }

        // ------------------------------------------------------------ 3. session-free sources

        fun sectionFreeSources() {
            root.addView(divider(context))
            root.addView(title(context, "3. Sources without a session"))
            root.addView(
                body(
                    context,
                    "These need no ZARZ session, so enabling them costs nothing. They are tried " +
                        "after the owning source and the selected download source."
                )
            )
            for (provider in Providers.all.filter { !Providers.isZarz(it.id) && it.canStream }) {
                val isEnabled = Providers.enabled(provider)
                val label = TextView(context).apply {
                    text = "${provider.label}  —  ${provider.statusDetail()}"
                    textSize = 14f
                    setTextColor(if (isEnabled) primary(context) else ((secondary(context) and 0x00FFFFFF) or 0x80000000.toInt()))
                }
                root.addView(
                    row(
                        context,
                        label,
                        button(context, if (isEnabled) "Disable" else "Enable") {
                            Providers.setEnabled(provider, !isEnabled)
                            show(context)
                        }
                    )
                )
            }
        }

        // ------------------------------------------------------------ 4. credentials

        fun sectionCredentials() {
            root.addView(divider(context))
            root.addView(title(context, "4. Provider credentials"))
            root.addView(
                body(
                    context,
                    "Only the values the Mornye sources actually read. Sign-in buttons capture " +
                        "cookies into the app's WebView jar, which the provider requests share."
                )
            )

            val chosenId = Providers.downloadSourceId()
            val relevant = AuthSpecs.all.filter { spec ->
                spec.fields.isNotEmpty() || spec.loginUrl != null
            }.sortedByDescending { it.id == chosenId }

            for (spec in relevant) {
                root.addView(title(context, spec.label + if (spec.id == chosenId) "  (selected download source)" else ""))
                if (spec.summary.isNotBlank()) root.addView(body(context, spec.summary))
                for (field in spec.fields) addFieldRow(context, root, field)

                val actions = row(context)
                if (spec.loginUrl != null) {
                    actions.addView(button(context, "Sign in") {
                        WebAuth.loginFlow(context, spec.loginUrl, spec.loginUrl) { cookies ->
                            if (cookies != null) {
                                toast(context, "Cookies captured (${cookies.length} chars)")
                                captureCookies(spec.id, cookies)
                                show(context)
                            } else {
                                toast(context, "Sign in cancelled")
                            }
                        }
                    })
                }
                actions.addView(button(context, "Test") { validateProvider(context, spec) })
                actions.addView(button(context, "Clear") {
                    Creds.clearProvider(spec)
                    toast(context, "${spec.label} credentials cleared")
                    show(context)
                })
                root.addView(actions)
                if (spec.loginNote != null) root.addView(body(context, spec.loginNote))
            }
        }

        // ------------------------------------------------------------ 5. backup

        fun sectionPortability() {
            root.addView(divider(context))
            root.addView(title(context, "5. Backup"))
            root.addView(
                row(
                    context,
                    button(context, "Export credentials") {
                        val json = Creds.export()
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("musicbox-credentials", json))
                        AlertDialog.Builder(context)
                            .setTitle("Credentials exported")
                            .setMessage("Copied to the clipboard. Treat it like a password: it contains session secrets.")
                            .setPositiveButton("OK", null)
                            .show()
                    },
                    button(context, "Import credentials") { importDialog(context) },
                    button(context, "Diagnostics") { diagnosticsDialog(context) }
                )
            )
            root.addView(
                button(context, "Erase all MusicBox data") {
                    AlertDialog.Builder(context)
                        .setTitle("Erase everything?")
                        .setMessage("Clears credentials, sessions and cookies. This cannot be undone.")
                        .setPositiveButton("Erase") { _, _ ->
                            Creds.clearAll()
                            Http.clearCookies(null)
                            toast(context, "All MusicBox data erased")
                            show(context)
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            )
        }

        sectionCatalogue()
        sectionDownloadSource()
        sectionFreeSources()
        sectionCredentials()
        sectionPortability()

        val scroll = ScrollView(context).apply { addView(root) }
        AlertDialog.Builder(context)
            .setTitle("MusicBox 64")
            .setView(scroll)
            .setPositiveButton("Close", null)
            .show()
    }

    // ------------------------------------------------------------------ helpers

    private fun addFieldRow(context: Context, root: LinearLayout, field: Field) {
        root.addView(body(context, field.label))
        val input = EditText(context).apply {
            setText(Creds.get(field.key, field.default) ?: field.default)
            textSize = 13f
            if (field.secret) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
        }
        root.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        if (field.hint.isNotBlank()) root.addView(body(context, field.hint))
        root.addView(
            button(context, "Save") {
                Creds.set(field.key, input.text.toString())
                toast(context, "Saved ${field.label}")
            }
        )
    }

    private fun captureCookies(providerId: String, cookies: String) {
        when (providerId) {
            "spotify" -> {
                val spDc = cookies.split(";").map { it.trim() }
                    .firstOrNull { it.startsWith("sp_dc=") }?.substringAfter("=")
                if (!spDc.isNullOrBlank()) {
                    Creds.set("spotify.sp_dc", spDc)
                    Diag.log("auth", "captured spotify sp_dc")
                }
            }
            else -> Creds.set("$providerId.cookies", cookies)
        }
    }

    /** Bootstrap, then the in-app grant flow when the gateway asks for verification. */
    private fun authorizeSession(context: Context, label: String, appVersion: String, done: () -> Unit) {
        Toast.makeText(context, "$label: contacting gateway…", Toast.LENGTH_SHORT).show()
        Thread {
            val outcome = runCatching { Zarz.ensure(appVersion) }
            main.post {
                val error = outcome.exceptionOrNull()
                when {
                    outcome.isSuccess -> {
                        toast(context, "$label authorized")
                        done()
                    }
                    error is Zarz.VerificationRequired -> {
                        WebAuth.grantFlow(context, error.authUrl) { grant ->
                            if (grant == null) {
                                toast(context, "$label: verification cancelled")
                            } else {
                                Thread {
                                    val result = runCatching { Zarz.exchange(appVersion, grant) }
                                    main.post {
                                        if (result.isSuccess) {
                                            toast(context, "$label authorized")
                                            done()
                                        } else {
                                            toast(context, "$label: ${result.exceptionOrNull()?.message}")
                                        }
                                    }
                                }.start()
                            }
                        }
                    }
                    else -> toast(context, "$label: ${error?.message ?: "failed"}")
                }
            }
        }.start()
    }

    private fun pasteGrant(context: Context, appVersion: String) {
        val input = EditText(context).apply {
            hint = "Paste the grant from the verification redirect"
            textSize = 13f
        }
        AlertDialog.Builder(context)
            .setTitle("Paste session grant")
            .setView(input)
            .setPositiveButton("Exchange") { _, _ ->
                val grant = input.text.toString().trim()
                if (grant.isBlank()) return@setPositiveButton
                Thread {
                    val result = runCatching { Zarz.exchange(appVersion, grant) }
                    main.post {
                        toast(
                            context,
                            if (result.isSuccess) "Session stored for $appVersion"
                            else "Failed: ${result.exceptionOrNull()?.message}"
                        )
                        show(context)
                    }
                }.start()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun validateProvider(context: Context, spec: com.watchuu.musicbox.core.AuthSpec) {
        val provider = Providers.byId(spec.id)
        if (provider == null) {
            toast(context, "${spec.label}: no provider port in this build")
            return
        }
        toast(context, "${spec.label}: checking…")
        Thread {
            val result = runCatching { Async.runBlocking { provider.search("test", 1) } }
            main.post {
                if (result.isSuccess) toast(context, "${spec.label}: reachable (${result.getOrNull()?.size ?: 0} hits)")
                else toast(context, "${spec.label}: ${result.exceptionOrNull()?.message}")
            }
        }.start()
    }

    private fun importDialog(context: Context) {
        val input = EditText(context).apply {
            hint = "Paste exported JSON"
            textSize = 12f
            setSingleLine(false)
            minLines = 4
        }
        AlertDialog.Builder(context)
            .setTitle("Import credentials")
            .setView(input)
            .setPositiveButton("Import") { _, _ ->
                val count = Creds.import(input.text.toString())
                toast(context, "Restored $count values")
                show(context)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun diagnosticsDialog(context: Context) {
        val text = TextView(context).apply {
            this.text = Diag.dump().ifBlank { "No diagnostics yet." }
            textSize = 11f
            setTextIsSelectable(true)
            setPadding(dp(context, 12), dp(context, 8), dp(context, 12), dp(context, 8))
        }
        val scroll = ScrollView(context).apply { addView(text) }
        AlertDialog.Builder(context)
            .setTitle("Diagnostics")
            .setView(scroll)
            .setPositiveButton("Close", null)
            .setNeutralButton("Clear") { _, _ -> Diag.clear() }
            .show()
    }

    private fun toast(context: Context, message: String) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }
}
