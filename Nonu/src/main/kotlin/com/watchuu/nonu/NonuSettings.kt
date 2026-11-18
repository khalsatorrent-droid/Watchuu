package com.watchuu.nonu

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

/** storefront, an optional cookie, password, and the step log */
object NonuSettings {

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

        root.addView(text("Nonu", 20f))
        root.addView(text("The catalogue is Apple Music, served per country. Change the storefront if you want a different one."))

        val ccField = field("storefront, e.g. in, us, gb", NonuApi.storefront)
        val cookieField = field("cf_clearance cookie (only if requests are refused)", NonuApi.manualCookie)
        root.addView(text("Storefront", 15f))
        root.addView(ccField)
        root.addView(text("Cloudflare cookie", 15f))
        root.addView(cookieField)

        val passwordField = field("Extension password (leave empty to disable)", NonuApi.password)
        root.addView(text("Password protection", 15f))
        root.addView(passwordField)

        root.addView(button("Save") {
            NonuApi.storefront = ccField.text.toString()
            NonuApi.manualCookie = cookieField.text.toString()
            val pw = passwordField.text.toString().trim()
            NonuApi.password = pw
            NonuApi.savedPassword = pw
            Toast.makeText(act, "Nonu: saved", Toast.LENGTH_SHORT).show()
        })

        root.addView(button("Open the site (Cloudflare check)") {
            scope.launch {
                val msg = NonuLogin.runAndSave(act)
                withContext(Dispatchers.Main) {
                    AlertDialog.Builder(act).setTitle("Nonu").setMessage(msg)
                        .setPositiveButton("OK", null).show()
                }
            }
        })

        root.addView(button("Clear cookies") {
            NonuApi.clearCookies()
            cookieField.setText("")
            Toast.makeText(act, "Nonu: cookies cleared", Toast.LENGTH_SHORT).show()
        })

        root.addView(button("Test") {
            scope.launch {
                val t0 = System.currentTimeMillis()
                val rootJson = NonuApi.json(
                    "/amp/v1/catalog/${NonuApi.storefront}/search?term=arijit&types=songs&limit=1&l=en-GB&platform=web"
                )
                val n = rootJson?.optJSONObject("results")?.optJSONObject("songs")?.optJSONArray("data")?.length() ?: 0
                val msg = if (n > 0) "catalogue OK (${System.currentTimeMillis() - t0}ms)"
                else "no answer - open the log"
                withContext(Dispatchers.Main) {
                    Toast.makeText(act, "Nonu: $msg", Toast.LENGTH_LONG).show()
                }
            }
        })

        root.addView(button("Show log (every step)") {
            val box = TextView(act).apply {
                text = NonuApi.logDump()
                textSize = 10f
                typeface = android.graphics.Typeface.MONOSPACE
                setTextIsSelectable(true)
                val p = (12 * act.resources.displayMetrics.density).toInt()
                setPadding(p, p, p, p)
            }
            AlertDialog.Builder(act)
                .setTitle("Nonu log")
                .setView(ScrollView(act).apply { addView(box) })
                .setPositiveButton("Copy") { _, _ ->
                    val cm = act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("Nonu log", NonuApi.logDump()))
                    Toast.makeText(act, "copied", Toast.LENGTH_SHORT).show()
                }
                .setNeutralButton("Clear") { _, _ -> NonuApi.logClear() }
                .setNegativeButton("Close", null)
                .show()
        })

        root.addView(button("Show report") {
            AlertDialog.Builder(act).setTitle("Nonu report").setMessage(NonuApi.report())
                .setPositiveButton("OK", null).show()
        })

        AlertDialog.Builder(act).setView(ScrollView(act).apply { addView(root) })
            .setNegativeButton("Close", null).show()
    }
}
