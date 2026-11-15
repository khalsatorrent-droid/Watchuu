package com.watchuu.octave

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
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
 * Three fields and four buttons. Nothing here is required for browsing or previews - these exist only
 * to unlock lossless, which needs the site's account key and a Cloudflare cookie.
 */
object OctaveSettings {

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

        fun field(hint: String, value: String) = EditText(act).apply {
            this.hint = hint
            setText(value)
            textSize = 13f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 1
            maxLines = 3
        }

        fun button(t: String, onClick: () -> Unit) = Button(act).apply {
            text = t
            isAllCaps = false
            setOnClickListener { onClick() }
        }

        val status = TextView(act).apply { textSize = 14f }
        fun refresh() {
            status.text = when {
                OctaveApi.hasClearance() && OctaveApi.accountKey.isNotBlank() -> "Ready: cookie and key present"
                OctaveApi.hasClearance() -> "Cookie present, no account key - previews only"
                OctaveApi.accountKey.isNotBlank() -> "Key present, no cookie - the token call will be refused"
                else -> "Nothing set - browsing and previews work, lossless does not"
            }
        }
        refresh()

        root.addView(text("Octave", 20f))
        root.addView(status)
        root.addView(text(
            "Browsing and 30-second previews need nothing. Full-length FLAC needs two things from the " +
                "site: a Cloudflare cookie and the account key it issues when you are signed in."
        ))

        val keyField = field("account key (octv_…)", OctaveApi.accountKey)
        val cookieField = field("cf_clearance cookie", OctaveApi.manualCookie)
        val uaField = field("user agent (only if you pasted a cookie from a browser)", OctaveApi.customUa)
        root.addView(text("Account key", 15f))
        root.addView(keyField)
        root.addView(text("Cloudflare cookie", 15f))
        root.addView(cookieField)
        root.addView(text("User agent", 15f))
        root.addView(uaField)

        root.addView(button("Save") {
            OctaveApi.accountKey = keyField.text.toString()
            OctaveApi.manualCookie = cookieField.text.toString()
            OctaveApi.customUa = uaField.text.toString()
            refresh()
            Toast.makeText(act, "Octave: saved", Toast.LENGTH_SHORT).show()
        })

        root.addView(button("Check the key in the field") {
            scope.launch {
                val problem = OctaveApi.validateKey(keyField.text.toString().trim())
                withContext(Dispatchers.Main) {
                    if (problem == null) {
                        OctaveApi.accountKey = keyField.text.toString()
                        refresh()
                    }
                    Toast.makeText(
                        act,
                        if (problem == null) "Octave: key works" else "Octave: key $problem",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        })

        root.addView(button("Test the key (runs the real token request)") {
            scope.launch {
                val key = keyField.text.toString().trim()
                val shape = OctaveApi.keyShape(key)
                OctaveApi.accountKey = key
                val token = OctaveApi.playbackToken(true)
                val answer = OctaveApi.lastTokenAnswer.ifBlank { "(no answer)" }
                val verdict = when {
                    token != null -> "TOKEN OBTAINED - lossless should play"
                    shape != "looks right" -> "key shape: $shape"
                    answer.contains("token", true) -> "the site answered with a token but it was not read"
                    else -> "the site refused the request - see its answer below"
                }
                withContext(Dispatchers.Main) {
                    refresh()
                    AlertDialog.Builder(act)
                        .setTitle("Octave token test")
                        .setMessage(
                            "key shape: $shape\n" +
                                "cf_clearance: ${if (OctaveApi.hasClearance()) "yes" else "NO"}\n\n" +
                                "$verdict\n\n" +
                                "The site said:\n$answer"
                        )
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        })
        root.addView(button("Open the site to get cookie + key") {
            scope.launch {
                val msg = OctaveLogin.runAndSave(act)
                withContext(Dispatchers.Main) {
                    refresh()
                    keyField.setText(OctaveApi.accountKey)
                    AlertDialog.Builder(act).setTitle("Octave").setMessage(msg)
                        .setPositiveButton("OK", null).show()
                }
            }
        })

        root.addView(button("Fill the user agent with this device's") {
            uaField.setText(OctaveApi.userAgent())
            Toast.makeText(act, "Octave: filled - press Save", Toast.LENGTH_SHORT).show()
        })
        root.addView(button("Copy this device's user agent") {
            val cm = act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Octave UA", OctaveApi.userAgent()))
            Toast.makeText(act, "Octave: copied - a pasted cookie only works with this exact UA", Toast.LENGTH_LONG).show()
        })

        root.addView(button("Test connection") {
            scope.launch {
                val chart = OctaveApi.json("${OctaveApi.API}/api/lastfm/global-chart-tracks?limit=1")
                val token = OctaveApi.playbackToken(true)
                val msg = buildString {
                    append(if (chart != null) "browsing OK" else "browsing failed")
                    append(", ")
                    append(if (token != null) "lossless OK" else "lossless needs cookie + key (previews still play)")
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(act, "Octave: $msg", Toast.LENGTH_LONG).show()
                }
            }
        })

        root.addView(button("Show report") {
            AlertDialog.Builder(act).setTitle("Octave report").setMessage(OctaveApi.report())
                .setPositiveButton("OK", null).show()
        })

        root.addView(button("Clear cookie and key") {
            OctaveApi.clearCookies()
            OctaveApi.accountKey = ""
            keyField.setText("")
            cookieField.setText("")
            refresh()
            Toast.makeText(act, "Octave: cleared", Toast.LENGTH_SHORT).show()
        })

        val scroll = ScrollView(act).apply { addView(root) }
        AlertDialog.Builder(act).setView(scroll).setNegativeButton("Close", null).show()
    }
}
