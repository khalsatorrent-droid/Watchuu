package com.watchuu.musicbox

/**
 * Singleton holder for the shared ZarzSession.
 *
 * FIX: Both MusicBoxProvider and MusicBoxSettings were creating their own
 *      ZarzSession() instances, so a session bootstrapped in Settings was
 *      never visible to the Provider (and vice-versa). This singleton ensures
 *      both share the same in-memory state; persistence is still via
 *      SharedPreferences through loadState/saveState.
 */
object ZarzSessionManager {
    val session = ZarzSession()

    @Volatile
    private var loaded = false

    /**
     * Load session state from the persisted JSON string.
     * Idempotent — only loads once per process unless forceReload is true.
     */
    fun load(json: String?, forceReload: Boolean = false) {
        if (loaded && !forceReload) return
        session.loadState(json)
        loaded = true
    }

    /** Mark as not loaded so the next load() call will re-read from disk. */
    fun invalidate() {
        loaded = false
    }
}
