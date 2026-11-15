# Audio City Studio 0.12.6 — BeatPilot initial implementation

## Included

- New **BEATPILOT / ONE-TOUCH BEAT** entry on Sound Desk.
- **Make Beat** button inside **PLAY IT. KEEP THE TAKE.**, leading to the same guided BeatPilot creator.
- Offline music choices: Kidandali/Afropop, Afrobeats, Dancehall, Soft R&B, Gospel; Gentle/Balanced/Energetic feels.
- Major/minor key and progression picker; four- or eight-bar beats.
- Creates **three separate Arrange audio tracks** (Drums, Smart Bass, Chords) at the next bar after the project cursor. Existing tracks are preserved.
- Generated bass pitches follow chord roots. Groove patterns are generated from built-in synthesis (sampled-piano rendering is not yet linked to automatic chord arrangement).
- After creating a beat, the user is offered **Play It** to perform with the existing pads and sampled instruments.
- No account, internet or payment required.

## Boundaries / limitations

- Automatically generated backing is rendered as audio regions, not editable MIDI events. They can be moved, repeated and mixed in Arrange, but note-by-note MIDI editability is not implemented for these generated regions.
- Live Magic Play (one pad triggers synchronized drum+bass+chord accompaniment), Tap My Rhythm, voice-to-melody and automatic Intro/Verse/Chorus/Outro have **not** been implemented.
- Full Android APK and device-level audio tests have **not** been performed here due to missing SDK/NDK. The provided GitHub Actions build workflow is the intended way to compile an APK.
- Core tests don't compile Android activity code. A separate host-only BeatPilot smoke check used simplified AudioIO stand-ins; it checked musical mappings and clip positions, not Android playback.
- Back up projects and use your existing signing key before replacing installed builds.

## Tests executed

- `./run-core-tests.sh`: all existing host core suites pass.
- 195 focused host checks: genres and moods return valid patterns, 8 bass-pad positions obey root/fifth/octave mapping, and three 4-bar arrangement tracks are created with correctly positioned files (AudioIO stub).
