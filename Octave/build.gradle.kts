version = 30

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
}

cloudstream {
    language = "en"
    description = "Lossless (FLAC) music from Octave (music.octavestreaming.com) - search songs, albums and artists. Needs a one-time Cloudflare check done by you (Extensions -> Octave -> settings -> Cloudflare check + login)."
    authors = listOf("khalsatorrent-droid")

    status = 1
    tvTypes = listOf("Music", "Others")
    iconUrl = "https://www.google.com/s2/favicons?domain=octavestreaming.com&sz=%size%"
}
