package com.watchuu.fullvideosporn

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class FullvideospornPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(FullvideospornProvider())
    }
}
