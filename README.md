# Watchuu – Cloudstream extensions

Cloudstream plugin repository. Built automatically by GitHub Actions into the `builds` branch.

## Extensions
| Module | Site | Types |
|---|---|---|
| CineLite | cinelite.xyz | Movie, TvSeries |

## Install
Add this repository URL in Cloudstream (Settings → Extensions → Add repository):

`https://raw.githubusercontent.com/khalsatorrent-droid/Watchuu/builds/plugins.json`

## Add a new extension
Create a new folder with a `build.gradle.kts`, `src/main/AndroidManifest.xml` and a Plugin + MainAPI class (copy `CineLite`). `settings.gradle.kts` picks up every folder with a `build.gradle.kts` automatically.
