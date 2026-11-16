# Audio City Studio 0.13.18 — users' own drum packs

Users can now import compatible single WAV files or ZIP archives of WAVs through Sounds → Import your drum packs, or Drum rack → Tools → Import your drum pack (WAV / ZIP). Imported libraries appear by name in every lane's pack selector.

- Browse all sounds, search names/folders, or filter Kick/Snare/Clap/Hat/Percussion/Other. Categories are suggestions; any sound can be assigned to any lane. Audition first, then explicitly choose Use sound. Cancel does not change the lane.
- Accepts mono/stereo 16/24-bit PCM or 32-bit PCM/float WAV at supported 8–192 kHz rates; playback data is converted to the engine's stereo 48 kHz format. Nested ZIP folders and original sample names are retained. SFZ mappings and non-WAV audio are not used by this drum importer; use the separate instrument importer for SFZ instruments. RAR/7z packs must first be extracted/repacked as ZIP.
- Imports use staging and publish a library only after all WAVs decode. Limits: 32 MB per WAV, 512 MB total unpacked/converted pack data, up to 1000 WAVs and 3000 archive entries. Unsafe paths, duplicate paths and invalid audio are rejected without partially installing a pack. README/text data are retained in the installed library, with excerpts in each sound's source metadata.
- Sound choices use stable portable file references and saved display names in v5 grid data. Legacy/factory-only patterns continue using the existing formats. Copies, resizing, independent lane rendering and tempo changes preserve personal choices.
- The selected sound is copied into the project. Portable backups include these selected snapshots, so restored patterns play without the original globally installed pack. The whole imported library is not added to every project or bundled into the APK.
- Existing Soundza defaults, the prepared Addictive Afrobeat importer, earlier clip spacing repairs and melody-length fixes remain available.

Validation: full host suite passed, including 40 generic user-pack checks: nested WAV ZIPs, mono 24-bit and stereo PCM/float sample rates, labels/categories, repeated imports, assigning to all lanes, v5 round trips and tempo changes, copied patterns, exact waveform preservation after backup/restore, factory switching, malformed files, traversal rejection and unsupported formats. APK compilation/signature checks passed. Native Android picker, search-list layout, touch and audio output still require phone verification. GitHub was not updated.

---

# Audio City Studio 0.13.17 — uploaded Addictive Afrobeat drums

The user's Addictive Afrobeat Sample Pack 1.rar was recovered and inspected. Its 14 drum/percussion one-shots were not connected to the channel rack. The archive also contains full loops, stems, FX and vocal material; those are not relabelled as isolated drum hits.

- Each drum lane's dropdown now opens a pack selector: Soundza, Addictive Afrobeat, or Import Afro drum pack. Sounds can be auditioned before choosing Use sound, and Cancel restores the current lane sound.
- The 14 original one-shot WAVs were decoded/resampled into AudioCity-Addictive-Afrobeat-Drums.acpack (approximately 4.8 MB). The original README is retained in its source metadata. It is a personal import with the supplied terms, not a new CC0 factory library.
- Install the updated APK, then open the drum rack → Tools → Import Afro drum pack, and select the .acpack file. On a lane, tap ▾ → Addictive Afrobeat and select the sound. The optional audio is not embedded in the APK.
- Personal sample choices have stable reserved IDs 1000–1013; existing factory IDs remain unchanged. Choices survive pattern copies, resize, project reopen and backup.
- A selected Afro pack is copied into the project, and portable .acstudio backups include it. Restored patterns can load it without a global installation. Missing packs report an import instruction rather than silently substituting factory samples. Reloading a different kit pauses the old audition source while loading.
- A bounded installer validates the expected 14-region map and installs atomically. The offline Soundza defaults and all prior spacing/melody fixes remain supported.

