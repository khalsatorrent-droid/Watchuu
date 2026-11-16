package com.watchuu.applymusic

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

/** storefront, an optional cookie, and the step log */
object ApplyMusicSettings {

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

        root.addView(text("Apply Music", 20f))
        root.addView(text("The catalogue is Apple Music, served per country. Change the storefront if you want a different one."))

        val ccField = field("storefront, e.g. in, us, gb", ApplyMusicApi.storefront)
        val cookieField = field("cf_clearance cookie (only if requests are refused)", ApplyMusicApi.manualCookie)
        root.addView(text("Storefront", 15f))
        root.addView(ccField)
        root.addView(text("Cloudflare cookie", 15f))
        root.addView(cookieField)

        root.addView(button("Save") {
            ApplyMusicApi.storefront = ccField.text.toString()
            ApplyMusicApi.manualCookie = cookieField.text.toString()
            Toast.makeText(act, "Apply Music: saved", Toast.LENGTH_SHORT).show()
        })

        root.addView(button("Test") {
            scope.launch {
                val t0 = System.currentTimeMillis()
                val rootJson = ApplyMusicApi.json(
                    "/amp/v1/catalog/${ApplyMusicApi.storefront}/search?term=arijit&types=songs&limit=1&l=en-GB&platform=web"
                )
                val n = rootJson?.optJSONObject("results")?.optJSONObject("songs")?.optJSONArray("data")?.length() ?: 0
                val msg = if (n > 0) "catalogue OK (${System.currentTimeMillis() - t0}ms)"
                else "no answer - open the log"
                withContext(Dispatchers.Main) {
                    Toast.makeText(act, "Apply Music: $msg", Toast.LENGTH_LONG).show()
                }
            }
        })

        root.addView(button("Show log (every step)") {
            val box = TextView(act).apply {
                text = ApplyMusicApi.logDump()
                textSize = 10f
                typeface = android.graphics.Typeface.MONOSPACE
                setTextIsSelectable(true)
                val p = (12 * act.resources.displayMetrics.density).toInt()
                setPadding(p, p, p, p)
            }
            AlertDialog.Builder(act)
                .setTitle("Apply Music log")
                .setView(ScrollView(act).apply { addView(box) })
                .setPositiveButton("Copy") { _, _ ->
                    val cm = act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("Apply Music log", ApplyMusicApi.logDump()))
                    Toast.makeText(act, "copied", Toast.LENGTH_SHORT).show()
                }
                .setNeutralButton("Clear") { _, _ -> ApplyMusicApi.logClear() }
                .setNegativeButton("Close", null)
                .show()
        })

        root.addView(button("Show report") {
            AlertDialog.Builder(act).setTitle("Apply Music report").setMessage(ApplyMusicApi.report())
                .setPositiveButton("OK", null).show()
        })

        AlertDialog.Builder(act).setView(ScrollView(act).apply { addView(root) })
            .setNegativeButton("Close", null).show()
    }
}
