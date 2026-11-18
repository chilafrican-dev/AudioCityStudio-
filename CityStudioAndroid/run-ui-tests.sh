#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
: "${ANDROID_SDK_ROOT:?Set ANDROID_SDK_ROOT}"
: "${MAVEN:=mvn}"
[[ -d build/classes ]] || { echo 'Build the app first with build.sh'; exit 1; }
mkdir -p build/ui-tests/lib build/ui-tests/classes
"$MAVEN" -B -f tests/ui/pom.xml dependency:copy-dependencies -DoutputDirectory="$PWD/build/ui-tests/lib"
python3 - <<'PY'
from pathlib import Path
import zipfile
p=Path('build/ui-tests/lib')
for aar in p.glob('*.aar'):
    with zipfile.ZipFile(aar) as z:
        if 'classes.jar' in z.namelist(): (p/(aar.stem+'-classes.jar')).write_bytes(z.read('classes.jar'))
Path('build/ui-tests/classpath').write_text(':'.join(str(j.resolve()) for j in sorted(p.glob('*.jar'))))
PY
classpath="build/classes:$(cat build/ui-tests/classpath)"
platform="$(find "$ANDROID_SDK_ROOT/platforms" -name android.jar | sort -V | tail -1)"
if command -v javac >/dev/null; then
  javac -source 8 -target 8 -cp "$platform:$classpath" -d build/ui-tests/classes tests/ui/*.java
else
  : "${ECJ_JAR:?Set ECJ_JAR when javac is unavailable}"
  java -jar "$ECJ_JAR" -source 1.8 -target 1.8 -cp "$platform:$classpath" -d build/ui-tests/classes tests/ui/*.java
fi
java -Xmx2g -Drack.output="$PWD/build/ui-tests/render" -cp "build/ui-tests/classes:$classpath" org.junit.runner.JUnitCore ug.audiocity.studio.ChannelRackLayoutTest
