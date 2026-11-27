package ug.audiocity.appvault;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Nico Pan Vault's iPhone-style home screen: a clock and calendar widget, swipeable pages of
 * rounded-square icons (4 across, 6 down), a Search pill that turns into page dots while swiping,
 * a frosted-glass dock and an App Library page that groups every app by kind.
 * Apps in the vault never appear here, so they're only reachable through the vault's fingerprint lock.
 */
public final class HomeActivity extends Activity {
    private static final int COLUMNS = 4;
    private static final int ROWS = 6;
    private static final int DOCK_SIZE = 4;
    private static final int REQ_WALLPAPER = 7;

    private VaultStore store;
    private Apps.App vaultTile;
    /** Everything shown on the home screen (pages, dock, App Library), vault tile first. Never contains vault apps. */
    private final List<Apps.App> visible = new ArrayList<>();
    private final List<Apps.App> dockApps = new ArrayList<>();

    private FrameLayout frame;
    private Pager pager;
    private Glass pill;
    private LinearLayout pillSearch;
    private LinearLayout pillDots;
    private LinearLayout dockRow;
    /** Frosted panels that move (App Library boxes) and must redraw their glass while scrolling. */
    private final List<View> movingGlass = new ArrayList<>();
    private FrameLayout overlay;
    private final Runnable showSearchPill = () -> { pillDots.setVisibility(View.GONE); pillSearch.setVisibility(View.VISIBLE); };

