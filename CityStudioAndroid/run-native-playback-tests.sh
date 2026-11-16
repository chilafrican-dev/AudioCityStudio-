#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
compiler="${CXX:-g++}"
mode="${1:-release}"
out="build/native-playback-$mode"
mkdir -p "$out"
flags=(-std=c++17 -Wall -Wextra -Werror -pthread -fno-fast-math)
case "$mode" in
 release) flags+=(-O2) ;;
 asan) flags+=(-O1 -g -fno-omit-frame-pointer -fsanitize=address,undefined) ;;
 tsan) flags+=(-O1 -g -fno-omit-frame-pointer -fsanitize=thread) ;;
 *) echo 'Usage: run-native-playback-tests.sh [release|asan|tsan]' >&2; exit 2 ;;
esac
"$compiler" "${flags[@]}" tests/native/PlaybackTests.cpp -o "$out/playback-tests"
"$out/playback-tests"
