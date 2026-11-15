# Audio City Studio — Shared MIDI audition

The regular piano roll now auditions notes through the same `SynthPoly` voice engine used by Play It and live MIDI monitoring:
- Tapped keys, selected notes, inserted notes, moved notes, and resized notes are audible immediately.
- Pattern preview queues note-on/off events on the shared voice stream and starts at the next project beat, so it can play over Arrange without offline rendering.
- Live MIDI clips retain their assigned sampled-instrument ID in project data. Bundled/imported sample packs are resolved when the project opens and are used for audition and clip regeneration.
- The piano-roll instrument picker includes installed sampled packs as well as factory synth patches.

Existing drum piano-roll audition introduced in 0.13.4 remains intact.

## Validation boundary
- Host-side MIDI scheduling and sampled clip-render tests run without Android.
- Android SDK compilation, device touch testing, audio routing, and physical-device listening still require an Android SDK/device environment.
