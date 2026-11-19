package com.watchuu.atlantic

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.lagradost.cloudstream3.CommonActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object AtlanticSettings {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun show(context: Context) {
        val act = (context as? Activity)
            ?: (CommonActivity.activity as? Activity)
            ?: return
        AtlanticTmdb.init(act)
        AtlanticNative.init(act)

        val dp = act.resources.displayMetrics.density
        val pad = (18 * dp).toInt()

        val root = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        fun text(t: String, size: Float = 13f) = TextView(act).apply {
            this.text = t
            textSize = size
            setPadding(0, 8, 0, 8)
        }

        fun button(t: String, onClick: () -> Unit) = Button(act).apply {
            text = t
            isAllCaps = false
            setOnClickListener { onClick() }
        }

        root.addView(text("Atlantic", 20f))
        root.addView(text("The catalogue comes from TMDB, because every source in the app is asked for a stream by TMDB id. A key is built in, so this works as it is; paste your own below only if you want to use it instead (the short v3 key or the long read-access token both work)."))

        // only what the user typed: the built-in key is not shown, so saving cannot turn it into "yours"
        val keyField = EditText(act).apply {
            hint = "empty = built-in key"
            setText(AtlanticTmdb.userKey)
            textSize = 14f
        }
        root.addView(text("TMDB api key", 15f))
        root.addView(keyField)

        val febboxField = EditText(act).apply {
            hint = "optional - aurora needs one"
            setText(AtlanticTmdb.febboxKey)
            textSize = 14f
        }
        root.addView(text("Febbox key (optional, only Aurora uses it)", 15f))
        root.addView(febboxField)

        val sourceField = EditText(act).apply {
            hint = "all"
            setText(AtlanticTmdb.enabledSources)
            textSize = 14f
        }
        root.addView(text("Sources (comma separated ids, empty = all ${AtlanticNative.SOURCES.size})", 15f))
        root.addView(sourceField)
        root.addView(text(AtlanticNative.SOURCES.joinToString(", ") { it.first }))

        val status = TextView(act).apply { textSize = 13f; setPadding(0, 8, 0, 8) }
        fun refresh() {
            status.text = buildString {
                append("TMDB key: ").append(if (AtlanticTmdb.usingBuiltInKey) "built in" else "yours").append('\n')
                append("resolver library: ").append(
                    when {
                        AtlanticNative.isLoaded -> "loaded" +
                            AtlanticNative.libraryVersion.takeIf { it.isNotBlank() }?.let { " (version $it)" }.orEmpty()
                        AtlanticNative.error != null -> "failed - ${AtlanticNative.error}"
                        else -> "not loaded yet - it loads the first time something is played"
                    }
                )
            }
        }

        root.addView(button("Save") {
            AtlanticTmdb.apiKey = keyField.text.toString()
            AtlanticTmdb.febboxKey = febboxField.text.toString()
            AtlanticTmdb.enabledSources = sourceField.text.toString()
            refresh()
            Toast.makeText(act, "Atlantic: saved", Toast.LENGTH_SHORT).show()
        })

        root.addView(button("Load the resolver library") {
            scope.launch {
                AtlanticNative.ensureLoaded(act)
                withContext(Dispatchers.Main) {
                    refresh()
                    Toast.makeText(
                        act,
                        if (AtlanticNative.isLoaded) "Atlantic: library loaded" else "Atlantic: ${AtlanticNative.error ?: "failed"}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        })

        root.addView(button("Clear the downloaded library") {
            AtlanticNative.clearCache(act)
            refresh()
            Toast.makeText(act, "Atlantic: cleared - restart the app to download a fresh copy", Toast.LENGTH_LONG).show()
        })

        root.addView(button("Test") {
            scope.launch {
                val tmdb = AtlanticTmdb.details("550", false) != null
                if (!AtlanticNative.isLoaded) AtlanticNative.ensureLoaded(act)
                val msg = buildString {
                    append(if (tmdb) "TMDB ok" else "TMDB refused - check the key")
                    append(" · library ").append(if (AtlanticNative.isLoaded) "ok" else "not loaded")
                }
                withContext(Dispatchers.Main) {
                    refresh()
                    Toast.makeText(act, "Atlantic: $msg", Toast.LENGTH_LONG).show()
                }
            }
        })

        root.addView(status)
        refresh()
        AlertDialog.Builder(act).setView(ScrollView(act).apply { addView(root) })
            .setNegativeButton("Close", null).show()
    }
}
