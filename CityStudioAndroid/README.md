# Audio City Studio for Android

Version 0.12.0 — development build, developed for Nico Pan / Audio City.

Native Android application with an ash-grey and dark-grey interface, rounded panels, silver knobs, waveform arrangement, channel strips, meters and transport controls. This is an independently built DAW interface inspired by conventional studio workflows; it is not affiliated with Apple or Logic Pro.

## Added in 0.12: vocal editor (note by note)

Select an audio clip → clip menu → **Pro clip tools… → Vocal editor (notes, pitch, timing)…**

- Each sung note is shown as a block on a piano-style grid, with the pitch line you will hear drawn through it. A faint line marks where the note was sung when it has been moved.
- **Pitch:** drag a note up or down (snaps to semitones, or free), use −1/+1/±octave, *Centre on semitone*, or *Snap to* the Vocal Tune key and scale.
- **Per-note knobs:** Drift (slow wander into and out of the note), Vibrato (0 % flat, 200 % double), Gain (−24 to +12 dB) and Formant (tone colour, ±12 semitones, pitch unchanged).
- **Timing inside a phrase:** choose *Drag: timing*, then drag a note sideways (its neighbours stretch to fill) or drag its right edge. The first and last notes of a phrase stay in place; move the clip to move a whole phrase.
- **Split and join:** long-press a note where it should be cut, or use *Split at middle* / *Join with next*. *Detect again* starts over.
- Knobs and buttons act on the selected notes, or every note when none is selected. The editor has its own undo; *Apply* is one studio undo step.
- Rendering always starts from the preserved original recording, so the edits are saved in the project and can be reopened and changed. *Back to original* restores the untouched recording. Running Vocal Tune on the clip afterwards replaces the note edits.
- Built on the Vocal Tune pitch tracker and its epoch-aligned PSOLA engine. It is meant for one voice at a time (lead or backing vocal, solo instrument); chords and heavy reverb confuse note detection. Large formant or pitch moves (beyond about 4 semitones) start to sound processed.

## Added in 0.12: free loops online (Freesound, CC0 only)

Sample browser (Afrobeat Toolkit) → **⇩ Get free loops online (CC0)**, or Pro tools → *Get loops online*.

- Search by style (Afrobeat, Amapiano, Afro house, hip hop, trap, drill, dancehall, reggae, R&B, house, pop, lo-fi, gospel, jazz, rock), instrument and your own words, optionally only near the project tempo. Only CC0 (public-domain) loops are shown; any other licence is dropped even if the server returns it.
- Preview streams the loop on repeat. **Insert on track** downloads it once into the phone's loop library (Downloaded list, works offline), places it at the playhead, and offers keep tempo, fit keeping pitch (WSOLA) or fit by resampling when the uploader stated a tempo that matches the loop length. MP3 start padding is trimmed so loops land on the beat.
- Each loop's creator, licence and source page are added to Project notes.
- Downloads are Freesound's HQ MP3 previews (about 128 kbps), the same quality as the bundled Afrobeat loops; original WAV downloads need a full Freesound login (OAuth), which is not built.
- **Needs a free Freesound API key** (freesound.org/apiv2/apply), pasted once. **Freesound's free API is for non-commercial use only.** The CC0 sounds themselves are free for any use, but shipping this search inside an app you sell or monetise needs a commercial API licence from Freesound (contact mtg@upf.edu). Until then, keep this feature for personal/non-commercial builds or remove it from a commercial release.
- Needs the INTERNET permission, which this version adds.

## Added in 0.12: 11 new effects

Channel → Inserts → **+ Add insert** (tracks, buses and master): Phaser, Flanger, Tremolo · auto-pan (tempo-synced), Auto-filter (low/band/high-pass with tempo LFO and envelope), Overdrive · distortion (soft, hard, fuzz, tube), Bitcrusher · lo-fi, Noise gate, De-esser (split band), Vocal doubler, Exciter and Pitch shifter (±12 semitones). Each shows a line of help under its knobs. That makes 21 insert effects in total.

## Added in 0.11: Logic-style snap and drag

Open the toolbar's Snap button → **Snap & drag**.

- **Grid:** Smart (follows zoom, from bars when zoomed out down to 1/64), Bar, Beat, 1/8–1/64, or Off.
- **Magnetic:** a dragged clip's start or end locks to other clips' edges (on any track), markers, the playhead and the loop range edges. A yellow guide names what it locked to, with a short buzz. Seeking on the ruler also locks to markers and loop edges.
- **Relative** snapping moves clips by whole steps and keeps their offset from the grid.
- **Edge trims:** drag a clip's left or right edge to trim it. Trims follow snap and magnets, then move to the nearest zero crossing to avoid clicks (can be turned off).
- **Fine drag:** rest a second finger on the screen while dragging to place a clip freely.
- **Drag modes:** Overlap (default), No Overlap (cuts a hole in the clip underneath), X-Fade (crossfades overlaps), Shuffle (butts clips together and pushes later clips along).

Snap settings are saved with the project.

## Added in 0.10: performance and timing

- **Audio quantize** (clip menu → Pro clip tools → Quantize audio timing): finds each hit, slices the clip there and moves hits to the grid (1/4–1/32), with strength and sensitivity controls. Slices stop at the next hit and use short crossfades. Undo restores the original clip.
- **Clip launcher** (Pro tools → Clip launcher): 8 slots per track and scene buttons. A launched clip loops from the next bar and replaces that track's arrangement until stopped. Slots can hold audio or live MIDI clips, and you can copy a slot to the arrangement at the playhead. Slots are saved with the project.
- **MIDI controller mapping** (Pro tools → MIDI controller mapping): choose a target, then move a knob or hit a pad to learn it. Targets include track volume, pan, mute, solo and sends, bus and master volume, any insert parameter on the selected track (logarithmic for frequencies), and transport (play, record, loop, marker, undo). Mapped controls no longer play notes. Mappings are saved with the project.

Third-party plugins are still not supported, because Android has no standard plugin format.

## Added in 0.9: modern-DAW essentials

