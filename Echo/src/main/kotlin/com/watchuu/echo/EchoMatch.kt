package com.watchuu.echo

/**
 * How Echo Music decides that a track from an add-on is the same song as the YouTube Music one,
 * ported from app/src/main/kotlin/com/music/echo/extensions/ExtensionManager.kt
 * (selectBestMatch, cleanTitleForSearch and the query building in resolveStream).
 *
 * Null means "no reliable replacement": that source is skipped rather than playing other audio.
 */
object EchoMatch {

    /** Shortest token pair accepted as a title match, so "yes" != "yesterday". */
    private const val MIN_TOKEN_MATCH_LEN = 3

    /** Longest suffix a longer token may have over a shorter prefix token ("run"/"running" = 4). */
    private const val MAX_PREFIX_SUFFIX_LEN = 4

    /** Tokens that describe a specific rendition of a song. */
    private val QUALIFIER_TOKENS = setOf(
        "remix", "rmx", "remaster", "remastered", "live", "instrumental",
        "acoustic", "extended", "radio", "edit", "demo", "deluxe", "version",
        "sped", "slowed", "reverb", "cover", "karaoke", "mix", "unplugged",
    )

    /** Credit markers that decorate a title without changing the song. */
    private val CREDIT_TOKENS = setOf("feat", "ft", "featuring", "prod")

    /** Tokens too generic to prove two artists are the same. */
    private val ARTIST_STOP_TOKENS =
        setOf("the", "and", "of", "with", "feat", "ft", "featuring", "prod", "vs")

    private fun normalizeForMatch(value: String): String =
        value.lowercase()
            .replace(Regex("[\\p{Punct}]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun tokenize(value: String): List<String> =
        normalizeForMatch(value).split(" ").filter { it.isNotBlank() }

    private fun artistMatches(want: List<String>, got: List<String>): Boolean {
        val wantSig = want.filter { it.length >= 2 && it !in ARTIST_STOP_TOKENS }
        val gotSig = got.filter { it.length >= 2 && it !in ARTIST_STOP_TOKENS }
        if (wantSig.isEmpty() || gotSig.isEmpty()) return false
        return wantSig.any { it in gotSig }
    }

    private fun coversAllTokens(shorter: List<String>, longer: List<String>): Boolean {
        if (shorter.isEmpty() || longer.isEmpty()) return false
        val (fewer, more) = if (shorter.size <= longer.size) shorter to longer else longer to shorter
        return fewer.all { token -> more.any { tokensMatch(token, it) } }
    }

    private fun tokensMatch(a: String, b: String): Boolean {
        if (a == b) return true
        val shorter = if (a.length <= b.length) a else b
        val longer = if (a.length <= b.length) b else a
        if (shorter.length < MIN_TOKEN_MATCH_LEN) return false
        return longer.startsWith(shorter) &&
            longer.length - shorter.length <= MAX_PREFIX_SUFFIX_LEN
    }

    fun selectBestMatch(
        tracks: List<EchoSources.Track>,
        title: String,
        artist: String,
        durationMs: Long?,
    ): EchoSources.Track? {
        if (tracks.isEmpty()) return null
        val wantTokens = tokenize(title)
        if (wantTokens.isEmpty()) return null
        val wantQualifiers = wantTokens.filter { it in QUALIFIER_TOKENS }.toSet()
        val wantBase = wantTokens.filterNot { it in QUALIFIER_TOKENS || it in CREDIT_TOKENS }
        if (wantBase.isEmpty()) return null
        val artistTokens = tokenize(artist)

        var best: EchoSources.Track? = null
        var bestScore = Int.MIN_VALUE
        for (track in tracks) {
            if (track.id.isBlank() || track.title.isBlank()) continue
            val gotTokens = tokenize(track.title)
            if (gotTokens.isEmpty()) continue
            val gotQualifiers = gotTokens.filter { it in QUALIFIER_TOKENS }.toSet()
            val gotBase = gotTokens.filterNot { it in QUALIFIER_TOKENS || it in CREDIT_TOKENS }
            if (gotBase.isEmpty()) continue
            if (!coversAllTokens(gotBase, wantBase)) continue
            if (!wantQualifiers.all { wanted -> gotQualifiers.any { tokensMatch(it, wanted) } }) continue
            val artistMatched = artistMatches(artistTokens, tokenize(track.artist))
            if (artistTokens.isNotEmpty() && !artistMatched) continue

            var score =
                if (gotBase == wantBase) 100
                else (gotBase.count { got -> wantBase.any { tokensMatch(got, it) } }
                    .toFloat() / maxOf(gotBase.size, wantBase.size) * 60).toInt()
            if (artistMatched) score += 60
            if (gotQualifiers == wantQualifiers) score += 20
            else if (wantQualifiers.isEmpty()) score -= gotQualifiers.size * 5
            val d = track.duration
            if (durationMs != null && durationMs > 0 && d != null && d > 0) {
                val diffMs = kotlin.math.abs(d * 1000L - durationMs)
                if (diffMs <= 8_000L) score += 10 else if (diffMs <= 20_000L) score += 3
            }
            if (score > bestScore) {
                bestScore = score
                best = track
            }
        }
        return best
    }

    fun cleanTitleForSearch(title: String): String =
        title
            .replace(Regex("(?i)\\s*\\((feat\\.|ft\\.|featuring)\\s+[^)]+\\)"), "")
            .replace(Regex("(?i)\\s*\\[(feat\\.|ft\\.|featuring)\\s+[^]]+\\]"), "")
            .replace(Regex("(?i)\\s*-\\s*(official\\s+video|official\\s+audio|audio|video|lyric\\s+video)"), "")
            .replace(Regex("(?i)\\s*\\((official\\s+video|official\\s+audio|audio|video|lyrics?)\\)"), "")
            .replace(Regex("(?i)\\s*\\[(official\\s+video|official\\s+audio|audio|video|lyrics?)\\]"), "")
            .trim()

    /** resolveStream's candidate queries: "clean title + first artist", then "title + artist" */
    fun candidateQueries(title: String, artist: String): List<String> {
        val cleanTitle = cleanTitleForSearch(title)
        val primaryArtist = artist.split(",", "&", "•", ";", "/").firstOrNull()?.trim() ?: artist
        return buildList {
            val q1 = "$cleanTitle $primaryArtist".trim()
            add(q1)
            val q2 = "$title $artist".trim()
            if (q2 != q1) add(q2)
        }
    }
}
