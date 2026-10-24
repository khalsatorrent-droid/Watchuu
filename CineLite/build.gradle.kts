version = 20

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
}

cloudstream {
    language = "en"
    description = "Movies and TV shows from CineLite (cinelite.xyz) - TMDB catalog with HLS streams"
    authors = listOf("khalsatorrent-droid")

    status = 1
    tvTypes = listOf("Movie", "TvSeries", "Anime")
    iconUrl = "https://cinelite.xyz/cinelite-logo.png"
}
