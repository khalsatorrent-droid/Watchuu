package com.watchuu.hicnmusic

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class HiCnMusicPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(HiCnMusicProvider())
    }
}
