# Lyric Video Studio (Audio City) for Android

A standalone Android app (`ug.audiocity.lyrics`) for making lyric videos on a phone: choose a song, add the words, tap along to sync them, pick a look, and export an MP4 ready for Reels, TikTok, YouTube Shorts, WhatsApp Status or YouTube.

It is separate from Audio City Studio (the DAW in `../CityStudioAndroid`) and installs alongside it.

## How it works

1. **Song tab.** Choose an audio file (MP3, M4A/AAC, WAV, FLAC, OGG or Opus). The app copies it into the project and analyses its loudness, which drives the visualizer. Set the title and artist for the opening title card. You can also add a background photo, such as cover art.
2. **Lyrics tab.** Paste or type the lyrics, one line on screen per row. You can also import a `.lrc` or `.txt` file; pasted or imported LRC keeps its timings. Editing a line keeps the timings of the other lines. **Save as .lrc** exports the synced lyrics.
3. **Sync tab.** Press **Start over**, then tap the big button the moment each line starts. Use **Undo last tap** and **Continue** to fix mistakes. Tap any line to fine-tune it: ±0.1 s, set it to the playhead, play from there, edit the text, or insert or delete lines. **Shift all…** moves every timing at once, and the reaction-compensation slider (default 120 ms) corrects for late taps.
4. **Style tab.**
   - Format: 9:16, 16:9 or 1:1.
   - Animation: karaoke wipe, fade & rise, scrolling lyrics, typewriter or word pop.
   - Font: six built-in fonts, or import your own `.ttf`/`.otf`.
   - Text: size, position, colours, highlight colour, shadow, all caps.
   - Background: animated gradient (8 palettes), solid colour, or photo with a slow zoom and adjustable darkening.
   - Extras: audio visualizer, progress bar, title card.
   - Video: 720p or 1080p; 24, 30 or 60 fps.
5. **Export video.** The app renders H.264 video with AAC audio on the phone's hardware encoder. The video is saved to **Movies/Lyric Videos**, and can then be shared or watched straight away.

The live preview at the top uses the same drawing code as the exporter, so the exported video looks like the preview. Tap the preview to play or pause.

Projects are saved automatically on the phone. Long-press a project on the home screen to rename, duplicate or delete it.

## Build

The app uses the same plain Android SDK toolchain as Audio City Studio (no Gradle).

### GitHub Actions

`.github/workflows/lyric-video-apk.yml` at the repository root runs on every push that changes `LyricVideoAndroid/`, and can also be run by hand (**Actions → Lyric Video Studio APK → Run workflow**). It runs the tests and builds `LyricVideoStudio.apk`, which you can download from the run's **Artifacts**.

To keep the same signing key between builds, so that updates install over each other, store a base64-encoded keystore with alias `lyrics` and password `android` in a repository secret named `LYRICS_KEYSTORE_BASE64`. Without it, each CI run signs with a new key.

### Locally

Requirements: JDK 17+, Android SDK Platform 35 and Build Tools 35.0.0.

```bash
export ANDROID_SDK_ROOT=/path/to/Android/Sdk
./run-tests.sh   # lyric timing / LRC tests, plain Java
./build.sh       # → LyricVideoStudio.apk
```

Requires Android 10 (API 29) or later, with OpenGL ES 2.0 (every Android 10 phone has it).

## Code map

| File | What it does |
|---|---|
| `Lyrics.java` | Lines and timings, LRC import/export, edit-preserving text updates, and per-frame timing (current line, wipe progress, fades, instrumental gaps). Plain Java, covered by `tests/LyricsTests.java`. |
| `Style.java` | All look settings and output sizes. |
| `Project.java` | Project folder: `project.json`, copied song, photo, font and loudness envelope. |
| `PcmDecoder.java` | Decodes any supported audio to PCM (MediaExtractor + MediaCodec); builds the loudness envelope. |
| `LyricRenderer.java` | Draws one frame (background, visualizer, title card, five lyric animations) on any Canvas. |
| `VideoExporter.java` | AAC audio encode, then a frame-by-frame render through EGL into the H.264 encoder, muxed to MP4. |
| `MainActivity.java` | Home screen, editor tabs, playback, tap-sync and export UI. |

## Status and limits

- The Java sources type-check against the Android 14 framework API, and the host tests pass. The APK build, on-device playback, sync and video export have **not yet been tested on a phone**.
- The export runs while the app stays open (the screen is kept on). Leaving the app for a long time may let Android stop the render; it can then simply be restarted.
- A full song at 1080p/30 fps typically takes a few minutes to render, depending on the phone. 720p is roughly twice as fast.
- Only use songs, lyrics and photos you have the rights to publish.
