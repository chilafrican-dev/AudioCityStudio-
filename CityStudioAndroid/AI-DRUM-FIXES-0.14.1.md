# AI drum fixes — 0.14.1

The AI generator now places Kick, Snare, Clap, Closed Hat, Open Hat, Shaker, Conga and
Bongo on eight independent tracks. Generated notes are separated by lane. Empty lines
remain visible and editable, and track-capacity validation reserves eight drum tracks.

The generator's Drum Sounds picker can use a saved pattern's selected factory, Afro or
personal samples. Every lane gets a project-owned, content-addressed instrument file
before the track is added. Normal Save/Open preserves the sound, its name and attribution
without requiring a portable backup or a globally installed sample library.

Older factory AI clips with the former `embedded:ai-drum-pads` reference are recovered
when opening a project and their kits are written into the project for subsequent saves.
Existing tracks/notes remain intact. Reopening is not used to restructure older edited
combined tracks; newly generated drums use the independent lanes.

Generation stays optional. One Undo removes all tracks created by the operation. Sample
loading/encoding and stopping the existing transport run on the worker. The amber/violet
UI and Claude's melody/chord/bass generator are retained.

The APK version is 0.14.1 (version code 33). Its local build uses the original development
signing certificate. CI now requires that same key through the private repository secret
and verifies the resulting APK certificate; it refuses to create an incompatible update.

Validation:

- 229 new host regression checks: ordinary sample save/reopen, audio equality, separate
  mixer identities, lane mute/solo, empty editable lanes, sample metadata, portable files,
  legacy recovery and invalid input.
- Full Android-source host runner passed: 43 suites.
- Signing restoration tests passed: 9 checks including the actual original key locally.
- SDK-independent core runner and Android build results are recorded with this change.

Physical Android installation/listening and UI touch behavior require phone testing.
The local APK has AudioTrack output; no NDK is available in this build environment.
The broader tick-model/native-engine integration remains a separate, unfinished effort.
