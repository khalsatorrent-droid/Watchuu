package com.watchuu.monochrome

/**
 * Monochrome (monochrome.st) — music extension for Cloudstream.
 *
 * Built from a captured network log.
 *
 * Architecture:
 *  - Catalog/search : tracks.monochrome.st  (no auth)
 *  - Home page      : monochrome.st/editors-picks.json
 *  - Audio stream   : Tidal, proxied via tidal-proxy.monochrome.tf
 *                     Requires a Tidal client-credentials token (public client keys from the site).
 *
 * Payload format:  track|{tidalTrackId}|{releaseId}|{title}|{artist}
 */

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLEncoder

class MonochromeProvider : MainAPI() {
    override var mainUrl = "https://monochrome.st"
    override var name = "Monochrome"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Others)

    private val tracksApi = "https://tracks.monochrome.st"
    private val tidalProxy = "https://tidal-proxy.monochrome.tf"

    // Tidal client credentials (public, taken from the monochrome.st site bundle)
    private val tidalClientId = "txNoH4kkV41MfH25"
    private val tidalClientSecret = "dQjy0MinCEvxi1O4UmxvxWnDjt4cgHBPw8ll6nYBk98="

    private val ua =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    private val mapper = ObjectMapper()

    // Cache the Tidal access token (expires in ~1 day)
    @Volatile private var tidalToken: String? = null
    @Volatile private var tidalTokenExpiry: Long = 0L

    private val baseHeaders = mapOf(
        "Origin" to mainUrl,
        "Referer" to "$mainUrl/",
        "User-Agent" to ua,
        "Accept" to "application/json",
    )

    // ---------------------------------------------------------------- home page

    override val mainPage = mainPageOf(
        "editors-picks" to "Editor's Picks",
        "hot" to "Hot Right Now",
        "new" to "New Releases",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(request.name, emptyList())
        return try {
            val items = when (request.data) {
                "editors-picks" -> fetchEditorsPicks()
                "hot" -> fetchHot()
                else -> emptyList()
            }
            newHomePageResponse(request.name, items)
        } catch (_: Exception) {
            newHomePageResponse(request.name, emptyList())
        }
    }

    private suspend fun fetchEditorsPicks(): List<SearchResponse> {
        val json = app.get("$mainUrl/editors-picks.json", headers = baseHeaders, timeout = 20_000L).text
        val arr = mapper.readTree(json) ?: return emptyList()
        if (!arr.isArray) return emptyList()
        return arr.mapNotNull { node ->
            val id = node.get("id")?.asText() ?: return@mapNotNull null
            val title = node.get("title")?.asText() ?: return@mapNotNull null
            val artist = node.get("artist")?.get("name")?.asText() ?: ""
            val cover = coverUrl(id)
            newMovieSearchResponse("$title — $artist", trackPayload(id, id, title, artist), TvType.Others) {
                this.posterUrl = cover
            }
        }
    }

    private suspend fun fetchHot(): List<SearchResponse> {
        val json = app.get("https://hot.monochrome.tf/", headers = baseHeaders, timeout = 20_000L).text
        val arr = mapper.readTree(json) ?: return emptyList()
        val list = when {
            arr.isArray -> arr
            arr.get("tracks")?.isArray == true -> arr.get("tracks")
            else -> return emptyList()
        }
        return list.mapNotNull { parseTrackNode(it) }
    }

    // ---------------------------------------------------------------- search

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        return try {
            val q = URLEncoder.encode(query.trim(), "UTF-8")
            val json = app.get(
                "$tracksApi/search/tracks?q=$q&limit=20",
                headers = baseHeaders,
                timeout = 20_000L
            ).text
            val root = mapper.readTree(json) ?: return emptyList()
            val arr = root.get("tracks") ?: return emptyList()
            arr.mapNotNull { parseTrackNode(it) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseTrackNode(node: JsonNode): SearchResponse? {
        val trackId = (node.get("trackId") ?: node.get("id"))?.asText() ?: return null
        val releaseId = node.get("releaseId")?.asText() ?: trackId
        val title = node.get("title")?.asText() ?: return null
        val artist = node.get("artists")
            ?.firstOrNull { it.isObject }?.get("name")?.asText()
            ?: node.get("artistName")?.asText()
            ?: ""
        val cover = coverUrl(releaseId)
        return newMovieSearchResponse(
            if (artist.isNotBlank()) "$title — $artist" else title,
            trackPayload(trackId, releaseId, title, artist),
            TvType.Others
        ) {
            this.posterUrl = cover
        }
    }

    // ---------------------------------------------------------------- load

    override suspend fun load(url: String): LoadResponse? {
        val parts = url.split("|")
        if (parts.size < 5 || parts[0] != "track") return null
        val trackId = parts[1]
        val releaseId = parts[2]
        val title = parts[3]
        val artist = parts[4]

        // Try to load richer release details
        val (richTitle, richArtist, cover) = try {
            val json = app.get("$tracksApi/releases/$releaseId", headers = baseHeaders, timeout = 15_000L).text
            val root = mapper.readTree(json)
            val t = root?.get("title")?.asText() ?: title
            val a = root?.get("artists")?.firstOrNull { it.isObject }?.get("name")?.asText() ?: artist
            val c = coverUrl(releaseId)
            Triple(t, a, c)
        } catch (_: Exception) {
            Triple(title, artist, coverUrl(releaseId))
        }

        val displayTitle = if (richArtist.isNotBlank()) "$richTitle — $richArtist" else richTitle

        return newMovieLoadResponse(displayTitle, url, TvType.Others, url) {
            this.posterUrl = cover
            this.backgroundPosterUrl = cover
        }
    }

    // ---------------------------------------------------------------- links

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split("|")
        if (parts.size < 2 || parts[0] != "track") return false
        val trackId = parts[1]

        // Get Tidal access token
        val token = getTidalToken() ?: return false

        // Try each quality in order: highest first
        val qualities = listOf("LOSSLESS", "HIGH", "LOW")
        var found = false

        for (quality in qualities) {
            try {
                val streamJson = app.get(
                    "$tidalProxy/tidal/v1/tracks/$trackId/streamUrl?soundQuality=$quality&countryCode=US",
                    headers = mapOf(
                        "Authorization" to "Bearer $token",
                        "Origin" to mainUrl,
                        "Referer" to "$mainUrl/",
                        "User-Agent" to ua,
                        "Accept" to "application/json",
                    ),
                    timeout = 20_000L
                )
                if (streamJson.code !in 200..299) continue

                val root = mapper.readTree(streamJson.text) ?: continue
                val streamUrl = root.get("url")?.asText()?.takeIf { it.startsWith("http") } ?: continue
                val codec = root.get("codec")?.asText() ?: ""
                val bitDepth = root.get("bitDepth")?.asInt()
                val sampleRate = root.get("sampleRate")?.asInt()

                val qualityLabel = when (quality) {
                    "LOSSLESS" -> if (bitDepth != null && sampleRate != null) "FLAC ${sampleRate / 1000}kHz/${bitDepth}bit" else "FLAC"
                    "HIGH" -> "AAC 320kbps"
                    else -> "AAC 96kbps"
                }

                val linkType = when {
                    streamUrl.contains(".m3u8") -> ExtractorLinkType.M3U8
                    else -> ExtractorLinkType.VIDEO
                }

                callback.invoke(
                    newExtractorLink(name, "$name $qualityLabel", streamUrl, linkType) {
                        this.referer = "$mainUrl/"
                        this.quality = Qualities.Unknown.value
                        this.headers = mapOf(
                            "Authorization" to "Bearer $token",
                            "Origin" to mainUrl,
                            "User-Agent" to ua,
                        )
                    }
                )
                found = true
                // Only emit the best working quality
                break
            } catch (_: Exception) {
                continue
            }
        }

        // Fallback: try the OpenAPI v2 playback manifest endpoint
        if (!found) {
            try {
                val manifestJson = app.get(
                    "$tidalProxy/openapi/v2/tracks/$trackId/playbackInfo?audioquality=HIGH&playbackmode=STREAM&assetpresentation=FULL",
                    headers = mapOf(
                        "Authorization" to "Bearer $token",
                        "Origin" to mainUrl,
                        "Referer" to "$mainUrl/",
                        "User-Agent" to ua,
                        "Accept" to "application/json",
                    ),
                    timeout = 20_000L
                )
                if (manifestJson.code in 200..299) {
                    val root = mapper.readTree(manifestJson.text)
                    val manifest = root?.get("manifest")?.asText()
                    if (manifest != null) {
                        // manifest is base64-encoded XML or JSON; look for URL inside
                        val decoded = android.util.Base64.decode(manifest, android.util.Base64.DEFAULT).decodeToString()
                        val urlMatch = Regex("""https?://[^\s<>"']+""").find(decoded)?.value
                        if (urlMatch != null) {
                            val isM3u8 = urlMatch.contains(".m3u8")
                            callback.invoke(
                                newExtractorLink(name, "$name Stream", urlMatch,
                                    if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                                    this.referer = "$mainUrl/"
                                    this.quality = Qualities.Unknown.value
                                }
                            )
                            found = true
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }

        return found
    }

    // ---------------------------------------------------------------- Tidal token

    private suspend fun getTidalToken(): String? {
        val now = System.currentTimeMillis()
        if (tidalToken != null && now < tidalTokenExpiry - 60_000L) return tidalToken

        return try {
            val credentials = android.util.Base64.encodeToString(
                "$tidalClientId:$tidalClientSecret".toByteArray(),
                android.util.Base64.NO_WRAP
            )
            val resp = app.post(
                "https://auth.tidal.com/v1/oauth2/token",
                headers = mapOf(
                    "Authorization" to "Basic $credentials",
                    "Content-Type" to "application/x-www-form-urlencoded",
                    "Origin" to mainUrl,
                    "User-Agent" to ua,
                ),
                data = mapOf("grant_type" to "client_credentials"),
                timeout = 20_000L
            )
            if (resp.code !in 200..299) return null
            val root = mapper.readTree(resp.text) ?: return null
            val token = root.get("access_token")?.asText() ?: return null
            val expiresIn = root.get("expires_in")?.asLong() ?: 86400L
            tidalToken = token
            tidalTokenExpiry = now + expiresIn * 1000L
            token
        } catch (_: Exception) {
            null
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun coverUrl(releaseId: String): String =
        "$tracksApi/proxy/mi/$releaseId-1A01.jpg"

    private fun trackPayload(trackId: String, releaseId: String, title: String, artist: String): String =
        "track|$trackId|$releaseId|$title|$artist"
}
