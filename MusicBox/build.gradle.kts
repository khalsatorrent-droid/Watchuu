dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
}

// use an integer for version numbers
version = 8

cloudstream {
    language = "en"
    description = "Music - top tracks, albums and artists from Deezer, played through YouTube's audio"
    authors = listOf("khalsatorrent-droid")

    status = 1
    tvTypes = listOf("Music")
    iconUrl = "https://www.google.com/s2/favicons?domain=deezer.com&sz=%size%"
}
