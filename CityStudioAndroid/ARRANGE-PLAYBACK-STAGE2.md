# Arrange playback — Stage 2 kernel, 16 November 2026

## Delivery status

The native resident-PCM playback kernel is implemented and host-tested. **Stage 2 as a
whole is not complete.** This kernel is not yet connected to the Android Arrange UI,
JNI, device output or disk streaming. The current app still plays through AudioIO.Engine
and Core.Renderer. Its existing AAudio backend has not been replaced by this delivery.
There is no new APK to install for these changes.

## Files changed

| File | Responsibility |
| --- | --- |
| `jni/arrange/Realtime.hpp` | Bounded SPSC queue; safe snapshot publication/retirement; exact allocation-free native tempo conversion |
| `jni/arrange/Playback.hpp` | Prepared immutable PCM document, interval scheduling, stereo mixer, buses/sends, transport command queue, status queue, callback handoff FIFO and PCM/offline rendering |
| `tests/native/PlaybackTests.cpp` | Timing oracle, concurrency stress, source boundaries, overlap/routing/cycle tests, snapshot transport continuity, offline PCM parity, allocation probes and host benchmark |
| `run-native-playback-tests.sh` | Release, Address/UndefinedBehavior and ThreadSanitizer host builds |
| `ARRANGE-ACCEPTANCE.md`, `README.md` | Updated results and explicit integration gaps |

## Implemented behavior

- Timeline positions remain integer 960-PPQN ticks. Native conversion uses whole
  microseconds plus a 1/960-microsecond remainder. It avoids heap operations, floating
  timing calculations, nonportable 128-bit arithmetic and accumulated segment rounding.
  Tests compare against an independent host-only 128-bit oracle over 60,000 large positions.
- PCM region start/end boundaries use the tempo map in the render call. Empty windows
  are silent. Mid-region seek uses the correct source-frame offset. Sources are already
  decoded, resident stereo float PCM at the engine sample rate; resampling is not implicit.
- Balanced per-track interval trees prune ended subtrees while retaining long overlapping
  clips. Their storage is prepared off the callback. Query traversal uses a bounded stack.
- Mixing supports gain, equal-power pan, mute, solo with downstream buses, pre/post-fader
  sends and validated acyclic routing. A smooth master knee bounds output; nonfinite intermediate overflow cannot poison
  output or meters. It includes peak/RMS
  and clip-hold state in the renderer. Final output subnormals are flushed to zero.
- Cycle wrapping is relative to cycle start. Regions crossing the cycle boundary render
  at their proper source offsets. A fixed 32-sample de-click interpolation is independent
  of block size. This is not proof that every musical loop is inaudible at its boundary.
- The same PCM render path processes small live-style blocks and large offline-style
  requests. A multi-cycle/overlap/repeated-source fixture produces bit-identical samples
  at 64-frame versus 512-frame internal block capacities. This result applies to this
  kernel, not to the existing application's bounce/export.
- The snapshot exchange keeps old objects alive until the callback retires them. All
  deletion occurs on control or shutdown after the callback stops. No shared_ptr
  destruction, mutex, disk operation or blocking API occurs in the kernel callback.
- Live snapshot adoption preserves the fractional-tick runtime cursor. Replacing a
  document with the same tempo no longer rounds the cursor to a different sample.
  Runtime phase is not persisted as a floating-point timeline position.
- Transport commands and status use bounded SPSC mailboxes. Full command queues return
  failure to the caller. A callback processes at most 128 commands; status polling
  drains at most 64 records, keeping producer activity from making either loop unbounded.
- OutputFifo consumes prepared stereo frames and supplies initialized silence on
  starvation, with consumed/missing counters. It does not open a device or synthesize
  missing audio. Counter values wrap modulo 2^32; they are diagnostics, not persisted time.

All public callback APIs assume valid output pointers/buffer lengths supplied by their
binding. Control must stop callbacks before destruction. A renderer and its prepared state
have one audio owner; do not run a second renderer concurrently on the same prepared object.
PCM sources, scratch buffers, graph validation and interval trees are allocated on control.
Control and audio must follow the documented single-producer/single-consumer ownership.

