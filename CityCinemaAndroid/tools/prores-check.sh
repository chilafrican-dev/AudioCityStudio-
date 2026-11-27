#!/usr/bin/env bash
# End-to-end ProRes check on the host: builds the native encoder for this machine, encodes test frames into a .mov
# (tests/ProResHostTest) and verifies it with FFmpeg (tools/verify_prores.py: decode, PSNR, audio, timecode).
# Needs: run-tests.sh to have been run (compiled classes), gcc, a JDK, FFmpeg and Python with numpy.
# Usage: tools/prores-check.sh <ffmpeg> <python> [width height frames]
set -euo pipefail
cd "$(dirname "$0")/.."
ffmpeg="$1"; python="$2"; w="${3:-1920}"; h="${4:-1080}"; n="${5:-12}"
: "${ANDROID_JAR:=${ANDROID_SDK_ROOT:?Set ANDROID_SDK_ROOT or ANDROID_JAR}/platforms/android-35/android.jar}"
jdk="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"
mkdir -p build/host build/prores-check
gcc -O3 -shared -fPIC -I"$jdk/include" -I"$jdk/include/linux" jni/prores.c jni/prores_jni.c -o build/host/libcitycinema.so -lm -lpthread
rm -f build/prores-check/*
java -Djava.library.path=build/host -cp "build/test/tests:build/test/classes:$ANDROID_JAR" ug.audiocity.cinema.ProResHostTest build/prores-check "$w" "$h" "$n"
"$python" -I tools/verify_prores.py build/prores-check "$ffmpeg" "$w" "$h" "$n"
