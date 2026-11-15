# BeatPilot 0.13.4 — audible drum piano-roll preview

Fixed a missing audition path in the BeatPilot drum piano-roll editor.

- Tapping a drum lane label auditions its Soundza WAV hit.
- Selecting an existing drum MIDI note auditions that note using its recorded velocity.
- Creating a new drum note auditions its sample immediately.
- Added **Hear drum pattern** and **Stop preview** controls inside the editor.
- Closing the editor stops its preview audio.
- Preserved saved MIDI, velocity editing, sampled audio rendering and existing instruments.

These auditions play on the app's existing independent audition engine. They are editor previews, not yet fully integrated real-time MIDI playback during Arrange transport. No physical Android APK test was available in this environment.
