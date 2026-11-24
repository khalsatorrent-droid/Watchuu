package com.watchuu.musicbox.core

/**
 * Every provider port implements this. One provider can serve catalogue data
 * (search / album / artist) and/or streams; the settings screen shows which.
 */
interface MusicProvider {
    val id: String
    val label: String
    val canCatalogue: Boolean
    val canStream: Boolean

    /** True when the provider has everything it needs to answer. */
    fun configured(): Boolean = true

    /** Human readable authorization state for the settings screen. */
    fun statusDetail(): String = if (configured()) "ready" else "not authorized"

    suspend fun search(query: String, limit: Int = 25): List<Hit>

    /** Songs for a home row. Providers whose search costs several calls answer with one. */
    suspend fun homeTracks(query: String, limit: Int = 20): List<Hit> = search(query, limit)

    suspend fun track(id: String): Track? = null
    suspend fun album(id: String): Album? = null
    suspend fun artist(id: String): Artist? = null

    /** Audio sources for a track, best first. */
    suspend fun streams(track: Track, quality: String): List<Stream> = emptyList()
}

/** Deep-link used between CloudStream load()/loadLinks() and the providers. */
object Links {
    fun track(provider: String, id: String) = "musicbox://$provider/track/$id"
    fun album(provider: String, id: String) = "musicbox://$provider/album/$id"
    fun artist(provider: String, id: String) = "musicbox://$provider/artist/$id"

    class Parsed(val provider: String, val kind: String, val id: String)

    /**
     * A card link can carry the item it was built from after this marker, so opening the card
     * never depends on a second provider lookup or on an in-memory cache that the app may have
     * dropped (plugin reload, home row restored from an earlier run).
     */
    private const val META = "#mb="
    private const val SCHEME = "musicbox://"

    /**
     * CloudStream passes every card link through MainAPI.fixUrl() before load(), and a link that
     * does not start with "http" comes back as "<mainUrl>/musicbox://…". Everything before the
     * scheme is dropped here so that rewritten link still resolves.
     */
    fun canonical(url: String): String {
        val idx = url.indexOf(SCHEME)
        return if (idx > 0) url.substring(idx) else url
    }

    fun base(url: String): String = canonical(url).substringBefore(META)

    fun withMeta(url: String, meta: String): String {
        val encoded = android.util.Base64.encodeToString(
            meta.toByteArray(Charsets.UTF_8),
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
        )
        return base(url) + META + encoded
    }

    fun meta(url: String): String? {
        val idx = url.indexOf(META)
        if (idx < 0) return null
        return runCatching {
            String(
                android.util.Base64.decode(url.substring(idx + META.length), android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING),
                Charsets.UTF_8
            )
        }.getOrNull()
    }

    fun parse(url: String): Parsed? {
        val clean = base(url)
        if (!clean.startsWith(SCHEME)) return null
        val rest = clean.removePrefix(SCHEME).trimEnd('/')
        val parts = rest.split("/")
        if (parts.size < 3) return null
        val id = parts.drop(2).joinToString("/")
        if (id.isBlank()) return null
        return Parsed(parts[0], parts[1], id)
    }
}
