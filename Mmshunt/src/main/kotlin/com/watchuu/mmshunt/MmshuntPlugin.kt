package com.watchuu.mmshunt

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class MmshuntPlugin : Plugin() {
    override fun load(context: Context) {
        MmshuntProvider.appContext = context.applicationContext
        registerMainAPI(MmshuntProvider())
    }
}
