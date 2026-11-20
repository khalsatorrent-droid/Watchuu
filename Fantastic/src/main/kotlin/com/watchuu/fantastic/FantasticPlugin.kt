package com.watchuu.fantastic

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class FantasticPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(FantasticProvider())
    }
}
