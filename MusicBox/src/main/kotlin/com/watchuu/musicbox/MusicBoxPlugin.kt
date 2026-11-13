package com.watchuu.musicbox

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class MusicBoxPlugin : Plugin() {
    override fun load(context: Context) {
        // the provider first: anything that throws after this still leaves it registered
        registerMainAPI(MusicBoxProvider())

        // the settings object needs a context or every toggle silently does nothing: its prefs
        // field stays null and each write is dropped
        runCatching { MusicBoxSettings.init(context) }

        runCatching {
            openSettings = { MusicBoxSettings.show(context) }
        }
    }
}
