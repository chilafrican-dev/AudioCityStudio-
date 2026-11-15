#!/usr/bin/env bash
# Host test suites (no phone needed). Uses the Android platform jar for compiling AudioIO and the
# host-only stubs in tests/stubs (org.json, AtomicFile), which must come first on the classpath.
set -euo pipefail
cd "$(dirname "$0")"
: "${ANDROID_JAR:=${ANDROID_SDK_ROOT:?Set ANDROID_SDK_ROOT or ANDROID_JAR}/platforms/android-35/android.jar}"
out=build/test; rm -rf "$out"; mkdir -p "$out/stubs" "$out/classes" "$out/tests" "$out/run"
javac -nowarn -d "$out/stubs" tests/stubs/org/json/*.java tests/stubs/android/util/AtomicFile.java
javac -nowarn --release 8 -cp "$out/stubs:$ANDROID_JAR" -d "$out/classes" src/ug/audiocity/studio/*.java
javac -nowarn -cp "$out/stubs:$out/classes:$ANDROID_JAR" -d "$out/tests" tests/*.java
cp="$out/stubs:$out/tests:$out/classes:$ANDROID_JAR"
run(){ echo "== $1"; java -cp "$cp" "ug.audiocity.studio.$1" "${@:2}" | tail -n 1; }
run SoundTests assets
run StudioTests "$out/run/mix.wav"
run ProTests "$out/run/mix24.wav"
for t in ProjectTests ProductionTests ComposerTests DrySourceTests VocalTests ProFeatureTests ProjectFeatureTests StudioToolsTests ModernDawTests LiveTests SnapTests; do run "$t"; done
run BeatPilotGrooveTests assets
run VocalEditTests
run EffectsTests
run LoopTests
run TouchPerformanceTests
echo "All host suites passed. KeyTests, PhonePresetTests and RealVocalTests need private recordings and are not run here."
