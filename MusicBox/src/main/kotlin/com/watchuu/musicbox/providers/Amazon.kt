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
import com.watchuu.musicbox.core.optStringOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Amazon Music, ported from Mornye-Extension sources/amazon/index.js
 *  - session    POST https://music.amazon.com/config.json?skipToken=false&clientApplication=skyfire
 *               -> deviceId, sessionId, version, displayLanguage, csrf{token,ts,rnd}   (initSession)
 *  - headers    the x-amzn-* client blob, serialised per request                     (buildHeaders)
 *  - search     POST https://na.mesk.skill.music.a2z.com/api/showSearch              (callShowSearch)
 *  - album      POST .../api/showHome with deeplink /albums/<asin>                   (callShowHome)
 *  - track      POST .../api/cosmicTrack/displayCatalogTrack                         (callDisplayCatalogTrack)
 *  - download   signed POST /dl/amazeamazeamaze {asin, codec},
 *               ticket("amazeamazeamaze","track",asin); answer is an array whose first
 *               element holds audio.url (+ audio.key when the asset is encrypted)
 */
object Amazon : MusicProvider {
    override val id = "amazon"
    override val label = "Amazon Music"
    override val canCatalogue = true
    override val canStream = true

    private const val APP_VERSION = "amzn@2.3.12"
    private const val SKILL = "https://na.mesk.skill.music.a2z.com/api"
    private const val MUSIC = "https://music.amazon.com"
    private const val DEVICE_MODEL = "WEBPLAYER"
    private const val DEVICE_FAMILY = "WebPlayer"
    private const val DEFAULT_APP_VERSION = "1.0.9678.0"
    private const val DOWNLOAD_PATH = "/dl/amazeamazeamaze"

    private val ASIN = Regex("^B[0-9A-Z]{9}$")

    private val USER_AGENTS = listOf(
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/152.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/152.0.0.0 Safari/537.36",
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/152.0.0.0 Safari/537.36",
    )

    private fun randomUa(): String = USER_AGENTS[Zarz.randomHex(1).hashCode().let { if (it < 0) -it else it } % USER_AGENTS.size]

    // ------------------------------------------------------------------ session

    private class Session {
        var deviceId = ""
        var sessionId = ""
        var csrfToken = ""
        var csrfTs = ""
        var csrfRnd = ""
        var appVersion = DEFAULT_APP_VERSION
        var displayLanguage = "en_US"
        var initialized = false
    }

    private val session = Session()

    private fun territory(): String = (Creds.get("amazon.homeCountry", "US") ?: "US").trim().uppercase()

    private fun currency(): String = when (territory()) {
        "JP" -> "JPY"
        "GB" -> "GBP"
        "DE", "FR", "IT", "ES" -> "EUR"
        "IN" -> "INR"
        "BR" -> "BRL"
        "MX" -> "MXN"
        "AU" -> "AUD"
        else -> "USD"
    }

    /** initSession(): the config.json POST issues the CSRF credentials. */
    private fun initSession(force: Boolean = false) {
        if (session.initialized && !force) return
        val url = "$MUSIC/config.json?skipToken=false&clientApplication=skyfire"
        val res = try {
            Http.request("POST", url, mapOf("Accept" to "application/json", "User-Agent" to randomUa()), ByteArray(0), null)
        } catch (e: Throwable) {
            Diag.log("amazon", "config.json failed: ${e.message}")
            null
        }
        val config = res?.takeIf { it.ok }?.json()
        if (config != null) {
            session.deviceId = config.optStringOrNull("deviceId") ?: ""
            session.sessionId = config.optStringOrNull("sessionId") ?: ""
            session.appVersion = config.optStringOrNull("version") ?: DEFAULT_APP_VERSION
            session.displayLanguage = config.optStringOrNull("displayLanguage") ?: "en_US"
            val csrf = config.optJSONObject("csrf")
            if (csrf != null) {
                session.csrfToken = csrf.optStringOrNull("token") ?: ""
                session.csrfTs = csrf.optStringOrNull("ts") ?: nowSeconds()
                session.csrfRnd = csrf.optStringOrNull("rnd") ?: randomNonce()
            }
            session.initialized = true
            Diag.log("amazon", "session ready (device ${session.deviceId.take(6)}…)")
            return
        }
        // Fallback session, as initSession() does when config.json does not answer.
        session.deviceId = Zarz.randomHex(8)
        session.sessionId = "${Zarz.randomHex(2).hashCode() % 999}-${Zarz.randomHex(4)}-${Zarz.randomHex(4)}"
        session.csrfToken = ""
        session.csrfTs = nowSeconds()
        session.csrfRnd = randomNonce()
        session.appVersion = DEFAULT_APP_VERSION
        session.displayLanguage = "en_US"
        session.initialized = true
        Diag.log("amazon", "using fallback session")
    }