## Test results

| Check | Result |
| --- | --- |
| Native release suite | PASS — 320,187 assertions |
| Callback allocation probe | PASS — zero instrumented C++ new/delete calls in marked callback paths |
| SPSC concurrency | PASS — 1,000,000 ordered items across producer and consumer threads |
| Snapshot publication/reclamation | PASS — 20,002 objects accounted for; none destroyed on callback |
| Fractional-tick continuity on snapshot swap | PASS |
| Realtime-style/offline-style PCM equality | PASS — exact sample bytes across multiple cycle wraps |
| AddressSanitizer + UndefinedBehaviorSanitizer | PASS with leak detection disabled; no reported address/UB errors |
| ThreadSanitizer | PASS on the host kernel/concurrency tests |
| LeakSanitizer | NOT VERIFIED — runtime refuses leak detection under this environment's tracing setup |
| Existing Android-source host suites | PASS — all 42 suites |
| Android NDK build / real device output | NOT RUN — no NDK/device available |

The test executable instruments normal C++ new/new[]/delete/delete[]. This is not a
universal malloc hook or a proof about future plugins, JNI or third-party libraries.
Sanitizer results apply to this tested host module, not to the full Android application.

## Host performance result

Release build, g++ 14.2, `-O2 -fno-fast-math`, x86_64 AMD EPYC 7763 host:
32 resident-PCM tracks, 48 kHz, 64 frames, 20,000 blocks:

- Mean wall time: **18,205 ns/block** (18.205 microseconds).
- Mean wall time uses **1.365%** of a 1.333 ms callback budget.
- Measured process CPU time per represented audio duration: **1.366%**.

This is a synthetic host result with no plugins, disk I/O, resampling, synthesis, device
scheduling or UI. It is not an Android CPU-load measurement, a worst-case execution
bound, a device xrun count or evidence that the requested 32-track phone target passes.
Sanitized-build timings are deliberately not used as release performance measurements.

## Build and run

Requires a C++17 compiler and pthreads; no Android SDK is needed for these tests.

```sh
./run-native-playback-tests.sh
./run-native-playback-tests.sh tsan
ASAN_OPTIONS=detect_leaks=0:halt_on_error=1 \
UBSAN_OPTIONS=halt_on_error=1 ./run-native-playback-tests.sh asan
```

A suitable unrestricted environment should also run ASan with leak detection enabled.
The script fails on compiler warnings and sanitizer errors. It does not hide sanitizer
failures or automatically disable leak detection. The explicit environment setting above
records the limitation of this environment.

## Remaining before Stage 2 can pass

1. Compile the Stage 1 Java document into the native prepared document through a validated
   binding; use it in the actual playback path with safe stream lifecycle handling.
2. Add bounded disk-reader streaming/prefetch, underrun recovery, media resampling and
   large-file operation; do not put mmap page faults or decoding in the callback.
3. Add MIDI/instrument scheduling, controllers/program/sustain chase and note release at
   cycle boundaries; preserve the current sampled-instrument timbres.
4. Implement insert DSP and automatic PDC across all dry/send/bus paths, with one shared
   live/offline processing chain. This kernel currently has no plugin insert processing.
5. Add full metronome/count-in/pre-roll transport, markers, end behavior and monitoring
   integration. Only Play/Pause/Stop/Seek/clip-clear commands are implemented here.
6. Implement the non-follow-tempo/absolute audio policy and cached pitch-preserving
   follow-tempo audio. Repeated PCM and region timing are not time-stretching.
7. Validate the device callback, disconnection/shutdown, native Android build and profiler
   on representative phones, including the requested 64-frame/32-track xrun/CPU test.

These items are not stubs in the source. They are undelivered portions of the larger
Stage 2 specification, and no later stage has been marked complete to conceal them.

Android callback/thread-lifecycle integration should follow the primary platform guidance:
https://developer.android.com/ndk/guides/audio/aaudio/aaudio
