package com.watchuu.musicbox

import android.content.Context
import com.lagradost.cloudstream3.CloudStreamApp

/**
 * The one ZARZ session, and the only place it is loaded from or written to.
 *
 * Why this exists: playback and the settings screen used to build their own ZarzSession instances,
 * so a session bootstrapped in settings was invisible to playback and every signed resolver call
 * went out unauthenticated. Sharing one object fixes that, but sharing the object is not enough on
 * its own - the load path matters too:
 *
 *   - a load must never discard a usable in-memory session. The provider calls load on every
 *     search, page and play, and the old code called loadState(null) whenever the disk read came
 *     back empty, which wiped a session that settings had just bootstrapped.
 *   - a save must always go to the same key both sides read.
 *
 * So the rules are simple: memory wins while it is usable, disk fills the gaps, and everything is
 * persisted through here.
 */
object ZarzSessionManager {
    private const val PREFS = "musicbox_prefs"
    private const val KEY_SESSION = "musicbox_zarz_session"

    val session = ZarzSession()

    private fun prefs(context: Context?) =
        context?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun contextOrNull(): Context? = CloudStreamApp.context

    /**
     * Fills the session from disk. Never overwrites a session that is still usable, and never
     * clears one that only exists in memory.
     */
    fun loadFromPrefs(context: Context? = contextOrNull(), force: Boolean = false) {
        val stored = prefs(context)?.getString(KEY_SESSION, null)

        if (!force) {
            // a live session beats an empty or stale disk
            if (session.record.isUsable()) return
            if (stored.isNullOrBlank() && session.record.sessionId.isNotBlank()) return
        }
        session.loadState(stored)
    }

    /** Writes the current session so settings and playback see the same thing next launch. */
    fun persist(context: Context? = contextOrNull()) {
        val p = prefs(context) ?: return
        runCatching { p.edit().putString(KEY_SESSION, session.saveState()).apply() }
    }

    fun hasSession(): Boolean = session.record.sessionId.isNotBlank()

    fun isUsable(): Boolean = session.record.isUsable()

    /** one line for the settings screen */
    fun statusText(): String = when {
        session.record.isUsable() ->
            "✓ Session active (expires: ${session.record.expiresAt.take(19).replace("T", " ")})"
        session.record.sessionId.isNotBlank() -> "✗ Session expired"
        else -> "○ No session (bootstrap required for ZARZ sources)"
    }
}
