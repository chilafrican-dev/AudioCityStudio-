#!/usr/bin/env bash
# Builds and signs City Cinema (ug.audiocity.cinema) with only the Android SDK build tools: no Gradle needed.
set -euo pipefail
cd "$(dirname "$0")"
: "${ANDROID_SDK_ROOT:?Set ANDROID_SDK_ROOT to your Android SDK directory}"
tools="$ANDROID_SDK_ROOT/build-tools/35.0.0"
platform="$ANDROID_SDK_ROOT/platforms/android-35/android.jar"
rm -rf build/classes build/dex
mkdir -p build/classes build/dex
"$tools/aapt2" compile --dir res -o build/resources.zip
"$tools/aapt2" link -o build/base.apk -I "$platform" --manifest AndroidManifest.xml build/resources.zip
javac -nowarn --release 8 -classpath "$platform" -d build/classes src/ug/audiocity/cinema/*.java
rm -f build/classes.jar
(cd build/classes && zip -qr ../classes.jar .)
"$tools/d8" --lib "$platform" --min-api 30 --output build/dex build/classes.jar
cp build/base.apk build/unsigned.apk
(cd build/dex && zip -q ../unsigned.apk classes*.dex)
# Native ProRes encoder. Without ANDROID_NDK_ROOT the app builds fine but offers no ProRes recording.
rm -rf build/lib
if [ -n "${ANDROID_NDK_ROOT:-}" ]; then
host="$(uname -s | tr '[:upper:]' '[:lower:]')-x86_64"
llvm="$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/$host/bin"
[ -d "$llvm" ] || { echo "NDK toolchain not found at $llvm" >&2; exit 1; }
for pair in arm64-v8a:aarch64-linux-android30 armeabi-v7a:armv7a-linux-androideabi30 x86_64:x86_64-linux-android30; do
abi="${pair%%:*}"; target="${pair#*:}"
mkdir -p "build/lib/$abi"
"$llvm/clang" --target="$target" -std=c11 -O3 -fno-math-errno -fPIC -shared -Wall -Wl,-z,max-page-size=16384 -o "build/lib/$abi/libcitycinema.so" jni/prores.c jni/prores_jni.c -lm -llog
done
(cd build && zip -qr unsigned.apk lib)
echo "Included the native ProRes encoder for arm64-v8a, armeabi-v7a and x86_64."
else
echo "ANDROID_NDK_ROOT not set: building without the ProRes encoder."
fi
"$tools/zipalign" -f -p 4 build/unsigned.apk build/aligned.apk
if [ ! -f build/development.keystore ]; then
keytool -genkeypair -keystore build/development.keystore -storepass android -keypass android -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=City Cinema Development' >/dev/null 2>&1
fi
"$tools/apksigner" sign --ks build/development.keystore --ks-pass pass:android --key-pass pass:android --out CityCinema.apk build/aligned.apk
"$tools/apksigner" verify --verbose CityCinema.apk
echo "Built CityCinema.apk"
