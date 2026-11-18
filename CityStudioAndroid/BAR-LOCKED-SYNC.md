# Bar-locked recording and playback

| Requirement | How it works |
|---|---|
| 1. Single master clock | `TransportClock`: the audio thread reports, after every block, which output frame was at the speaker at which `System.nanoTime()` (AudioTrack/AAudio hardware timestamp; play-head estimate as fallback). Playback, metronome, MIDI monitor, pads and recording all use it. |
| 2. Bar-aligned regions | Audio: the first microphone frame's capture time (AudioRecord timestamp) is mapped to the song frame being heard at that instant; `TransportClock.place` trims pre-cursor audio or places a late start exactly. MIDI: events are placed at the heard song frame. |
| 3. Accurate MIDI | `MidiTake.add(..., nanos)` uses the touch event time (`MotionEvent` time) or the USB/Bluetooth MIDI timestamp, mapped through the clock. Notes up to 1/8 beat early (count-in pick-ups) land on beat one. |
| 4. Optional quantize | Menu ▸ Recording timing & quantize: Off (default), 1/4, 1/8, 1/16, 1/32, applied on the tempo map. Off never moves notes. |
| 5. Magnetic placement | Existing `ProSnap` move/trim: snaps region edges to the grid, keeps source offsets. |
| 6. Latency compensation | Hardware timestamps on both streams, plus a measured correction from the speaker-to-microphone loopback test (same menu). |
| 7. Loop sync | The engine restarts the cycle on the exact frame (blocks are cut at the loop end); positions are whole frames, so nothing accumulates. Tested over six cycles of a four-part loop. |
| 8. No UI timers | Phrase pads are scheduled on the synth's frame clock; nothing audible uses `Handler` timers. |
| 9. Record while playing | Backing keeps playing from the same engine during audio and MIDI takes. |
| 10. Region length | Stop time comes from the clock; optional "End MIDI regions on the next bar line" moves only the region edge. |
| 11. Tempo changes | Adding/removing tempo changes or changing the time signature calls `ProEdit.followTempoMap`: MIDI notes keep their beats; audio regions keep their bar/beat at recorded speed. |
| 12. Tests | `tests/SyncTests.java` (149 checks): clock, placement, timestamped MIDI, quantize, region length, tempo map, six identical loop cycles of drums+bass+piano+vocal, every kick within 1 ms of its sixteenth. |