    private fun nowSeconds(): String = (System.currentTimeMillis() / 1000).toString()
    private fun randomNonce(): String = (Math.abs(Zarz.randomHex(4).hashCode()).toLong() % 2_000_000_000L).toString()

    /** buildHeaders(): the x-amzn-* client blob. */
    private fun buildHeaders(pageUrl: String): Map<String, String> {
        val csrf = JSONObject()
            .put("interface", "CSRFInterface.v1_0.CSRFHeaderElement")
            .put("token", session.csrfToken)
            .put("timestamp", session.csrfTs.ifBlank { nowSeconds() })
            .put("rndNonce", session.csrfRnd.ifBlank { randomNonce() })
            .toString()
        val auth = JSONObject()
            .put("interface", "ClientAuthenticationInterface.v1_0.ClientTokenElement")
            .put("accessToken", "")
            .toString()
        return mapOf(
            "x-amzn-authentication" to auth,
            "x-amzn-device-model" to DEVICE_MODEL,
            "x-amzn-device-width" to "1920",
            "x-amzn-device-family" to DEVICE_FAMILY,
            "x-amzn-device-id" to session.deviceId,
            "x-amzn-user-agent" to randomUa(),
            "x-amzn-session-id" to session.sessionId,
            "x-amzn-device-height" to "1080",
            "x-amzn-request-id" to (Zarz.randomHex(4) + "-" + System.currentTimeMillis()),
            "x-amzn-device-language" to session.displayLanguage.ifBlank { "en_US" },
            "x-amzn-currency-of-preference" to currency(),
            "x-amzn-os-version" to "1.0",
            "x-amzn-application-version" to session.appVersion,
            "x-amzn-device-time-zone" to TimeZone.getDefault().id,
            "x-amzn-timestamp" to System.currentTimeMillis().toString(),
            "x-amzn-csrf" to csrf,
            "x-amzn-music-domain" to "music.amazon.com",
            "x-amzn-referer" to "",
            "x-amzn-affiliate-tags" to "",
            "x-amzn-ref-marker" to "",
            "x-amzn-page-url" to pageUrl,
            "x-amzn-weblab-id-overrides" to "",
            "x-amzn-video-player-token" to "",
            "x-amzn-feature-flags" to "",
            "x-amzn-has-profile-id" to "",
            "x-amzn-age-band" to "",
        )
    }

    private class Page(val data: JSONObject, val raw: String)

    private fun post(url: String, body: String, pageUrl: String, headers: Map<String, String>): Page? {
        val all = headers + mapOf(
            "Content-Type" to "text/plain;charset=UTF-8",
            "User-Agent" to randomUa(),
            "Origin" to MUSIC,
            "Referer" to pageUrl,
        )
        val res = try {
            Http.request("POST", url, all, body.toByteArray(Charsets.UTF_8), "text/plain;charset=UTF-8")
        } catch (e: Throwable) {
            Diag.log("amazon", "POST $url failed: ${e.message}")
            return null
        }
        if (!res.ok) {
            Diag.log("amazon", "POST $url HTTP ${res.code}")
            return null
        }
        val parsed = jsonOrNull(res.body) ?: return null
        return Page(parsed, res.body)
    }

