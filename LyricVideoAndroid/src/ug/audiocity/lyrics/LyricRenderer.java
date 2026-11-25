package ug.audiocity.lyrics;

import android.graphics.*;

import java.io.File;
import java.util.ArrayList;
import java.util.Locale;

/**
 * Draws one frame of a lyric video onto any Canvas: the on-screen preview and the video encoder use the same code, so
 * the export looks exactly like the preview. All sizes scale with the frame, designed on a 1080-pixel short side.
 */
final class LyricRenderer {
    Lyrics lyrics = new Lyrics();
    Style style = new Style();
    String title = "", artist = "";
    float[] envelope = new float[0];
    long duration;
    Bitmap photo;
    Typeface custom;

    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Matrix matrix = new Matrix();
    private final RectF rect = new RectF();
    private final Typeface[] faces = new Typeface[Style.FONTS.length];

    /** Points the renderer at a project's current data (for the live preview). */
    void bind(Project p) {
        lyrics = p.lyrics; style = p.style; title = p.title; artist = p.artist; envelope = p.envelope; duration = p.durationMs;
    }

    /** Independent copy for rendering on a background thread while the project keeps changing. */
    static LyricRenderer snapshot(Project p, Bitmap photo, Typeface custom) {
        LyricRenderer r = new LyricRenderer();
        for (Lyrics.Line l : p.lyrics.lines) r.lyrics.lines.add(new Lyrics.Line(l.text, l.start));
        r.style = p.style.copy(); r.title = p.title; r.artist = p.artist; r.envelope = p.envelope.clone(); r.duration = p.durationMs;
        r.photo = photo; r.custom = custom;
        return r;
    }

    static Typeface loadFont(File f) { try { return f.isFile() ? Typeface.createFromFile(f) : null; } catch (Exception e) { return null; } }

