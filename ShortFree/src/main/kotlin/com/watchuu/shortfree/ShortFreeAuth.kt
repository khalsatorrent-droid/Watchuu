package com.watchuu.shortfree

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
 * ShortFree (com.dashtoon.video.shortfree) signs guests in with an anonymous Firebase account
 * (project app-dashtoon, same as DashReels) and sends that account's ID token as
 * "authorization: Bearer ...". After first sign-in it registers the guest with the backend.
 * The refresh token is kept so the same guest is reused.
 */
object ShortFreeAuth {
    // from the apk (resources.arsc google_api_key, project app-dashtoon)
    private const val FIREBASE_KEY = "AIzaSyBB2OR7JTj5Wx6BXUKFwYBJyQD2Z3Cvuvg"
    private const val PACKAGE = "com.dashtoon.video.shortfree"
    const val API = "https://lb.dashtoon.ai"

    private val mapper = ObjectMapper()
    private val lock = Mutex()
    private var prefs: SharedPreferences? = null

    @Volatile private var idToken: String? = null
    @Volatile private var expiresAt = 0L

    val sessionId: String = UUID.randomUUID().toString()

    @Volatile
    var lastError: String = ""
        private set

    fun init(context: Context) {
        if (prefs == null) prefs = context.applicationContext.getSharedPreferences("shortfree_auth", Context.MODE_PRIVATE)
        ShortFreeSettings.init(context)
    }

    private val json = "application/json".toMediaType()
    private val form = "application/x-www-form-urlencoded".toMediaType()
    private val firebaseHeaders = mapOf("X-Android-Package" to PACKAGE, "Accept" to "application/json")

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
                ShortFreeSettings.logRequest("POST (firebase refresh)", "securetoken.googleapis.com/v1/token", r.code, null, if (t.isNullOrBlank()) r.text.take(200) else "ok")
                if (r.code in 200..299 && !t.isNullOrBlank()) {
                    save(t, n.get("refresh_token")?.asText() ?: refresh, n.get("expires_in")?.asLong() ?: 3600L)
                    return@withLock t
                }
                lastError = "token refresh HTTP ${r.code}: ${r.text.take(160)}"
            }
            val r = app.post(
                "https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=$FIREBASE_KEY",
                headers = firebaseHeaders,
                requestBody = """{"returnSecureToken":true}""".toRequestBody(json),
                timeout = 20L
            )
            val n = mapper.readTree(r.text)
            val t = n?.get("idToken")?.asText()
            ShortFreeSettings.logRequest("POST (firebase signUp)", "identitytoolkit.googleapis.com/v1/accounts:signUp", r.code, null, if (t.isNullOrBlank()) r.text.take(200) else "ok (new guest)")
            if (r.code !in 200..299 || t.isNullOrBlank()) {
                lastError = "guest sign-in HTTP ${r.code}: ${r.text.take(200)}"
                return@withLock null
            }
            save(t, n.get("refreshToken")?.asText(), n.get("expiresIn")?.asLong() ?: 3600L)
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

    fun reset() {
        idToken = null
        expiresAt = 0L
        prefs?.edit()?.remove("refresh")?.apply()
    }

    /**
     * HttpClient.setUserAgent: the entries below as "key:value", joined with ", " (no space after
     * the colon). The server reads the Version out of this, so the format must match exactly.
     */
    val userAgent: String get() = listOf(
        "OS" to "android",
        "Country" to ShortFreeSettings.country,
        "sessionId" to sessionId,
        "Platform" to "android",
        "Device" to "Pixel 8",
        "App" to PACKAGE,
        "Version" to ShortFreeSettings.version,
        "Network" to "wifi",
    ).joinToString(", ") { "${it.first}:${it.second}" }

    fun apiHeaders(token: String): Map<String, String> = mapOf(
        "content-type" to "application/json",
        "authorization" to "Bearer $token",
        "user-agent" to userAgent,
        "X-Tenant" to ShortFreeSettings.tenant,
        "X-Country" to ShortFreeSettings.country,
        "X-Session-Id" to sessionId,
    )
}
