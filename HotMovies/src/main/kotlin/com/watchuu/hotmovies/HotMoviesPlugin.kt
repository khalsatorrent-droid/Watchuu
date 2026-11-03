package com.watchuu.hotmovies

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class HotMoviesPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(HotMoviesProvider())
    }
}
