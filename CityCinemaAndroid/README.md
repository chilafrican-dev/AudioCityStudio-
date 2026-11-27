# City Cinema 2.1

A standalone manual cinema camera for Android phones (package `ug.audiocity.cinema`, Android 11+). It is a separate
app from Audio City Studio, built the same way: plain Java, Camera2, MediaCodec and MediaMuxer, with no Gradle and
no third-party libraries.

## What it does

**Exposure**
- Manual ISO in third stops across the sensor's full range, or auto exposure with EV compensation and AE lock.
- Shutter as an **angle** (11.25° to 360°, including the flicker-safe 172.8° for 50 Hz and 144° for 60 Hz) or as a **speed** (1/24 to 1/8000).
- Switching from auto to manual starts from the values auto exposure was using, so the picture doesn't jump.
- Anti-flicker setting: auto, 50 Hz, 60 Hz or off.

**Frame rates and timing**
- 23.976, 24, 25, 29.97, 30, 48, 50, 59.94 and 60 fps. With manual exposure, the NTSC rates use exact 1001/1000 sensor frame durations.
- 120 and 240 fps on cameras with high-speed video. Record in real time, or conform to 23.976/24/25/29.97/30 for in-camera slow motion.
- Every file is **constant frame rate**: each frame is snapped onto the exact frame grid. A dropped frame leaves a gap rather than shifting later frames, so sound stays in sync. Dropped frames are counted and reported.
- Timecode in record-run or time-of-day mode, with SMPTE drop-frame at 29.97 and 59.94.

**Color**
- Kelvin white balance from 2000 to 10000 K, plus green/magenta tint and white-balance presets. When the phone reports its sensor calibration matrices, the Kelvin values are sensor-calibrated (DNG method); otherwise a generic model is used. You can also use auto white balance with AWB lock.
- **City Log** is the log profile, a published formula on scene-linear light `s` (18% grey = 0.18):
  `y = 0.255825 · log10(s + 0.01) + 0.604514`, inverse `s = 10^((y − 0.604514) / 0.255825) − 0.01`.
  Black sits at 95/1023, 18% grey at 0.42, about 79 ten-bit codes per stop, and y reaches 1.0 about 7.6 stops over grey.
  Like cinema log modes, it gains highlight range through an **exposure index**:
  - The sensor is exposed 2 stops under the EI you set: exposure compensation in auto, sensor ISO = EI ÷ 4 in manual.
  - The curve lifts the image back, so the phone's clip point lands 2 stops higher (code ~0.76).
  - The ISO dial shows `EI`. The headroom is reduced automatically where the camera's ISO or exposure-compensation range can't go 2 stops lower.
- **Grading LUTs:** Menu → *Export log LUTs* saves `CityLog_to_Rec709.cube` (33³, soft highlight roll-off) and `CityLog_to_Linear.cube` (1D) to `Documents/City Cinema/LUTs` for Resolve, Premiere, Final Cut or a monitor. CI applies the LUT with FFmpeg and checks it.
- **Camera logs:** **RED Log3G10**, **Sony S-Log3** and **Blackmagic Film Gen 5** are the manufacturers' published transfer functions. The tests check them against the open-source colour-science library to within 2×10⁻⁶. Each uses the same 2-stop EI headroom as City Log, so footage cuts in with the same input gamma in Resolve, Premiere or Final Cut.
  - **Gamut:** each log is recorded in its own wide gamut, with the colour matrix built from the published primaries (checked against colour-science):
    - Sony → **S-Gamut3.Cine**
    - RED → **REDWideGamutRGB**
    - Blackmagic → **BMD Wide Gamut Gen 5**
    - City Log and Flat Log → **Rec.2020**

    The footage looks properly flat and low in saturation, like real log, and grades exactly. In Resolve, use a Color Space Transform with input e.g. *S-Gamut3.Cine / S-Log3*. This needs a camera that allows manual colour correction; on others the colours stay Rec.709 and the sidecar says so.
  - **White balance in the logs is one-push:** the custom colour matrix needs auto white balance off, so the app measures white balance when you choose a log (or tap **ONE-PUSH AWB**) and then holds it, like a cinema camera. Manual Kelvin works as usual.
  - **LUTs:** Menu → *Export log LUTs* writes a 3D LUT per log, including the gamut conversion back to Rec.709: `RED_Log3G10_to_Rec709.cube`, `Sony_S-Log3_to_Rec709.cube`, `BMD_Film_Gen_5_to_Rec709.cube` and `Flat_Log_to_Rec709.cube`.
  - *RED, Log3G10, Sony, S-Log3, Blackmagic and Blackmagic Film are trademarks of their owners. City Cinema implements their published curves for interchange and is not affiliated with or endorsed by them.*
