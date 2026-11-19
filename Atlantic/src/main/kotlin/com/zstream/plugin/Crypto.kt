package com.zstream.plugin

/**
 * JNI declarations for Atlantic's resolver library, copied argument-for-argument from the app's own
 * Crypto class so the symbols bind and the arguments land where the native code reads them.
 *
 * The class name and package are fixed by the exported symbols
 * (`Java_com_zstream_plugin_Crypto_<name>Resolve`); nothing here can be renamed or moved.
 *
 * Two encodings matter and they differ per source, which is easy to get wrong:
 *   - the tmdb-id sources take type as "1" for a show and "0" for a film
 *   - artemis, aurora, halcyon and nexus take "tv" / "movie"
 * Every method returns the library's JSON:
 *   {"streamUrl":"…","streamType":"hls","headers":{…},"captions":[…],"variants":[…]}
 */
class Crypto {
    companion object {
        @JvmStatic external fun nativeTest(): String

        /** void, not String - the library writes nothing back */
        @JvmStatic external fun setCloudflareClearance(host: String, cookie: String, userAgent: String)

        /** the shared entry point artemis uses; shelf carries the season, slot the episode */
        @JvmStatic external fun nativeResolve(
            tmdbId: String, shelf: String, slot: String, type: String,
            title: String, year: String, token: String, preferredVariantId: String,
        ): String

        // ---- (tmdbId, type, season, ep), type = "1" / "0"
        @JvmStatic external fun aetherResolve(tmdbId: String, type: String, season: String, ep: String): String
        @JvmStatic external fun amethystResolve(tmdbId: String, type: String, season: String, ep: String): String
        @JvmStatic external fun aphroditeResolve(tmdbId: String, type: String, season: String, ep: String): String
        @JvmStatic external fun apolloResolve(tmdbId: String, type: String, season: String, ep: String): String
        @JvmStatic external fun asperaResolve(tmdbId: String, type: String, season: String, ep: String): String
        @JvmStatic external fun cosmicResolve(tmdbId: String, type: String, season: String, ep: String): String
        @JvmStatic external fun eclipseResolve(tmdbId: String, type: String, season: String, ep: String): String
        @JvmStatic external fun lumiResolve(tmdbId: String, type: String, season: String, ep: String): String
        @JvmStatic external fun magnoliaResolve(tmdbId: String, type: String, season: String, ep: String): String
        @JvmStatic external fun nesterovResolve(tmdbId: String, type: String, season: String, ep: String): String
        @JvmStatic external fun shibuyaResolve(tmdbId: String, type: String, season: String, ep: String): String
        @JvmStatic external fun stellarResolve(tmdbId: String, type: String, season: String, ep: String): String
        @JvmStatic external fun thaliaResolve(tmdbId: String, type: String, season: String, ep: String): String
        @JvmStatic external fun valleyResolve(tmdbId: String, type: String, season: String, ep: String): String

        // ---- these take more than the id
        @JvmStatic external fun tokyoResolve(
            tmdbId: String, title: String, year: String, type: String, season: String, ep: String,
        ): String

        @JvmStatic external fun nepuResolve(
            title: String, year: String, posterPath: String, type: String, season: String, ep: String,
        ): String

        @JvmStatic external fun nexusResolve(
            title: String, year: String, type: String, season: String, ep: String,
        ): String

        @JvmStatic external fun fujiResolve(
            title: String, year: String, type: String, season: String, ep: String,
        ): String

        @JvmStatic external fun halcyonResolve(
            title: String, year: String, type: String, season: String, ep: String,
        ): String

        @JvmStatic external fun dizipalResolve(title: String, season: String, ep: String): String

        @JvmStatic external fun auroraResolve(
            tmdbId: String, shelf: String, slot: String, type: String,
            title: String, year: String, token: String, preferredVariantId: String,
        ): String
    }
}
