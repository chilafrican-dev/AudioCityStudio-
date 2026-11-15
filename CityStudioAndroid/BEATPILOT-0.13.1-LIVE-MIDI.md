# BeatPilot 0.13.1 — Live Drums and Editable MIDI (source update)

## Implemented
- A transient drum-preview track is inserted when opening the Drum screen. It plays the 8-lane, 16-step sample pattern against the same project timeline as other tracks.
- Clicking step cells, Starter, or Clear updates the 1-bar audio phrase used for live preview in a worker thread; no pause/restart is requested.
- The transient monitor track is excluded from session serialization and removed when the drum dialog closes.
- Keep Drum Pattern saves a four-bar sampled audio clip to Arrange, plus a compact editable step pattern, its MIDI note events and a standard .mid sidecar file.
- Edit saved MIDI loads a previously stored BeatPilot drum pattern, displays its steps, and allows saving a replacement clip.
- Live preview is muted after a take is kept to avoid doubling the saved track; editing steps unmutes it.
- Existing instruments, Soundza sounds and non-Addictive sample content remain unchanged.

## Limitations
- Only 4/4, one 16-step bar repeated across four bars is supported in this version.
- MIDI note data is edited through the BeatPilot step UI, not the general MIDI piano roll; the audio region is the playback source.
- Drum audio is reconstructed when the pattern is re-saved. MIDI sidecar is separate.
- Android SDK/NDK APK compilation and on-device latency tests were not available in this environment.
- Preview follows the main project's fixed BPM; tempo automation may require revisiting this implementation.
- Full-project playback length includes a 10-minute temporary preview region while the editor is open.
- This implementation does not yet offer per-step velocity, multiple-bar patterns, or real-time pattern recording by tapping pads.

## Test
Run `bash run-core-tests.sh` to execute host engine tests and the new MIDI/pattern tests.
