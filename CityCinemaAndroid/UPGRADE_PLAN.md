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
| Vectorscope, desqueeze preview, shadow-clip warning | **missing** |
| Monitoring LUT (Rec.709 VIEW) | **added in 3.7** (see Phase 2) |
| Audio: AudioRecord float, input choice, gain, meters, clip indicator, 32-bit WAV sidecar | working; limiter / wind NR / monitoring **missing** |
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
2. Camera: Rec.709 VIEW — **done in 3.7** (Android 13+, monitor only). Next: vectorscope, shadow-clip warning,
   anamorphic desqueeze, audio limiter.
3. Timeline engine: tracks (video layers, audio, music, voice-over, titles), gaps, snapping, ripple/roll/slip/slide,
   redo, markers, versioned project format with migration.
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
