package com.watchuu.musicbox

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Typeface
import android.view.ViewGroup
import android.widget.*
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.CommonActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * MusicBox settings dialog.
 *
 * - Catalogue source picker (All / JioSaavn / Spotify / Apple Music / Deezer / Tidal / Qobuz / SoundCloud / YouTube Music / Jamendo / GDStudio)
 * - Download/stream provider toggles (powered by CharlesPikachu/musicdl + Monochrome + ZARZ)
 * - ZARZ session status and verification challenge launcher
 */
object MusicBoxSettings {
    /** base id for the catalogue radio buttons, clear of 0 and of generated ids */
    private const val RADIO_BASE = 1000
    private const val PREFS_NAME = "musicbox_prefs"
    private var prefs: SharedPreferences? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    private fun ensurePrefs(): SharedPreferences? {
        if (prefs == null) {
            runCatching {
                CloudStreamApp.context?.applicationContext?.let { ctx ->
                    prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                }
            }
        }
        return prefs
    }

    private fun str(k: String, d: String = ""): String = ensurePrefs()?.getString(k, d) ?: d
    private fun bool(k: String, d: Boolean): Boolean = ensurePrefs()?.getBoolean(k, d) ?: d
    private fun put(block: SharedPreferences.Editor.() -> Unit) {
        ensurePrefs()?.edit()?.apply { block(); apply() }
    }

    var catalogue: String
        get() = str("musicbox_catalogue", "all")
        set(v) = put { putString("musicbox_catalogue", v) }

    var enableJioSaavn: Boolean
        get() = bool("enable_jiosaavn", true)
        set(v) = put { putBoolean("enable_jiosaavn", v) }

    var enableTidal: Boolean
        get() = bool("enable_tidal", true)
        set(v) = put { putBoolean("enable_tidal", v) }

    var enableDeezerDownload: Boolean
        get() = bool("enable_deezer_dl", true)
        set(v) = put { putBoolean("enable_deezer_dl", v) }

    var enableQobuz: Boolean
        get() = bool("enable_qobuz_dl", true)
        set(v) = put { putBoolean("enable_qobuz_dl", v) }

    var enableSpotifyDownload: Boolean
        get() = bool("enable_spotify_dl", true)
        set(v) = put { putBoolean("enable_spotify_dl", v) }

    var enableAmazonDownload: Boolean
        get() = bool("enable_amazon_dl", true)
        set(v) = put { putBoolean("enable_amazon_dl", v) }

    var enableSoundCloud: Boolean
        get() = bool("enable_soundcloud", true)
        set(v) = put { putBoolean("enable_soundcloud", v) }

    var enableGDStudio: Boolean
        get() = bool("enable_gdstudio", true)
        set(v) = put { putBoolean("enable_gdstudio", v) }

    var enableJamendo: Boolean
        get() = bool("enable_jamendo", true)
        set(v) = put { putBoolean("enable_jamendo", v) }

    var enableYouTubeFallback: Boolean
        get() = bool("enable_yt_fallback", true)
        set(v) = put { putBoolean("enable_yt_fallback", v) }

    val catalogueOptions = listOf(
        "all" to "All Sources (Merged)",
        "jiosaavn" to "JioSaavn",
        "spotify" to "Spotify",
        "apple" to "Apple Music / iTunes",
        "deezer" to "Deezer",
        "tidal" to "Tidal / Monochrome",
        "qobuz" to "Qobuz",
        "soundcloud" to "SoundCloud",
        "ytmusic" to "YouTube Music",
        "jamendo" to "Jamendo",
        "gdstudio" to "GDStudio (Multi-Source)",
    )

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
        root.addView(note("Which service to use for home page, search & browsing"))

        val catalogueGroup = RadioGroup(act).apply {
            orientation = RadioGroup.VERTICAL
        }
        val currentCatalogue = catalogue
        catalogueOptions.forEachIndexed { index, (value, label) ->
            val rb = RadioButton(act).apply {
                text = label
                // 1000 + index, not index: id 0 is RadioGroup's "no selection", so the first
                // option could never be saved
                id = RADIO_BASE + index
                isChecked = value == currentCatalogue
            }
            catalogueGroup.addView(rb)
        }
        root.addView(catalogueGroup)

        // ========== Download sources ==========
        root.addView(header("Playback & Download Providers"))
        root.addView(note("Active providers powered by CharlesPikachu/musicdl, Monochrome & ZARZ"))

        val cbJioSaavn = CheckBox(act).apply {
            text = "1. JioSaavn (Direct 320kbps MP3/M4A)"
            isChecked = enableJioSaavn
        }
        root.addView(cbJioSaavn)

        val cbTidal = CheckBox(act).apply {
            text = "2. Tidal / Monochrome (Lossless FLAC / GDStudio / ZARZ)"
            isChecked = enableTidal
        }
        root.addView(cbTidal)

