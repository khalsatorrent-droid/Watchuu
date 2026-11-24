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

/**
 * Deezer, ported from Mornye-Extension sources/deezer/index.js
 *  - metadata   api.deezer.com (CONFIG.apiBaseURL), public, no credentials
 *  - covers     e-cdns-images.dzcdn.net, rewritten to coverMaxSize (1400)
 *  - download   signed POST /dl/dzr on the zarz-v2 session
 *               body {id, type:"track", platform:"deezer", url:"https://www.deezer.com/track/<id>"}
 *               header X-Zarz-Ticket = ticket("dzr", "track", url)   (resolveDownloadDescriptor)
 *               answer: direct_download_url / download_url          (resolveDescriptorDownloadURL)
 */
object Deezer : MusicProvider {
    override val id = "deezer"
    override val label = "Deezer"
    override val canCatalogue = true
    override val canStream = true

    private const val APP_VERSION = "deezer@1.3.5"
    private const val DOWNLOAD_PATH = "/dl/dzr"
    private const val WEB_BASE = "https://www.deezer.com"
    private const val COVER_MAX = 1400

    private fun api(): String = (Creds.get("deezer.apiBaseUrl", "https://api.deezer.com") ?: "https://api.deezer.com").trimEnd('/')

    override fun configured(): Boolean = true

    override fun statusDetail(): String {
        val session = Zarz.statusLine(APP_VERSION)
        return if (session == "not authorized") "metadata ready · downloads need ZARZ" else "metadata ready · $session"
    }

    private fun get(path: String): JSONObject? {
        val res = Http.get("${api()}$path", mapOf("Accept" to "application/json"))
        if (!res.ok) {
            Diag.log("deezer", "$path HTTP ${res.code}")
            return null
        }
        val body = jsonOrNull(res.body)
        if (body?.has("error") == true) {
            Diag.log("deezer", "$path error ${body.optJSONObject("error")?.optString("message")}")
            return null
        }
        return body
    }

    // ------------------------------------------------------------------ mapping

    private fun cover(album: JSONObject?): String? {
        val raw = album?.optStringOrNull("cover_xl")
            ?: album?.optStringOrNull("cover_big")
            ?: album?.optStringOrNull("cover_medium")
            ?: return null
        if (!raw.contains(".dzcdn.net/")) return raw
        return raw.replace(Regex("/(\\d+)x(\\d+)-(\\d+)-(\\d+)-(\\d+)-(\\d+)\\.jpg")) { m ->
            val w = m.groupValues[1].toIntOrNull() ?: 0
            val h = m.groupValues[2].toIntOrNull() ?: 0
            if (w >= COVER_MAX && h >= COVER_MAX) m.value
            else "/${COVER_MAX}x$COVER_MAX-${m.groupValues[3]}-${m.groupValues[4]}-${m.groupValues[5]}-${m.groupValues[6]}.jpg"
        }
    }

    private fun trackOf(item: JSONObject): Track? {
        val trackId = item.optStringOrNull("id") ?: return null
        val album = item.optJSONObject("album")
        val artist = item.optJSONObject("artist")
        val names = ArrayList<String>()
        artist?.optStringOrNull("name")?.let { names.add(it) }
        item.optJSONArray("contributors")?.objects()?.forEach { c ->
            c.optStringOrNull("name")?.let { if (!names.contains(it)) names.add(it) }
        }
        val release = item.optStringOrNull("release_date")
            ?: album?.optStringOrNull("release_date")
        return Track(
            provider = id,
            id = trackId,
            title = item.optStringOrNull("title") ?: item.optStringOrNull("title_short") ?: "",
            artists = names,
            albumName = album?.optStringOrNull("title") ?: item.optStringOrNull("album_name"),
            albumId = album?.optStringOrNull("id"),
            cover = cover(album),
            durationMs = (item.optLongOrNull("duration") ?: 0L).takeIf { it > 0 }?.times(1000),
            isrc = item.optStringOrNull("isrc"),
            trackNumber = item.optIntOrNull("track_position"),
            discNumber = item.optIntOrNull("disk_number"),
            year = release?.take(4)?.toIntOrNull(),
            explicit = item.optBoolean("explicit_lyrics", false),
            webUrl = item.optStringOrNull("link"),
        )
    }

    private fun albumOf(item: JSONObject): Album? {
        val albumId = item.optStringOrNull("id") ?: return null
        val tracks = ArrayList<Track>()
        item.optJSONObject("tracks")?.array("data")?.objects()?.forEach { t ->
            // Album track objects carry no album object of their own.
            val withAlbum = JSONObject(t.toString())
            if (!withAlbum.has("album")) withAlbum.put("album", item)
            trackOf(withAlbum)?.let { tracks.add(it) }
        }
        return Album(
            provider = id,
            id = albumId,
            title = item.optStringOrNull("title") ?: "",
            artists = listOfNotNull(item.optJSONObject("artist")?.optStringOrNull("name")),
            cover = cover(item),
            year = (item.optStringOrNull("release_date") ?: "").take(4).toIntOrNull(),
            trackCount = item.optIntOrNull("nb_tracks"),
            albumType = item.optStringOrNull("record_type"),
            label = item.optStringOrNull("label"),
            tracks = tracks,
            webUrl = item.optStringOrNull("link"),
        )
    }

