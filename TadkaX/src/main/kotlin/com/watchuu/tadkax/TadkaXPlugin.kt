package com.watchuu.tadkax

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class TadkaXPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(TadkaXProvider())
    }
}
