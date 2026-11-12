dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
}

// use an integer for version numbers
version = 12

cloudstream {
    language = "en"
    description = "Music - Spotify Web catalogue with Apple, Deezer, SoundCloud, Tidal and Qobuz backups; plays through YouTube and the resolver"
    authors = listOf("khalsatorrent-droid")

    status = 1
    tvTypes = listOf("Music")
    iconUrl = "https://www.google.com/s2/favicons?domain=spotify.com&sz=%size%"
}
