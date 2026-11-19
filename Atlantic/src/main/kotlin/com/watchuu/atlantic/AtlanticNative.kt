package com.watchuu.atlantic

import android.content.Context
import android.os.Build
import android.util.Log
import com.lagradost.cloudstream3.app
import com.zstream.plugin.Crypto
import com.zstream.plugin.HostFetcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.util.zip.ZipFile

/**
 * Loads Atlantic's resolver library and calls it.
 *
 * The library is not bundled here. It ships inside Atlantic's plugin apk, which is public and
 * versioned, so this fetches that, pulls the matching `libzstream-crypto.so` out of it and loads it.
 *
 *   https://atlantic.bz/dl/plugin/manifest.json   -> {downloadUrl, hash, latestVersion}
 *   <plugin apk>!/lib/<abi>/libzstream-crypto.so  -> System.load()
 *
 * The resolvers break whenever a site changes and Atlantic ships a new library, so the cached copy
 * is compared against `latestVersion` the first time it is needed in a process and replaced when it
 * differs. A library that is already loaded cannot be swapped inside a running process, so an update
 * takes effect on the next start of the app.
 */
object AtlanticNative {
    private const val TAG = "Atlantic"
    private const val MANIFEST = "https://atlantic.bz/dl/plugin/manifest.json"
    private const val LIB_NAME = "libzstream-crypto.so"
    private const val PREFS = "atlantic"
    private const val KEY_VERSION = "lib_version"

    @Volatile private var loaded = false
    @Volatile private var loadError: String? = null
    @Volatile private var appContext: Context? = null
    @Volatile private var version: String = ""

    /** one load at a time: playback and the settings buttons can both ask for it */
    private val lock = Mutex()

    val isLoaded: Boolean get() = loaded
    val error: String? get() = loadError
    val libraryVersion: String get() = version

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * Makes sure the library is on disk and loaded. Safe to call from every playback request: it
     * returns at once when the library is already in.
     */
    suspend fun ensureLoaded(context: Context? = null): Boolean {
        if (loaded) return true
        val ctx = (context ?: appContext)?.applicationContext
        if (ctx == null) {
            loadError = "Atlantic has no context yet"
            return false
        }
        return lock.withLock {
            if (loaded) true else withContext(Dispatchers.IO) { loadLocked(ctx) }
        }
    }

    private suspend fun loadLocked(ctx: Context): Boolean {
        loadError = null
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val dir = File(ctx.filesDir, "atlantic").apply { mkdirs() }
        val soFile = File(dir, LIB_NAME)

        return try {
            val manifest = fetchManifest()
            val remoteVersion = manifest?.optString("latestVersion").orEmpty()
            val cached = soFile.isFile && soFile.length() > 0
            val storedVersion = prefs.getString(KEY_VERSION, "").orEmpty()
            val stale = cached && remoteVersion.isNotBlank() && remoteVersion != storedVersion

            if (!cached || stale) {
                val problem = download(manifest, dir, soFile)
                when {
                    problem == null -> prefs.edit().putString(KEY_VERSION, remoteVersion).apply()
                    // an update that cannot be fetched must not take away a library that works
                    cached -> Log.w(TAG, "library update skipped: $problem")
                    else -> {
                        loadError = problem
                        return false
                    }
                }
            }

            version = prefs.getString(KEY_VERSION, "").orEmpty()
            System.load(soFile.absolutePath)
            // the class's static block would call loadLibrary by name, which only works inside the
            // app that owns the library, so it is only touched once System.load has already run
            runCatching { HostFetcher.userAgent() }
            loaded = true
            true
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            loadError = "${t.javaClass.simpleName}: ${t.message}"
            Log.w(TAG, "library load failed: $loadError")
            // a bad file left behind would fail the same way on every later attempt
            runCatching { soFile.delete() }
            prefs.edit().remove(KEY_VERSION).apply()
            false
        }
    }

    private suspend fun fetchManifest(): JSONObject? = try {
        JSONObject(app.get(MANIFEST, timeout = 20L).text)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Log.w(TAG, "manifest fetch failed: ${e.message}")
        null
    }

    /** returns null on success, otherwise what went wrong */
    private suspend fun download(manifest: JSONObject?, dir: File, soFile: File): String? {
        if (manifest == null) return "the plugin manifest could not be fetched"
        val raw = manifest.optString("downloadUrl")
        if (raw.isBlank()) return "plugin manifest had no download url"
        // the manifest may give a path instead of a full address
        val url = runCatching { URI(MANIFEST).resolve(raw).toString() }.getOrDefault(raw)

        val apk = File(dir, "plugin.apk")
        val tmp = File(dir, "$LIB_NAME.tmp")
        try {
            apk.outputStream().use { out ->
                app.get(url, timeout = 180L).body.byteStream().use { it.copyTo(out) }
            }

            val abis = Build.SUPPORTED_ABIS.toList()
            ZipFile(apk).use { zip ->
                // the device's own preference order, never another architecture's copy
                val entry = abis.firstNotNullOfOrNull { zip.getEntry("lib/$it/$LIB_NAME") }
                    ?: return "the plugin has no $LIB_NAME for ${abis.joinToString()}"
                tmp.outputStream().use { out -> zip.getInputStream(entry).use { it.copyTo(out) } }
            }

            // an error page saved under a .so name would otherwise be cached and fail forever
            val magic = ByteArray(4)
            val read = tmp.inputStream().use { it.read(magic) }
            val elf = read == 4 && magic[0] == 0x7f.toByte() && magic[1] == 'E'.code.toByte() &&
                magic[2] == 'L'.code.toByte() && magic[3] == 'F'.code.toByte()
            if (!elf) return "the downloaded file is not a native library"

            soFile.delete()
            if (!tmp.renameTo(soFile)) return "could not store the library"
            soFile.setReadOnly()
            return null
        } finally {
            apk.delete()
            tmp.delete()
        }
    }

