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
 * Qobuz, ported from Mornye-Extension sources/qobuz-web/index.js
 *  - metadata   signed GET /qbz/<resource>/<method> on the zarz-v2 session
 *               (CONFIG.apiBaseURL = https://api.zarz.moe/v2/qbz, app_id appended by metadataURL)
 *               with the public qobuz.com widget API as the fallback source
 *  - images     trailing _<n>.jpg rewritten to _max.jpg (upscaleImageURL)
 *  - download   signed POST /dl/qbz {quality, upload_to_r2:false, id, type:"track", url}
 *               ticket("qbz","track", trackURL) - the gateway hashes body.url
 *               answer: direct_download_url / download_url
 */
object Qobuz : MusicProvider {
    override val id = "qobuz"
    override val label = "Qobuz"
    override val canCatalogue = true
    override val canStream = true

    private const val APP_VERSION = "qobuz-web@1.2.21"
    private const val DOWNLOAD_PATH = "/dl/qbz"
    private const val OPEN_BASE = "https://open.qobuz.com"
    private const val PUBLIC_API = "https://www.qobuz.com/api.json/0.2"
    // CONFIG.widgetAppID - the id the public qobuz.com widget API accepts.
    private const val WIDGET_APP_ID = "735532640"

    private fun appId(): String = (Creds.get("qobuz.appId", "798273057") ?: "798273057").trim()
    private fun countryCode(): String = (Creds.get("qobuz.countryCode", "US") ?: "US").trim().uppercase()

    override fun configured(): Boolean = true

    override fun statusDetail(): String {
        val session = Zarz.statusLine(APP_VERSION)
        return if (session == "not authorized") "needs ZARZ authorization" else session
    }

    private fun enc(v: String): String = URLEncoder.encode(v, "UTF-8")

    private fun query(params: List<Pair<String, String>>): String =
        params.filter { it.second.isNotEmpty() }.joinToString("&") { "${enc(it.first)}=${enc(it.second)}" }

    /**
     * getMetadataJSON(): the signed gateway is asked first, the public widget API second.
     * Both return the same collection envelopes, so callers read one shape.
     */
    private fun get(path: String, params: List<Pair<String, String>>): JSONObject? {
        val signedQuery = query(params + listOf("app_id" to appId()))
        try {
            val res = Zarz.signedFetch(APP_VERSION, "GET", "/qbz/$path?$signedQuery")
            if (res.ok) jsonOrNull(res.body)?.let { return it }
        } catch (e: Zarz.VerificationRequired) {
            throw e
        } catch (e: Throwable) {
            Diag.log("qobuz", "signed $path failed: ${e.message}")
        }
        // getPublicQobuzJSON(): qobuz.com's own widget API, no session needed.
        val publicQuery = query(params + listOf("country" to countryCode()))
        val res = Http.get(
            "$PUBLIC_API/$path?$publicQuery",
            mapOf("Accept" to "application/json", "X-App-Id" to WIDGET_APP_ID)
        )
        if (!res.ok) {
            Diag.log("qobuz", "public $path HTTP ${res.code}")
            return null
        }
        return jsonOrNull(res.body)
    }

    // ------------------------------------------------------------------ mapping

    private fun imageUrl(url: String?): String? {
        val text = (url ?: "").trim()
        if (text.isEmpty()) return null
        return text.replace(Regex("_\\d+\\.jpg$"), "_max.jpg")
    }

    private fun imageOf(obj: JSONObject?): String? =
        imageUrl(obj?.optJSONObject("image")?.optStringOrNull("large")
            ?: obj?.optJSONObject("image")?.optStringOrNull("small"))

    private fun displayTitle(title: String?, version: String?): String {
        val t = (title ?: "").trim()
        val v = (version ?: "").trim()
        if (t.isEmpty() || v.isEmpty()) return t
        if (t.lowercase().contains(v.lowercase())) return t
        return "$t ($v)"
    }

    private fun artistsOf(track: JSONObject): List<String> {
        val names = ArrayList<String>()
        track.optJSONObject("performer")?.optStringOrNull("name")?.let { names.add(it) }
        track.optJSONArray("artists")?.objects()?.forEach { a ->
            a.optStringOrNull("name")?.let { if (!names.contains(it)) names.add(it) }
        }
        if (names.isEmpty()) {
            track.optJSONObject("album")?.optJSONObject("artist")?.optStringOrNull("name")?.let { names.add(it) }
        }
        return names
    }

    private fun trackOf(item: JSONObject): Track? {
        val trackId = item.optStringOrNull("id") ?: return null
        val album = item.optJSONObject("album")
        return Track(
            provider = id,
            id = trackId,
            title = displayTitle(item.optStringOrNull("title"), item.optStringOrNull("version")),
            artists = artistsOf(item),
            albumName = displayTitle(album?.optStringOrNull("title"), album?.optStringOrNull("version")),
            albumId = album?.optStringOrNull("id"),
            cover = imageOf(album),
            durationMs = (item.optLongOrNull("duration") ?: 0L).takeIf { it > 0 }?.times(1000),
            isrc = item.optStringOrNull("isrc"),
            trackNumber = item.optIntOrNull("track_number"),
            discNumber = item.optIntOrNull("media_number"),
            year = (album?.optStringOrNull("release_date_original") ?: "").take(4).toIntOrNull(),
            explicit = item.optBoolean("parental_warning", false),
            webUrl = "$OPEN_BASE/track/$trackId",
        )
    }

    private fun albumOf(item: JSONObject): Album? {
        val albumId = item.optStringOrNull("id") ?: return null
        val tracks = ArrayList<Track>()
        item.optJSONObject("tracks")?.array("items")?.objects()?.forEach { t ->
            val withAlbum = JSONObject(t.toString())
            if (!withAlbum.has("album")) withAlbum.put("album", item)
            trackOf(withAlbum)?.let { tracks.add(it) }
        }
        return Album(
            provider = id,
            id = albumId,
            title = displayTitle(item.optStringOrNull("title"), item.optStringOrNull("version")),
            artists = listOfNotNull(item.optJSONObject("artist")?.optStringOrNull("name")),
            cover = imageOf(item),
            year = (item.optStringOrNull("release_date_original") ?: "").take(4).toIntOrNull(),
            trackCount = item.optIntOrNull("tracks_count"),
            albumType = item.optStringOrNull("release_type") ?: "album",
            label = item.optJSONObject("label")?.optStringOrNull("name"),
            tracks = tracks,
            webUrl = "$OPEN_BASE/album/$albumId",
        )
    }

    private fun itemsOf(payload: JSONObject?, key: String): List<JSONObject> {
        val collection = payload?.optJSONObject(key)
        collection?.array("items")?.let { return it.objects() }
        payload?.array(key)?.let { return it.objects() }
        return emptyList()
    }

    // ------------------------------------------------------------------ catalogue

    override suspend fun search(query: String, limit: Int): List<Hit> {
        val out = ArrayList<Hit>()
        val params = listOf("query" to query, "limit" to limit.toString(), "offset" to "0")

        itemsOf(get("track/search", params), "tracks").forEach { item ->
            trackOf(item)?.let { t ->
                out.add(Hit(Hit.Kind.TRACK, t.title, t.artistLine, t.cover, Links.track(id, t.id), track = t))
            }
        }
        itemsOf(get("artist/search", listOf("query" to query, "limit" to "5", "offset" to "0")), "artists").forEach { item ->
            val artistId = item.optStringOrNull("id") ?: return@forEach
            val artist = Artist(
                provider = id, id = artistId,
                name = item.optStringOrNull("name") ?: "",
                image = imageOf(item),
            )
            out.add(Hit(Hit.Kind.ARTIST, artist.name, "Artist", artist.image, Links.artist(id, artist.id), artist = artist))
        }
        itemsOf(get("album/search", listOf("query" to query, "limit" to "5", "offset" to "0")), "albums").forEach { item ->
            albumOf(item)?.let { a ->
                out.add(Hit(Hit.Kind.ALBUM, a.title, a.artists.joinToString(", "), a.cover, Links.album(id, a.id), album = a))
            }
        }
        return out
    }

    override suspend fun track(id: String): Track? =
        get("track/get", listOf("track_id" to id))?.let { trackOf(it) }

    override suspend fun album(id: String): Album? =
        get("album/get", listOf("album_id" to id, "limit" to "500"))?.let { albumOf(it) }

    override suspend fun artist(id: String): Artist? {
        val body = get("artist/get", listOf("artist_id" to id, "extra" to "albums", "limit" to "50", "offset" to "0")) ?: return null
        val artist = Artist(
            provider = this.id, id = id,
            name = body.optStringOrNull("name") ?: "",
            image = imageOf(body),
        )
        val albums = ArrayList<Album>()
        body.optJSONObject("albums")?.array("items")?.objects()?.forEach { a -> albumOf(a)?.let { albums.add(it) } }
        return artist.copy(albums = albums)
    }

    // ------------------------------------------------------------------ streams

    /** mapMusicDLQuality(): the gateway's quality names. */
    private fun downloadQuality(quality: String): String = when (quality.uppercase()) {
        "HI_RES_LOSSLESS", "HI-RES-MAX", "MAX" -> "hi-res-max"
        "HI_RES", "HI-RES" -> "hi-res"
        else -> "cd"
    }

    override suspend fun streams(track: Track, quality: String): List<Stream> {
        val trackUrl = "$OPEN_BASE/track/${track.id}"
        // The ticket is minted on the URL because the gateway hashes body.url.
        val ticket = Zarz.ticket(APP_VERSION, "qbz", "track", trackUrl)
        val body = JSONObject()
            .put("quality", downloadQuality(quality))
            .put("upload_to_r2", false)
            .put("id", track.id)
            .put("type", "track")
            .put("url", trackUrl)
        val res = Zarz.signedFetch(APP_VERSION, "POST", DOWNLOAD_PATH, body.toString(), mapOf("X-Zarz-Ticket" to ticket))
        val payload = jsonOrNull(res.body) ?: return emptyList()
        val descriptor = payload.optJSONObject("data") ?: payload
        val url = descriptor.optStringOrNull("direct_download_url")
            ?: descriptor.optStringOrNull("download_url")
            ?: return emptyList()
        val mime = if (url.contains(".flac", true)) "audio/flac" else "audio/mpeg"
        return listOf(Stream(url, qualityLabel(url, quality), mime, provider = id))
    }

    private fun qualityLabel(url: String, requested: String): String = when {
        url.contains(".flac", true) -> "FLAC"
        else -> requested.uppercase()
    }
}