- **Flat Log** is a gentle flat curve without EI headroom: no change to exposure, so shadows stay as clean as Rec.709, with less highlight range than the logs above.
- Other looks: Film, Rec.709, Vivid, Flat.
- Detail: Natural (sharpening off), Standard, or Clean (strong noise reduction).
- **10-bit HDR HLG** (HEVC Main10, BT.2020). Needs Android 13+, a 10-bit camera and a Main10 encoder.

**Codec**
- HEVC or H.264 at cinema-camera bitrates. HEVC 4K24 is about 100 Mb/s at High and about 150 Mb/s at Extreme (H.264 1.6× more). Each level has a floor (Proxy 8, Standard 20, High 40, Extreme 60 Mb/s), so 1080p isn't starved. The encoder's maximum caps it. Before 1.6, 4K High was only ~26 Mb/s, which broke up badly on foliage and gravel.
- CBR is the default, and the encoder is asked for its highest level, because many phone encoders undershoot badly in VBR. Existing installs are switched to CBR and at least High once.
- GOP: Long (1 s), Short (0.5 s) or All-Intra. Bitrate mode: VBR or CBR.
- Resolution lists only sizes that both the camera and the hardware encoder support at the chosen frame rate (4K DCI/UHD, 1440p, 1080p, 720p).

**Focus, zoom and stabilization**
- Continuous AF with tap-to-focus and tap-to-expose. Double-tap to go back to the whole frame.
- Manual focus with distance readout. Set marks A and B, then run eased **focus pulls** over 1, 2, 3, 5 or 8 s.
- Zoom across the whole logical camera range, including ultra-wide below 1x, with eased **zoom ramps** (2, 4 or 8 s, geometric so the speed looks constant).
- Stabilization: off, optical, digital, optical + digital, or preview-matched.

**Preview quality.** The viewfinder gets its own stream of up to 1920 pixels wide. Earlier versions drew the 4K buffer straight into the view, which aliased and looked harshly over-sharpened. That was the viewfinder only, not the recording.

**Cinema auto exposure (all looks).** On cameras with manual exposure, auto exposure is run by City Cinema, like a cinema camera:
- **The shutter stays at your angle** (flicker-safe with Anti-flicker set to 50/60 Hz) and only ISO moves. If even the lowest ISO is too bright, the app tells you how many stops of ND filter you need instead of changing the shutter. *Menu → Auto exposure* can allow shutter changes instead.
- **It holds while a little of the picture clips** rather than alternating with highlight protection, and changes more slowly and smoothly during a take.
- **White balance locks when recording starts** and unlocks when you stop, so colour doesn't drift mid-shot. *Menu → White balance while recording* turns this off.

**Log auto exposure.** In the log modes with auto exposure, City Cinema meters the picture itself and drives ISO and shutter manually:
- It decodes the log curve back to scene light and takes a centre-weighted average, aiming slightly under 18% grey.
- It holds your shutter angle (180° by default) and moves ISO. Above ISO 3200 it lengthens the shutter up to 360° before adding more gain.
- With Anti-flicker set to 50/60 Hz it picks the nearest flicker-free angle, e.g. 172.8° at 24 fps under 50 Hz light.
- EV compensation and AE lock still work.
- **Highlight protection:** when more than 2% of the picture clips it pulls exposure down by at least a quarter stop, and by at least 0.6 stop above 8%.
- **Log-aware monitoring:** in log the picture clips well below white (S-Log3 with 2 stops of headroom clips at code ~0.75). Zebras at 90–100, false-colour red and the histogram's clip % trigger at that code, not at 95 IRE.

Phone auto exposure can't be used here: some phones (seen on a Galaxy S20 Ultra) meter *after* the log curve, read the lifted shadows as brightness and run away to about 6 stops under (ISO 50, 18°). Phones without manual exposure keep the phone's auto exposure.

