version = 3

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
}

cloudstream {
    language = "en"
    description = "Movies and TV from Vivarium (vivarium.su) - TMDB catalog, streams caught behind Cloudflare in a hidden browser"
    authors = listOf("khalsatorrent-droid")

    status = 1
    tvTypes = listOf("Movie", "TvSeries", "Anime")
    iconUrl = "https://www.google.com/s2/favicons?domain=vivarium.su&sz=128"
}
