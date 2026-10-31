package com.watchuu.movienerds

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class MovieNerdsPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(MovieNerdsProvider())
    }
}
