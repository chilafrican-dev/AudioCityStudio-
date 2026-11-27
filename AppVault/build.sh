#!/usr/bin/env bash
# Builds AppVault.apk with the Android SDK command-line tools only (no Gradle), like Audio City Studio.
# Usage: ./build.sh            -> AppVault.apk (standard: no App Guard, installs past Play Protect)
#        ./build.sh guard      -> AppVault-Guard.apk (adds the App Guard Accessibility service)
set -euo pipefail
cd "$(dirname "$0")"
variant="${1:-standard}"
case "$variant" in
  standard) out=AppVault.apk ;;
  guard) out=AppVault-Guard.apk ;;
  *) echo "Unknown variant '$variant' (use: standard or guard)" >&2; exit 1 ;;
esac
: "${ANDROID_SDK_ROOT:?Set ANDROID_SDK_ROOT to your Android SDK directory}"
tools="$ANDROID_SDK_ROOT/build-tools/35.0.0"
platform="$ANDROID_SDK_ROOT/platforms/android-35/android.jar"
rm -rf build/classes build/dex build/gen
mkdir -p build/classes build/dex build/gen
# Google Play Protect blocks sideloaded apps that declare an Accessibility service, so the standard build leaves it out.
if [ "$variant" = guard ]; then cp AndroidManifest.xml build/AndroidManifest.xml
else grep -v 'GuardService' AndroidManifest.xml > build/AndroidManifest.xml; fi
"$tools/aapt2" compile --dir res -o build/resources.zip
"$tools/aapt2" link -o build/base.apk -I "$platform" --manifest build/AndroidManifest.xml --java build/gen build/resources.zip
javac --release 8 -classpath "$platform" -d build/classes src/ug/audiocity/appvault/*.java $(find build/gen -name '*.java')
rm -f build/classes.jar
(cd build/classes && zip -qr ../classes.jar .)
"$tools/d8" --lib "$platform" --min-api 30 --output build/dex build/classes.jar
cp build/base.apk build/unsigned.apk
(cd build/dex && zip -q ../unsigned.apk classes*.dex)
"$tools/zipalign" -f -p 4 build/unsigned.apk build/aligned.apk
if [ ! -f build/development.keystore ]; then
keytool -genkeypair -keystore build/development.keystore -storepass android -keypass android -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=AppVault Development' >/dev/null 2>&1
fi
"$tools/apksigner" sign --ks build/development.keystore --ks-pass pass:android --key-pass pass:android --out "$out" build/aligned.apk
"$tools/apksigner" verify --verbose "$out"
echo "Built $out"
