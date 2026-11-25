#!/usr/bin/env bash
# Builds and signs LyricVideoStudio.apk with the plain Android SDK tools (no Gradle).
set -euo pipefail
cd "$(dirname "$0")"
: "${ANDROID_SDK_ROOT:?Set ANDROID_SDK_ROOT to your Android SDK directory}"
tools="$ANDROID_SDK_ROOT/build-tools/35.0.0"
platform="$ANDROID_SDK_ROOT/platforms/android-35/android.jar"
rm -rf build/classes build/dex
mkdir -p build/classes build/dex
"$tools/aapt2" compile --dir res -o build/resources.zip
"$tools/aapt2" link -o build/base.apk -I "$platform" --manifest AndroidManifest.xml build/resources.zip
javac -nowarn --release 8 -classpath "$platform" -d build/classes src/ug/audiocity/lyrics/*.java
rm -f build/classes.jar
(cd build/classes && zip -qr ../classes.jar .)
"$tools/d8" --lib "$platform" --min-api 29 --release --output build/dex build/classes.jar
cp build/base.apk build/unsigned.apk
(cd build/dex && zip -q ../unsigned.apk classes*.dex)
"$tools/zipalign" -f -p 4 build/unsigned.apk build/aligned.apk
if [ ! -f build/release.keystore ]; then
  keytool -genkeypair -keystore build/release.keystore -storepass android -keypass android -alias lyrics -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=Audio City Lyric Video Studio' >/dev/null 2>&1
fi
"$tools/apksigner" sign --ks build/release.keystore --ks-key-alias lyrics --ks-pass pass:android --key-pass pass:android --out LyricVideoStudio.apk build/aligned.apk
"$tools/apksigner" verify LyricVideoStudio.apk
echo "Built $(pwd)/LyricVideoStudio.apk"
