// use an integer for version numbers
version = 1

cloudstream {
    language = "en"
    description = "Watch HD videos and categories from X-X-X.tube"
    authors = listOf("khalsatorrent-droid")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1
    tvTypes = listOf(
        "NSFW",
        "Movie",
        "TvSeries"
    )

    iconUrl = "https://x-x-x.tube/favicon.ico"
}
