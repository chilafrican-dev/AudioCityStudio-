package ug.audiocity.appvault;

import android.app.Activity;
import android.app.role.RoleManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.provider.MediaStore;
import android.provider.Telephony;
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
        /** ApplicationInfo.CATEGORY_*, used to group apps in the App Library. */
        final int category;
        App(String pkg, String label, Drawable icon) { this(pkg, label, icon, -1); }
        App(String pkg, String label, Drawable icon, int category) { this.pkg = pkg; this.label = label; this.icon = icon; this.category = category; }
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
            byPkg.put(pkg, new App(pkg, String.valueOf(ri.loadLabel(pm)), rounded(context.getResources(), ri.loadIcon(pm), false), ri.activityInfo.applicationInfo.category));
        }
        List<App> out = new ArrayList<>(byPkg.values());
        Collections.sort(out, (a, b) -> a.label.compareToIgnoreCase(b.label));
        return out;
    }

    /** Pixel size icons are drawn at: sharp at 60dp on high-density screens without using much memory. */
    private static final int ICON_PX = 168;

    /**
     * Draws an icon as a rounded square, the same shape for every app. Adaptive icons fill the shape;
     * older icons sit on a white tile. fullBleed draws a square icon edge to edge (Nico Pan Vault's own).
     */
    static Drawable rounded(Resources res, Drawable icon, boolean fullBleed) {
        int px = ICON_PX;
        Bitmap bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Path clip = new Path();
        float r = px * 0.225f;
        clip.addRoundRect(0, 0, px, px, r, r, Path.Direction.CW);
        c.clipPath(clip);
        if (icon instanceof AdaptiveIconDrawable) {
            // Adaptive layers are 108 units with the middle 72 visible: spread them a quarter past each edge.
            AdaptiveIconDrawable a = (AdaptiveIconDrawable) icon;
            int pad = px / 4;
            for (Drawable layer : new Drawable[]{a.getBackground(), a.getForeground()}) {
                if (layer == null) continue;
                layer.setBounds(-pad, -pad, px + pad, px + pad);
                layer.draw(c);
            }
        } else if (fullBleed) {
            icon.setBounds(0, 0, px, px);
            icon.draw(c);
        } else {
            c.drawColor(0xFFFFFFFF);
            int in = Math.round(px * 0.14f);
            icon.setBounds(in, in, px - in, px - in);
            icon.draw(c);
        }
        return new BitmapDrawable(res, bmp);
    }

    /** The phone's default dialer, messages, browser and camera apps, for the first dock. */
    static List<String> defaultDock(Context context) {
        PackageManager pm = context.getPackageManager();
        List<String> out = new ArrayList<>();
        addIfLaunchable(context, out, resolvePkg(pm, new Intent(Intent.ACTION_DIAL)));
        addIfLaunchable(context, out, Telephony.Sms.getDefaultSmsPackage(context));
        addIfLaunchable(context, out, resolvePkg(pm, new Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))));
        addIfLaunchable(context, out, resolvePkg(pm, new Intent(MediaStore.ACTION_IMAGE_CAPTURE)));
        return out;
    }

    private static String resolvePkg(PackageManager pm, Intent i) {
        ResolveInfo ri = pm.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY);
        return ri == null || ri.activityInfo == null ? null : ri.activityInfo.packageName;
    }

    private static void addIfLaunchable(Context context, List<String> out, String pkg) {
        // "android" is the app chooser, shown when no default is set.
        if (pkg == null || pkg.equals("android") || out.contains(pkg)) return;
        if (context.getPackageManager().getLaunchIntentForPackage(pkg) != null) out.add(pkg);
    }

    /** The vault's apps in the user's order, skipping any that were uninstalled. */
    static List<App> inVault(Context context, VaultStore store) {
        Map<String, App> all = new HashMap<>();
        for (App a : installed(context)) all.put(a.pkg, a);
        List<App> out = new ArrayList<>();
        for (String pkg : store.apps()) if (all.containsKey(pkg)) out.add(all.get(pkg));
        return out;
    }

    /** Whether AppVault is the phone's home screen, which is what hides vault apps from it. */
    static boolean isDefaultHome(Context context) {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo ri = context.getPackageManager().resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
        return ri != null && ri.activityInfo != null && context.getPackageName().equals(ri.activityInfo.packageName);
    }

    /** Asks Android to make AppVault the home screen; falls back to the system's home-app chooser. */
    static void requestDefaultHome(Activity activity) {
        RoleManager rm = activity.getSystemService(RoleManager.class);
        if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME) && !rm.isRoleHeld(RoleManager.ROLE_HOME)) {
            try { activity.startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_HOME), 1); return; }
            catch (RuntimeException ignored) { /* fall through to Settings */ }
        }
        activity.startActivity(new Intent(android.provider.Settings.ACTION_HOME_SETTINGS));
    }

    static boolean open(Context context, String pkg) { return open(context, pkg, null); }

    /** Opens an app; options can carry a launch animation (e.g. zooming out of its icon). */
    static boolean open(Context context, String pkg, android.os.Bundle options) {
        Intent i = context.getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) return false;
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(i, options);
        return true;
    }

    /**
     * Whether this build includes App Guard. The standard build leaves it out of the manifest because
     * Google Play Protect blocks sideloaded apps that declare an Accessibility service.
     */
    static boolean guardAvailable(Context context) {
        try {
            context.getPackageManager().getServiceInfo(new ComponentName(context, GuardService.class), 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** Whether the user has switched on App Guard in Accessibility settings. */
    static boolean guardEnabled(Context context) {
        if (!guardAvailable(context)) return false;
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
