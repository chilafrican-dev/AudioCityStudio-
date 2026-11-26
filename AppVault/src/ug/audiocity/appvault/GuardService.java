package ug.audiocity.appvault;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.accessibility.AccessibilityEvent;

/**
 * App Guard: when a vaulted app comes on screen while the vault is locked, puts the lock screen over it.
 * Only the package name of the foreground window is read.
 */
public final class GuardService extends AccessibilityService {
    private final Session session = Session.SHARED;
    private VaultStore store;
    private long lastLockShownAt;

    private final BroadcastReceiver screenOff = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { session.lock(); }
    };

    @Override protected void onServiceConnected() {
        store = new VaultStore(this);
        registerReceiver(screenOff, new IntentFilter(Intent.ACTION_SCREEN_OFF));
    }

    @Override public void onDestroy() {
        try { unregisterReceiver(screenOff); } catch (IllegalArgumentException ignored) { /* never connected */ }
        super.onDestroy();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (store == null || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || event.getPackageName() == null) return;
        String pkg = event.getPackageName().toString();
        if (isOverlay(pkg)) return;
        if (!store.contains(pkg)) { session.left(); return; }
        if (session.returned(store.graceMs())) return;
        if (LockActivity.showing || SystemClock.elapsedRealtime() - lastLockShownAt < 1500) return;
        lastLockShownAt = SystemClock.elapsedRealtime();
        startActivity(new Intent(this, LockActivity.class)
                .putExtra(LockActivity.EXTRA_PACKAGE, pkg)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
    }

    /** Windows that float over the current app (AppVault itself, the status bar, the keyboard) don't count as leaving it. */
    private boolean isOverlay(String pkg) {
        if (pkg.equals(getPackageName()) || pkg.equals("com.android.systemui") || pkg.equals("android")) return true;
        String ime = Settings.Secure.getString(getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        ComponentName c = ime == null ? null : ComponentName.unflattenFromString(ime);
        return c != null && pkg.equals(c.getPackageName());
    }

    @Override public void onInterrupt() {}
}