    Typeface face() {
        int i = style.font;
        if (i == 6 && custom != null) return custom;
        if (i == 6) i = 0;
        if (faces[i] == null) {
            switch (i) {
                case 0: faces[i] = Typeface.create("sans-serif-black", Typeface.NORMAL); break;
                case 1: faces[i] = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD); break;
                case 2: faces[i] = Typeface.create("sans-serif-condensed", Typeface.BOLD); break;
                case 3: faces[i] = Typeface.create(Typeface.SERIF, Typeface.BOLD); break;
                case 4: faces[i] = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); break;
                default: faces[i] = Typeface.create("cursive", Typeface.BOLD); break;
            }
        }
        return faces[i];
    }

    float level(long t) {
        float[] e = envelope;
        if (e.length == 0 || t < 0) return 0;
        float pos = t * Project.ENVELOPE_RATE / 1000f;
        int i = (int) pos;
        if (i >= e.length - 1) return i < e.length ? e[i] : 0;
        float f = pos - i;
        return e[i] * (1 - f) + e[i + 1] * f;
    }

    // ------------------------------------------------------------------ frame

    void draw(Canvas c, int w, int h, long t) {
        float u = Math.min(w, h) / 1080f, lv = level(t);
        background(c, w, h, t, lv, u);
        if (style.visualizer) visualizer(c, w, h, t, u);
        if (style.progressBar && duration > 0) progress(c, w, h, t, u);
        Lyrics.Frame f = lyrics.frame(t, duration);
        // Lower third sits higher in wide frames so the next-line hint clears the visualizer.
        float anchor = h * (style.position == 1 ? (style.aspect == 0 ? .66f : .58f) : style.position == 2 ? .32f : .5f);
        float maxW = w * (style.aspect == 1 ? .74f : .86f);
        float size = 76 * u * style.textSize;
        if (f.untilFirst > 0 && style.titleCard) titleCard(c, w, h, t, f.untilFirst, u);
        if (f.untilFirst > 0 && style.titleCard && style.animation != 2) return;
        switch (style.animation) {
            case 1: fadeRise(c, w, f, anchor, maxW, size, u); break;
            case 2: scrolling(c, w, f, anchor, maxW, size, f.untilFirst > 0 && style.titleCard); break;
            case 3: typewriter(c, w, t, f, anchor, maxW, size); break;
            case 4: wordPop(c, w, f, anchor, maxW, size); break;
            default: karaoke(c, w, t, f, anchor, maxW, size); break;
        }
    }

    String shown(int i) { String s = lyrics.lines.get(i).text; return style.uppercase ? s.toUpperCase(Locale.getDefault()) : s; }

    // ------------------------------------------------------------------ background and decorations

    void background(Canvas c, int w, int h, long t, float lv, float u) {
        int[] p = Style.PALETTES[style.palette];
        if (style.background == 1) c.drawColor(style.solid);
        else if (style.background == 2 && photo != null && !photo.isRecycled()) {
            c.drawColor(0xff000000);
            float bw = photo.getWidth(), bh = photo.getHeight();
            float progress = duration > 0 ? Math.min(1, t / (float) duration) : 0;
            float zoom = (style.kenBurns ? 1.04f + .12f * progress : 1f) * (1 + .012f * lv);
            float scale = Math.max(w / bw, h / bh) * zoom;
            float dx = style.kenBurns ? (float) Math.sin(progress * Math.PI) * w * .02f : 0;
            matrix.reset();
            matrix.postTranslate(-bw / 2, -bh / 2);
            matrix.postScale(scale, scale);
            matrix.postTranslate(w / 2f + dx, h / 2f);
            c.drawBitmap(photo, matrix, fill);
            c.drawColor(Color.argb(Math.round(style.dim * 255), 0, 0, 0));
        } else {
            double a = t / 1000.0 * .09;
            float d = (float) Math.hypot(w, h) / 2, cx = w / 2f, cy = h / 2f;
            fill.setShader(new LinearGradient(cx - (float) Math.cos(a) * d, cy - (float) Math.sin(a) * d, cx + (float) Math.cos(a) * d, cy + (float) Math.sin(a) * d, p, null, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, fill);
            fill.setShader(null);
            // Slow, soft light blobs that swell a little with the music.
            for (int i = 0; i < 4; i++) {
                double s = t / 1000.0 * (.05 + .02 * i) + i * 1.7;
                float x = w * (.5f + .38f * (float) Math.sin(s * 1.3 + i)), y = h * (.5f + .36f * (float) Math.cos(s + i * 2.1));
                float r = Math.min(w, h) * (.38f + .07f * i) * (1 + .18f * lv);
                int col = p[(i + 1) % p.length];
                fill.setShader(new RadialGradient(x, y, r, new int[]{(col & 0xffffff) | 0x60000000, col & 0xffffff}, null, Shader.TileMode.CLAMP));
                c.drawCircle(x, y, r, fill);
            }
            fill.setShader(null);
        }
        // Vignette for depth and legibility.
        float r = (float) Math.hypot(w, h) * .62f;
        fill.setShader(new RadialGradient(w / 2f, h / 2f, r, new int[]{0x00000000, 0x00000000, 0x8c000000}, new float[]{0, .55f, 1}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, fill);
        fill.setShader(null);
    }

    void visualizer(Canvas c, int w, int h, long t, float u) {
        int half = 22;
        float span = w * (style.aspect == 1 ? .5f : .8f), gap = span / (half * 2), bw = gap * .52f;
        float base = h - (style.progressBar ? 120 : 90) * u;
        for (int k = 0; k < half; k++) {
            float v = level(t - k * 45L);
            float shape = 1 - k / (float) half * .55f;
            float bh = (5 + v * v * 95 * shape + v * 18) * u;
            int alpha = Math.round(255 * (.85f - .55f * k / half));
            fill.setColor((style.highlight & 0xffffff) | (alpha << 24));
            for (int side = -1; side <= 1; side += 2) {
                float x = w / 2f + side * (k * gap + gap / 2);
                rect.set(x - bw / 2, base - bh, x + bw / 2, base + bh * .35f);
                c.drawRoundRect(rect, bw / 2, bw / 2, fill);
            }
        }
    }

    void progress(Canvas c, int w, int h, long t, float u) {
        float y = h - 46 * u, x0 = w * .08f, x1 = w * .92f, th = 5 * u;
        fill.setColor(0x33ffffff);
        rect.set(x0, y - th / 2, x1, y + th / 2); c.drawRoundRect(rect, th, th, fill);
        float p = Math.max(0, Math.min(1, t / (float) duration));
        fill.setColor(style.highlight);
        rect.set(x0, y - th / 2, x0 + (x1 - x0) * p, y + th / 2); c.drawRoundRect(rect, th, th, fill);
    }

    void titleCard(Canvas c, int w, int h, long t, long untilFirst, float u) {
        float alpha = untilFirst == Long.MAX_VALUE ? 1 : Lyrics.clamp((untilFirst - 250) / 650f);
        alpha *= Lyrics.clamp(t / 500f + .35f);
        if (alpha <= 0) return;
        float maxW = w * .82f, size = 104 * u * Math.min(1.25f, style.textSize);
        String ti = title == null || title.trim().isEmpty() ? "Untitled" : title.trim();
        if (style.uppercase) ti = ti.toUpperCase(Locale.getDefault());
        paint(size, style.textColor, alpha);
        ArrayList<String> rows = fit(ti, maxW, size, 3);
        size = text.getTextSize();
        float lh = size * 1.12f, top = h * .47f - rows.size() * lh / 2;
        if (artist != null && !artist.trim().isEmpty()) top -= 30 * u;
        float rise = (1 - Lyrics.ease(Lyrics.clamp(t / 900f))) * 30 * u;
        for (int r = 0; r < rows.size(); r++) c.drawText(rows.get(r), w / 2f - text.measureText(rows.get(r)) / 2, top + r * lh + lh * .8f + rise, text);
        float y = top + rows.size() * lh + 22 * u + rise;
        fill.setColor(applyAlpha(style.highlight, alpha));
        rect.set(w / 2f - 60 * u, y, w / 2f + 60 * u, y + 7 * u); c.drawRoundRect(rect, 4 * u, 4 * u, fill);
        if (artist != null && !artist.trim().isEmpty()) {
            paint(50 * u, style.highlight, alpha);
            String ar = artist.trim();
            ArrayList<String> arRows = fit(ar, maxW, 50 * u, 2);
            float s = text.getTextSize();
            for (int r = 0; r < arRows.size(); r++) c.drawText(arRows.get(r), w / 2f - text.measureText(arRows.get(r)) / 2, y + 40 * u + s + r * s * 1.15f, text);
        }
    }

    // ------------------------------------------------------------------ text helpers

    static int applyAlpha(int color, float a) { return (color & 0xffffff) | (Math.round(Color.alpha(color) * Lyrics.clamp(a)) << 24); }

    void paint(float size, int color, float alpha) {
        text.setTypeface(face());
        text.setTextSize(size);
        text.setColor(applyAlpha(color, alpha));
        text.setTextAlign(Paint.Align.LEFT);
        if (style.shadow && alpha > 0.02f) text.setShadowLayer(size * .14f, 0, size * .05f, Color.argb(Math.round(150 * alpha), 0, 0, 0));
        else text.clearShadowLayer();
    }

    /** Greedy word wrap with the current text size. */
    ArrayList<String> wrap(String s, float maxW) {
        ArrayList<String> rows = new ArrayList<>();
        StringBuilder row = new StringBuilder();
        for (String word : s.split(" ")) {
            if (word.isEmpty()) continue;
            String tryRow = row.length() == 0 ? word : row + " " + word;
            if (row.length() > 0 && text.measureText(tryRow) > maxW) { rows.add(row.toString()); row.setLength(0); row.append(word); }
            else { row.setLength(0); row.append(tryRow); }
        }
        if (row.length() > 0) rows.add(row.toString());
        if (rows.isEmpty()) rows.add("");
        return rows;
    }

    /** Wraps, shrinking the text until it fits in maxRows rows and every row fits the width. Leaves the paint at the final size. */
    ArrayList<String> fit(String s, float maxW, float size, int maxRows) {
        ArrayList<String> rows = null;
        for (int i = 0; i < 8; i++) {
            text.setTextSize(size);
            rows = wrap(s, maxW);
            boolean ok = rows.size() <= maxRows;
            for (String r : rows) if (text.measureText(r) > maxW) ok = false;
            if (ok) break;
            size *= .88f;
            if (style.shadow) text.setShadowLayer(size * .14f, 0, size * .05f, 0x96000000);
        }
        return rows;
    }

    /** Single row, shrunk to the width. */
    float fitRow(String s, float maxW, float size) {
        text.setTextSize(size);
        float m = text.measureText(s);
        if (m > maxW) { size = size * maxW / m; text.setTextSize(size); }
        return size;
    }

    /** Draws a wrapped block centred on y; wipe (0..1, or -1 for none) paints the sung part in the highlight colour. */
    void block(Canvas c, int w, ArrayList<String> rows, float y, float wipe, int base, int high, float alpha) {
        float size = text.getTextSize(), lh = size * 1.2f, top = y - rows.size() * lh / 2;
        float total = 0;
        float[] widths = new float[rows.size()];
        for (int r = 0; r < rows.size(); r++) { widths[r] = text.measureText(rows.get(r)); total += widths[r]; }
        float filled = wipe < 0 ? 0 : wipe * total, done = 0;
        for (int r = 0; r < rows.size(); r++) {
            float x = w / 2f - widths[r] / 2, baseLine = top + r * lh + lh * .78f;
            float part = Math.max(0, Math.min(widths[r], filled - done));
            done += widths[r];
            text.setColor(applyAlpha(base, alpha));
            c.drawText(rows.get(r), x, baseLine, text);
            if (part > 0) {
                c.save();
                c.clipRect(x - size, baseLine - size * 1.4f, x + part, baseLine + size * .6f);
                text.setColor(applyAlpha(high, alpha));
                c.drawText(rows.get(r), x, baseLine, text);
                c.restore();
            }
        }
    }

    float blockHeight(int rows) { return rows * text.getTextSize() * 1.2f; }

    // ------------------------------------------------------------------ animations

    void karaoke(Canvas c, int w, long t, Lyrics.Frame f, float y, float maxW, float size) {
        float small = size * .6f, alpha = 1 - f.out;
        if (f.index < 0) {
            // Instrumental gap: show the next line faintly so the singer can prepare.
            float a = f.next >= 0 ? .45f * Lyrics.clamp(1 - (lyrics.lines.get(f.next).start - t) / 2500f) : 0;
            if (a > 0) {
                paint(size, style.textColor, a);
                ArrayList<String> rows = fit(shown(f.next), maxW, size, 3);
                block(c, w, rows, y, -1, style.textColor, style.highlight, a);
            }
            return;
        }
        paint(size, style.textColor, alpha);
        ArrayList<String> rows = fit(shown(f.index), maxW, size, 3);
        float bh = blockHeight(rows.size());
        float slide = (1 - f.change) * (bh * .5f + small * 1.6f);
        block(c, w, rows, y + slide, f.wipe, style.textColor, style.highlight, alpha * (f.previous >= 0 ? .4f + .6f * f.change : .45f + .55f * f.in));
        if (f.previous >= 0 && f.change < 1) {
            paint(small, style.textColor, 1);
            float s = fitRow(shown(f.previous), maxW, small);
            text.setTextSize(s);
            ArrayList<String> one = new ArrayList<>(); one.add(shown(f.previous));
            block(c, w, one, y - bh / 2 - small * 1.1f + slide, -1, style.highlight, style.highlight, .55f * (1 - f.change));
        }
        if (f.next >= 0 && f.out == 0) {
            paint(small, style.textColor, 1);
            fitRow(shown(f.next), maxW, small);
            ArrayList<String> one = new ArrayList<>(); one.add(shown(f.next));
            block(c, w, one, y + bh / 2 + small * 1.2f + slide, -1, style.textColor, style.textColor, .42f * f.change * alpha);
        }
    }

    void fadeRise(Canvas c, int w, Lyrics.Frame f, float y, float maxW, float size, float u) {
        if (f.previous >= 0 && f.index >= 0 && f.change < 1) {
            paint(size, style.textColor, 1);
            ArrayList<String> rows = fit(shown(f.previous), maxW, size, 3);
            block(c, w, rows, y - f.change * 50 * u, -1, style.textColor, style.textColor, 1 - f.change);
        }
        if (f.index < 0) return;
        float alpha = Lyrics.ease(f.in) * (1 - f.out);
        paint(size, style.textColor, alpha);
        ArrayList<String> rows = fit(shown(f.index), maxW, size, 3);
        float scale = .93f + .07f * Lyrics.ease(f.in), rise = (1 - Lyrics.ease(f.in)) * 46 * u + f.out * -30 * u;
        c.save();
        c.scale(scale, scale, w / 2f, y);
        block(c, w, rows, y + rise, -1, style.textColor, style.textColor, alpha);
        float bh = blockHeight(rows.size()), lineW = 0;
        for (String r : rows) lineW = Math.max(lineW, text.measureText(r));
        fill.setColor(applyAlpha(style.highlight, alpha));
        float bw = lineW * .5f * f.wipe;
        rect.set(w / 2f - bw / 2, y + rise + bh / 2 + 18 * u, w / 2f + bw / 2, y + rise + bh / 2 + 26 * u);
        c.drawRoundRect(rect, 4 * u, 4 * u, fill);
        c.restore();
    }

    void scrolling(Canvas c, int w, Lyrics.Frame f, float y, float maxW, float size, boolean titleShown) {
        if (lyrics.lines.isEmpty()) return;
        int current = f.index >= 0 ? f.index : f.previous >= 0 ? f.previous : -1;
        float pos;
        if (current < 0) { if (titleShown) return; pos = (f.next >= 0 ? f.next : 0) - .8f; }
        else { int from = f.index >= 0 && f.previous >= 0 ? f.previous : current; pos = current - (1 - f.change) * (current - from); }
        // Long lines wrap to two rows rather than shrinking, so each line gets its own height.
        float s = size * .78f, gap = s * .45f;
        int lo = Math.max(0, (int) Math.floor(pos) - 4), hi = Math.min(lyrics.lines.size() - 1, (int) Math.ceil(pos) + 5);
        int n = hi - lo + 1;
        if (n <= 0) return;
        ArrayList<ArrayList<String>> rows = new ArrayList<>();
        float[] sizes = new float[n], centre = new float[n];
        float at = 0;
        for (int k = lo; k <= hi; k++) {
            float rowSize = s * (k == f.index ? 1.08f : 1f);
            paint(rowSize, style.textColor, 1);
            ArrayList<String> r = fit(shown(k), maxW, rowSize, 2);
            sizes[k - lo] = text.getTextSize();
            float bh = blockHeight(r.size());
            rows.add(r);
            centre[k - lo] = at + bh / 2;
            at += bh + gap;
        }
        // Interpolate the centre for the fractional scroll position, then place that point on the anchor.
        int a = Math.max(lo, Math.min(hi, (int) Math.floor(pos))), b = Math.min(hi, a + 1);
        float frac = Math.max(0, Math.min(1, pos - a));
        float focus = pos < lo ? centre[0] - (lo - pos) * (s * 1.6f) : centre[a - lo] + (centre[b - lo] - centre[a - lo]) * frac;
        for (int k = lo; k <= hi; k++) {
            float yk = y + centre[k - lo] - focus;
            float d = Math.abs(yk - y) / (s * 1.6f);
            if (d > 4.5f) continue;
            boolean active = k == f.index;
            float alpha = active ? 1 - f.out * .5f : Math.max(0, .62f - d * .14f);
            paint(sizes[k - lo], style.textColor, 1);
            block(c, w, rows.get(k - lo), yk, active ? f.wipe : -1, active ? style.highlight : style.textColor, style.highlight, alpha);
        }
    }

    void typewriter(Canvas c, int w, long t, Lyrics.Frame f, float y, float maxW, float size) {
        if (f.index < 0) return;
        float alpha = 1 - f.out;
        paint(size, style.textColor, alpha);
        String s = shown(f.index);
        ArrayList<String> rows = fit(s, maxW, size, 3);
        int total = 0;
        for (String r : rows) total += r.length();
        int visible = Math.round(Lyrics.clamp(f.wipe * 1.15f) * total);
        float sz = text.getTextSize(), lh = sz * 1.2f, top = y - rows.size() * lh / 2;
        int used = 0;
        float cursorX = w / 2f, cursorY = top + lh * .78f;
        for (int r = 0; r < rows.size(); r++) {
            String row = rows.get(r);
            float x = w / 2f - text.measureText(row) / 2, base = top + r * lh + lh * .78f;
            int n = Math.max(0, Math.min(row.length(), visible - used));
            used += row.length();
            if (n > 0) c.drawText(row, 0, n, x, base, text);
            if (n > 0 || r == 0) { cursorX = x + text.measureText(row, 0, n); cursorY = base; }
        }
        if (visible < total || (t / 450) % 2 == 0) {
            fill.setColor(applyAlpha(style.highlight, alpha));
            rect.set(cursorX + sz * .06f, cursorY - sz * .78f, cursorX + sz * .14f, cursorY + sz * .1f);
            c.drawRect(rect, fill);
        }
    }

    void wordPop(Canvas c, int w, Lyrics.Frame f, float y, float maxW, float size) {
        if (f.index < 0) return;
        float alpha = 1 - f.out;
        paint(size, style.textColor, alpha);
        ArrayList<String> rows = fit(shown(f.index), maxW, size, 3);
        int n = 0;
        for (String r : rows) n += r.split(" ").length;
        float sz = text.getTextSize(), lh = sz * 1.2f, top = y - rows.size() * lh / 2, space = text.measureText(" ");
        int i = 0;
        for (int r = 0; r < rows.size(); r++) {
            String[] words = rows.get(r).split(" ");
            float x = w / 2f - text.measureText(rows.get(r)) / 2, base = top + r * lh + lh * .78f;
            for (String word : words) {
                float ww = text.measureText(word);
                float local = Lyrics.clamp((f.wipe - i / (float) n) * n / 1.3f);
                if (local > 0) {
                    float k = local - 1, scale = 1 + 2.4f * k * k * k + 1.4f * k * k;
                    c.save();
                    c.scale(scale, scale, x + ww / 2, base - sz * .35f);
                    text.setColor(applyAlpha(local < 1 && f.wipe < 1 ? style.highlight : style.textColor, alpha * Math.min(1, local * 2)));
                    c.drawText(word, x, base, text);
                    c.restore();
                }
                x += ww + space;
                i++;
            }
        }
    }
}