    // ------------------------------------------------------------------ catalogue

    override suspend fun search(query: String, limit: Int): List<Hit> {
        val out = ArrayList<Hit>()
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")

        get("/search?q=$encoded&limit=$limit")?.array("data")?.objects()?.forEach { item ->
            trackOf(item)?.let { t ->
                out.add(Hit(Hit.Kind.TRACK, t.title, t.artistLine, t.cover, Links.track(id, t.id), track = t))
            }
        }
        get("/search/album?q=$encoded&limit=10")?.array("data")?.objects()?.forEach { item ->
            albumOf(item)?.let { a ->
                out.add(Hit(Hit.Kind.ALBUM, a.title, a.artists.joinToString(", "), a.cover, Links.album(id, a.id), album = a))
            }
        }
        get("/search/artist?q=$encoded&limit=10")?.array("data")?.objects()?.forEach { item ->
            val artistId = item.optStringOrNull("id") ?: return@forEach
            val artist = Artist(
                provider = id,
                id = artistId,
                name = item.optStringOrNull("name") ?: "",
                image = item.optStringOrNull("picture_xl") ?: item.optStringOrNull("picture_big"),
                webUrl = item.optStringOrNull("link"),
            )
            out.add(Hit(Hit.Kind.ARTIST, artist.name, "Artist", artist.image, Links.artist(id, artist.id), artist = artist))
        }
        return out
    }

    override suspend fun track(id: String): Track? = get("/track/$id")?.let { trackOf(it) }

    override suspend fun album(id: String): Album? = get("/album/$id")?.let { albumOf(it) }

    override suspend fun artist(id: String): Artist? {
        val detail = get("/artist/$id") ?: return null
        val top = get("/artist/$id/top?limit=20")
        val tracks = ArrayList<Track>()
        top?.array("data")?.objects()?.forEach { t -> trackOf(t)?.let { tracks.add(it) } }
        val albums = ArrayList<Album>()
        get("/artist/$id/albums?limit=50")?.array("data")?.objects()?.forEach { a ->
            albumOf(a)?.let { albums.add(it.copy(tracks = emptyList())) }
        }
        return Artist(
            provider = this.id,
            id = id,
            name = detail.optStringOrNull("name") ?: "",
            image = detail.optStringOrNull("picture_xl") ?: detail.optStringOrNull("picture_big"),
            albums = albums,
            topTracks = tracks,
            webUrl = detail.optStringOrNull("link"),
        )
    }

    // ------------------------------------------------------------------ streams

    override suspend fun streams(track: Track, quality: String): List<Stream> {
        val trackUrl = "$WEB_BASE/track/${track.id}"
        val ticket = Zarz.ticket(APP_VERSION, "dzr", "track", trackUrl)
        val body = JSONObject()
            .put("id", track.id)
            .put("type", "track")
            .put("platform", "deezer")
            .put("url", trackUrl)
        val res = Zarz.signedFetch(
            APP_VERSION, "POST", DOWNLOAD_PATH, body.toString(),
            mapOf("X-Zarz-Ticket" to ticket)
        )
        val payload = jsonOrNull(res.body) ?: return emptyList()
        val descriptor = payload.optJSONObject("data") ?: payload
        val direct = descriptor.optStringOrNull("direct_download_url")
        // descriptorRequiresClientDecryption(): Mornye decrypts these after download; a streaming
        // player gets Blowfish-scrambled audio, so only an unencrypted file is offered.
        val encrypted = when {
            descriptor.has("requires_client_decryption") -> descriptor.optBoolean("requires_client_decryption", false)
            descriptor.has("direct_downloadable") -> !descriptor.optBoolean("direct_downloadable", true)
            else -> descriptor.optBoolean("deezer_encrypted", false)
        }
        if (encrypted) {
            Diag.log("deezer", "gateway gave an encrypted file (needs client decryption); CloudStream cannot play it")
            return emptyList()
        }
        val url = when {
            descriptor.optBoolean("direct_downloadable", false) && direct != null -> direct
            descriptor.optStringOrNull("download_url") != null -> descriptor.optStringOrNull("download_url")!!
            direct != null -> direct
            else -> null
        } ?: run {
            Diag.log("deezer", "descriptor had no download url")
            return emptyList()
        }
        val mime = if (url.contains(".flac", true)) "audio/flac" else "audio/mpeg"
        return listOf(Stream(url, qualityLabel(url, quality), mime, provider = id))
    }

    private fun qualityLabel(url: String, requested: String): String = when {
        url.contains(".flac", true) -> "FLAC"
        requested.equals("flac", true) -> "FLAC"
        else -> "MP3 320"
    }
}
