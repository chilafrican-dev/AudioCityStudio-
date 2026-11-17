#!/usr/bin/env bash
# Run the Android-independent Audio City Studio engine regressions without an Android SDK.
# Requires JDK 17+ and bundled test stubs. Does not test Android UI, microphone, or native AAudio.
set -euo pipefail
cd "$(dirname "$0")"
out=build/core-tests
rm -rf "$out"
mkdir -p "$out"
sources=()
while IFS= read -r f; do
  case "$f" in */PocketComposer.java|*/KeyDetector.java|*/BeatPilot.java|*/PatternArrangement.java|*/PatternDraft.java) continue ;; esac
  if grep -qE '^import (android|androidx)\.' "$f"; then continue; fi
  sources+=("$f")
done < <(find src -name "*.java" -print | sort)
javac -Xlint:-options --release 8 -d "$out" tests/stubs/org/json/*.java tests/stubs/android/util/AtomicFile.java "${sources[@]}"
javac -cp "$out" -d "$out" \
  tests/ArrangeModelTests.java tests/SoundTests.java tests/EffectsTests.java tests/LoopTests.java \
  tests/SnapTests.java tests/LiveTests.java tests/ModernDawTests.java \
  tests/VocalTests.java tests/VocalEditTests.java tests/ProFeatureTests.java \
  tests/StockSoundCatalogTests.java tests/LoopTempoTests.java tests/BleedRemoverTests.java tests/SpeakerChoiceTests.java tests/PhraseBankTests.java tests/AutoPartsTests.java tests/StudioToolsTests.java tests/TouchPerformanceTests.java tests/SampledInstrumentTests.java tests/PianoLibraryTests.java tests/GuitarLibraryTests.java tests/BeatWorkspaceTests.java tests/WorkflowRegressionTests.java tests/MidiImportRegressionTests.java tests/PortableInstrumentTests.java tests/SamplePlaybackRegressionTests.java tests/RecordedClipBoundsTests.java tests/PatternBankTests.java tests/AiMidiGeneratorTests.java tests/AiDrumTracksTests.java
for t in AutoPartsTests PhraseBankTests SpeakerChoiceTests BleedRemoverTests LoopTempoTests StockSoundCatalogTests ArrangeModelTests SoundTests EffectsTests LoopTests SnapTests LiveTests ModernDawTests VocalTests VocalEditTests ProFeatureTests StudioToolsTests TouchPerformanceTests SampledInstrumentTests PianoLibraryTests GuitarLibraryTests BeatWorkspaceTests WorkflowRegressionTests MidiImportRegressionTests PortableInstrumentTests SamplePlaybackRegressionTests RecordedClipBoundsTests PatternBankTests AiMidiGeneratorTests AiDrumTracksTests; do
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