- **Live MIDI clips.** MIDI recordings now stay as notes plus an instrument, rendered on the fly, so they remain editable. The piano roll applies changes instantly. "Change instrument…" swaps the sound at any time. Track menu → "New MIDI clip at playhead" makes an empty 4-bar clip. Older printed performances can be converted with "Convert to live MIDI clip". Turn off the `liveMidi` preference to keep the old print-to-audio behaviour.
- **Per-clip time-stretch** (clip menu → Pro clip tools → Time-stretch): fit to a number of bars, convert from the clip's own BPM to the project tempo, or set a percentage. It uses the existing pitch-preserving WSOLA, 50–200% per stretch. The original audio and window are kept, so each new stretch starts from the original and Reset brings it back.
- **Plugin delay compensation.** Tracks and buses with look-ahead inserts are delayed relative to each other so every path stays in time at the bus inputs and at the master. Exports remove the added delay. Projects without look-ahead inserts are unaffected.
- **Bigger projects:** 60-minute timeline (was 10), 64 tracks (was 24), 16 buses (was 8). Phone performance limits still apply to very large sessions.

Compatibility: projects that use none of these features render identically. Live MIDI clips are saved in session.json with no audio file.

## Added in 0.8: mixing, editing, mastering and workflow

- **Mixing:**
  - Five new insert types: a soft-knee compressor with parallel mix and sidechain low cut, a plate reverb (size, decay time, damping, pre-delay, width, low cut), a tempo-synced delay with filtered feedback and ping-pong, tape saturation, and a transient shaper.
  - The master screen shows a live 20 Hz–20 kHz spectrum and a stereo correlation reading with a phase warning.
- **Editing:**
  - A loop and edit range (from the playhead, markers, a clip or a number of bars), shaded on the ruler.
  - Range copy, cut with ripple, delete leaving a gap, paste at the playhead, insert time and split at the range edges, on all tracks or the selected one. Automation and markers follow.
  - Clip tools: nudge, duplicate ×N, normalise to −1 dBFS, reverse, strip silence (threshold, minimum gap, padding), copy and paste, and set the loop range to a clip.
  - Bounce a track, with its effects and fader, to a new audio track. The original is muted, not deleted.
- **Mastering:**
  - Reference-track A/B: import a commercial song, level-matched automatically to your mix's integrated loudness, and switch between the two.
  - Four mastering presets: Streaming, Loud, Warm & gentle, and Podcast.
  - Export at 44.1 kHz through a 96-tap Kaiser-windowed polyphase resampler (flat to 20 kHz, more than 100 dB alias rejection), with or without TPDF dither. WAV, FLAC and stems are available at 44.1 kHz.
- **Workflow:**
  - Loop playback inside the range.
  - A 1- or 2-bar count-in before recording (the take is trimmed to where you pressed Record), with an optional click while recording.
  - Hardware keyboard shortcuts (Pro tools → Keyboard shortcuts).
  - Project notes saved with the project, and Duplicate project.

All 0.7 compatibility guarantees still hold. Projects without these features render and export identically.

## Added in 0.7: professional workstation features

Open the project menu → **Pro tools…** (plus new entries in each track's and clip's menu).

