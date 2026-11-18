package com.watchuu.musicbox

import android.content.Context
import com.lagradost.cloudstream3.CloudStreamApp

/**
 * Single ZARZ session shared by every provider.
 *
 * Previously each provider (Deezer, Qobuz, Tidal, Amazon) had its own session because the server
 * scoped them by app_version. This has been consolidated: one bootstrap, one verification, one
 * session. The resolver endpoints (`/dl/dzr`, `/dl/tid`, `/dl/qbz`, `/dl/amazeamazeamaze`) route
 * by path; the session signs them all.
 */
object ZarzSessionManager {
    private const val PREFS = "musicbox_prefs"
    private const val KEY = "musicbox_zarz_session_unified"
    private const val INSTALL_KEY = "musicbox_zarz_install_id"
    private const val TAG = "ZarzMgr"

    // legacy keys used before the single-session migration
    private val LEGACY_VERSIONS = listOf("deezer_1_2_0", "qobuz_web_1_1_0", "tidal_web_1_1_0", "amzn_2_3_12")
    private fun legacyKey(slug: String) = "musicbox_zarz_session_$slug"

    @Volatile
    private var _session: ZarzSession? = null

    private fun prefs(context: Context?) =
        context?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun contextOrNull(): Context? = CloudStreamApp.context

    /** the single ZARZ session */
    fun session(): ZarzSession {
        _session?.let { return it }
        return ZarzSession().also { _session = it }
    }

    /** backward-compat overload - version is ignored, same session returned */
    fun session(@Suppress("UNUSED_PARAMETER") version: String): ZarzSession = session()

    /**
     * Fills the session from disk.  On first run after the migration, tries the new unified key
     * and falls back to the first usable legacy key so the user keeps their existing session.
     */
    fun loadFromPrefs(context: Context? = contextOrNull()) {
        val s = session()
        val p = prefs(context) ?: return

        // try unified key first
        val stored = p.getString(KEY, null)
        if (!stored.isNullOrBlank()) {
            s.loadState(stored)
            if (s.record.installId.isBlank()) sharedInstallId(context)?.let { s.record.installId = it }
            return
        }

        // migrate: take the first usable legacy session
        for (slug in LEGACY_VERSIONS) {
            val legacy = p.getString(legacyKey(slug), null)
            if (!legacy.isNullOrBlank()) {
                s.loadState(legacy)
                if (s.record.isUsable()) {
                    MusicBoxLog.d(TAG, "Migrated session from legacy key $slug")
                    // persist to unified key so we don't repeat this
                    persist(context)
                    break
                }
            }
        }

        if (s.record.installId.isBlank()) sharedInstallId(context)?.let { s.record.installId = it }
    }

    /** backward-compat overload - version is ignored */
    fun loadFromPrefs(@Suppress("UNUSED_PARAMETER") version: String, context: Context? = contextOrNull(), @Suppress("UNUSED_PARAMETER") force: Boolean = false) {
        loadFromPrefs(context)
    }

    /**
     * One install id shared globally. Taken from an already working session if there is one,
     * otherwise generated once and kept.
     */
    @Synchronized
    fun sharedInstallId(context: Context? = contextOrNull()): String? {
        val p = prefs(context) ?: return null
        p.getString(INSTALL_KEY, null)?.takeIf { it.isNotBlank() }?.let { return it }
        val s = _session?.record
        val id = s?.installId?.takeIf { it.isNotBlank() } ?: return null
        runCatching { p.edit().putString(INSTALL_KEY, id).apply() }
        return id
    }

    /** writes the session to disk */
    fun persist(context: Context? = contextOrNull()) {
        val p = prefs(context) ?: return
        runCatching { p.edit().putString(KEY, session().saveState()).apply() }
    }

    /** backward-compat overload - version is ignored */
    fun persist(@Suppress("UNUSED_PARAMETER") version: String, context: Context? = contextOrNull()) {
        persist(context)
    }

    fun isUsable(): Boolean = _session?.record?.isUsable() == true

    /** backward-compat overload - version is ignored */
    fun isUsable(@Suppress("UNUSED_PARAMETER") version: String): Boolean = isUsable()

    fun anyUsable(): Boolean = isUsable()

    /** single-line status for the settings screen */
    fun statusText(): String {
        val record = session().record
        return when {
            record.isUsable() ->
                "ZARZ: ✓ active (expires ${record.expiresAt.take(19).replace("T", " ")})"
            record.sessionId.isNotBlank() -> "ZARZ: ✗ expired"
            else -> "ZARZ: ○ not bootstrapped"
        }
    }

    /** backward-compat overload - version is ignored */
    fun statusText(@Suppress("UNUSED_PARAMETER") version: String): String = statusText()

    fun label(@Suppress("UNUSED_PARAMETER") version: String): String = "ZARZ"

    /** remove all legacy per-provider keys */
    fun cleanLegacyKeys(context: Context? = contextOrNull()) {
        val p = prefs(context) ?: return
        val editor = p.edit()
        LEGACY_VERSIONS.forEach { editor.remove(legacyKey(it)) }
        editor.apply()
    }
}
