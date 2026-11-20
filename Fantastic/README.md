# Fantastic

QQ Music catalogue and audio through <https://music.1412.tech/>. Home rows are
paginated QQ genre searches, not official charts/new releases (those site views
use Apple Music). Search includes songs, albums, playlists and artists. Albums,
playlists and artist songs load as episodes; artist albums appear as recommendations.
Search returns up to 20 results per category. Playlist/artist track pagination follows
the backend's 10,000-offset ceiling.

## Playback feasibility

The server's `/qq/search?type=song&q=Hello` returned Adele's `002Il7Ya1tZ6UZ`.
`/qq/song/{id}/play?quality=HQ` redirects to a signed QQ CDN MP3. That CDN
returned HTTP 403 from this machine, including with QQ and Fantastic referers.
The site's **existing** `/qq/song/{id}/download?quality=HQ` proxy returned HTTP
200 `audio/mpeg`, 11,823,226 bytes, beginning with `ID3`. The provider uses this
proxy rather than the blocked CDN. It is raw audio despite the attachment header.
The proxy ignores Range requests, so seeking/resuming may be limited.

Quality codes are case-sensitive. The provider offers available MP3 (HQ/MP3128),
AAC (WEB/AAC192/AAC48), Ogg and standard FLAC (SQ) only. Their formats are
supported by ExoPlayer; actual CloudStream/device playback remains unverified.
Availability metadata is not a guarantee that an individual upstream file works.
No AC-4, proprietary NAC, DTS or spatial variants are exposed.

Apple full tracks are deliberately excluded: `web/wrapper.js::songMaster` and
`web/decrypt/decrypt.js` in Fantastic require client-side decryption, including
the AAC fallback. The Apple catalogue also exposes short previews (a Hello preview
returned HTTP 206 for a range request), but these are not full songs and are not
silently substituted. No Apple catalogue entries are mixed into playable QQ results.

## Checks

```sh
./gradlew Fantastic:make
python3 Fantastic/tests/smoke.py
```

The standalone standard-library check exercises live searches and details for all
four types, empty/error search responses, and search → quality → proxy audio bytes.
It does not launch CloudStream or validate every quality on every song.
