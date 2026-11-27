# Nico Pan Vault

An Android app that keeps your chosen apps together in one place, behind your fingerprint.

- **Vault**: a home-screen-style grid of the apps you pick. It opens only after your fingerprint is accepted.
- **Hidden apps, iPhone-style home screen**: make Nico Pan Vault your home screen and it shows every app *except* the ones in your vault, so those disappear from your home screen and only appear inside the vault.
- **App Guard** (optional, separate download): when one of your vault apps is opened from the home screen or recent apps, a lock screen appears over it until your fingerprint is accepted.
- **Auto-lock**: locks again when the screen turns off, and after you've been away for a time you choose (right away, 30 seconds, 1 minute or 5 minutes; 30 seconds by default).
- **PIN backup**: your phone's PIN, pattern or password works when your fingerprint isn't recognised. You can turn this off for fingerprint only.
- **Private**: uses Android's own fingerprint prompt (BiometricPrompt). Nico Pan Vault never sees your fingerprint, has no internet permission, and blocks screenshots of its screens.

Needs Android 11 or later and a fingerprint set up on the phone.

## Using it

1. Open Nico Pan Vault and touch the fingerprint sensor.
2. Tap **＋**, tick the apps you want in the vault, then tap **Save**.
3. Tap an app in the vault to open it. Long-press one to remove it from the vault. Removing it doesn't uninstall it.
4. Tap **🔒** to lock right away, or **⚙** to change the settings.

### Hiding vault apps from the home screen

Android doesn't let an app hide another app's icon from your phone's own home screen, so Nico Pan Vault comes with its own home screen that leaves vault apps out.

1. In the vault, tap the banner *"These apps still show on your home screen"* (or **⚙ → Hide vault apps from home screen**).
2. Tap **Make Nico Pan Vault my home screen** and choose **Nico Pan Vault** (choose **Always** if asked).

The home screen is laid out like an iPhone's: pages of rounded-square icons, four across and six down, that you swipe between, with page dots, a **Search** button and a translucent dock of four apps at the bottom. At first the dock holds your phone's default dialer, messages, browser and camera apps. The first icon, **Vault**, opens the vault. Long-press any app for **Hide in Vault**, **Add to Dock** / **Remove from Dock** and **App Info**. Pressing Home returns to the first page. To go back to your phone's usual home screen, long-press **Vault** and choose **Change Home Screen App**, or go to **Settings → Apps → Choose default apps → Home app**. Your old home screen layout is still there.

Hidden apps can still be found in **Settings → Apps**, in the Play Store, and in recent apps after you've opened them. Their notifications still show too.

### Two versions

| Download | What's in it | Play Protect |
|---|---|---|
| **NicoPanVault-apk** (`NicoPanVault.apk`) | The vault, fingerprint unlock and auto-lock | Installs normally |
| **NicoPanVault-Guard-apk** (`NicoPanVault-Guard.apk`) | All of that, plus App Guard | Blocked: *"App blocked to protect your device"* |

App Guard has to use an Android Accessibility service to see which app is on screen. Google Play Protect's fraud protection blocks any app installed from outside the Play Store that uses one, whatever the app does with it. There is no "install anyway" button. The Guard version installs only if you turn off **Play Store → profile picture → Play Protect → ⚙ → Scan apps with Play Protect** while installing it, and that lowers your phone's protection. Use the standard version unless you're sure you want that trade-off.

### Turning on App Guard (Guard version only)

Without App Guard, vault apps still open normally from the home screen. To make them ask for your fingerprint everywhere:

1. In Nico Pan Vault, tap the App Guard banner (or **⚙ → App Guard**) and then **Open Accessibility settings**.
2. Choose **Installed apps** (or **Downloaded apps**), then **Nico Pan Vault App Guard**, and switch it on.
3. If Android says the setting is restricted (Android 13 and later, for apps not installed from the Play Store): open **Settings → Apps → Nico Pan Vault**, tap **⋮** at the top right, choose **Allow restricted settings**, then repeat step 2.

App Guard reads only which app is on screen, never what's inside it.

### Limits

Vault apps are only hidden while Nico Pan Vault is your home screen. Someone who knows your phone's PIN can switch off App Guard in Settings.

## Building

**GitHub Actions:** the **Build Nico Pan Vault APK** workflow (`.github/workflows/build-appvault.yml` at the repository root) runs whenever something under `AppVault/` is pushed, or from **Actions → Build Nico Pan Vault APK → Run workflow**. Download **NicoPanVault-apk** (or **NicoPanVault-Guard-apk**) from the run's **Artifacts**, unzip it and install the APK. Both are signed with the same key, so you can install the Guard version over the standard one later.

Each run without a saved key signs the APK with a new key, and Android won't install that over an older install. To keep updates installable, save a keystore as the repository secret `APPVAULT_KEYSTORE_BASE64` (base64-encoded).

**Locally** (JDK 17+, Android SDK Platform 35 and Build Tools 35.0.0):

```bash
export ANDROID_SDK_ROOT=/path/to/Android/Sdk
./run-tests.sh   # auto-lock rules; plain Java, no SDK needed
./build.sh       # writes NicoPanVault.apk (no App Guard)
./build.sh guard # writes NicoPanVault-Guard.apk
```
