package com.watchuu.kitsu

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class KitsuPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(KitsuProvider())
    }
}
