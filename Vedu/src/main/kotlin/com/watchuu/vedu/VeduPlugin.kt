package com.watchuu.vedu

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class VeduPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(VeduProvider())
    }
}
