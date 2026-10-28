package com.watchuu.familypornhd

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class FamilyPornHDPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(FamilyPornHDProvider())
    }
}