- **Tracks, buses and routing.** Up to 24 audio tracks and 8 buses. Each track can output to the master or a bus, has pre- or post-fader sends, and can sidechain its compressor from another track (the key works even when the key track is muted). Reverb and delay buses are 100% wet. Tracks with the same group name move their faders and pans together.
- **Insert slots.** An ordered, reorderable chain after the channel strip, up to 12 per channel, on tracks, buses and the master: 8-band parametric EQ (bell, shelves, 24 dB/oct cuts, notch) with a draggable response curve; 3-band Linkwitz–Riley multiband compressor; look-ahead true-peak limiter; utility (gain, width, phase, mono bass); stereo chorus.
- **Master chain and metering.** Master inserts, a one-tap mastering chain, and a live EBU R128 meter: momentary, short-term and integrated LUFS, loudness range, 4× oversampled true peak, and limiter gain reduction. "Analyse whole mix" measures the full render offline.
- **Automation.** Breakpoint lanes for volume, pan, sends, master volume and the main channel-strip controls. Write mode records fader and pan moves while playing. Lanes can be shown on the arrangement and edited point by point.
- **Editing.** Every recording is kept as a take. Takes & comping swaps in the best part of any take with equal-power crossfades. Optional overlap crossfades per track (the later clip plays on top). Slip edit, track or global ripple delete, and adjustable crossfade length.
- **Piano roll** for MIDI clips: add, move, delete and select notes, quantize (100%, 50%, swing), velocity, transpose, zoom, local undo. "Print to audio" uses the current instrument.
- **Tempo map, time signature and markers.** Tempo changes at bar boundaries drive the click, the transport's bar display and Pro snapping. The click accents bar starts in any metre from 2 to 12 beats. Markers appear on the ruler and work for jumping and comp ranges.
- **Recording.** Low-latency output (device burst size, two-burst buffer), input monitoring (optionally through the track's FX), USB interface input selection including multi-channel inputs and stereo pairs, and automatic recording-latency compensation with a speaker-to-microphone click calibration. A warning appears when Bluetooth output is active.
- **Export.** WAV 24/16-bit, FLAC 24/16-bit (bit-exact lossless), AAC 256 kbps .m4a, and stems (one WAV or FLAC per track, in a ZIP). Optional loudness normalisation to −14/−16/−23 LUFS with a −1 dBTP ceiling. Exports remove master look-ahead delay.
- **Safety.** Undo history (50 steps, labelled and timed) with multi-step jump back, autosave every minute, emergency save on a crash, and a recovery notice on the next launch. Project templates (three built in, plus your own) and channel presets (channel strip plus inserts).
- **Layout.** Tablets (smallest width ≥ 600 dp) can rotate. Phones stay in portrait.

Compatibility: projects that use none of these features render sample-identically to 0.6 (checked against a saved reference render), and their 16/24-bit WAV exports are byte-identical. New project keys are written only when used, so 0.6 project files still load.

Added in 0.7.1:
- **Native AAudio output** (jni/audiocity_aaudio.cpp). In low-latency mode the engine opens an exclusive (MMAP) AAudio stream, then a shared one, then falls back to AudioTrack. Recording & audio settings show which path is active. build.sh compiles it for arm64-v8a, armeabi-v7a and x86_64 when ANDROID_NDK_ROOT points to an NDK (r23 or later). Without the NDK the APK builds as before, using AudioTrack only.
- **Track folders.** Put tracks in named folders from the track menu. Pro tools → Track folders can collapse a folder (its tracks stay audible), mute or solo the whole folder, trim all member faders together, rename it or dissolve it.
- **Tempo-aware arrangement grid.** Grid lines and bar numbers follow tempo changes and the time signature, and dragging clips snaps to the tempo-mapped grid.

Not included, and why:
- MP3 export is not included because Android has no built-in MP3 encoder, and a bundled encoder needs the format's standard code tables, which could not be obtained in this build environment. AAC (.m4a) plays everywhere MP3 does.
- Ableton Link needs Ableton's Link SDK and its licence, which could not be obtained here.
- Collapsed folders hide tracks in the arrangement only; the mixer still shows every track.
- Monitoring through Android adds audible delay on many phones. Direct monitoring on an interface is better.

Testing status: the engine, project format and exporters are covered by host tests. The AAudio C++ was syntax-checked against the AAudio API and its JNI signatures were checked against a javac-generated header, but it has not been compiled with the NDK or run on a device. The Android screens and audio-device code (low latency, monitoring, USB inputs, calibration, AAC) were compiled against host stand-ins for the Android API, not the real SDK, and have not been run on a phone. Build with the SDK and test on hardware before relying on them.

## Added in 0.6: phone-vocal presets calibrated to supplied takes

- Effects → Preset starts with four Phone profiles: Clean (dry), Afropop Lead, Dancehall & Rap, and Soft R&B. They were calibrated against all three supplied phone takes. They are starting points for listening, not a guarantee of a commercially finished mix.
- Select a vocal region before choosing a Phone preset. If no region is selected, calibration uses the first region on the selected channel. Up to the first 30 seconds of that region are measured, including clip gain. Input trim, compressor threshold, soft expander threshold and output level are adapted to that region. The channel fader, project key, pitch correction and source files are not changed.
- Measurements on the supplied recordings showed strong vocal-body energy, quiet pauses and prominent consonant peaks. The profiles therefore use restrained shaping rather than large treble boosts: 80–95 Hz low cuts, mild 600–750 Hz bell cuts, gentle high-shelf changes, compression and de-essing. Clean is dry; the other styles have small room/delay amounts. Saturation is off in all four.
- The quiet-level estimate is a percentile-based calibration aid, not a trained noise detector. The expander lowers pauses with a 40% floor; it is not broadband noise removal or de-reverberation. If a region has no convincing quiet interval, the expander is disabled rather than treating continuous singing as background noise.
- Added input/output trims, a switchable 12 dB/octave low-cut filter, adjustable mid-bell frequency and expander floor to the native effects panel and saved FX schema. Existing projects default these new controls to neutral/off.
- Phone profiles use stereo-linked, zero-lookahead sample-peak protection after the effects, with a .82 linear ceiling and smooth release. This catches brief peaks while output calibration keeps active vocal level close to the source. It is not true-peak or loudness mastering. Input and output trims remain manually adjustable; the peak guard can be toggled.
- The graph now shows low-cut plus EQ response. Gain reduction includes the peak guard when enabled. All new controls persist in FX copies and saved/backed-up projects.
- The supplied legacy project still renders byte-identically when Phone presets are not applied. The approved tuning algorithm is unchanged. Phone recordings are not bundled into the app or source.
- A level-matched listening comparison uses the same clean, already-tuned reference excerpt for all five segments: reference, Phone Clean, Afropop Lead, Dancehall/Rap and Soft R&B. Each segment is six seconds, separated by one second of silence.

## Fixed in 0.5.1: preserve the dry vocal

- Pitch correction now preserves the original PCM file plus the region-to-original offset. Changing tuning settings or pressing Apply again re-renders from that preserved original, rather than feeding the previous tuned result through the shifter again.
- Copy, trim and split retain the dry mapping. Both the current and preserved source are transformed during tempo edits. Undo snapshots, project JSON and portable backups retain the mapping; backups include the original audio even when no region plays it directly.
- Edit → Restore preserved dry vocal returns the selected region to its original samples. Clips already printed by older builds do not contain this association; re-import the original recording once. Updating the APK cannot recover samples that an earlier project no longer references.
- Chromatic mode now disables the key selector and explicitly explains that all twelve notes are allowed. Major/minor or Detect key from backing activates a song-specific note set.
- Reproduced the supplied project export byte-for-byte, including all 24-bit dither samples, against the supplied WAV. That establishes a reproducible session, not a subjective judgment that its audio is clean. The saved session had a previously tuned vocal, Chromatic/Hard settings, and saturation/delay/room effects on the soloed vocal channel. The available evidence does not establish how many times the clip had been processed.
- A separate recovery project was built from the matching original AAC supplied by the user, decoded at its native 44.1 kHz then linearly converted to 48 kHz using the importer's sample-position mapping. It uses A-major/Modern settings based on the supplied backing and bypasses the vocal FX for evaluation. It contains its dry source. This is a controlled trial for listening, not a guarantee that every audible artifact is fixed.
- Source/APK artifacts do not contain the user's recordings or recovery project. Preserving dry audio increases internal/backup storage use.

## Added in 0.5: tuning for different songs

- Vocal Tune now offers **Detect key from backing**. Import the instrumental onto another track, select the vocal region, open Edit → Pitch correct vocal region, tap Detect key from backing, and select the harmonic backing region. The instrumental is analysed without changing it. Detection uses that region's trimmed boundaries, not the entire original file.
- Native offline spectral-peak analysis tests all twelve major and twelve natural-minor keys. It samples up to 96 windows distributed through the backing, estimates its tuning offset, forms a pitch-class profile and ranks empirical Krumhansl major/minor profiles. No internet, server or extra runtime dependency is used.
- Clear estimates set the key/scale selectors automatically. Uncertain estimates leave the current selection alone and offer a suggestion for the user to review. Silence, noise and inadequate harmony do not silently force a key. Backings must be at least three seconds long and contain bass/chords; a longer representative section is preferable.
- Manual key/scale control remains available. Relative major/minor keys can share the same allowed notes, so their names may differ while the tuning mask is equivalent. Unusual/modal scales, ambiguous short loops and key changes still need manual checking; this estimates a region's overall key and does not follow modulations in real time.
- Key, scale, strength, retune speed and voice range now belong to each project and survive save/load, Undo/Redo and portable backups. A new or older project without stored settings starts in chromatic mode rather than inheriting a previous song's key. Song Starter supplies its chosen root/scale automatically.
- Pitch-preserving tempo edits retain the song key. Pitch-changing resampling clears the old key and returns to chromatic mode, so an outdated scale is not silently applied.
- The pitch-shifting engine remains the same general engine used for the approved A-major preview; this version does not hardcode A major or any uploaded vocal. It still processes recorded regions offline.

## Fixed in 0.4.2: supplied vocal fixture investigation

- Tested the tuner on three user-supplied approximately 33-second AAC recordings, decoded to 48 kHz stereo float PCM. Their original/corrected roles were not labeled; they were treated as supplied input fixtures, not assumed to be dry originals.
- Removed correction-dependent parallel dry/wet switching inside a voiced phrase. Phase differences between the dry waveform and resynthesised waveform caused short cancellation dips when requested correction crossed zero. Near-tuned phrases can now bypass as a whole, with the existing phrase-edge fades retained.
- Added a second pitch-analysis pass centered using the detected period, and full-band sub-sample correlation refinement of each epoch. This reduces pitch-timing error and aligns the harmonic grains more accurately.
- On these fixtures the fifth-percentile 20 ms output/input RMS ratios improved from approximately .894–.904 to .938–.944. Deep dips still occur in a few windows; this is an improvement, not proof that all audible artifacts are gone. Independent Praat analysis also showed reduced tracking disagreement, but does not establish the singer's intended melody.
- Added 18 external-fixture checks for duration, finite output, pitch coverage, peak bounds and short-window amplitude retention. The three recordings and derived audio are private external fixtures and are not bundled into the source archive or APK.
- Listening comparison: the previous and revised tuner process the same uploaded excerpt using chromatic Hard tuning at 100% strength; both sides use the same playback gain. Human listening confirmation and identification of the original dry take remain necessary.

## Fixed in 0.4.1: vocal tuning

- Rebuilt the recorded-vocal pitch correction path with centred 10 ms pitch tracking, voicing gates, median filtering, target-note hysteresis and correlation-aligned fractional pitch epochs. Epoch correlation retains fractional reference positions to avoid accumulating timing/phase error. The previous peak-selection path could produce rough, unstable output.
- Voiced regions are processed independently with smooth boundaries; consonants/noise and near-zero correction pass through dry. Stereo channels share the same marks, grains and weights.
- Vocal Tune defaults to 100% strength and Hard (0 ms). Modern (20 ms), Natural (80 ms) and Gentle (180 ms) control retune smoothing. Key and chromatic/major/natural-minor scale are retained between uses. Wide, low and high voice ranges constrain detection.
- A completion message reports the average requested correction in cents and percentage of voiced frames. These are analysis statistics, not a measured guarantee of final intonation.
- 56 additional regressions use independently measured full-rate autocorrelation on rich harmonic vocal-like fixtures across MIDI 40–79, sharp/flat tuning, vibrato, noise/consonant gaps, stereo relationships, already-tuned material, C-major scale correction, retune-speed differences and legato note transitions. Stable-note fixtures measured a worst error below 1 cent; this does not establish quality on human singing or equivalence to a commercial tuner.
- This remains offline processing of a selected recorded region. It does not add live microphone monitoring or a real-time Auto-Tune insert. Physical-phone performance and real-vocal listening tests are still required.

## Added in 0.4: make music without a controller

- Sounds starts with **Song Starter** and **Play / Touch Instrument**. The detailed synth panel also links directly to touch performance.
- Song Starter offers Afropop bounce, Dancehall pocket and Soft R&B; all twelve roots; major/natural minor; three transposed chord progressions; 40–240 BPM; 16 or 32 bars. It makes a new saved project, preserving the current one, with separate drums, bass, keys, hook melody and an empty vocal track.
- Intro, verse, hook and outro are named on the regions. The 32-bar layout includes a second verse. Sparse intro/outro drums, section fills, bass entries/exits and hook-only melody provide arrangement changes. These are deterministic original musical templates, not a generative AI service.
- Touch performance offers eight scale-safe melody pads, four one-finger chord pads, four registers and all twelve factory sounds. Multiple fingers and sliding are supported. Shared chord tones use reference counts so one finger cannot cut another finger's note short.
- Record take plays the current project underneath and captures the touch notes. Keep take prints audio onto the selected track and retains a standard MIDI note source for export. Undo removes the take. Closing the performance panel also saves an active take.
- No USB/Bluetooth MIDI device or microphone permission is needed for touch instruments. Wired headphones are recommended. Timing is played by hand: there is no count-in, quantisation or piano-roll editing in this version.

To make a song: **Sounds → Song Starter → Build my song**, play the backing, then record a vocal onto Your vocal. For melody, select or add a track, open **Sounds → Play / Touch Instrument**, choose a sound/key, press **Record take**, play the pads, then **Keep take**. For chord pads, choose Chord pads in the mode selector. Touch recording uses the existing playhead position; use Return to start before recording if needed.

## Fixed in 0.3.1

- Timeline selection, clip movement, seeking and loop changes are locked while a microphone or MIDI take is recording/finalising. Project-effect knobs also respect the recording lock.
- Playback and live MIDI retain stopping worker threads until they exit, preventing a rapid restart from reviving two workers. Audio device references are visible to the stop thread; zero-length and partial output writes are handled explicitly.
- MIDI connection/program-change callbacks respect foreground state. Unexpected live monitor shutdown clears the backing-engine monitor reference.
- An unreadable previous session now displays an error with recovery guidance while preserving its files. MIDI export detects an unavailable destination.

## Added in 0.3

- Offline factory sample browser with 29 internet-sourced CC0 sounds: five four-bar Afrobeat loop variations plus 24 percussion/drum one-shots, with source URLs, hashes, creators and licence text.
- The Freesound loops use public HQ MP3 previews, not lossless originals. The VCSL percussion uses original lossless WAV sources. All bundled files are edited/levelled 48 kHz / 16-bit stereo WAV.
- Six-lane sampled percussion sequencer with Afro bounce, Shaker pocket, Dancehall lean and empty patterns. The original three-lane synthesised drum machine remains available.
- Twelve factory synth patches: Soft keys, FM electric piano, Round bass, Afro pluck, Warm pad, Glass bell, Silk lead, Log drum, Marimba, Brass stab, Nylon pluck and Sub 808. Attack, decay, sustain, release, tone and appropriate-patch detune controls; register selector C1–C7.
- Offline vocal pitch correction: YIN analysis (65–1,000 Hz), key/scale, strength and retune smoothing, printed through pitch-synchronous overlap/add to a new clip source. Undo keeps the original.
- Actual project tempo changes: WSOLA time stretch with pitch retention, rate conversion with pitch change, or explicitly selected grid-only mode. Audio duration, source offsets, fades and region positions retime together. Shared sources are rendered once.
- Independent stereo-delay times, beat sync or milliseconds, filtered feedback, stereo width and ping-pong mode. Export/playback tail is extended as needed up to 15 seconds, within the ten-minute timeline limit.
- MIDI 1.0 controller input through class-compliant USB MIDI/OTG or already-paired Bluetooth MIDI; 16-voice factory synth monitor; note/velocity, sustain, program change and ±2-semitone pitch bend.
- MIDI take recording prints audio onto the selected track and retains a standard single-track 480-PPQ .mid source. Project backups include MIDI sources. MIDI export uses the current project tempo.

## Install and try

1. Download AudioCityStudio.apk to an Android 11+ phone and install it. If Android asks, allow your file manager to install this downloaded APK, then turn that installation permission off afterward if desired.
2. Open Audio City Studio. It creates a four-track demo named First Light. Press Play to hear drums, bass and keys. The fourth track is an empty vocal channel.
3. To create your own session, open the top-right menu and choose New empty project. Tap the project title to rename it.
4. Add tracks with Track +. Select a track in Arrange, then Import to choose device-supported mono/stereo audio through Android's file picker. Files are copied and decoded inside the project; originals are unchanged.
5. In Arrange, tap the ruler to seek. Tap a clip to select it, then use Split at the playhead. Drag a clip horizontally to move it on the sixteenth-note grid. Long-press a clip (or tap Edit in the inspector) for rename, move-to-bar, trim, duplicate, remove, clip gain and fades. Swipe empty space to scroll; use −/+ in the channel inspector to zoom, or Fit for the full session.
6. Open Mixer for volume, pan, mute and solo. Swipe horizontally to reach more channels and the master. Each track's M/S buttons are also available in Arrange. Tap the fader readout or long-press a channel for numeric gain/pan. Tap its meter to reset peak hold.
7. Select a track, then open FX. Choose a starting-point preset or bypass the channel. Toggle individual insert effects on/off. Drag knobs vertically or tap one to open a precise slider.
8. Sounds opens the sample library, sampled percussion sequencer, 12-patch synth and original drum machine. Audition sounds and insert them on the selected track. Loop tempo fitting uses resampling and explicitly changes pitch. Synth notes include their release tails; chord insertion creates a four-bar F–C–B-flat–B-flat progression.
9. To record, select a track, grant microphone permission when asked, then press the red record button. Use wired headphones. Press the red button again to stop. Recording is dry; the channel's effects apply during playback and export.
10. Use the menu to Save/Open projects, Export stereo WAV, or Back up project (.acstudio). Restore project backup creates a new internal project from a portable archive.

## Working capabilities

- Up to 8 audio tracks with multiple clips; 10-minute maximum timeline and per-clip duration.
- 48 kHz stereo float mixing, memory-mapped PCM sources, sample-accurate clip boundaries and sixteenth-note move snapping.
- Non-destructive source trim, split, move, duplicate and clip removal. Last 20 edit snapshots support Undo and Redo, including mixer and effect edits.
- Per-track gain, equal-power pan, mute/solo and peak meters. Gain changes ramp across the next audio block.
- Three-band EQ: 120 Hz low shelf, 1.2 kHz bell, 8 kHz high shelf; +/-12 dB.
- Noise gate; compressor with adjustable threshold and ratio plus measured gain-reduction display; high-frequency de-essing; soft saturation.
- Independent stereo/ping-pong delay with tempo sync or milliseconds, and stereo feedback-comb room reverb.
- Always-active -1 dBFS sample-peak protection at the stereo output. This is not a true-peak mastering limiter.
- Android audio decoding for compatible mono/stereo formats, converted to 48 kHz stereo float PCM. Output from the device decoder must be 16-bit PCM or float PCM. Format availability depends on the phone.
- Microphone recording through Android AudioRecord. Playback through AudioTrack; latency and device compatibility have not been measured on physical hardware.
- Stereo WAV export: 48 kHz / 16-bit or 24-bit PCM, triangular dither, rendered channel effects, master processing and three to fifteen seconds of effect tail within the 10-minute limit. The click is excluded from export.
- Automatic local saving after edits and when leaving the app. Project backups include JSON metadata and referenced raw PCM source files in a ZIP-based .acstudio archive.
- No internet permission, accounts, ads, cloud uploads or third-party runtime libraries. Bluetooth connection permission is requested only when opening paired Bluetooth MIDI devices.

## Limits that matter

This is an early working build, not a commercial-release product. It has not been installed, visually inspected or audio-tested on a physical Android phone. Host tests validate DSP, samples, synthesis, time stretch, correction on test tones, MIDI protocol/printing, WAV encoding and project logic. Android UI, microphone, codecs, USB/Bluetooth MIDI hardware, audio routing, controller latency and correction quality on real vocals still need hands-on validation. An emulator test was attempted for 0.4 but the device stalled before a usable Android screen appeared; this release is verified by compilation, APK checks and host regression tests only. Touch layout, multitouch input and end-to-end recording still require phone validation.

- Portrait phone layout in this build. Tablets/landscape, accessibility tuning, screens of unusual dimensions and device-specific font scaling need validation.
- No VST/AU hosting, MIDI file/piano-roll editor, automation lanes, sample-rate selection, external audio-driver control, background recording, plug-in presets or automatic input latency compensation.
- Synth/drum outputs are audio clips, not editable MIDI regions. Tempo defaults to retiming all project audio with pitch-preserving WSOLA. Resampling changes pitch; grid-only mode leaves existing audio timing untouched. Loop fitting in the sound browser is resampling, not pitch-preserving stretching.
- No live microphone monitoring. Android chooses the current input/output route; USB interfaces and Bluetooth latency are not validated. Use wired headphones for recording.
- Keep the app visible during recording. Leaving the app stops playback/recording. Unexpected process termination may lose an unfinished take.
- Internal project storage is removed if the app is uninstalled or its data is cleared. Back up important projects through the menu and export mixes outside the app.
- Longer projects consume substantial storage: internal audio is stereo 32-bit float, approximately 23 MB per minute per source. Duplicate regions share their source file. Removed sources remain internally available for undo and are omitted from portable backup when unreferenced.
- Some imported audio may be rejected if the device decoder cannot provide supported PCM. DRM-protected media is not supported. A session backup is limited to 1 GiB and 2,000 entries.
- Export is bounded to ten minutes, so effect tails at the very end of a ten-minute timeline may be truncated. Delay tails longer than the 15-second extension may also be truncated.
- Development-signed sideload APK; not Play Store reviewed or independently audited. The source archive includes its development signing key at build/development.keystore, password android, for future updates. Keep the archive private. Use a separate protected signing/release process before public commercial distribution.


## New workflow details

**Pitch correction:** for a clear tuning effect, use 100% strength and Hard, then select the correct key/scale (or Chromatic if the key is unknown). Choose the matching voice range; use dry isolated vocals. Select a vocal region in Tracks, open Edit → Pitch correct vocal region (or the correction button in Effects), choose key/scale, strength, retune speed and voice range, then process. This is offline monophonic correction, not real-time Auto-Tune. It is unsuitable for mixed songs, chords or heavily layered vocals. Detection errors and artifacts are possible; review the result and use Undo if needed. A4 is fixed at 440 Hz.

**Project tempo:** tap BPM, enter the new tempo, and choose time stretch, resampling or grid only. Pitch-preserving stretching supports 0.5×–2× speed per operation; larger moves need an intermediate tempo or resampling. Full source audio is transformed, so long source files may reach the ten-minute limit even if a short region is used. Large changes can sound smeared or transient-heavy. The original source files remain for Undo.

**Stereo delay:** in Effects, enable Stereo Delay. Set tempo sync on for independent left/right beat values, or off for milliseconds. Adjust feedback, width, wet mix and ping-pong. Feedback is bounded below unity and low-pass filtered. The stereo output remains sample-peak protected.

**MIDI controller:** connect a class-compliant USB keyboard with an OTG adapter (some controllers require a powered hub), then Sounds → MIDI controller input → Choose connected MIDI device. Select the controller's note-output port. Bluetooth support opens already-bonded devices after Android grants Bluetooth-connect permission. BLE controllers that require in-app scanning and cannot be paired in Android Settings are not supported by this paired-device-only picker.

Choose a factory synth in the synth panel. For a take, select a track and choose Record MIDI to track in the MIDI dialog. Play the keyboard, then press the red Record or Stop key. The performance is printed as audio; its note source remains as a .mid file. Use the project menu's Export MIDI performance action to save that file separately. There is no piano-roll editor, MIDI-file import, MIDI 2.0/UMP or MPE support, external clock sync, or control-surface transport mapping in this build. Live monitoring is dry; printed audio uses channel effects on playback. Program changes select one of the 12 factory sounds modulo 12.

**Samples:** Sounds → Afrobeat Sample Library. Audition, view the source/creator/licence, and insert on the selected track. "Fit to project" resamples the loop and changes pitch; it does not apply WSOLA. The library and all synths work offline after installation. Complete CC0 text and provenance are in assets/licenses and assets/samples/manifest.json. Source assets can be rebuilt with tools/build_sample_bank.py (Python and ffmpeg; downloads require internet).

## Build in the cloud with GitHub (no SDK on your computer)

1. Create a **private** GitHub repository and push this folder to it. `.gitignore` keeps the signing key and build output out of git.
2. Add your signing key as a secret so every build can update the app already installed on your phone:
   - On a Mac or Linux machine: `base64 -i build/development.keystore | pbcopy` (Mac) or `base64 -w0 build/development.keystore` (Linux).
   - On GitHub: repository → Settings → Secrets and variables → Actions → New repository secret, named `KEYSTORE_BASE64`, with that text as the value.
3. Every push, or Actions → Build APK → Run workflow, then:
   - installs Android platform 35, build tools 35.0.0 and NDK r26
   - runs all host test suites (`run-tests.sh`)
   - builds and signs the APK with native AAudio
4. Download the APK from the run's **Artifacts** section (AudioCityStudio-apk), unzip it on your phone and install it. Allow installs from unknown sources when Android asks.

Without the secret, the build still works, but it uses a new key each time, so Android will ask you to uninstall the old copy first.

To run the tests locally: `ANDROID_SDK_ROOT=… ./run-tests.sh`.

## Build

Install Android SDK Platform 35, Android Build Tools 35.0.0, and a JDK. Set ANDROID_SDK_ROOT, then run ./build.sh. If javac is unavailable, set ECJ_JAR to Eclipse's ecj compiler JAR. The APK contains only the app classes and Android resources; the compiler is not bundled.

The build script retains build/development.keystore if present, so future APKs can update an installed copy. Package name: ug.audiocity.studio.

## Validation

946 host checks (including private supplied-recording fixtures; 172 new in 0.7, 65 new in 0.8, 22 new in 0.9, 43 new in 0.10, 27 new in 0.11, 129 new in 0.12):

- 18 LoopTests: tempo read from names, tags and descriptions, bar counts from durations, half-time correction, CC0-only filtering, the search address, parsing a Freesound response, credits, plain-language errors, the offline library, MP3 checks, and trimming decoder padding to whole bars. The live Freesound connection itself is not tested here (no internet in the build environment).

- 82 EffectsTests: every new effect registered, stable with defaults and with every control at its minimum and maximum on loud noise; tremolo depth, auto-pan, low- and high-pass filtering, added harmonics from drive and exciter, crusher bit depth, gate open and closed, de-esser cutting sibilance but not the body, doubler width, and pitch shifts of +12 and −7 semitones measured.

- 29 VocalEditTests: note detection and centres on a synthetic voice, untouched pass-through, a dragged note landing on its new pitch while neighbours stay, vibrato flattening, per-note gain, formant shift keeping pitch, scale snap, split and join, timing edits inside a legato phrase and their limits, and saving, loading and copying the edits.

- 27 SnapTests: smart grid by zoom, bar and division grids, relative snapping, magnetic locks to clip starts and ends, markers, the playhead and loop edges, free placement, magnet off, edge trims and their limits, zero crossings, the four drag modes, the older code paths with Smart and Bar, and saved and copied settings.

- 43 LiveTests: onset detection within 3 ms, audio quantize to the grid at full and half strength, silence left alone, controller mapping ranges and toggles, logarithmic frequency control, transport actions, bar-quantised launches that loop and replace the arrangement, stop and resume, scenes, undo copies, saved slots and mappings, and invalid mappings rejected.

- 22 ModernDawTests: live MIDI rendering, copy independence, re-render on note and instrument edits, retiming, recording-to-notes, save/load with identical render, invalid-note rejection, stretch-original persistence and backups, pitch-preserving stretch length and pitch, latency alignment for track and bus inserts, export compensation, and the new limits.

- 64 StudioToolsTests: compressor curve and measured reduction, knee, mix, sidechain low cut and makeup; reverb decay, width and dry path; delay timing, ping-pong and tempo follow; tape low-level unity and harmonics; transient attack/sustain; range copy, ripple delete (clips, automation, markers), paste, insert time and split; nudge, duplicate, normalise, reverse and strip silence; bounce equals the stem render; spectrum and correlation; reference A/B and loop-range playhead; resampler level, purity and alias rejection; 44.1 kHz WAV/FLAC decoded by ffmpeg; no-dither silence; insert defaults stay finite.
- 1 more ProjectFeatureTests check: loop range, notes and reference saved and restored, and the reference is included in backups.

- 128 ProFeatureTests: BS.1770 loudness (integrated/short-term/momentary within 0.1 LU on reference tones), gating and loudness range, true peak on an fs/4 sine, limiter ceiling and exact look-ahead latency, EQ response measured against the formula, multiband transparency at unity, automation interpolation/thinning/rendering, buses and pre/post sends, sidechain ducking, equal-power crossfades, comping, slip/ripple, tempo map and metre accents, FFT latency estimation, monitoring ring buffer, note editing and MIDI file round trip, FLAC bit-exactness decoded by ffmpeg, offline latency removal, stems and insert ordering.
- 44 ProjectFeatureTests: save/load of every new feature with identical re-render, neutral defaults for 0.6 files, rejection/clamping of invalid or unsafe values, take path traversal, templates with re-mapped ids, channel presets, tempo-conversion scaling, backup file lists, byte-identical WAV export against the 0.6 exporter, FLAC equal to WAV sample-for-sample, stem ZIPs and loudness normalisation.

For 0.7 the suites that need the private recordings (KeyTests, PhonePresetTests, RealVocalTests) were not re-run. All other earlier suites were re-run and pass unchanged.

Earlier checks:

- 18 DSP / render checks: silence, clip position/trim, pan, mute/solo, split equivalence, EQ response, compression, gate, de-essing, delay timing, room tail, saturation, ceiling/finite samples, undo isolation, disk PCM round-trip, WAV header and data length.
- 17 project/backup checks: metadata, mixer/FX and edits round-trip, shared source mapping, sample-identical restored rendering, portable backup with source audio, non-overwriting restores, path restrictions, invalid/missing manifests, size bounds and partial-file cleanup.

- 24 additional workflow/audio checks: fader mapping and range, free/quantised placement, clip gain, fade edges, fade-preserving split, effect bypass, live gain reduction, stereo meters, peak hold and overload reset, EQ response formula, independent presets, 24-bit WAV header/data/samples, new metadata persistence, old-session compatibility and envelope validation.

- 160 sample/synth/sequencer checks validate all 29 WAV assets, frame counts, non-silence/headroom, source/licence metadata, SHA-256 digests, BPM-aligned loop lengths, all twelve synth voices, envelopes, tone, detune and sampled-step placement.
- 31 production checks validate WSOLA/rate-conversion duration and pitch, project retiming/undo sources, pitch correction on a detuned tone, silence rejection, scale mapping, separate stereo taps and ping-pong feedback, MIDI running status/fragments/SysEx, polyphony, sustain/all-notes-off, standard MIDI parsing, take printing, source associations, MIDI backup/restore and printed correction.

- 64 controller-free composition checks validate all roots/scales and progression chord tones, chord labels, exact bar lengths and clean boundaries, all three arrangement styles, 16/32-bar section placement, shared source storage, generated-project reload/rendering, device-free touch-take printing/MIDI export, and overlapping-finger note release/slide/cancel behaviour.

- 56 vocal-tuning regressions are described above. Synthetic voice fixtures cannot replace listening to dry recordings of real singers.

- 18 optional RealVocalTests checks use externally supplied 48 kHz stereo float PCM paths; the fixtures are not included. Run RealVocalTests with those local paths to reproduce the amplitude-retention checks.

- 36 key/per-project checks cover transpositions across all 24 major/natural-minor note sets; silence, short material, noise and a single held note; snapshot isolation; new-project defaults; saved controls and portable backup; old-project compatibility; key handling during tempo conversion; and agreement with independent key analysis on the privately supplied backing. The backing is not bundled.

- 16 non-stacking/source-provenance checks cover one-pass equivalence after changing settings, repeated-Apply identity, trims and copied/split regions, exact dry restoration, save/load, portable backup, tempo edits with/without pitch preservation, and rejection of unsafe original-source paths.

- 48 phone-preset checks cover low-cut/mid response, profile copy/persistence, continuous-voice calibration, and all four presets on the three supplied takes. They validate finite output, sample headroom and level consistency; the measured source-relative RMS differences on these fixtures were under .1 dB. The private takes are not included.

Project tests use a host-only AtomicFile adapter plus JSON-java. These fixtures and dependencies are not included in the APK and do not substitute for testing Android's actual file APIs.

An eight-track run with all effects processed four seconds of audio in approximately 0.7 seconds on the build host after warm-up; this does not predict phone performance. ffprobe separately verified the exported fixtures as 48 kHz, two-channel, 16-bit and 24-bit little-endian PCM with the expected duration. The APK signature and launch manifest were also checked.

New in 0.7: tests/stubs/org/json is a minimal host-only org.json so the project tests run without a JSON jar. ProFeatureTests and ProjectFeatureTests need Core, Pro, ProjectIO, Export, Synth, SynthPoly, MidiTake and TimeStretch plus that stub and tests/stubs/android/util/AtomicFile.java. The FLAC and export checks call ffmpeg. Test commands use ECJ_JAR, ANDROID_SDK_ROOT, and JSON_TEST_JAR. Compile Core, AudioIO, Synth, SynthPoly, SampleAudio, TimeStretch, PitchCorrector, MidiParser and MidiTake with the Android platform jar, the tests, and tests/stubs/android/util/AtomicFile.java. Include PocketComposer, TouchNotes, KeyDetector and PhoneVocalPresets in that compilation. Run StudioTests, ProjectTests, ProTests, SoundTests (argument: assets), ProductionTests, ComposerTests, VocalTests KeyTests (optionally pass an external 48 kHz stereo float backing fixture), DrySourceTests, and PhonePresetTests (optional external vocal PCM paths) on the host classpath. The Android activity and hardware MIDI controllers are compiled into the APK but are not exercised by host tests.

The tempo fixture measured approximately 440.15 Hz after WSOLA versus 549.74 Hz after 1.25× resampling of a 440 Hz tone. The correction fixture moved a roughly 445.94 Hz test tone toward 439.74 Hz by the coarse zero-crossing estimator (the independent harmonic-vocal estimator measured stable-note errors below 1 cent) without changing length. These synthetic-tone checks do not establish vocal quality or hardware performance.


## 0.12.1 — Rebuilt Play It. Keep The Take. (source patch)

This section supersedes the older *touch-performance* description in the 0.12 README. The performance surface now uses a full-height dialog with permanently visible chord pads and a separate settings dialog. Pad mode defaults to four chords, with optional 6/8 slots, 14 progression presets in each major/minor mode, and a separate optional melody mode. Opening and closing the surface no longer explicitly pauses playback. Project Play/Pause/Stop, Loop, Start Recording and Stop Recording are separate controls. Count-in is a small beat readout above the pads.

Playback and MIDI recording now share the live transport timeline. Recording no longer forcibly pauses or disables looping; touch notes during count-in are ignored and note timestamps use the monotonically advancing audio buffer clock. The metronome is continuous when enabled in the current mode and can be changed without stopping the beat. A user-adjustable click level is monitored, not exported.

Limitations: Android UI, real audio routing and round-trip latency have **not yet been tested on a physical device**. A full APK needs Android SDK. The metronome and click-level path is implemented in Java AudioTrack and native output via the shared Core.Renderer, but jitter/latency requires device validation. The factory instruments remain synthesizers; no new internet sample packs are bundled. A take-quantization control is not included in this patch because MIDI timing quantization is not yet implemented.


## Playable sampled instruments (0.12.2)

Nine CC0 sampled percussion instruments are playable in **Play It. Keep the Take.** An offline WAV / basic SFZ ZIP importer now supports additional legally obtained sampled instrument packs and preserves sampled sound in printed MIDI takes. See [REAL-INSTRUMENTS-0.12.2.md](REAL-INSTRUMENTS-0.12.2.md) for supported formats, licensing and limits. Large piano/string libraries are not bundled.


## Audio City Studio 0.12.4 — bundled FreePats electric piano

This Android build includes the full CC0 FreePats **FM Electric Piano 2** pack
(12 original WAV samples + SFZ mapping + README + CC0 license) inside the APK.
The pack installs automatically on first app launch without an internet connection.
To play it, open **Sounds → Play / Touch Instrument → instrument picker** and select
**BUILT-IN · FM Electric Piano 2 · FreePats (CC0)**. It works with the existing
multitouch chord pads and MIDI take recording. Users can still import their own
WAV or SFZ ZIP instruments.

The pack is an electric piano from a software DX7-style synth, not an acoustic grand.
See `FREEPATS_FM_PIANO_IMPORT.md` and `assets/licenses/SAMPLE-CREDITS.md`.

**Build status:** requires Android SDK/NDK and a device test to produce and validate an APK.


## 0.12.5 Factory piano collection

See `PIANO-INSTRUMENTS-0.12.5.md` for the 4 additional real sampled pianos, install behavior, performance-memory notes, and Creative Commons attributions.
