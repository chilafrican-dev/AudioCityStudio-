# Build Audio City Studio 0.13.0 — Play It rebuild (Android APK)

This source project builds an Android application (`ug.audiocity.studio`), not an Apple AU/VST plugin or a Mac desktop app.

## Shared live MIDI and BeatPilot

Play It, Arrange, and the piano rolls use the shared `SynthPoly` event/synthesis path. Piano-roll note edits audition immediately; BeatPilot drum-note edits audition the selected sample. Arrange MIDI clips render live through the project transport and remain editable, with note timing, velocity, and instrument/sample-pack assignment saved in the project. BeatPilot bass and chord parts are editable MIDI clips aligned to its sampled drum clips.

The transport keeps audio clips and loops in the project mix while MIDI plays. Metronome playback follows its play/record settings independently of the recording count-in. Existing BeatPilot, chord pads, piano-roll features, and bundled instrument/sample libraries are retained; no Addictive instruments are included.

Host tests and an Android APK build have completed successfully. Device-level sound, touch, recording, and project-reopen checks are still required; no Android device or usable emulator is available in this workspace.

## Easiest route: GitHub Actions (cloud build)

1. Extract this ZIP. Create a **private** GitHub repository and upload/push the **contents** of the `CityStudioAndroid` folder (including the hidden `.github/workflows/build-apk.yml` file).
2. In the repository, open **Actions** -> **Build APK** -> **Run workflow**. A push to the main branch also starts the workflow.
3. The workflow installs Java 17, Android SDK Platform 35, Build Tools 35.0.0 and Android NDK 26.3.11579264. It runs the host tests and produces a development-signed APK with native AAudio support.
4. When the workflow succeeds, open its run page -> **Artifacts** -> **AudioCityStudio-apk**. Unzip the downloaded artifact and install `AudioCityStudio.apk` on a phone running Android 11 or later.

### Important: signing and updates

This uploaded source ZIP did NOT include `build/development.keystore`. Without an existing key, `build.sh` creates a fresh development signing key. APKs signed with different keys cannot be installed as updates over each other. If an earlier version of Audio City Studio is installed, you need its original signing key to update while preserving its app data. **Back up projects inside the app before any uninstall**. The CI workflow supports a GitHub repository secret named `KEYSTORE_BASE64` containing the base64-encoded existing keystore, when you have it. Keep keys and private recordings out of public repositories.

## Build locally with Android SDK

Install a JDK (Java 17+), Android SDK Platform 35, and Build Tools 35.0.0. Optionally install NDK 26.3.11579264 for native AAudio.

```bash
export ANDROID_SDK_ROOT=/path/to/Android/Sdk
# Optional native low-latency output:
export ANDROID_NDK_ROOT="$ANDROID_SDK_ROOT/ndk/26.3.11579264"
bash run-tests.sh
bash build.sh
```

The generated APK is `CityStudioAndroid/AudioCityStudio.apk`. Omit `ANDROID_NDK_ROOT` to build using the Android AudioTrack fallback only.

## Java-only tests without the Android SDK

On a machine with Java 17+:

```bash
./run-core-tests.sh
```

These are engine checks only. Full Android build and device-level microphone, MIDI, UI, codec and latency testing require Android SDK and an actual Android device (or functioning emulator).

## Verification completed in this workspace

- Installed Android SDK Platform 35, Build Tools 35.0.0, Platform Tools, and NDK 26.3.11579264.
- Both `bash run-core-tests.sh` and `ANDROID_SDK_ROOT=... bash run-tests.sh` passed, including the BeatPilot live-MIDI, multibar-drum, and sampled-groove regressions.
- Built `AudioCityStudio.apk` with native AAudio libraries for arm64-v8a, armeabi-v7a, and x86_64. `apksigner` verified the v3 signature.
- Device installation and physical sound, touch, recording, playback, and project-reopen tests are not verified: no Android device is attached, and this workspace has no usable emulator.

## Release considerations

The app offers a Freesound CC0 loop search. Per the existing README, Freesound's free API is limited to non-commercial usage; a monetized/commercial release needs appropriate API permission or must disable the online search. This is a development build; physical-device QA and separate production signing are needed before wider distribution.
