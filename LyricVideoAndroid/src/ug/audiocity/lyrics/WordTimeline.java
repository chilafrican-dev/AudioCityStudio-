package ug.audiocity.lyrics;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/**
 * Drag-and-drop word timing for one line: the song's sound wave around the line (singing shows as tall bars, breaks
 * as flat), with each word as a block that can be dragged to where it is sung. A playhead shows the song position.
 */
final class WordTimeline extends View {
    interface Clock { long now(); }
    interface Moved { void moved(int word, long time); }

    final String[] words;
    final long[] times;
    private final long from, to;
    private final float[] envelope;
    private final Clock clock;
    private final Moved moved;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private int dragging = -1, selected = 0;
    private float grabOffset;
    private final float density;

    WordTimeline(Context c, String[] words, long[] times, long from, long to, float[] envelope, Clock clock, Moved moved) {
        super(c);
        this.words = words; this.times = times; this.from = from; this.to = Math.max(from + 1000, to);
        this.envelope = envelope; this.clock = clock; this.moved = moved;
        density = c.getResources().getDisplayMetrics().density;
        text.setTextSize(12 * density);
        text.setColor(0xff1a1a1a);
    }

    float x(long t) { return getPaddingLeft() + (getWidth() - getPaddingLeft() - getPaddingRight()) * (t - from) / (float) (to - from); }
    long time(float x) { return from + Math.round((x - getPaddingLeft()) / (getWidth() - getPaddingLeft() - getPaddingRight()) * (to - from)); }

    /** Where a word's block ends: at the next word, but never longer than the word could be sung (so breaks stay visible). */
    long endOf(int k) {
        long sung = times[k] + 300 + 220L * Lyrics.syllables(words[k]);
        return Math.min(to, k + 1 < times.length ? Math.min(times[k + 1], sung) : sung);
    }

    int selected() { return selected; }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), top = 6 * density, waveH = h * .52f, mid = top + waveH / 2;
        paint.setColor(0xff1d1d26);
        r.set(0, 0, w, h); c.drawRoundRect(r, 10 * density, 10 * density, paint);
        // Sound wave: loudness per pixel column (singing stands out, breaks are flat).
        paint.setColor(0xff4a4a5c);
        float step = 2 * density;
        for (float px = getPaddingLeft(); px < w - getPaddingRight(); px += step) {
            long t = time(px);
            float v = level(t);
            float bh = Math.max(1, v * waveH * .48f);
            c.drawRect(px, mid - bh, px + step * .6f, mid + bh, paint);
        }
        // Second marks.
        paint.setColor(0x33ffffff);
        text.setColor(0x99ffffff);
        text.setTextSize(9 * density);
        for (long s = (from / 1000 + 1) * 1000; s < to; s += 1000) {
            float px = x(s);
            c.drawRect(px, top, px + 1, top + waveH, paint);
            c.drawText(Lyrics.stamp(s).substring(0, 5), px + 2 * density, top + 9 * density, text);
        }
        // Word blocks.
        text.setTextSize(12 * density);
        float by = top + waveH + 6 * density, bh = h - by - 6 * density;
        for (int k = 0; k < words.length; k++) {
            float x0 = x(times[k]), x1 = Math.max(x0 + 6 * density, x(endOf(k)) - 2 * density);
            paint.setColor(k == selected ? 0xffffd166 : 0xffe4e4ec);
            r.set(x0, by, x1, by + bh);
            c.drawRoundRect(r, 6 * density, 6 * density, paint);
            paint.setColor(k == selected ? 0xffffd166 : 0x99ffffff);
            c.drawRect(x0, top, x0 + 1.5f * density, by, paint); // start marker over the wave
            text.setColor(0xff1a1a1a);
            c.save(); c.clipRect(r);
            c.drawText(words[k], x0 + 4 * density, by + bh / 2 + 4 * density, text);
            c.restore();
        }
        // Playhead.
        long now = clock.now();
        if (now >= from && now <= to) { paint.setColor(0xff4cc9f0); float px = x(now); c.drawRect(px - density, 0, px + density, h, paint); }
        postInvalidateDelayed(50);
    }

    float level(long t) {
        if (envelope.length == 0) return 0;
        int i = (int) (t * Project.ENVELOPE_RATE / 1000);
        return i >= 0 && i < envelope.length ? envelope[i] : 0;
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        float ex = e.getX();
        int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            // Grab the word under the finger (or the nearest word start).
            int best = -1;
            float bestD = Float.MAX_VALUE;
            for (int k = 0; k < words.length; k++) {
                float x0 = x(times[k]), x1 = x(endOf(k));
                float d = ex >= x0 && ex <= x1 ? 0 : Math.min(Math.abs(ex - x0), Math.abs(ex - x1));
                if (d < bestD) { bestD = d; best = k; }
            }
            dragging = best;
            selected = best;
            grabOffset = best >= 0 ? ex - x(times[best]) : 0;
            getParent().requestDisallowInterceptTouchEvent(true);
            invalidate();
            return true;
        }
        if (action == MotionEvent.ACTION_MOVE) {
            if (dragging >= 0) {
                long t = time(ex - grabOffset);
                long lo = dragging > 0 ? times[dragging - 1] + WordTapper.MIN_GAP : from;
                long hi = dragging + 1 < times.length ? times[dragging + 1] - WordTapper.MIN_GAP : to - 100;
                times[dragging] = Math.max(lo, Math.min(hi, t));
                if (moved != null) moved.moved(dragging, times[dragging]);
                invalidate();
            }
            return true;
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) { dragging = -1; return true; }
        return super.onTouchEvent(e);
    }

    /** Moves the selected word to time t (e.g. the playhead), keeping word order. */
    void setSelected(long t) {
        int k = selected;
        long lo = k > 0 ? times[k - 1] + WordTapper.MIN_GAP : from;
        long hi = k + 1 < times.length ? times[k + 1] - WordTapper.MIN_GAP : to - 100;
        times[k] = Math.max(lo, Math.min(hi, t));
        if (moved != null) moved.moved(k, times[k]);
        invalidate();
    }

    void nudge(long ms) { setSelected(times[selected] + ms); }
}
