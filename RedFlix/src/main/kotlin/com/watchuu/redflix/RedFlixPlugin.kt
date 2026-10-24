package com.watchuu.redflix

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class RedFlixPlugin : Plugin() {
    override fun load(context: Context) {
        RedFlixBrowser.init(context)
        registerMainAPI(RedFlixProvider())
    }
}
