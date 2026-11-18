# Audio City Studio: AI MIDI Generator (patch on 0.13.18)

New: **AI** tab (5th tab, bottom bar) and Pro tools → "AI MIDI generator…".
Pick key, mode, bars (1–32), parts (melody / chords / bass) and creativity, then "Generate at playhead".
It creates new tracks ("AI · Melody", "AI · Chords", "AI · Bass") holding live MIDI clips aligned to the bar at the playhead,
following the project tempo map and time signature. Open any clip to edit its notes or change its instrument. One Undo removes it all.

Design (new amber/violet look, drawing colours only; no layout or touch changes):
- Restyled: Arrange timeline (dark ground, flat clip cards with colour header and outline, track-colour edge strip, amber mute / yellow solo, glowing playhead), piano roll (dark keys and grid, cyan velocity-shaded notes), transport panel and LCD (flat dark panel, amber clock), bottom tab indicator, and the new AI sheet.
- NOT touched: the Mixer screen and the Sounds (instrument) screen. The Mixer and Knob classes are byte-identical to 0.13.18.
- The header, transport and bottom bar are shared chrome and appear on every screen, including Mixer and Sounds.
- Not restyled yet: Effects, drum rack, vocal editor, dialogs.

Files
- src/.../AiMidiGenerator.java  platform-independent generator (Markov melody, chords, bass, drums), deterministic per seed
- src/.../AiGenerateUi.java     the sheet and clip/track creation (Android UI)
- src/.../MainActivity.java     adds the AI tab (one line at the end of buildChrome)
- src/.../ProUi.java            adds the menu entry and aiGenerate(); piano roll colours
- src/.../StudioFinish.java     flat transport panel
- tests/AiMidiGeneratorTests.java, run-core-tests.sh (registers the new test)

Verified: full host suite (run-core-tests.sh) passes, including ~1.5 million new checks (scale safety, ranges, determinism,
bar limits, chord-tone alignment, odd time signatures, tempo-map conversion, invalid input).
NOT verified: Android compilation of AiGenerateUi/MainActivity/ProUi (no Android SDK here; no syntax errors found, only missing-SDK symbols),
UI layout, touch behaviour and audio on a phone.
Not connected yet: AI drums (generated in AiMidiGenerator but not mapped to the drum rack), a trained Transformer model.
