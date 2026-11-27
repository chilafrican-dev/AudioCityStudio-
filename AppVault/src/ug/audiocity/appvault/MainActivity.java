package ug.audiocity.appvault;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** The vault: locked until the fingerprint is accepted, then a grid of the user's chosen apps. */
public final class MainActivity extends Activity {
    private final Session session = Session.SHARED;
    private VaultStore store;

    private LinearLayout lockView;
    private TextView lockStatus;
    private android.widget.Button unlockButton;
    private android.widget.Button enrollButton;

    private LinearLayout vaultView;
    private TextView guardBanner;
    private GridView grid;
    private TextView emptyText;
    private final List<Apps.App> vaultApps = new ArrayList<>();
    private final AppGridAdapter gridAdapter = new AppGridAdapter();

    private CancellationSignal pendingPrompt;
    private boolean launchingVaultApp;
    private boolean started;

    private final BroadcastReceiver screenOff = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { session.lock(); showLocked(null); }
    };

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        // Keeps the vault out of screenshots and the recent-apps preview.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);
        store = new VaultStore(this);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);
        root.addView(buildLockView());
        root.addView(buildVaultView());
        setContentView(root);
        registerReceiver(screenOff, new IntentFilter(Intent.ACTION_SCREEN_OFF));
    }

    @Override protected void onDestroy() {
        unregisterReceiver(screenOff);
        cancelPrompt();
        super.onDestroy();
    }

    @Override protected void onStart() {
        super.onStart();
        started = true;
        if (session.returned(store.graceMs())) showVault();
        else { showLocked(null); askForFingerprint(); }
    }

    @Override protected void onStop() {
        started = false;
        // The prompt is not cancelled here: the PIN backup screen is its own activity and stops this one.
        // With App Guard on, opening a vaulted app keeps the session going: Guard sees the app and
        // starts the countdown when the user leaves it. Without Guard, the countdown starts now.
        if (!(launchingVaultApp && Apps.guardEnabled(this))) session.left();
        launchingVaultApp = false;
        super.onStop();
    }

    // ---------------------------------------------------------------- lock screen

    private View buildLockView() {
        lockView = new LinearLayout(this);
        lockView.setOrientation(LinearLayout.VERTICAL);
        lockView.setGravity(Gravity.CENTER);
        int pad = Ui.dp(this, 32);
        lockView.setPadding(pad, pad, pad, pad);

        ImageView icon = new ImageView(this);
        icon.setImageResource(getApplicationInfo().icon);
        icon.setContentDescription(null);
        lockView.addView(icon, new LinearLayout.LayoutParams(Ui.dp(this, 112), Ui.dp(this, 112)));

        TextView title = Ui.text(this, "Nico Pan Vault", 28, Ui.TEXT, true);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, Ui.dp(this, 16), 0, Ui.dp(this, 6));
        lockView.addView(title);

        lockStatus = Ui.text(this, "Locked", 16, Ui.MUTED, false);
        lockStatus.setGravity(Gravity.CENTER);
        lockStatus.setPadding(0, 0, 0, Ui.dp(this, 28));
        lockView.addView(lockStatus);

        unlockButton = Ui.primary(this, "Unlock with fingerprint");
        unlockButton.setOnClickListener(v -> askForFingerprint());
        lockView.addView(unlockButton);

        enrollButton = Ui.quiet(this, "Set up a fingerprint");
        enrollButton.setOnClickListener(v -> Biometrics.openEnrollment(this, store));
        enrollButton.setVisibility(View.GONE);
        lockView.addView(enrollButton);
        return lockView;
    }

    private void showLocked(CharSequence message) {
        grid.setAdapter(null); // drop app icons from the view tree while locked
        vaultView.setVisibility(View.GONE);
        lockView.setVisibility(View.VISIBLE);
        String problem = Biometrics.problem(this, store);
        lockStatus.setText(problem != null ? problem : message != null ? message : "Locked");
        enrollButton.setVisibility(problem != null && problem.startsWith("No fingerprint") ? View.VISIBLE : View.GONE);
        unlockButton.setEnabled(problem == null || problem.contains("busy"));
    }

    private void askForFingerprint() {
        if (!started || pendingPrompt != null) return;
        String problem = Biometrics.problem(this, store);
        if (problem != null) { showLocked(problem); return; }
        pendingPrompt = Biometrics.prompt(this, store, "Touch the sensor to open your apps", new Biometrics.Result() {
            @Override public void unlocked() { pendingPrompt = null; showVault(); }
            @Override public void failed(CharSequence message) { pendingPrompt = null; showLocked(message); }
        });
    }

    private void cancelPrompt() {
        if (pendingPrompt != null) { pendingPrompt.cancel(); pendingPrompt = null; }
    }

    // ---------------------------------------------------------------- vault

    private View buildVaultView() {
        vaultView = new LinearLayout(this);
        vaultView.setOrientation(LinearLayout.VERTICAL);
        vaultView.setVisibility(View.GONE);
        int side = Ui.dp(this, 12);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(Ui.dp(this, 20), Ui.dp(this, 16), side, Ui.dp(this, 8));
        TextView title = Ui.text(this, "My Vault", 26, Ui.TEXT, true);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView add = Ui.iconButton(this, "＋", "Add apps");
        add.setTextColor(Ui.ACCENT);
        add.setOnClickListener(v -> showPicker());
        header.addView(add);
        TextView settings = Ui.iconButton(this, "⚙", "Settings");
        settings.setOnClickListener(v -> showSettings());
        header.addView(settings);
        TextView lockNow = Ui.iconButton(this, "🔒", "Lock now");
        lockNow.setOnClickListener(v -> { session.lock(); showLocked("Locked"); });
        header.addView(lockNow);
        vaultView.addView(header);

        guardBanner = Ui.text(this, "", 14, Ui.TEXT, false);
        guardBanner.setBackground(Ui.rounded(Ui.CARD, Ui.dp(this, 14)));
        int bp = Ui.dp(this, 14);
        guardBanner.setPadding(bp, bp, bp, bp);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.setMargins(Ui.dp(this, 16), 0, Ui.dp(this, 16), Ui.dp(this, 8));
        vaultView.addView(guardBanner, blp);

        FrameLayout body = new FrameLayout(this);
        grid = new GridView(this);
        grid.setNumColumns(GridView.AUTO_FIT);
        grid.setColumnWidth(Ui.dp(this, 84));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setVerticalSpacing(Ui.dp(this, 14));
        grid.setPadding(side, Ui.dp(this, 8), side, Ui.dp(this, 24));
        grid.setClipToPadding(false);
        grid.setSelector(android.R.color.transparent);
        grid.setOnItemClickListener((p, v, pos, id) -> openApp(vaultApps.get(pos)));
        grid.setOnItemLongClickListener((p, v, pos, id) -> { confirmRemove(vaultApps.get(pos)); return true; });
        body.addView(grid);

        emptyText = Ui.text(this, "Your vault is empty.\nTap ＋ to add the apps you want to keep behind your fingerprint.", 16, Ui.MUTED, false);
        emptyText.setGravity(Gravity.CENTER);
        emptyText.setPadding(Ui.dp(this, 40), 0, Ui.dp(this, 40), Ui.dp(this, 80));
        body.addView(emptyText, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        vaultView.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        return vaultView;
    }

    private void showVault() {
        lockView.setVisibility(View.GONE);
        vaultView.setVisibility(View.VISIBLE);
        updateBanner();
        grid.setAdapter(gridAdapter);
        reloadVault();
    }

    /** Points the user at whatever still lets vault apps be seen or opened outside the vault. */
    private void updateBanner() {
        if (!Apps.isDefaultHome(this)) {
            guardBanner.setText("These apps still show on your home screen. Tap to hide them by making Nico Pan Vault your home screen.");
            guardBanner.setOnClickListener(v -> explainHiding());
            guardBanner.setVisibility(View.VISIBLE);
        } else if (Apps.guardAvailable(this) && !Apps.guardEnabled(this)) {
            guardBanner.setText("Turn on App Guard so these apps also ask for your fingerprint when opened from recent apps. Tap to set up.");
            guardBanner.setOnClickListener(v -> explainGuard());
            guardBanner.setVisibility(View.VISIBLE);
        } else {
            guardBanner.setVisibility(View.GONE);
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (vaultView.getVisibility() == View.VISIBLE) updateBanner();
    }

    private void explainHiding() {
        new AlertDialog.Builder(this)
                .setTitle("Hide vault apps")
                .setMessage("Android doesn't let one app hide another app's icon from your phone's home screen. "
                        + "So Nico Pan Vault can be your home screen instead: it shows all your apps except the ones in the vault, "
                        + "and those only appear here after your fingerprint.\n\n"
                        + "On the next screen choose Nico Pan Vault (if asked, choose \"Always\"). "
                        + "Your current home screen's layout and widgets are kept, and you can switch back any time in Settings > Apps > Choose default apps > Home app.\n\n"
                        + "Vault apps can still be found in Settings > Apps and in the Play Store.")
                .setPositiveButton("Make Nico Pan Vault my home screen", (d, w) -> Apps.requestDefaultHome(this))
                .setNegativeButton("Not now", null)
                .show();
    }

    private void reloadVault() {
        new Thread(() -> {
            List<Apps.App> apps = Apps.inVault(this, store);
            runOnUiThread(() -> {
                vaultApps.clear();
                vaultApps.addAll(apps);
                gridAdapter.notifyDataSetChanged();
                emptyText.setVisibility(apps.isEmpty() ? View.VISIBLE : View.GONE);
            });
        }, "vault-load").start();
    }

    private void openApp(Apps.App app) {
        launchingVaultApp = true;
        if (!Apps.open(this, app.pkg)) {
            launchingVaultApp = false;
            Toast.makeText(this, app.label + " can no longer be opened.", Toast.LENGTH_SHORT).show();
        }
    }

    private void confirmRemove(Apps.App app) {
        new AlertDialog.Builder(this)
                .setTitle("Remove " + app.label + "?")
                .setMessage("It leaves the vault and no longer needs your fingerprint. The app itself is not uninstalled.")
                .setPositiveButton("Remove", (d, w) -> { store.remove(app.pkg); reloadVault(); })
                .setNegativeButton("Keep", null)
                .show();
    }

    private final class AppGridAdapter extends BaseAdapter {
        @Override public int getCount() { return vaultApps.size(); }
        @Override public Object getItem(int i) { return vaultApps.get(i); }
        @Override public long getItemId(int i) { return i; }
        @Override public View getView(int i, View convert, ViewGroup parent) {
            Context c = MainActivity.this;
            LinearLayout cell = (LinearLayout) convert;
            if (cell == null) {
                cell = new LinearLayout(c);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setGravity(Gravity.CENTER_HORIZONTAL);
                cell.setPadding(0, Ui.dp(c, 6), 0, Ui.dp(c, 6));
                cell.addView(new ImageView(c), new LinearLayout.LayoutParams(Ui.dp(c, 56), Ui.dp(c, 56)));
                TextView label = Ui.text(c, "", 13, Ui.TEXT, false);
                label.setGravity(Gravity.CENTER);
                label.setMaxLines(2);
                label.setPadding(Ui.dp(c, 2), Ui.dp(c, 6), Ui.dp(c, 2), 0);
                cell.addView(label);
            }
            Apps.App app = vaultApps.get(i);
            ((ImageView) cell.getChildAt(0)).setImageDrawable(app.icon);
            ((TextView) cell.getChildAt(1)).setText(app.label);
            cell.setContentDescription("Open " + app.label);
            return cell;
        }
    }

    // ---------------------------------------------------------------- choosing apps

    private void showPicker() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 16);
        box.setPadding(p, Ui.dp(this, 8), p, 0);
        EditText search = new EditText(this);
        search.setHint("Search apps");
        search.setSingleLine(true);
        box.addView(search);
        ProgressBar loading = new ProgressBar(this);
        box.addView(loading);
        ListView list = new ListView(this);
        box.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 420)));

        Set<String> chosen = new HashSet<>(store.apps());
        List<Apps.App> all = new ArrayList<>();
        List<Apps.App> shown = new ArrayList<>();
        BaseAdapter adapter = new BaseAdapter() {
            @Override public int getCount() { return shown.size(); }
            @Override public Object getItem(int i) { return shown.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int i, View convert, ViewGroup parent) {
                Context c = MainActivity.this;
                LinearLayout row = (LinearLayout) convert;
                if (row == null) {
                    row = new LinearLayout(c);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setPadding(0, Ui.dp(c, 8), 0, Ui.dp(c, 8));
                    row.addView(new ImageView(c), new LinearLayout.LayoutParams(Ui.dp(c, 40), Ui.dp(c, 40)));
                    TextView label = new TextView(c);
                    label.setTextSize(16);
                    label.setPadding(Ui.dp(c, 14), 0, Ui.dp(c, 8), 0);
                    row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                    CheckBox box = new CheckBox(c);
                    box.setClickable(false);
                    box.setFocusable(false);
                    row.addView(box);
                }
                Apps.App app = shown.get(i);
                ((ImageView) row.getChildAt(0)).setImageDrawable(app.icon);
                ((TextView) row.getChildAt(1)).setText(app.label);
                ((CheckBox) row.getChildAt(2)).setChecked(chosen.contains(app.pkg));
                return row;
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, v, pos, id) -> {
            String pkg = shown.get(pos).pkg;
            if (!chosen.remove(pkg)) chosen.add(pkg);
            adapter.notifyDataSetChanged();
        });
        Runnable filter = () -> {
            String q = search.getText().toString().trim().toLowerCase(Locale.ROOT);
            shown.clear();
            for (Apps.App a : all) if (q.isEmpty() || a.label.toLowerCase(Locale.ROOT).contains(q)) shown.add(a);
            adapter.notifyDataSetChanged();
        };
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { filter.run(); }
        });

        new AlertDialog.Builder(this)
                .setTitle("Choose apps for your vault")
                .setView(box)
                .setPositiveButton("Save", (d, w) -> {
                    // Keep existing order, then append newly chosen apps alphabetically.
                    List<String> order = new ArrayList<>();
                    for (String pkg : store.apps()) if (chosen.contains(pkg)) order.add(pkg);
                    for (Apps.App a : all) if (chosen.contains(a.pkg) && !order.contains(a.pkg)) order.add(a.pkg);
                    store.setApps(order);
                    reloadVault();
                })
                .setNegativeButton("Cancel", null)
                .show();

        new Thread(() -> {
            List<Apps.App> apps = Apps.installed(this);
            runOnUiThread(() -> { all.addAll(apps); loading.setVisibility(View.GONE); filter.run(); });
        }, "picker-load").start();
    }

    // ---------------------------------------------------------------- settings

    private void showSettings() {
        String guard = !Apps.guardAvailable(this) ? "App Guard: not in this version"
                : "App Guard: " + (Apps.guardEnabled(this) ? "On" : "Off");
        String[] items = {
                "Lock again: " + VaultStore.graceLabel(store.graceMs()),
                "Phone PIN as backup: " + (store.allowDeviceCredential() ? "On" : "Off"),
                "Hide vault apps from home screen: " + (Apps.isDefaultHome(this) ? "On" : "Off"),
                guard,
        };
        new AlertDialog.Builder(this)
                .setTitle("Settings")
                .setItems(items, (d, which) -> {
                    if (which == 0) chooseGrace();
                    else if (which == 1) togglePinBackup();
                    else if (which == 2) {
                        if (Apps.isDefaultHome(this)) startActivity(new Intent(Settings.ACTION_HOME_SETTINGS));
                        else explainHiding();
                    }
                    else explainGuard();
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private void chooseGrace() {
        int current = 0;
        for (int i = 0; i < VaultStore.GRACE_OPTIONS_MS.length; i++) if (VaultStore.GRACE_OPTIONS_MS[i] == store.graceMs()) current = i;
        new AlertDialog.Builder(this)
                .setTitle("Lock again after leaving")
                .setSingleChoiceItems(VaultStore.GRACE_LABELS, current, (d, which) -> {
                    store.setGraceMs(VaultStore.GRACE_OPTIONS_MS[which]);
                    d.dismiss();
                })
                .show();
    }

    private void togglePinBackup() {
        boolean turnOn = !store.allowDeviceCredential();
        new AlertDialog.Builder(this)
                .setTitle(turnOn ? "Allow phone PIN as backup?" : "Fingerprint only?")
                .setMessage(turnOn
                        ? "If your fingerprint isn't recognised, you can unlock with your phone's PIN, pattern or password."
                        : "Only a fingerprint will unlock the vault. If the sensor stops working or locks after too many tries, you will have to wait before trying again.")
                .setPositiveButton(turnOn ? "Allow" : "Fingerprint only", (d, w) -> store.setAllowDeviceCredential(turnOn))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void explainGuard() {
        if (!Apps.guardAvailable(this)) {
            new AlertDialog.Builder(this)
                    .setTitle("App Guard")
                    .setMessage("This version of Nico Pan Vault doesn't include App Guard, so apps in your vault still open normally from the home screen. "
                            + "App Guard needs Android's Accessibility access, and Google Play Protect blocks apps that ask for it unless they come from the Play Store.\n\n"
                            + "The separate Nico Pan Vault Guard download includes it.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("App Guard")
                .setMessage("Without App Guard, the apps in your vault can still be opened from the home screen. "
                        + "App Guard watches which app comes on screen, and when it's one of your vault apps it asks for your fingerprint first. "
                        + "It does not read what's inside apps and sends nothing anywhere.\n\n"
                        + "On the next screen, open \"Installed apps\" (or \"Downloaded apps\"), choose Nico Pan Vault App Guard and switch it on.\n\n"
                        + "If Android says the setting is restricted: open Settings > Apps > Nico Pan Vault, tap ⋮ at the top right, choose \"Allow restricted settings\", then come back and switch it on.")
                .setPositiveButton("Open Accessibility settings", (d, w) -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)))
                .setNegativeButton("Not now", null)
                .show();
    }
}
