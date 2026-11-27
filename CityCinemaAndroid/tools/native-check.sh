#!/usr/bin/env bash
# Native code checks on the host:
#  - ProRes: encodes test frames into a .mov (tests/ProResHostTest) and verifies it with FFmpeg (tools/verify_prores.py)
#  - RAW: lossless JPEG CinemaDNG, remosaic and monitor (tests/RawHostTest), decoded by LibRaw and by an independent
#    T.81 decoder (tools/verify_dng.py); City Log LUT applied by FFmpeg
#  - NEON: ARM builds must be bit-identical to the reference (under qemu-aarch64, when installed)
# Needs: run-tests.sh to have been run (compiled classes), gcc, a JDK, FFmpeg and Python with numpy.
# Usage: tools/native-check.sh <ffmpeg> <python-with-numpy-and-rawpy> [width height frames]
set -euo pipefail
cd "$(dirname "$0")/.."
ffmpeg="$1"; python="$2"; w="${3:-1920}"; h="${4:-1080}"; n="${5:-12}"
: "${ANDROID_JAR:=${ANDROID_SDK_ROOT:?Set ANDROID_SDK_ROOT or ANDROID_JAR}/platforms/android-35/android.jar}"
jdk="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"
mkdir -p build/host build/prores-check
gcc -O3 -shared -fPIC -I"$jdk/include" -I"$jdk/include/linux" jni/prores.c jni/prores_jni.c jni/rawtools.c jni/rawtools_jni.c -o build/host/libcitycinema.so -lm -lpthread
rm -f build/prores-check/*
java -Djava.library.path=build/host -cp "build/test/tests:build/test/classes:$ANDROID_JAR" ug.audiocity.cinema.ProResHostTest build/prores-check "$w" "$h" "$n"
"$python" -I tools/verify_prores.py build/prores-check "$ffmpeg" "$w" "$h" "$n"

# RAW: uncompressed + lossless JPEG DNGs, checked by LibRaw and the spec decoder; the City Log LUT through FFmpeg
mkdir -p build/raw-check && rm -f build/raw-check/*
java -cp "build/test/tests:build/test/classes:$ANDROID_JAR" ug.audiocity.cinema.CinemaTests build/raw-check | tail -n 1
java -Djava.library.path=build/host -cp "build/test/tests:build/test/classes:$ANDROID_JAR" ug.audiocity.cinema.RawHostTest build/raw-check
"$python" -I tools/verify_dng.py build/raw-check
"$python" -I tools/verify_lut.py "$ffmpeg" build/raw-check

# NEON check: the ARM NEON build must produce byte-identical frames to the scalar build (same fingerprints).
# Runs when an ARM64 compiler and qemu-aarch64 are available. Uses the NDK's clang (the compiler that builds the
# APK) when ANDROID_NDK_ROOT is set, plus the system aarch64 GCC if installed.
if command -v qemu-aarch64 >/dev/null; then
  gcc -O3 tests/prores_selftest.c jni/prores.c -o build/host/selftest -lm -lpthread
  gcc -O3 tests/rawtools_selftest.c jni/rawtools.c -o build/host/rawtest -lm
  arm=()
  if [ -n "${ANDROID_NDK_ROOT:-}" ]; then
    ndkcc="$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin/clang"
    "$ndkcc" --target=aarch64-linux-android30 -O3 -fno-math-errno -static tests/prores_selftest.c jni/prores.c -o build/host/selftest-ndk-neon -lm && arm+=(ndk-neon)
    "$ndkcc" --target=aarch64-linux-android30 -O3 -static tests/rawtools_selftest.c jni/rawtools.c -o build/host/rawtest-ndk-neon -lm
  fi
  if command -v aarch64-linux-gnu-gcc >/dev/null; then
    aarch64-linux-gnu-gcc -O3 -static tests/prores_selftest.c jni/prores.c -o build/host/selftest-gcc-neon -lm -lpthread && arm+=(gcc-neon)
    aarch64-linux-gnu-gcc -O3 -static -DPRORES_NO_NEON tests/prores_selftest.c jni/prores.c -o build/host/selftest-gcc-scalar -lm -lpthread && arm+=(gcc-scalar)
    aarch64-linux-gnu-gcc -O3 -static tests/rawtools_selftest.c jni/rawtools.c -o build/host/rawtest-gcc-neon -lm
  fi
  for args in "1920 1080 4 3 hard" "1000 720 3 0 camera" "3840 2160 2 2 camera"; do
    build/host/selftest $args | grep fingerprint > build/host/ref.txt
    for b in "${arm[@]}"; do
      qemu-aarch64 "build/host/selftest-$b" $args | grep fingerprint > build/host/arm.txt
      diff -q build/host/ref.txt build/host/arm.txt >/dev/null || { echo "ARM $b output differs from the reference ($args)"; diff build/host/ref.txt build/host/arm.txt | head; exit 1; }
    done
    echo "ARM builds (${arm[*]}) are bit-identical to the reference encoder: $args"
  done
  for args in "4000 3000 1023" "1024 768 16383"; do
    ref=$(build/host/rawtest $args | grep -o 'fingerprint [0-9a-f]*')
    for b in build/host/rawtest-*-neon; do
      [ -x "$b" ] || continue
      got=$(qemu-aarch64 "$b" $args | grep -o 'fingerprint [0-9a-f]*')
      [ "$got" = "$ref" ] || { echo "ARM $(basename "$b") lossless JPEG differs ($args): $got vs $ref"; exit 1; }
    done
    echo "ARM NEON lossless JPEG is bit-identical to the reference: $args"
  done
else
  echo "qemu-aarch64 not installed: skipping the NEON bit-exactness check."
fi