Validation: complete host suite passed, including 42 optional-pack/import/choice/backup checks. All 14 real uploaded one-shots decoded successfully, the generated payload was imported and read back, and APK compilation/signature checks passed. Native phone picker/UI/audio testing remains pending. The original loops/stems remain in the original uploaded archive and are not included in this one-shot import. GitHub was not updated.

---

# Audio City Studio 0.13.16 — repeated pattern spacing and melody lengths

The first supplied screenshot was reproduced: changing a one-bar pattern to eight bars enlarged every repeated clip while leaving starts one bar apart. The second screenshot showed a short Touch performance filling the loop. Early manual stops were incorrectly forced to the entire captured loop end.

- Full consecutive pattern repeats are re-spaced when the pattern grows or shrinks. Eight-bar copies start at bars 1, 9, 17, etc. Partial overlap is rejected when placing another instance.
- A captured loop end is now a recording limit. A manually stopped melody fits the bar where recording ended instead of extending to the complete loop; automatically completed loop takes retain the full range.
- Reprinting edited MIDI notes preserves the original Arrange start, visible length, source offset, gain and fades. Enough source audio is printed to cover that window; an instrument's release tail cannot lengthen it.
- Main project menu: Repair clip spacing & lengths. This re-spaces already-overlapping full same-pattern copies on their own drum channels and trims empty ends on unrenamed captured live Touch/MIDI performance clips to their last played note's bar. Custom MIDI regions and renamed clips are not auto-trimmed. Source audio and notes are retained, and the entire repair has one Undo snapshot.
- Pattern repairs are planned before applying changes. New collisions with separately positioned clips or the project limit are reported without partial changes. Individually trimmed clips and unrelated tracks remain in place.

Validation: both primary regressions failed before their fixes and passed afterward. Full host suite passed, including 267 pattern-repeat spacing checks and 119 melody length checks. Coverage includes all eight drum lanes, growth/shrink, repeat repair, Undo snapshots, reopening, partial placement overlap, limits, unrelated sections, early/full-loop stops at multiple tempos/meters, repeated reprinting, gain/fades, tail repair and custom-region preservation. APK compilation/signature/integrity checks passed using the existing development key. Physical phone UI/touch/audio still require device testing. Installing this update does not alter existing phone projects until the user invokes the repair command. GitHub was not updated.

---

# Audio City Studio 0.13.15 — drum-rack behavior fixes

- Step taps and velocity editing now retain the bar displayed at finger-down, including a bar change before Android delivers a click or long-press callback. Cancelled scroll/touch gestures discard that target; accessibility clicks use the displayed bar.
- Extending a manual pattern adds empty bars. Repeating is an explicit Pattern tools action, with a confirmation that captures the chosen bar. Shortening a pattern warns before discarding occupied bars.
- Step edits do not produce an extra immediate audition while Pattern or Song playback runs. Tapping channel names and choosing samples still auditions intentionally.
- Placing the same pattern at an already occupied placement is rejected before printing files or partly adding repeated instances, preventing accidental doubling. New placements at free bars remain supported.
- Tapping the already-active Pattern/Song button no longer pauses and restarts transport.

Validation: full host suite passed, including 779 drum-rack behavior checks, existing sample/backup/MIDI tests and 2,414 loop-boundary checks. APK compilation, signature and ZIP integrity checks pass. Phone-specific native layout, finger interactions and audio output still require device testing. No claim is made that these fixes identify every symptom on the user's phone. The reference-inspired appearance, per-lane sample menus and independent Arrange/mixer tracks remain. APK retains offline factory instruments and is approximately 220 MB. GitHub was not updated.

---

# Audio City Studio 0.13.14 — channel-rack layout

Redesigned the drum pattern rack around the supplied visual reference:

- Small raised rectangular switches, alternating neutral-gray and muted-red groups of four. Enabled switches light up white or pink; playback marks the current column in lime.
- Slim colored channel/sample strips, individual activity lights, and visible per-lane sample dropdowns.
- Shorter 32dp channel rows, compact cool-gray toolbars, and a subdued rack panel. All 16 steps fit the viewport; Zoom+ shows larger switches with horizontal scrolling for precise tapping.
- Existing pattern names, sample choices, velocities, Follow, playback, manual empty startup, optional Auto Build, and eight independent Arrange/mixer tracks remain supported.

The separate PNG is a layout illustration with example hits, not an Android screenshot or an automatically generated starter beat. It is not included as an APK asset. Native UI artwork is drawn directly and adds no large bitmap packs.

Validation: complete host regression suite passed, APK compiled and signed with the existing development key, archive CRC and bundled asset comparison passed. Physical Android rendering, finger input and live audio remain unverified. Rack supports 4/4 and a constant project tempo. Bundled offline instruments are retained; APK size remains approximately 220 MB. GitHub was not updated.

---

# Audio City Studio 0.13.13 — review fixes

- Velocity editing keeps the bar originally pressed while playback advances. Manual bar navigation turns Follow off; tap the bar label to toggle Follow again. The current step is highlighted only on its own displayed bar.
- Saving/reprinting placed patterns returns a mapping from old clips to their replacements, so Arrange keeps the correct clip selected for Delete, Split and further edits.
- Dirty pattern drafts are captured immutably when the app pauses and saved on the serial project job queue, including the chosen one-shot samples. Completion of an older save cannot clear newer draft edits. Rack preview resources are disposed when the Activity is destroyed, and sample audition callbacks cannot restart audio in the background.
- Project writes are serialized against background draft saves, preventing overlapping AtomicFile writes.
- Removed AlertDialog custom-view padding from the rack to leave more space for the step grid.
- Corrected fractional-frame bar rounding. At tempos such as 93 BPM, a rendered bar ending at the nearest sample could previously be mistaken for the start of another bar and extend an unranged loop with silence. Pattern previews also set explicit loop bounds.

Validation: complete host suite, immutable draft/reopen/backup tests, selection remapping tests, drum sample persistence, tempo and recording-bound regressions, loop boundaries at every integer BPM from 40 to 240, and APK compile/signature verification. Original APK CRC/signature checked and all 185 assets matched its source. Physical Android UI/touch/audio remains unverified. The factory sound packs are retained, so the APK remains approximately 220 MB. GitHub has not been updated.

---

# Audio City Studio 0.13.12 — compact hardware pattern rack

- Pattern rack fits all 16 steps across the available width, with an explicit Zoom+/Fit16 option for more precise input. Eight drum lines remain on independent Arrange and mixer tracks.
- Reduced toolbar and row heights, smaller corner radii, charcoal metal chassis, fine brushed lines, warm gold step pads and mint playback LEDs. Shared button/transport finish updated across the app. Graphics are drawn directly; no large UI bitmap assets were added.
- Each lane has a visible sample dropdown. Its category contains the actual bundled one-shots (kick/snare/clap/closed hat/open hat/shaker/conga/bongo). Tap a sound to audition it, then Use sound or Cancel. Tap the row name to audition the current sound.
- Current step lights while playing; bars follow Pattern playback. In Song mode, the playhead follows placed clips of the selected pattern; row LEDs reflect the independent mixer channels. LEDs and callbacks stop when the rack closes.
- Sample choices persist with named patterns, copies, resize, tempo changes, templates and saved projects. Saving regenerates linked independent audio clips using the chosen sounds. Old v2 and legacy patterns keep their factory defaults; expressive v3 drum MIDI remains readable. New grid encoding is v4. Append future catalog entries instead of reordering them.
- Optional Auto Build and empty manual startup remain available.

Validation: host regression suites passed, including sample asset availability, PCM changes after replacing a kick, project reopen, independent copies and legacy compatibility. APK compile and signature verification pass. Actual Android layout, touch, speaker audio, latency and TalkBack need device verification. Rack still requires 4/4 at a constant tempo. This release retains the bundled offline packs and is still approximately 220 MB. GitHub has not been updated.