    private fun showSearch(keyword: String, retried: Boolean = false): Page? {
        initSession()
        val pageUrl = "$MUSIC/search/${URLEncoder.encode(keyword, "UTF-8")}"
        val body = JSONObject()
            .put("filter", JSONObject().put("IsLibrary", JSONArray().put("false")).toString())
            .put(
                "keyword",
                JSONObject()
                    .put("interface", "Web.TemplatesInterface.v1_0.Touch.SearchTemplateInterface.SearchKeywordClientInformation")
                    .put("keyword", keyword)
                    .toString()
            )
            .put("suggestedKeyword", keyword)
            .put("userHash", JSONObject().put("level", "LIBRARY_MEMBER").toString())
            .put("headers", JSONObject(buildHeaders(pageUrl)).toString())
            .toString()
        val page = post("$SKILL/showSearch", body, pageUrl, emptyMap())
        if (page == null && !retried) {
            initSession(true)
            return showSearch(keyword, true)
        }
        if (page != null && isServiceErrorDialog(page.data) && !retried) {
            // An expired session answers 200 with a "Service error" dialog.
            Diag.log("amazon", "search returned a service error, renewing session")
            initSession(true)
            return showSearch(keyword, true)
        }
        return page
    }

    /** A page answer that carries only a dialog template (the expired-session "Service error"). */
    private fun isDialogOnly(data: JSONObject): Boolean {
        val methods = data.optJSONArray("methods") ?: return false
        var dialog = false
        for (i in 0 until methods.length()) {
            val name = methods.optJSONObject(i)?.optJSONObject("template")?.optString("interface", "") ?: continue
            if (name.endsWith("DialogTemplate")) dialog = true else if (name.isNotEmpty()) return false
        }
        return dialog
    }

    private fun isServiceErrorDialog(data: JSONObject): Boolean {
        val methods = data.optJSONArray("methods") ?: return false
        var dialog = false
        for (i in 0 until methods.length()) {
            val template = methods.optJSONObject(i)?.optJSONObject("template") ?: continue
            val name = template.optString("interface", "")
            if (name.endsWith("SearchTemplate")) return false
            if (name.endsWith("DialogTemplate")) dialog = true
        }
        return dialog
    }

    private fun showHome(deeplink: String, retried: Boolean = false): Page? {
        initSession()
        val pageUrl = MUSIC + deeplink
        val body = JSONObject()
            .put(
                "deeplink",
                JSONObject()
                    .put("interface", "DeeplinkInterface.v1_0.DeeplinkClientInformation")
                    .put("deeplink", deeplink)
                    .toString()
            )
            .put("headers", JSONObject(buildHeaders(pageUrl)).toString())
            .toString()
        val page = post("$SKILL/showHome", body, pageUrl, emptyMap())
        if ((page == null || isDialogOnly(page.data)) && !retried) {
            if (page != null) Diag.log("amazon", "page $deeplink returned a service error, renewing session")
            initSession(true)
            return showHome(deeplink, true)
        }
        return page
    }

    private fun displayCatalogTrack(trackId: String, retried: Boolean = false): Page? {
        initSession()
        val pageUrl = "$MUSIC/tracks/$trackId"
        val body = JSONObject()
            .put("id", trackId)
            .put("userHash", JSONObject().put("level", "LIBRARY_MEMBER").toString())
            .put("headers", JSONObject(buildHeaders(pageUrl)).toString())
            .toString()
        val page = post("https://na.mesk.skill.music.a2z.com/api/cosmicTrack/displayCatalogTrack", body, "$MUSIC/", emptyMap())
        if (page == null && !retried) {
            initSession(true)
            return displayCatalogTrack(trackId, true)
        }
        return page
    }

    // ------------------------------------------------------------------ json walking

    private fun findAllByInterface(obj: Any?, target: String, out: MutableList<JSONObject> = ArrayList(), depth: Int = 0) {
        if (obj == null || depth > 20) return
        when (obj) {
            is JSONObject -> {
                if (obj.optString("interface", "") == target) out.add(obj)
                for (key in obj.keys()) findAllByInterface(obj.opt(key), target, out, depth + 1)
            }
            is JSONArray -> for (i in 0 until obj.length()) findAllByInterface(obj.opt(i), target, out, depth + 1)
        }
    }

