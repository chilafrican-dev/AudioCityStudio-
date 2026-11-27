# City Cinema — professional upgrade: audit and phase plan

Started from v3.6.1. This file is the source of truth for what is done, what is partial and what is not built yet.
"Done" means implemented in code and covered by host tests and/or the CI checks; real-device results are listed
separately, because nothing here can be tested on a phone from the build environment.

## Phase 1 — audit of v3.6.1 (what exists)

Build: Java 8 sources + NDK C, built by `build.sh` with the SDK build tools (aapt2, javac, d8, apksigner); no Gradle.
CI (`.github/workflows/city-cinema.yml`) runs host tests, LibRaw/FFmpeg checks of DNG, ProRes and LUT output, NEON
bit-exactness under qemu, then builds and signs the APK. Kotlin/Gradle migration is **not** planned: it would rewrite
~10k working lines for no functional gain. New code stays Java + C.

| Area | State in 3.6.1 |
|---|---|
| Camera2 manual control: fps, shutter (speed + angle), ISO/EI, EV, Kelvin + tint WB, manual/continuous/tap focus, AE/AWB lock, zoom, lens switching | working |
| Own log auto exposure (keeps shutter, shortens it when too bright at ISO min) | working (3.4.2) |
| Looks: City Log, Flat Log, Flat, S-Log3-, Log3G10-, BMD Film Gen 5-style, Film, Vivid, Rec.709; wide-gamut colour matrix from sensor calibration | working; log tagged in file name |
| H.264 / HEVC 8-bit, HEVC 10-bit HLG, ProRes 422 family (own NEON encoder, MOV), CinemaDNG RAW (lossless JPEG) up to 8K | working where the device supports it |
| Scopes: zebra, peaking, false colour, histogram, waveform, RGB parade, guides incl. anamorphic, grid, level | working (on the real preview texture) |
| Vectorscope (with 75% targets and skin-tone line), anamorphic desqueeze preview, highlight and shadow clip warnings on every scope | working (vectorscope 3.7.1; desqueeze and warnings existed) |
| Monitoring LUT (Rec.709 VIEW) | **added in 3.7** (see Phase 2) |
| Audio: AudioRecord float, input choice, gain, meters, clip indicator, 32-bit WAV sidecar, optional -1 dBFS peak limiter (3.7.1) | working; wind noise reduction / headphone monitoring **missing** |
| Recording reliability: no dropped encoded frames, measured camera clock, A/V realign, storage check, thermal label | working |
| Clips: list, player, viewing LUT, 709 COPY, share, send to editor | working; grid, thumbnails, search, multi-select, rename **missing** |
| Editor: single video track + titles + one music track; trim, split, move, duplicate, delete, undo; speed (constant); fit/fill/zoom/pan/rotate/flip; log conversion; grade (exposure, contrast, saturation, temp, tint, shadows, highlights, film looks); .cube LUTs (3 packs + import); auto colour; 3 transitions; fades; export MP4 | working |
| Multi-track timeline, keyframes, masks, blend modes, effects stack, adjustment layers | **missing** |
| Audio DSP, ducking, LUFS, beat detection, sync by correlation | **missing** |
| AI: face retouch, tracking, segmentation, captions, auto edit | **missing** — needs ML models (see below) |

Known architectural limits to fix in Phase 3: timeline is a single ordered list (no track model, no gaps); time is
already integer microseconds (good); project format is unversioned JSON (add a version field + migration).

## Phase order and status

1. Audit — done (this file).
2. Camera — **done**: Rec.709 VIEW (3.7, Android 13+, monitor only), vectorscope and clip warnings on all scopes,
   audio limiter (3.7.1). Left for later: wind-noise filter, headphone monitoring.
3. Timeline engine — **in progress**.
   - 3.8: **layer tracks** (3 video layers above the main track: B-roll, picture-in-picture) with free placement,
     opacity, framing incl. PiP (zoom below 100%), their own sound in the mix; **drag to move** layers, titles and
     music with **magnetic snapping** (clip/layer/title edges and the playhead, with a guide line); split layers;
     **redo**; project format **version 2** (version-1 projects load unchanged, tested).
   - The main track stays magnetic (clips butt together, like a primary storyline); gaps live on layer tracks.
   - 3.9: **audio tracks** MUSIC / VOICE / FX with any number of sound clips (trim in and length, volume, pan,
     fades, mute, split, duplicate, drag with snapping); **voice-over recording** onto the VOICE track while the
     programme plays silently; project format **version 3** (the old single music slot becomes a MUSIC clip, tested).
   - Next: timeline thumbnails and audio waveforms, ripple/roll/slip/slide on the main track, markers, multi-select.
4. GPU compositing: per-layer transform with opacity and blend modes, FP16 intermediates, PiP.
5. Colour: lift/gamma/gain/offset wheels, curves, HSL qualifier, masks, before/after; LUT manager (rename, delete,
   favourites, thumbnails).
6. Audio: per-track mixer, pan, EQ / HPF / LPF / compressor / limiter / gate, LUFS + true-peak meters, ducking,
   voice-over recording, waveform display, sync by cross-correlation.
7. Effects (grain, halation, bloom, vignette, blur, sharpen, chromatic aberration), more transitions, keyframes.
8. Music video workflow (beat detection, multicam sync), automatic edit from measured shot statistics.
9. AI features — each needs an on-device model that must be added and licensed: face landmarks / segmentation
   (e.g. MediaPipe tasks, Apache-2.0), speech-to-text (e.g. whisper.cpp, MIT; Luganda only if a model is validated).
   Until a model is bundled these features stay out of the UI rather than faked.
10. Export presets, size estimate, cancel, foreground service; long-project and A/V sync tests.

Each phase ships as its own version and leaves the app building and the tests green.

## Design target (mockups received after 3.9)

Three mockups (camera, editor, AI edit) are the visual target. Rule: a control appears only when the function
behind it works. Mapping:

| Mockup element | Status |
|---|---|
| Camera top bar: lens, res, fps, shutter, ISO, WB, codec, bitrate, timecode, storage free + time left, battery %, temperature | working (storage size and battery °C added in 3.9.1) |
| Camera: REC.709 VIEW, zebra, peaking, false colour, grid, waveform, parade, histogram, vectorscope, stabilisation, AF/lock, lens, zoom, audio meters, USB mic | working; layout restyle (left rail, stepper controls, scope strip) pending |
| Camera: LUT dropdown (monitor creative LUT), 4-scope strip at once | not built |
| Editor: media browser panel, V1-V3 + A1-A3 tracks | tracks working (3 layers + main, MUSIC/VOICE/FX); browser, track headers (eye/lock/M/S) pending |
| Editor: thumbnails / waveforms in clips, keyframes, blend modes, effects & transitions browser, scopes in editor | not built (Phases 3-7) |
| Editor: export panel with presets and size estimate | pending (Phase 10) |
| AI Edit page (scene detection, best takes, styles, beat sync, automatic finishing) | not built (Phase 8) |

