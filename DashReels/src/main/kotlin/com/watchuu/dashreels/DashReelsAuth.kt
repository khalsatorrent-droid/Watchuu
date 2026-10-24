package com.watchuu.dashreels

import android.content.Context
import android.content.SharedPreferences
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.app
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * DashReels signs every guest in with an anonymous Firebase account (project app-dashtoon) and
 * sends that account's ID token as "authorization: Bearer ..." (core/network/http_client.dart:
 * HttpClient.token -> User.getIdToken). After the first sign-in the app calls
 * POST /reels/api/v1/user/create with an empty body.
 *
 * The refresh token is kept in SharedPreferences, so the same guest account is reused.
 */
object DashReelsAuth {
    // values from the app (res/values/strings.xml, reels/main_production.dart)
    private const val FIREBASE_KEY = "AIzaSyBB2OR7JTj5Wx6BXUKFwYBJyQD2Z3Cvuvg"
    private const val PACKAGE = "com.dashtoon.video.reels"
    const val API = "https://lb.dashtoon.ai"

    private val mapper = ObjectMapper()
    private val lock = Mutex()
    private var prefs: SharedPreferences? = null

    @Volatile private var idToken: String? = null
    @Volatile private var expiresAt = 0L

    /** One session id per app start, like the app's X-Session-Id. */
    val sessionId: String = UUID.randomUUID().toString()

    @Volatile
    var lastError: String = ""
        private set

    fun init(context: Context) {
        if (prefs == null) prefs = context.applicationContext.getSharedPreferences("dashreels_auth", Context.MODE_PRIVATE)
    }

    private val json = "application/json".toMediaType()
    private val form = "application/x-www-form-urlencoded".toMediaType()
    private val firebaseHeaders = mapOf("X-Android-Package" to PACKAGE, "Accept" to "application/json")

    /** A valid ID token (refreshed / created when needed), or null with [lastError] set. */
    suspend fun token(): String? = lock.withLock {
        val now = System.currentTimeMillis()
        idToken?.let { if (now < expiresAt - 60_000L) return@withLock it }
        try {
            val refresh = prefs?.getString("refresh", null)
            if (refresh != null) {
                val r = app.post(
                    "https://securetoken.googleapis.com/v1/token?key=$FIREBASE_KEY",
                    headers = firebaseHeaders,
                    requestBody = "grant_type=refresh_token&refresh_token=$refresh".toRequestBody(form),
                    timeout = 20L
                )
                val n = mapper.readTree(r.text)
                val t = n?.get("id_token")?.asText()
                if (r.code in 200..299 && !t.isNullOrBlank()) {
                    save(t, n.get("refresh_token")?.asText() ?: refresh, n.get("expires_in")?.asLong() ?: 3600L)
                    return@withLock t
                }
                lastError = "token refresh HTTP ${r.code}: ${r.text.take(160)}"
            }
            // new anonymous guest
            val r = app.post(
                "https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=$FIREBASE_KEY",
                headers = firebaseHeaders,
                requestBody = """{"returnSecureToken":true}""".toRequestBody(json),
                timeout = 20L
            )
            val n = mapper.readTree(r.text)
            val t = n?.get("idToken")?.asText()
            if (r.code !in 200..299 || t.isNullOrBlank()) {
                lastError = "guest sign-in HTTP ${r.code}: ${r.text.take(200)}"
                return@withLock null
            }
            save(t, n.get("refreshToken")?.asText(), n.get("expiresIn")?.asLong() ?: 3600L)
            // register the guest with the backend (the app does this once after signing in)
            try {
                app.post("$API/reels/api/v1/user/create", headers = apiHeaders(t), requestBody = "{}".toRequestBody(json), timeout = 20L)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
            t
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            lastError = "sign-in error: ${e.message?.take(160)}"
            null
        }
    }

    private fun save(token: String, refresh: String?, expiresIn: Long) {
        idToken = token
        expiresAt = System.currentTimeMillis() + expiresIn * 1000L
        if (refresh != null) prefs?.edit()?.putString("refresh", refresh)?.apply()
    }

    /** Forget the guest account (used when the backend rejects the token). */
    fun reset() {
        idToken = null
        expiresAt = 0L
        prefs?.edit()?.remove("refresh")?.apply()
    }

    /**
     * HttpClient.setUserAgent: the entries below as "key:value", joined with ", " (no prefix).
     * The server reads the Version from it as numbers, so the format must match exactly.
     */
    val userAgent: String get() = listOf(
        "OS" to "android",
        "Country" to "IN",
        "sessionId" to sessionId,
        "Platform" to "android",
        "Device" to "Pixel 8",
        "App" to PACKAGE,
        "Version" to "1.2.55",
        "Network" to "wifi",
    ).joinToString(", ") { "${it.first}:${it.second}" }

    /** Same headers as the app's HttpClient.getOptions(). */
    fun apiHeaders(token: String): Map<String, String> = mapOf(
        "content-type" to "application/json",
        "authorization" to "Bearer $token",
        "user-agent" to userAgent,
        "X-Tenant" to "reels",
        "X-Country" to "IN",
        "X-Session-Id" to sessionId,
    )
}