        val cbDeezer = CheckBox(act).apply {
            text = "3. Deezer (musicdl: AnandServer / Deemixer / MusicFab / DeezDL / ZARZ)"
            isChecked = enableDeezerDownload
        }
        root.addView(cbDeezer)

        val cbQobuz = CheckBox(act).apply {
            text = "4. Qobuz (musicdl: AnandServer / DezAlty / GDStudio / ClashFLAC / ZARZ)"
            isChecked = enableQobuz
        }
        root.addView(cbQobuz)

        val cbSpotify = CheckBox(act).apply {
            text = "5. Spotify Direct (musicdl: SpotiDown / RapidAPI / SpotSaver / MusicFab)"
            isChecked = enableSpotifyDownload
        }
        root.addView(cbSpotify)

        val cbAmazon = CheckBox(act).apply {
            text = "6. Amazon Music (ZARZ signed - FLAC)"
            isChecked = enableAmazonDownload
        }
        root.addView(cbAmazon)

        val cbSoundCloud = CheckBox(act).apply {
            text = "7. SoundCloud (Direct Progressive/HLS + MP3Juice)"
            isChecked = enableSoundCloud
        }
        root.addView(cbSoundCloud)

        val cbGDStudio = CheckBox(act).apply {
            text = "8. GDStudio Multi-Source (Netease / Kuwo / Joox / Apple / YTMusic)"
            isChecked = enableGDStudio
        }
        root.addView(cbGDStudio)

        val cbJamendo = CheckBox(act).apply {
            text = "9. Jamendo (Direct FLAC / 320kbps MP3)"
            isChecked = enableJamendo
        }
        root.addView(cbJamendo)

        val cbYouTube = CheckBox(act).apply {
            text = "10. YouTube Music (musicdl: Y2Mate / CnvMP3 / Convert1s / EzSrv / Extractor)"
            isChecked = enableYouTubeFallback
        }
        root.addView(cbYouTube)

        // ========== ZARZ Session ==========
        root.addView(header("ZARZ Session (Optional Bonus Resolver)"))

        val sessionStatus = TextView(act).apply {
            textSize = 13f
            setPadding(0, dp(act, 4), 0, dp(act, 4))
        }
        root.addView(sessionStatus)

        // the same object the provider uses. loadFromPrefs never discards a usable session, so
        // opening this screen cannot wipe one that playback is relying on
        ensurePrefs()
        ZarzSessionManager.loadFromPrefs(act)
        val session = ZarzSessionManager.session

        sessionStatus.text = ZarzSessionManager.statusText()

        val btnBootstrap = Button(act).apply {
            text = "Bootstrap ZARZ Session"
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
                    ZarzSessionManager.persist(act)

                    withContext(Dispatchers.Main) {
                        if (url.isBlank()) {
                            sessionStatus.text = "✓ Session obtained directly!"
                            Toast.makeText(act, "Session active!", Toast.LENGTH_SHORT).show()
                        } else {
                            sessionStatus.text = "⚠ Verification required"
                            MusicBoxBrowser.openVerification(act, url) { grant ->
                                scope.launch {
                                    try {
                                        session.exchange(grant)
                                        ZarzSessionManager.persist(act)
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
            text = "Clear ZARZ Session"
            isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        btnClear.setOnClickListener {
            session.record.clear()
            ZarzSessionManager.persist(act)
            sessionStatus.text = "○ Session cleared"
            Toast.makeText(act, "Session cleared", Toast.LENGTH_SHORT).show()
        }
        root.addView(btnClear)

        val scroll = ScrollView(act).apply { addView(root) }

        AlertDialog.Builder(act)
            .setTitle("MusicBox Settings")
            .setView(scroll)
            .setPositiveButton("Save") { _, _ ->
                val selectedIdx = catalogueGroup.checkedRadioButtonId - RADIO_BASE
                if (selectedIdx in catalogueOptions.indices) {
                    catalogue = catalogueOptions[selectedIdx].first
                }
                enableJioSaavn = cbJioSaavn.isChecked
                enableTidal = cbTidal.isChecked
                enableDeezerDownload = cbDeezer.isChecked
                enableQobuz = cbQobuz.isChecked
                enableSpotifyDownload = cbSpotify.isChecked
                enableAmazonDownload = cbAmazon.isChecked
                enableSoundCloud = cbSoundCloud.isChecked
                enableGDStudio = cbGDStudio.isChecked
                enableJamendo = cbJamendo.isChecked
                enableYouTubeFallback = cbYouTube.isChecked
                Toast.makeText(act, "Settings saved", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun dp(ctx: Context, dp: Int): Int =
        (dp * ctx.resources.displayMetrics.density + 0.5f).toInt()
}
