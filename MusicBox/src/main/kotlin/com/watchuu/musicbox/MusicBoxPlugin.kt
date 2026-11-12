package com.watchuu.musicbox

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class MusicBoxPlugin : Plugin() {
    override fun load(context: Context) {
        // register first, so the provider is live even if anything below fails
        MusicBoxProvider.appContext = context.applicationContext
        registerMainAPI(MusicBoxProvider())

        runCatching {
            val activity = context as AppCompatActivity
            openSettings = {
                MusicBoxSettings().show(activity.supportFragmentManager, "MusicBoxSettings")
            }
        }
    }
}
