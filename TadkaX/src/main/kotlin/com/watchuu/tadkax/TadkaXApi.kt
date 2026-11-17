package com.watchuu.tadkax

import com.lagradost.cloudstream3.app
import org.json.JSONObject

/**
 * TadkaX - the mobile api behind the app.
 *
 * One php endpoint carries everything, chosen by an `action` parameter:
 *
 *   action=home          latest_limit=   -> {success, data:{featured[], latest[], recommended[]}}
 *   action=latest        page limit search tag viewer_id -> {data:[Video], meta:{total,page,limit,pages}}
 *   action=search        q page limit    -> same shape
 *   action=by_category   category page limit
 *   action=by_tag        tag page limit
 *   action=by_studio     studio page limit
 *   action=by_ott        ott page limit
 *   action=post          id              -> {success, data: Video}
 *   action=categories    limit           -> {data:[{category, count}]}
 *   action=tags | studios | ott_platforms limit -> {data:[{name, count}]}
 *   action=daily_top     limit
 *   action=recommended   viewer_id limit
 *   action=similar       id limit
 *
 * A Video carries: id, title, description, poster_url, duration "27:25", tags, category, studio,
 * actress, actor, is_hd, created_at, and four urls - video_url plus 480p/720p/1080p variants, each
 * already signed with a token and an expiry, so they are played directly.
 */
object TadkaXApi {
    const val API = "https://hotapi.store/mobile-api.php"
    const val SITE = "https://www.tadkax.com"
    const val BUILD = "v1"

    private fun headers(): Map<String, String> = mapOf(
        "Accept" to "application/json",
        "User-Agent" to "okhttp/4.12.0",
    )

    suspend fun get(action: String, params: Map<String, String> = emptyMap()): JSONObject? {
        val q = buildString {
            append("action=").append(action)
            params.forEach { (k, v) -> append("&").append(k).append("=").append(java.net.URLEncoder.encode(v, "UTF-8")) }
        }
        val url = "$API?$q"
        return try {
            val r = app.get(url, headers = headers(), timeout = 25L)
            if (r.code !in 200..299) null else runCatching { JSONObject(r.text) }.getOrNull()
        } catch (e: Throwable) {
            null
        }
    }

    /** {data:[…], meta:{…}} responses */
    suspend fun list(action: String, params: Map<String, String> = emptyMap()): Pair<List<JSONObject>, Int> {
        val root = get(action, params) ?: return emptyList<JSONObject>() to 0
        val arr = root.optJSONArray("data")
        val items = if (arr == null) emptyList() else (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        val pages = root.optJSONObject("meta")?.optInt("pages", 0) ?: 0
        return items to pages
    }
}
