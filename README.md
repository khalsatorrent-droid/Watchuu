<div align="center">

# 🎬 Watchuu

**Cloudstream extensions, built automatically.**

[![Build](https://github.com/khalsatorrent-droid/Watchuu/actions/workflows/build.yml/badge.svg)](https://github.com/khalsatorrent-droid/Watchuu/actions/workflows/build.yml)
[![Cloudstream](https://img.shields.io/badge/Cloudstream-repository-blueviolet)](https://github.com/recloudstream/cloudstream)
[![Language](https://img.shields.io/badge/Kotlin-2.4-7F52FF)](https://kotlinlang.org)

**45 extensions** · movies, TV, anime, short drama, music and more

</div>

---

## 📲 Install

**1. Open Cloudstream** → **Settings** → **Extensions** → **Add repository**

**2. Paste the repository link:**

```
https://raw.githubusercontent.com/khalsatorrent-droid/Watchuu/main/repo.json
```

**3.** Tap the repository and install what you want.

Extensions update themselves from the same link — when a site changes and a module is fixed, bumping
its version makes Cloudstream offer the update.

## 🧩 What is inside

The repository carries **45 modules**. Each one appears in Cloudstream under its own name with its own
settings; this page deliberately does not list them.

| Type tag | Modules |
|----------|---------|
| Movie | 14 |
| TV series | 13 |
| Anime, including anime movies | 8 |
| Asian and short drama | 3 |
| Music, lossless | 4 |
| Adult | 26 |
| Other catalogue types | 4 |

A module can carry more than one tag, so these do not add up to 45.

Most modules are English; a handful are Hindi. Every module is installable on its own — take the ones
you want and ignore the rest.

Some modules keep settings of their own: a sign-in for sites that need an account, a region or
storefront, or a log of what the module is doing. Open **Extensions → the module → settings** when a
module asks for one.

> Home rows are paginated wherever the site allows it.

## 🛠 Build

Pushes to `main` trigger the [GitHub Actions](.github/workflows/build.yml) workflow, which compiles
every module and publishes the `.cs3` files and `plugins.json` to the [`builds`](../../tree/builds)
branch.

To build locally (JDK 17 + Android SDK):

```bash
./gradlew make makePluginsJson
```

## ➕ Add a new extension

1. Copy an existing module folder and rename it.
2. Edit its `build.gradle.kts` (version, description, tvTypes, iconUrl).
3. Write a `Plugin` and a `MainAPI` class.
4. Push — `settings.gradle.kts` picks up any folder with a `build.gradle.kts`.

**Before writing a provider**, capture the site with
[CS3-Extension-Builder](https://github.com/khalsatorrent-droid/CS3-Extension-Builder): it records the
real requests, the rendered DOM, and the whole response bodies, then generates a starting module.

Two habits that save a lot of time:

- If a sibling module for a similar site already works, read it first and copy its approach. Several
  bugs here came from treating a new site as an unknown when a working pattern was sitting in the same
  repository.
- Prefer the app's own machinery over hand-rolled parsing. Cloudstream already ships extractors for
  most embed hosts, and a site's own player config usually states the real stream URL rather than
  something you have to reconstruct.
- When a site refuses a plain HTTP client but works in a browser, the request has to be made the way
  the browser makes it. Keep a step-by-step log inside the module's settings so the next failure can
  be read off the screen instead of guessed at.

---

## ⚖️ Legal Disclaimer

> **These extensions do not host, store, upload, or distribute any media content of any kind.**

They work exactly like a web browser: they connect to publicly accessible third-party websites, read
the HTML those sites serve to any visitor, and display the results inside the Cloudstream app. Every
video, thumbnail, and piece of metadata comes entirely from those external third-party sources — this
repository never touches it.

- The developer **does not** own, operate, control, or endorse any of the third-party websites these
  extensions access.
- The developer **bears no responsibility** for the content, legality, accuracy, or availability of
  anything served by those third-party sites.
- The developer **makes no warranties** of any kind regarding this software.
- **You are solely responsible** for ensuring your use of this software complies with the laws of your
  jurisdiction.
- If you are a rights holder and believe a linked third-party site infringes your intellectual
  property, contact that site directly — this repository has no control over third-party content.

By installing or using these extensions, you acknowledge and accept the above.

<div align="center">Made with ❤️ by <a href="https://github.com/khalsatorrent-droid">khalsatorrent-droid</a></div>
