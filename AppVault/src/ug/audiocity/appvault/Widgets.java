package ug.audiocity.appvault;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;
import java.text.DateFormatSymbols;
import java.util.Calendar;
import java.util.Locale;

/** Home screen widgets drawn in code: an analog clock and this month's calendar, both on white rounded cards. */
final class Widgets {
    private Widgets() {}

    private static float cornerFor(float size) { return size * 0.22f; }

    /** Analog clock: hour and minute hands, an orange second hand, numbers 1–12. */
    static final class Clock extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Runnable tick = new Runnable() {
            @Override public void run() {
                invalidate();
                postDelayed(this, 1000 - System.currentTimeMillis() % 1000);
            }
        };

        Clock(Context c) { super(c); setContentDescription("Clock"); }

        @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); post(tick); }
        @Override protected void onDetachedFromWindow() { removeCallbacks(tick); super.onDetachedFromWindow(); }

        @Override protected void onDraw(Canvas c) {
            float s = Math.min(getWidth(), getHeight());
            float ox = (getWidth() - s) / 2f, oy = (getHeight() - s) / 2f;
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFFFFFFFF);
            c.drawRoundRect(new RectF(ox, oy, ox + s, oy + s), cornerFor(s), cornerFor(s), p);
            float cx = ox + s / 2, cy = oy + s / 2, r = s * 0.42f;

            p.setColor(0xFF1C1C1E);
            p.setTextAlign(Paint.Align.CENTER);
            p.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            p.setTextSize(s * 0.085f);
            for (int n = 1; n <= 12; n++) {
                double a = Math.toRadians(n * 30 - 90);
                float tx = cx + (float) Math.cos(a) * r * 0.82f;
                float ty = cy + (float) Math.sin(a) * r * 0.82f - (p.descent() + p.ascent()) / 2;
                c.drawText(Integer.toString(n), tx, ty, p);
            }
            p.setColor(0x33000000);
            for (int m = 0; m < 60; m++) {
                if (m % 5 == 0) continue;
                double a = Math.toRadians(m * 6);
                c.drawCircle(cx + (float) Math.cos(a) * r, cy + (float) Math.sin(a) * r, s * 0.006f, p);
            }

            Calendar now = Calendar.getInstance();
            float sec = now.get(Calendar.SECOND);
            float min = now.get(Calendar.MINUTE) + sec / 60f;
            float hr = (now.get(Calendar.HOUR) + min / 60f);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStyle(Paint.Style.STROKE);
            p.setColor(0xFF1C1C1E);
            hand(c, cx, cy, hr * 30, r * 0.5f, s * 0.035f);
            hand(c, cx, cy, min * 6, r * 0.78f, s * 0.028f);
            p.setColor(0xFFFF9500);
            hand(c, cx, cy, sec * 6, r * 0.88f, s * 0.012f);
            p.setStyle(Paint.Style.FILL);
            c.drawCircle(cx, cy, s * 0.022f, p);
            p.setColor(0xFFFFFFFF);
            c.drawCircle(cx, cy, s * 0.008f, p);
        }

        private void hand(Canvas c, float cx, float cy, float deg, float len, float width) {
            double a = Math.toRadians(deg - 90);
            p.setStrokeWidth(width);
            c.drawLine(cx, cy, cx + (float) Math.cos(a) * len, cy + (float) Math.sin(a) * len, p);
        }
    }

    /** This month's calendar with today circled in red. */
    static final class Month extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int drawnDay = -1;
        private final Runnable check = new Runnable() {
            @Override public void run() {
                if (Calendar.getInstance().get(Calendar.DAY_OF_YEAR) != drawnDay) invalidate();
                postDelayed(this, 60_000);
            }
        };

        Month(Context c) { super(c); setContentDescription("Calendar"); }

        @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); post(check); }
        @Override protected void onDetachedFromWindow() { removeCallbacks(check); super.onDetachedFromWindow(); }

        @Override protected void onDraw(Canvas c) {
            float s = Math.min(getWidth(), getHeight());
            float ox = (getWidth() - s) / 2f, oy = (getHeight() - s) / 2f;
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFFFFFFFF);
            c.drawRoundRect(new RectF(ox, oy, ox + s, oy + s), cornerFor(s), cornerFor(s), p);

            Calendar now = Calendar.getInstance();
            drawnDay = now.get(Calendar.DAY_OF_YEAR);
            int today = now.get(Calendar.DAY_OF_MONTH);
            Calendar first = (Calendar) now.clone();
            first.set(Calendar.DAY_OF_MONTH, 1);
            int lead = (first.get(Calendar.DAY_OF_WEEK) - first.getFirstDayOfWeek() + 7) % 7;
            int days = now.getActualMaximum(Calendar.DAY_OF_MONTH);
            int weeks = (lead + days + 6) / 7;

            float pad = s * 0.1f;
            float colW = (s - 2 * pad) / 7f;
            p.setTextAlign(Paint.Align.LEFT);
            p.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
            p.setTextSize(s * 0.075f);
            p.setColor(0xFFFF3B30);
            String month = new DateFormatSymbols(Locale.getDefault()).getMonths()[now.get(Calendar.MONTH)].toUpperCase(Locale.getDefault());
            float headerY = oy + pad + s * 0.06f;
            c.drawText(month, ox + pad + colW * 0.18f, headerY, p);

            p.setTextAlign(Paint.Align.CENTER);
            p.setTextSize(s * 0.058f);
            p.setColor(0xFF8E8E93);
            String[] letters = new DateFormatSymbols(Locale.getDefault()).getShortWeekdays();
            float rowH = (s - (headerY - oy) - pad * 0.9f) / (weeks + 1);
            float lettersY = headerY + rowH;
            for (int i = 0; i < 7; i++) {
                int dow = (now.getFirstDayOfWeek() - 1 + i) % 7 + 1;
                String l = letters[dow].isEmpty() ? "" : letters[dow].substring(0, 1).toUpperCase(Locale.getDefault());
                c.drawText(l, ox + pad + colW * (i + 0.5f), lettersY, p);
            }
            p.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            p.setTextSize(s * 0.062f);
            for (int d = 1; d <= days; d++) {
                int cell = lead + d - 1;
                float cx = ox + pad + colW * (cell % 7 + 0.5f);
                float cy = lettersY + rowH * (cell / 7 + 1);
                if (d == today) {
                    p.setColor(0xFFFF3B30);
                    c.drawCircle(cx, cy + (p.ascent() + p.descent()) / 2, Math.min(colW, rowH) * 0.48f, p);
                    p.setColor(0xFFFFFFFF);
                } else {
                    p.setColor(0xFF1C1C1E);
                }
                c.drawText(Integer.toString(d), cx, cy, p);
            }
        }
    }
}
