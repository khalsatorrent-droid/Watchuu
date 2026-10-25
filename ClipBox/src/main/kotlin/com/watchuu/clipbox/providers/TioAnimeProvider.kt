package com.watchuu.clipbox.providers

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import java.net.URLEncoder
import kotlinx.coroutines.CancellationException

/**
 * TioAnime (tioanime.com) - Spanish anime, ported from StreamFlix Reborn's TioAnimeProvider.
 *
 *  - catalog / search : /directorio?p=N  /  /directorio?q=...   (cards: article.anime)
 *  - detail           : /anime/{slug}    (title, poster, sinopsis; var episodes gives the count)
 *  - episode          : /ver/{slug}-{n}
 *  - servers          : the episode page carries  var videos = [["Voe","url"],["YourUpload","url"]]
 *                       each url is handed to Cloudstream's built-in extractors.
 */
class TioAnimeProvider : MainAPI() {
    override var mainUrl = "https://tioanime.com"
    override var name = "TioAnime"
    override var lang = "es"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    // only these are wired up in the app; the url is passed straight to the matching extractor
    private val supportedServers = setOf("voe", "yourupload")

    private fun abs(u: String): String =
        if (u.startsWith("http")) u else mainUrl.trimEnd('/') + "/" + u.trimStart('/')

    private fun posterOf(el: org.jsoup.nodes.Element?): String? {
        val img = el?.selectFirst("img") ?: return null
        val src = img.attr("src").ifBlank { img.attr("data-src") }.ifBlank { null } ?: return null
        return abs(src)
    }

    // ------------------------------------------------------------------ catalog

    override val mainPage = mainPageOf("$mainUrl/directorio?p=" to "Directorio")

    private fun parseCards(doc: org.jsoup.nodes.Document): List<SearchResponse> {
        val out = ArrayList<SearchResponse>()
        val seen = HashSet<String>()
        for (a in doc.select("article.anime a[href*='/anime/'], .anime a[href*='/anime/']")) {
            val href = a.attr("href")
            val url = abs(href).trimEnd('/')
            if (!seen.add(url)) continue
            val card = a.closest("article.anime") ?: a
            val title = card.selectFirst("h3.title")?.text()?.trim()
                ?: a.selectFirst("h3.title")?.text()?.trim()
                ?: a.attr("title").ifBlank { a.text() }.trim()
            if (title.isBlank()) continue
            out.add(newAnimeSearchResponse(title, url, TvType.Anime) {
                this.posterUrl = posterOf(card)
            })
        }
        return out
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get("${request.data}$page").document
        val items = parseCards(doc)
        return newHomePageResponse(request.name, items, items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")
        return parseCards(app.get("$mainUrl/directorio?q=$q").document)
    }

    // ------------------------------------------------------------------ detail

    /** Pull the first `var <name> = [ ... ];` JSON array out of a page's inline scripts. */
    private fun extractArray(html: String, name: String): String? {
        val i = html.indexOf(name)
        if (i < 0) return null
        val start = html.indexOf('[', i)
        if (start < 0) return null
        var depth = 0
        var k = start
        while (k < html.length) {
            when (html[k]) {
                '[' -> depth++
                ']' -> { depth--; if (depth == 0) return html.substring(start, k + 1) }
            }
            k++
        }
        return null
    }

    private fun slugOf(url: String): String =
        url.trimEnd('/').substringAfterLast("/").substringBefore("?")

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val html = doc.html()
        val slug = slugOf(url)

        val title = doc.selectFirst("h1.title")?.text()?.trim()
            ?: doc.selectFirst(".header .title")?.text()?.trim()
            ?: slug
        val poster = posterOf(doc.selectFirst("article.anime-single") ?: doc.selectFirst(".thumb"))
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")?.ifBlank { null }
        val plot = doc.selectFirst("p.sinopsis")?.text()?.trim()
        val genres = doc.select("p.genres a[href*='genero=']").map { it.text().trim() }.filter { it.isNotBlank() }
        val isMovie = doc.selectFirst(".anime-type-peli") != null

        // var episodes = [[epNum, animeId], ...]  -> number of episodes
        val epCount = extractArray(html, "var episodes")?.let { arr ->
            Regex("""\[\s*(\d+)\s*,""").findAll(arr).map { it.groupValues[1].toInt() }.toList()
        }?.maxOrNull() ?: if (isMovie) 1 else 1

        if (isMovie) {
            return newMovieLoadResponse(title, url, TvType.AnimeMovie, "$mainUrl/ver/$slug-1") {
                this.posterUrl = poster
                this.plot = plot
                this.tags = genres
            }
        }

        val episodes = (1..epCount).map { n ->
            newEpisode("$mainUrl/ver/$slug-$n") {
                this.name = "Episodio $n"
                this.episode = n
                this.season = 1
            }
        }
        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = genres
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    // ------------------------------------------------------------------ links

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = app.get(data).document.html()
        val arr = extractArray(html, "var videos") ?: return false
        // entries look like ["Voe","https://..."]
        val entries = Regex(""""([^"]+)"\s*,\s*"(https?:[^"]+)"""").findAll(arr)
        var found = false
        for (m in entries) {
            val server = m.groupValues[1].trim()
            val link = m.groupValues[2].trim().replace("\\/", "/")
            if (supportedServers.none { it.equals(server, true) || server.contains(it, true) }) continue
            try {
                if (loadExtractor(link, "$mainUrl/", subtitleCallback, callback)) found = true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
        return found
    }
}
