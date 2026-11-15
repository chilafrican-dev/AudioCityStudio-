# Audio City Studio — Built-in FreePats FM Electric Piano 2

This build **includes** the FM Electric Piano 2 samples directly in the Android APK.
No separate sample ZIP download or manual import is required.

- Source: FreePats / Roberto — https://freepats.zenvoid.org/
- Contents: 12 WAV samples mapped by the original SFZ, README and CC0 1.0 license.
- First launch: the bundled ZIP is installed automatically into the app's private instrument folder. This happens in the existing background initialisation task, not on the UI thread.
- Later launches: installed factory piano remains in place without being overwritten.
- Usage: Sound Desk → Play / Touch Instrument → choose `BUILT-IN · FM Electric Piano 2 · FreePats (CC0)`.
- Audio: stereo SFZ-mapped sample playback, multisample roots and ranges, polyphony, SFZ `ampeg_release` support, MIDI take printing.
- Offline playback: yes.
- Size: APK is approximately 20 MB larger; the installed sample files consume additional private phone storage.
- User-imported sample instruments are unaffected.

The original upstream license text and README remain in the bundled ZIP. This is a bright FM electric piano, **not an acoustic grand piano**.

See `assets/licenses/SAMPLE-CREDITS.md` for provenance.
