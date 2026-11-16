# Arrange rebuild — Stage 1 delivery, 16 November 2026

## Current result

This is an incremental source delivery, not the completed Logic-style Arrange replacement.
The Android app remains the existing 0.13.18 Java application. Its native backend is optional
C++ AAudio, not Oboe. No Kotlin migration or new Oboe dependency has been introduced.
The new platform-independent model compiles into the app but is **not yet used by its UI,
playback, recording or project save format**. Existing sessions have not been migrated or rewritten.
Do not describe this APK as implementing the new specification.

## Stage 1 implementation

| File | Responsibility |
| --- | --- |
| `src/ug/audiocity/studio/arrange/model/MusicalTime.java` | Integer 960 PPQN positions; exact reference tick/sample conversion; tempo and time-signature maps; bars/beats; grid snapping |
| `src/ug/audiocity/studio/arrange/model/ArrangeProject.java` | Immutable project, source, track, region, marker, cycle/punch, automation, routing, flex metadata, take folder and comp document types |
| `src/ug/audiocity/studio/arrange/model/RegionMath.java` | Half-open windows, non-destructive trim/split boundaries, cycle wrap and repeated-source offsets |
| `tests/ArrangeModelTests.java` | Deterministic model, musical time, validation, randomized region/snap and immutability contracts |
| `run-arrange-model-tests.sh` | Fast standalone model tests without an Android SDK or instrument assets |
| `build.sh`, `run-tests.sh`, `run-core-tests.sh` | Discover nested Java module sources and include the new suite |

All document collections are defensively copied and unmodifiable. Builders copy editable
collections while retaining immutable values. Stable caller-supplied IDs survive model edits.
A project validates referential integrity, routing destinations and feedback, stack ancestry,
source windows, automation points, take membership, comp ranges and unique object IDs.
Empty projects and zero-length region windows are legal. Empty enabled cycle/punch ranges,
zero-length loop sources, NaN/Infinity, unsafe paths and invalid MIDI data are rejected.
Deep routing graphs are traversed iteratively, avoiding recursive graph stack overflow.

Source audio format metadata records integer sample rate, channel count and frame count.
Stored musical positions and durations are long ticks. Absolute audio anchor metadata uses
integer nanoseconds, allowing an eventual non-follow-tempo audio implementation to preserve
wall-clock placement without floating-point persisted positions. Flex/comp/plugin records
are data, **not implementations of stretching, comp playback or plugin processing**.

Tempo is integer microseconds per quarter note, like MIDI tempo events. The reference map
accumulates exact tick × microsecond products using BigInteger and rounds at the final
sample boundary. It does not accumulate per-segment sample rounding. It is an off-thread
reference implementation and must **not** run in a hard real-time callback. The playback
stage must compile an allocation-free native representation and compare against this oracle.
Meter events start on complete bars, and bars/beats are zero based internally.

## Validation results

| Check | Result |
| --- | --- |
| New model suite | PASS — 49,090 assertions |
| Entire Android-source host suite | PASS — 42 suites, including the new model suite |
| Existing SDK-independent core runner | PASS |
| Android compilation and APK packaging | PASS — AudioTrack-only build; warnings remain |
| Physical Android UI, microphone, native callback, xruns and timing | NOT RUN — no device or emulator |
| ASan / TSan | NOT RUN — new code is Java model code; native engine not replaced |

The timing suite covers six sample rates (8–384 kHz), changing tempo, 10,000 dense tempo
segments without accumulated rounding, two-hour duration, large integer positions,
bar/signature inverses, and invalid tempo/rate boundaries. Randomized interval tests verify
split continuity and source offsets for 10,000 windows. The separate 10,000-edit/full-undo
acceptance test has **not** been implemented; it is not the same test.

## Build and dependencies

Full Android build dependencies remain Android SDK platform 35, build-tools 35.0.0,
Java 17+ compiler (or Eclipse ECJ producing Java 8 bytecode), and the existing instrument
assets supplied in this source archive. Native AAudio additionally needs an Android NDK
through `ANDROID_NDK_ROOT`. This delivery does not bundle an NDK or Oboe.

From `CityStudioAndroid`:

```sh
# Model only: no Android SDK needed.
./run-arrange-model-tests.sh

# Alternate compiler when javac is unavailable.
ECJ_JAR=/absolute/path/ecj.jar ./run-arrange-model-tests.sh

# All host suites; requires Android SDK and a javac command.
ANDROID_SDK_ROOT=/absolute/path/android-sdk ./run-tests.sh

# Current application; source discovery now includes nested model modules.
ANDROID_SDK_ROOT=/absolute/path/android-sdk ./build.sh
```

See `BUILD-ANDROID.md` for the existing signing and Android installation workflow.
The source ZIP excludes private signing keys and generated build products.

## Remaining implementation sequence

1. Stage 1 model and test foundation: implemented and tested in isolation; app migration pending.
2. Playback: compile tick-domain snapshots, native callback renderer, SPSC disk streams,
   non-RT snapshot reclamation, scheduling/chase/cycle/PDC, metering and offline parity.
3. UI: adopt the tick document, peak cache, overview/ruler/headers/tools/zoom/Catch/accessibility.
4. Editing: commands and transactions, exact selection/ID undo, complete tools and 10,000-edit test.
5. Recording: native capture rings, disk worker/recovery, growing regions, punch, latency, takes/comping.
6. Automation/flex: render every documented mode; cache non-destructive time/pitch work.
7. Persistence: new schema and audited legacy migration, fsync/rename, 60 s autosave and recovery/relink.
8. Hardening: sanitizers, fault injection, device benchmarking, profiler/frame budgets and release QA.

No later stage is marked complete by the presence of its metadata types. The exhaustive
release acceptance table is in `ARRANGE-ACCEPTANCE.md`.

## Important unresolved constraints

At 120 BPM/48 kHz one 960-PPQN tick spans 25 samples. Arbitrary one-sample placement
cannot be represented solely by integer ticks at that resolution. Sample snapping needs an
explicit integer sub-tick/source-sample policy; otherwise it can only snap to the nearest
representable tick. This delivery does not silently promise both incompatible guarantees.

Existing `Core.Session`, `Core.Clip` and `Pro.TempoMap` remain frame based; converting
only a display label would not constitute migration. Existing undo uses capped session
copies, and Clip.copy creates new IDs. Existing Java mixing allocates and accesses mutable
state; optional native output currently uses blocking writes. Existing project storage and
recording are not substitutes for the new architecture. No zero-xrun, 4 ms frame,
sample-accurate-new-engine or bit-identical bounce result has been measured or claimed.