    private fun findFirst(obj: Any?, key: String, depth: Int = 0): Any? {
        if (obj == null || depth > 20) return null
        when (obj) {
            is JSONObject -> {
                if (obj.has(key)) return obj.opt(key)
                for (k in obj.keys()) {
                    val found = findFirst(obj.opt(k), key, depth + 1)
                    if (found != null) return found
                }
            }
            is JSONArray -> for (i in 0 until obj.length()) {
                val found = findFirst(obj.opt(i), key, depth + 1)
                if (found != null) return found
            }
        }
        return null
    }

    /** textValue(): Amazon wraps display strings in {text: …} or {defaultValue: …}. */
    private fun textValue(value: Any?): String {
        when (value) {
            null -> return ""
            is String -> return value
            is Number -> return value.toString()
            is JSONObject -> {
                value.optStringOrNull("text")?.let { return it }
                textValue(value.opt("defaultValue")).takeIf { it.isNotEmpty() }?.let { return it }
                value.optJSONObject("observer")?.let { obs ->
                    textValue(obs.opt("defaultValue")).takeIf { it.isNotEmpty() }?.let { return it }
                }
            }
            else -> return ""
        }
        return ""
    }

    private fun normalizeAsin(candidate: String?): String? {
        var s = (candidate ?: "").trim()
        if (s.isEmpty()) return null
        s = try {
            URLDecoderCompat.decode(s)
        } catch (e: Throwable) {
            s
        }.uppercase(Locale.US)
        val cut = s.indexOfFirst { it == '?' || it == '#' || it == '&' || it == '/' }
        if (cut >= 0) s = s.substring(0, cut)
        return if (ASIN.matches(s)) s else null
    }

    private object URLDecoderCompat {
        fun decode(value: String): String = java.net.URLDecoder.decode(value, "UTF-8")
    }

    private fun fixImage(url: String?): String? {
        val text = (url ?: "").trim()
        if (text.isEmpty()) return null
        // Amazon's unsized CDN URL serves the source asset.
        return text.replace(Regex("\\._[^.]+\\.(jpg|png|jpeg)$"), ".$1")
    }

    private fun parseDuration(mmss: String?): Int {
        val text = textValue(mmss)
        if (text.isBlank()) return 0
        val parts = text.split(":")
        return when (parts.size) {
            2 -> (parts[0].toIntOrNull() ?: 0) * 60 + (parts[1].toIntOrNull() ?: 0)
            3 -> (parts[0].toIntOrNull() ?: 0) * 3600 + (parts[1].toIntOrNull() ?: 0) * 60 + (parts[2].toIntOrNull() ?: 0)
            else -> 0
        }
    }

    private class Deeplink(val type: String, val id: String, val albumId: String?)

    /** extractAmazonDeeplinkInfo(): /albums/<id>?trackAsin=<asin> means a track. */
    private fun deeplinkInfo(deeplink: String?): Deeplink? {
        if (deeplink.isNullOrBlank()) return null
        // Split by hand: java.net.URI throws on spaces, "|" or quotes that `new URL()` accepts,
        // and every row whose link threw was silently dropped from the album.
        var rest = deeplink.trim()
        Regex("""^[a-zA-Z][a-zA-Z0-9+.-]*://[^/]*""").find(rest)?.let { rest = rest.substring(it.range.last + 1) }
        val path = rest.substringBefore('?').substringBefore('#').trim('/')
        if (path.isEmpty()) return null
        val segments = path.split("/")
        val kind = segments[0].lowercase(Locale.US)
        val rawId = segments.getOrNull(1) ?: ""
        if (kind == "albums") {
            val trackAsin = queryParam(deeplink, "trackAsin")
            if (!trackAsin.isNullOrBlank()) {
                return Deeplink("track", normalizeAsin(trackAsin) ?: trackAsin, normalizeAsin(rawId) ?: rawId)
            }
        }
        return Deeplink(kind, normalizeAsin(rawId) ?: rawId, null)
    }

