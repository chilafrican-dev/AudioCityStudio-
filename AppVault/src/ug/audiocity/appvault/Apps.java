package ug.audiocity.appvault;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.provider.Settings;
import android.text.TextUtils;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Apps installed on the phone that have a home-screen icon. */
final class Apps {
    static final class App {
        final String pkg;
        final String label;
        final Drawable icon;
        App(String pkg, String label, Drawable icon) { this.pkg = pkg; this.label = label; this.icon = icon; }
    }

    private Apps() {}

    /** Every launchable app except AppVault itself, sorted by name. Slow: call off the main thread. */
    static List<App> installed(Context context) {
        PackageManager pm = context.getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        Map<String, App> byPkg = new HashMap<>();
        for (ResolveInfo ri : pm.queryIntentActivities(main, 0)) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(context.getPackageName()) || byPkg.containsKey(pkg)) continue;
            byPkg.put(pkg, new App(pkg, String.valueOf(ri.loadLabel(pm)), ri.loadIcon(pm)));
        }
        List<App> out = new ArrayList<>(byPkg.values());
        Collections.sort(out, (a, b) -> a.label.compareToIgnoreCase(b.label));
        return out;
    }

    /** The vault's apps in the user's order, skipping any that were uninstalled. */
    static List<App> inVault(Context context, VaultStore store) {
        Map<String, App> all = new HashMap<>();
        for (App a : installed(context)) all.put(a.pkg, a);
        List<App> out = new ArrayList<>();
        for (String pkg : store.apps()) if (all.containsKey(pkg)) out.add(all.get(pkg));
        return out;
    }

    static boolean open(Context context, String pkg) {
        Intent i = context.getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) return false;
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(i);
        return true;
    }

    /** Whether the user has switched on App Guard in Accessibility settings. */
    static boolean guardEnabled(Context context) {
        String enabled = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) return false;
        ComponentName me = new ComponentName(context, GuardService.class);
        TextUtils.SimpleStringSplitter split = new TextUtils.SimpleStringSplitter(':');
        split.setString(enabled);
        for (String s : split) {
            ComponentName c = ComponentName.unflattenFromString(s);
            if (me.equals(c)) return true;
        }
        return false;
    }
}
