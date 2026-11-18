# Native Android pattern-rack layout tests

Requires Java 17+, Maven, Android SDK, and a completed `build.sh` build.
Run `ANDROID_SDK_ROOT=/path/to/sdk ./run-ui-tests.sh`.
Set `ECJ_JAR` if using a runtime without javac. Maven honors its standard proxy settings;
Robolectric dependency downloads honor Java HTTPS proxy properties via JAVA_TOOL_OPTIONS.
First execution downloads Android 13 framework/native graphics dependencies.

The harness skips application startup/audio/media loading, then shows the production
pattern Dialog with production Android widgets and Pad drawable. It verifies all 128
pads have physical width and are actually visible; real clicks toggle pattern data;
widths 240/320/360/393/480/800 dp preserve lane alignment and 8/16-step zoom.
A native Android/Skia PNG is written to build/ui-tests/render/pattern-after.png.
This is a JVM Android framework test, not a physical-device/audio-performance test.
