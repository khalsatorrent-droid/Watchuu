package com.watchuu.musicbox.providers

import com.watchuu.musicbox.core.Album
import com.watchuu.musicbox.core.Artist
import com.watchuu.musicbox.core.Diag
import com.watchuu.musicbox.core.Hit
import com.watchuu.musicbox.core.Links
import com.watchuu.musicbox.core.MusicProvider
import com.watchuu.musicbox.core.Stream
import com.watchuu.musicbox.core.Track
import com.watchuu.musicbox.core.Zarz
import com.watchuu.musicbox.core.jsonOrNull
import com.watchuu.musicbox.core.array
import com.watchuu.musicbox.core.objects
import com.watchuu.musicbox.core.optStringOrNull
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Beatport, ported from Mornye-Extension sources/beatport/index.js
 *  - catalogue   signed POST /btp/catalog with an `action` field
 *                (search, track, release, artist, artist-releases, artist-tracks)
 *  - download    signed POST /dl/btp {id, quality, allow_fallback:false}
 *                ticket: provider "btp" but the resource hash is sha256("beatport:track:<id>")
 *  - quality     lossless -> flac, high -> aac; the answer is rejected unless the
 *                gateway reports the exact requested quality and codec
 */
object Beatport : MusicProvider {
    override val id = "beatport"
    override val label = "Beatport"
    override val canCatalogue = true
    override val canStream = true

    private const val APP_VERSION = "beatport@1.0.0"
    private const val CATALOG = "/btp/catalog"
    private const val DOWNLOAD = "/dl/btp"
    private const val ID_REGEX = "^[1-9]\\d{0,14}$"

    override fun configured(): Boolean = true

    override fun statusDetail(): String {
        val session = Zarz.statusLine(APP_VERSION)
        return if (session == "not authorized") "needs ZARZ authorization" else session
    }

    private fun rawId(value: String): String? {
        val raw = value.trim().removePrefix("beatport:")
        return if (Regex(ID_REGEX).matches(raw)) raw else null
    }

    /** request(): one signed catalogue call. */
    private fun catalog(body: JSONObject): JSONObject? {
        val res = Zarz.signedFetch(APP_VERSION, "POST", CATALOG, body.toString())
        if (!res.ok) {
            Diag.log("beatport", "catalog HTTP ${res.code}")
            return null
        }
        return jsonOrNull(res.body)
    }

    // ------------------------------------------------------------------ mapping

    private fun trackOf(t: JSONObject): Track? {
        val trackId = t.optStringOrNull("id")?.removePrefix("beatport:") ?: return null
        var title = (t.optStringOrNull("title") ?: "").trim()
        val mix = (t.optStringOrNull("mix_name") ?: "").trim()
        if (mix.isNotEmpty() && !title.lowercase().contains(mix.lowercase())) title = "$title ($mix)"
        val artists = t.optJSONArray("artists")?.objects()?.mapNotNull { it.optStringOrNull("name") } ?: emptyList()
        return Track(
            provider = id,
            id = trackId,
            title = title,
            artists = artists,
            albumName = t.optStringOrNull("album"),
            albumId = t.optStringOrNull("album_id")?.removePrefix("beatport:"),
            cover = t.optStringOrNull("cover"),
            durationMs = t.optStringOrNull("duration_ms")?.toLongOrNull(),
            isrc = t.optStringOrNull("isrc"),
            trackNumber = t.optStringOrNull("track_number")?.toIntOrNull(),
            discNumber = 1,
            year = (t.optStringOrNull("release_date") ?: "").take(4).toIntOrNull(),
            explicit = t.optBoolean("explicit", false),
            webUrl = t.optStringOrNull("url"),
        )
    }

    private fun albumOf(r: JSONObject): Album? {
        val albumId = r.optStringOrNull("id")?.removePrefix("beatport:") ?: return null
        val artists = r.optJSONArray("artists")?.objects()?.mapNotNull { it.optStringOrNull("name") } ?: emptyList()
        val tracks = ArrayList<Track>()
        r.array("tracks")?.objects()?.forEach { t -> trackOf(t)?.let { tracks.add(it) } }
        return Album(
            provider = id,
            id = albumId,
            title = r.optStringOrNull("name") ?: "",
            artists = artists,
            cover = r.optStringOrNull("cover"),
            year = (r.optStringOrNull("release_date") ?: "").take(4).toIntOrNull(),
            trackCount = r.optStringOrNull("total_tracks")?.toIntOrNull() ?: tracks.size,
            albumType = "album",
            label = r.optStringOrNull("label"),
            tracks = tracks,
            webUrl = r.optStringOrNull("url"),
        )
    }

    private fun artistOf(a: JSONObject): Artist? {
        val artistId = a.optStringOrNull("id")?.removePrefix("beatport:") ?: return null
        val albums = ArrayList<Album>()
        a.array("albums")?.objects()?.forEach { r -> albumOf(r)?.let { albums.add(it) } }
        val top = ArrayList<Track>()
        a.array("top_tracks")?.objects()?.forEach { t -> trackOf(t)?.let { top.add(it) } }
        return Artist(
            provider = id,
            id = artistId,
            name = a.optStringOrNull("name") ?: "",
            image = a.optStringOrNull("image") ?: a.optStringOrNull("image_url"),
            albums = albums,
            topTracks = top,
            webUrl = a.optStringOrNull("url"),
        )
    }

