package com.watchuu.arcod

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.lagradost.cloudstream3.CommonActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Sign in with the account the site already uses. The token is kept, so this is a one-time step
 * unless the site expires it - in which case the saved email and password are used to sign in again
 * automatically.
 */
object ArcodSettings {

    fun show(context: Context) {
        val act: Activity = CommonActivity.activity ?: context as? Activity ?: return
        val scope = CoroutineScope(Dispatchers.IO)

        val root = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            val p = (18 * act.resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
        }

        fun text(t: String, size: Float = 13f) = TextView(act).apply {
            text = t
            textSize = size
            setPadding(0, 8, 0, 8)
        }

        fun field(hint: String, value: String, secret: Boolean = false) = EditText(act).apply {
            this.hint = hint
            setText(value)
            textSize = 14f
            inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }

        fun button(t: String, onClick: () -> Unit) = Button(act).apply {
            text = t
            isAllCaps = false
            setOnClickListener { onClick() }
        }

        root.addView(text("Arcod", 20f))
        root.addView(text("Signing in is optional - the catalogue and playback work without it. Use the email and password you have on player.arcod.xyz if you want your account attached; the details stay on this device so the extension can sign in again when the site expires the session."))

        val status = TextView(act).apply {
            textSize = 13f
            setPadding(0, 8, 0, 8)
        }
        fun refresh() {
            status.text = if (ArcodApi.hasToken())
                "Status: signed in${ArcodApi.displayName.takeIf { it.isNotBlank() }?.let { " as $it" } ?: ""}"
            else "Status: not signed in"
        }

        val emailField = field("email", ArcodApi.email)
        val passField = field("password", ArcodApi.password, secret = true)
        root.addView(text("Email", 15f))
        root.addView(emailField)
        root.addView(text("Password", 15f))
        root.addView(passField)

        root.addView(button("Sign in") {
            val e = emailField.text.toString().trim()
            val p = passField.text.toString()
            scope.launch {
                val problem = ArcodApi.login(e, p)
                withContext(Dispatchers.Main) {
                    refresh()
                    AlertDialog.Builder(act)
                        .setTitle("Arcod")
                        .setMessage(problem ?: "Signed in - search and playback should work now")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        })

        root.addView(button("Sign out") {
            ArcodApi.logout()
            passField.setText("")
            refresh()
            Toast.makeText(act, "Arcod: signed out", Toast.LENGTH_SHORT).show()
        })

        root.addView(button("Test") {
            scope.launch {
                val t0 = System.currentTimeMillis()
                val root = ArcodApi.get(
                    "/api/player/search",
                    mapOf("q" to "arijit", "limit" to "1", "offset" to "0")
                )
                val n = root?.optJSONObject("data")?.optJSONObject("most_popular")?.optJSONArray("items")?.length() ?: 0
                val msg = if (n > 0) "catalogue OK (${System.currentTimeMillis() - t0}ms)"
                else "no answer - sign in first, then try again"
                withContext(Dispatchers.Main) {
                    Toast.makeText(act, "Arcod: $msg", Toast.LENGTH_LONG).show()
                }
            }
        })

        root.addView(button("Show report") {
            AlertDialog.Builder(act).setTitle("Arcod report").setMessage(ArcodApi.report())
                .setPositiveButton("OK", null).show()
        })

        refresh()
        AlertDialog.Builder(act).setView(ScrollView(act).apply { addView(root) })
            .setNegativeButton("Close", null).show()
    }
}