---

# Build Audio City Studio 0.13.11 — Manual patterns and separate drum tracks (Android APK)

This source project builds an Android application (`ug.audiocity.studio`), not an Apple AU/VST plugin or a Mac desktop app.

## Shared live MIDI and BeatPilot

Play It, Arrange, and the piano rolls use the shared `SynthPoly` event/synthesis path. Piano-roll note edits audition immediately; BeatPilot drum-note edits audition the selected sample. Arrange MIDI clips render live through the project transport and remain editable, with note timing, velocity, and instrument/sample-pack assignment saved in the project. BeatPilot bass and chord parts are editable MIDI clips aligned to its sampled drum clips.

The transport keeps audio clips and loops in the project mix while MIDI plays. Metronome playback follows its play/record settings independently of the recording count-in. Existing BeatPilot, chord pads, piano-roll features, and bundled instrument/sample libraries are retained; no Addictive instruments are included.

Host tests and an Android APK build have completed successfully. Device-level sound, touch, recording, and project-reopen checks are still required; no Android device or usable emulator is available in this workspace.

## Easiest route: GitHub Actions (cloud build)

1. Extract the complete source ZIP and upload/push its contents, including the root `.github/workflows/build-apk.yml` and the `CityStudioAndroid` folder. Keep `CityStudioAndroid/assets/instruments/*.zip`: these are required factory instruments. The root ignore rules explicitly allow these ZIP assets.
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

## Repository build repairs — 15 November 2026

- Moved the GitHub Actions workflow to the repository root and set its shell working directory to `CityStudioAndroid`; corrected the APK artifact path.
- Restored all nine factory instrument ZIP assets from `AudioCityStudio-0.13.5-Audible-MIDI-Piano-Roll-Source.zip`. The blanket ZIP ignore rule had excluded them from Git.
- Allowed instrument ZIP assets in `.gitignore`, restored executable script permissions, and added a build preflight that rejects missing factory packs.
- Full host suites and core suites passed, including sample-bank installation, the five piano choices, three guitar choices, live MIDI, multibar drum MIDI, loop playback, and touch-performance engine checks.
- Built and verified the development APK signature using SDK Platform 35 and Build Tools 35.0.0. This build uses Java AudioTrack output; native AAudio was not included because no NDK is installed in this environment.
- Physical-device sound, microphone, touch, and MIDI checks remain outstanding. A new development signing key was generated; it may not match APKs built elsewhere. Export/back up existing projects before uninstalling any previous version.
- These repairs are included in the downloadable source archive; they have not been pushed to GitHub.

## Version 0.13.6 — Beat workspace fixes

- Play It recording starts with four preparation clicks by default. During the clicks the backing song is silent and its cursor is held. With looping on, the take starts at the loop start; with looping off, it starts at the exact cursor. Settings offer Off / 4 clicks / 8 clicks. Stopping during the count cancels the take.
- Select is the safe default: swipes scroll, never drag clips. Choose Move, hold a clip still for at least 350 ms, then drag horizontally. Trim is a separate explicit tool. A cancelled drag restores the original start, offset and length; pinching cannot commit a clip edit.
- Two visible tool rows include Track +, Import, scissors, Delete, Copy +, Select, Move, Trim, Undo, Redo and Snap. Delete is undoable and does not remove source recordings.
- Arrange supports pinch zoom, anchored under the fingers, plus − / + / Fit controls.
- MIDI seeks now reconstruct held-note age, sampled-instrument position and release tails. MIDI duplicates and undo snapshots have independent playback sources. Copying/refreshing no longer grows the release duration.
- Captures freeze their instrument pack identity at recording start. The loop toggle is disabled during a take.
- Automated validation: full host suite and core suite passed, including 223 new BeatWorkspaceTests checks. APK compiled and signed successfully with the same development keystore used for the previous delivered APK.
- Not verified on physical Android hardware: screen layout, multitouch, perceived count-in timing, microphone route and hardware MIDI. This APK uses the AudioTrack fallback; native AAudio is not bundled.
- Source archive includes complete instrument assets and the root GitHub Actions workflow. These edits have not been pushed to GitHub. Signing material is kept separately and is not included in the source archive.

