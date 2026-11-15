# Audio City Studio 0.12.2 — Real Sampled Instrument Support

**This is an Android source-code upgrade, not an APK.** It requires an Android SDK build and on-device audio testing. It extends 0.12.1's restored "PLAY IT. KEEP THE TAKE." interface.

## What is actually included

- Nine playable **recorded CC0 percussion instruments** using existing Versilian/VCSL WAV assets: high bongo, low bongo, conga, quinto, cowbell, clave, tambourine, shaker, and cabasa. They are playable chromatically from the touch chord/melody pads; they are short percussion recordings, not realistic pitched piano/string instruments.
- New `SampledInstruments.java`: offline WAV/SFZ voice renderer with note-to-sample mapping, simple velocity regions, linear pitch interpolation, note attack/release, basic sample loops, and polyphonic playback integrated into `SynthPoly`.
- Import `.wav` directly (one WAV mapped from MIDI 60/C4). Import `.zip` containing a **basic SFZ1** instrument plus WAV samples. Audio gets decoded and converted to 48 kHz in app. Imported samples persist under private app files and work offline.
- "Real Sampled Instruments" screen in Sound Desk, plus "Choose instrument → Install ..." inside Play It. Existing factory synths remain.
- Sampled MIDI performances print the **selected sample pack**, not a fallback synth. Their MIDI events remain stored next to the printed audio.
- Free library links for Salamander Grand Piano (CC BY 3.0), VSCO2 Community Edition (CC0) and FreePats (licenses differ by pack).

## Important limitations

- **No full grand piano, violin/string ensemble or guitar library was downloaded or bundled**. The work environment could not reach external file hosts; these libraries are large and carry their own licenses. The app links to verified official pages, but the user must download compatible WAV/SFZ packs manually and use Import.
- This is a **restricted SFZ1 reader**; it supports `<control> default_path`, `<group>`, `<global>`, `<region>`, `sample`, `key`, `lokey`, `hikey`, `pitch_keycenter`, `lovel`, `hivel`, `volume`, and basic `loop_*` opcodes. It does not support complex macros/includes, all SFZ2 ARIA features, compressed FLAC/OGG or unbundled SFZ files. A downloaded library is not guaranteed to import.
- Each WAV <=32 MB; extracted archive <=512 MB; decoded instrument <=128 MB. This cannot directly install entire multi-gigabyte VSCO orchestral archives.
- Pitch-stretched single WAV may sound artificial over a large keyboard range. Realistic piano/string sounds require multiple recorded notes/layers and appropriate mapping.
- The library exposes credits and links but **does not automatically acquire redistribution rights**. Follow each instrument creator's license when building or shipping packs.
- Playback handling depends on existing Android audio engine; real-phone testing of UI, latency, pack loading and memory must still be done. No APK was built in this environment.

## How to use

1. Build the Android project APK using the existing GitHub Actions workflow or a local Android SDK.
2. Open **The Sound Desk → Free / Real Sampled Instruments**.
3. Use the official sample library links and obtain a legally usable **small WAV or SFZ ZIP**. The ZIP must contain its referenced WAV files relative to the SFZ file.
4. Tap **Import downloaded WAV / SFZ ZIP** and select the downloaded file.
5. Open **Play / Touch Instrument → instrument selector**. Choose a `SAMPLED · ...` entry, or one of the nine bundled CC0 sampled percussion sounds.
6. Play the chord/melody pads over the existing beat; Start Recording / Stop Recording preserves the project's transport. Printed audio uses the selected real samples.

## Tests

`./run-core-tests.sh` now executes a new `SampledInstrumentTests` suite testing 44.1kHz WAV decoding and resampling, ZIP installation, SFZ note mapping, sampled voice playback, note-off, MIDI take printing and ZIP-path traversal prevention. This is a host-side test, not an Android instrumentation test.

## Independent, authoritative instrument sources

- Salamander: https://sfzinstruments.github.io/pianos/salamander/ (Alexander Holm, CC BY 3.0; large pack)
- Versilian VSCO 2 Community Edition: https://github.com/sgossner/VSCO-2-CE (CC0; large orchestral library)
- FreePats: https://freepats.zenvoid.org/ (license varies by sample/pack; check before use)

Always preserve attribution and license documents when redistributing samples.
