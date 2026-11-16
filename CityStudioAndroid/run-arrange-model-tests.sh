#!/usr/bin/env bash
# Stage-one musical model only. No Android SDK, factory samples or device required.
set -euo pipefail
cd "$(dirname "$0")"
out=build/arrange-model-tests
rm -rf "$out"
mkdir -p "$out"
mapfile -t sources < <(find src/ug/audiocity/studio/arrange/model -name '*.java' -print | sort)
sources+=(tests/ArrangeModelTests.java)
if command -v javac >/dev/null; then
  javac --release 8 -d "$out" "${sources[@]}"
else
  : "${ECJ_JAR:?Install a JDK or set ECJ_JAR to the Eclipse compiler jar}"
  java -jar "$ECJ_JAR" -source 1.8 -target 1.8 -d "$out" "${sources[@]}"
fi
java -cp "$out" ug.audiocity.studio.ArrangeModelTests
