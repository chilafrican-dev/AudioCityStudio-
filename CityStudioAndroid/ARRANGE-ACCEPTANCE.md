# Arrange release acceptance — 16 November 2026

**Release result: FAIL — full replacement is unfinished.** This delivery includes the Stage 1 model and a partial Stage 2 native PCM kernel. PASS below means the stated module test passed; it does not establish
application integration. FAIL/unverified means the release criterion has not been demonstrated,
not that a hardware measurement was performed and failed. Existing partial functionality is
not counted as satisfying every clause of a new requirement.

## Model-specific checks

| Check | Result | Evidence |
| --- | --- | --- |
| Immutable project/track/region/automation/take/comp data | PASS | ArrangeModelTests documents/routesAndTakes |
| Integer 960 PPQN model positions | PASS | MusicalTime and model field declarations; timing tests |
| Exact piecewise tempo reference conversion | PASS | Six rates, dense segment accumulator oracle, large ticks, two-hour case |
| Meter changes and bar/beat mapping | PASS | Signature boundaries, 5,000 bar inverses, invalid mid-bar event |
| Region interval math and source continuity | PASS | 10,000 randomized splits; trim and half-open boundaries |
| Integer snap/cycle math | PASS | 10,000 random nearest-grid checks; tie/wrap/overshoot cases |
| Validation/routing/stack/comp integrity | PASS | Invalid source, cycle, stack, route, path, MIDI and comp tests |
| Existing regression suites | PASS | 42 host suites; separate core runner |
| Android package build | PASS | SDK 35 / ECJ, AudioTrack output; native NDK absent |

## Stage 2 kernel checks (host only)

| Check | Result | Scope |
| --- | --- | --- |
| Native tempo reference parity | PASS | 60,000 large tick positions, independent 128-bit oracle |
| PCM scheduling, overlap, routing, sends, cycle and transport | PASS | Resident stereo PCM only; no app/JNI integration |
| Bounded SPSC handoff and snapshot retirement | PASS | Million-item FIFO and concurrent publication stress |
| Callback allocation/free probe | PASS | Zero instrumented C++ new/delete operations |
| Exact PCM output across live-style/offline-style blocks | PASS | Kernel fixture, not current app export |
| Address/undefined behavior sanitizer | PASS | Leak detection explicitly disabled due host tracing limitation |
| ThreadSanitizer | PASS | Host kernel tests only |
| LeakSanitizer | FAIL — unverified | Runtime rejects leak detection under tracing |
| 32 tracks / 64-frame host timing | PASS — benchmark ran | 18.205 us mean, 1.366% process CPU/audio duration; phone target unverified |
| Complete Stage 2 | FAIL — partial | Disk streaming, MIDI, inserts/PDC, full transport and device integration remain |

## 1. Architecture

| Requirement | Result | Current evidence / gap |
| --- | --- | --- |
| Complete Project maps, markers, ranges, tracks, regions, automation, takes | FAIL — integration pending | New model exists; application still uses Core.Session |
| Canonical integer ticks; sample conversion only in callback | FAIL | New model uses ticks; current app stores frames; reference converter intentionally off-thread |
| All specified Track fields including type/I/O/stacks | FAIL — integration pending | Present in new model; not adopted by headers or mixer |
| All specified Region fields, fades/crossfade/flex | FAIL — integration pending | Data types only; playback/edit semantics unfinished |
| Separate UI/audio/disk-reader/disk-writer threads | FAIL | Replacement thread architecture not implemented |
| Audio thread zero allocations/locks/disk/blocking calls | FAIL | Existing renderer/output cannot satisfy this contract |
| Lock-free SPSC and atomically published immutable snapshots with safe reclamation | FAIL | Native SPSC/exchange pass host tests; app integration/reclamation path still pending |
| Command pattern, transactions, unlimited undo and selection restore | FAIL | Existing undo capped at 50; clip snapshot copies change IDs |
| Versioned atomic temp/fsync/rename save, 60 s autosave, recovery, relative media | FAIL | New schema/persistence not implemented; relative-path validation is model-only |

## 2. Layout / UI

