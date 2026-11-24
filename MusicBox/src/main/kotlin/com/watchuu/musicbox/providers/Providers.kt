package com.watchuu.musicbox.providers

import com.watchuu.musicbox.core.AuthSpecs
import com.watchuu.musicbox.core.Creds
import com.watchuu.musicbox.core.MusicProvider

/**
 * Registry and routing.
 *
 * ZARZ sessions are rate limited per install, so the extension never authorizes or spends
 * more than one of them: [downloadSourceId] is the single signed-session source the user
 * picked, and every other ZARZ-backed provider is left out of the stream chain entirely.
 * Providers that need no session (JioSaavn, SoundCloud, YouTube Music, Jellyfin) cost
 * nothing and stay available.
 */
object Providers {
    val all: List<MusicProvider> = listOf(
        JioSaavn,
        Deezer,
        Tidal,
        Qobuz,
        Amazon,
        SoundCloud,
        YtMusic,
        Beatport,
        Jellyfin,
    )

    fun byId(id: String): MusicProvider? = all.firstOrNull { it.id == id }

    fun isZarz(id: String): Boolean = AuthSpecs.isZarzBacked(id)

    /** Providers that spend a signed session, i.e. the ones the user may pick one of. */
    fun zarzCandidates(): List<MusicProvider> = all.filter { isZarz(it.id) }

    fun catalogueCandidates(): List<MusicProvider> = all.filter { it.canCatalogue }

    fun enabled(provider: MusicProvider): Boolean =
        Creds.get("musicbox.stream.${provider.id}") != "0"

    fun setEnabled(provider: MusicProvider, value: Boolean) =
        Creds.set("musicbox.stream.${provider.id}", if (value) "1" else "0")

    // ------------------------------------------------------------------ chosen sources

    /** The catalogue the user picked; search, albums and artists come from it alone. */
    fun catalogueId(): String {
        val stored = Creds.get("musicbox.catalogue")
        if (stored != null && byId(stored)?.canCatalogue == true) return stored
        return "jiosaavn"
    }

    fun setCatalogueId(id: String) {
        if (byId(id)?.canCatalogue == true) Creds.set("musicbox.catalogue", id)
    }

    /**
     * Qobuz and Beatport serve their catalogue through the signed session as well, so choosing
     * one of them as the catalogue means that same session has to be the authorized one.
     */
    fun catalogueNeedsSession(id: String): Boolean = id == "qobuz" || id == "beatport"

    /** The one signed-session download source. Null when the user has not picked one. */
    fun downloadSourceId(): String? {
        val stored = Creds.get("musicbox.download.source") ?: return null
        return if (isZarz(stored) && byId(stored) != null) stored else null
    }

    fun setDownloadSourceId(id: String?) {
        if (id == null) {
            Creds.set("musicbox.download.source", null)
            return
        }
        if (isZarz(id) && byId(id) != null) Creds.set("musicbox.download.source", id)
    }

    /**
     * Catalogue providers for a search: exactly the chosen one. Querying the others would
     * spend their requests without the user asking for it.
     */
    fun catalogue(primary: String): List<MusicProvider> =
        listOfNotNull(byId(primary)).filter { it.canCatalogue }

    /**
     * Stream chain for a track that came from [ownerId]:
     *  1. the chosen download source - the user picked it for its quality, and a free owner
     *     (JioSaavn) answering first used to end the search before it was ever asked
     *  2. the owning provider, when it is allowed to be used
     *  3. the session-free providers the user left enabled
     * A ZARZ-backed provider that is not the chosen one never appears here.
     */
    fun streamOrder(ownerId: String): List<MusicProvider> {
        val chosen = downloadSourceId()
        val usable = all.filter { provider ->
            provider.canStream && enabled(provider) && (!isZarz(provider.id) || provider.id == chosen)
        }
        val ordered = ArrayList<MusicProvider>()
        chosen?.let { id -> usable.firstOrNull { it.id == id }?.let { ordered.add(it) } }
        usable.firstOrNull { it.id == ownerId && ordered.none { o -> o.id == it.id } }?.let { ordered.add(it) }
        for (provider in usable) {
            if (ordered.none { it.id == provider.id }) ordered.add(provider)
        }
        return ordered
    }
}
