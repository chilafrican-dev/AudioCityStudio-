# Lyric Video Studio (Audio City) for Android

A standalone Android app (`ug.audiocity.lyrics`) for making lyric videos on a phone: choose a song, add the words, tap along to sync them, pick a look, and export an MP4 ready for Reels, TikTok, YouTube Shorts, WhatsApp Status or YouTube.

It is separate from Audio City Studio (the DAW in `../CityStudioAndroid`) and installs alongside it.

## How it works

1. **Song tab.** Choose an audio file (MP3, M4A/AAC, WAV, FLAC, OGG or Opus). The app copies it into the project and analyses its loudness, which drives the visualizer. Set the title and artist for the opening title card. Add background photos here too: pick one or many at once (up to 20), then tap a thumbnail to move or remove it.
2. **Lyrics tab.**
   - **Paste** puts copied lyrics straight in, replacing or adding to what's there.
   - **Clean up** removes labels such as `[Chorus]` and `Verse 2:`, stray symbols and timestamps, and splits rows that are too long for the screen at a natural break. The app offers to do this when pasted text needs it.
   - **Import file** reads `.lrc` or `.txt`, and LRC keeps its timings. **Save as .lrc** exports your synced lyrics.
   - Typing works as normal: one row is one line on screen. Editing a line keeps the timings of the others.
3. **Sync tab.**
   - **Sync mode** fills the screen with big text showing the line to tap, the one before and the one after. Tap anywhere as each line begins; the tap counts the moment your finger lands. It also has slow-down playback (1×, 0.75×, 0.5×), a back-5-seconds button and Undo.
   - **Quick sync** spreads every untimed line between a start and end time in proportion to its length, in seconds; you then fix only the lines that are off.
   - Tap any line in the list to fine-tune it: ±0.1 s, set it to the playhead, play from there, edit the text, or insert or delete lines. **Shift all…** moves every timing at once. A reaction-compensation slider corrects for late taps and scales with playback speed.
4. **Style tab.**
   - **One-tap looks:** Clean pop, Neon night, Cinematic, Karaoke classic, Minimal, Social captions, Handwritten, Bold poster.
   - **Format:** portrait 9:16, 4:5 or 1:1; landscape 16:9, 21:9 cinema or 4:3. Optional cinematic black bars.
   - **13 words effects:** karaoke wipe, fade & rise, scrolling lyrics, typewriter, word pop, neon glow, blur focus, slide-up words, zoom burst, letter cascade, wave, cinematic, word highlighter.
   - **20 professional fonts** are bundled, and the picker shows each one: Montserrat, Inter, Poppins, Bebas Neue, Anton, Oswald, Archivo Black, Raleway, Space Grotesk, Playfair Display, DM Serif Display, Abril Fatface, Righteous, Bungee, Permanent Marker, Pacifico, Dancing Script, Great Vibes, Caveat and Lobster. You can also use the system font or import your own.
   - **Text:** size, letter spacing, position, text and highlight colours, soft shadow, outline, glow, caption box, all caps.
   - **Background:** animated gradient (8 palettes), solid colour, or photos. Photos can be one photo or a cross-fading slideshow. They either fill the frame or show the whole photo on a blurred copy of itself. Colour filters: natural, black & white, warm, cool, vintage, dramatic. You can also set the slideshow timing, darkening, and slow zoom and pan.
   - **Extras:** audio visualizer, progress bar, title card.
   - **Video:** 720p, 1080p or 4K at 24, 30 or 60 fps. If the phone's encoder can't handle a size, such as 4K or 21:9 on older phones, the export uses the best size it supports.
5. **Export video.** The app renders H.264 video with AAC audio on the phone's hardware encoder. The video is saved to **Movies/Lyric Videos**, and can then be shared or watched straight away.

The live preview at the top uses the same drawing code as the exporter, so the exported video looks like the preview. Tap the preview to play or pause.

Projects are saved automatically on the phone. Long-press a project on the home screen to rename, duplicate or delete it. Projects made with version 0.1 open unchanged: their single background photo becomes the first slideshow photo and their font is mapped to the closest bundled font.

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
| `Lyrics.java` | Lines and timings, LRC import/export, clean-up of pasted lyrics, Quick sync, edit-preserving text updates, and per-frame timing (current line, wipe progress, fades, instrumental gaps). Plain Java, covered by `tests/LyricsTests.java`. |
| `Style.java` | All look settings, formats, presets and output sizes. |
| `Fonts.java` | Loads the bundled fonts from `assets/fonts` (licences in `assets/fonts/licenses`: SIL Open Font Licence 1.1, Permanent Marker Apache 2.0). |
| `PhotoSet.java` | Background photos for the slideshow, decoded a few at a time, with a soft blurred copy for the "whole photo" layout. |
| `Project.java` | Project folder: `project.json`, copied song, photo, font and loudness envelope. |
| `PcmDecoder.java` | Decodes any supported audio to PCM (MediaExtractor + MediaCodec); builds the loudness envelope. |
| `LyricRenderer.java` | Draws one frame on any Canvas: background (gradient, colour, photo slideshow with filters), visualizer, letterbox, title card, the 13 word effects and the text effects. |
| `VideoExporter.java` | AAC audio encode, then a frame-by-frame render through EGL into the H.264 encoder, muxed to MP4. |
| `MainActivity.java` | Home screen, editor tabs, playback, full-screen sync mode, font picker and export UI. |

## Status and limits

- The Java sources type-check against the Android 14 framework API, the host tests pass, and sample frames of every effect, font, photo layout and format have been rendered through Android's graphics stack and checked. On-device playback, sync timing and video export have **not yet been tested on a phone**.
- The export runs while the app stays open (the screen is kept on). Leaving the app for a long time may let Android stop the render; it can then simply be restarted.
- A full song at 1080p/30 fps typically takes a few minutes to render, depending on the phone. 720p is roughly twice as fast; 4K takes several times longer.
- Only use songs, lyrics and photos you have the rights to publish.
