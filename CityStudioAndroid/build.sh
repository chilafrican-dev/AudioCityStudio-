#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
: "${ANDROID_SDK_ROOT:?Set ANDROID_SDK_ROOT to your Android SDK directory}"
tools="$ANDROID_SDK_ROOT/build-tools/35.0.0"
platform="$ANDROID_SDK_ROOT/platforms/android-35/android.jar"
# Factory packs are ZIP assets, not disposable source archives. Fail before
# packaging if an incomplete checkout would leave the instrument picker empty.
for id in freepats-fm-piano2 freepats-ydp-grand-compact freepats-upright-kw-full freepats-upright-kw-small freepats-upright-kw-bright freepats-electric-guitar-clean freepats-electric-guitar-direct freepats-electric-guitar-jazz soundza-afrobeats-drums; do
  [ -s "assets/instruments/$id.zip" ] || {
    echo "Missing factory instrument: assets/instruments/$id.zip. Restore the instrument assets from the complete source archive before building." >&2
    exit 1
  }
done
rm -rf build/classes build/dex build/lib
mkdir -p build/classes build/dex
"$tools/aapt2" compile --dir res -o build/resources.zip
"$tools/aapt2" link -o build/base.apk -I "$platform" --manifest AndroidManifest.xml -A assets build/resources.zip
mapfile -t java_sources < <(find src -name "*.java" -print | sort)
if command -v javac >/dev/null; then
javac --release 8 -classpath "$platform" -d build/classes "${java_sources[@]}"
else
: "${ECJ_JAR:?Set ECJ_JAR to Eclipse ecj compiler jar when javac is unavailable}"
java -jar "$ECJ_JAR" -source 1.8 -target 1.8 -classpath "$platform" -d build/classes "${java_sources[@]}"
fi
rm -f build/classes.jar
(cd build/classes && zip -qr ../classes.jar .)
"$tools/d8" --lib "$platform" --min-api 30 --output build/dex build/classes.jar
cp build/base.apk build/unsigned.apk
(cd build/dex && zip -q ../unsigned.apk classes*.dex)
# Optional native AAudio output. Without ANDROID_NDK_ROOT the APK uses the Java AudioTrack path only.
if [ -n "${ANDROID_NDK_ROOT:-}" ]; then
host="$(uname -s | tr '[:upper:]' '[:lower:]')-x86_64"
llvm="$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/$host/bin"
[ -d "$llvm" ] || { echo "NDK toolchain not found at $llvm" >&2; exit 1; }
for pair in arm64-v8a:aarch64-linux-android30 armeabi-v7a:armv7a-linux-androideabi30 x86_64:x86_64-linux-android30; do
abi="${pair%%:*}"; target="${pair#*:}"
mkdir -p "build/lib/$abi"
"$llvm/clang++" --target="$target" -std=c++17 -O2 -fPIC -shared -static-libstdc++ -Wl,-z,max-page-size=16384 -o "build/lib/$abi/libaudiocity.so" jni/audiocity_aaudio.cpp -laaudio -llog
done
(cd build && zip -qr unsigned.apk lib)
echo "Included native AAudio output for arm64-v8a, armeabi-v7a and x86_64."
else
echo "ANDROID_NDK_ROOT not set: building without native AAudio (AudioTrack output only)."
fi
"$tools/zipalign" -f -p 4 build/unsigned.apk build/aligned.apk
if [ ! -f build/development.keystore ]; then
keytool -genkeypair -keystore build/development.keystore -storepass android -keypass android -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=Audio City Studio Development' >/dev/null 2>&1
fi
"$tools/apksigner" sign --ks build/development.keystore --ks-pass pass:android --key-pass pass:android --out AudioCityStudio.apk build/aligned.apk
"$tools/apksigner" verify --verbose AudioCityStudio.apk