    // ------------------------------------------------------------------ catalogue

    override suspend fun search(query: String, limit: Int): List<Hit> {
        val body = JSONObject()
            .put("action", "search")
            .put("q", query)
            .put("type", "all")
            .put("limit", limit)
            .put("page", 1)
        val payload = catalog(body) ?: return emptyList()
        val out = ArrayList<Hit>()
        payload.array("tracks")?.objects()?.forEach { t ->
            trackOf(t)?.let { hit -> out.add(Hit(Hit.Kind.TRACK, hit.title, hit.artistLine, hit.cover, Links.track(id, hit.id), track = hit)) }
        }
        payload.array("artists")?.objects()?.forEach { a ->
            artistOf(a)?.let { hit -> out.add(Hit(Hit.Kind.ARTIST, hit.name, "Artist", hit.image, Links.artist(id, hit.id), artist = hit)) }
        }
        payload.array("releases")?.objects()?.forEach { r ->
            albumOf(r)?.let { hit -> out.add(Hit(Hit.Kind.ALBUM, hit.title, hit.artists.joinToString(", "), hit.cover, Links.album(id, hit.id), album = hit)) }
        }
        return out
    }

    override suspend fun track(id: String): Track? {
        val raw = rawId(id) ?: return null
        val payload = catalog(JSONObject().put("action", "track").put("id", raw)) ?: return null
        return trackOf(payload.optJSONObject("track") ?: payload)
    }

    override suspend fun album(id: String): Album? {
        val raw = rawId(id) ?: return null
        val payload = catalog(JSONObject().put("action", "release").put("id", raw)) ?: return null
        return albumOf(payload.optJSONObject("release") ?: payload)
    }

    override suspend fun artist(id: String): Artist? {
        val raw = rawId(id) ?: return null
        val payload = catalog(JSONObject().put("action", "artist").put("id", raw)) ?: return null
        val artist = artistOf(payload.optJSONObject("artist") ?: payload) ?: return null
        val releases = catalog(
            JSONObject().put("action", "artist-releases").put("id", raw).put("limit", 20).put("page", 1)
        )
        val albums = ArrayList<Album>()
        releases?.array("releases")?.objects()?.forEach { r -> albumOf(r)?.let { albums.add(it) } }
        val tracksPage = catalog(
            JSONObject().put("action", "artist-tracks").put("id", raw).put("limit", 20).put("page", 1)
        )
        val top = ArrayList<Track>()
        tracksPage?.array("tracks")?.objects()?.forEach { t -> trackOf(t)?.let { top.add(it) } }
        return artist.copy(albums = albums, topTracks = top)
    }

    // ------------------------------------------------------------------ streams

    private fun qualityName(value: String): String = when (value.uppercase()) {
        "LOSSLESS", "FLAC", "BEST", "HI_RES", "HI_RES_LOSSLESS", "" -> "lossless"
        "HIGH", "AAC", "256" -> "high"
        else -> "lossless"
    }

    override suspend fun streams(track: Track, quality: String): List<Stream> {
        val raw = rawId(track.id) ?: return emptyList()
        val q = qualityName(quality)
        // The header names the alias "btp"; the hash is minted over "beatport:track:<id>".
        val ticket = Zarz.ticket(APP_VERSION, "btp", "track", raw, hashProvider = "beatport")
        val body = JSONObject()
            .put("id", raw)
            .put("quality", q)
            .put("allow_fallback", false)
        val res = Zarz.signedFetch(APP_VERSION, "POST", DOWNLOAD, body.toString(), mapOf("X-Zarz-Ticket" to ticket))
        val payload = jsonOrNull(res.body) ?: return emptyList()
        val audio = payload.optJSONObject("audio")
        val url = payload.optStringOrNull("download_url") ?: return emptyList()

        // cachedResolve(): refuse anything that is not exactly what was asked for.
        val fulfilled = payload.optStringOrNull("fulfilled_quality")
        val requested = payload.optStringOrNull("requested_quality")
        val changed = payload.optBoolean("quality_changed", false)
        val expectedCodec = if (q == "lossless") "flac" else "aac"
        val codec = audio?.optStringOrNull("codec")
        if (changed || (fulfilled != null && fulfilled != q) || (requested != null && requested != q) || (codec != null && codec != expectedCodec)) {
            Diag.log("beatport", "gateway returned $fulfilled/${codec} for requested $q; rejected")
            return emptyList()
        }
        val mime = if (codec == "flac") "audio/flac" else "audio/mp4"
        val label = buildString {
            append(if (q == "lossless") "FLAC" else "AAC")
            val depth = audio?.optStringOrNull("bit_depth")?.toIntOrNull() ?: 0
            val rate = audio?.optStringOrNull("sample_rate")?.toIntOrNull() ?: 0
            if (depth > 0) append(" · ${depth}bit")
            if (rate > 0) append(" · ${rate / 1000}kHz")
        }
        // The media address is on the gateway (api.zarz.moe/v2/media/btp/...); Mornye fetches it with
        // its own user agent rather than a browser's.
        return listOf(Stream(url, label, mime, headers = mapOf("User-Agent" to "Mornye/5.3.0"), provider = id))
    }
}