    /**
     * Removes the cached library so the next start downloads a fresh one. A library already loaded
     * in this process stays loaded until the app restarts.
     */
    fun clearCache(context: Context) {
        val ctx = context.applicationContext
        File(File(ctx.filesDir, "atlantic"), LIB_NAME).delete()
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_VERSION).apply()
        version = ""
    }

    /** what a resolver needs to be asked for a stream */
    data class Request(
        val tmdbId: String,
        val title: String,
        val year: String,
        val posterPath: String,
        val isShow: Boolean,
        val season: String,
        val ep: String,
        val febboxKey: String = "",
        val preferredVariantId: String = "",
    ) {
        /**
         * Two encodings, and they are not interchangeable:
         *   "1"/"0"    aether aspera cosmic eclipse lumi magnolia nesterov shibuya stellar valley
         *              tokyo nepu fuji
         *   "tv"/"movie" amethyst apollo thalia nexus halcyon artemis aurora
         * A source asked in the wrong one answers nothing, which looks like a dead source.
         */
        val flag: String get() = if (isShow) "1" else "0"

        /** artemis, aurora, halcyon and nexus take tv/movie instead */
        val word: String get() = if (isShow) "tv" else "movie"
    }

    /**
     * One source, one call. Argument order and the 1/0 versus tv/movie split are taken from the
     * app's own Source classes - getting either wrong is what makes a resolver return nothing.
     *
     * This blocks for as long as the source takes, so callers run it off the main thread.
     */
    fun resolve(sourceId: String, r: Request): String? {
        // aphrodite does not use the library, so it still works when the library cannot load
        if (sourceId == "aphrodite") return AtlanticAphrodite.resolve(r)
        if (!loaded) return null
        val raw = runCatching {
            when (sourceId) {
                "aether" -> Crypto.aetherResolve(r.tmdbId, r.flag, r.season, r.ep)
                "amethyst" -> Crypto.amethystResolve(r.tmdbId, r.word, r.season, r.ep)
                "apollo" -> Crypto.apolloResolve(r.tmdbId, r.word, r.season, r.ep)
                "aspera" -> Crypto.asperaResolve(r.tmdbId, r.flag, r.season, r.ep)
                "cosmic" -> Crypto.cosmicResolve(r.tmdbId, r.flag, r.season, r.ep)
                "eclipse" -> Crypto.eclipseResolve(r.tmdbId, r.flag, r.season, r.ep)
                "lumi" -> Crypto.lumiResolve(r.tmdbId, r.flag, r.season, r.ep)
                "magnolia" -> Crypto.magnoliaResolve(r.tmdbId, r.flag, r.season, r.ep)
                "nesterov" -> Crypto.nesterovResolve(r.tmdbId, r.flag, r.season, r.ep)
                "shibuya" -> Crypto.shibuyaResolve(r.tmdbId, r.flag, r.season, r.ep)
                "stellar" -> Crypto.stellarResolve(r.tmdbId, r.flag, r.season, r.ep)
                "thalia" -> Crypto.thaliaResolve(r.tmdbId, r.word, r.season, r.ep)
                "valley" -> Crypto.valleyResolve(r.tmdbId, r.flag, r.season, r.ep)

                "tokyo" -> Crypto.tokyoResolve(r.tmdbId, r.title, r.year, r.flag, r.season, r.ep)
                "nepu" -> Crypto.nepuResolve(r.title, r.year, r.posterPath, r.flag, r.season, r.ep)
                "fuji" -> Crypto.fujiResolve(r.title, r.year, r.flag, r.season, r.ep)
                "dizipal" -> Crypto.dizipalResolve(r.title, r.season, r.ep)

                "nexus" -> Crypto.nexusResolve(r.title, r.year, r.word, r.season, r.ep)
                "halcyon" -> Crypto.halcyonResolve(r.title, r.year, r.word, r.season, r.ep)

                // artemis goes through the shared entry point: shelf = season, slot = episode
                "artemis" -> Crypto.nativeResolve(
                    r.tmdbId, r.season, r.ep, r.word, r.title, r.year, "", r.preferredVariantId
                )

                // aurora only answers when a febbox key is supplied
                "aurora" -> if (r.febboxKey.isBlank()) null else Crypto.auroraResolve(
                    r.tmdbId, r.season, r.ep, r.word, r.title, r.year, r.febboxKey, r.preferredVariantId
                )

                else -> null
            }
        }.onFailure { Log.d(TAG, "$sourceId failed: ${it.javaClass.simpleName} ${it.message}") }
            .getOrNull()

        // "{}" is the library saying "not available"; "" is a failure
        return raw?.takeIf { it.isNotBlank() && it.trim() != "{}" }
    }

    /** the sources of plugin v11, in its order and with its names (older ones no longer exist) */
    val SOURCES: List<Pair<String, String>> = listOf(
        "aphrodite" to "Aphrodite",
        "aurora" to "Aurora API",
        "stellar" to "Nebula",
        "nesterov" to "Perigee",
        "magnolia" to "Camellia",
        "tokyo" to "Tokyo",
        "valley" to "Canyon [4K HDR]",
        "aether" to "Aether [4K]",
        "lumi" to "Lumi [4K]",
        "aspera" to "Ardua",
        "nexus" to "Nexus (DDL)",
    )
}
