package ug.audiocity.appvault;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Shown by App Guard over a vaulted app opened from outside the vault. */
public final class LockActivity extends Activity {
    static final String EXTRA_PACKAGE = "pkg";
    static volatile boolean showing;

    private VaultStore store;
    private String targetPkg;
    private String targetLabel = "this app";
    private TextView status;
    private CancellationSignal pending;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);
        store = new VaultStore(this);
        targetPkg = getIntent().getStringExtra(EXTRA_PACKAGE);

        Drawable icon = null;
        try {
            PackageManager pm = getPackageManager();
            icon = pm.getApplicationIcon(targetPkg);
            targetLabel = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(targetPkg, 0)));
        } catch (Exception ignored) { /* uninstalled meanwhile: generic text */ }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Ui.BG);
        int pad = Ui.dp(this, 32);
        root.setPadding(pad, pad, pad, pad);
        ImageView iv = new ImageView(this);
        if (icon != null) iv.setImageDrawable(icon); else iv.setImageResource(getApplicationInfo().icon);
        root.addView(iv, new LinearLayout.LayoutParams(Ui.dp(this, 88), Ui.dp(this, 88)));
        TextView title = Ui.text(this, targetLabel + " is locked", 22, Ui.TEXT, true);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, Ui.dp(this, 18), 0, Ui.dp(this, 6));
        root.addView(title);
        status = Ui.text(this, "It's in your AppVault", 15, Ui.MUTED, false);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, 0, 0, Ui.dp(this, 28));
        root.addView(status);
        Button unlock = Ui.primary(this, "Unlock with fingerprint");
        unlock.setOnClickListener(v -> ask());
        root.addView(unlock);
        Button leave = Ui.quiet(this, "Go to home screen");
        leave.setOnClickListener(v -> goHome());
        root.addView(leave);
        setContentView(root);
    }

    @Override protected void onStart() {
        super.onStart();
        showing = true;
        if (Session.SHARED.returned(store.graceMs())) { finish(); return; }
        ask();
    }

    @Override protected void onStop() {
        showing = false;
        super.onStop();
    }

    @Override protected void onDestroy() {
        if (pending != null) pending.cancel();
        super.onDestroy();
    }

    @Override public void onBackPressed() { goHome(); }

    private void ask() {
        if (pending != null) return;
        String problem = Biometrics.problem(this, store);
        if (problem != null) { status.setText(problem); return; }
        pending = Biometrics.prompt(this, store, "Touch the sensor to open " + targetLabel, new Biometrics.Result() {
            @Override public void unlocked() {
                pending = null;
                if (targetPkg != null) Apps.open(LockActivity.this, targetPkg);
                finish();
            }
            @Override public void failed(CharSequence message) {
                pending = null;
                status.setText(message);
            }
        });
    }

    private void goHome() {
        startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        finish();
    }
}
