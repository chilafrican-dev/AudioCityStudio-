# AppVault

An Android app that keeps your chosen apps together in one place, behind your fingerprint.

- **Vault**: a home-screen-style grid of the apps you pick. It opens only after your fingerprint is accepted.
- **App Guard** (optional, separate download): when one of your vault apps is opened from the home screen or recent apps, a lock screen appears over it until your fingerprint is accepted.
- **Auto-lock**: locks again when the screen turns off, and after you've been away for a time you choose (right away, 30 seconds, 1 minute or 5 minutes; 30 seconds by default).
- **PIN backup**: your phone's PIN, pattern or password works when your fingerprint isn't recognised. You can turn this off for fingerprint only.
- **Private**: uses Android's own fingerprint prompt (BiometricPrompt). AppVault never sees your fingerprint, has no internet permission, and blocks screenshots of its screens.

Needs Android 11 or later and a fingerprint set up on the phone.

## Using it

1. Open AppVault and touch the fingerprint sensor.
2. Tap **＋**, tick the apps you want in the vault, then tap **Save**.
3. Tap an app in the vault to open it. Long-press one to remove it from the vault. Removing it doesn't uninstall it.
4. Tap **🔒** to lock right away, or **⚙** to change the settings.

### Two versions

| Download | What's in it | Play Protect |
|---|---|---|
| **AppVault-apk** (`AppVault.apk`) | The vault, fingerprint unlock and auto-lock | Installs normally |
| **AppVault-Guard-apk** (`AppVault-Guard.apk`) | All of that, plus App Guard | Blocked: *"App blocked to protect your device"* |

App Guard has to use an Android Accessibility service to see which app is on screen. Google Play Protect's fraud protection blocks any app installed from outside the Play Store that uses one, whatever the app does with it. There is no "install anyway" button. The Guard version installs only if you turn off **Play Store → profile picture → Play Protect → ⚙ → Scan apps with Play Protect** while installing it, and that lowers your phone's protection. Use the standard version unless you're sure you want that trade-off.

### Turning on App Guard (Guard version only)

Without App Guard, vault apps still open normally from the home screen. To make them ask for your fingerprint everywhere:

1. In AppVault, tap the App Guard banner (or **⚙ → App Guard**) and then **Open Accessibility settings**.
2. Choose **Installed apps** (or **Downloaded apps**), then **AppVault App Guard**, and switch it on.
3. If Android says the setting is restricted (Android 13 and later, for apps not installed from the Play Store): open **Settings → Apps → AppVault**, tap **⋮** at the top right, choose **Allow restricted settings**, then repeat step 2.

App Guard reads only which app is on screen, never what's inside it.

### Limits

Android doesn't let an ordinary app hide other apps' icons from the home screen or app drawer. Vault apps stay visible there: App Guard locks them, but can't hide them. Someone who knows your phone's PIN can switch off App Guard in Settings.

## Building

**GitHub Actions:** the **Build AppVault APK** workflow (`.github/workflows/build-appvault.yml` at the repository root) runs whenever something under `AppVault/` is pushed, or from **Actions → Build AppVault APK → Run workflow**. Download **AppVault-apk** (or **AppVault-Guard-apk**) from the run's **Artifacts**, unzip it and install the APK. Both are signed with the same key, so you can install the Guard version over the standard one later.

Each run without a saved key signs the APK with a new key, and Android won't install that over an older AppVault. To keep updates installable, save a keystore as the repository secret `APPVAULT_KEYSTORE_BASE64` (base64-encoded).

**Locally** (JDK 17+, Android SDK Platform 35 and Build Tools 35.0.0):

```bash
export ANDROID_SDK_ROOT=/path/to/Android/Sdk
./run-tests.sh   # auto-lock rules; plain Java, no SDK needed
./build.sh       # writes AppVault.apk (no App Guard)
./build.sh guard # writes AppVault-Guard.apk
```
