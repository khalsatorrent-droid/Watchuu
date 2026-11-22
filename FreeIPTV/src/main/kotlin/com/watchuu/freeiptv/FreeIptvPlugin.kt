package com.watchuu.freeiptv

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.widget.Toast
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@CloudstreamPlugin
class FreeIptvPlugin : Plugin() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun load(context: Context) {
        val app = context.applicationContext ?: context
        val prefs = context.getSharedPreferences(FreeIptvAccounts.PREFS_NAME, 0)
        val accounts = FreeIptvAccounts(app, prefs)
        registerMainAPI(FreeIptvProvider(accounts))
        runCatching { openSettings = { ctx -> showSettings(ctx, accounts) } }
    }

    private fun status(accounts: FreeIptvAccounts): String {
        val a = accounts.saved() ?: return "No account yet. One is created the first time channels load."
        val left = a.expiresAt - System.currentTimeMillis()
        val until = SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(a.expiresAt))
        return buildString {
            append("Account ").append(a.user).append('\n')
            append(if (left > 0) "Valid until $until (renewed automatically in the last 30 minutes)" else "Expired $until - a new one is made on the next use")
            accounts.lastError?.let { append("\nLast problem: ").append(it) }
        }
    }

    private fun showSettings(ctx: Context, accounts: FreeIptvAccounts) {
        val act: Activity = (ctx as? Activity) ?: CommonActivity.activity ?: return
        AlertDialog.Builder(act)
            .setTitle("Free IPTV")
            .setMessage(status(accounts))
            .setPositiveButton("New account now") { _, _ ->
                Toast.makeText(act, "Free IPTV: getting a new account...", Toast.LENGTH_SHORT).show()
                scope.launch {
                    val a = accounts.current(force = true)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(act, if (a != null) "Free IPTV: new account ready" else "Free IPTV: failed - ${accounts.lastError ?: "try again"}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }
}
