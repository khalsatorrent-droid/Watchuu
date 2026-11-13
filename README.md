<div align="center">

# 🎬 Watchuu

**Cloudstream extensions, built automatically.**

[![Build](https://github.com/khalsatorrent-droid/Watchuu/actions/workflows/build.yml/badge.svg)](https://github.com/khalsatorrent-droid/Watchuu/actions/workflows/build.yml)
[![Cloudstream](https://img.shields.io/badge/Cloudstream-repository-blueviolet
![Language](https://img.shields.io/badge/Kotlin-2.4-7F52FF

**25 extensions** · movies, TV, anime, short drama and music

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
| **Almostott** | [almostott.aethoflix.world](https://almostott.aethoflix.world) | Movie · TV Series · Anime | 🇬🇧 EN |
| **CineLite** | [cinelite.xyz](https://cinelite.xyz) | Movie · TV Series · Anime | 🇬🇧 EN |
| **Cinesrc** | — | Movie · TV Series | 🇬🇧 EN |
| **DesireMovies** | — | Movie · TV Series | 🇮🇳 HI |
| **FlixVision** | — | Movie · TV Series | 🇬🇧 EN |
| **KiligDrama** | [kiligdrama.com](https://kiligdrama.com) | Short Drama | 🇬🇧 EN |
| **MovieNerds** | [movienerds.site](https://movienerds.site) | Movie · TV Series · Anime | 🇬🇧 EN |
| **NartoDrama** | [narto-drama.com](https://narto-drama.com) | Short Drama · Movie · Anime | 🇬🇧 EN |
| **RedFlix** | [redflix.one](https://redflix.one) | Movie · TV Series · Anime | 🇬🇧 EN |
| **Ssrmovies** | — | Movie · TV Series | 🇮🇳 HI |
| **Vedu** | Vedu app sources | Movie · TV Series · Anime · Short Drama | 🇬🇧 EN |
| **Vivarium** | [vivarium.su](https://vivarium.su) | Movie · TV Series · Anime | 🇬🇧 EN |

### Music

| Extension | Site | Types | Language |
|-----------|------|-------|----------|
| **Monochrome** | [monochrome.st](https://monochrome.st) | Music — lossless, songs/albums/artists | 🇬🇧 EN |
| **MusicBox** | — | Music — Spotify catalogue, 6 providers | 🇬🇧 EN |

> Home rows are paginated wherever the site allows it.

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

---

## ⚖️ Legal Disclaimer

> **These extensions do not host, store, upload, or distribute any media content of any kind.**

They work exactly like a web browser: they connect to publicly accessible third-party websites, read the HTML those sites serve to any visitor, and display the results inside the Cloudstream app. Every video, thumbnail, and piece of metadata comes entirely from those external third-party sources — this repository never touches it.

- The developer **does not** own, operate, control, or endorse any of the third-party websites these extensions access.
- The developer **bears no responsibility** for the content, legality, accuracy, or availability of anything served by those third-party sites.
- The developer **makes no warranties** of any kind regarding this software.
- **You are solely responsible** for ensuring your use of this software complies with the laws of your jurisdiction.
- If you are a rights holder and believe a linked third-party site infringes your intellectual property, contact that site directly — this repository has no control over third-party content.

By installing or using these extensions, you acknowledge and accept the above.

<div align="center">Made with ❤️ by <a href="https://github.com/khalsatorrent-droid">khalsatorrent-droid</a></div>
