package com.watchuu.arcod

import android.content.Context
import android.content.SharedPreferences
import com.lagradost.cloudstream3.app
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Arcod - player.arcod.xyz
 *
 * The site is a Qobuz front end behind a login. Everything below was read off its own traffic:
 *
 *   POST /api/auth/login            {email, password}      -> {success, token, user{…}}
 *   GET  /api/player/search         q limit offset         -> {data:{most_popular:{items[]},
 *                                                              albums:{items[]}, …}}
 *   GET  /api/player/get-album      album_id=<slug>        -> {data:{…album…, tracks:{items[]}}}
 *   GET  /api/player/stream/<id>    quality=5|6|7          -> {url, mimeType, quality, trackId}
 *
 * Every player call needs `Authorization: Bearer <token>`, and the token comes from the login. The
 * stream call returns a signed url on the same host, which is what actually plays.
 */
object ArcodApi {
    const val SITE = "https://player.arcod.xyz"
    const val BUILD = "v1"

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences("arcod_prefs", Context.MODE_PRIVATE)
        }
    }

    private fun str(k: String) = prefs?.getString(k, "")?.trim().orEmpty()
    private fun put(k: String, v: String) {
        prefs?.edit()?.putString(k, v.trim())?.apply()
    }

    var email: String
        get() = str("email")
        set(v) = put("email", v)

    var password: String
        get() = str("password")
        set(v) = put("password", v)

    @Volatile
    private var token: String? = null

    var displayName: String
        get() = str("display_name")
        private set(v) = put("display_name", v)

    fun hasToken(): Boolean = (token ?: str("token")).isNotBlank()

    fun logout() {
        token = null
        put("token", "")
        put("display_name", "")
    }

    private fun headers(): Map<String, String> {
        val h = LinkedHashMap<String, String>()
        h["Accept"] = "application/json"
        h["Content-Type"] = "application/json"
        h["Origin"] = SITE
        h["Referer"] = "$SITE/"
        val t = token ?: str("token")
        if (t.isNotBlank()) h["Authorization"] = "Bearer $t"
        return h
    }

    /** signs in and keeps the token; returns null on success or a sentence explaining the failure */
    suspend fun login(userEmail: String = email, userPassword: String = password): String? {
        if (userEmail.isBlank() || userPassword.isBlank()) return "Email and password are both needed"
        val body = JSONObject().put("email", userEmail.trim()).put("password", userPassword)
        return try {
            val r = app.post(
                "$SITE/api/auth/login",
                headers = headers(),
                requestBody = body.toString().toRequestBody("application/json".toMediaType()),
                timeout = 25_000L
            )
            val json = runCatching { JSONObject(r.text) }.getOrNull()
            if (r.code !in 200..299 || json == null) {
                "Sign in refused (HTTP ${r.code}) - check the email and password"
            } else {
                val t = json.optString("token").ifBlank { return "Signed in but no token came back" }
                token = t
                put("token", t)
                put("email", userEmail.trim())
                put("password", userPassword)
                displayName = json.optJSONObject("user")?.optString("displayName").orEmpty()
                null
            }
        } catch (e: Throwable) {
            "Sign in failed: ${e.message}"
        }
    }

    /** makes sure there is a usable token, signing in with the saved credentials if needed */
    suspend fun ensureToken(): String? {
        token?.let { if (it.isNotBlank()) return it }
        val saved = str("token")
        if (saved.isNotBlank()) {
            token = saved
            return saved
        }
        if (email.isBlank() || password.isBlank()) return null
        login()
        return token?.takeIf { it.isNotBlank() }
    }

    suspend fun get(path: String, params: Map<String, String> = emptyMap(), retry: Boolean = true): JSONObject? {
        ensureToken()
        val q = buildString {
            params.forEach { (k, v) ->
                if (isNotEmpty()) append("&")
                append(k).append("=").append(java.net.URLEncoder.encode(v, "UTF-8"))
            }
        }
        val url = "$SITE$path" + if (q.isNotEmpty()) "?$q" else ""
        return try {
            val r = app.get(url, headers = headers(), timeout = 25L)
            // an expired token answers 401: sign in again once and repeat the call
            if (r.code == 401 && retry && email.isNotBlank() && password.isNotBlank()) {
                token = null
                put("token", "")
                if (login() == null) return get(path, params, retry = false)
                return null
            }
            if (r.code !in 200..299) null else runCatching { JSONObject(r.text) }.getOrNull()
        } catch (e: Throwable) {
            null
        }
    }

    /** the signed url for a track, trying the best quality first */
    suspend fun streamUrl(trackId: String): Pair<String, String>? {
        for (quality in listOf("7", "6", "5")) {
            val json = get("/api/player/stream/$trackId", mapOf("quality" to quality)) ?: continue
            val url = json.optString("url")
            if (url.startsWith("http")) {
                val mime = json.optString("mimeType").ifBlank { "audio" }
                return url to "$mime q$quality"
            }
        }
        return null
    }

    fun report(): String = buildString {
        append("Arcod build: $BUILD\n")
        append("account: ${if (email.isNotBlank()) "saved" else "none"}\n")
        append("signed in: ${if (hasToken()) "yes" else "no"}\n")
        append("name: ${displayName.ifBlank { "-" }}")
    }
}
