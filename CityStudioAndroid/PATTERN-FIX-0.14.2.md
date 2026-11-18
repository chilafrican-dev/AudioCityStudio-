# Audio City Studio 0.14.2 — pattern rack correction

The old weighted grid content collapsed inside HorizontalScrollView. In an Android 13 framework render at 393 dp, every pad was only 3 dp wide (6 px), while the viewport was 273 dp wide. The pad drawable therefore rendered no usable switches.

ChannelRackView now measures the viewport explicitly, assigns positive widths to all step cells and beat labels, aligns all eight lanes, and supports 16-step fit / 8-step zoom. The rack uses a full-height Dialog with scrolling rows and anchored Arrange actions. The header identifies version 0.14.2. Long-press on an empty step now opens at the selected input velocity instead of velocity 1.

## Validation

- Before reproduction: 128 narrow pads, first pad 6 px, matrix 112 px, viewport 546 px.
- After: zero narrow pads, first pad 33 px, matrix and viewport both 546 px.
- Native Android 13 / Skia layout suite: 2 tests passed. All 128 cells stay within the sheet at 393 dp; actual clicks toggle stored velocity 0 → 105 → 0.
- Width matrix: 240, 320, 360, 393, 480, 800 dp; minimum 12 dp pad width, aligned columns and 8/16-step zoom passed. Narrow layouts scroll horizontally.
- All 43 host regression suites passed, including 229 AI drum-track assertions. Private-recording tests were unavailable and are excluded by the host runner.
- APK compilation and signature verification passed; versionCode 34, versionName 0.14.2. Original certificate retained.

The attached PNG is rendered from the actual production Android widgets/drawables, with example pad states and a highlighted playback column. It is not a physical-phone screenshot or proof of live playback timing. Physical-device/audio/performance checks remain outstanding.

## Files changed

- src/ug/audiocity/studio/ChannelRackView.java
- src/ug/audiocity/studio/BeatPilotDrumScreen.java
- AndroidManifest.xml
- tests/ui/ChannelRackLayoutTest.java, pom.xml, README.md
- run-ui-tests.sh

Run build.sh, run-tests.sh and run-ui-tests.sh as described in the included source and tests/ui/README.md. No Maven dependencies or signing keys are included in the source archive.
