package com.watchuu.fantastic

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URI
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject

class FantasticProvider : MainAPI() {
    override var mainUrl = "https://music.1412.tech"
    override var name = "Fantastic"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Music)

    private val headers get() = mapOf("Referer" to "$mainUrl/", "Origin" to mainUrl, "User-Agent" to "Mozilla/5.0")
    private val kinds = listOf("song", "album", "playlist", "artist")
    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
    private fun JSONObject.string(key: String): String = optString(key, "").takeUnless { it == "null" }.orEmpty()
    private fun cover(item: JSONObject): String? =
        item.string("cover").ifBlank { item.string("avatar") }.ifBlank { null }
            ?.replace("{w}", "500")?.replace("{h}", "500")
    private fun artists(item: JSONObject) = item.optJSONArray("artists").objects()
        .map { it.string("name") }.filter { it.isNotBlank() }.joinToString(", ")

    private suspend fun json(path: String): JSONObject {
        val response = app.get("$mainUrl/qq/$path", headers = headers, timeout = 60L)
        if (response.code !in 200..299) throw ErrorLoadingException("Fantastic: HTTP ${response.code}")
        return try { JSONObject(response.text) } catch (_: org.json.JSONException) {
            throw ErrorLoadingException("Fantastic: invalid API response")
        }
    }

    private fun itemUrl(kind: String, id: String) = "$mainUrl/qq/$kind/$id"
    private fun idOf(item: JSONObject, kind: String) = item.string(if (kind == "artist" || kind == "album") "mid" else "id")
    private fun card(item: JSONObject, kind: String): SearchResponse? {
        val id = idOf(item, kind).takeIf { it.matches(Regex("[A-Za-z0-9]+")) } ?: return null
        val title = item.string("name").takeIf { it.isNotBlank() } ?: return null
        val label = listOf(title, artists(item)).filter { it.isNotBlank() }.joinToString(" - ")
        return if (kind == "song") newMovieSearchResponse(label, itemUrl(kind, id), TvType.Music) {
            posterUrl = cover(item)
        } else newTvSeriesSearchResponse(label, itemUrl(kind, id), TvType.Music) {
            posterUrl = cover(item)
        }
    }

    override val mainPage = mainPageOf(
        "Mandopop" to "QQ Music · Mandopop",
        "Pop" to "QQ Music · Pop",
        "Rock" to "QQ Music · Rock",
        "Classical" to "QQ Music · Classical",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val result = json("search?q=${encode(request.data)}&type=song&limit=30&offset=${(page - 1) * 30}")
        val songs = result.optJSONArray("songs").objects()
        return newHomePageResponse(HomePageList(request.name, songs.mapNotNull { card(it, "song") }),
            page * 30 < result.optInt("total"))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        return kinds.flatMap { kind ->
            json("search?q=${encode(query.trim())}&type=$kind&limit=20")
                .optJSONArray("${kind}s").objects().mapNotNull { card(it, kind) }
        }
    }

    private fun parseUrl(url: String): Pair<String, String> {
        val uri = try { URI(url) } catch (_: Exception) { throw ErrorLoadingException("Fantastic: invalid URL") }
        val parts = uri.path.orEmpty().trim('/').split('/')
        if (uri.host != URI(mainUrl).host || parts.size != 3 || parts[0] != "qq" ||
            parts[1] !in kinds || !parts[2].matches(Regex("[A-Za-z0-9]+"))) {
            throw ErrorLoadingException("Fantastic: unsupported URL")
        }
        return parts[1] to parts[2]
    }

    override suspend fun load(url: String): LoadResponse {
        val (kind, id) = parseUrl(url)
        val first = json("$kind/$id" + if (kind == "playlist") "?limit=100&offset=0" else "")
        if (kind == "song") {
            return newMovieLoadResponse(first.string("name"), url, TvType.Music, itemUrl(kind, id)) {
                posterUrl = cover(first)
                plot = listOf(artists(first), first.string("album")).filter { it.isNotBlank() }.joinToString(" | ")
                duration = (first.optLong("durationMs") / 60000).toInt().takeIf { it > 0 }
            }
        }
        val info = first.optJSONObject(kind) ?: JSONObject().put("name", "QQ Music playlist")
        val songs = first.optJSONArray("songs").objects().toMutableList()
        var more = if (kind == "artist") first.optBoolean("moreSongs") else first.optBoolean("more")
        var offset = songs.size
        while (more && kind != "album" && offset <= 10000) {
            val next = json("$kind/$id${if (kind == "artist") "/songs" else ""}?limit=100&offset=$offset")
            val rows = next.optJSONArray("songs").objects()
            if (rows.isEmpty()) break
            songs.addAll(rows)
            offset += rows.size
            more = next.optBoolean("more")
        }
        val episodes = songs.distinctBy { it.string("id") }.mapIndexedNotNull { index, song ->
            val songId = idOf(song, "song").takeIf { it.matches(Regex("[A-Za-z0-9]+")) } ?: return@mapIndexedNotNull null
            newEpisode(itemUrl("song", songId)) {
                name = song.string("name")
                posterUrl = cover(song)
                episode = index + 1
                season = 1
            }
        }
        return newTvSeriesLoadResponse(info.string("name"), url, TvType.Music, episodes) {
            posterUrl = cover(info)
            plot = info.string("description").ifBlank { artists(info) }
            recommendations = first.optJSONArray("albums").objects().mapNotNull { card(it, "album") }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val (kind, id) = parseUrl(data)
        if (kind != "song") return false
        val available = json("song/$id/qualities?basic=1").optJSONArray("qualities").objects()
        val qualityOrder = listOf("RS", "SQ", "HQ", "AAC192", "OGG192", "MP3128", "OGG96", "WEB", "AAC48")
        val playable = available.filter { it.optBoolean("available") && it.string("quality") in qualityOrder }
            .sortedBy { qualityOrder.indexOf(it.string("quality")) }
        playable.forEach { row ->
            callback(newExtractorLink(name, "$name · ${row.string("quality")} · ${row.string("format")}",
                "$mainUrl/qq/song/$id/download?quality=${row.string("quality")}", ExtractorLinkType.VIDEO) {
                referer = "$mainUrl/"
                quality = Qualities.Unknown.value
                headers = this@FantasticProvider.headers
            })
        }
        return playable.isNotEmpty()
    }
}
