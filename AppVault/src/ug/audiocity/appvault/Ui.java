package ug.audiocity.appvault;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.Button;
import android.widget.TextView;

/** Shared look: dark background, lime accent, matching the app icon. */
final class Ui {
    static final int BG = 0xFF121719;
    static final int CARD = 0xFF1E2629;
    static final int ACCENT = 0xFFA5E76F;
    static final int TEXT = 0xFFEFF3F0;
    static final int MUTED = 0xFF9AA6A0;

    private Ui() {}

    static int dp(Context c, float v) { return Math.round(v * c.getResources().getDisplayMetrics().density); }

    static TextView text(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    static GradientDrawable rounded(int color, float radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusPx);
        return g;
    }

    static Button primary(Context c, String label) {
        Button b = new Button(c);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(17);
        b.setTextColor(BG);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setBackground(rounded(ACCENT, dp(c, 28)));
        b.setPadding(dp(c, 28), dp(c, 14), dp(c, 28), dp(c, 14));
        b.setGravity(Gravity.CENTER);
        return b;
    }

    static Button quiet(Context c, String label) {
        Button b = new Button(c);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setTextColor(ACCENT);
        b.setBackground(null);
        return b;
    }

    /** A header icon button; contentDescription keeps it usable with TalkBack. */
    static TextView iconButton(Context c, String glyph, String description) {
        TextView t = text(c, glyph, 22, TEXT, false);
        t.setGravity(Gravity.CENTER);
        t.setContentDescription(description);
        int p = dp(c, 10);
        t.setPadding(p, p, p, p);
        t.setClickable(true);
        t.setFocusable(true);
        return t;
    }
}
