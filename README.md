<div align="center">

# 🎬 Watchuu

**Cloudstream extensions, built automatically.**

[![Build](https://github.com/khalsatorrent-droid/Watchuu/actions/workflows/build.yml/badge.svg)](https://github.com/khalsatorrent-droid/Watchuu/actions/workflows/build.yml)
[![Cloudstream](https://img.shields.io/badge/Cloudstream-repository-blueviolet
![Language](https://img.shields.io/badge/Kotlin-2.4-7F52FF

**24 extensions** · movies, TV, anime, short drama, music and NSFW

</div>

---

## 📲 Install

**1. Open Cloudstream** → **Settings** → **Extensions** → **Add repository**

**2. Paste the repository link:**

```
https://raw.githubusercontent.com/khalsatorrent-droid/Watchuu/main/repo.json
```

**3.** Tap the repository and install the extensions you want.

Extensions update themselves from the same link — when a site changes and a module is fixed, bumping its version makes Cloudstream offer the update.

## 🧩 Extensions

### Movies · TV · Anime · Short drama

| Extension | Site | Types | Language |
|-----------|------|-------|----------|
| **CineLite** | [cinelite.xyz](https://cinelite.xyz) | Movie · TV Series | 🇬🇧 EN |
| **MovieNerds** | [movienerds.site](https://movienerds.site) | Movie · TV Series | 🇬🇧 EN |
| **RedFlix** | [redflix.one](https://redflix.one) | Movie · TV Series · Anime | 🇬🇧 EN · 🇮🇳 HI |
| **Vivarium** | [vivarium.su](https://vivarium.su) | Movie · TV Series · Anime | 🇬🇧 EN |
| **NartoDrama** | [narto-drama.com](https://narto-drama.com) | Short Drama · Movie · Anime | 🇬🇧 EN |
| **Almostott** | [almostott.aethoflix.world](https://almostott.aethoflix.world) | Movie · TV Series · Anime | 🇬🇧 EN |
| **Mmsvibex** | [mmsvibex.fit](https://mmsvibex.fit) | Movie · TV Series | 🇮🇳 HI |
| **KiligDrama** | [kiligdrama.com](https://kiligdrama.com) | Short Drama | 🇬🇧 EN |
| **Vedu** | Vedu app sources (no Bollywood/Pikashow, YouTube or FlixCloud) | Movie · TV Series · Anime · Short Drama | 🇬🇧 EN |

### Music

| Extension | Site | Types | Language |
|-----------|------|-------|----------|
| **Monochrome** | [monochrome.st](https://monochrome.st) | Music — songs, albums, artists | 🇬🇧 EN |

### NSFW

| Extension | Site | Home rows · Search |
|-----------|------|--------------------|
| **XXXTube** | [x-x-x.tube](https://x-x-x.tube) | Featured · Latest · Top Rated · Most Popular · search |
| **Beeg** | [beeg.porn](https://beeg.porn) | Latest · Trending · Best · search |
| **XFantazy** | [xfantazy.com](https://xfantazy.com) | New · Trending · Most Viewed · search |
| **LatestPornVideo** | [latestpornvideo.com](https://latestpornvideo.com) | latest · categories · search |
| **Pornxday** | [pornxday.com](https://pornxday.com) | latest · categories · search |
| **FamilyPornHD** | [familypornhd.com](https://familypornhd.com) | latest · categories · search |
| **Freeuseporn** | [freeuseporn.com](https://www.freeuseporn.com) | latest · categories · search |
| **Taboodaddy** | [taboodaddy.com](https://taboodaddy.com) | Latest · Taboo · Brazzers · BangBros · Naughty America · Reality Kings · Mom & Son · Brother & Sister · search |
| **Tabooporn** | [tabooporn.to](https://tabooporn.to) | Latest · Most Popular · Top Rated · Latest Updates · search |
| **Youperv** | [youperv.com](https://youperv.com) | Latest · Anal · Big Dick · Amateur · search |
| **Porneec** | [porneec.com](https://porneec.com) | Latest · search |
| **Desihub** | [desihub.sh](https://desihub.sh) | Explore · Feed · search |
| **SuperJav** | [supjav.com](https://supjav.com) | censored · uncensored · subtitled · search |
| **Archivebate** | [archivebate.com](https://archivebate.com) | archived cam shows · search |

> Home rows are paginated wherever the site allows it. A few sites build their listing through
> JavaScript and cannot be paged from a plain request — those modules return the first page only,
> which is why the row count above is worth reading as a list of what exists, not a promise of depth.

## 🛠 Build

Pushes to `main` trigger the [GitHub Actions](.github/workflows/build.yml) workflow, which compiles every module and publishes the `.cs3` files and `plugins.json` to the [`builds`](../../tree/builds) branch.

To build locally (JDK 17 + Android SDK):

```bash
./gradlew make makePluginsJson
```

## ➕ Add a new extension

1. Copy an existing module folder and rename it.
2. Edit its `build.gradle.kts` (version, description, tvTypes, iconUrl).
3. Write a `Plugin` and a `MainAPI` class.
4. Push — `settings.gradle.kts` picks up any folder with a `build.gradle.kts`.

**Before writing a provider**, capture the site with [CS3-Extension-Builder](https://github.com/khalsatorrent-droid/CS3-Extension-Builder): it records the real requests, the rendered DOM, and the whole response bodies, then generates a starting module. Most of the modules here started that way.

Two habits that save a lot of time:

- If a sibling module for a similar site already works, read it first and copy its approach. Several bugs here came from treating a new site as an unknown when a working pattern was sitting in the same repo.
- Prefer the app's own machinery over hand-rolled parsing. Cloudstream already ships extractors for most embed hosts, and a site's own player config usually states the real stream URL rather than something you have to reconstruct.

## ⚠️ Disclaimer

These extensions only index content hosted by third-party sites. The authors host no media and are not responsible for it.

<div align="center">Made with ❤️ by <a href="https://github.com/khalsatorrent-droid">khalsatorrent-droid</a></div>
