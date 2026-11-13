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
 * FIX: Uses ZarzSessionManager singleton so bootstrap here is immediately
 *      visible to MusicBoxProvider (they were using separate instances before).
 * FIX: Added Beatport enable/disable toggle (was wired in ZarzSession but
 *      missing from settings, so it was always off in loadLinks).
 * appVersion strings match registry.json 2026-11-08.
 */
object MusicBoxSettings {
    private const val PREFS_NAME = "musicbox_prefs"
    private var prefs: SharedPreferences? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun init(context: Context) {
        if (prefs == null) prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun str(k: String, d: String = "")   = prefs?.getString(k, d) ?: d
    private fun bool(k: String, d: Boolean)       = prefs?.getBoolean(k, d) ?: d
    private fun put(block: SharedPreferences.Editor.() -> Unit) {
        prefs?.edit()?.apply { block(); apply() }
    }

    var catalogue: String
        get() = str("musicbox_catalogue", "all")
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

    var enableQobuz: Boolean
        get() = bool("enable_qobuz", true)
        set(v) = put { putBoolean("enable_qobuz", v) }

    // FIX: Beatport toggle was missing — it was listed in ZarzSession and checked in
    //      the old provider code but was never actually read because there was no
    //      prefs key for it. Defaults to true so existing users get it immediately.
    var enableBeatport: Boolean
        get() = bool("enable_beatport", true)
        set(v) = put { putBoolean("enable_beatport", v) }

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
            text = t; setTypeface(typeface, Typeface.BOLD); textSize = 15f
            setPadding(0, dp(act, 14), 0, dp(act, 4))
        }
        fun note(t: String) = TextView(act).apply {
            text = t; textSize = 12f; alpha = 0.75f
        }

        // ── Catalogue source ──────────────────────────────────────────────────
        root.addView(header("Catalogue Source"))
        root.addView(note("Which service to use for search & browsing"))

        val catalogueOptions = listOf(
            "all"        to "All sources",
            "jiosaavn"   to "JioSaavn",
            "spotify"    to "Spotify",
            "apple"      to "Apple Music",
            "soundcloud" to "SoundCloud",
            "deezer"     to "Deezer",
            "qobuz"      to "Qobuz",
            "tidal"      to "Tidal",
            "beatport"   to "Beatport",
        )
        val catalogueGroup = RadioGroup(act).apply { orientation = RadioGroup.VERTICAL }
        val currentCatalogue = catalogue
        catalogueOptions.forEachIndexed { index, (value, label) ->
            catalogueGroup.addView(RadioButton(act).apply {
                text = label; id = index; isChecked = value == currentCatalogue
            })
        }
        root.addView(catalogueGroup)

        // ── Download sources ──────────────────────────────────────────────────
        root.addView(header("Download Sources"))
        root.addView(note("Which services to try for playback (in priority order)"))
        root.addView(note("ZARZ = needs session bootstrap once; then automatic"))

        val cbAmazon = CheckBox(act).apply {
            text = "1. Amazon Music (ZARZ — amzn@2.3.19, FLAC)"
            isChecked = enableAmazonDownload
        }
        root.addView(cbAmazon)

        val cbDeezer = CheckBox(act).apply {
            text = "2. Deezer (ZARZ — deezer@1.3.11, FLAC/320)"
            isChecked = enableDeezerDownload
        }
        root.addView(cbDeezer)

        val cbJioSaavn = CheckBox(act).apply {
            text = "3. JioSaavn (direct, 320kbps MP3)"
            isChecked = enableJioSaavn
        }
        root.addView(cbJioSaavn)

        val cbSoundCloud = CheckBox(act).apply {
            text = "4. SoundCloud (ZARZ /dl/sc → direct stream fallback)"
            isChecked = enableSoundCloud
        }
        root.addView(cbSoundCloud)

        val cbTidal = CheckBox(act).apply {
            text = "5. Tidal (ZARZ — tidal-web@1.2.14, FLAC)"
            isChecked = enableTidal
        }
        root.addView(cbTidal)

