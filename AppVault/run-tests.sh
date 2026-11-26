#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
rm -rf build/test-classes && mkdir -p build/test-classes
javac --release 8 -d build/test-classes src/ug/audiocity/appvault/Session.java tests/SessionTests.java
java -cp build/test-classes ug.audiocity.appvault.SessionTests
