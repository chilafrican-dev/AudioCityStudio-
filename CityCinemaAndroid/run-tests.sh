#!/usr/bin/env bash
# Host tests (no phone needed): camera math, timecode, white balance, looks, bitrate and WAV format.
# Needs an android.jar to compile against: ANDROID_JAR, or ANDROID_SDK_ROOT with platform 35 installed.
set -euo pipefail
cd "$(dirname "$0")"
: "${ANDROID_JAR:=${ANDROID_SDK_ROOT:?Set ANDROID_SDK_ROOT or ANDROID_JAR}/platforms/android-35/android.jar}"
out=build/test; rm -rf "$out"; mkdir -p "$out/classes" "$out/tests"
javac -nowarn --release 8 -cp "$ANDROID_JAR" -d "$out/classes" src/ug/audiocity/cinema/*.java
javac -nowarn -cp "$out/classes:$ANDROID_JAR" -d "$out/tests" tests/*.java
java -cp "$out/tests:$out/classes:$ANDROID_JAR" ug.audiocity.cinema.CinemaTests
