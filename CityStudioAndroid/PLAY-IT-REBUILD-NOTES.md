# PLAY IT. KEEP THE TAKE. — Source rebuild 0.12.1

Based on the uploaded `AudioCityStudio-0.12-BuildReady (1).zip`, which is older than the newer interface screenshot. This archive is a complete Android source project, not a prebuilt APK.

## Changes made

- Replaced the fixed-height touch dialog with a full-height performance panel that gives playable chord pads the main space.
- Chord pads default to 4 in the intended progression order; optional 6/8 pads and melody pads are available in settings.
- Added 14 major and 14 minor chord progression choices, selectable by musical style; pads play the selected notes with multi-finger support.
- Moved key, scale, pad layout, chord progression and other advanced controls into a separate settings dialog. A small count-in indicator stays above the pads.
- Added independent Play/Pause Beat, Stop Beat, Loop, Start Recording and Stop Recording controls within the performance dialog.
- Opening the touch performance dialog does not deliberately pause the backing beat. Starting and stopping a MIDI take no longer pauses/restarts project playback or disables looping.
- Take-event timestamps now use the monotonically increasing rendered audio transport frame clock. This allows a musical count-in and avoids wall-clock drift when a project loop wraps.
- Included an optional continuously repeating metronome with play/record choices, user-adjustable click level and one/two bar count-in.
- Kept metronome sound in the live playback renderer only, not in offline exports or rendered MIDI takes.

## Important remaining limitations

- **An APK has not been compiled in this environment** (Android Platform SDK, build tools and NDK are absent). Build it in GitHub Actions using the included workflow or on a machine with Android SDK, then test it on a phone.
- These are **12 existing synthesizer presets**, not newly integrated internet-sourced realistic piano/strings/guitar multisample libraries. Sample instrument downloads are not included in this patch.
- Device input/output latency, UI responsiveness on small screens, MIDI alignment and count-in timing must be checked on real Android hardware. The count-in is armed using the audio transport frame clock; visual updates use the UI handler and may be late by a frame.
- Loop recording captures one continuous take whose notes may extend beyond the loop range. Proper lane-by-lane loop-comping/overdub placement is not implemented.
- Stop Recording saves each Play It take to its own instrument track in Arrange. Note onsets and releases are automatically snapped to the project tempo's 1/16-note grid; notes keep at least one grid step of duration. An independent Keep/Retake audition workflow is not implemented yet.
- Signed APK updates require the original app signing key; keep existing projects backed up before installing a differently signed APK.

## Validation done

- Existing 629 SDK-free audio/logic regression checks passed.
- Added and passed 268 progression and MIDI count-in/transport checks.
- Checked the new `TouchStudio.java` against lightweight Android API stand-ins for syntax/type consistency (not a substitute for Android SDK compilation).

## How to build

Extract ZIP, push the contents of `CityStudioAndroid/` to a private GitHub repository, and run Actions → Build APK. See BUILD-ANDROID.md. The build's APK artifact will be signed with a development key unless you provide the original signing key secret.
