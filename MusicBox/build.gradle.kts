dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
}

// use an integer for version numbers
version = 14

cloudstream {
    language = "en"
    description = "Music - Spotify catalogue with 6-provider download: Amazon Music, Deezer, JioSaavn, SoundCloud, Tidal, YouTube Music via ZARZ signed sessions"
    authors = listOf("khalsatorrent-droid")

    status = 1
    tvTypes = listOf("Music")
    iconUrl = "https://www.google.com/s2/favicons?domain=spotify.com&sz=%size%"
}
