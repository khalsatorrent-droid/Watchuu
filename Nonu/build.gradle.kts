version = 4

cloudstream {
    language = "en"
    description = "Nonu - Apple Music catalogue via ak1ra proxy. Songs, albums and artists with HLS playback."
    authors = listOf("khalsatorrent-droid")

    status = 1
    tvTypes = listOf("Music", "Others")
    iconUrl = "https://www.google.com/s2/favicons?domain=ak1ra.de5.net&sz=%size%"
}

android {
    sourceSets.getByName("main") {
        assets.srcDir("src/main/assets")
    }
}

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))
}