| Requirement | Result | Current evidence / gap |
| --- | --- | --- |
| Bars/beats and min:sec ruler, tempo/marker lanes, yellow cycle, punch, playhead | FAIL — incomplete | Existing ruler is partial; new maps not adopted |
| Header color/icon/rename/M/S/R/I/volume/pan/height/reorder | FAIL — incomplete | Current header has a subset |
| Alternating rows, adaptive bar/beat grid, smooth 60 fps scroll/zoom | FAIL — unverified | Current Canvas exists; no new implementation or frame profile |
| Independent H/V zoom; pointer/playhead anchor; fit selection/project | FAIL — incomplete | Existing horizontal pinch/fit does not verify every behavior |
| Catch page/smooth modes; manual scroll disables until toggle | FAIL — incomplete | Full requested behavior not verified |
| Region names/colors/waveforms/MIDI previews/fade/loop/resize/gain handles | FAIL — incomplete | Existing drawing does not satisfy complete handle contract |
| Background multiresolution min/max peak files; no UI audio decode | FAIL | Replacement peak cache not implemented |
| Whole-project overview strip | FAIL | Replacement overview not implemented |
| Dark theme, touch, mouse/trackpad, shortcuts, accessibility | FAIL — incomplete | Existing theme/touch partial; whole interaction matrix not tested |

## 3. Playback

| Requirement | Result | Current evidence / gap |
| --- | --- | --- |
| Complete play/stop/pause/record/rewind/forward/start/marker/cycle transport | FAIL — incomplete | Existing transport partial; new scheduler absent |
| Metronome, off/1/2/4 bar count-in and pre-roll | FAIL — incomplete | Existing four-beat count-in is not the entire specification |
| Sample-accurate region edges and per-track lookahead | FAIL | Resident-PCM kernel passed host tests; app integration and full scheduler pending |
| Track buffers, inserts, equal-power pan, sends, buses, master | FAIL — unverified | Existing Java renderer not new snapshot engine |
| Denormal protection, safe master, peak/RMS/clip-hold meters | FAIL — unverified | Complete new processing/meter contract untested |
| Seamless cycle, note releases and crossing-region scheduling | FAIL — unverified | Resident-PCM cycle tests pass; MIDI and app integration absent |
| Mid-song tempo changes, following audio stretch, absolute-time audio | FAIL | Metadata/reference maps only |
| Automatic plugin delay compensation for all routes | FAIL — unverified | Existing partial PDC does not establish new engine behavior |
| MIDI controller/program/sustain chase and audio seek offsets | FAIL — unverified | Complete chase on new engine not implemented |

## 4. Recording

| Requirement | Result | Current evidence / gap |
| --- | --- | --- |
| Multiarm, count-in, correct start and capture/pre-roll exclusion | FAIL — incomplete | New recording architecture absent |
| Callback→ring→disk WAV streaming, 24-bit/float, live growing waveform | FAIL | Existing recording is not the requested native callback writer |
| Stop/crash-safe headers and periodic header update | FAIL — unverified | Crash/fault tests not performed |
| Tick timestamped notes, velocity, bend, CC, sustain and hanging-note close | FAIL — integration pending | MIDI event data exists; new recorder absent |
| Region starts at record start; first note retains offset | FAIL — unverified | New recorder integration test absent |
| Cycle pass takes, swipe comp, auto-crossfade | FAIL | Take/comp metadata only |
| MIDI cycle Merge/Create Tracks/Takes | FAIL | Replacement MIDI cycle modes absent |
| Manual/auto punch preserving original audio | FAIL | Range metadata only |
| Overdub/Replace and low-latency monitoring | FAIL — unverified | New native capture path absent |
| Measured round-trip latency compensation | FAIL — unverified | No physical measurement |
| Input meters, clipping and disk space warnings | FAIL — unverified | Complete recording UX not validated |
| Device disconnect/underrun/low-storage resilience without data loss | FAIL — unverified | Device and storage fault-injection tests absent |

## 5. Editing

