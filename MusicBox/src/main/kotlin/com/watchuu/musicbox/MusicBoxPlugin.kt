package com.watchuu.musicbox

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class MusicBoxPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(MusicBoxProvider())
    }
}
