package ug.audiocity.appvault;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.Menu;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Nico Pan Vault's iPhone-style home screen: swipeable pages of rounded-square icons (4 across,
 * 6 down), page dots, a Search button and a translucent dock of up to four apps. Apps in the vault
 * never appear here, so they are only reachable through the vault and its fingerprint lock.
 */
public final class HomeActivity extends Activity {
    private static final int COLUMNS = 4;
    private static final int ROWS = 6;
    private static final int DOCK_SIZE = 4;

    private VaultStore store;
    private Apps.App vaultTile;
    /** Everything shown on the home screen (pages and dock), vault tile first. Never contains vault apps. */
    private final List<Apps.App> visible = new ArrayList<>();
    private final List<Apps.App> dockApps = new ArrayList<>();

    private Pager pager;
    private LinearLayout dots;
    private LinearLayout dock;
    private FrameLayout searchOverlay;
    private EditText searchField;
    private final List<Apps.App> results = new ArrayList<>();
    private final ResultsAdapter resultsAdapter = new ResultsAdapter();

    private final BroadcastReceiver packagesChanged = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { reload(); }
    };

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        store = new VaultStore(this);
        vaultTile = new Apps.App(getPackageName(), "Vault",
                Apps.rounded(getResources(), getDrawable(getApplicationInfo().icon), true));

        // Draw under the transparent status and navigation bars, like iOS, and pad for them by hand.
        getWindow().setDecorFitsSystemWindows(false);
        getWindow().setStatusBarColor(0x00000000);
        getWindow().setNavigationBarColor(0x00000000);

        FrameLayout frame = new FrameLayout(this);
        frame.addView(buildHome());
        frame.addView(buildSearch());
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

    /** Pressing Home while already home closes search and returns to the first page, as on iPhone. */
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (searchOverlay.getVisibility() == View.VISIBLE) closeSearch();
        else pager.goTo(0, true);
    }

    @Override public void onBackPressed() {
        if (searchOverlay.getVisibility() == View.VISIBLE) closeSearch();
        else pager.goTo(0, true);
    }

    // ---------------------------------------------------------------- layout

    private View buildHome() {
        LinearLayout home = new LinearLayout(this);
        home.setOrientation(LinearLayout.VERTICAL);
        home.setOnApplyWindowInsetsListener((v, in) -> {
            Insets bars = in.getInsets(WindowInsets.Type.systemBars());
            v.setPadding(bars.left, bars.top + dp(12), bars.right, bars.bottom + dp(8));
            return in;
        });

        pager = new Pager(this);
        pager.listener = this::updateDots;
        home.addView(pager, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        dots = new LinearLayout(this);
        dots.setGravity(Gravity.CENTER);
        home.addView(dots, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(18)));

        TextView search = text("🔍  Search", 14);
        search.setGravity(Gravity.CENTER);
        search.setBackground(Ui.rounded(0x4DFFFFFF, dp(16)));
        search.setPadding(dp(14), dp(6), dp(14), dp(6));
        search.setContentDescription("Search apps");
        search.setOnClickListener(v -> openSearch());
        pressEffect(search);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.gravity = Gravity.CENTER_HORIZONTAL;
        slp.setMargins(0, dp(6), 0, dp(12));
        home.addView(search, slp);

        dock = new LinearLayout(this);
        dock.setGravity(Gravity.CENTER);
        dock.setBackground(Ui.rounded(0x40FFFFFF, dp(32)));
        dock.setPadding(dp(8), dp(12), dp(8), dp(12));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.setMargins(dp(10), 0, dp(10), 0);
        home.addView(dock, dlp);
        return home;
    }

    private View buildSearch() {
        searchOverlay = new FrameLayout(this);
        searchOverlay.setBackgroundColor(0xE6101214);
        searchOverlay.setVisibility(View.GONE);
        searchOverlay.setClickable(true); // swallow taps so they don't reach icons underneath

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setOnApplyWindowInsetsListener((v, in) -> {
            Insets bars = in.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
            v.setPadding(bars.left, bars.top + dp(12), bars.right, bars.bottom);
            return in;
        });

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(16), 0, dp(8), dp(10));
        searchField = new EditText(this);
        searchField.setHint("Search");
        searchField.setHintTextColor(0x99FFFFFF);
        searchField.setTextColor(0xFFFFFFFF);
        searchField.setTextSize(17);
        searchField.setSingleLine(true);
        searchField.setImeOptions(EditorInfo.IME_ACTION_GO);
        searchField.setBackground(Ui.rounded(0x33FFFFFF, dp(12)));
        searchField.setPadding(dp(14), dp(10), dp(14), dp(10));
        searchField.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { filterResults(); }
        });
        searchField.setOnEditorActionListener((v, action, ev) -> {
            if (!results.isEmpty()) launch(results.get(0), null);
            return true;
        });
        bar.addView(searchField, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView cancel = text("Cancel", 17);
        cancel.setTextColor(Ui.ACCENT);
        cancel.setShadowLayer(0, 0, 0, 0);
        cancel.setPadding(dp(12), dp(10), dp(8), dp(10));
        cancel.setOnClickListener(v -> closeSearch());
        bar.addView(cancel);
        col.addView(bar);

        ListView list = new ListView(this);
        list.setDivider(null);
        list.setSelector(android.R.color.transparent);
        list.setAdapter(resultsAdapter);
        list.setOnItemClickListener((p, v, pos, id) -> launch(results.get(pos), v));
        col.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        searchOverlay.addView(col);
        return searchOverlay;
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
        // Pages hold every visible app that isn't in the dock, in alphabetical order after the vault.
        List<Apps.App> onPages = new ArrayList<>();
        for (Apps.App a : visible) if (!dockApps.contains(a)) onPages.add(a);
        int perPage = COLUMNS * ROWS;
        int pageCount = Math.max(1, (onPages.size() + perPage - 1) / perPage);
        List<View> pages = new ArrayList<>();
        for (int p = 0; p < pageCount; p++) {
            LinearLayout page = new LinearLayout(this);
            page.setOrientation(LinearLayout.VERTICAL);
            page.setPadding(dp(10), 0, dp(10), 0);
            for (int r = 0; r < ROWS; r++) {
                LinearLayout row = new LinearLayout(this);
                for (int c = 0; c < COLUMNS; c++) {
                    int i = p * perPage + r * COLUMNS + c;
                    View cell = i < onPages.size() ? iconCell(onPages.get(i), true) : new View(this);
                    row.addView(cell, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
                }
                page.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
            }
            pages.add(page);
        }
        pager.setPages(pages);

        dock.removeAllViews();
        for (Apps.App a : dockApps) dock.addView(iconCell(a, false), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        // Keep the dock its usual height when empty.
        if (dockApps.isEmpty()) dock.addView(new View(this), new LinearLayout.LayoutParams(1, dp(60)));
        updateDots(pager.page);
        if (searchOverlay.getVisibility() == View.VISIBLE) filterResults();
    }

    private void updateDots(int current) {
        dots.removeAllViews();
        int count = pager.pageCount();
        if (count < 2) return;
        for (int i = 0; i < count; i++) {
            View dot = new View(this);
            dot.setBackground(Ui.rounded(i == current ? 0xFFFFFFFF : 0x66FFFFFF, dp(4)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(7), dp(7));
            lp.setMargins(dp(4), 0, dp(4), 0);
            dots.addView(dot, lp);
        }
    }

    // ---------------------------------------------------------------- icons

    private View iconCell(Apps.App app, boolean withLabel) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER);
        ImageView icon = new ImageView(this);
        icon.setImageDrawable(app.icon);
        icon.setElevation(dp(2));
        icon.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View v, android.graphics.Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), v.getWidth() * 0.225f);
            }
        });
        cell.addView(icon, new LinearLayout.LayoutParams(dp(60), dp(60)));
        if (withLabel) {
            TextView label = text(app.label, 12);
            label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.END);
            label.setGravity(Gravity.CENTER);
            label.setPadding(dp(2), dp(5), dp(2), 0);
            cell.addView(label, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        cell.setContentDescription(app == vaultTile ? "Nico Pan Vault" : app.label);
        cell.setOnClickListener(v -> launch(app, icon));
        cell.setOnLongClickListener(v -> { menu(app, icon); return true; });
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
        Bundle anim = from == null ? null
                : ActivityOptions.makeScaleUpAnimation(from, 0, 0, from.getWidth(), from.getHeight()).toBundle();
        if (app == vaultTile) startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), anim);
        else if (!Apps.open(this, app.pkg, anim)) Toast.makeText(this, app.label + " can't be opened.", Toast.LENGTH_SHORT).show();
        if (searchOverlay.getVisibility() == View.VISIBLE) closeSearch();
    }

    private void menu(Apps.App app, View anchor) {
        PopupMenu pm = new PopupMenu(this, anchor);
        Menu m = pm.getMenu();
        if (app == vaultTile) {
            m.add(0, 1, 0, "Open Vault");
            m.add(0, 2, 1, "Change Home Screen App");
        } else {
            boolean docked = dockApps.contains(app);
            m.add(0, 3, 0, "Hide in Vault");
            m.add(0, 4, 1, docked ? "Remove from Dock" : "Add to Dock");
            m.add(0, 5, 2, "App Info");
        }
        pm.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: launch(app, anchor); break;
                case 2: startActivity(new Intent(Settings.ACTION_HOME_SETTINGS)); break;
                case 3: {
                    List<String> list = store.apps();
                    list.add(app.pkg);
                    store.setApps(list);
                    Toast.makeText(this, app.label + " is now hidden in your vault.", Toast.LENGTH_SHORT).show();
                    reload();
                    break;
                }
                case 4: toggleDock(app); break;
                case 5: startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", app.pkg, null))); break;
                default: return false;
            }
            return true;
        });
        pm.show();
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

    // ---------------------------------------------------------------- search

    private void openSearch() {
        searchField.setText("");
        filterResults();
        searchOverlay.setAlpha(0f);
        searchOverlay.setVisibility(View.VISIBLE);
        searchOverlay.animate().alpha(1f).setDuration(150).start();
        searchField.requestFocus();
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        if (imm != null) searchField.post(() -> imm.showSoftInput(searchField, InputMethodManager.SHOW_IMPLICIT));
    }

    private void closeSearch() {
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        if (imm != null) imm.hideSoftInputFromWindow(searchField.getWindowToken(), 0);
        searchField.clearFocus();
        searchOverlay.setVisibility(View.GONE);
    }

    /** Matches names starting with the query first, then names containing it. Vault apps are never listed. */
    private void filterResults() {
        String q = searchField.getText().toString().trim().toLowerCase(Locale.ROOT);
        results.clear();
        List<Apps.App> contains = new ArrayList<>();
        for (Apps.App a : visible) {
            String name = (a == vaultTile ? "Nico Pan Vault" : a.label).toLowerCase(Locale.ROOT);
            if (q.isEmpty() || name.startsWith(q)) results.add(a);
            else if (name.contains(q)) contains.add(a);
        }
        results.addAll(contains);
        resultsAdapter.notifyDataSetChanged();
    }

    private final class ResultsAdapter extends BaseAdapter {
        @Override public int getCount() { return results.size(); }
        @Override public Object getItem(int i) { return results.get(i); }
        @Override public long getItemId(int i) { return i; }
        @Override public View getView(int i, View convert, ViewGroup parent) {
            LinearLayout row = (LinearLayout) convert;
            if (row == null) {
                row = new LinearLayout(HomeActivity.this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(20), dp(8), dp(20), dp(8));
                row.addView(new ImageView(HomeActivity.this), new LinearLayout.LayoutParams(dp(42), dp(42)));
                TextView label = text("", 17);
                label.setShadowLayer(0, 0, 0, 0);
                label.setPadding(dp(14), 0, 0, 0);
                row.addView(label);
            }
            Apps.App app = results.get(i);
            ((ImageView) row.getChildAt(0)).setImageDrawable(app.icon);
            ((TextView) row.getChildAt(1)).setText(app == vaultTile ? "Nico Pan Vault" : app.label);
            return row;
        }
    }

    // ---------------------------------------------------------------- helpers

    private int dp(float v) { return Ui.dp(this, v); }

    private TextView text(String s, float sp) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(0xFFFFFFFF);
        t.setShadowLayer(dp(3), 0, dp(1), 0x99000000);
        return t;
    }

    /** Horizontally paged container: one screen-wide page at a time, snapping like the iPhone home screen. */
    static final class Pager extends HorizontalScrollView {
        interface Listener { void pageChanged(int page); }

        Listener listener;
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
            if (target != page) { page = target; if (listener != null) listener.pageChanged(page); }
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
            int from = Math.round(getScrollX() / (float) getWidth());
            int base = getScrollX() / getWidth(); // page whose left edge is at or before the view
            int target = velocityX > 0 ? base + 1 : velocityX < 0 ? base : from;
            goTo(target, true);
        }
    }
}
