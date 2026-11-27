package ug.audiocity.appvault;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * AppVault's home screen. Once chosen as the phone's home app it shows every app except the ones in
 * the vault, so those are only reachable through the vault and its fingerprint lock.
 */
public final class HomeActivity extends Activity {
    private VaultStore store;
    private final List<Apps.App> all = new ArrayList<>();
    private final List<Apps.App> shown = new ArrayList<>();
    private EditText search;
    private final HomeAdapter adapter = new HomeAdapter();

    private final BroadcastReceiver packagesChanged = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { reload(); }
    };

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        store = new VaultStore(this);
        getWindow().setStatusBarColor(0x00000000);
        getWindow().setNavigationBarColor(0x00000000);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0x99000000); // darkens the wallpaper so labels stay readable
        root.setFitsSystemWindows(true);
        int side = Ui.dp(this, 16);

        TextClock clock = new TextClock(this);
        clock.setFormat12Hour("h:mm");
        clock.setFormat24Hour("HH:mm");
        clock.setTextSize(56);
        clock.setTextColor(Ui.TEXT);
        clock.setPadding(Ui.dp(this, 24), Ui.dp(this, 28), side, 0);
        root.addView(clock);
        TextClock date = new TextClock(this);
        date.setFormat12Hour("EEEE, d MMMM");
        date.setFormat24Hour("EEEE, d MMMM");
        date.setTextSize(16);
        date.setTextColor(Ui.MUTED);
        date.setPadding(Ui.dp(this, 26), 0, side, Ui.dp(this, 20));
        root.addView(date);

        search = new EditText(this);
        search.setHint("Search apps");
        search.setHintTextColor(Ui.MUTED);
        search.setTextColor(Ui.TEXT);
        search.setSingleLine(true);
        search.setImeOptions(EditorInfo.IME_ACTION_GO);
        search.setBackground(Ui.rounded(0xCC1E2629, Ui.dp(this, 24)));
        search.setPadding(Ui.dp(this, 20), Ui.dp(this, 12), Ui.dp(this, 20), Ui.dp(this, 12));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { filter(); }
        });
        search.setOnEditorActionListener((v, action, ev) -> {
            boolean go = action == EditorInfo.IME_ACTION_GO || (ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER);
            if (go && !shown.isEmpty()) open(shown.get(0));
            return go;
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.setMargins(side, 0, side, Ui.dp(this, 12));
        root.addView(search, slp);

        GridView grid = new GridView(this);
        grid.setNumColumns(GridView.AUTO_FIT);
        grid.setColumnWidth(Ui.dp(this, 80));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setVerticalSpacing(Ui.dp(this, 12));
        grid.setPadding(Ui.dp(this, 8), 0, Ui.dp(this, 8), Ui.dp(this, 16));
        grid.setClipToPadding(false);
        grid.setSelector(android.R.color.transparent);
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((p, v, pos, id) -> open(shown.get(pos)));
        grid.setOnItemLongClickListener((p, v, pos, id) -> { appMenu(shown.get(pos)); return true; });
        root.addView(grid, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);

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

    /** Pressing Home while already home clears the search, like other launchers. */
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (search != null && search.length() > 0) search.setText("");
        hideKeyboard();
    }

    @Override public void onBackPressed() {
        if (search.length() > 0) search.setText("");
        hideKeyboard();
    }

    private void reload() {
        new Thread(() -> {
            List<Apps.App> visible = new ArrayList<>();
            // The vault itself always comes first; Apps.installed() leaves AppVault out.
            visible.add(new Apps.App(getPackageName(), "AppVault", getDrawable(getApplicationInfo().icon)));
            java.util.Set<String> hidden = new java.util.HashSet<>(store.apps());
            for (Apps.App a : Apps.installed(this)) if (!hidden.contains(a.pkg)) visible.add(a);
            runOnUiThread(() -> { all.clear(); all.addAll(visible); filter(); });
        }, "home-load").start();
    }

    private void filter() {
        String q = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        shown.clear();
        for (Apps.App a : all) if (q.isEmpty() || a.label.toLowerCase(Locale.ROOT).contains(q)) shown.add(a);
        adapter.notifyDataSetChanged();
    }

    private void open(Apps.App app) {
        hideKeyboard();
        if (app.pkg.equals(getPackageName())) {
            startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } else {
            Apps.open(this, app.pkg);
        }
        search.setText("");
    }

    private void appMenu(Apps.App app) {
        if (app.pkg.equals(getPackageName())) {
            new AlertDialog.Builder(this)
                    .setTitle("AppVault")
                    .setItems(new String[]{"Open vault", "Change home screen app"}, (d, which) -> {
                        if (which == 0) open(app);
                        else startActivity(new Intent(Settings.ACTION_HOME_SETTINGS));
                    })
                    .show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(app.label)
                .setItems(new String[]{"Hide in vault", "App info"}, (d, which) -> {
                    if (which == 0) {
                        List<String> list = store.apps();
                        list.add(app.pkg);
                        store.setApps(list);
                        reload();
                    } else {
                        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", app.pkg, null)));
                    }
                })
                .show();
    }

    private void hideKeyboard() {
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        if (imm != null && search != null) imm.hideSoftInputFromWindow(search.getWindowToken(), 0);
        if (search != null) search.clearFocus();
    }

    private final class HomeAdapter extends BaseAdapter {
        @Override public int getCount() { return shown.size(); }
        @Override public Object getItem(int i) { return shown.get(i); }
        @Override public long getItemId(int i) { return i; }
        @Override public View getView(int i, View convert, ViewGroup parent) {
            Context c = HomeActivity.this;
            LinearLayout cell = (LinearLayout) convert;
            if (cell == null) {
                cell = new LinearLayout(c);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setGravity(Gravity.CENTER_HORIZONTAL);
                cell.setPadding(0, Ui.dp(c, 4), 0, Ui.dp(c, 4));
                cell.addView(new ImageView(c), new LinearLayout.LayoutParams(Ui.dp(c, 54), Ui.dp(c, 54)));
                TextView label = Ui.text(c, "", 12, Ui.TEXT, false);
                label.setGravity(Gravity.CENTER);
                label.setMaxLines(2);
                label.setShadowLayer(4, 0, 1, 0xFF000000);
                label.setPadding(Ui.dp(c, 2), Ui.dp(c, 5), Ui.dp(c, 2), 0);
                cell.addView(label);
            }
            Apps.App app = shown.get(i);
            ((ImageView) cell.getChildAt(0)).setImageDrawable(app.icon);
            ((TextView) cell.getChildAt(1)).setText(app.label);
            cell.setContentDescription(app.label);
            return cell;
        }
    }
}
