#!/usr/bin/env bash
# Run the Android-independent Audio City Studio engine regressions without an Android SDK.
# Requires JDK 17+ and bundled test stubs. Does not test Android UI, microphone, or native AAudio.
set -euo pipefail
cd "$(dirname "$0")"
out=build/core-tests
rm -rf "$out"
mkdir -p "$out"
sources=()
for f in src/ug/audiocity/studio/*.java; do
  case "$f" in */PocketComposer.java|*/KeyDetector.java|*/BeatPilot.java) continue ;; esac
  if grep -qE '^import (android|androidx)\.' "$f"; then continue; fi
  sources+=("$f")
done
javac -Xlint:-options --release 8 -d "$out" tests/stubs/org/json/*.java tests/stubs/android/util/AtomicFile.java "${sources[@]}"
javac -cp "$out" -d "$out" \
  tests/SoundTests.java tests/EffectsTests.java tests/LoopTests.java \
  tests/SnapTests.java tests/LiveTests.java tests/ModernDawTests.java \
  tests/VocalTests.java tests/VocalEditTests.java tests/ProFeatureTests.java \
  tests/StudioToolsTests.java tests/TouchPerformanceTests.java tests/SampledInstrumentTests.java tests/PianoLibraryTests.java tests/GuitarLibraryTests.java
for t in SoundTests EffectsTests LoopTests SnapTests LiveTests ModernDawTests VocalTests VocalEditTests ProFeatureTests StudioToolsTests TouchPerformanceTests SampledInstrumentTests PianoLibraryTests GuitarLibraryTests; do
  echo "== $t =="
  java -Xmx1024m -cp "$out" "ug.audiocity.studio.$t" assets | tail -n 1
done
echo 'All host core checks passed (including touch performance tests).' 

# BeatPilot live sequencer persistence and MIDI file checks
javac -cp "$out" -d "$out" tests/BeatPilotLiveMidiTests.java
java -cp "$out" ug.audiocity.studio.BeatPilotLiveMidiTests

# BeatPilot multibar step/velocity MIDI persistence and sample render tests
javac -cp "$out" -d "$out" tests/BeatPilotMultiBarTests.java
java -cp "$out" ug.audiocity.studio.BeatPilotMultiBarTests
