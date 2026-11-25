#!/usr/bin/env bash
# Desktop tests for lyric timing, LRC, word alignment and audio conversion (plain Java, no Android SDK or phone needed).
set -euo pipefail
cd "$(dirname "$0")"
out=build/test; rm -rf "$out"; mkdir -p "$out"
javac -nowarn --release 8 -d "$out" src/ug/audiocity/lyrics/Lyrics.java src/ug/audiocity/lyrics/Style.java src/ug/audiocity/lyrics/Ctc.java src/ug/audiocity/lyrics/Audio16k.java src/ug/audiocity/lyrics/AutoTiming.java src/ug/audiocity/lyrics/WordTapper.java src/ug/audiocity/lyrics/ProjectBackup.java src/ug/audiocity/lyrics/Sequence.java tests/LyricsTests.java tests/AlignTests.java tests/BackupTests.java tests/SequenceTests.java
java -ea -cp "$out" ug.audiocity.lyrics.LyricsTests
java -ea -cp "$out" ug.audiocity.lyrics.AlignTests
java -ea -cp "$out" ug.audiocity.lyrics.BackupTests
java -ea -cp "$out" ug.audiocity.lyrics.SequenceTests
