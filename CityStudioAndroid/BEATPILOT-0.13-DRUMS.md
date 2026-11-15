# Audio City Studio 0.13 — BeatPilot Drum Workspace

## Included
- New **Drums** button directly inside PLAY IT. KEEP THE TAKE.
- Eight real 48 kHz Soundza drum one-shots: kick, snare, clap, closed/open hi-hat, shaker, conga and bongo.
- Playable touch pads using the app's existing live sample engine.
- Eight-lane, 16-step pattern editor with a simple starter groove and Clear / Starter controls.
- Keep Drum Pattern renders a four-bar drum clip and saves it on a new, separate Arrange track without pausing current playback.
- Built-in Soundza loop browser with 19 percussion loops; set 1, 2, or 4 bars and add a pitch-preserving, offline stretched audio region to Arrange.
- Preserves the previously bundled 142 Soundza samples, guitars, pianos, and instruments. Does **not** include Addictive Afrobeat files.

## Known limitations
- The editor currently renders the step pattern to audio on Keep Take. It does not provide a MIDI drum region or live pattern playback while changing steps; playable drum pads are live.
- The loop stretcher is a preliminary WSOLA-style offline algorithm. It can create audible artifacts; bar count must be selected manually, and it should not be used for polished final masters without listening.
- Drum editing and loop browser currently support 4/4 projects only.
- Full Android SDK/NDK build and real-phone input latency tests have not been completed here.
- The new sampled drum pad menu replaces the prior sampled instrument temporarily; it restores the original instrument when closed.

## Next work
- Add a clock-synchronized live step scheduler and editable MIDI step patterns.
- Add robust tempo/bar detection, better pitch-preserving loop processing, and sample audition before saving.
- Integrate all 142 sounds as selectable pad sound slots (currently 8 pads from the 142 library).
