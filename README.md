# YT MP3 – Android

Paste or share a YouTube **or Spotify** link → get MP3s in `Download/YT-MP3`.

- **Spotify** track / album / playlist → the song list is read from Spotify (its audio is DRM-locked), each song is found on YouTube and saved as `SpotifyTitle_Language.mp3`. Public lists only (not private Liked Songs).

- **Single video** → `Download/YT-MP3/SongName_Language.mp3`
- **Playlist or channel** (turn on *Download the whole playlist / channel*) →
  `Download/YT-MP3/SongName_Language.mp3` (same folder as single songs), saved one by one as each song finishes
- Quality: MP3 320 / 256 / 192 / 128 kbps, or the original audio without re-encoding
- Cover art + title/artist tags embedded
- Runs in the background with a progress notification; links queue up
- Share straight from the YouTube app: **Share → YT MP3**

## Naming
`SongName` = YouTube Music track name if available, otherwise the title with clutter removed
(“Official Video”, “Lyrical”, “| Movie | Singer”, “4K” …).
`Language` = language word in the title → YouTube's language field → tags → script of the title
(ಕನ್ನಡ, हिन्दी, தமிழ் …) → description → `Unknown`.

## Build the APK (no Android Studio needed)
1. Create a **private** GitHub repo and push this folder to it.
2. GitHub Actions builds automatically (~5 min).
3. On your phone open the repo → **Releases** → download `YT-MP3-buildN.apk` → install
   (allow “Install unknown apps” for your browser once).

Every new build installs over the old one (same signing key — keep the repo private).

## If downloads stop working
Tap **Update engine** in the app (it also auto-updates yt-dlp once a day). YouTube changes often.

## Notes
- YouTube's audio is ~128–160 kbps at source; 320 kbps MP3 is bigger but not better.
- Downloading from YouTube is against its Terms of Service – use it for content you have the right to download.
- Built on [youtubedl-android](https://github.com/yausername/youtubedl-android) (yt-dlp + ffmpeg).