**Tone-curve check.** Some phones claim to support custom tone curves but ignore them. City Cinema reads back what the camera actually applied. If the curve is ignored, it says so, shows *Phone look*, and turns off the log exposure offset, so log looks don't just record 2 stops dark.

**Clips and player.** **CLIPS** on the side panel opens the clip browser:
- Thumbnails, length, size and look of everything in `Movies/City Cinema`.
- A player with seek, share, and a **live viewing LUT** for log clips. The LUT is chosen automatically from the clip's sidecar and applied on the GPU, while the file stays untouched.
- ProRes `.mov` clips are listed but don't play on the phone, because Android has no ProRes decoder. Play them in Resolve, Final Cut, Premiere or VLC.

**STAB** on the side panel cycles the stabilization modes the camera offers:
- Off, for a tripod or gimbal
- Optical (OIS)
- Digital (EIS: smoother, crops a little)
- Optical + digital (smoothest handheld)
- Preview-matched (Android 13+)

**Monitoring**
- Zebras at 70–100 IRE.
- Focus peaking in four colours and three sensitivities.
- False color: purple for crushed black, blue/teal for shadows, green for 18% grey, pink for skin, yellow for bright highlights, red for clipping.
- Scopes: RGB+luma histogram with clip/crush percentages, luma waveform, or RGB parade.
- Frame guides (2.39, 2.00, 1.85, 16:9, 4:3, 1:1, 9:16, 4:5) with matte, a thirds grid, a centre mark and an electronic level.
- Anamorphic desqueeze preview at 1.33x, 1.5x, 1.8x or 2x.
- A red tally border while recording, plus storage time remaining, battery level and a thermal warning.

**ProRes 422**
- Menu → Recording → **ProRes 422** records **Proxy, LT, 422 or HQ** (`apco`/`apcs`/`apcn`/`apch`) into a QuickTime `.mov`, 10-bit 4:2:2. Files open in Final Cut Pro, DaVinci Resolve, Premiere and FFmpeg.
- Android has no ProRes encoder, so City Cinema includes its own (`jni/prores.c`), written in C from the bitstream Apple published as **SMPTE RDD 36**. Several cores encode frames in parallel.
- Data rates follow Apple's published targets (at 1080p29.97: Proxy 45, LT 102, 422 147, HQ 220 Mb/s). A per-slice quantiser keeps each slice on budget.
- The `.mov` also holds:
  - **24-bit PCM** audio, aligned sample-accurately to frame 0
  - a QuickTime **timecode track** with reel name, record-run or time of day, and drop-frame at 29.97/59.94
  - `colr` color tags and the orientation
  - an optional 32-bit float WAV, plus a JSON sidecar
- If the encoders can't keep up, the next frame fills the missing frame numbers: it is stored once and referenced again, so the file stays constant frame rate and in sync. `ENC xx fps · BUF` on screen shows encoder speed and backlog.
- The source is the camera's 8-bit YUV (BT.709 primaries, BT.601 matrix, as flagged in the file), mapped to 10-bit legal range. Looks such as AC Log are baked in, as on a camera.
- **4K and NEON.** The encoder is all integer arithmetic, with NEON (ARM SIMD) versions of the hot paths:
  - a butterfly 8×8 DCT run 8 rows at a time
  - vectorised quantisation in coding order
  - 8→10-bit mapping and chroma interpolation
  - zero-run skipping with non-zero masks

  It also skips most of the per-slice quantiser search by starting from the quantiser that slice used in the previous frame (about 1.1 tries per slice). One encoder thread runs per core except one (up to 8), sized to fit the phone's memory at 4K. Sizes run from 720p to 4K UHD/DCI, wherever the camera offers YUV output at that size.
