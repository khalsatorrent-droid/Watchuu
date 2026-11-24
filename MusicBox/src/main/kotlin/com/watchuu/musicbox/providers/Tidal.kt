package com.watchuu.musicbox.providers

import com.watchuu.musicbox.core.Album
import com.watchuu.musicbox.core.Artist
import com.watchuu.musicbox.core.Creds
import com.watchuu.musicbox.core.Diag
import com.watchuu.musicbox.core.Hit
import com.watchuu.musicbox.core.Http
import com.watchuu.musicbox.core.Links
import com.watchuu.musicbox.core.MusicProvider
import com.watchuu.musicbox.core.Stream
import com.watchuu.musicbox.core.Track
import com.watchuu.musicbox.core.Zarz
import com.watchuu.musicbox.core.array
import com.watchuu.musicbox.core.jsonOrNull
import com.watchuu.musicbox.core.objects
import com.watchuu.musicbox.core.optIntOrNull
import com.watchuu.musicbox.core.optLongOrNull
import com.watchuu.musicbox.core.optStringOrNull
import org.json.JSONObject
import java.net.URLEncoder

/**
 * TIDAL, ported from Mornye-Extension sources/tidal-web/index.js
 *  - metadata   https://tidal.com/v1 (CONFIG.apiBaseURL) with countryCode/locale/deviceType
 *  - auth       public web token (x-tidal-token) for tracks/albums/artists, and the
 *               signed-out web session (client_credentials grant) for search,
 *               exactly as webAccessToken()/getWithWebSession() do it
 *  - images     https://resources.tidal.com/images/<id with - -> />/<size>.jpg  (imageURL)
 *  - download   signed POST /dl/tid {id, quality}, ticket("tid","track",trackId)
 *               manifest is base64 JSON with urls[0] (parseManifestText "direct")
 */
object Tidal : MusicProvider {
    override val id = "tidal"
    override val label = "TIDAL"
    override val canCatalogue = true
    override val canStream = true

    private const val APP_VERSION = "tidal-web@1.2.5"
    private const val API = "https://tidal.com/v1"
    private const val RESOURCES = "https://resources.tidal.com"
    private const val AUTH_URL = "https://auth.tidal.com/v1/oauth2/token"
    private const val DOWNLOAD_PATH = "/dl/tid"

    // CONFIG.webClientID / CONFIG.webClientKey
    private const val WEB_CLIENT_ID = "txNoH4kkV41MfH25"
    private const val WEB_CLIENT_KEY = "dQjy0MinCEvxi1O4UmxvxWnDjt4cgHBPw8ll6nYBk98="
    // CONFIG.publicTokens
    private val PUBLIC_TOKENS = listOf("CzET4vdadNUFQ5JU", "49YxDN9a2aFV6RTG")

    private fun setting(key: String, fallback: String): String =
        (Creds.get(key, fallback) ?: fallback).trim()

    private fun publicToken(): String = setting("tidal.publicToken", PUBLIC_TOKENS[0])
    private fun countryCode(): String = setting("tidal.countryCode", "US").uppercase()
    private fun locale(): String = setting("tidal.locale", "en_US")
    private fun deviceType(): String = setting("tidal.deviceType", "BROWSER")

    override fun configured(): Boolean = true

    override fun statusDetail(): String {
        val session = Zarz.statusLine(APP_VERSION)
        return if (session == "not authorized") "metadata ready · downloads need ZARZ" else "metadata ready · $session"
    }

    // ------------------------------------------------------------------ helpers

    private fun imageUrl(imageId: String?, size: String): String? {
        if (imageId.isNullOrBlank()) return null
        return "$RESOURCES/images/${imageId.replace("-", "/")}/$size.jpg"
    }

    /** tidalTrackTitle(): keep the version out of the title when it is already in it. */
    private fun titleWithVersion(title: String?, version: String?): String {
        val t = (title ?: "").trim()
        val v = (version ?: "").trim()
        if (t.isEmpty() || v.isEmpty()) return t
        val lt = t.lowercase()
        val lv = v.lowercase()
        if (lt.contains("($lv)") || lt.contains("[$lv]") || lt.contains(" - $lv") || lt == lv) return t
        return "$t ($v)"
    }

