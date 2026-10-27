<div align="center">

# 🎬 Watchuu

**Cloudstream extensions, built automatically.**

[![Build](https://github.com/khalsatorrent-droid/Watchuu/actions/workflows/build.yml/badge.svg)](https://github.com/khalsatorrent-droid/Watchuu/actions/workflows/build.yml)
[![Cloudstream](https://img.shields.io/badge/Cloudstream-repository-blueviolet?logo=android&logoColor=white)](https://github.com/recloudstream/cloudstream)
![Language](https://img.shields.io/badge/Kotlin-2.4-7F52FF?logo=kotlin&logoColor=white)

</div>

---

## 📲 Install

**1. Open Cloudstream** → **Settings** → **Extensions** → **Add repository**

**2. Paste the repository link:**

```
https://raw.githubusercontent.com/khalsatorrent-droid/Watchuu/main/repo.json
```

> Prefer a shortcode? Create one for the link above at [cutt.ly](https://cutt.ly) and enter it in the same box, then list it here: `SHORTCODE_HERE`

**3.** Tap the repository and install the extensions you want.

## 🧩 Extensions

| Extension | Site | Types | Language | Status |
|-----------|------|-------|----------|--------|
| **CineLite** | [cinelite.xyz](https://cinelite.xyz) | Movie · TV Series | 🇬🇧 EN | ✅ Beta |
| **Monochrome** | [monochrome.st](https://monochrome.st) | Music | 🇬🇧 EN | ✅ Beta |
| **KiligDrama** | [kiligdrama.com](https://kiligdrama.com) | Short Drama | 🇬🇧 EN | ✅ Beta |
| **RedFlix** | [redflix.one](https://redflix.one) | Movie · TV Series · Anime | 🇬🇧 EN · 🇮🇳 HI | ✅ Beta |
| **NartoDrama** | [narto-drama.com](https://narto-drama.com) | Short Drama · Movie · Anime | 🇬🇧 EN | ✅ Beta |
| **Vivarium** | [vivarium.su](https://vivarium.su) | Movie · TV Series · Anime | 🇬🇧 EN | ✅ Beta |
| **Almostott** | [almostott.aethoflix.world](https://almostott.aethoflix.world) | Movie · TV Series · Anime | 🇬🇧 EN | ✅ Beta |
| **Vedu** | Vedu app resolvers (Orbit · Nova) | Movie · TV Series · Anime | 🇬🇧 EN | ✅ Beta |

## 🛠 Build

Pushes to `main` trigger the [GitHub Actions](.github/workflows/build.yml) workflow, which compiles every module and publishes the `.cs3` files and `plugins.json` to the [`builds`](../../tree/builds) branch.

To build locally (JDK 17 + Android SDK):

```bash
./gradlew make makePluginsJson
```

## ➕ Add a new extension

1. Copy the `CineLite` folder and rename it.
2. Edit its `build.gradle.kts` (version, description, tvTypes, iconUrl).
3. Write a `Plugin` and a `MainAPI` class.
4. Push — `settings.gradle.kts` picks up any folder with a `build.gradle.kts`.

## ⚠️ Disclaimer

These extensions only index content hosted by third-party sites. The authors host no media and are not responsible for it.

<div align="center">Made with ❤️ by <a href="https://github.com/khalsatorrent-droid">khalsatorrent-droid</a></div>
