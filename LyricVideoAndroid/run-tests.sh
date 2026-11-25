#!/usr/bin/env bash
# Desktop tests for the lyric timing and LRC logic (plain Java, no Android SDK or phone needed).
set -euo pipefail
cd "$(dirname "$0")"
out=build/test; rm -rf "$out"; mkdir -p "$out"
javac -nowarn --release 8 -d "$out" src/ug/audiocity/lyrics/Lyrics.java src/ug/audiocity/lyrics/Style.java tests/LyricsTests.java
java -ea -cp "$out" ug.audiocity.lyrics.LyricsTests