    private final BroadcastReceiver packagesChanged = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { reload(); }
    };

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        store = new VaultStore(this);
        Wallpaper.ensureLoaded(this);
        vaultTile = new Apps.App(getPackageName(), "Vault",
                Apps.rounded(getResources(), getDrawable(getApplicationInfo().icon), true));

        // Draw edge to edge under transparent status and navigation bars, like iOS.
        getWindow().setDecorFitsSystemWindows(false);
        getWindow().setStatusBarColor(0x00000000);
        getWindow().setNavigationBarColor(0x00000000);

        frame = new FrameLayout(this);
        frame.setBackground(Wallpaper.drawable(false, 0));
        frame.addView(buildHome());
        setContentView(frame);

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        registerReceiver(packagesChanged, f);
    }

    @Override protected void onDestroy() {
        unregisterReceiver(packagesChanged);
        super.onDestroy();
    }

    @Override protected void onResume() {
        super.onResume();
        reload(); // picks up apps just added to or removed from the vault
    }

    /** Pressing Home closes whatever is open, then returns to the first page, as on iPhone. */
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (overlay != null) closeOverlay();
        else pager.goTo(0, true);
    }

    @Override public void onBackPressed() {
        if (overlay != null) closeOverlay();
        else pager.goTo(0, true);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != REQ_WALLPAPER || result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        new Thread(() -> {
            boolean ok = Wallpaper.setFromUri(this, uri);
            runOnUiThread(() -> {
                if (!ok) Toast.makeText(this, "That picture couldn't be used.", Toast.LENGTH_SHORT).show();
                refreshWallpaper();
            });
        }, "wallpaper").start();
    }

    // ---------------------------------------------------------------- layout

    private View buildHome() {
        LinearLayout home = new LinearLayout(this);
        home.setOrientation(LinearLayout.VERTICAL);
        home.setOnApplyWindowInsetsListener((v, in) -> {
            Insets bars = in.getInsets(WindowInsets.Type.systemBars());
            v.setPadding(bars.left, bars.top + dp(14), bars.right, bars.bottom + dp(8));
            return in;
        });

        pager = new Pager(this);
        pager.scrolled = () -> {
            for (View g : movingGlass) g.invalidate();
            pillSearch.setVisibility(View.GONE);
            pillDots.setVisibility(View.VISIBLE);
            pill.removeCallbacks(showSearchPill);
            pill.postDelayed(showSearchPill, 1200);
        };
        pager.pageChanged = this::updateDots;
        home.addView(pager, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        // Search pill above the dock; shows page dots while swiping between pages.
        pill = new Glass(this, dp(15), 0x2EFFFFFF);
        pill.setContentDescription("Search");
        pill.setOnClickListener(v -> openSearch());
        pressEffect(pill);
        pillSearch = new LinearLayout(this);
        pillSearch.setGravity(Gravity.CENTER_VERTICAL);
        pillSearch.setPadding(dp(12), 0, dp(14), 0);
        pillSearch.addView(glyphView(Glyph.SEARCH, 0xFFFFFFFF, 13), new LinearLayout.LayoutParams(dp(13), dp(13)));
        TextView st = label("Search", 13, 0xFFFFFFFF);
        st.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        st.setShadowLayer(0, 0, 0, 0);
        st.setPadding(dp(5), 0, 0, 0);
        pillSearch.addView(st);
        pill.addView(pillSearch, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        pillDots = new LinearLayout(this);
        pillDots.setGravity(Gravity.CENTER);
        pillDots.setPadding(dp(8), 0, dp(8), 0);
        pillDots.setVisibility(View.GONE);
        pill.addView(pillDots, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(30));
        plp.gravity = Gravity.CENTER_HORIZONTAL;
        plp.setMargins(0, dp(4), 0, dp(14));
        home.addView(pill, plp);

        // Frosted-glass dock.
        Glass dock = new Glass(this, dp(34), 0x38FFFFFF);
        dockRow = new LinearLayout(this);
        dockRow.setGravity(Gravity.CENTER);
        dockRow.setPadding(dp(6), dp(15), dp(6), dp(15));
        dock.addView(dockRow, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.setMargins(dp(12), 0, dp(12), 0);
        home.addView(dock, dlp);
        return home;
    }

    // ---------------------------------------------------------------- data

    private void reload() {
        new Thread(() -> {
            Set<String> hidden = new HashSet<>(store.apps());
            Map<String, Apps.App> byPkg = new HashMap<>();
            List<Apps.App> list = new ArrayList<>();
            list.add(vaultTile);
            for (Apps.App a : Apps.installed(this)) {
                if (hidden.contains(a.pkg)) continue;
                list.add(a);
                byPkg.put(a.pkg, a);
            }
            List<String> dockPkgs = store.dock();
            if (dockPkgs == null) { dockPkgs = Apps.defaultDock(this); store.setDock(dockPkgs); }
            List<Apps.App> docked = new ArrayList<>();
            for (String pkg : dockPkgs) if (byPkg.containsKey(pkg) && docked.size() < DOCK_SIZE) docked.add(byPkg.get(pkg));
            runOnUiThread(() -> {
                visible.clear();
                visible.addAll(list);
                dockApps.clear();
                dockApps.addAll(docked);
                render();
            });
        }, "home-load").start();
    }

    private void render() {
        movingGlass.clear();
        List<Apps.App> onPages = new ArrayList<>();
        for (Apps.App a : visible) if (!dockApps.contains(a)) onPages.add(a);

        // Page 1 has the two widgets across its top two rows; later pages are all icons.
        List<View> pages = new ArrayList<>();
        int i = 0;
        boolean first = true;
        do {
            LinearLayout page = new LinearLayout(this);
            page.setOrientation(LinearLayout.VERTICAL);
            page.setPadding(dp(14), 0, dp(14), 0);
            page.setOnLongClickListener(v -> { homeMenu(); return true; });
            int rows = ROWS;
            if (first) {
                LinearLayout widgets = new LinearLayout(this);
                widgets.addView(widgetCell(new Widgets.Clock(this), "Clock"), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
                widgets.addView(widgetCell(new Widgets.Month(this), "Calendar"), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
                page.addView(widgets, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 2));
                rows -= 2;
                first = false;
            }
            for (int r = 0; r < rows; r++) {
                LinearLayout row = new LinearLayout(this);
                for (int c = 0; c < COLUMNS; c++) {
                    View cell = i < onPages.size() ? iconCell(onPages.get(i++), 60, true) : new View(this);
                    row.addView(cell, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
                }
                page.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
            }
            pages.add(page);
        } while (i < onPages.size());
        pages.add(appLibrary());
        pager.setPages(pages);

        dockRow.removeAllViews();
        for (Apps.App a : dockApps) dockRow.addView(iconCell(a, 60, false), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (dockApps.isEmpty()) dockRow.addView(new View(this), new LinearLayout.LayoutParams(1, dp(60)));
        updateDots(pager.page);
    }

    private View widgetCell(View widget, String name) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setPadding(dp(8), dp(4), dp(8), 0);
        widget.setElevation(dp(3));
        cell.addView(widget, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        TextView l = label(name, 12, 0xFFFFFFFF);
        l.setGravity(Gravity.CENTER);
        l.setPadding(0, dp(5), 0, dp(6));
        cell.addView(l, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return cell;
    }

    private void updateDots(int current) {
        pillDots.removeAllViews();
        int count = pager.pageCount();
        for (int i = 0; i < count; i++) {
            View dot = new View(this);
            dot.setBackground(Ui.rounded(i == current ? 0xFFFFFFFF : 0x66FFFFFF, dp(4)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(7), dp(7));
            lp.setMargins(dp(4), 0, dp(4), 0);
            pillDots.addView(dot, lp);
        }
    }

    // ---------------------------------------------------------------- App Library

    private static final String[] GROUPS = {"Social", "Entertainment", "Photo & Video", "Games",
            "Productivity & Finance", "Information & Reading", "Travel", "Utilities"};

    private static String groupOf(Apps.App a) {
        switch (a.category) {
            case ApplicationInfo.CATEGORY_SOCIAL: return "Social";
            case ApplicationInfo.CATEGORY_AUDIO:
            case ApplicationInfo.CATEGORY_VIDEO: return "Entertainment";
            case ApplicationInfo.CATEGORY_IMAGE: return "Photo & Video";
            case ApplicationInfo.CATEGORY_GAME: return "Games";
            case ApplicationInfo.CATEGORY_PRODUCTIVITY: return "Productivity & Finance";
            case ApplicationInfo.CATEGORY_NEWS: return "Information & Reading";
            case ApplicationInfo.CATEGORY_MAPS: return "Travel";
            default: return "Utilities";
        }
    }

    /** The last page: a search field and frosted boxes that group every visible app by kind. */
    private View appLibrary() {
        Map<String, List<Apps.App>> groups = new LinkedHashMap<>();
        for (String g : GROUPS) groups.put(g, new ArrayList<>());
        for (Apps.App a : visible) groups.get(a == vaultTile ? "Utilities" : groupOf(a)).add(a);

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setOnScrollChangeListener((v, x, y, ox, oy) -> { for (View g : movingGlass) g.invalidate(); });
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(22), dp(4), dp(22), dp(24));
        scroll.addView(col);

        Glass search = new Glass(this, dp(12), 0x2EFFFFFF);
        movingGlass.add(search);
        LinearLayout sr = new LinearLayout(this);
        sr.setGravity(Gravity.CENTER_VERTICAL);
        sr.setPadding(dp(12), 0, dp(12), 0);
        sr.addView(glyphView(Glyph.SEARCH, 0xCCFFFFFF, 16), new LinearLayout.LayoutParams(dp(16), dp(16)));
        TextView hint = label("App Library", 17, 0xCCFFFFFF);
        hint.setShadowLayer(0, 0, 0, 0);
        hint.setPadding(dp(8), 0, 0, 0);
        sr.addView(hint);
        search.addView(sr, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        search.setOnClickListener(v -> openSearch());
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40));
        slp.setMargins(0, 0, 0, dp(20));
        col.addView(search, slp);

        LinearLayout row = null;
        int n = 0;
        for (Map.Entry<String, List<Apps.App>> e : groups.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            if (n % 2 == 0) {
                row = new LinearLayout(this);
                col.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            lp.setMargins(n % 2 == 0 ? 0 : dp(10), 0, n % 2 == 0 ? dp(10) : 0, dp(14));
            row.addView(libraryBox(e.getKey(), e.getValue()), lp);
            n++;
        }
        if (n % 2 == 1) row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        return scroll;
    }

    /** One App Library box: up to three apps you can tap, and a cluster of small icons that opens the full group. */
    private View libraryBox(String name, List<Apps.App> apps) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        Glass box = new Glass(this, dp(24), 0x24FFFFFF);
        box.square = true;
        movingGlass.add(box);
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setPadding(dp(11), dp(11), dp(11), dp(11));
        boolean cluster = apps.size() > 4;
        for (int r = 0; r < 2; r++) {
            LinearLayout line = new LinearLayout(this);
            for (int c = 0; c < 2; c++) {
                int i = r * 2 + c;
                View q;
                if (i == 3 && cluster) q = cluster(apps.subList(3, Math.min(7, apps.size())), () -> openFolder(name, apps));
                else if (i < apps.size()) q = libraryIcon(apps.get(i));
                else q = new View(this);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
                int m = dp(5);
                lp.setMargins(m, m, m, m);
                line.addView(q, lp);
            }
            grid.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        }
        box.addView(grid, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        cell.addView(box, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView l = label(name, 12, 0xFFFFFFFF);
        l.setGravity(Gravity.CENTER);
        l.setSingleLine(true);
        l.setEllipsize(TextUtils.TruncateAt.END);
        l.setPadding(0, dp(6), 0, 0);
        l.setOnClickListener(v -> openFolder(name, apps));
        cell.addView(l);
        return cell;
    }

    private View libraryIcon(Apps.App app) {
        ImageView iv = new ImageView(this);
        iv.setImageDrawable(app.icon);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setContentDescription(app == vaultTile ? "Nico Pan Vault" : app.label);
        iv.setOnClickListener(v -> launch(app, v));
        iv.setOnLongClickListener(v -> { appMenu(app, v); return true; });
        pressEffect(iv);
        return iv;
    }

    private View cluster(List<Apps.App> apps, Runnable open) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        for (int r = 0; r < 2; r++) {
            LinearLayout line = new LinearLayout(this);
            for (int k = 0; k < 2; k++) {
                int i = r * 2 + k;
                ImageView iv = new ImageView(this);
                if (i < apps.size()) iv.setImageDrawable(apps.get(i).icon);
                iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
                lp.setMargins(dp(2), dp(2), dp(2), dp(2));
                line.addView(iv, lp);
            }
            c.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        }
        c.setContentDescription("More apps");
        c.setOnClickListener(v -> open.run());
        pressEffect(c);
        return c;
    }

    // ---------------------------------------------------------------- icons

    private View iconCell(Apps.App app, int sizeDp, boolean withLabel) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER);
        ImageView icon = new ImageView(this);
        icon.setImageDrawable(app.icon);
        cell.addView(icon, new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        if (withLabel) {
            TextView l = label(app.label, 12, 0xFFFFFFFF);
            l.setSingleLine(true);
            l.setEllipsize(TextUtils.TruncateAt.END);
            l.setGravity(Gravity.CENTER);
            l.setPadding(dp(2), dp(5), dp(2), 0);
            cell.addView(l, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        cell.setContentDescription(app == vaultTile ? "Nico Pan Vault" : app.label);
        cell.setOnClickListener(v -> launch(app, icon));
        cell.setOnLongClickListener(v -> { appMenu(app, icon); return true; });
        pressEffect(cell);
        return cell;
    }

    /** iOS-style press feedback: the icon dims while touched. */
    private static void pressEffect(View v) {
        v.setOnTouchListener((view, ev) -> {
            int a = ev.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) view.animate().alpha(0.55f).setDuration(80).start();
            else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) view.animate().alpha(1f).setDuration(150).start();
            return false;
        });
    }

    private void launch(Apps.App app, View from) {
        Bundle anim = from == null || from.getWidth() == 0 ? null
                : ActivityOptions.makeScaleUpAnimation(from, 0, 0, from.getWidth(), from.getHeight()).toBundle();
        if (overlay != null) closeOverlay();
        if (app == vaultTile) startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), anim);
        else if (!Apps.open(this, app.pkg, anim)) Toast.makeText(this, app.label + " can't be opened.", Toast.LENGTH_SHORT).show();
    }

    // ---------------------------------------------------------------- menus (long-press)

    private static final class Item {
        final String title; final int glyph; final Runnable action; final boolean destructive;
        Item(String title, int glyph, boolean destructive, Runnable action) { this.title = title; this.glyph = glyph; this.destructive = destructive; this.action = action; }
    }

    private void appMenu(Apps.App app, View icon) {
        List<Item> items = new ArrayList<>();
        if (app == vaultTile) {
            items.add(new Item("Open Vault", Glyph.LOCK, false, () -> launch(app, null)));
            items.add(new Item("Change Wallpaper", Glyph.PHOTO, false, this::pickWallpaper));
            items.add(new Item("Change Home Screen App", Glyph.HOME, false, () -> startActivity(new Intent(Settings.ACTION_HOME_SETTINGS))));
        } else {
            boolean docked = dockApps.contains(app);
            items.add(new Item(docked ? "Remove from Dock" : "Add to Dock", docked ? Glyph.MINUS : Glyph.PLUS, false, () -> toggleDock(app)));
            items.add(new Item("App Info", Glyph.INFO, false, () -> startActivity(
                    new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", app.pkg, null)))));
            items.add(new Item("Hide in Vault", Glyph.LOCK, true, () -> hideInVault(app)));
        }
        showMenu(icon, app.icon, items);
    }

    private void homeMenu() {
        List<Item> items = new ArrayList<>();
        items.add(new Item("Change Wallpaper", Glyph.PHOTO, false, this::pickWallpaper));
        items.add(new Item("Default Wallpaper", Glyph.RESET, false, () -> { Wallpaper.reset(this); refreshWallpaper(); }));
        items.add(new Item("Change Home Screen App", Glyph.HOME, false, () -> startActivity(new Intent(Settings.ACTION_HOME_SETTINGS))));
        showMenu(null, null, items);
    }

    /** iOS-style context menu: the screen blurs, the icon lifts, and a rounded menu appears beside it. */
    private void showMenu(View anchor, android.graphics.drawable.Drawable iconImage, List<Item> items) {
        FrameLayout o = openOverlay(0x33000000);
        o.setOnClickListener(v -> closeOverlay());

        int[] at = new int[2];
        int aw = 0, ah = 0;
        if (anchor != null) {
            anchor.getLocationInWindow(at);
            aw = anchor.getWidth();
            ah = anchor.getHeight();
            ImageView lifted = new ImageView(this);
            lifted.setImageDrawable(iconImage);
            lifted.setElevation(dp(12));
            FrameLayout.LayoutParams llp = new FrameLayout.LayoutParams(aw, ah);
            llp.leftMargin = at[0];
            llp.topMargin = at[1];
            o.addView(lifted, llp);
            lifted.animate().scaleX(1.12f).scaleY(1.12f).setDuration(180).start();
        }

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.rounded(0xF0F2F2F7, dp(14)));
        card.setElevation(dp(16));
        card.setClipToOutline(true);
        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            if (i > 0) {
                View sep = new View(this);
                sep.setBackgroundColor(0x24000000);
                card.addView(sep, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.5f))));
            }
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), 0, dp(16), 0);
            int color = it.destructive ? 0xFFFF3B30 : 0xFF000000;
            TextView t = new TextView(this);
            t.setText(it.title);
            t.setTextSize(17);
            t.setTextColor(color);
            row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            row.addView(glyphView(it.glyph, color, 20), new LinearLayout.LayoutParams(dp(20), dp(20)));
            row.setBackground(new android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(0x22000000), null, null));
            row.setOnClickListener(v -> { closeOverlay(); it.action.run(); });
            card.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)));
        }

        int cardW = dp(250);
        int cardH = items.size() * dp(46) + (items.size() - 1);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(cardW, ViewGroup.LayoutParams.WRAP_CONTENT);
        int screenW = frame.getWidth(), screenH = frame.getHeight();
        if (anchor != null) {
            boolean leftHalf = at[0] + aw / 2 < screenW / 2;
            clp.leftMargin = leftHalf ? Math.max(dp(16), at[0]) : Math.min(screenW - dp(16), at[0] + aw) - cardW;
            int below = at[1] + ah + dp(14);
            clp.topMargin = below + cardH < screenH - dp(40) ? below : at[1] - cardH - dp(14);
            card.setPivotX(leftHalf ? 0 : cardW);
            card.setPivotY(clp.topMargin > at[1] ? 0 : cardH);
        } else {
            clp.leftMargin = (screenW - cardW) / 2;
            clp.topMargin = (screenH - cardH) / 2;
        }
        clp.leftMargin = Math.max(dp(16), clp.leftMargin);
        o.addView(card, clp);
        card.setScaleX(0.6f);
        card.setScaleY(0.6f);
        card.setAlpha(0f);
        card.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(200).start();
    }

    private void hideInVault(Apps.App app) {
        List<String> list = store.apps();
        list.add(app.pkg);
        store.setApps(list);
        Toast.makeText(this, app.label + " is now hidden in your vault.", Toast.LENGTH_SHORT).show();
        reload();
    }

    private void toggleDock(Apps.App app) {
        List<String> pkgs = new ArrayList<>();
        for (Apps.App a : dockApps) pkgs.add(a.pkg);
        if (pkgs.contains(app.pkg)) pkgs.remove(app.pkg);
        else if (pkgs.size() >= DOCK_SIZE) {
            Toast.makeText(this, "The dock is full. Remove an app from it first.", Toast.LENGTH_SHORT).show();
            return;
        } else pkgs.add(app.pkg);
        store.setDock(pkgs);
        reload();
    }

    private void pickWallpaper() {
        Intent i = Build.VERSION.SDK_INT >= 33
                ? new Intent(MediaStore.ACTION_PICK_IMAGES)
                : new Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);
        try { startActivityForResult(i, REQ_WALLPAPER); }
        catch (RuntimeException e) { Toast.makeText(this, "No photo picker found.", Toast.LENGTH_SHORT).show(); }
    }

    private void refreshWallpaper() {
        frame.setBackground(Wallpaper.drawable(false, 0));
        render();
    }

    // ---------------------------------------------------------------- overlays: search, folders, menus

    /** Covers the screen with a blurred picture of the home screen, like iOS does behind menus and search. */
    private FrameLayout openOverlay(int dim) {
        if (overlay != null) frame.removeView(overlay);
        FrameLayout o = new FrameLayout(this);
        ImageView bg = new ImageView(this);
        bg.setScaleType(ImageView.ScaleType.FIT_XY);
        bg.setImageBitmap(blurredSnapshot());
        o.addView(bg, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        View shade = new View(this);
        shade.setBackgroundColor(dim);
        o.addView(shade, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        o.setClickable(true);
        frame.addView(o, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        o.setAlpha(0f);
        o.animate().alpha(1f).setDuration(180).start();
        overlay = o;
        return o;
    }

    private void closeOverlay() {
        FrameLayout o = overlay;
        overlay = null;
        if (o == null) return;
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        if (imm != null) imm.hideSoftInputFromWindow(o.getWindowToken(), 0);
        o.animate().alpha(0f).setDuration(150).withEndAction(() -> frame.removeView(o)).start();
    }

    private Bitmap blurredSnapshot() {
        int scale = 8;
        int w = Math.max(1, frame.getWidth() / scale), h = Math.max(1, frame.getHeight() / scale);
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.scale(1f / scale, 1f / scale);
        frame.draw(c);
        return Wallpaper.blur(b, 4);
    }

    /** Pads an overlay's content for the status bar, navigation bar and keyboard. */
    private void padForBars(View content) {
        content.setOnApplyWindowInsetsListener((v, in) -> {
            Insets bars = in.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
            v.setPadding(bars.left, bars.top + dp(10), bars.right, bars.bottom);
            return in;
        });
        content.post(content::requestApplyInsets);
    }

    private void openSearch() {
        FrameLayout o = openOverlay(0x66000000);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        padForBars(col);

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(16), 0, dp(6), dp(14));
        LinearLayout field = new LinearLayout(this);
        field.setGravity(Gravity.CENTER_VERTICAL);
        field.setBackground(Ui.rounded(0x33FFFFFF, dp(12)));
        field.setPadding(dp(10), 0, dp(10), 0);
        field.addView(glyphView(Glyph.SEARCH, 0xCCFFFFFF, 16), new LinearLayout.LayoutParams(dp(16), dp(16)));
        EditText input = new EditText(this);
        input.setHint("Search");
        input.setHintTextColor(0x99FFFFFF);
        input.setTextColor(0xFFFFFFFF);
        input.setTextSize(17);
        input.setSingleLine(true);
        input.setBackground(null);
        input.setImeOptions(EditorInfo.IME_ACTION_GO);
        input.setPadding(dp(8), dp(9), 0, dp(9));
        field.addView(input, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        bar.addView(field, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView cancel = label("Cancel", 17, 0xFFFFFFFF);
        cancel.setShadowLayer(0, 0, 0, 0);
        cancel.setPadding(dp(12), dp(10), dp(10), dp(10));
        cancel.setOnClickListener(v -> closeOverlay());
        bar.addView(cancel);
        col.addView(bar);

        TextView header = label("Apps", 20, 0xFFFFFFFF);
        header.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        header.setShadowLayer(0, 0, 0, 0);
        header.setPadding(dp(20), dp(4), dp(20), dp(10));
        col.addView(header);

        List<Apps.App> results = new ArrayList<>();
        GridView grid = iconGrid(results);
        col.addView(grid, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        Runnable filter = () -> {
            String q = input.getText().toString().trim().toLowerCase(Locale.ROOT);
            results.clear();
            List<Apps.App> contains = new ArrayList<>();
            for (Apps.App a : visible) {
                String name = (a == vaultTile ? "Nico Pan Vault" : a.label).toLowerCase(Locale.ROOT);
                if (q.isEmpty() || name.startsWith(q)) results.add(a);
                else if (name.contains(q)) contains.add(a);
            }
            results.addAll(contains);
            header.setText(results.isEmpty() ? "No Results" : "Apps");
            ((BaseAdapter) grid.getAdapter()).notifyDataSetChanged();
        };
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { filter.run(); }
        });
        input.setOnEditorActionListener((v, action, ev) -> {
            if (!results.isEmpty()) launch(results.get(0), null);
            return true;
        });
        filter.run();
        o.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        input.requestFocus();
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        if (imm != null) input.postDelayed(() -> imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT), 120);
    }

    /** An App Library group opened full size: its name and a frosted panel of all its apps. */
    private void openFolder(String name, List<Apps.App> apps) {
        FrameLayout o = openOverlay(0x40000000);
        o.setOnClickListener(v -> closeOverlay());
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        padForBars(col);
        TextView title = label(name, 28, 0xFFFFFFFF);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        title.setGravity(Gravity.CENTER);
        title.setPadding(dp(20), 0, dp(20), dp(18));
        col.addView(title);
        FrameLayout panel = new FrameLayout(this);
        panel.setBackground(Ui.rounded(0x33FFFFFF, dp(36)));
        panel.setPadding(dp(8), dp(18), dp(8), dp(8));
        panel.setClickable(true); // taps inside the panel don't close it
        GridView grid = iconGrid(apps);
        int rows = (apps.size() + COLUMNS - 1) / COLUMNS;
        int gridH = Math.min(rows * dp(98), (int) (frame.getHeight() * 0.6f));
        panel.addView(grid, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, gridH));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.setMargins(dp(24), 0, dp(24), dp(60));
        col.addView(panel, plp);
        o.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        panel.setScaleX(0.85f);
        panel.setScaleY(0.85f);
        panel.animate().scaleX(1f).scaleY(1f).setDuration(220).start();
    }

    private GridView iconGrid(List<Apps.App> apps) {
        GridView grid = new GridView(this);
        grid.setNumColumns(COLUMNS);
        grid.setVerticalSpacing(dp(18));
        grid.setSelector(android.R.color.transparent);
        grid.setVerticalScrollBarEnabled(false);
        grid.setPadding(dp(8), 0, dp(8), dp(16));
        grid.setClipToPadding(false);
        grid.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return apps.size(); }
            @Override public Object getItem(int i) { return apps.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int i, View convert, ViewGroup parent) { return iconCell(apps.get(i), 60, true); }
        });
        return grid;
    }

    // ---------------------------------------------------------------- helpers

    private int dp(float v) { return Ui.dp(this, v); }

    private TextView label(String s, float sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setShadowLayer(dp(2), 0, dp(0.5f), 0x66000000);
        return t;
    }

    private View glyphView(int type, int color, int sizeDp) {
        ImageView iv = new ImageView(this);
        iv.setImageDrawable(new Glyph(type, color));
        iv.setMinimumWidth(dp(sizeDp));
        iv.setMinimumHeight(dp(sizeDp));
        return iv;
    }

    /** Horizontally paged container: one screen-wide page at a time, snapping like the iPhone home screen. */
    static final class Pager extends HorizontalScrollView {
        interface PageListener { void pageChanged(int page); }

        PageListener pageChanged;
        Runnable scrolled;
        int page;
        private final LinearLayout strip;
        private boolean flung;

        Pager(Context c) {
            super(c);
            setHorizontalScrollBarEnabled(false);
            setOverScrollMode(OVER_SCROLL_NEVER);
            setFillViewport(true);
            strip = new LinearLayout(c);
            addView(strip, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }

        int pageCount() { return strip.getChildCount(); }

        void setPages(List<View> pages) {
            strip.removeAllViews();
            int w = getWidth();
            for (View p : pages) strip.addView(p, new LinearLayout.LayoutParams(w > 0 ? w : ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            int target = Math.min(page, pages.size() - 1);
            post(() -> goTo(target, false));
        }

        void goTo(int target, boolean animate) {
            target = Math.max(0, Math.min(target, pageCount() - 1));
            int x = target * getWidth();
            if (animate) smoothScrollTo(x, 0); else scrollTo(x, 0);
            if (target != page) { page = target; if (pageChanged != null) pageChanged.pageChanged(page); }
        }

        @Override protected void onScrollChanged(int l, int t, int ol, int ot) {
            super.onScrollChanged(l, t, ol, ot);
            if (scrolled != null && l != ol) scrolled.run();
        }

        @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) {
            super.onSizeChanged(w, h, oldW, oldH);
            for (int i = 0; i < strip.getChildCount(); i++) strip.getChildAt(i).getLayoutParams().width = w;
            strip.requestLayout();
            post(() -> goTo(page, false));
        }

        @Override public boolean onTouchEvent(MotionEvent ev) {
            if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) flung = false;
            boolean handled = super.onTouchEvent(ev);
            int a = ev.getActionMasked();
            if ((a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) && !flung && getWidth() > 0) {
                goTo(Math.round(getScrollX() / (float) getWidth()), true);
            }
            return handled;
        }

        /** A quick swipe turns one page in its direction instead of coasting freely. */
        @Override public void fling(int velocityX) {
            flung = true;
            if (getWidth() == 0) return;
            int base = getScrollX() / getWidth(); // page whose left edge is at or before the view
            int target = velocityX > 0 ? base + 1 : velocityX < 0 ? base : Math.round(getScrollX() / (float) getWidth());
            goTo(target, true);
        }
    }
}
