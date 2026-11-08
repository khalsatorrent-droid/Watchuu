package com.watchuu.cinephile

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class CinephilePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(CinephileProvider())
    }
}
