# Audio City Studio 0.13.3 — BeatPilot Drum Piano Roll

## New
- Arrange → Pro Clip Tools → **Edit BeatPilot drum piano roll…** opens a free-position eight-lane MIDI editor for BeatPilot drums.
- Add/select notes by tapping, drag notes horizontally or between drum lanes, drag a note's right edge to resize, long-press to delete, apply velocity 1–127, undo (up to 40 edits), 1/16 / 1/32 / unquantized position snapping, horizontal scrolling and zoom.
- Supports saved patterns of 1, 2, 4 or 8 bars and original 16-step patterns.
- **Save MIDI + audio** reprints the Soundza sample-backed audio clip, exports an updated standard MIDI file, and saves a version-3 drum pattern preserving individual note offsets, lengths, lanes and velocity. Existing source clip is replaced in the track only after new audio and MIDI files are written.
- ProjectIO reads both legacy/v2 step patterns and expressive v3 MIDI patterns; the older step editor remains available for older patterns. A v3 freehand MIDI roll is intentionally not downgraded to a 16-step grid (which would discard free timing and lengths).
- Original pianos, guitars, Soundza sounds, BeatPilot playback, and Addictive pack exclusion are unchanged.

## Testing
- `bash run-core-tests.sh` passed (full host core regression suite, including previous BeatPilot live and multibar tests).
- `javac -cp build/core-tests -d build/core-tests tests/BeatPilotDrumRollTests.java && java -cp build/core-tests ug.audiocity.studio.BeatPilotDrumRollTests` passed 49 tests: v3 MIDI round trips, non-grid note timing, note length, velocities, audio rendering, standard MIDI export, legacy compatibility, rejection of invalid note data.

## Limits / APK
- The editor targets **BeatPilot's eight drum lanes**. The existing `ProUi` editor continues to handle other live MIDI instruments.
- BeatPilot drum clips still use rendered sampled audio for playback; editing MIDI regenerates their audio. This is not live note-by-note drum synthesis during arrangement playback, nor arbitrary-pitch piano performance editing.
- This is Android source, not an APK. A full Android SDK/NDK build and phone touchscreen/audio tests remain necessary.
