package ug.audiocity.lyrics;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import java.util.ArrayDeque;

/**
 * Whole-song timeline editor: the song's sound wave with every lyric line as a block. The playhead stays in the
 * middle; swiping the timeline moves through the song. Tap a line to select it, drag it to move it (words and all),
 * drag its edges to set where it starts and ends, tap it again to edit its words, tap an empty spot to drop the next
 * unplaced line there. Pinch to zoom. Every change can be undone.
 */
final class SongTimeline extends View {
    interface Host {
        long now();
        void seek(long ms);
        void changed();
        void editLine(int index);
        void dropNextLine(long ms);
    }

    private final Lyrics lyrics;
    private final Host host;
    private long duration;
    private float[] envelope;
    private final float d; // density
    private float pxPerSec;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final ScaleGestureDetector zoom;
    private final ArrayDeque<java.util.ArrayList<Lyrics.Line>> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();
    private java.util.ArrayList<Lyrics.Line> pending;

    int selected = -1;
    /** Snap a dragged clip's start to the nearest place where the singing starts (a rise in loudness). */
    boolean snap = true;
    // Touch state.
    private static final int NONE = 0, SCRUB = 1, MOVE = 2, LEFT = 3, RIGHT = 4;
    private int mode = NONE, touchLine = -1;
    private float downX, lastX;
    private long downTime, grabStart, grabEnd;
    private boolean moved, scaling;
    private long viewCentre = -1; // when not following the playhead (while scrubbing)