- **NEON output is bit-identical** to the scalar reference. In CI, the NEON build made with the NDK compiler that ships in the APK, plus GCC NEON and scalar ARM builds, run under emulated ARM64. They must produce the same bytes as the reference encoder, whose output FFmpeg decodes and checks.
- **Menu → ProRes speed test** encodes 4K, 1440p and 1080p test frames (HQ and LT) on all encoder threads. It tells you which frame rates your phone holds in real time, so you know before you shoot whether 4K ProRes will drop frames.
- **Speed (measured on one x86 desktop core):** HQ takes about 35–45 ms per 1080p frame and about 145 ms per 4K frame. A phone multiplies that by its encoder threads. Recent flagships should hold 4K HQ at 24/25 fps and 4K LT at 30. Mid-range phones are better at 1440p or 1080p. Long takes heat the phone and can throttle it, so leave headroom and watch `ENC` while recording. These are estimates: run the speed test on your phone.
- *ProRes is a trademark of Apple Inc. This is an independent implementation of the published bitstream; it is not an Apple-licensed or Apple-certified encoder. Check Apple's licensing before distributing the app commercially.*

**8K RAW**
- On 50 MP-class sensors with Android's **maximum-resolution mode** (Android 12+), RAW offers **8K UHD 7680×4320**, **8K DCI 8192×4320** (where the sensor is wide enough) or the full sensor. 8K crops of an ordinary full-size RAW stream are offered too.
- Quad-Bayer sensors (2×2 binned) deliver their maximum-resolution RAW as quad Bayer. City Cinema turns that into standard Bayer by **pixel-shuffle remosaic** while copying the frame, so DNG tools can demosaic it. Detail sits between the binned image and true 8K, and fine patterns can show some color artifacts. Native-Bayer sensors give full detail.
- **Frame rate:** limited by the sensor's maximum-resolution readout, often 5–15 fps, which is shown for each option. 5, 6, 8, 10, 12, 15 and 20 fps are offered when 24 isn't possible. Manual exposure gives the steadiest cadence.
- **Preview:** this sensor mode leaves no room for a preview stream, so the viewfinder shows a monitor image made from the RAW frames themselves (white-balanced, ~8 fps), and zebras, peaking and scopes run on it.
- **Memory:** 8K frames are ~66 MB each. Buffers and writer threads are sized to the phone's memory, so an 8K-capable flagship is required.

**RAW video (CinemaDNG)**
- Menu → Recording → **CinemaDNG RAW** records the sensor's Bayer data as a folder of DNG frames per clip. DaVinci Resolve and other CinemaDNG tools open the folder as one clip.
- Every frame is a valid DNG 1.4 containing:
  - the sensor's own color matrices, forward matrices and calibration
  - black and white levels (dynamic black level per frame where the camera reports it)
  - the as-shot white balance
  - a **lens-shading GainMap**, so phone vignetting and color shading are corrected in the raw developer
  - CinemaDNG **TimeCodes**, **FrameRate** (exact 24000/1001 for 23.976) and **ReelName** tags
