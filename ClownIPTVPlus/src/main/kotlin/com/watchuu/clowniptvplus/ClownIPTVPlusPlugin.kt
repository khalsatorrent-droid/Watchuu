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
        val entries = CountryData.SELECTABLE
        val current = prefs.getString(ClownIPTVPlusProvider.KEY_COUNTRY, CountryData.ALL)
        val checked = entries.indexOf(current).coerceAtLeast(0)
        var selected = checked
        var adult = prefs.getBoolean(ClownIPTVPlusProvider.KEY_ADULT, false)
        fun label() = if (adult) "NSFW: ON" else "NSFW: OFF"

        val dialog = AlertDialog.Builder(context)
            .setTitle("Clown IPTV+ — Country")
            .setSingleChoiceItems(entries.toTypedArray<CharSequence>(), checked) { _, which -> selected = which }
            .setPositiveButton("Save") { _, _ ->
                val value = entries[selected]
                prefs.edit()
                    .putString(ClownIPTVPlusProvider.KEY_COUNTRY, value)
                    .putBoolean(ClownIPTVPlusProvider.KEY_ADULT, adult)
                    .apply()
                CommonActivity.showToast(
                    if (value == CountryData.ALL) "All countries selected. Reload the home page."
                    else "$value selected. Reload the home page.",
                    Toast.LENGTH_LONG
                )
            }
            .setNegativeButton("Cancel", null)
            .setNeutralButton(label(), null)
            .create()

        // the neutral button toggles NSFW without closing the dialog
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_NEUTRAL)?.setOnClickListener {
                adult = !adult
                prefs.edit().putBoolean(ClownIPTVPlusProvider.KEY_ADULT, adult).apply()
                (it as? Button)?.text = label()
            }
        }
        dialog.show()
    }
}
