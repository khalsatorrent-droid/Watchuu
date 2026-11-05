package com.watchuu.katmoviehd

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class KatMovieHDPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(KatMovieHDProvider())
    }
}
