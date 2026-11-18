package com.watchuu.musicbox

import android.content.Context
import com.lagradost.cloudstream3.CloudStreamApp
import java.util.concurrent.ConcurrentHashMap

/**
 * Four ZARZ sessions behind a single-bootstrap facade.
 *
 * The server scopes each session to `namespace + base_url + app_version + platform`, so a session
 * minted as `deezer@1.2.0` cannot sign requests for endpoints that expect `tidal-web@1.1.0`.
 * Internally four sessions are therefore kept — one per provider — but the user experience is a
 * single bootstrap: verify once and [activateOthers] cascades the same grant to every remaining
 * provider.
 */
object ZarzSessionManager {
    private const val PREFS = "musicbox_prefs"
    private const val INSTALL_KEY = "musicbox_zarz_install_id"
    private const val KEY_PREFIX = "musicbox_zarz_session"
    private const val TAG = "ZarzMgr"

    val versions = listOf(
        ZarzSession.APP_VERSION_DEEZER,
        ZarzSession.APP_VERSION_QOBUZ,
        ZarzSession.APP_VERSION_TIDAL,
        ZarzSession.APP_VERSION_AMAZON,
    )

    private val sessions = ConcurrentHashMap<String, ZarzSession>()

    private fun key(version: String): String =
        KEY_PREFIX + "_" + version.replace("@", "_").replace(".", "_").replace("-", "_")

    private fun prefs(context: Context?) =
        context?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun contextOrNull(): Context? = CloudStreamApp.context

    /** session for a specific provider app_version */
    fun session(version: String = ZarzSession.APP_VERSION_DEEZER): ZarzSession =
        sessions.getOrPut(version) { ZarzSession(appVersion = version) }

    /**
     * Load all provider sessions from disk.
     *
     * Also handles migration from the broken v64 unified key: if one exists, its session is loaded
     * into the deezer slot, persisted there, and the unified key is removed.
     */
    fun loadFromPrefs(context: Context? = contextOrNull()) {
        val p = prefs(context) ?: return

        // migrate from v64's unified key if present
        val unified = p.getString("musicbox_zarz_session_unified", null)
        if (!unified.isNullOrBlank()) {
            val s = session(ZarzSession.APP_VERSION_DEEZER)
            s.loadState(unified)
            if (s.record.installId.isBlank()) sharedInstallId(context)?.let { s.record.installId = it }
            runCatching {
                p.edit()
                    .putString(key(ZarzSession.APP_VERSION_DEEZER), s.saveState())
                    .remove("musicbox_zarz_session_unified")
                    .apply()
            }
            MusicBoxLog.d(TAG, "Migrated unified session back to per-version key")
        }

        for (v in versions) {
            val s = session(v)
            val stored = p.getString(key(v), null)
            if (!stored.isNullOrBlank()) {
                s.loadState(stored)
            }
            if (s.record.installId.isBlank()) {
                sharedInstallId(context)?.let { s.record.installId = it }
            }
        }
    }

    /** backward-compat overload — version is ignored, all sessions are loaded */
    fun loadFromPrefs(
        @Suppress("UNUSED_PARAMETER") version: String,
        context: Context? = contextOrNull(),
        @Suppress("UNUSED_PARAMETER") force: Boolean = false,
    ) {
        loadFromPrefs(context)
    }

    /**
     * One install id shared across all providers so that one verification can cover all sessions.
     * Generated on first use, then persisted.
     */
    @Synchronized
    fun sharedInstallId(context: Context? = contextOrNull()): String? {
        val p = prefs(context) ?: return null
        p.getString(INSTALL_KEY, null)?.takeIf { it.isNotBlank() }?.let { return it }
        // try to pick up an id from an existing session
        for (v in versions) {
            val id = sessions[v]?.record?.installId?.takeIf { it.isNotBlank() }
            if (id != null) {
                runCatching { p.edit().putString(INSTALL_KEY, id).apply() }
                return id
            }
        }
        return null
    }

    /** writes all sessions to disk */
    fun persist(context: Context? = contextOrNull()) {
        val p = prefs(context) ?: return
        val editor = p.edit()
        for (v in versions) {
            sessions[v]?.let { editor.putString(key(v), it.saveState()) }
        }
        editor.apply()
    }

    /** backward-compat overload — version is ignored, all sessions are saved */
    fun persist(@Suppress("UNUSED_PARAMETER") version: String, context: Context? = contextOrNull()) {
        persist(context)
    }

    fun isUsable(version: String = ZarzSession.APP_VERSION_DEEZER): Boolean =
        sessions[version]?.record?.isUsable() == true

    fun anyUsable(): Boolean = versions.any { isUsable(it) }

    /**
     * After one provider is verified, cascade the same install id and grant to bootstrap the
     * remaining providers. The server recognises the shared install and usually hands out sessions
     * directly without another challenge.
     */
    suspend fun activateOthers(
        primary: String,
        grant: String?,
        context: Context? = contextOrNull(),
    ): List<String> {
        val install = session(primary).record.installId
        val activated = mutableListOf<String>()
        for (v in versions) {
            if (v == primary) continue
            val s = session(v)
            if (s.record.isUsable()) { activated.add(v); continue }
            s.record.installId = install
            try {
                val url = s.bootstrap()
                if (url.isBlank() && s.record.isUsable()) {
                    activated.add(v)
                    MusicBoxLog.d(TAG, "activateOthers: $v bootstrapped directly")
                } else if (!grant.isNullOrBlank()) {
                    s.exchange(grant)
                    if (s.record.isUsable()) {
                        activated.add(v)
                        MusicBoxLog.d(TAG, "activateOthers: $v exchanged with grant")
                    }
                }
            } catch (e: Exception) {
                MusicBoxLog.w(TAG, "activateOthers: $v failed: ${e.message}")
            }
        }
        persist(context)
        return activated
    }

    /** consolidated status for the settings screen */
    fun statusText(): String {
        val usableCount = versions.count { isUsable(it) }
        val total = versions.size
        return when {
            usableCount == total -> {
                val exp = session(versions.first()).record.expiresAt.take(19).replace("T", " ")
                "ZARZ: ✓ active ($usableCount/$total providers, expires $exp)"
            }
            usableCount > 0 -> {
                val active = versions.filter { isUsable(it) }.joinToString(", ") {
                    it.substringBefore("@")
                }
                val exp = versions.firstOrNull { isUsable(it) }
                    ?.let { session(it).record.expiresAt.take(19).replace("T", " ") } ?: ""
                "ZARZ: ⚠ partial ($usableCount/$total — $active, expires $exp)"
            }
            versions.any { sessions[it]?.record?.sessionId?.isNotBlank() == true } -> "ZARZ: ✗ expired"
            else -> "ZARZ: ○ not bootstrapped"
        }
    }

    /** backward-compat overload — version is ignored */
    fun statusText(@Suppress("UNUSED_PARAMETER") version: String): String = statusText()

    fun label(@Suppress("UNUSED_PARAMETER") version: String): String = "ZARZ"

    /** clear all sessions */
    fun clearAll(context: Context? = contextOrNull()) {
        for (v in versions) session(v).record.clear()
        persist(context)
    }
}