| Requirement | Result | Current evidence / gap |
| --- | --- | --- |
| Click/shift/cmd selection, marquee/all/track/after-playhead | FAIL — incomplete | New selection model/editor not integrated |
| Pointer, Pencil, Eraser, Scissors, Glue, Solo, Mute tools | FAIL — incomplete | Full new tool behavior not implemented |
| Marquee, Fade, Automation, Flex, Zoom, Loop tools | FAIL — incomplete | Full new tool behavior not implemented |
| Move time/across tracks, modifier-copy, axis lock | FAIL — unverified | New command editor absent |
| Grid/region/marker/playhead snap; Smart/Bar/Beat/Division/Tick/Sample/Off | FAIL — incomplete | Integer grid/bar math only; sample-versus-tick constraint documented |
| Overlap/No Overlap/Crossfade/X-Fade drag modes | FAIL — unverified | Existing implementations not ported/tested against new document |
| Non-destructive start/end trim, loop-resize, stretch-resize | FAIL — incomplete | Trim math only, no integrated tools |
| Split playhead/cursor/selection/transients | FAIL — incomplete | Interior tick split math only |
| Join/Merge/Glue/Repeat/Duplicate/Loop/Unloop | FAIL — incomplete | Replacement command set absent |
| Bounce in Place, Convert to Sampler/Drummer | FAIL — incomplete | New workflow/render integration absent |
| Copy/Cut/Paste at cursor and multi-track relative layout | FAIL — incomplete | New command editor absent |
| Delete and ripple delete | FAIL — incomplete | New command editor absent |
| Fade handles/curves and automatic overlap crossfades | FAIL | Metadata only |
| Gain/normalize/reverse/mute/lock/color/rename | FAIL — incomplete | Data only for new model |
| Flex warp/time/pitch, transient quantize, non-destructive worker cache | FAIL | Metadata only; DSP/cache absent |
| MIDI quantize/strength/swing/transpose/velocity/legato | FAIL — unverified | New MIDI editor commands absent |
| Inline/double-click piano roll | FAIL — incomplete | Existing separate editor not connected to new model |
| Automation lanes volume/pan/sends/plugin params | FAIL — incomplete | Metadata/interpolation only |
| Automation Read/Touch/Latch/Write, drag/curves/region movement/thinning | FAIL | Capture/edit/render modes absent |
| Markers/sections, tempo/signature tracks and section moves | FAIL — incomplete | Data only; arrangement edit absent |
| Folder/summing stacks, expand/collapse | FAIL — integration pending | Validated data only |
| Track freeze and offline bounce identical to live | FAIL — unverified | Resident-PCM kernel parity passes; complete renderer/app/offline integration absent |

## 6. Shortcuts

| Shortcut contract | Result |
| --- | --- |
| Space, R, C, K, Return | FAIL — full specified mapping not verified |
| Ctrl/Cmd+Z and Shift+Ctrl/Cmd+Z | FAIL — unlimited/exact restore not implemented |
| Ctrl/Cmd+T, J, Ctrl/Cmd+R, Ctrl/Cmd+D | FAIL — new command mappings absent |
| S, M, X | FAIL — complete focus/selection behavior unverified |
| Ctrl/Cmd+= and -, Z, F | FAIL — complete zoom/fit/follow mappings unverified |

## 7. Correctness / quality

| Requirement | Result | Evidence / gap |
| --- | --- | --- |
| No crashes/ANRs/leaks/data races; ASan/TSan clean | FAIL — unverified | Kernel ASan/UBSan and TSan passed; leaks and whole app/device unverified |
| Zero xruns at 64 frames, 32 tracks, midrange device; CPU load | FAIL — unverified | No suitable device; no invented CPU/xrun result |
| Sample-accurate timing and bit-identical realtime/offline bounce | FAIL — unverified | Model oracle passed; new complete renderer/app integration absent |
| Every command undoable; 10,000 edits then exact full undo including selection | FAIL | Command engine/test absent; randomized model windows and playback tests are not undo tests |
| Empty/zero-length/overlap/out-of-end/2 h audio | FAIL — integration pending | Model covers empty/zero/overlap/long timing; no complete media workflow test |
| Import resample, mono/stereo/multichannel | FAIL — unverified | New import/scheduler path not implemented |
| Missing media relink and corrupt project recovery | FAIL | New persistence/relink workflow absent |
| Device changes mid-playback | FAIL — unverified | No device integration test |
| No UI frame work over 4 ms | FAIL — unverified | No UI frame profiling |
| Validate all inputs, reject corrupt files without crashes | FAIL — unverified | Model validation passed; new file decoder/load fuzzing absent |

## 8. Deliverables

| Deliverable | Result | Evidence / gap |
| --- | --- | --- |
| Full organized source for all replacement modules | FAIL — partial | Complete existing app plus new Stage 1 model; Stage 2 partial, stages 3–8 not delivered |
| Build instructions and dependencies | PASS | ARRANGE-REBUILD.md and BUILD-ANDROID.md |
| Tick/sample, tempo, region, snap unit tests | PASS | ArrangeModelTests, 49,090 assertions |
| Undo and ring-buffer unit tests for new architecture | FAIL — partial | Ring tests pass; command/undo tests absent |
| Record/cycle/punch/bounce/save/load integration tests for new architecture | FAIL | Not implemented; existing regression tests cover older behavior only |
| Acceptance results for every requested group | PASS | This document records pass/fail and unverified gaps |
| Honest known limitations | PASS | ARRANGE-REBUILD.md and per-row gaps |
| Complete/test stages 1–8 sequentially | FAIL — partial | Stage 1 model foundation and partial Stage 2 PCM kernel tested; later work pending |
