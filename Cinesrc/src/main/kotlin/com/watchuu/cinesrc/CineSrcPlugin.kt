package com.watchuu.cinesrc

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class CineSrcPlugin : Plugin() {
    override fun load(context: Context) {
        // register first, always: if anything below throws, the provider is still live and the
        // extension still appears. Doing it the other way round means a failure in the browser
        // setup silently removes the extension from the list.
        registerMainAPI(CineSrcProvider())
        runCatching { CineSrcBrowser.init(context) }
    }
}
