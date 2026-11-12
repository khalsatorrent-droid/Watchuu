package com.watchuu.musicbox

import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import androidx.fragment.app.DialogFragment

/**
 * The two choices the app this was built from offers, as a Cloudstream settings dialog.
 *
 *   Catalogue  - which service the search box asks. "All" merges every one of them.
 *   Sources    - which services may be tried for a track, and in what order.
 *
 * Cloudstream's settings are a dialog, not a draggable list, so the order is fixed and shown rather
 * than dragged: YouTube first because it is the only source that returns full-length audio for any
 * track, then SoundCloud (full length when the uploader allows it), then the three that answer with
 * a preview, and Apple last because its preview is the shortest lived.
 */
object MusicBoxPrefs {
    private const val FILE = "musicbox"
    private const val KEY_CATALOGUE = "catalogue"
    private const val KEY_SOURCES = "sources"
    private const val KEY_ZARZ_TICKET = "zarz_ticket"

    /** value used for "All" */
    const val ALL = "all"

    val catalogues = listOf(
        "all" to "All sources",
        "deezer" to "Deezer",
        "qobuz" to "Qobuz",
        "tidal" to "Tidal",
        "soundcloud" to "SoundCloud",
        "apple" to "Apple Music",
        "youtube" to "YouTube Music"
    )

    /** the order a track is tried in; the value is what gets stored */
    val sources = listOf(
        "youtube" to "YouTube Music (full)",
        "soundcloud" to "SoundCloud (full)",
        "tidal" to "Tidal",
        "qobuz" to "Qobuz",
        "deezer" to "Deezer",
        "apple" to "Apple Music"
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /**
     * The ticket the zarz resolver wants on a download call. The extensions do not sign it
     * themselves - they call session.signedFetch and the host app signs with keys an extension
     * never sees - so the ticket has to come from a verification the user completes, and is pasted
     * here. With one, Qobuz/Tidal/Deezer return the real file instead of a preview.
     */
    fun zarzTicket(ctx: Context): String? =
        prefs(ctx).getString(KEY_ZARZ_TICKET, null)?.ifBlank { null }

    fun setZarzTicket(ctx: Context, value: String?) {
        prefs(ctx).edit().putString(KEY_ZARZ_TICKET, value?.ifBlank { null }).apply()
    }

    fun catalogue(ctx: Context): String =
        prefs(ctx).getString(KEY_CATALOGUE, ALL) ?: ALL

    fun setCatalogue(ctx: Context, value: String) {
        prefs(ctx).edit().putString(KEY_CATALOGUE, value).apply()
    }

    /** enabled sources, already in priority order */
    fun enabledSources(ctx: Context): List<String> {
        val stored = prefs(ctx).getStringSet(KEY_SOURCES, null)
            ?: return sources.map { it.first }
        return sources.map { it.first }.filter { stored.contains(it) }
    }

    fun setEnabledSources(ctx: Context, values: Set<String>) {
        prefs(ctx).edit().putStringSet(KEY_SOURCES, values).apply()
    }
}

class MusicBoxSettings : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): android.app.Dialog {
        val ctx = requireContext()

        // the three dialogs refer to each other, so they are held in variables rather than
        // declared as local functions - Kotlin has no forward declaration for those
        lateinit var openRoot: () -> Unit

        val openCatalogue: () -> Unit = {
            val values = MusicBoxPrefs.catalogues.map { it.first }
            val labels = MusicBoxPrefs.catalogues.map { it.second }.toTypedArray()
            val current = values.indexOf(MusicBoxPrefs.catalogue(ctx)).coerceAtLeast(0)

            AlertDialog.Builder(ctx)
                .setTitle("Catalogue")
                .setSingleChoiceItems(labels, current) { d, which ->
                    MusicBoxPrefs.setCatalogue(ctx, values[which])
                    d.dismiss()
                    openRoot()
                }
                .setNegativeButton("Back") { _, _ -> openRoot() }
                .show()
        }

