package com.watchuu.musicbox

import android.content.Context
import com.lagradost.cloudstream3.CloudStreamApp
import java.util.concurrent.ConcurrentHashMap

/**
 * One ZARZ session per provider, and the only place they are loaded from or written to.
 *
 * Two things forced this shape.
 *
 * 1. Playback and the settings screen used to build their own ZarzSession, so a session bootstrapped
 *    in settings was invisible to playback. Everything goes through here now.
 *
 * 2. The app scopes a signed session to namespace + base_url + **app_version** + platform - see
 *    filename() in the rust backend. A session minted as deezer@1.2.0 therefore cannot sign a
 *    request declared as tidal-web@1.1.0; the server answers 403. That is exactly what the log
 *    showed: the session was active, and the Tidal call came back "HTTP 403".
 *
 * So there is one session per app version, each bootstrapped and persisted on its own, exactly as
 * the app gives each extension its own session.
 */
object ZarzSessionManager {
    private const val PREFS = "musicbox_prefs"
    private const val KEY_PREFIX = "musicbox_zarz_session"

    private val sessions = ConcurrentHashMap<String, ZarzSession>()

    private fun key(version: String): String =
        KEY_PREFIX + "_" + version.replace("@", "_").replace(".", "_").replace("-", "_")

    private fun prefs(context: Context?) =
        context?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun contextOrNull(): Context? = CloudStreamApp.context

    /** the session for one provider, created on first use with that provider's app version */
    fun session(version: String): ZarzSession = sessions.getOrPut(version) {
        ZarzSession(appVersion = version)
    }

    /**
     * Fills a session from disk. Never overwrites a session that is still usable, and never clears
     * one that only exists in memory.
     */
    fun loadFromPrefs(version: String, context: Context? = contextOrNull(), force: Boolean = false) {
        val session = session(version)
        val stored = prefs(context)?.getString(key(version), null)

        if (!force) {
            if (session.record.isUsable()) return
            if (stored.isNullOrBlank() && session.record.sessionId.isNotBlank()) return
        }
        session.loadState(stored)
    }

    /** writes one provider's session so settings and playback agree next launch */
    fun persist(version: String, context: Context? = contextOrNull()) {
        val p = prefs(context) ?: return
        val session = session(version)
        runCatching { p.edit().putString(key(version), session.saveState()).apply() }
    }

    /** every provider version this extension signs for */
    val versions: List<String> = listOf(
        ZarzSession.APP_VERSION_DEEZER,
        ZarzSession.APP_VERSION_QOBUZ,
        ZarzSession.APP_VERSION_TIDAL,
        ZarzSession.APP_VERSION_AMAZON
    )

    fun label(version: String): String = when (version) {
        ZarzSession.APP_VERSION_DEEZER -> "Deezer"
        ZarzSession.APP_VERSION_QOBUZ -> "Qobuz"
        ZarzSession.APP_VERSION_TIDAL -> "Tidal"
        ZarzSession.APP_VERSION_AMAZON -> "Amazon"
        else -> version.substringBefore("@")
    }

    fun isUsable(version: String): Boolean = session(version).record.isUsable()

    fun anyUsable(): Boolean = versions.any { isUsable(it) }

    /** one line per provider for the settings screen */
    fun statusText(version: String): String {
        val record = session(version).record
        return when {
            record.isUsable() ->
                "${label(version)}: ✓ active (expires ${record.expiresAt.take(19).replace("T", " ")})"
            record.sessionId.isNotBlank() -> "${label(version)}: ✗ expired"
            else -> "${label(version)}: ○ not bootstrapped"
        }
    }
}
