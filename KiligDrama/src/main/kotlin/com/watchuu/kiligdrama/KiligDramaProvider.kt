package com.watchuu.kiligdrama

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLEncoder
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * KiligDrama (kiligdrama.com) - a Next.js site that aggregates vertical short dramas
 * (ReelShort, DramaBox, ShortMax, GoodShort, ToonScroll, ...).
 *
 * Built from a captured network log + the site's own player bundle:
 *  - catalog  : /en/genre/{slug}[/page/N]        (cards: a[href*=/drama/][aria-label])
 *  - search   : /en/search?q=...&page=N
 *  - series   : /en/drama/{slug}                  (JSON-LD TVSeries + bookId / firstSerial in the RSC data)
 *  - episode  : /en/drama/{slug}/episode-{n}      (serial = firstSerial + n - 1)
 *  - streams  : GET /api/episode-source/{bookId}/{serial}?lang=en
 *               -> {descriptor:{chain:[{type,source,enc,encByQuality}], subtitle:{enc}}}
 *               every "enc" is base64(iv[12] + AES-256-GCM ciphertext) with a static key from the player.
 *  - /api/play is only a view-count beacon, not needed.
 *
 * Stream types (same handling as the site's hls.js loaders):
 *  - mp4 / dramapops-mp4 : direct file, no referer
 *  - hls                 : direct, tqshort/toonshort hosts go through the b-cdn hls proxy
 *  - dramawave / storyreel : through the dramadizilerim media proxy (b-cdn as backup)
 *  - hls-enc (ShortTV)    : same proxies, plus KiligProxy (localhost) to unscramble the segments
 *  - goodshort           : acfs*.goodreels.com -> v2-akm.goodreels.com through the dramadizilerim ?url= proxy
 */
class KiligDramaProvider : MainAPI() {
    override var mainUrl = KiligSettings.siteUrl
    override var name = "KiligDrama"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.AsianDrama)

    private val locale = "en"

    // static key the site's player uses to decrypt episode sources
    private val sourceKey = SecretKeySpec(
        Base64.decode("QC6Ir2trghxRAyyyWZEOEFR4GgLhnfQ4A19I3QBlQkc=", Base64.DEFAULT), "AES"
    )

    private val mediaProxy = "https://cdn.dramadizilerim.xyz/"
    private val mediaProxyQ = "https://cdn.dramadizilerim.xyz/?url="
    private val bcdnProxy = "https://hls-proxy-i4ic7.b-cdn.net/?url="

    override val mainPage = mainPageOf(
        "new-releases" to "New This Week",
        "popular" to "Popular",
        "revenge" to "Revenge & Payback",
        "romance" to "Romance & Sweet Love",
        "ceo-billionaire" to "CEO & Billionaire",
        "hidden-identity" to "Hidden Identity",
        "second-chance" to "Second Chance & Rebirth",
        "rise-comeback" to "Rise & Comeback",
        "enemies-to-lovers" to "Enemies to Lovers",
        "marriage-love" to "Marriage & Love",
        "rich-family" to "Rich Family & Elite",
        "strong-women" to "Strong Women",
        "powerful-hero" to "Powerful Hero",
        "family-drama" to "Family Drama",
        "modern-drama" to "Modern Drama",
        "historical" to "Historical & Period",
        "fantasy" to "Fantasy & Supernatural",
        "adored" to "Adored",
        "anime" to "Anime",
    )

    // ---------------------------------------------------------------- networking

    private suspend fun getText(url: String, referer: String = "$mainUrl/$locale"): String =
        KiligBrowser.get(url, referer)

    private suspend fun getDoc(url: String, referer: String = "$mainUrl/$locale"): Document =
        Jsoup.parse(getText(url, referer), url)

    // ---------------------------------------------------------------- parsing helpers

    private fun posterOf(el: org.jsoup.nodes.Element?): String? {
        val img = el?.selectFirst("img") ?: return null
        val src = img.attr("src").ifBlank { null }
            ?: img.attr("srcset").split(",").lastOrNull()?.trim()?.substringBefore(" ")?.ifBlank { null }
            ?: img.attr("data-src").ifBlank { null }
            ?: return null
        return fixUrl(src.replace("&amp;", "&"))
    }

    private fun parseCards(doc: Document, exclude: String? = null): List<SearchResponse> {
        val seen = HashSet<String>()
        val out = ArrayList<SearchResponse>()
        for (a in doc.select("a[href*=/drama/][aria-label]")) {
            val href = a.attr("href")
            if (href.contains("/episode-")) continue
            val url = fixUrl(href).trimEnd('/')
            if (url == exclude || !seen.add(url)) continue
            val title = a.attr("aria-label").trim()
            if (title.isBlank()) continue
            // card text looks like "EP 50 8.1 Title ..." (catalog) or "English · 30 Episodes" (search)
            val text = a.text() + " " + (a.parent()?.text() ?: "")
            val eps = Regex("""\bEP\s*(\d{1,4})\b""", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""(\d{1,4})\s*Episodes?\b""", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.toIntOrNull()
            val rating = Regex("""(?<![\d.])([1-9]\.\d|10\.0)(?![\d.])""").find(a.text())?.groupValues?.get(1)
            out.add(newAnimeSearchResponse(title, url, TvType.AsianDrama) {
                this.posterUrl = posterOf(a)
                if (eps != null && eps > 0) addSub(eps)
                rating?.let { r -> this.score = Score.from10(r) }
            })
        }
        return out
    }

    // ---------------------------------------------------------------- catalog

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = "$mainUrl/$locale/genre/${request.data}"
        val url = if (page <= 1) base else "$base/page/$page"
        val doc = getDoc(url)
        val items = parseCards(doc)
        val hasNext = doc.select("a[href\$=/page/${page + 1}]").isNotEmpty()
        return newHomePageResponse(HomePageList(request.name, items), hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query, "UTF-8")
        val out = ArrayList<SearchResponse>()
        for (page in 1..2) {
            val url = if (page == 1) "$mainUrl/$locale/search?q=$q" else "$mainUrl/$locale/search?q=$q&page=$page"
            val doc = try {
                getDoc(url)
            } catch (e: Throwable) {
                if (page == 1) throw e else break
            }
            val items = parseCards(doc)
            out.addAll(items.filter { n -> out.none { it.url == n.url } })
            if (items.isEmpty() || doc.select("a[href*=page=${page + 1}]").isEmpty()) break
        }
        return out
    }

    // ---------------------------------------------------------------- details

    private fun ldSeries(doc: Document): JSONObject? {
        for (s in doc.select("script[type=application/ld+json]")) {
            try {
                val root = JSONObject(s.data())
                val graph = root.optJSONArray("@graph")
                if (graph != null) {
                    for (i in 0 until graph.length()) {
                        val o = graph.optJSONObject(i) ?: continue
                        if (o.optString("@type") == "TVSeries") return o
                    }
                } else if (root.optString("@type") == "TVSeries") return root
            } catch (_: Throwable) {
            }
        }
        return null
    }

    // values inside the (escaped) Next.js RSC payload: \"bookId\":\"123\"  /  \"firstSerial\":1
    private fun rscString(html: String, key: String): String? =
        Regex("""\\?"$key\\?"\s*:\s*\\?"([^"\\]+)""").find(html)?.groupValues?.get(1)

    private fun rscInt(html: String, key: String): Int? =
        Regex("""\\?"$key\\?"\s*:\s*(\d+)""").find(html)?.groupValues?.get(1)?.toIntOrNull()

    override suspend fun load(url: String): LoadResponse {
        val seriesUrl = url.substringBefore("/episode-").trimEnd('/')
        var html = getText(seriesUrl)
        val doc = Jsoup.parse(html, seriesUrl)
        val ld = ldSeries(doc)

        val title = ld?.optString("name")?.ifBlank { null }
            ?: doc.selectFirst("h1")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore(":")
            ?: throw ErrorLoadingException("KiligDrama: no title")
        val plot = ld?.optString("description")?.ifBlank { null }
            ?: doc.selectFirst("meta[name=description]")?.attr("content")
        val poster = ld?.optString("image")?.ifBlank { null }
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")

        val maxLinked = doc.select("a[href*=/episode-]").mapNotNull {
            it.attr("href").substringAfterLast("/episode-").substringBefore("?").toIntOrNull()
        }.maxOrNull() ?: 0
        val count = maxOf(ld?.optInt("numberOfEpisodes", 0) ?: 0, maxLinked)

        var bookId = rscString(html, "bookId")
        var first = rscInt(html, "firstSerial")
        if (bookId == null && count > 0) {
            // older pages only carry it on the watch page
            html = getText("$seriesUrl/episode-1", seriesUrl)
            bookId = rscString(html, "bookId")
            first = first ?: rscInt(html, "firstSerial")
        }
        val firstSerial = first ?: 1

        // exact serial numbers + thumbnails from the RSC episode list
        //   {"chapter_id":"..","serial_number":1,"cover":"https://..."}
        val serials = ArrayList<Int>()
        val covers = HashMap<Int, String>()
        Regex("""\\?"serial_number\\?"\s*:\s*(\d+)(?:\s*,\s*\\?"cover\\?"\s*:\s*\\?"(https?:[^"\\]+))?""")
            .findAll(html).forEach { m ->
                val sn = m.groupValues[1].toIntOrNull() ?: return@forEach
                if (!serials.contains(sn)) serials.add(sn)
                m.groupValues.getOrNull(2)?.ifBlank { null }?.let { covers[sn] = it }
            }
        serials.sort()
        val total = maxOf(count, serials.size)

        val episodes = (1..total).map { n ->
            val epUrl = "$seriesUrl/episode-$n"
            val serial = serials.getOrNull(n - 1) ?: (firstSerial + n - 1)
            newEpisode("${bookId ?: ""}|$serial|$epUrl") {
                this.name = "Episode $n"
                this.season = 1
                this.episode = n
                this.posterUrl = covers[serial] ?: poster
            }
        }

        val source = doc.select("a[href*=/studio/]").firstOrNull()?.text()?.trim()
        return newTvSeriesLoadResponse(title, seriesUrl, TvType.AsianDrama, episodes) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = listOfNotNull(source?.ifBlank { null }, "Short Drama")
            this.recommendations = parseCards(doc, seriesUrl)
        }
    }

    // ---------------------------------------------------------------- links

    private fun decrypt(b64: String?): String? {
        if (b64.isNullOrBlank()) return null
        return try {
            val raw = Base64.decode(b64, Base64.DEFAULT)
            if (raw.size <= 12) return null
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, sourceKey, GCMParameterSpec(128, raw, 0, 12))
            String(c.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8).trim()
        } catch (_: Throwable) {
            null
        }
    }

    private fun enc(u: String) = URLEncoder.encode(u, "UTF-8")

    private fun host(u: String) = u.substringAfter("://").substringBefore("/").substringBefore(":").lowercase()

    /** https://host/path -> https://cdn.dramadizilerim.xyz/https/host/path (site's mediaProxyWrap) */
    private fun mediaWrap(u: String): String {
        if (u.startsWith(mediaProxy) || u.startsWith(bcdnProxy)) return u
        val m = Regex("^(https?)://(.+)$", RegexOption.IGNORE_CASE).find(u) ?: return u
        return "$mediaProxy${m.groupValues[1].lowercase()}/${m.groupValues[2]}"
    }

    private fun qualityOf(key: String?, url: String): Int {
        key?.let { k -> Regex("""(\d{3,4})""").find(k)?.groupValues?.get(1)?.toIntOrNull()?.let { return it } }
        Regex("""[_/\-.](2160|1440|1080|720|540|480|360)[p_/\-.]""").find(url)?.groupValues?.get(1)?.toIntOrNull()
            ?.let { return it }
        return Qualities.Unknown.value
    }

    private data class Cand(val label: String, val url: String, val type: ExtractorLinkType, val quality: Int)

    private fun candidates(type: String, source: String, url: String, qKey: String?): List<Cand> {
        val q = qualityOf(qKey, url)
        val tag = if (qKey != null) " $qKey" else ""
        val isHls = url.contains(".m3u8", true)
        return when (type) {
            "mp4", "dramapops-mp4" ->
                listOf(Cand("$source$tag", url, if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO, q))

            "hls" -> {
                val h = host(url)
                val wrapped = if (h == "tqshort.com" || h.endsWith(".tqshort.com") ||
                    h == "toonshort.com" || h.endsWith(".toonshort.com")
                ) bcdnProxy + enc(url) else url
                listOf(Cand("$source$tag", wrapped, ExtractorLinkType.M3U8, q))
            }

            // ShortTV segments are scrambled -> unscrambled on the fly by the local proxy
            "hls-enc" -> listOf(
                Cand("$source$tag", KiligProxy.playlist(mediaWrap(url)), ExtractorLinkType.M3U8, q),
                Cand("$source$tag (backup)", KiligProxy.playlist(bcdnProxy + enc(url)), ExtractorLinkType.M3U8, q),
            )

            "dramawave", "storyreel" -> listOf(
                Cand("$source$tag", mediaWrap(url), ExtractorLinkType.M3U8, q),
                Cand("$source$tag (backup)", bcdnProxy + enc(url), ExtractorLinkType.M3U8, q),
            )

            "goodshort" -> {
                val swapped = if (Regex("""(^|\.)acfs\d*\.goodreels\.com$""").containsMatchIn(host(url)))
                    url.replaceFirst(host(url), "v2-akm.goodreels.com") else url
                listOf(
                    Cand("$source$tag", mediaProxyQ + enc(swapped), ExtractorLinkType.M3U8, q),
                    Cand("$source$tag (direct)", swapped, ExtractorLinkType.M3U8, q),
                )
            }

            else -> listOf(
                Cand("$source$tag", url, if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO, q)
            )
        }
    }

    /** Turns one /api/episode-source JSON into links. Returns how many links were sent. */
    private suspend fun emitFromJson(
        body: String,
        seen: HashSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        notes: StringBuilder,
    ): Int {
        val json = try {
            JSONObject(body.trim())
        } catch (_: Throwable) {
            notes.append("api answer is not JSON (").append(body.take(60).replace("\n", " ")).append("); ")
            return 0
        }
        val desc = json.optJSONObject("descriptor") ?: json
        val chain = desc.optJSONArray("chain")
        if (chain == null || chain.length() == 0) {
            notes.append("api has no chain (").append(json.optString("error", json.optString("message")).take(60)).append("); ")
            return 0
        }
        val defaultSource = desc.optString("source").ifBlank { name }
        var n = 0
        var decFail = 0
        for (i in 0 until chain.length()) {
            val item = chain.optJSONObject(i) ?: continue
            val type = item.optString("type").lowercase()
            val source = item.optString("source").ifBlank { defaultSource }
            if (type == "overlay") continue

            val urls = ArrayList<Pair<String?, String>>()
            item.optJSONObject("encByQuality")?.let { byQ ->
                byQ.keys().forEach { k ->
                    val d = decrypt(byQ.optString(k))
                    if (d != null) urls.add(k to d) else decFail++
                }
            }
            item.optString("enc").ifBlank { null }?.let { e ->
                val d = decrypt(e)
                if (d == null) decFail++ else if (urls.none { it.second == d }) urls.add(null to d)
            }
            for (k in listOf("url", "src", "source_url")) {
                item.optString(k).takeIf { it.startsWith("http") }?.let { urls.add(null to it) }
            }

            for ((qKey, u) in urls) {
                if (!u.startsWith("http")) continue
                val cands = try {
                    candidates(type, source, u, qKey)
                } catch (e: Throwable) {
                    notes.append("$type: ${e.javaClass.simpleName}; ")
                    listOf(Cand("$source ${qKey ?: ""}".trim(), u, if (u.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO, qualityOf(qKey, u)))
                }
                for (c in cands) if (emit(c, seen, callback)) n++
            }
        }
        if (decFail > 0) notes.append("decrypt failed x").append(decFail).append("; ")

        desc.optJSONObject("subtitle")?.let { sub ->
            val u = decrypt(sub.optString("enc")) ?: sub.optString("url").takeIf { it.startsWith("http") }
            if (u != null && u.startsWith("http")) {
                val code = sub.optString("language").ifBlank { sub.optString("lang") }
                val lang = if (code.isBlank() || code.startsWith("en", true)) "English" else code
                subtitleCallback.invoke(newSubtitleFile(lang, u))
            }
        }
        return n
    }

    private suspend fun emit(c: Cand, seen: HashSet<String>, callback: (ExtractorLink) -> Unit): Boolean {
        if (!seen.add(c.url)) return false
        // the site's proxies only answer requests coming from kiligdrama.com,
        // the original CDNs are fetched with no referrer (referrerPolicy: no-referrer)
        val proxied = c.url.startsWith(mediaProxy) || c.url.startsWith(bcdnProxy) ||
            (c.url.contains("127.0.0.1") && (c.url.contains("dramadizilerim") || c.url.contains("b-cdn")))
        val ua = mapOf("User-Agent" to KiligBrowser.userAgent())
        callback.invoke(
            newExtractorLink(name, "$name - ${c.label}", c.url, c.type) {
                this.referer = if (proxied) "$mainUrl/" else ""
                this.headers = if (proxied) ua + ("Origin" to mainUrl) else ua
                this.quality = c.quality
            }
        )
        return true
    }

    /**
     * The episode page embeds the first source in its RSC data:
     *   \"sourceFirst\":{\"source\":\"ToonScroll\",\"chain\":[{\"enc\":\"...\",\"type\":\"mp4\"}],\"subtitle\":null}
     * Returns it as plain JSON (same shape as the API descriptor).
     */
    private fun pageDescriptor(html: String): String? {
        val i = html.indexOf("sourceFirst").takeIf { it >= 0 } ?: return null
        val start = html.indexOf('{', i).takeIf { it >= 0 && it - i < 12 } ?: return null
        val raw = html.substring(start, minOf(html.length, start + 20_000))
        val sb = StringBuilder()
        var depth = 0
        var k = 0
        while (k < raw.length) {
            var ch = raw[k]
            if (ch == '\\' && k + 1 < raw.length) { // undo the JS-string escaping
                ch = raw[k + 1]; k++
                if (ch == 'n') ch = '\n'
            }
            sb.append(ch)
            if (ch == '{') depth++ else if (ch == '}') { depth--; if (depth == 0) return sb.toString() }
            k++
        }
        return null
    }

    /** Link that the site's own player loaded (sniffed from the episode page). */
    private fun sniffedCand(u: String): Cand {
        val isHls = u.substringBefore("?").endsWith(".m3u8", true) || u.contains(".m3u8", true)
        val type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
        val q = qualityOf(null, u)
        return if (u.contains("/hls-encrypted/")) Cand("Player (ShortTV)", KiligProxy.playlist(u), type, q)
        else Cand("Player", u, type, q)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val notes = StringBuilder()
        val parts = data.split("|", limit = 3)
        var bookId = parts.getOrNull(0)?.ifBlank { null }
        var serial = parts.getOrNull(1)?.toIntOrNull()
        val epUrl = parts.getOrNull(2)?.ifBlank { null }
            ?: data.takeIf { it.startsWith("http") } ?: "$mainUrl/$locale"
        KiligProxy.userAgent = KiligBrowser.userAgent()
        val seen = HashSet<String>()
        var found = 0

        // 1) the site's API (same request the player makes)
        try {
            if (bookId == null || serial == null) {
                val page = getText(epUrl)
                bookId = bookId ?: rscString(page, "bookId")
                serial = serial ?: rscInt(page, "serial") ?: rscInt(page, "serialNumber")
                    ?: epUrl.substringAfterLast("/episode-").toIntOrNull()
            }
            if (bookId != null && serial != null) {
                val api = "$mainUrl/api/episode-source/$bookId/$serial?lang=$locale"
                val body = getText(api, epUrl)
                found += emitFromJson(body, seen, subtitleCallback, callback, notes)
            } else notes.append("no bookId on page; ")
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            notes.append("api: ").append(e.message?.take(80) ?: e.javaClass.simpleName)
                .append(" [").append(KiligBrowser.lastTrace.take(160)).append("]; ")
        }

        // 2) the episode page itself carries the first source (same route as the catalog pages)
        if (found == 0) {
            try {
                val page = getText(epUrl, epUrl.substringBefore("/episode-"))
                val d = pageDescriptor(page)
                if (d != null) found += emitFromJson(d, seen, subtitleCallback, callback, notes)
                else notes.append("no source in episode page; ")
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                notes.append("page: ").append(e.message?.take(80) ?: e.javaClass.simpleName).append("; ")
            }
        }

        // 3) open the episode page like a viewer and catch what the player loads
        if (found == 0 && KiligSettings.sniffFallback) {
            try {
                val sn = KiligBrowser.sniff(epUrl)
                for (b in sn.apiBodies) found += emitFromJson(b, seen, subtitleCallback, callback, notes)
                if (found == 0) for (u in sn.video) if (emit(sniffedCand(u), seen, callback)) found++
                // last resort: first media request (the player loads the current episode before preloading the next)
                if (found == 0) sn.media.firstOrNull()?.let { if (emit(sniffedCand(it), seen, callback)) found++ }
                if (found == 0) notes.append("player page gave nothing; ")
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                notes.append("sniff: ").append(e.javaClass.simpleName).append("; ")
            }
        }

        KiligSettings.lastLinkReport = if (found > 0) "OK: $found link(s) for $epUrl  $notes" else "FAILED for $epUrl: $notes"
        if (found == 0) KiligSettings.toast("KiligDrama: no links - $notes".take(300))
        return found > 0
    }
}
