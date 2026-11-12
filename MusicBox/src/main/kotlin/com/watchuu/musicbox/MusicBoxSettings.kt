package com.watchuu.musicbox

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Typeface
import android.view.ViewGroup
import android.widget.*
import com.lagradost.cloudstream3.CommonActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * MusicBox settings dialog.
 *
 * - Catalogue source picker (Spotify / Deezer / JioSaavn)
 * - ZARZ session status and verification challenge launcher
 * - Source toggles
 */
object MusicBoxSettings {
    private const val PREFS_NAME = "musicbox_prefs"
    private var prefs: SharedPreferences? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun init(context: Context) {
        if (prefs == null) prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun str(k: String, d: String = "") = prefs?.getString(k, d) ?: d
    private fun bool(k: String, d: Boolean) = prefs?.getBoolean(k, d) ?: d
    private fun put(block: SharedPreferences.Editor.() -> Unit) {
        prefs?.edit()?.apply { block(); apply() }
    }

    var catalogue: String
        get() = str("musicbox_catalogue", "spotify")
        set(v) = put { putString("musicbox_catalogue", v) }

    var enableAmazonDownload: Boolean
        get() = bool("enable_amazon_dl", true)
        set(v) = put { putBoolean("enable_amazon_dl", v) }

    var enableDeezerDownload: Boolean
        get() = bool("enable_deezer_dl", true)
        set(v) = put { putBoolean("enable_deezer_dl", v) }

    var enableJioSaavn: Boolean
        get() = bool("enable_jiosaavn", true)
        set(v) = put { putBoolean("enable_jiosaavn", v) }

    var enableSoundCloud: Boolean
        get() = bool("enable_soundcloud", true)
        set(v) = put { putBoolean("enable_soundcloud", v) }

    var enableTidal: Boolean
        get() = bool("enable_tidal", true)
        set(v) = put { putBoolean("enable_tidal", v) }

    var enableYouTubeFallback: Boolean
        get() = bool("enable_yt_fallback", true)
        set(v) = put { putBoolean("enable_yt_fallback", v) }

    fun show(context: Context) {
        val act = (context as? Activity)
            ?: (CommonActivity.activity as? Activity)
            ?: return
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

        // ========== Catalogue source ==========
        root.addView(header("Catalogue Source"))
        root.addView(note("Which service to use for search & browsing"))

        val catalogueOptions = listOf("spotify" to "Spotify", "deezer" to "Deezer", "jiosaavn" to "JioSaavn")
        val catalogueGroup = RadioGroup(act).apply {
            orientation = RadioGroup.VERTICAL
        }
        val currentCatalogue = catalogue
        catalogueOptions.forEachIndexed { index, (value, label) ->
            val rb = RadioButton(act).apply {
                text = label
                id = index
                isChecked = value == currentCatalogue
            }
            catalogueGroup.addView(rb)
        }
        root.addView(catalogueGroup)

        // ========== Download sources ==========
        root.addView(header("Download Sources"))
        root.addView(note("Which services to try for playback (in priority order)"))

        val cbAmazon = CheckBox(act).apply {
            text = "1. Amazon Music (ZARZ signed - FLAC)"
            isChecked = enableAmazonDownload
        }
        root.addView(cbAmazon)

        val cbDeezer = CheckBox(act).apply {
            text = "2. Deezer (ZARZ signed - FLAC/320kbps)"
            isChecked = enableDeezerDownload
        }
        root.addView(cbDeezer)

        val cbJioSaavn = CheckBox(act).apply {
            text = "3. JioSaavn (320kbps MP3)"
            isChecked = enableJioSaavn
        }
        root.addView(cbJioSaavn)

        val cbSoundCloud = CheckBox(act).apply {
            text = "4. SoundCloud (128kbps)"
            isChecked = enableSoundCloud
        }
        root.addView(cbSoundCloud)

        val cbTidal = CheckBox(act).apply {
            text = "5. Tidal (ZARZ signed - FLAC)"
            isChecked = enableTidal
        }
        root.addView(cbTidal)

        val cbYouTube = CheckBox(act).apply {
            text = "6. YouTube Music (Cobalt)"
            isChecked = enableYouTubeFallback
        }
        root.addView(cbYouTube)

        // ========== ZARZ Session ==========
        root.addView(header("ZARZ Session"))

        val sessionStatus = TextView(act).apply {
            textSize = 13f
            setPadding(0, dp(act, 4), 0, dp(act, 4))
        }
        root.addView(sessionStatus)

        // Check current session status
        val session = ZarzSession()
        val stored = prefs?.getString("musicbox_zarz_session", null)
        session.loadState(stored)

        sessionStatus.text = if (session.record.isUsable()) {
            "✓ Session active (expires: ${session.record.expiresAt.take(19).replace("T", " ")})"
        } else if (session.record.sessionId.isNotBlank()) {
            "✗ Session expired"
        } else {
            "○ No session (bootstrap required)"
        }

        val btnBootstrap = Button(act).apply {
            text = "Bootstrap Session"
            isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        btnBootstrap.setOnClickListener {
            scope.launch {
                try {
                    val url = session.bootstrap()
                    prefs?.edit()?.putString("musicbox_zarz_session", session.saveState())?.apply()

                    withContext(Dispatchers.Main) {
                        if (url.isBlank()) {
                            sessionStatus.text = "✓ Session obtained directly!"
                            Toast.makeText(act, "Session active!", Toast.LENGTH_SHORT).show()
                        } else {
                            sessionStatus.text = "⚠ Verification required"
                            // Open verification URL in WebView
                            MusicBoxBrowser.openVerification(act, url) { grant ->
                                scope.launch {
                                    try {
                                        session.exchange(grant)
                                        prefs?.edit()?.putString("musicbox_zarz_session", session.saveState())?.apply()
                                        withContext(Dispatchers.Main) {
                                            sessionStatus.text = "✓ Session active!"
                                            Toast.makeText(act, "Verification complete!", Toast.LENGTH_SHORT).show()
                                        }
                                    } catch (e: Exception) {
                                        withContext(Dispatchers.Main) {
                                            sessionStatus.text = "✗ Exchange failed: ${e.message}"
                                            Toast.makeText(act, "Exchange failed: ${e.message}", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        sessionStatus.text = "✗ Bootstrap failed: ${e.message}"
                        Toast.makeText(act, "Bootstrap failed: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        root.addView(btnBootstrap)

        val btnClear = Button(act).apply {
            text = "Clear Session"
            isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        btnClear.setOnClickListener {
            session.record.clear()
            prefs?.edit()?.putString("musicbox_zarz_session", session.saveState())?.apply()
            sessionStatus.text = "○ Session cleared"
            Toast.makeText(act, "Session cleared", Toast.LENGTH_SHORT).show()
        }
        root.addView(btnClear)

        // ========== Show dialog ==========
        val scroll = ScrollView(act).apply { addView(root) }

        AlertDialog.Builder(act)
            .setTitle("MusicBox Settings")
            .setView(scroll)
            .setPositiveButton("Save") { _, _ ->
                val selectedIdx = catalogueGroup.checkedRadioButtonId
                if (selectedIdx in catalogueOptions.indices) {
                    catalogue = catalogueOptions[selectedIdx].first
                }
                enableAmazonDownload = cbAmazon.isChecked
                enableDeezerDownload = cbDeezer.isChecked
                enableJioSaavn = cbJioSaavn.isChecked
                enableSoundCloud = cbSoundCloud.isChecked
                enableTidal = cbTidal.isChecked
                enableYouTubeFallback = cbYouTube.isChecked
                Toast.makeText(act, "Settings saved", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun dp(ctx: Context, dp: Int): Int =
        (dp * ctx.resources.displayMetrics.density + 0.5f).toInt()
}
