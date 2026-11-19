package com.zstream.plugin

/**
 * The library does not do its own HTTP. It calls back into the host through this class, which is the
 * only Java type it references (`com/zstream/plugin/HostFetcher`, methods `fetch`, `cookies`,
 * `userAgent`). Atlantic injects a bridge object that runs the request inside a WebView; here the
 * same three entry points are answered directly.
 *
 * The names, the argument order and the return type must match what the library looks up, so this
 * class keeps its package and its static signatures.
 */
object HostFetcher {

    @Volatile
    var userAgentValue: String =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    /**
     * fetch(url, referer, script, timeoutMs) -> body
     *
     * `script` is the JavaScript Atlantic runs after the page loads (usually
     * `document.documentElement.outerHTML`). A plain GET cannot execute it, so the body of the
     * response is returned as-is; sources that need a rendered page will come back thin. Failed
     * requests are logged under the "Atlantic" tag.
     */
    @JvmStatic
    fun fetch(url: String, referer: String, script: String, timeoutMs: Long): String {
        return AtlanticBridge.fetch(url, referer, script, timeoutMs)
    }

    @JvmStatic
    fun cookies(url: String): String = AtlanticBridge.cookies(url)

    @JvmStatic
    fun userAgent(): String = userAgentValue
}
