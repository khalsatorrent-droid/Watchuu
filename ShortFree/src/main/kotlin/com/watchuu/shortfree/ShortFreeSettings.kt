package com.watchuu.shortfree

import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.text.InputType
import android.view.ViewGroup
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

/**
 * ShortFree runs on the Dashtoon/Dashverse "reels" backend. The app is one tenant on that backend;
 * the tenant id, app version and country go into every request. They are read from the app here but
 * kept adjustable in case the backend starts expecting different values.
 */
object ShortFreeSettings {
    private var prefs: SharedPreferences? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // defaults read from the ShortFree apk (com.dashtoon.video.shortfree, v1.49.101, Dashverse India)
    const val DEFAULT_TENANT = "shortfree"
    const val DEFAULT_VERSION = "1.49.101"
    const val DEFAULT_COUNTRY = "IN"

    fun init(context: Context) {
        if (prefs == null) prefs = context.applicationContext.getSharedPreferences("shortfree_settings", Context.MODE_PRIVATE)
    }

    private fun str(k: String, d: String) = prefs?.getString(k, d)?.ifBlank { d } ?: d
    private fun put(k: String, v: String) { prefs?.edit()?.putString(k, v.trim())?.apply() }

    var tenant: String
        get() = str("tenant", DEFAULT_TENANT)
        set(v) = put("tenant", v)

    var version: String
        get() = str("version", DEFAULT_VERSION)
        set(v) = put("version", v)

    var country: String
        get() = str("country", DEFAULT_COUNTRY)
        set(v) = put("country", v)

    @Volatile
    var lastReport: String = "Nothing played yet."

    fun toast(msg: String) {
        scope.launch(Dispatchers.Main) {
            try {
                CommonActivity.showToast(msg, Toast.LENGTH_LONG)
            } catch (_: Throwable) {
            }
        }
    }

    private fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    fun show(context: Context) {
        val act = (context as? android.app.Activity)
            ?: try { CommonActivity.activity } catch (_: Throwable) { null } ?: return
        init(act)
        val pad = dp(act, 16)
        val root = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        fun label(t: String) = TextView(act).apply { text = t; textSize = 14f; setPadding(0, dp(act, 10), 0, dp(act, 2)) }
        fun field(v: String) = EditText(act).apply {
            setText(v); textSize = 13f; isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT
        }

        root.addView(TextView(act).apply { text = "ShortFree settings"; textSize = 18f })
        root.addView(label("Tenant (X-Tenant) - catalog selector"))
        val etTenant = field(tenant); root.addView(etTenant)
        root.addView(label("App version (user-agent)"))
        val etVer = field(version); root.addView(etVer)
        root.addView(label("Country (X-Country)"))
        val etCountry = field(country); root.addView(etCountry)

        root.addView(Button(act).apply {
            text = "Test connection"
            isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setOnClickListener {
                Toast.makeText(act, "Testing...", Toast.LENGTH_SHORT).show()
                scope.launch {
                    val msg = try {
                        val p = ShortFreeProvider()
                        val items = p.getMainPage(1, com.lagradost.cloudstream3.MainPageRequest("Explore", "explore", false))
                        "OK: ${items?.items?.sumOf { it.list.size } ?: 0} shows on the first page.\n\n${ShortFreeAuth.lastError.ifBlank { "auth ok" }}"
                    } catch (e: Throwable) {
                        "FAILED: ${e.message}\n\nauth: ${ShortFreeAuth.lastError}"
                    }
                    withContext(Dispatchers.Main) {
                        AlertDialog.Builder(act).setTitle("ShortFree test").setMessage(msg).setPositiveButton("OK", null).show()
                    }
                }
            }
        })
        root.addView(Button(act).apply {
            text = "Show last link report"
            isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setOnClickListener {
                AlertDialog.Builder(act).setTitle("Last link report").setMessage(lastReport).setPositiveButton("OK", null).show()
            }
        })

        AlertDialog.Builder(act)
            .setView(ScrollView(act).apply { addView(root) })
            .setPositiveButton("Save") { _, _ ->
                tenant = etTenant.text.toString()
                version = etVer.text.toString()
                country = etCountry.text.toString()
                ShortFreeAuth.reset()
                Toast.makeText(act, "Saved", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Close", null)
            .show()
    }
}
