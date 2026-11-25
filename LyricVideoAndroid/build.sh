#!/usr/bin/env bash
# Builds and signs PearlLyricsVideoMaker.apk with the plain Android SDK tools (no Gradle).
set -euo pipefail
cd "$(dirname "$0")"
: "${ANDROID_SDK_ROOT:?Set ANDROID_SDK_ROOT to your Android SDK directory}"
tools="$ANDROID_SDK_ROOT/build-tools/35.0.0"
platform="$ANDROID_SDK_ROOT/platforms/android-35/android.jar"
rm -rf build/classes build/dex build/lib
mkdir -p build/classes build/dex build/deps
# ONNX Runtime for the on-phone Luganda speech model (MIT licence), from Maven Central, checked by SHA-256.
ort=build/deps/onnxruntime-android-1.19.2.aar
ort_sha=84737dd1fdb715f0ee695c0a9864f6949b66ecb4c46ca733bc9211bf0c2a7b5f
if [ ! -f "$ort" ]; then
  curl -sSfL --retry 4 -o "$ort.part" https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/1.19.2/onnxruntime-android-1.19.2.aar
  mv "$ort.part" "$ort"
fi
echo "$ort_sha  $ort" | sha256sum -c --quiet
rm -rf build/deps/ort && mkdir -p build/deps/ort && (cd build/deps/ort && unzip -q ../onnxruntime-android-1.19.2.aar classes.jar 'jni/arm64-v8a/*' 'jni/armeabi-v7a/*')
for abi in arm64-v8a armeabi-v7a; do mkdir -p "build/lib/$abi" && cp build/deps/ort/jni/$abi/*.so "build/lib/$abi/"; done
"$tools/aapt2" compile --dir res -o build/resources.zip
"$tools/aapt2" link -o build/base.apk -I "$platform" --manifest AndroidManifest.xml -A assets build/resources.zip
javac -nowarn --release 8 -classpath "$platform:build/deps/ort/classes.jar" -d build/classes src/ug/audiocity/lyrics/*.java
rm -f build/classes.jar
(cd build/classes && zip -qr ../classes.jar .)
"$tools/d8" --lib "$platform" --min-api 29 --release --output build/dex build/classes.jar build/deps/ort/classes.jar
cp build/base.apk build/unsigned.apk
(cd build/dex && zip -q ../unsigned.apk classes*.dex)
(cd build && zip -qr unsigned.apk lib)
"$tools/zipalign" -f -p 4 build/unsigned.apk build/aligned.apk
if [ ! -f build/release.keystore ]; then
  # Never sign with a throwaway key by accident: installed apps could not be updated.
  if [ "${ALLOW_DEV_KEY:-}" != "1" ]; then
    echo "No signing key at build/release.keystore. Put the permanent key there (see README > Signing), or set ALLOW_DEV_KEY=1 for a test-only build." >&2
    exit 1
  fi
  keytool -genkeypair -keystore build/release.keystore -storepass android -keypass android -alias lyrics -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=Pearl Lyrics Video Maker by Nico Pan Beats' >/dev/null 2>&1
fi
"$tools/apksigner" sign --ks build/release.keystore --ks-key-alias lyrics --ks-pass pass:android --key-pass pass:android --out PearlLyricsVideoMaker.apk build/aligned.apk
"$tools/apksigner" verify PearlLyricsVideoMaker.apk
echo "Built $(pwd)/PearlLyricsVideoMaker.apk"
