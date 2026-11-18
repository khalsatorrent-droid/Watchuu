version = 7

cloudstream {
    language = "en"
    description = "Nonu - Apple Music catalogue via 1412 proxy. Songs, albums and artists with HLS playback."
    authors = listOf("khalsatorrent-droid")

    status = 1
    tvTypes = listOf("Music", "Others")
    iconUrl = "https://www.google.com/s2/favicons?domain=1412.tech&sz=%size%"
}

android {
    sourceSets.getByName("main") {
        assets.srcDir("src/main/assets")
    }
}

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))
}
