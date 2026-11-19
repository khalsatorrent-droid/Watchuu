package com.watchuu.atlantic

import android.content.Context
import android.content.SharedPreferences
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/**
 * TMDB is the catalogue. It has to be: every resolver in the library takes a tmdb id, so the id has
 * to come from somewhere before a source can be asked for a stream.
 *
 * A key is built in so the module works as soon as it is installed, the same way Cinesrc and
 * FlixVision do. A key typed in settings replaces it: either the short v3 key or the long
 * "API Read Access Token" from themoviedb.org, which is sent as a bearer token.
 */
object AtlanticTmdb {
    private const val BASE = "https://api.themoviedb.org/3"
    private const val IMG = "https://image.tmdb.org/t/p/w500"

    private const val DEFAULT_KEY = "2f3cb5763db1117fcba3948632f8aad9"

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) prefs = context.applicationContext.getSharedPreferences("atlantic", Context.MODE_PRIVATE)
    }

    /** what the user typed, empty when the built-in key is in use */
    val userKey: String
        get() = prefs?.getString("tmdb_key", "")?.trim().orEmpty()

    var apiKey: String
        get() = userKey.ifBlank { DEFAULT_KEY }
        set(v) {
            // pasting the built-in key back in is the same as not having a key of your own
            val key = v.trim().takeIf { it != DEFAULT_KEY }.orEmpty()
            prefs?.edit()?.putString("tmdb_key", key)?.apply()
        }

    val usingBuiltInKey: Boolean
        get() = userKey.isBlank()

    /** aurora only answers with one of these; every other source ignores it */
    var febboxKey: String
        get() = prefs?.getString("febbox_key", "")?.trim().orEmpty()
        set(v) {
            prefs?.edit()?.putString("febbox_key", v.trim())?.apply()
        }

    var enabledSources: String
        get() = prefs?.getString("sources", "")?.trim().orEmpty()
        set(v) {
            prefs?.edit()?.putString("sources", v.trim())?.apply()
        }

    private suspend fun get(path: String, params: Map<String, String>): JSONObject? {
        val key = apiKey
        if (key.isBlank()) return null
        // v4 read tokens are JWTs; a v3 key is a short hex string
        val bearer = key.startsWith("eyJ")

        val query = buildList<String> {
            if (!bearer) add("api_key=${java.net.URLEncoder.encode(key, "UTF-8")}")
            params.forEach { (k, v) -> add("$k=${java.net.URLEncoder.encode(v, "UTF-8")}") }
        }.joinToString("&")

        val url = "$BASE$path" + if (query.isNotEmpty()) "?$query" else ""
        val headers = if (bearer) {
            mapOf("Authorization" to "Bearer $key", "Accept" to "application/json")
        } else {
            mapOf("Accept" to "application/json")
        }
        return try {
            val r = app.get(url, headers = headers, timeout = 20L)
            if (r.code !in 200..299) null else runCatching { JSONObject(r.text) }.getOrNull()
        } catch (e: CancellationException) {
            // swallowing this would keep a cancelled request running as if nothing happened
            throw e
        } catch (e: Throwable) {
            null
        }
    }

    fun poster(path: String?): String? = path?.takeIf { it.isNotBlank() }?.let { "$IMG$it" }

    suspend fun trending(page: Int) = get("/trending/all/week", mapOf("page" to page.toString()))
    suspend fun popularMovies(page: Int) = get("/movie/popular", mapOf("page" to page.toString()))
    suspend fun popularTv(page: Int) = get("/tv/popular", mapOf("page" to page.toString()))
    suspend fun topRated(page: Int) = get("/movie/top_rated", mapOf("page" to page.toString()))
    suspend fun nowPlaying(page: Int) = get("/movie/now_playing", mapOf("page" to page.toString()))
    suspend fun search(query: String, page: Int) = get("/search/multi", mapOf("query" to query, "page" to page.toString()))
    suspend fun details(tmdbId: String, isShow: Boolean) = get(if (isShow) "/tv/$tmdbId" else "/movie/$tmdbId", mapOf())
    suspend fun season(tmdbId: String, season: Int) = get("/tv/$tmdbId/season/$season", mapOf())
}