        val openSources: () -> Unit = {
            val values = MusicBoxPrefs.sources.map { it.first }
            val labels = MusicBoxPrefs.sources.map { it.second }.toTypedArray()
            val enabled = MusicBoxPrefs.enabledSources(ctx).toMutableSet()

            AlertDialog.Builder(ctx)
                .setTitle("Sources - tried top to bottom")
                .setMultiChoiceItems(
                    labels,
                    values.map { enabled.contains(it) }.toBooleanArray()
                ) { _, which, checked ->
                    if (checked) enabled.add(values[which]) else enabled.remove(values[which])
                }
                .setPositiveButton("Save") { _, _ ->
                    if (enabled.isEmpty()) enabled.add("youtube")
                    MusicBoxPrefs.setEnabledSources(ctx, enabled)
                    openRoot()
                }
                .setNegativeButton("Cancel") { _, _ -> openRoot() }
                .show()
        }

        val openChallenge: () -> Unit = {
            val labels = MusicBoxBrowser.hosts.map { it.second }.toTypedArray()
            AlertDialog.Builder(ctx)
                .setTitle("Solve a challenge for")
                .setItems(labels) { d, which ->
                    d.dismiss()
                    runCatching {
                        MusicBoxBrowser.solve(requireActivity(), MusicBoxBrowser.hosts[which].second)
                    }
                }
                .setNegativeButton("Back") { _, _ -> openRoot() }
                .show()
        }

        val openZarz: () -> Unit = {
            val current = MusicBoxPrefs.zarzTicket(ctx).orEmpty()
            val input = android.widget.EditText(ctx).apply {
                setText(current)
                hint = "X-Zarz-Ticket value"
            }
            AlertDialog.Builder(ctx)
                .setTitle("Zarz ticket")
                .setMessage(
                    "Paste the ticket from the verification page. With one, Qobuz, Tidal and " +
                        "Deezer return the real file; without one they only offer a preview."
                )
                .setView(input)
                .setPositiveButton("Save") { _, _ ->
                    MusicBoxPrefs.setZarzTicket(ctx, input.text.toString().trim())
                    openRoot()
                }
                .setNeutralButton("Verify") { _, _ ->
                    runCatching {
                        MusicBoxBrowser.solve(requireActivity(), "https://api.zarz.moe/")
                    }
                    openRoot()
                }
                .setNegativeButton("Cancel") { _, _ -> openRoot() }
                .show()
        }

        openRoot = {
            val catalogueLabel = MusicBoxPrefs.catalogues
                .firstOrNull { it.first == MusicBoxPrefs.catalogue(ctx) }?.second ?: "All sources"
            val sourcesLabel = MusicBoxPrefs.enabledSources(ctx)
                .mapNotNull { v -> MusicBoxPrefs.sources.firstOrNull { it.first == v }?.second }
                .joinToString(", ")
                .ifBlank { "none" }

            val solved = MusicBoxBrowser.hosts.count { MusicBoxBrowser.hasClearance(it.second) }
            val zarz = if (MusicBoxPrefs.zarzTicket(ctx) != null) "set" else "not set"
            val items = arrayOf(
                "Catalogue: $catalogueLabel",
                "Sources: $sourcesLabel",
                "Solve Cloudflare challenge" + if (solved > 0) " ($solved cleared)" else "",
                "Zarz ticket: $zarz"
            )
            AlertDialog.Builder(ctx)
                .setTitle("MusicBox")
                .setItems(items) { _, which ->
                    when (which) {
                        0 -> openCatalogue()
                        1 -> openSources()
                        2 -> openChallenge()
                        else -> openZarz()
                    }
                }
                .setNegativeButton("Close", null)
                .show()
        }

        val root = AlertDialog.Builder(ctx)
            .setTitle("MusicBox")
            .setMessage("Choose which service is searched, and which are tried for playback.")
            .setPositiveButton("Settings") { _, _ -> openRoot() }
            .setNegativeButton("Close", null)
            .create()

        // open the real menu straight away rather than making the user tap twice
        root.setOnShowListener { openRoot() }
        return root
    }
}