## Version 0.13.7 — Additional review fixes

- Tempo changes keep BeatPilot drums as sampled audio instead of rebuilding them through the default synthesizer. Drum pattern metadata, live MIDI export files and the project loop range now follow the new tempo. Tempo changes that would move MIDI clips or the loop range beyond 60 minutes are rejected before writing new media.
- Temporary drum monitoring tracks are excluded from undo snapshots, offline renders and backup file lists. Undo after closing the drum editor no longer resurrects an unsaved preview. Reference-clip backup lists now include all source dependencies.
- MIDI CC controls can turn mute and solo both on and off.
- Templates preserve Smart, Bar and 1/64 Snap settings.
- Added WorkflowRegressionTests and TempoDrumRegressionTests. Expanded the regular host runner to include the drum machine, expressive drum roll, Soundza and multibar tests.
- The four original defects were reproduced with the new tests against the prior 0.13.6 classes before testing the fixes. The full and core test suites passed after the fixes.
- Physical Android sound, touch, microphone and MIDI device checks are still required. APK uses AudioTrack and the same development signing key as the previously delivered builds.
- Fixes are included in the APK and source ZIP. GitHub has not been changed.

## Version 0.13.8 — Further app review

- Editable recorded MIDI notes preserve sustain-pedal durations and obey All Notes Off / All Sound Off on the correct channel. Previously a key release could cut the saved sustain, and panic messages could leave notes extended to the end of the take.
- Mapped transport controls react once per MIDI CC press and rearm on release. Note mappings consume their own channel's presses/releases, including note-on with velocity zero, without swallowing another channel's note-off. A MIDI loop command follows the same recording lock as the visible loop button.
- SFZ sample-rate detection accepts WAV metadata chunks before the format chunk. Regions inherit global SFZ parameters even when there is no group section.
- Reopened, restored and duplicated projects prepare their sampled instruments before binding the project for playback. Clips sharing an instrument use one decoded sample pack. Missing instruments leave their notes, boundaries and identity intact, produce silence instead of the wrong synth, and are reported once per missing pack. Reinstalling a pack restores playback on reopening.
- MIDI export supports live editable clips and uses the current notes and visible trim boundaries. Legacy printed MIDI retains its original controller data. Cancelling the file picker clears the pending export snapshot.
- Corrected obsolete help text that said MIDI piano-roll editing was unavailable.
- MidiImportRegressionTests exercises these behaviors (27 checks). Four defect classes were reproduced against the previous compiled version before applying the fixes. The complete host and core suites passed; the APK was compiled and its signature verified.
- Physical Android UI, USB/Bluetooth MIDI hardware, microphone, sound and latency checks remain outstanding. Uses the same development signing key as the previous builds. Native AAudio is not included; AudioTrack is used. These changes have not been pushed to GitHub.

## Version 0.13.9 — Portable samples and playback review

