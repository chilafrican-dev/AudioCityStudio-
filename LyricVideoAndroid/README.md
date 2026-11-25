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
   - **🎧 AI listens (Luganda):** the app listens to the song and finds when every line and every word is sung ("block by block, word by word"). It runs a Luganda speech model on the phone: download it once (355 MB) from the Sync tab, then it works offline and songs never leave the phone. Lines it is unsure about are marked ⚠. Word times can be adjusted in the line editor by tapping a word at the playhead.
   - **✍️ Write the lyrics for me (Lyrics tab):** drafts Luganda lyrics from the song, using either Sunbird AI (online; needs your own free Sunbird API key; the song is sent to Sunbird in pieces of about 30 seconds, cut at quiet moments, with live progress and a working Cancel) or the on-phone model (offline, rough draft). Sunbird's text is then split into lines at the singer's pauses and timed word by word on the phone. The lyrics are shown for review before they replace anything.
4. **Style tab.**
   - **One-tap looks:** Clean pop, Neon night, Cinematic, Karaoke classic, Minimal, Social captions, Handwritten, Bold poster.
   - **Format:** portrait 9:16, 4:5 or 1:1; landscape 16:9, 21:9 cinema or 4:3. Optional cinematic black bars.
   - **13 words effects:** karaoke wipe, fade & rise, scrolling lyrics, typewriter, word pop, neon glow, blur focus, slide-up words, zoom burst, letter cascade, wave, cinematic, word highlighter.
   - **20 professional fonts** are bundled, and the picker shows each one: Montserrat, Inter, Poppins, Bebas Neue, Anton, Oswald, Archivo Black, Raleway, Space Grotesk, Playfair Display, DM Serif Display, Abril Fatface, Righteous, Bungee, Permanent Marker, Pacifico, Dancing Script, Great Vibes, Caveat and Lobster. You can also use the system font or import your own.
   - **Text:** size, letter spacing, position, text and highlight colours, soft shadow, outline, glow, caption box, all caps.
   - **Cover photo (square):** your artwork on top of the background.
     - Place it left, centre, right or top. The lyrics, and the visualizer under them, move to the free space automatically.
     - Shapes: square, rounded, circle, or a vinyl record that spins at 33⅓ rpm while the song plays.
     - Adjustable size, an optional pulse on the beat, an optional "Now playing" caption with title and artist, and a soft drop shadow.
     - Add it in the Song tab; any picture is centre-cropped to a square. One tap uses it as a blurred background too.
   - **Video background:** choose any video on the phone (Song tab).
     - It plays silently behind the lyrics and loops to the length of the song; your song is the only sound.
     - **Layout:** *Fill frame* (cropped), or *Whole video + blur*: the whole clip sharp in the middle over a blurred, darker copy of itself. Use it to show a landscape clip in a 9:16 video, or a vertical clip in 16:9, without cropping.
     - Darkening, blur and colour filter apply to it. The blur shows in the preview on Android 12 and newer, and always in the export.
     - In the export, each video frame is decoded by the phone's video decoder straight onto the graphics chip, and the lyrics, cover and other layers are blended over it.
   - **Background:** animated gradient (8 palettes), solid colour, or photos, or the cover photo blurred. Background photos have a blur slider from sharp to very soft (a smooth, Gaussian-like blur). Photos can be one photo or a cross-fading slideshow. They either fill the frame or show the whole photo on a blurred copy of itself. Colour filters: natural, black & white, warm, cool, vintage, dramatic. You can also set the slideshow timing, darkening, and slow zoom and pan.
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
./run-tests.sh   # lyric timing, LRC, alignment and audio-conversion tests, plain Java
./build.sh       # → LyricVideoStudio.apk
```

Requires Android 10 (API 29) or later, with OpenGL ES 2.0 (every Android 10 phone has it).

## The Luganda speech model

- **Model:** [`indonesian-nlp/wav2vec2-luganda`](https://huggingface.co/indonesian-nlp/wav2vec2-luganda), a wav2vec2 XLS-R model fine-tuned on Mozilla Common Voice Luganda. Its licence is **Apache-2.0**, so it can be used commercially.
- **Conversion:** `tools/export_aligner.py`, run by `.github/workflows/luganda-aligner-model.yml`, converts it to int8 ONNX (355 MB). The script refuses any model whose licence does not allow commercial use, and checks the converted model against the original on Google FLEURS Luganda speech: 99.1% frame agreement and a character error rate of 17.6%, mostly from numbers written as words and foreign names. It then publishes the model as the release [`luganda-aligner-v1`](https://github.com/chilafrican-dev/AudioCityStudio-/releases/tag/luganda-aligner-v1).
- **On the phone:**
  1. The song is decoded and mixed to mono. Rumble and kick drum below about 100 Hz are removed, and the audio is resampled to 16 kHz.
  2. The model runs in 15-second chunks with overlap, using ONNX Runtime (MIT licence).
  3. A forced alignment (CTC Viterbi) matches the lyrics letter by letter. It can skip ad-libs, intros and repeats that are not in the lyrics, and allows words that run together.
- **Accuracy so far:**
  - On a synthetic Luganda test song over a drum and bass beat, every word was placed within 0.1 s, and an ad-lib that was not in the lyrics was skipped.
  - On a real 3-minute Luganda song, listening took 19 s on a desktop CPU; expect about 1–2 minutes on a phone.
  - Writing lyrics from scratch is much weaker than timing known lyrics: the draft has real Luganda words and phrases, but singing over a full beat garbles some of them. Treat it as a draft to correct.

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
| `Ctc.java` | Forced alignment of lyrics to the model's per-frame letter probabilities (with skippable ad-libs and word gaps) and greedy transcription. Plain Java, tested in `tests/AlignTests.java`. |
| `Audio16k.java` | Song audio to 16 kHz mono for the model (high-pass, windowed-sinc resampling). Plain Java, tested. |
| `AutoTiming.java` | Applies alignment results to lines and words, fills unplaceable words, and splits a transcript into screen lines at pauses. Plain Java, tested. |
| `SpeechModel.java`, `ModelStore.java`, `AutoSync.java` | Run the ONNX model in chunks; download and store the model; tie decoding, listening, timing and writing together. |
| `Sunbird.java` | Sunbird AI speech-to-text upload (Luganda, `lug`). |
| `VideoLayer.java`, `VideoBackdrop.java` | Background video: decoded to an OpenGL texture for the export (looping, centre-cropped, colour filter and blur shader), and a muted TextureView player that follows the song in the preview. |
| `MainActivity.java` | Home screen, editor tabs, playback, full-screen sync mode, font picker and export UI. |

## Status and limits

- Sunbird's API was integrated from its public documentation (endpoint `POST /tasks/audio/transcriptions`, fields `audio` and `language=lug`, reply field `audio_transcription`), but it could not be called from the development environment. The first real call needs a check with your key.
- The Java sources type-check against the Android 14 framework API, the host tests pass, and sample frames of every effect, font, photo layout and format have been rendered through Android's graphics stack and checked. On-device playback, sync timing, video export and the video background (which needs the phone's video decoder and graphics chip) have **not yet been tested on a phone**.
- The export runs while the app stays open (the screen is kept on). Leaving the app for a long time may let Android stop the render; it can then simply be restarted.
- A full song at 1080p/30 fps typically takes a few minutes to render, depending on the phone. 720p is roughly twice as fast; 4K takes several times longer.
- Only use songs, lyrics and photos you have the rights to publish.