    private fun metadataUrl(path: String, params: List<Pair<String, String>>): String {
        val query = StringBuilder()
        query.append("countryCode=").append(enc(countryCode()))
        query.append("&locale=").append(enc(locale()))
        query.append("&deviceType=").append(enc(deviceType()))
        for ((k, v) in params) {
            if (v.isEmpty()) continue
            query.append('&').append(enc(k)).append('=').append(enc(v))
        }
        return "$API/$path?$query"
    }

    private fun enc(v: String): String = URLEncoder.encode(v, "UTF-8")

    // ------------------------------------------------------------------ auth

    private val tokenLock = Any()

    private fun webToken(renew: Boolean): String? = synchronized(tokenLock) { webTokenLocked(renew) }

    private fun webTokenLocked(renew: Boolean): String? {
        if (!renew) {
            val cached = Creds.get("tidal.webToken")
            val expiry = Creds.get("tidal.webTokenExpiry")?.toLongOrNull() ?: 0L
            if (!cached.isNullOrBlank() && System.currentTimeMillis() < expiry) return cached
        }
        // requestWebGrant(): client_credentials with the web app's client.
        val form = "client_id=${enc(WEB_CLIENT_ID)}&client_secret=${enc(WEB_CLIENT_KEY)}&grant_type=client_credentials"
        val res = Http.postForm(
            AUTH_URL, form,
            mapOf("Accept" to "application/json", "User-Agent" to Http.userAgent)
        )
        if (!res.ok) {
            Diag.log("tidal", "web token refused: HTTP ${res.code}")
            return null
        }
        val body = jsonOrNull(res.body) ?: return null
        val token = body.optStringOrNull("access_token") ?: return null
        val lifetime = body.optLongOrNull("expires_in") ?: 3600L
        // keepWebToken(): renewed a minute early.
        Creds.set("tidal.webToken", token)
        Creds.set("tidal.webTokenExpiry", (System.currentTimeMillis() + (lifetime - 60).coerceAtLeast(60) * 1000).toString())
        Diag.log("tidal", "web session token acquired")
        return token
    }

    /**
     * getJSON(): the public tokens are tried first, then the web session, matching the
     * order the extension uses now that search only answers the web session.
     */
    private fun getJson(url: String, preferWebSession: Boolean): JSONObject? {
        if (preferWebSession) {
            webToken(false)?.let { token ->
                val res = Http.get(url, mapOf("Accept" to "application/json", "Authorization" to "Bearer $token"))
                if (res.ok) jsonOrNull(res.body)?.let { return it }
                Diag.log("tidal", "web session refused ${res.code}, falling back to public tokens")
            }
        }
        val tokens = LinkedHashSet<String>()
        tokens.add(publicToken())
        tokens.addAll(PUBLIC_TOKENS)
        for (token in tokens) {
            val res = Http.get(url, mapOf("Accept" to "application/json", "x-tidal-token" to token))
            if (res.ok) return jsonOrNull(res.body)
            if (res.code != 401) {
                Diag.log("tidal", "HTTP ${res.code} for $url")
                return null
            }
        }
        // Every public token refused: ask the web session, which is then used first.
        val token = webToken(true) ?: return null
        val res = Http.get(url, mapOf("Accept" to "application/json", "Authorization" to "Bearer $token"))
        return if (res.ok) jsonOrNull(res.body) else null
    }

    // ------------------------------------------------------------------ mapping

    private fun trackOf(item: JSONObject): Track? {
        val trackId = item.optStringOrNull("id") ?: return null
        val album = item.optJSONObject("album")
        val artists = item.optJSONArray("artists")?.objects()?.mapNotNull { it.optStringOrNull("name") } ?: emptyList()
        val release = album?.optStringOrNull("releaseDate") ?: item.optStringOrNull("releaseDate")
        return Track(
            provider = id,
            id = trackId,
            title = titleWithVersion(item.optStringOrNull("title"), item.optStringOrNull("version")),
            artists = artists,
            albumName = titleWithVersion(album?.optStringOrNull("title"), album?.optStringOrNull("version")),
            albumId = album?.optStringOrNull("id"),
            cover = imageUrl(album?.optStringOrNull("cover"), "1280x1280"),
            durationMs = (item.optLongOrNull("duration") ?: 0L).takeIf { it > 0 }?.times(1000),
            isrc = item.optStringOrNull("isrc"),
            trackNumber = item.optIntOrNull("trackNumber"),
            discNumber = item.optIntOrNull("volumeNumber"),
            year = release?.take(4)?.toIntOrNull(),
            explicit = item.optBoolean("explicit", false),
            webUrl = "https://tidal.com/browse/track/$trackId",
        )
    }

