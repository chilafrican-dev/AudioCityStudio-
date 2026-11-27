package ug.audiocity.appvault;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;

/** The apps in the vault and the lock settings, kept in this app's private storage. */
final class VaultStore {
    static final long[] GRACE_OPTIONS_MS = {0, 30_000, 60_000, 300_000};
    static final String[] GRACE_LABELS = {"Immediately", "After 30 seconds", "After 1 minute", "After 5 minutes"};

    private static final String APPS = "apps";
    private final SharedPreferences prefs;

    VaultStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences("vault", Context.MODE_PRIVATE);
    }

    /** Package names in the order they were added. */
    List<String> apps() {
        String raw = prefs.getString(APPS, "");
        List<String> out = new ArrayList<>();
        if (raw.isEmpty()) return out;
        out.addAll(new LinkedHashSet<>(Arrays.asList(raw.split("\n"))));
        return out;
    }

    boolean contains(String pkg) { return apps().contains(pkg); }

    void setApps(List<String> pkgs) {
        prefs.edit().putString(APPS, String.join("\n", new LinkedHashSet<>(pkgs))).apply();
    }

    void remove(String pkg) {
        List<String> list = apps();
        list.remove(pkg);
        setApps(list);
    }

    /** Home screen dock (up to 4 package names), or null until it has been set up. */
    List<String> dock() {
        if (!prefs.contains("dock")) return null;
        String raw = prefs.getString("dock", "");
        return raw.isEmpty() ? new ArrayList<>() : new ArrayList<>(new LinkedHashSet<>(Arrays.asList(raw.split("\n"))));
    }

    void setDock(List<String> pkgs) {
        prefs.edit().putString("dock", String.join("\n", new LinkedHashSet<>(pkgs))).apply();
    }

    long graceMs() { return prefs.getLong("grace", 30_000); }

    void setGraceMs(long ms) { prefs.edit().putLong("grace", ms).apply(); }

    /** Whether the phone's PIN, pattern or password may stand in when the fingerprint is not recognised. */
    boolean allowDeviceCredential() { return prefs.getBoolean("pin_backup", true); }

    void setAllowDeviceCredential(boolean allow) { prefs.edit().putBoolean("pin_backup", allow).apply(); }

    static String graceLabel(long ms) {
        for (int i = 0; i < GRACE_OPTIONS_MS.length; i++) if (GRACE_OPTIONS_MS[i] == ms) return GRACE_LABELS[i];
        return (ms / 1000) + " seconds";
    }
}
