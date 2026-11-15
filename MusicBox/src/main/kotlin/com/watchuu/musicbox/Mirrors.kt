package com.watchuu.musicbox

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The third-party converter / mirror services (AnandServer, Deemixer, Y2Mate, SpotSaver ...) are the
 * fallbacks. Each one is a separate [stage] with its own switch in Settings, and [run] fires every
 * enabled stage at the same moment instead of one after another, so the total wait is the slowest
 * single mirror rather than the sum of all of them. [onFound] sees each result the instant it exists.
 */
class MirrorRun<T>(private val onFound: (suspend (T) -> Unit)? = null) {
    private val found = java.util.Collections.synchronizedList(ArrayList<T>())
    private val stages = ArrayList<Pair<String, suspend () -> Unit>>()

    suspend fun add(item: T) {
        found.add(item)
        onFound?.invoke(item)
    }

    fun found(): List<T> = synchronized(found) { found.toList() }

    fun stage(key: String, block: suspend () -> Unit) {
        stages.add(key to block)
    }

    suspend fun run(timeoutMs: Long = 20_000L): List<T> {
        val active = stages.filter { MusicBoxSettings.mirrorEnabled(it.first) }
        coroutineScope {
            active.map { (key, block) ->
                async {
                    withTimeoutOrNull(timeoutMs) {
                        try { block() } catch (e: CancellationException) { throw e } catch (e: Throwable) {
                            MusicBoxLog.d("Mirror", "$key: ${e.message?.take(80)}")
                        }
                    }
                }
            }.awaitAll()
        }
        return synchronized(found) { found.toList() }
    }

    companion object {
        /** key -> label, in the order they appear in Settings */
        val catalogue: List<Triple<String, String, String>> = listOf(
            Triple("deezer_anand", "Deezer", "AnandServer FLAC"),
            Triple("deezer_deemixer", "Deezer", "Deemixer"),
            Triple("deezer_musicfab", "Deezer", "MusicFab"),
            Triple("deezer_deezdl", "Deezer", "DeezDownloaders"),
            Triple("deezer_antra", "Deezer", "Antra Hoshi lossless"),
            Triple("qobuz_anand", "Qobuz", "AnandServer Hi-Res FLAC"),
            Triple("qobuz_dezalty", "Qobuz", "DezAlty guest stream"),
            Triple("qobuz_gdstudio", "Qobuz", "GDStudio lossless"),
            Triple("qobuz_gdsearch", "Qobuz", "GDStudio search"),
            Triple("tidal_gdstudio", "Tidal", "GDStudio Tidal"),
            Triple("spotify_spotidown", "Spotify", "SpotiDown"),
            Triple("spotify_rapidapi", "Spotify", "RapidAPI downloader"),
            Triple("spotify_musicfab", "Spotify", "MusicFab"),
            Triple("spotify_spotsaver", "Spotify", "SpotSaver"),
            Triple("spotify_spotifydl", "Spotify", "Spotify-Downloads"),
            Triple("yt_y2mate", "YouTube", "Y2Mate / cnv.cx"),
            Triple("yt_cnvmp3", "YouTube", "CnvMP3"),
            Triple("yt_mediaytmp3", "YouTube", "MediaYTMP3 / Convert1s"),
            Triple("yt_mp3juice", "YouTube", "MP3Juice"),
            Triple("yt_audioonly", "YouTube", "Direct audio-only stream"),
            Triple("yt_extractor", "YouTube", "CloudStream extractor"),
            Triple("yt_cobalt", "YouTube", "ZARZ Cobalt"),
        )
    }
}
