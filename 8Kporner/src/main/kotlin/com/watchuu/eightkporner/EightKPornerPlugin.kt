package com.watchuu.eightkporner

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class EightKPornerPlugin : Plugin() {
    override fun load(context: Context) {
        EightKPornerBrowser.init(context)
        registerMainAPI(EightKPornerProvider())
    }
}
