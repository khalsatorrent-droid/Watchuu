package com.watchuu.clowniptvplus

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.content.SharedPreferences
import android.widget.Button
import android.widget.Toast
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class ClownIPTVPlusPlugin : Plugin() {
    override fun load(context: Context) {
        val prefs = context.getSharedPreferences(ClownIPTVPlusProvider.PREFS_NAME, 0)
        registerMainAPI(ClownIPTVPlusProvider(prefs))
        runCatching { openSettings = { ctx -> showSettings(ctx, prefs) } }
    }

    private fun showSettings(ctx: Context, prefs: SharedPreferences) {
        val context: Activity = (ctx as? Activity) ?: CommonActivity.activity ?: return

        // The selectable list is every row name (excluding "All countries" sentinel)
        val entries = CountryData.ROW_ORDER.toTypedArray<CharSequence>()

        // Current selection: set of saved country names
        val savedRaw = prefs.getString(ClownIPTVPlusProvider.KEY_COUNTRIES, "") ?: ""
        val savedSet = savedRaw.split(',').map { it.trim() }.filter { it.isNotEmpty() && it != ClownIPTVPlusProvider.ALL }.toMutableSet()

        // BooleanArray parallel to entries
        val checked = BooleanArray(entries.size) { entries[it].toString() in savedSet }
        val current = checked.copyOf()   // tracks live toggles

        var adult = prefs.getBoolean(ClownIPTVPlusProvider.KEY_ADULT, false)
        fun adultLabel() = if (adult) "NSFW: ON" else "NSFW: OFF"

        val dialog = AlertDialog.Builder(context)
            .setTitle("Clown IPTV+ — Countries")
            .setMultiChoiceItems(entries, current) { _, which, isChecked ->
                current[which] = isChecked
            }
            .setPositiveButton("Save") { _, _ ->
                val newSet = entries.filterIndexed { i, _ -> current[i] }.map { it.toString() }.toSet()
                val value = newSet.joinToString(",")
                prefs.edit()
                    .putString(ClownIPTVPlusProvider.KEY_COUNTRIES, value)
                    .putBoolean(ClownIPTVPlusProvider.KEY_ADULT, adult)
                    .apply()
                val msg = when {
                    newSet.isEmpty() -> "All countries shown. Reload the home page."
                    newSet.size == 1 -> "${newSet.first()} selected. Reload the home page."
                    else -> "${newSet.size} countries selected. Reload the home page."
                }
                CommonActivity.showToast(msg, Toast.LENGTH_LONG)
            }
            .setNegativeButton("Cancel", null)
            .setNeutralButton(adultLabel(), null)
            .create()

        // Neutral button toggles NSFW without closing the dialog
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_NEUTRAL)?.setOnClickListener {
                adult = !adult
                prefs.edit().putBoolean(ClownIPTVPlusProvider.KEY_ADULT, adult).apply()
                (it as? Button)?.text = adultLabel()
            }
        }
        dialog.show()
    }
}
