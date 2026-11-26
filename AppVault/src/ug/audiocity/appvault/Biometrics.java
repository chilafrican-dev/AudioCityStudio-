package ug.audiocity.appvault;

import android.app.Activity;
import android.content.Intent;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricManager.Authenticators;
import android.hardware.biometrics.BiometricPrompt;
import android.os.CancellationSignal;
import android.provider.Settings;

/** Shows the system fingerprint prompt. The fingerprint itself never reaches this app: Android only reports success or failure. */
final class Biometrics {
    interface Result {
        void unlocked();
        /** Authentication ended without success; message is shown to the user. */
        void failed(CharSequence message);
    }

    private Biometrics() {}

    static int authenticators(VaultStore store) {
        int a = Authenticators.BIOMETRIC_STRONG;
        if (store.allowDeviceCredential()) a |= Authenticators.DEVICE_CREDENTIAL;
        return a;
    }

    /** Null when the prompt can be shown, otherwise why it cannot. */
    static String problem(Activity activity, VaultStore store) {
        BiometricManager bm = activity.getSystemService(BiometricManager.class);
        if (bm == null) return "This phone does not offer fingerprint unlock to apps.";
        switch (bm.canAuthenticate(authenticators(store))) {
            case BiometricManager.BIOMETRIC_SUCCESS: return null;
            case BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED: return "No fingerprint is set up on this phone yet.";
            case BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE: return "This phone has no fingerprint sensor that apps can use.";
            case BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE: return "The fingerprint sensor is busy or unavailable. Try again in a moment.";
            default: return "Fingerprint unlock is not available right now.";
        }
    }

    /** Opens the system screen for adding a fingerprint. */
    static void openEnrollment(Activity activity, VaultStore store) {
        Intent i = new Intent(Settings.ACTION_BIOMETRIC_ENROLL)
                .putExtra(Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED, authenticators(store));
        try { activity.startActivity(i); }
        catch (RuntimeException e) { activity.startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS)); }
    }

    static CancellationSignal prompt(Activity activity, VaultStore store, String subtitle, Result result) {
        BiometricPrompt.Builder b = new BiometricPrompt.Builder(activity)
                .setTitle("Unlock AppVault")
                .setSubtitle(subtitle)
                .setConfirmationRequired(false)
                .setAllowedAuthenticators(authenticators(store));
        if (!store.allowDeviceCredential()) {
            // Fingerprint-only prompts must offer their own cancel button.
            b.setNegativeButton("Cancel", activity.getMainExecutor(), (d, w) -> result.failed("Unlock cancelled."));
        }
        CancellationSignal cancel = new CancellationSignal();
        b.build().authenticate(cancel, activity.getMainExecutor(), new BiometricPrompt.AuthenticationCallback() {
            @Override public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult r) {
                Session.SHARED.unlock();
                result.unlocked();
            }
            @Override public void onAuthenticationError(int code, CharSequence message) {
                result.failed(message);
            }
            // onAuthenticationFailed (finger not recognised) leaves the prompt open for another try.
        });
        return cancel;
    }
}