        val cbQobuz = CheckBox(act).apply {
            text = "6. Qobuz (ZARZ — qobuz-web@1.2.27, FLAC)"
            isChecked = enableQobuz
        }
        root.addView(cbQobuz)

        // FIX: Added Beatport checkbox
        val cbBeatport = CheckBox(act).apply {
            text = "7. Beatport (ZARZ — beatport@1.0.1, FLAC)"
            isChecked = enableBeatport
        }
        root.addView(cbBeatport)

        val cbYouTube = CheckBox(act).apply {
            text = "8. YouTube Music (Cobalt → CloudStream extractor)"
            isChecked = enableYouTubeFallback
        }
        root.addView(cbYouTube)

        // ── ZARZ Session ──────────────────────────────────────────────────────
        root.addView(header("ZARZ Session"))
        root.addView(note("Required for Amazon, Deezer, Tidal, Qobuz, SoundCloud, Beatport (ZARZ)"))

        val sessionStatus = TextView(act).apply {
            textSize = 13f; setPadding(0, dp(act, 4), 0, dp(act, 4))
        }
        root.addView(sessionStatus)

        // FIX: Use the shared singleton session instead of creating a new local instance.
        //      Bootstrap in Settings is now immediately visible to MusicBoxProvider.
        val session = ZarzSessionManager.session
        ZarzSessionManager.load(prefs?.getString("musicbox_zarz_session", null), forceReload = true)

        fun updateStatus() {
            sessionStatus.text = when {
                session.record.isUsable() ->
                    "✓ Session active (expires: ${session.record.expiresAt.take(19).replace("T", " ")})"
                session.record.sessionId.isNotBlank() -> "✗ Session expired — tap Bootstrap"
                else -> "○ No session — tap Bootstrap"
            }
        }
        updateStatus()

        val btnBootstrap = Button(act).apply {
            text = "Bootstrap Session"
            isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        btnBootstrap.setOnClickListener {
            scope.launch {
                try {
                    val url = session.bootstrap()
                    prefs?.edit()?.putString("musicbox_zarz_session", session.saveState())?.apply()
                    withContext(Dispatchers.Main) {
                        if (url.isBlank()) {
                            updateStatus()
                            Toast.makeText(act, "Session active!", Toast.LENGTH_SHORT).show()
                        } else {
                            sessionStatus.text = "⚠ Verification required"
                            MusicBoxBrowser.openVerification(act, url) { grant ->
                                scope.launch {
                                    try {
                                        session.exchange(grant)
                                        prefs?.edit()?.putString("musicbox_zarz_session", session.saveState())?.apply()
                                        withContext(Dispatchers.Main) {
                                            updateStatus()
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
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        btnClear.setOnClickListener {
            session.record.clear()
            prefs?.edit()?.putString("musicbox_zarz_session", session.saveState())?.apply()
            updateStatus()
            Toast.makeText(act, "Session cleared", Toast.LENGTH_SHORT).show()
        }
        root.addView(btnClear)

        val scroll = ScrollView(act).apply { addView(root) }
        AlertDialog.Builder(act)
            .setTitle("MusicBox Settings")
            .setView(scroll)
            .setPositiveButton("Save") { _, _ ->
                val selectedIdx = catalogueGroup.checkedRadioButtonId
                if (selectedIdx in catalogueOptions.indices) {
                    catalogue = catalogueOptions[selectedIdx].first
                }
                enableAmazonDownload  = cbAmazon.isChecked
                enableDeezerDownload  = cbDeezer.isChecked
                enableJioSaavn        = cbJioSaavn.isChecked
                enableSoundCloud      = cbSoundCloud.isChecked
                enableTidal           = cbTidal.isChecked
                enableQobuz           = cbQobuz.isChecked
                enableBeatport        = cbBeatport.isChecked
                enableYouTubeFallback = cbYouTube.isChecked
                Toast.makeText(act, "Settings saved", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun dp(ctx: Context, dp: Int): Int =
        (dp * ctx.resources.displayMetrics.density + 0.5f).toInt()
}