- Project backups embed the decoded sample packs used by editable MIDI clips, including takes, launcher slots and the reference clip. The new bounded `.acpack` data format retains region mapping, PCM samples, loop and release settings, instrument name and source attribution. Shared PCM arrays are written once; identical content reuses its file name.
- On restore or project reopen, embedded samples are preferred and shared between clips. Imported instruments work even when the original library is absent. Old backups still open. Backups containing `.acpack` samples require Audio City Studio 0.13.9 or later to restore. Missing instruments that were already unavailable cannot be reconstructed by a backup.
- Short sampled loops no longer restart their attack envelope after every wrap. Released looped notes keep their configured release instead of being cut at a sample boundary.
- MIDI pitch bend now affects sampled instruments as well as synth voices. Sample playback caches the bend ratio rather than computing it for each sample frame.
- Non-finite floating-point WAV samples and invalid SFZ gain values are sanitized before they can poison the audio path.
- Added PortableInstrumentTests, PortableBackupTests and SamplePlaybackRegressionTests. Tests verify source-independent backup restore, identical sample output after reopen, loop continuity, release tails, sample pitch bend, bounded archive parsing and invalid-sample handling. Full and core host suites pass.
- Physical Android microphone, sound, touch, Bluetooth/USB MIDI and latency testing remains outstanding. APK uses AudioTrack and the same development signing key as previous builds. These changes have not been pushed to GitHub.

## Version 0.13.10 — Recorded instrument clips stay inside bars

- With Loop on, new Play It and keyboard MIDI takes have a frame-accurate limit at the captured loop end. Additional events from a second pass are ignored and the take is finished automatically by the UI tick. Backing playback can continue looping.
- With Loop off, manually stopped instrument takes close at a bar boundary. A slightly late Stop does not create an empty extra bar; a note genuinely crossing the boundary is preserved and the clip closes at the following bar. Starts remain at the chosen cursor.
- The implicit whole-song loop rounds up to a complete bar. Explicit user loop ranges are respected exactly, including ranges that are not a whole number of bars.
- Visible clip length is independent of the instrument release tail in the source. Printed MIDI takes and live MIDI clips use the same musical bounds. Existing recordings are not rewritten.
- Bar alignment uses the project tempo map and time signature. Added RecordedClipBoundsTests across several BPMs, 3/4, 4/4 and 6/4, tempo changes, mid-bar starts, delayed Stop events and long sampled-instrument tails.
- Automated host/core tests pass. Physical Android timing and loop joins still need phone testing. Same development signing key; native AAudio is not bundled. These changes have not been pushed to GitHub.

## Version 0.13.11 — Manual patterns; generation is optional

- Fresh installations open an empty project. Existing last-opened projects are preserved. Sounds now leads with Start from scratch / Pattern rack and playing your own instrument parts. Auto Build is a separate optional route; opening the rack never fills steps automatically.
- The rack has named patterns, New, Copy, Rename, Save pattern, a 16-step channel grid with groups of four, velocity editing, and 1/2/4/8-bar lengths. Pattern drafts are saved on switching or closing. Up to 32 patterns belong to each project.
- PATTERN previews the current draft. SONG plays the arranged tracks. Place in Arrange adds one, two, four or eight copies starting at the cursor's next bar.
- Every placed pattern has independent Kick, Snare, Clap, Closed Hat, Open Hat, Shaker, Conga and Bongo tracks and mixer controls. Even initially empty lanes remain available for later edits. Pattern changes update linked repeats without combining their sound sources or resetting mixer settings. Independently trimmed lane clips retain their windows.
- Optional Auto Build uses the same editable pattern bank and separate eight drum tracks, with bass and chords on two additional tracks. The legacy automatic starter is no longer the main beat-workspace route.
- Pattern bank, names and clip links survive project reopen, undo snapshots, templates and backups. Previous projects without a pattern bank still open. Legacy unlinked drum clips keep their separate piano-roll editor. The new pattern rack and placement currently use 4/4 and one project tempo.
- Pattern audio preview is temporary and stops when the rack closes or the app loses audio focus. The pattern preview never appears as a mixed drum track in the project. The project writer refuses metadata beyond its readable limit before replacing a saved project.
- PatternBankTests and PatternArrangementTests cover blank defaults, independent copies, save/reopen, templates, separate track placement, linked edits, mixer settings, lane trims, repeat placement, backups and optional generation. All host/core suites passed and APK signing was verified. Physical Android layout, touch and audio testing remains outstanding.
- Same development signing key. GitHub has not been changed.
