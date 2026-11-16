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
    private const val INSTALL_KEY = "musicbox_zarz_install_id"

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
        // a provider that has no session of its own yet joins the shared install, so that one
        // verification can cover all of them
        if (session.record.sessionId.isBlank()) sharedInstallId(context)?.let { session.record.installId = it }
    }

    /**
     * One install id for every provider. It is taken from an already working session if there is
     * one (that install is the one the server has verified), otherwise it is made once and kept.
     */
    @Synchronized
    fun sharedInstallId(context: Context? = contextOrNull()): String? {
        val p = prefs(context) ?: return null
        p.getString(INSTALL_KEY, null)?.takeIf { it.isNotBlank() }?.let { return it }
        val fromSession = versions.map { session(it).record }.firstOrNull { it.isUsable() }?.installId
        val id = fromSession ?: versions.map { session(it).record.installId }.firstOrNull { it.isNotBlank() }
            ?: return null
        runCatching { p.edit().putString(INSTALL_KEY, id).apply() }
        return id
    }

    /**
     * After one provider has been verified, brings the others up on the same install: first by asking
     * for a session directly (the server already knows this install), then by trying the grant just
     * earned. Returns the providers that still have no session, so the caller can say so.
     */
    suspend fun activateOthers(primary: String, grant: String?, context: Context? = contextOrNull()): List<String> {
        val install = session(primary).record.installId
        val missing = ArrayList<String>()
        for (v in versions) {
            if (v == primary) continue
            val s = session(v)
            if (s.record.isUsable()) continue
            s.record.installId = install
            var ok = false
            try {
                ok = s.bootstrap().isBlank() && s.record.isUsable()
                MusicBoxLog.d("ZarzSessionManager", "${label(v)}: direct bootstrap on shared install -> $ok")
            } catch (e: Exception) {
                MusicBoxLog.d("ZarzSessionManager", "${label(v)}: bootstrap failed: ${e.message}")
            }
            if (!ok && grant != null) {
                try {
                    s.exchange(grant)
                    ok = s.record.isUsable()
                    MusicBoxLog.d("ZarzSessionManager", "${label(v)}: shared grant exchange -> $ok")
                } catch (e: Exception) {
                    MusicBoxLog.d("ZarzSessionManager", "${label(v)}: shared grant refused: ${e.message}")
                }
            }
            persist(v, context)
            if (!ok) missing.add(v)
        }
        return missing
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