    private fun queryParam(url: String, name: String): String? =
        url.substringAfter('?', "").substringBefore('#').takeIf { it.isNotEmpty() }
            ?.split("&")
            ?.map { it.split("=", limit = 2) }
            ?.firstOrNull { it.size == 2 && it[0] == name }
            ?.let { runCatching { java.net.URLDecoder.decode(it[1], "UTF-8") }.getOrDefault(it[1]) }

    // ------------------------------------------------------------------ parsing

    private fun trackUrl(asin: String) = "$MUSIC/tracks/$asin"
    private fun albumUrl(asin: String) = "$MUSIC/albums/$asin"

    private fun tracksFrom(page: Page, limit: Int): List<Track> {
        val rows = ArrayList<JSONObject>()
        findAllByInterface(page.data, "Web.TemplatesInterface.v1_0.Touch.WidgetsInterface.DescriptiveRowItemElement", rows)
        findAllByInterface(page.data, "Web.TemplatesInterface.v1_0.Touch.WidgetsInterface.SquareHorizontalItemElement", rows)
        val out = ArrayList<Track>()
        for (row in rows) {
            val name = textValue(row.opt("primaryText"))
            var link = row.optJSONObject("primaryTextLink")?.optStringOrNull("deeplink")
            if (link.isNullOrBlank()) link = row.optJSONObject("primaryLink")?.optStringOrNull("deeplink")
            val info = deeplinkInfo(link)
            // Album rows link "/albums/<a>?trackAsin=<t>"; others link "/tracks/<t>" directly.
            if (name.isBlank() || info == null || (info.type != "track" && info.type != "tracks") || info.id.isBlank()) continue
            val artist = textValue(row.opt("secondaryText1")).ifBlank { textValue(row.opt("secondaryText2")) }
                .ifBlank { textValue(row.opt("secondaryText")) }
            val duration = parseDuration(row.optStringOrNull("secondaryText3") ?: row.optStringOrNull("duration"))
            out.add(
                Track(
                    provider = id,
                    id = info.id,
                    title = name,
                    artists = if (artist.isBlank()) emptyList() else listOf(artist),
                    albumId = info.albumId,
                    cover = fixImage(row.optStringOrNull("image")),
                    durationMs = if (duration > 0) duration * 1000L else null,
                    webUrl = trackUrl(info.id),
                )
            )
            if (out.size >= limit) break
        }
        return out
    }

    // ------------------------------------------------------------------ catalogue

    override suspend fun search(query: String, limit: Int): List<Hit> {
        val page = showSearch(query) ?: return emptyList()
        val out = ArrayList<Hit>()
        for (t in tracksFrom(page, limit)) {
            out.add(Hit(Hit.Kind.TRACK, t.title, t.artistLine, t.cover, Links.track(id, t.id), track = t))
        }
        // Albums: /albums/<asin> deeplinks on square items.
        val squares = ArrayList<JSONObject>()
        findAllByInterface(page.data, "Web.TemplatesInterface.v1_0.Touch.WidgetsInterface.SquareVerticalItemElement", squares)
        findAllByInterface(page.data, "Web.TemplatesInterface.v1_0.Touch.WidgetsInterface.SquareHorizontalItemElement", squares)
        val seen = HashSet<String>()
        for (item in squares) {
            val link = item.optJSONObject("primaryLink")?.optStringOrNull("deeplink") ?: continue
            val info = deeplinkInfo(link) ?: continue
            if (info.type != "albums" || !seen.add(info.id)) continue
            val title = textValue(item.opt("primaryText"))
            if (title.isBlank()) continue
            val artist = textValue(item.opt("secondaryText1")).ifBlank { textValue(item.opt("secondaryText")) }
            val album = Album(
                provider = id, id = info.id, title = title,
                artists = if (artist.isBlank()) emptyList() else listOf(artist),
                cover = fixImage(item.optStringOrNull("image")),
                webUrl = albumUrl(info.id),
            )
            out.add(Hit(Hit.Kind.ALBUM, album.title, album.artists.joinToString(", "), album.cover, Links.album(id, album.id), album = album))
        }
        return out
    }

