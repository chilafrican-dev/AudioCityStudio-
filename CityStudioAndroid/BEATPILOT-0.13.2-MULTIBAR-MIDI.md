# Audio City BeatPilot 0.13.2 — Multi-bar editable drum MIDI

- In **PLAY IT. KEEP THE TAKE. → Drums**, select 1, 2, 4, or 8 bars. The sequencer displays 16 steps for the selected bar with previous/next bar navigation.
- Set velocity with **Velocity − / +** (1–127). Tap an empty step to add a hit at that velocity, tap an active step to remove it, or **long-press** a step to set/change its velocity.
- Live preview uses the actual selected multi-bar pattern; changing steps or velocity re-renders preview on a worker thread without deliberately stopping the project transport.
- **Keep drum pattern** records a sampled-audio region, matching variable-length MIDI note data, and an exported standard `.mid` file. The pattern is stored in the project's `drumPattern` field.
- From **Arrange**, open the drum clip's **Pro clip tools → Edit BeatPilot drum grid & velocities…** to reopen the drum roll, revise notes/velocities, and save a regenerated audio+MIDI linked clip.
- Existing 0.13.1 single-bar encoded drum clips are still readable; they reopen as a four-bar repeated pattern.
- No payment features and no Addictive Afrobeat samples are included.

## Known limitations
- This is a focused 8-drum-lane, 16th-note **drum grid/piano roll**, not yet a generic freehand piano roll with arbitrary microtiming or note-length dragging.
- Drum playback in Arrange is still a rendered sampled audio clip. Edited notes and velocities re-render that clip when you press Keep; the saved standard MIDI file is also updated.
- Changing project tempo after saving does not automatically re-render every drum clip. Rhythm editing supports 4/4 only.
- The preview uses the current transport but a separately regenerated phrase; UI/audio latency on real Android devices has not been verified.
- Full Android SDK/NDK APK build and touch/audio testing are still required.

To run host tests: `bash run-core-tests.sh`.
