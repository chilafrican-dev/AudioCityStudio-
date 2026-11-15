# Audio City Studio 0.12.5 — FreePats Piano Collection

The Android source now includes **five sampled piano instruments**, using the existing
SFZ/WAV offline sampler. All factory packs install automatically into app-private
storage on first launch and remain available without internet.

| Picker name | Source | Licensed | Notes |
| --- | --- | --- | --- |
| FM Electric Piano 2 | FreePats / Roberto | CC0 | 12 WAV samples; retained from 0.12.4 |
| YDP Grand Piano · Compact | FreePats / Roberto / Zenph OLPC | CC BY 3.0 | 5 velocity layers, 150 SFZ regions, 121 WAV samples. Tail shortened to max 2.2 seconds; original SoundFont is not bundled; retain attribution. |
| Kawai Upright Piano · Full | FreePats / Gonzalo and Roberto | CC0 | 66 stereo WAV samples, two velocity layers; high RAM usage on older phones. |
| Kawai Upright Piano · Lite | FreePats / Gonzalo and Roberto | CC0 | 26 compact samples, originally FLAC, converted to WAV. |
| Kawai Upright Piano · Bright | FreePats / Gonzalo and Roberto | CC0 | 26 brighter compact samples, originally FLAC, converted to WAV. |

Original uploaded archives are alternative file formats of the same recordings;
we intentionally avoid duplicating SF2, FLAC and WAV copies in the APK.

## Playback and storage

- These instruments are playable in **Sounds → Play / Touch Instrument → Choose playable instrument**.
- The chord pads, metronome, beat playback and MIDI take functionality are preserved.
- High-quality full upright uses approximately 120 MB for PCM in memory when selected.
  On devices with a small Android heap it may fail to load; use the Lite/Bright piano.
- Real instrument loading runs on the background executor. First launch extracts
  each bundled ZIP into app-private storage; plan for extra free phone space.
- If a factory install fails (for example, insufficient disk space), the project
  still opens and remaining available instruments can be used.
- SF2 files are *not directly supported* by the current Android sampler. The grand
  piano was converted from SF2 into a compatible compact SFZ/WAV instrument.

The updated build requires an Android SDK/NDK build and real-device testing.
To validate the Java-side sample mappings, run `./run-core-tests.sh`.

### Build distribution note

This complete source package contains about 125 MB of compressed audio assets.
It is not an APK. GitHub Codespaces can extract the ZIP with `unzip -o` and run
`./build.sh` after Android SDK/NDK setup. The `run-core-tests.sh` suite validates
all four new factory pianos, including multisample mapping and rendered PCM.

If you already installed an APK with a different signing key, back up projects
before reinstalling. The app's current version is 0.12.5, versionCode 18.
