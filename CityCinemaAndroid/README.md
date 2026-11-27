# City Cinema 1.0

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
- Looks via custom tone curves: **AC Log** (flat and gradable, 18% grey at about 42%), Film, Rec.709, Vivid and Flat.
- Detail: Natural (sharpening off), Standard, or Clean (strong noise reduction).
- **10-bit HDR HLG** (HEVC Main10, BT.2020). Needs Android 13+, a 10-bit camera and a Main10 encoder.

**Codec**
- HEVC or H.264. Four quality levels from Proxy to Extreme, capped at the encoder's maximum bitrate.
- GOP: Long (1 s), Short (0.5 s) or All-Intra. Bitrate mode: VBR or CBR.
- Resolution lists only sizes that both the camera and the hardware encoder support at the chosen frame rate (4K DCI/UHD, 1440p, 1080p, 720p).

**Focus, zoom and stabilization**
- Continuous AF with tap-to-focus and tap-to-expose. Double-tap to go back to the whole frame.
- Manual focus with distance readout. Set marks A and B, then run eased **focus pulls** over 1, 2, 3, 5 or 8 s.
- Zoom across the whole logical camera range, including ultra-wide below 1x, with eased **zoom ramps** (2, 4 or 8 s, geometric so the speed looks constant).
- Stabilization: off, optical, digital, optical + digital, or preview-matched.

**Monitoring**
- Zebras at 70–100 IRE.
- Focus peaking in four colours and three sensitivities.
- False color: purple for crushed black, blue/teal for shadows, green for 18% grey, pink for skin, yellow for bright highlights, red for clipping.
- Scopes: RGB+luma histogram with clip/crush percentages, luma waveform, or RGB parade.
- Frame guides (2.39, 2.00, 1.85, 16:9, 4:3, 1:1, 9:16, 4:5) with matte, a thirds grid, a centre mark and an electronic level.
- Anamorphic desqueeze preview at 1.33x, 1.5x, 1.8x or 2x.
- A red tally border while recording, plus storage time remaining, battery level and a thermal warning.

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
- There is no RAW or ProRes recording: Android phones don't expose a ProRes encoder, and RAW video needs a per-frame DNG pipeline that this version doesn't have.
- "AC Log" is a tone curve applied to the ISP's output. It flattens and protects highlights, but it can't recover range the ISP has already clipped.
- The camera stays locked to landscape. The preview analysis for scopes, peaking and zebras runs at about 12 fps on a 480-pixel-wide copy of the picture, so peaking is coarser than the recorded image.

## Build

```bash
export ANDROID_SDK_ROOT=/path/to/Android/Sdk   # platform 35 + build-tools 35.0.0
./run-tests.sh                                  # host tests, no phone needed
./build.sh                                      # -> CityCinema.apk
```

**On GitHub:** the workflow `.github/workflows/city-cinema.yml` runs the tests and builds `CityCinema.apk` on every push that touches this folder. Download it from the run's **Artifacts**.

**Signing:** to keep updates installable over each other, store your keystore, base64-encoded, as the repository secret `CINEMA_KEYSTORE_BASE64`. Without it, every build gets a fresh development key.

## Status

The host tests pass (`CinemaTests`: shutter/angle maths, flicker-free angles, ISO stops, white balance round trips, tone curves, false color, guides, drop-frame timecode, ramps, bitrate and the WAV header). The app has **not yet been run on a phone**. Before relying on it for a shoot, test on your device: recording, audio sync, 10-bit, high frame rate, and the look/WB on your sensor.