    override suspend fun track(id: String): Track? {
        val page = displayCatalogTrack(id) ?: return null
        val tracks = tracksFrom(page, 50)
        return tracks.firstOrNull { it.id == id } ?: tracks.firstOrNull()
    }

    /** extractSchemaOrg(page, "MusicAlbum"): the album-level schema, preferring one with tracks. */
    private fun schemaAlbum(page: Page): JSONObject? {
        val blobs = ArrayList<String>()
        fun walk(obj: Any?, depth: Int) {
            if (obj == null || depth > 25) return
            when (obj) {
                is JSONObject -> for (k in obj.keys()) {
                    val v = obj.opt(k)
                    if (k == "innerHTML" && v is String && v.contains("MusicAlbum")) blobs.add(v) else walk(v, depth + 1)
                }
                is JSONArray -> for (i in 0 until obj.length()) walk(obj.opt(i), depth + 1)
            }
        }
        walk(page.data, 0)
        var first: JSONObject? = null
        for (blob in blobs) {
            val candidates = ArrayList<JSONObject>()
            val text = blob.trim()
            runCatching {
                if (text.startsWith("[")) JSONArray(text).let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let(candidates::add) }
                else JSONObject(text).let { o ->
                    candidates.add(o)
                    o.optJSONArray("@graph")?.let { g -> for (i in 0 until g.length()) g.optJSONObject(i)?.let(candidates::add) }
                }
            }
            for (c in candidates) {
                if (c.optString("@type") != "MusicAlbum") continue
                if (c.has("track") || c.has("numTracks")) return c
                if (first == null) first = c
            }
        }
        return first
    }

    private fun schemaTrackId(item: JSONObject): String? {
        val ref = item.optStringOrNull("@id") ?: item.optStringOrNull("url") ?: return null
        queryParam(ref, "trackAsin")?.let { normalizeAsin(it)?.let { a -> return a } }
        val m = Regex("""/tracks/([^/?#]+)""").find(ref) ?: return null
        return normalizeAsin(m.groupValues[1])
    }

    private fun isoSeconds(iso: String?): Int {
        val m = Regex("""PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?""").find(iso ?: "") ?: return 0
        return (m.groupValues[1].toIntOrNull() ?: 0) * 3600 + (m.groupValues[2].toIntOrNull() ?: 0) * 60 + (m.groupValues[3].toIntOrNull() ?: 0)
    }

    override suspend fun album(id: String): Album? {
        val page = showHome("/albums/$id") ?: return null
        val schema = schemaAlbum(page)
        val schemaTracks = ArrayList<JSONObject>()
        when (val t = schema?.opt("track")) {
            is JSONArray -> for (i in 0 until t.length()) t.optJSONObject(i)?.let(schemaTracks::add)
            is JSONObject -> schemaTracks.add(t)
        }
        val schemaArtist = schema?.optJSONObject("byArtist")?.optStringOrNull("name") ?: ""
        var tracks = tracksFrom(page, 300).map { it.copy(albumId = id, albumName = null) }
        if (tracks.isEmpty() && schemaTracks.isNotEmpty()) {
            // No track rows on the page: build the list from the schema.org copy.
            tracks = schemaTracks.mapNotNull { st ->
                val asin = schemaTrackId(st) ?: return@mapNotNull null
                val secs = isoSeconds(st.optStringOrNull("duration"))
                Track(
                    provider = this.id, id = asin,
                    title = st.optStringOrNull("name") ?: return@mapNotNull null,
                    artists = listOfNotNull(st.optJSONObject("byArtist")?.optStringOrNull("name") ?: schemaArtist.ifBlank { null }),
                    albumId = id,
                    durationMs = if (secs > 0) secs * 1000L else null,
                    isrc = st.optStringOrNull("isrcCode"),
                    webUrl = trackUrl(asin),
                )
            }
        } else if (schemaTracks.isNotEmpty()) {
            // Rows carry no ISRC; the schema does, and it is what lets another source find the song.
            val byId = schemaTracks.mapNotNull { st -> schemaTrackId(st)?.let { it to st } }.toMap()
            tracks = tracks.map { t ->
                val st = byId[t.id] ?: return@map t
                t.copy(isrc = t.isrc ?: st.optStringOrNull("isrcCode"))
            }
        }
        Diag.log("amazon", "album $id: ${tracks.size} tracks (${schemaTracks.size} in schema)")
        val title = sanitize(findFirst(page.data, "headerText"))
            .ifBlank { schema?.optStringOrNull("name") ?: "" }
            .ifBlank { sanitize(findFirst(page.data, "primaryText")) }
        val artist = sanitize(findFirst(page.data, "headerPrimaryText"))
            .ifBlank { schemaArtist }
            .ifBlank { sanitize(findFirst(page.data, "secondaryText")) }
        var cover = fixImage(findFirst(page.data, "headerImage") as? String)
        if (cover == null) cover = fixImage(findFirst(page.data, "backgroundImage") as? String)
        if (title.isBlank() && tracks.isEmpty()) return null
        val withCover = tracks.map { if (it.cover == null) it.copy(cover = cover) else it }
        return Album(
            provider = this.id,
            id = id,
            title = title,
            artists = if (artist.isBlank()) emptyList() else listOf(artist),
            cover = cover,
            trackCount = withCover.size,
            tracks = withCover,
            webUrl = albumUrl(id),
        )
    }

    override suspend fun artist(id: String): Artist? = null

    private fun sanitize(value: Any?): String {
        val text = textValue(value)
        if (text.isBlank()) return ""
        if (text.startsWith("http://") || text.startsWith("https://")) return ""
        return text.trim()
    }

    // ------------------------------------------------------------------ streams

    private fun codecFor(quality: String): String = when (quality.uppercase()) {
        "OPUS" -> "opus"
        "EAC3", "DOLBY", "DOLBY_ATMOS" -> "eac3"
        "AC4" -> "ac4"
        else -> "flac"
    }

    override suspend fun streams(track: Track, quality: String): List<Stream> {
        val asin = normalizeAsin(track.id) ?: return emptyList()
        val codec = codecFor(quality)
        val ticket = Zarz.ticket(APP_VERSION, "amazeamazeamaze", "track", asin)
        val body = JSONObject().put("asin", asin).put("codec", codec)
        val res = Zarz.signedFetch(APP_VERSION, "POST", DOWNLOAD_PATH, body.toString(), mapOf("X-Zarz-Ticket" to ticket))
        // callZarzMedia(): the answer is usually an array whose first element carries `audio`.
        val text = res.body.trim()
        val entry = when {
            text.startsWith("[") -> runCatching { JSONArray(text).optJSONObject(0) }.getOrNull()
            else -> jsonOrNull(text)?.let { p ->
                when {
                    p.has("audio") -> p
                    else -> p.optJSONArray("data")?.optJSONObject(0) ?: p.optJSONObject("data")
                }
            }
        }
        val audio = entry?.optJSONObject("audio")
        val url = audio?.optStringOrNull("url")
        if (audio == null || url == null) {
            Diag.log("amazon", "download answer had no audio URL: ${text.take(200)}")
            return emptyList()
        }
        val key = audio.optStringOrNull("key")
        if (!key.isNullOrBlank()) {
            // Amazon returns the key when the asset must be decrypted client-side; a
            // player cannot use that file, so say so instead of serving noise.
            Diag.log("amazon", "Amazon gave an encrypted file; CloudStream can only decrypt DASH streams, so it cannot play this. Pick TIDAL, Qobuz or Deezer as the download source.")
            return emptyList()
        }
        val actualCodec = audio.optStringOrNull("codec") ?: codec
        val mime = when (actualCodec.lowercase()) {
            "flac" -> "audio/flac"
            "opus" -> "audio/opus"
            "eac3" -> "audio/eac3"
            "ac4" -> "audio/mp4"
            else -> "audio/mp4"
        }
        return listOf(Stream(url, actualCodec.uppercase(), mime, provider = id))
    }
}
