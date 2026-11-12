package com.watchuu.musicbox

import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.widget.EditText
import androidx.fragment.app.DialogFragment

/**
 * The choices, and nothing else.
 *
 *   Catalogue  which service the search box asks; "All" asks every one of them
 *   Sources    which may be tried for a track, in the order shown
 *   Challenge  a window to clear a Cloudflare interstitial for a host
 *   Ticket     the resolver's ticket, which an extension cannot mint
 *
 * Cloudstream's settings are a dialog, not a draggable list, so the order is fixed and written down
 * rather than dragged.
 */
object MusicBoxPrefs {
    private const val FILE = "musicbox"
    private const val KEY_CATALOGUE = "catalogue"
    private const val KEY_SOURCES = "sources"
    private const val KEY_TICKET = "zarz_ticket"

    const val ALL = "all"

    val catalogues = listOf(
        "all" to "All sources",
        "spotify" to "Spotify Web",
        "apple" to "Apple Music",
        "deezer" to "Deezer",
        "soundcloud" to "SoundCloud",
        "tidal" to "Tidal",
        "qobuz" to "Qobuz"
    )

    /** the order a track is tried in */
    val sources = listOf(
        "youtube" to "YouTube Music (full)",
        "soundcloud" to "SoundCloud (full)",
        "tidal" to "Tidal",
        "qobuz" to "Qobuz",
        "deezer" to "Deezer",
        "apple" to "Apple Music"
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun catalogue(ctx: Context): String = prefs(ctx).getString(KEY_CATALOGUE, ALL) ?: ALL

    fun setCatalogue(ctx: Context, value: String) {
        prefs(ctx).edit().putString(KEY_CATALOGUE, value).apply()
    }

    fun enabledSources(ctx: Context): List<String> {
        val stored = prefs(ctx).getStringSet(KEY_SOURCES, null)
            ?: return sources.map { it.first }
        return sources.map { it.first }.filter { stored.contains(it) }
    }

    fun setEnabledSources(ctx: Context, values: Set<String>) {
        prefs(ctx).edit().putStringSet(KEY_SOURCES, values).apply()
    }

    fun zarzTicket(ctx: Context): String? =
        prefs(ctx).getString(KEY_TICKET, null)?.ifBlank { null }

    fun setZarzTicket(ctx: Context, value: String?) {
        prefs(ctx).edit().putString(KEY_TICKET, value?.ifBlank { null }).apply()
    }
}

class MusicBoxSettings : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): android.app.Dialog {
        val ctx = requireContext()
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

        val openTicket: () -> Unit = {
            val input = EditText(ctx).apply {
                setText(MusicBoxPrefs.zarzTicket(ctx).orEmpty())
                hint = "X-Zarz-Ticket"
            }
            AlertDialog.Builder(ctx)
                .setTitle("Resolver ticket")
                .setMessage(
                    "The extensions cannot mint this - they ask the host app to sign it. Paste one " +
                        "and Qobuz/Tidal/Deezer return the real file; leave it empty for previews."
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
                .joinToString(", ").ifBlank { "none" }
            val cleared = MusicBoxBrowser.hosts.count { MusicBoxBrowser.hasClearance(it.second) }
            val ticket = if (MusicBoxPrefs.zarzTicket(ctx) != null) "set" else "not set"

            AlertDialog.Builder(ctx)
                .setTitle("MusicBox")
                .setItems(
                    arrayOf(
                        "Catalogue: $catalogueLabel",
                        "Sources: $sourcesLabel",
                        "Solve Cloudflare challenge" + if (cleared > 0) " ($cleared cleared)" else "",
                        "Resolver ticket: $ticket"
                    )
                ) { _, which ->
                    when (which) {
                        0 -> openCatalogue()
                        1 -> openSources()
                        2 -> openChallenge()
                        else -> openTicket()
                    }
                }
                .setNegativeButton("Close", null)
                .show()
        }

        val root = AlertDialog.Builder(ctx)
            .setTitle("MusicBox")
            .setMessage("Choose a catalogue and the sources to try.")
            .setPositiveButton("Settings") { _, _ -> openRoot() }
            .setNegativeButton("Close", null)
            .create()
        root.setOnShowListener { openRoot() }
        return root
    }
}