- **Lossless compression (default):** lossless JPEG CinemaDNG (ITU T.81 process 14, the DNG standard's lossless mode), in 256×256 tiles laid out the way the Adobe DNG SDK does it, with a Huffman table per tile. It typically shrinks data to about **half**. NEON computes the prediction differences, and any frame that wouldn't fit is stored uncompressed and counted. LibRaw decodes every sample exactly in the tests (10, 12 and 14-bit), and so does an independent decoder written from the spec (`tools/lj92_decode.py`). Sensors with white levels above 14 bits are stored uncompressed, because LibRaw mis-decodes 16-bit lossless JPEG.
- Or uncompressed **12-bit packed** (for 10/12-bit sensors) or **16-bit**.
- You can pick from the camera's RAW sizes, for example full sensor or a binned size. Frame rates are limited to what the sensor can stream in RAW.
- Frames are copied into a pool of preallocated buffers and written by parallel threads. If storage can't keep up, the next frame fills the missing frame number(s), so the sequence stays continuous and in sync with the sound. `RAW BUF` on screen shows how full the buffer is, and drops are counted.
- Each clip folder holds `A001_C001_…_000000.dng` …, a 32-bit float WAV, and a JSON sidecar with the measured audio offset to frame 0.
- RAW clips are saved in the app's storage at `Android/data/ug.audiocity.cinema/files/Movies/RAW/`, so thousands of frames don't flood the gallery. Copy them off over USB. **They are deleted if you uninstall the app.**
- The look you choose is for monitoring only: RAW frames are recorded ungraded.

**Audio**
- Always-on 48 kHz capture, so the meters work before you press record. Records 32-bit float where the device allows it.
- Stereo peak meters with hold and a clip latch, and digital gain from -12 to +24 dB.
- Choice of input device (USB, wired or Bluetooth mics) and source (Camcorder, Unprocessed, Mic or Voice).
- AAC 320 kb/s inside the MP4, plus an optional **double-system 32-bit float WAV** for every clip.

**Workflow**
- Camera-style clip names (`A001_C007_20261127`), with camera letter A–D and reels.
- A JSON metadata sidecar per clip recording lens, ISO, shutter, white balance, look, codec, start timecode and dropped frames.
- The volume keys and the camera key start and stop recording.

Files are saved to `Movies/City Cinema` (MP4), `Music/City Cinema` (WAV) and `Documents/City Cinema` (JSON).

## Limits

City Cinema can only use what the phone's Camera2 HAL and hardware encoder expose:
- Manual exposure, manual white balance, custom tone curves, high frame rates and 10-bit each need the matching camera capability. The Menu → About screen shows what your phone supports, and unsupported controls fall back to auto.
- ProRes uses the CPU (no phone has a ProRes hardware encoder), so it is limited by processor speed and heat. RAW is uncompressed CinemaDNG and needs the Camera2 RAW capability.
- RAW data rates are very high. For example, 12 MP at 24 fps is about 430 MB/s uncompressed 12-bit, or about 215 MB/s lossless, and 8K at 10 fps lossless is about 250 MB/s. Fast UFS 3.1/4.0 storage is needed. A smaller RAW size or a lower frame rate gives drop-free recording. Watch `RAW BUF`.
- City Log's extra highlight range comes from exposing under. Shadows carry the sensor noise of the lower ISO, so ETTR-style exposure with zebras at about 90 works best.
- "AC Log" is a tone curve applied to the ISP's output. It flattens and protects highlights, but it can't recover range the ISP has already clipped.
- The camera stays locked to landscape. The preview analysis for scopes, peaking and zebras runs at about 12 fps on a 480-pixel-wide copy of the picture, so peaking is coarser than the recorded image.

## Build

```bash
export ANDROID_SDK_ROOT=/path/to/Android/Sdk   # platform 35 + build-tools 35.0.0
./run-tests.sh                                  # host tests, no phone needed
./build.sh                                      # -> CityCinema.apk
```

With `ANDROID_NDK_ROOT` set (NDK 26), `build.sh` also compiles the native ProRes encoder. Without it the app still builds, but without ProRes. `tools/native-check.sh <ffmpeg> <python>` checks all native code on the host. It decodes the ProRes output with FFmpeg and the lossless DNGs with LibRaw and the spec decoder. With qemu-aarch64 installed, it also confirms the ARM NEON builds are bit-identical.

**On GitHub:** the workflow `.github/workflows/city-cinema.yml` runs the tests and builds `CityCinema.apk` on every push that touches this folder. Download it from the run's **Artifacts**.

**Signing:** to keep updates installable over each other, store your keystore, base64-encoded, as the repository secret `CINEMA_KEYSTORE_BASE64`. Without it, every build gets a fresh development key.

## Status

The host tests pass. In CI, the ProRes encoder and `.mov` writer are checked end to end: test frames at 1080p (and locally also at 4K and at widths that are not a multiple of 16) are decoded by FFmpeg for every profile. FFmpeg confirms profile, 10-bit 4:2:2, frame rate, timecode, reel and 24-bit audio. Luma PSNR is 35 (Proxy), 39 (LT), 43 (422) and 50.6 dB (HQ) on a deliberately noisy test picture, and chroma is 56–63 dB. Also in CI, the sample DNGs (12- and 16-bit, with GainMap and CinemaDNG tags) are decoded with LibRaw, which confirms every sample, the levels, the CFA pattern and the as-shot white balance (`tools/verify_dng.py`). `CinemaTests` covers shutter/angle maths, flicker-free angles, ISO stops, white balance round trips, tone curves, false color, guides, drop-frame timecode, ramps, bitrate and the WAV header). The app has **not yet been run on a phone**. Before relying on it for a shoot, test on your device: recording, audio sync, 10-bit, high frame rate, and the look/WB on your sensor.