    private fun albumOf(item: JSONObject): Album? {
        val albumId = item.optStringOrNull("id") ?: return null
        val artistNames = item.optJSONArray("artists")?.objects()?.mapNotNull { it.optStringOrNull("name") }
            ?: listOfNotNull(item.optJSONObject("artist")?.optStringOrNull("name"))
        return Album(
            provider = id,
            id = albumId,
            title = titleWithVersion(item.optStringOrNull("title"), item.optStringOrNull("version")),
            artists = artistNames,
            cover = imageUrl(item.optStringOrNull("cover"), "1280x1280"),
            year = (item.optStringOrNull("releaseDate") ?: "").take(4).toIntOrNull(),
            trackCount = item.optIntOrNull("numberOfTracks"),
            albumType = item.optStringOrNull("type") ?: "album",
            label = item.optStringOrNull("label"),
            webUrl = "https://tidal.com/browse/album/$albumId",
        )
    }

    // ------------------------------------------------------------------ catalogue

    override suspend fun search(query: String, limit: Int): List<Hit> {
        val out = ArrayList<Hit>()
        // searchOne(): tracks, then artists and albums.
        val tracks = getJson(metadataUrl("search/tracks", listOf("query" to query, "limit" to limit.toString(), "offset" to "0")), true)
        tracks?.array("items")?.objects()?.forEach { item ->
            trackOf(item)?.let { t ->
                out.add(Hit(Hit.Kind.TRACK, t.title, t.artistLine, t.cover, Links.track(id, t.id), track = t))
            }
        }
        val artists = getJson(metadataUrl("search/artists", listOf("query" to query, "limit" to "5", "offset" to "0")), true)
        artists?.array("items")?.objects()?.forEach { item ->
            val artistId = item.optStringOrNull("id") ?: return@forEach
            val artist = Artist(
                provider = id, id = artistId,
                name = item.optStringOrNull("name") ?: "",
                image = imageUrl(item.optStringOrNull("picture"), "750x750"),
            )
            out.add(Hit(Hit.Kind.ARTIST, artist.name, "Artist", artist.image, Links.artist(id, artist.id), artist = artist))
        }
        val albums = getJson(metadataUrl("search/albums", listOf("query" to query, "limit" to "5", "offset" to "0")), true)
        albums?.array("items")?.objects()?.forEach { item ->
            albumOf(item)?.let { a ->
                out.add(Hit(Hit.Kind.ALBUM, a.title, a.artists.joinToString(", "), a.cover, Links.album(id, a.id), album = a))
            }
        }
        return out
    }

    override suspend fun track(id: String): Track? {
        val body = getJson(metadataUrl("tracks/${enc(id)}", emptyList()), false) ?: return null
        return trackOf(body)
    }

    override suspend fun album(id: String): Album? {
        val body = getJson(metadataUrl("albums/${enc(id)}", emptyList()), false) ?: return null
        val album = albumOf(body) ?: return null
        // The API answers HTTP 400 above 100 items per page, so long albums are paged.
        val wrappers = ArrayList<JSONObject>()
        var offset = 0
        while (offset < 500) {
            val page = getJson(metadataUrl("albums/${enc(id)}/items", listOf("limit" to "100", "offset" to offset.toString())), false)
                ?: break
            val got = page.array("items")?.objects() ?: emptyList()
            wrappers.addAll(got)
            val total = page.optIntOrNull("totalNumberOfItems") ?: 0
            offset += got.size
            if (got.isEmpty() || offset >= total) break
        }
        val tracks = ArrayList<Track>()
        wrappers.forEach { wrapper ->
            val item = wrapper.optJSONObject("item") ?: wrapper
            // Album items carry no album object; attach the one we already have.
            val withAlbum = JSONObject(item.toString())
            if (!withAlbum.has("album")) withAlbum.put("album", body)
            trackOf(withAlbum)?.let { tracks.add(it) }
        }
        return album.copy(tracks = tracks)
    }