    SongTimeline(Context c, Lyrics lyrics, long duration, float[] envelope, Host host) {
        super(c);
        this.lyrics = lyrics; this.duration = Math.max(1000, duration); this.envelope = envelope; this.host = host;
        d = c.getResources().getDisplayMetrics().density;
        pxPerSec = 70 * d;
        zoom = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector g) { setZoom(pxPerSec * g.getScaleFactor()); return true; }
            @Override public boolean onScaleBegin(ScaleGestureDetector g) { scaling = true; return true; }
        });
    }

    void setZoom(float pps) { pxPerSec = Math.max(4 * d, Math.min(600 * d, pps)); invalidate(); }
    void zoomBy(float f) { setZoom(pxPerSec * f); }
    /** Fits the whole song on screen. */
    void zoomToFit() { setZoom(Math.max(1, getWidth() - 20 * d) / (duration / 1000f)); }

    long centre() { return viewCentre >= 0 ? viewCentre : host.now(); }
    float x(long t) { return getWidth() / 2f + (t - centre()) / 1000f * pxPerSec; }
    long time(float x) { return centre() + Math.round((x - getWidth() / 2f) / pxPerSec * 1000); }

    long startOf(int i) { return lyrics.lines.get(i).start; }
    long endOf(int i) {
        Lyrics.Line l = lyrics.lines.get(i);
        return l.end > l.start ? l.end : lyrics.endOf(i, duration);
    }

    // Layout bands.
    float rulerH() { return 16 * d; }
    float waveTop() { return rulerH(); }
    float waveH() { return getHeight() * .34f; }
    float lineTop() { return waveTop() + waveH() + 6 * d; }
    float lineH() { return getHeight() - lineTop() - 6 * d; }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        paint.setColor(0xff15151c);
        c.drawRect(0, 0, w, h, paint);
        long t0 = time(0), t1 = time(w);
        // Ruler with time labels.
        long step = pxPerSec > 150 * d ? 1000 : pxPerSec > 40 * d ? 5000 : pxPerSec > 12 * d ? 15000 : 30000;
        text.setTextSize(9 * d);
        text.setColor(0x99ffffff);
        paint.setColor(0x22ffffff);
        for (long s = Math.max(0, t0 / step * step); s <= Math.min(duration, t1); s += step) {
            float px = x(s);
            c.drawRect(px, 0, px + 1, h, paint);
            long sec = s / 1000;
            c.drawText(String.format(java.util.Locale.ROOT, "%d:%02d", sec / 60, sec % 60), px + 3 * d, 11 * d, text);
        }
        // Outside the song.
        paint.setColor(0xff0c0c10);
        if (x(0) > 0) c.drawRect(0, rulerH(), x(0), h, paint);
        if (x(duration) < w) c.drawRect(x(duration), rulerH(), w, h, paint);
        // Sound wave.
        float mid = waveTop() + waveH() / 2, colW = Math.max(1.5f * d, 2 * d);
        paint.setColor(0xff5b5b72);
        for (float px = Math.max(0, x(0)); px < Math.min(w, x(duration)); px += colW) {
            float v = level(time(px)), bh = Math.max(1, v * waveH() * .46f);
            c.drawRect(px, mid - bh, px + colW * .7f, mid + bh, paint);
        }
        // Line blocks.
        text.setTextSize(12 * d);
        float top = lineTop(), bh = lineH();
        for (int i = 0; i < lyrics.lines.size(); i++) {
            Lyrics.Line l = lyrics.lines.get(i);
            if (l.start < 0) continue;
            long e = endOf(i);
            if (e < t0 || l.start > t1) continue;
            float x0 = x(l.start), x1 = Math.max(x0 + 8 * d, x(e));
            boolean sel = i == selected;
            paint.setColor(sel ? 0xffffd166 : l.wordTimed() ? 0xff3fb7a6 : 0xff6a7bd8);
            r.set(x0, top, x1, top + bh);
            c.drawRoundRect(r, 7 * d, 7 * d, paint);
            // Word starts inside the line.
            long[] words = l.wordTimed() ? l.wordTimes : Lyrics.estimateWords(l.words(), l.start, l.end > l.start ? l.end : l.start + Lyrics.singFor(l.text, Math.max(1, e - l.start)));
            paint.setColor(sel ? 0x55000000 : 0x40ffffff);
            for (int k = 1; k < words.length; k++) { float px = x(words[k]); if (px > x0 && px < x1) c.drawRect(px, top + bh * .55f, px + Math.max(1, d), top + bh, paint); }
            c.save();
            c.clipRect(r);
            text.setColor(sel ? 0xff1a1a1a : 0xffffffff);
            c.drawText((i + 1) + "  " + l.text, x0 + 6 * d, top + 16 * d, text);
            if (pxPerSec > 120 * d) {
                text.setTextSize(10 * d);
                String[] ws = l.words();
                for (int k = 0; k < ws.length && k < words.length; k++) c.drawText(ws[k], x(words[k]) + 3 * d, top + bh - 7 * d, text);
                text.setTextSize(12 * d);
            }
            c.restore();
            if (sel) { // edge handles
                paint.setColor(0xffffffff);
                c.drawRoundRect(new RectF(x0 - 3 * d, top + bh * .25f, x0 + 3 * d, top + bh * .75f), 3 * d, 3 * d, paint);
                c.drawRoundRect(new RectF(x1 - 3 * d, top + bh * .25f, x1 + 3 * d, top + bh * .75f), 3 * d, 3 * d, paint);
            }
        }
        // Playhead in the middle.
        paint.setColor(0xff4cc9f0);
        c.drawRect(w / 2f - d, 0, w / 2f + d, h, paint);
        long now = centre();
        text.setColor(0xff4cc9f0);
        text.setTextSize(10 * d);
        c.drawText(Lyrics.stamp(now), w / 2f + 4 * d, h - 4 * d, text);
        if (mode == NONE) postInvalidateDelayed(40);
    }

    float level(long t) {
        if (envelope.length == 0 || t < 0) return 0;
        int i = (int) (t * Project.ENVELOPE_RATE / 1000);
        return i < envelope.length ? envelope[i] : 0;
    }

    /** The line block under x (in the lines band), or -1. */
    int lineAt(float x) {
        long t = time(x);
        for (int i = 0; i < lyrics.lines.size(); i++) if (startOf(i) >= 0 && t >= startOf(i) - 60 && t <= endOf(i) + 60) return i;
        return -1;
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        zoom.onTouchEvent(e);
        if (scaling) { if (e.getActionMasked() == MotionEvent.ACTION_UP || e.getActionMasked() == MotionEvent.ACTION_CANCEL) { scaling = false; mode = NONE; } return true; }
        float ex = e.getX(), ey = e.getY();
        int a = e.getActionMasked();
        if (a == MotionEvent.ACTION_DOWN) {
            getParent().requestDisallowInterceptTouchEvent(true);
            downX = lastX = ex; moved = false; downTime = centre(); viewCentre = centre();
            mode = SCRUB; touchLine = -1;
            if (ey >= lineTop() && selected >= 0 && startOf(selected) >= 0) {
                float x0 = x(startOf(selected)), x1 = x(endOf(selected)), grab = 16 * d;
                if (Math.abs(ex - x0) < grab) mode = LEFT;
                else if (Math.abs(ex - x1) < grab) mode = RIGHT;
                else if (ex > x0 && ex < x1) mode = MOVE;
                if (mode != SCRUB) { touchLine = selected; grabStart = startOf(selected); grabEnd = endOf(selected); pending = lyrics.snapshot(); }
            }
            return true;
        }
        if (a == MotionEvent.ACTION_MOVE) {
            if (Math.abs(ex - downX) > 6 * d) {
                if (!moved && pending != null) { remember(pending); pending = null; } // a real drag: it can be undone
                moved = true;
            }
            if (!moved) return true;
            long dt = Math.round((ex - downX) / pxPerSec * 1000);
            if (mode == SCRUB) { viewCentre = Math.max(0, Math.min(duration, downTime - dt)); }
            else if (touchLine >= 0) { dragLine(touchLine, dt); host.changed(); }
            invalidate();
            return true;
        }
        if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
            if (mode == SCRUB && moved) host.seek(viewCentre);
            else if (!moved && a == MotionEvent.ACTION_UP) tapAt(ex, ey);
            else if (touchLine >= 0 && moved) { if (snap && (mode == MOVE || mode == LEFT)) snapStart(touchLine); host.changed(); }
            pending = null;
            viewCentre = -1; mode = NONE; touchLine = -1;
            invalidate();
            return true;
        }
        return true;
    }

    private void tapAt(float ex, float ey) {
        int hit = ey >= lineTop() - 10 * d ? lineAt(ex) : -1;
        if (hit >= 0) {
            if (hit == selected) host.editLine(hit); else selected = hit;
        } else if (ey >= lineTop() - 10 * d && lyrics.nextUnsynced() >= 0) {
            snapshot();
            host.dropNextLine(Math.max(0, Math.min(duration - 500, time(ex))));
            selected = -1;
        } else {
            selected = -1;
            host.seek(Math.max(0, Math.min(duration, time(ex))));
        }
        invalidate();
    }

    /** Moves a line's start to the strongest rise in loudness within ±0.35 s (where singing starts), if there is a clear one. */
    void snapStart(int i) {
        Lyrics.Line l = lyrics.lines.get(i);
        if (envelope.length == 0 || l.start < 0) return;
        int c = (int) (l.start * Project.ENVELOPE_RATE / 1000), best = -1;
        float bestRise = .12f;
        for (int k = Math.max(13, c - 35); k <= Math.min(envelope.length - 1, c + 35); k++) {
            float low = Float.MAX_VALUE;
            for (int j = k - 12; j < k; j++) low = Math.min(low, envelope[j]);
            float rise = envelope[k] - low;
            if (rise > bestRise) { bestRise = rise; best = k; }
        }
        if (best < 0) return;
        long s = best * 1000L / Project.ENVELOPE_RATE - 30; // a hair before the loudness peak rises
        long prevStart = -1, nextStart = Long.MAX_VALUE;
        for (int k = i - 1; k >= 0; k--) if (lyrics.lines.get(k).start >= 0) { prevStart = lyrics.lines.get(k).start; break; }
        for (int k = i + 1; k < lyrics.lines.size(); k++) if (lyrics.lines.get(k).start >= 0) { nextStart = lyrics.lines.get(k).start; break; }
        if (s <= prevStart + 100 || s >= nextStart - 100 || (l.end > 0 && s >= l.end - 200)) return;
        if (mode == MOVE) Lyrics.moveLine(l, s);
        else { if (l.wordTimed() && l.wordTimes.length > 1 && s >= l.wordTimes[1]) return; if (l.wordTimed()) l.wordTimes[0] = s; l.start = s; }
    }

    /** Moves (or resizes) a line, keeping lines in order and at least 0.2 s long. */
    private void dragLine(int i, long dt) {
        Lyrics.Line l = lyrics.lines.get(i);
        long prevEnd = 0, nextStart = duration;
        for (int k = i - 1; k >= 0; k--) { Lyrics.Line p = lyrics.lines.get(k); if (p.start >= 0) { prevEnd = p.start + 100; break; } }
        for (int k = i + 1; k < lyrics.lines.size(); k++) { Lyrics.Line n = lyrics.lines.get(k); if (n.start >= 0) { nextStart = n.start - 100; break; } }
        if (mode == MOVE) {
            long len = grabEnd - grabStart;
            long s = Math.max(prevEnd, Math.min(nextStart, grabStart + dt));
            Lyrics.moveLine(l, s);
            if (l.end < 0) l.end = s + len;
        } else if (mode == LEFT) {
            long s = Math.max(prevEnd, Math.min(grabEnd - 200, grabStart + dt));
            long end = l.end > l.start ? l.end : grabEnd;
            if (l.wordTimed()) {
                // Keep the words inside the line: squeeze or stretch them with the new start.
                long[] w = l.wordTimes;
                long oldS = w[0], span = Math.max(1, end - oldS), newSpan = Math.max(1, end - s);
                for (int k = 0; k < w.length; k++) w[k] = s + (w[k] - oldS) * newSpan / span;
            }
            l.start = s;
            l.end = end;
        } else if (mode == RIGHT) {
            long e = Math.max(l.start + 200, Math.min(nextStart + 99, grabEnd + dt));
            if (l.wordTimed()) {
                long[] w = l.wordTimes;
                long oldEnd = Math.max(w[w.length - 1] + 1, l.end > l.start ? l.end : grabEnd), span = Math.max(1, oldEnd - l.start), newSpan = Math.max(1, e - l.start);
                for (int k = 1; k < w.length; k++) w[k] = l.start + (w[k] - l.start) * newSpan / span;
            }
            l.end = e;
        }
    }

    /** Remembers all lines (text and timing) before a change, so it can be undone. */
    void snapshot() { remember(lyrics.snapshot()); }

    private void remember(java.util.ArrayList<Lyrics.Line> state) {
        undo.push(state);
        while (undo.size() > 60) undo.removeLast();
        redo.clear();
    }

    boolean canUndo() { return !undo.isEmpty(); }
    boolean canRedo() { return !redo.isEmpty(); }

    void undo() {
        if (undo.isEmpty()) return;
        redo.push(lyrics.snapshot());
        lyrics.restore(undo.pop());
        if (selected >= lyrics.lines.size()) selected = -1;
        host.changed();
        invalidate();
    }

    void redo() {
        if (redo.isEmpty()) return;
        undo.push(lyrics.snapshot());
        lyrics.restore(redo.pop());
        if (selected >= lyrics.lines.size()) selected = -1;
        host.changed();
        invalidate();
    }

    void update(long duration, float[] envelope) { this.duration = Math.max(1000, duration); this.envelope = envelope; invalidate(); }
}
