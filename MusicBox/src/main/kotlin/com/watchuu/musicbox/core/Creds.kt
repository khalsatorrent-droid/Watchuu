package com.watchuu.musicbox.core

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/** One editable setting/credential shown in the settings screen. */
data class Field(
    val key: String,
    val label: String,
    val hint: String,
    val secret: Boolean = false,
    val default: String = "",
    val network: Boolean = false,
)

/** Everything the settings screen needs to authorize one provider. */
data class AuthSpec(
    val id: String,
    val label: String,
    val fields: List<Field> = emptyList(),
    val loginUrl: String? = null,
    val loginNote: String? = null,
    /** Zarz signed-session app_version for this provider, from Mornye's manifests. */
    val signedSessionAppVersion: String? = null,
    val summary: String = "",
)

/**
 * Credential store. Every value is namespaced by provider so an import/export round-trip
 * moves exactly the authorization state and nothing else.
 */
object Creds {
    private const val FILE = "musicbox_v2_creds"
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        }
    }

    fun ready(): Boolean = prefs != null

    private fun p(): SharedPreferences? = prefs

    fun get(key: String, fallback: String? = null): String? =
        p()?.getString(key, null)?.takeIf { it.isNotBlank() } ?: fallback

    fun set(key: String, value: String?) {
        val editor = p()?.edit() ?: return
        if (value.isNullOrBlank()) editor.remove(key) else editor.putString(key, value)
        editor.apply()
    }

    fun has(key: String): Boolean = !get(key).isNullOrBlank()

    fun getJson(key: String): JSONObject? = get(key)?.let { jsonOrNull(it) }

    fun setJson(key: String, value: JSONObject?) {
        set(key, value?.toString())
    }

    /** All stored keys, so export/import never has to guess at the schema. */
    fun export(): String {
        val all = p()?.all ?: return "{}"
        val obj = JSONObject()
        for ((k, v) in all) obj.put(k, v)
        return obj.toString(2)
    }

    /** Returns the number of keys restored. */
    fun import(json: String): Int {
        val obj = jsonOrNull(json) ?: return 0
        val editor = p()?.edit() ?: return 0
        var count = 0
        for (key in obj.keys()) {
            val value = obj.optString(key, "")
            if (value.isBlank()) continue
            editor.putString(key, value)
            count++
        }
        editor.apply()
        return count
    }

    fun clearAll() {
        p()?.edit()?.clear()?.apply()
    }

    fun clearProvider(spec: AuthSpec) {
        for (f in spec.fields) set(f.key, null)
    }
}

/**
 * The authorization surface of the extension: every provider, every field it reads,
 * taken from Mornye-Extension's manifests and from the settings keys its sources read.
 */
object AuthSpecs {
    private fun q(id: String, label: String, summary: String, fields: List<Field>, appVersion: String? = null, loginUrl: String? = null, loginNote: String? = null) =
        AuthSpec(id, label, fields, loginUrl, loginNote, appVersion, summary)

    /** Providers that spend a rate-limited ZARZ session on every request. */
    fun zarzBacked(): List<AuthSpec> = all.filter { it.signedSessionAppVersion != null }

    fun isZarzBacked(providerId: String): Boolean =
        all.firstOrNull { it.id == providerId }?.signedSessionAppVersion != null

    val all: List<AuthSpec> = listOf(
        q(
            "amazon", "Amazon Music",
            "Signed session (zarz-v2) + home country. Region must match your Amazon store.",
            listOf(
                Field("amazon.homeCountry", "Home feed country", "US, GB, JP, DE, FR, IT, ES, IN, BR, MX, AU", default = "US"),
            ),
            appVersion = "amzn@2.3.12",
        ),
        q(
            "tidal", "TIDAL",
            "Public web token for metadata, signed session (zarz-v2) for /dl/tid downloads.",
            listOf(
                Field("tidal.publicToken", "Public web token", "TIDAL signed-out web token", default = "CzET4vdadNUFQ5JU"),
                Field("tidal.countryCode", "Country code", "e.g. US, IN", default = "US"),
                Field("tidal.locale", "Locale", "e.g. en_US", default = "en_US"),
                Field("tidal.deviceType", "Device type", "BROWSER", default = "BROWSER"),
                Field("tidal.customEndpoint", "Custom download endpoint", "Optional hifi-api compatible server, tried before the default API", network = true),
            ),
            appVersion = "tidal-web@1.2.5",
        ),
        q(
            "qobuz", "Qobuz",
            "App id for metadata, signed session (zarz-v2) for /dl/qbz downloads.",
            listOf(
                Field("qobuz.appId", "Qobuz App ID", "App id sent to the metadata API", default = "798273057"),
                Field("qobuz.countryCode", "Country code", "e.g. US, IN", default = "US"),
                Field("qobuz.customEndpoint", "Custom download endpoint", "Optional qobuz-rest-api compatible server", network = true),
                Field("qobuz.customEndpointApiKey", "Custom endpoint API key", "Sent as X-API-Key", secret = true),
            ),
            appVersion = "qobuz-web@1.2.21",
        ),
        q(
            "deezer", "Deezer",
            "Public metadata API + signed session (zarz-v2) for /dl/dee.",
            listOf(
                Field("deezer.apiBaseUrl", "API base URL", "Override only if Deezer moves its public API", default = "https://api.deezer.com"),
            ),
            appVersion = "deezer@1.3.5",
        ),
        q(
            "soundcloud", "SoundCloud",
            "Client id is discovered from SoundCloud's own web player; no account needed.",
            listOf(),
            loginUrl = "https://soundcloud.com/signin",
            loginNote = "Optional. Sign in only if you want your own likes and private tracks.",
        ),
        q(
            "ytmusic", "YouTube Music",
            "InnerTube web client. PoToken settings exist for formats that require one.",
            listOf(
                Field("ytmusic.preferredFormat", "Preferred format", "opus | m4a", default = "opus"),
                Field("ytmusic.poTokenMode", "PoToken mode", "none | manual | provider", default = "none"),
                Field("ytmusic.manualGvsPoToken", "Manual GVS PoToken", "Paste a token when mode is manual", secret = true),
                Field("ytmusic.poTokenProviderUrl", "PoToken provider URL", "Endpoint that returns a GVS PoToken", network = true),
                Field("ytmusic.homeCountry", "Home country", "e.g. IN, US", default = "IN"),
            ),
        ),
        q(
            "jiosaavn", "JioSaavn",
            "Public catalogue, direct streams. No account or token needed.",
            listOf(
                Field("jiosaavn.lang", "Language filter", "e.g. hindi,english", default = ""),
            ),
        ),
        q(
            "jellyfin", "Jellyfin",
            "Your own server. Credentials are exchanged for an access token on the server.",
            listOf(
                Field("jellyfin.serverUrl", "Server URL", "https://jellyfin.example.com", network = true),
                Field("jellyfin.username", "Username", ""),
                Field("jellyfin.password", "Password", "", secret = true),
                Field("jellyfin.apiKey", "API key (optional)", "Used instead of username/password when set", secret = true),
            ),
        ),
        q(
            "beatport", "Beatport",
            "Signed session (zarz-v2) for Beatport V2 downloads.",
            listOf(),
            appVersion = "beatport@1.0.0",
        ),
    )

    fun byId(id: String): AuthSpec? = all.firstOrNull { it.id == id }
}