    override suspend fun artist(id: String): Artist? {
        val body = getJson(metadataUrl("artists/${enc(id)}", emptyList()), false) ?: return null
        val artist = Artist(
            provider = this.id, id = id,
            name = body.optStringOrNull("name") ?: "",
            image = imageUrl(body.optStringOrNull("picture"), "750x750"),
        )
        val top = ArrayList<Track>()
        getJson(metadataUrl("artists/${enc(id)}/tracks", listOf("limit" to "20", "offset" to "0")), false)
            ?.array("items")?.objects()?.forEach { item -> trackOf(item)?.let { top.add(it) } }
        val albums = ArrayList<Album>()
        getJson(metadataUrl("artists/${enc(id)}/albums", listOf("limit" to "50", "offset" to "0")), false)
            ?.array("items")?.objects()?.forEach { item -> albumOf(item)?.let { albums.add(it) } }
        return artist.copy(topTracks = top, albums = albums)
    }

    // ------------------------------------------------------------------ streams

    /** normalizeDownloadQuality(): the ids the gateway accepts. */
    private fun quality(quality: String): String = when (quality.uppercase()) {
        "", "LOSSLESS", "FLAC" -> "LOSSLESS"
        "HI_RES", "HIRES", "HI_RES_LOSSLESS", "MASTER" -> "HI_RES_LOSSLESS"
        "DOLBY", "ATMOS", "DOLBY ATMOS", "EAC3", "EC3", "EAC3_JOC", "DOLBY_ATMOS" -> "DOLBY_ATMOS"
        "HIGH", "LOW" -> quality.uppercase()
        // The shared quality setting defaults to JioSaavn's "AAC_320", which TIDAL rejects.
        else -> "LOSSLESS"
    }

    override suspend fun streams(track: Track, quality: String): List<Stream> {
        val requested = quality(quality)
        val ticket = Zarz.ticket(APP_VERSION, "tid", "track", track.id)
        val body = JSONObject().put("id", track.id).put("quality", requested)
        val res = Zarz.signedFetch(APP_VERSION, "POST", DOWNLOAD_PATH, body.toString(), mapOf("X-Zarz-Ticket" to ticket))
        val payload = jsonOrNull(res.body) ?: return emptyList()
        val data = payload.optJSONObject("data") ?: return emptyList()
        if (data.optStringOrNull("assetPresentation")?.uppercase() == "PREVIEW") {
            Diag.log("tidal", "gateway returned a preview asset")
            return emptyList()
        }
        val manifest = data.optStringOrNull("manifest") ?: return emptyList()
        val decoded = runCatching {
            String(android.util.Base64.decode(manifest, android.util.Base64.DEFAULT), Charsets.UTF_8)
        }.getOrNull() ?: return emptyList()
        // parseManifestText(): a JSON manifest carries the direct URL; an MPD does not,
        // and a DASH MPD cannot be handed to the player as inline text.
        if (!decoded.trimStart().startsWith("{")) {
            Diag.log("tidal", "manifest is DASH MPD (segment based); not playable without a segment downloader")
            return emptyList()
        }
        val manifestJson = jsonOrNull(decoded) ?: return emptyList()
        val url = manifestJson.optJSONArray("urls")?.optString(0)?.takeIf { it.isNotBlank() } ?: return emptyList()
        val mime = manifestJson.optStringOrNull("mimeType") ?: "audio/flac"
        val label = buildString {
            append(data.optStringOrNull("audioQuality") ?: requested)
            val depth = data.optIntOrNull("bitDepth") ?: 0
            val rate = data.optIntOrNull("sampleRate") ?: 0
            if (depth > 0) append(" · ${depth}bit")
            if (rate > 0) append(" · ${rate / 1000}kHz")
        }
        return listOf(Stream(url, label, mime, provider = id))
    }
}
