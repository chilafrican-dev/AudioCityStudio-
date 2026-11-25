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
    String title = "", artist = "", editor = "";
    float[] envelope = new float[0];
    long duration;
    PhotoSet photos = new PhotoSet(null, 1080);
    Typeface custom;
    /** True when a background video is drawn underneath (preview TextureView or exporter GL layer). */
    boolean videoUnder;
    /** Square cover photo (may be null). */
    Bitmap cover;
    private Bitmap coverSoft, coverSoftFrom;
    private float coverSoftAmount = -1;
    private BitmapShader coverShader;
    private Bitmap coverShaderFrom;
    private final Paint coverPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Matrix coverMatrix = new Matrix();
    private final RectF coverRect = new RectF();
    // Lyric area for this frame (moved aside when a cover photo is shown).
    private float areaW = Float.MAX_VALUE, areaY = -1;

    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint fx = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint image = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Matrix matrix = new Matrix();
    private final RectF rect = new RectF();
    private final ColorFilter[] filters = new ColorFilter[Style.PHOTO_FILTERS.length];
    private final MaskFilter[] blurCache = new MaskFilter[64];

    // Per-frame values.
    private long now;
    private float u, lv;

    /** Points the renderer at a project's current data (for the live preview). */
    void bind(Project p) {
        lyrics = p.lyrics; style = p.style; title = p.title; artist = p.artist; editor = p.editor; envelope = p.envelope; duration = p.durationMs;
    }

    /** Independent copy for rendering on a background thread while the project keeps changing. */
    static LyricRenderer snapshot(Project p, PhotoSet photos, Typeface custom, Bitmap cover) {
        LyricRenderer r = new LyricRenderer();
        for (Lyrics.Line l : p.lyrics.lines) r.lyrics.lines.add(new Lyrics.Line(l.text, l.start));
        r.style = p.style.copy(); r.title = p.title; r.artist = p.artist; r.editor = p.editor; r.envelope = p.envelope.clone(); r.duration = p.durationMs;
        r.photos = photos; r.custom = custom; r.cover = cover;
        return r;
    }

    static Typeface loadFont(File f) { try { return f.isFile() ? Typeface.createFromFile(f) : null; } catch (Exception e) { return null; } }

    Typeface face() { return Fonts.get(style.font, custom); }

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
        now = t; u = Math.min(w, h) / 1080f; lv = level(t);
        background(c, w, h, t);
        boolean wide = style.landscape();
        float anchor = h * (style.position == 1 ? (wide ? .6f : .66f) : style.position == 2 ? .32f : .5f);
        float maxW = w * (wide ? Math.min(.78f, .55f + .12f * (style.ratio() - 1)) : .86f);
        if (style.aspect == 4) maxW = w * .62f;
        float size = 76 * u * style.textSize;
        // A cover photo takes its place first; the lyrics (and the visualizer under them) use the space that is left.
        float lx = w / 2f;
        areaW = Float.MAX_VALUE; areaY = -1;
        boolean showCover = style.cover && cover != null;
        if (showCover) {
            float[] area = coverLayout(w, h, anchor, maxW);
            lx = area[0]; anchor = area[1]; maxW = area[2]; areaW = maxW; areaY = anchor;
        }
        float bar = style.letterbox ? letterboxBar(w, h) : 0;
        if (style.visualizer) visualizer(c, w, h, t, bar, lx, showCover && lx != w / 2f ? maxW : -1);
        if (bar > 0) { fill.setColor(0xff000000); c.drawRect(0, 0, w, bar, fill); c.drawRect(0, h - bar, w, h, fill); }
        if (style.progressBar && duration > 0) progress(c, w, h, t);
        Lyrics.Frame f = lyrics.frame(t, duration);
        if (showCover) drawCover(c, w, h, t);
        c.save();
        c.translate(lx - w / 2f, 0);
        try { lyricsLayer(c, w, h, t, f, anchor, maxW, size); } finally { c.restore(); }
        watermark(c, w, h, bar);
    }

    // ------------------------------------------------------------------ watermark

    private final Paint mark = new Paint(Paint.ANTI_ALIAS_FLAG);

    /** Short watermark in a corner of every frame: pearl icon, "Pearl Lyrics · by Nico Pan Beats", and "✎ editor" if set. */
    void watermark(Canvas c, int w, int h, float bar) {
        String brand = "Pearl Lyrics", by = " · " + Style.APP_BY, ed = editor == null || editor.trim().isEmpty() ? null : "✎ " + editor.trim();
        float s1 = 20 * u, s2 = 16 * u, pad = 10 * u, icon = 24 * u, gapX = 7 * u;
        mark.setTextSize(s1);
        mark.setTypeface(Fonts.get(0, null)); float wb = mark.measureText(brand);
        mark.setTypeface(Fonts.get(1, null)); float wby = mark.measureText(by);
        mark.setTextSize(s2); float we = ed == null ? 0 : mark.measureText(ed);
        float textW = Math.max(wb + wby, we), boxW = pad + icon + gapX + textW + pad * 1.3f;
        float boxH = pad + s1 + (ed == null ? 0 : 4 * u + s2) + pad * .8f;
        int pos = style.watermarkPos;
        float margin = 24 * u;
        boolean top = pos < 2, right = pos == 0 || pos == 2;
        float x = right ? w - margin - boxW : margin;
        float y = top ? Math.max(margin, bar + 10 * u) : h - Math.max(margin, bar + 10 * u) - boxH;
        if (!top) y = Math.min(y, h - (style.progressBar ? 66 : 28) * u - boxH); // above the progress bar
        mark.setColor(0x4d000000);
        rect.set(x, y, x + boxW, y + boxH);
        c.drawRoundRect(rect, boxH / 2, boxH / 2, mark);
        float cx = x + pad + icon / 2, cy = y + pad + s1 * .5f, r = icon / 2;
        mark.setShader(new RadialGradient(cx - r * .35f, cy - r * .4f, r * 1.4f, new int[]{0xffffffff, 0xfff3eefc, 0xffd9cfee, 0xffb9b0d6}, new float[]{0, .35f, .75f, 1}, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, r, mark);
        mark.setShader(null);
        mark.setColor(0xccffffff);
        c.drawCircle(cx - r * .38f, cy - r * .4f, r * .2f, mark);
        float tx = x + pad + icon + gapX, ty = y + pad + s1 * .82f;
        mark.setTextSize(s1);
        mark.setTypeface(Fonts.get(0, null)); mark.setColor(0xe6ffffff);
        c.drawText(brand, tx, ty, mark);
        mark.setTypeface(Fonts.get(1, null)); mark.setColor(0xccffe9b0);
        c.drawText(by, tx + wb, ty, mark);
        if (ed != null) { mark.setTextSize(s2); mark.setColor(0xb3ffffff); c.drawText(ed, tx, ty + 4 * u + s2, mark); }
    }

    private void lyricsLayer(Canvas c, int w, int h, long t, Lyrics.Frame f, float anchor, float maxW, float size) {
        // A cover with its caption already shows the title and artist, so the big title card is not repeated.
        boolean card = style.titleCard && !(style.cover && cover != null && style.coverCaption);
        if (f.untilFirst > 0 && card) titleCard(c, w, h, t, f.untilFirst);
        if (f.untilFirst > 0 && card && style.animation != 2) return;
        if (style.animation >= 5 && style.animation != 12) exitPrevious(c, w, f, anchor, maxW, size);
        switch (style.animation) {
            case 1: fadeRise(c, w, f, anchor, maxW, size); break;
            case 2: scrolling(c, w, f, anchor, maxW, size, f.untilFirst > 0 && card); break;
            case 3: typewriter(c, w, f, anchor, maxW, size); break;
            case 4: wordPop(c, w, f, anchor, maxW, size); break;
            case 5: neon(c, w, f, anchor, maxW, size); break;
            case 6: blurFocus(c, w, f, anchor, maxW, size); break;
            case 7: slideWords(c, w, f, anchor, maxW, size); break;
            case 8: zoomBurst(c, w, f, anchor, maxW, size); break;
            case 9: cascade(c, w, f, anchor, maxW, size); break;
            case 10: wave(c, w, f, anchor, maxW, size); break;
            case 11: cinematic(c, w, f, anchor, maxW, size); break;
            case 12: highlighter(c, w, f, anchor, maxW, size); break;
            default: karaoke(c, w, f, anchor, maxW, size); break;
        }
    }

    /** Height of each cinematic bar: crops to 2.39:1 in wide frames, a slim band in tall ones. */
    float letterboxBar(int w, int h) { float target = (h - w / 2.39f) / 2; return target > h * .02f ? Math.min(target, h * .1f) : h * .06f; }

    // ------------------------------------------------------------------ cover photo

    /**
     * Places the cover (coverRect) and returns the lyric area {centre x, anchor y, width}. Left/Right in wide frames
     * put the cover and the lyrics side by side; in tall frames the cover sits above the lyrics.
     */
    float[] coverLayout(int w, int h, float anchor, float maxW) {
        boolean wide = w > h * 1.05f;
        int pos = style.coverPos;
        float side = Math.min(w, h) * style.coverSize, cx, cy, lx = w / 2f, ly = anchor, lw = maxW;
        float caption = style.coverCaption ? 120 * u : 0;
        if (wide && (pos == 0 || pos == 2)) {
            side = Math.min(side * 1.25f, Math.min(h * .62f, w * .36f));
            cx = pos == 0 ? w * .27f : w * .73f;
            cy = h * .47f - caption * .35f;
            lx = pos == 0 ? w * .665f : w * .335f;
            lw = w * .5f;
        } else if (!wide && (pos == 0 || pos == 2)) {
            side = Math.min(side * .8f, w * .42f);
            cx = pos == 0 ? w * .07f + side / 2 : w * .93f - side / 2;
            cy = h * .3f;
            ly = Math.max(anchor, cy + side / 2 + h * .17f);
        } else {
            side = Math.min(side, wide ? h * (pos == 3 ? .36f : .46f) : w * (pos == 3 ? .6f : .72f));
            cx = w / 2f;
            cy = pos == 3 ? h * (wide ? .08f : .1f) + side / 2 : h * (wide ? .36f : .37f);
            ly = Math.max(anchor, cy + side / 2 + caption + h * (wide ? .13f : .1f));
        }
        // Keep room for two lines of lyrics above the visualizer: shrink the cover if it would push them too low.
        if (!(wide && (pos == 0 || pos == 2))) {
            float limit = h * (w > h * .85f ? .66f : .7f);
            for (int i = 0; i < 12 && ly > limit; i++) {
                float shrink = side * .1f;
                side -= shrink;
                if (!(pos == 0 || pos == 2) || wide) cy -= pos == 3 ? shrink / 2 : shrink * .25f;
                ly = Math.max(anchor, cy + side / 2 + (pos == 0 || pos == 2 ? h * .17f : caption + h * (wide ? .13f : .1f)));
            }
        }
        ly = Math.min(ly, h * (wide ? .78f : .8f));
        coverRect.set(cx - side / 2, cy - side / 2, cx + side / 2, cy + side / 2);
        return new float[]{lx, ly, lw};
    }

    void drawCover(Canvas c, int w, int h, long t) {
        RectF r = coverRect;
        float side = r.width(), R = side / 2, cx = r.centerX(), cy = r.centerY();
        float pulse = style.coverPulse ? 1 + .035f * lv : 1;
        int shape = style.coverShape;
        c.save();
        c.scale(pulse, pulse, cx, cy);
        // Soft drop shadow.
        fill.setColor(0xff000000);
        fill.setShadowLayer(side * .07f, 0, side * .035f, 0xa0000000);
        float radius = shape == 0 ? 0 : shape == 1 ? side * .07f : R;
        c.drawRoundRect(r, radius, radius, fill);
        fill.clearShadowLayer();
        if (coverShader == null || coverShaderFrom != cover) { coverShader = new BitmapShader(cover, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP); coverShaderFrom = cover; }
        float bw = cover.getWidth(), bh = cover.getHeight();
        if (shape == 3) {
            // Vinyl record: grooved black disc, the cover as the centre label, turning at 33⅓ rpm.
            float angle = (t * .2f) % 360;
            fill.setShader(new RadialGradient(cx, cy, R, new int[]{0xff1c1c1c, 0xff0b0b0b, 0xff181818, 0xff050505}, new float[]{0, .45f, .8f, 1}, Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, R, fill);
            fill.setShader(null);
            fill.setStyle(Paint.Style.STROKE);
            fill.setStrokeWidth(Math.max(1, 1.2f * u));
            for (int k = 0; k < 22; k++) { fill.setColor(k % 3 == 0 ? 0x22ffffff : 0x14ffffff); c.drawCircle(cx, cy, R * (.47f + .5f * k / 22f), fill); }
            fill.setStyle(Paint.Style.FILL);
            float label = R * .44f, sc = 2 * label / Math.min(bw, bh);
            coverMatrix.setScale(sc, sc);
            coverMatrix.postTranslate(cx - bw * sc / 2, cy - bh * sc / 2);
            coverMatrix.postRotate(angle, cx, cy);
            coverShader.setLocalMatrix(coverMatrix);
            coverPaint.setShader(coverShader);
            c.drawCircle(cx, cy, label, coverPaint);
            fill.setColor(0xff0a0a0a);
            c.drawCircle(cx, cy, R * .035f, fill);
            // Light catching the grooves (stays still while the record turns).
            fill.setShader(new SweepGradient(cx, cy, new int[]{0x00ffffff, 0x1effffff, 0x00ffffff, 0x00ffffff, 0x1effffff, 0x00ffffff}, new float[]{0, .08f, .16f, .5f, .58f, .66f}));
            c.drawCircle(cx, cy, R, fill);
            fill.setShader(null);
        } else {
            float sc = side / Math.min(bw, bh);
            coverMatrix.setScale(sc, sc);
            coverMatrix.postTranslate(cx - bw * sc / 2, cy - bh * sc / 2);
            coverShader.setLocalMatrix(coverMatrix);
            coverPaint.setShader(coverShader);
            c.drawRoundRect(r, radius, radius, coverPaint);
            fill.setStyle(Paint.Style.STROKE);
            fill.setStrokeWidth(Math.max(1, 2 * u));
            fill.setColor(0x33ffffff);
            c.drawRoundRect(r, radius, radius, fill);
            fill.setStyle(Paint.Style.FILL);
        }
        c.restore();
        if (style.coverCaption) coverCaption(c, w, h);
    }

    /** "Now playing" caption: title and artist under the cover, or beside it when the cover sits at the side of a tall frame. */
    void coverCaption(Canvas c, int w, int h) {
        RectF r = coverRect;
        boolean wide = w > h * 1.05f, beside = !wide && (style.coverPos == 0 || style.coverPos == 2);
        String ti = title == null || title.trim().isEmpty() ? "" : title.trim(), ar = artist == null ? "" : artist.trim();
        if (style.uppercase) { ti = ti.toUpperCase(Locale.getDefault()); ar = ar.toUpperCase(Locale.getDefault()); }
        float maxW = beside ? w - r.width() - w * .2f : Math.max(r.width() * 1.3f, Math.min(w * .8f, r.width() * 1.6f));
        float tx, ty;
        boolean left = beside && style.coverPos == 0, right = beside && style.coverPos == 2;
        if (beside) { tx = left ? r.right + w * .05f : r.left - w * .05f; ty = r.centerY() - 10 * u; }
        else { tx = r.centerX(); ty = r.bottom + 70 * u * Math.max(1, style.coverPulse ? 1.03f : 1); }
        if (!ti.isEmpty()) {
            paint(46 * u, style.textColor, 1);
            fitRow(ti, maxW, 46 * u);
            float tw = text.measureText(ti);
            glyphs(c, ti, right ? tx - tw : left ? tx : tx - tw / 2, ty, style.textColor, 1);
        }
        if (!ar.isEmpty()) {
            paint(34 * u, style.highlight, 1);
            fitRow(ar, maxW, 34 * u);
            float aw = text.measureText(ar);
            glyphs(c, ar, right ? tx - aw : left ? tx : tx - aw / 2, ty + 50 * u, style.highlight, .95f);
        }
    }

    /** The cover, blurred, as the background ("Cover, blurred"). */
    boolean coverBackground(Canvas c, int w, int h, long t) {
        if (cover == null) return false;
        float amount = Math.round(Math.max(.15f, style.photoBlur) * PhotoSet.BLUR_STEPS) / (float) PhotoSet.BLUR_STEPS;
        if (coverSoft == null || coverSoftFrom != cover || amount != coverSoftAmount) { coverSoft = PhotoSet.blur(cover, amount); coverSoftFrom = cover; coverSoftAmount = amount; }
        Bitmap b = coverSoft;
        float p = duration > 0 ? Math.min(1, t / (float) duration) : 0;
        float s = Math.max(w / (float) b.getWidth(), h / (float) b.getHeight()) * (style.kenBurns ? 1.08f + .1f * p : 1.08f) * (1 + .015f * lv);
        place(b.getWidth(), b.getHeight(), s, w / 2f, h / 2f);
        image.setColorFilter(filter(style.photoFilter));
        image.setAlpha(255);
        c.drawBitmap(b, matrix, image);
        image.setColorFilter(null);
        c.drawColor(Color.argb(Math.round(style.dim * 255), 0, 0, 0));
        return true;
    }

    String shown(int i) { String s = lyrics.lines.get(i).text; return style.uppercase ? s.toUpperCase(Locale.getDefault()) : s; }

    // ------------------------------------------------------------------ background and decorations

    void background(Canvas c, int w, int h, long t) {
        int[] p = Style.PALETTES[style.palette];
        if (style.background == 4 && videoUnder) c.drawColor(Color.argb(Math.round(style.dim * 255), 0, 0, 0)); // video is underneath: only darken
        else if (style.background == 1) c.drawColor(style.solid);
        else if (style.background == 2 && photos.size() > 0 && photoBackground(c, w, h, t)) { /* drawn */ }
        else if (style.background == 3 && coverBackground(c, w, h, t)) { /* drawn */ }
        else {
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

    /** One photo, or a slideshow with cross-fades; each photo drifts and zooms a little (Ken Burns). */
    boolean photoBackground(Canvas c, int w, int h, long t) {
        int n = photos.size();
        if (photos.photo(0) == null) return false;
        c.drawColor(0xff000000);
        image.setColorFilter(filter(style.photoFilter));
        if (n == 1) drawPhoto(c, w, h, 0, duration > 0 ? Math.min(1, t / (float) duration) : 0, 1);
        else {
            long slot = style.photoSeconds > 0 ? style.photoSeconds * 1000L : Math.max(4000, (duration > 0 ? duration : 60000) / n);
            long fade = Math.min(1200, slot / 3);
            int i = (int) (t / slot);
            long local = t - i * slot;
            drawPhoto(c, w, h, i, local / (float) slot, 1);
            if (local > slot - fade) drawPhoto(c, w, h, i + 1, (local - slot) / (float) slot, Lyrics.ease((local - (slot - fade)) / (float) fade));
        }
        image.setColorFilter(null);
        c.drawColor(Color.argb(Math.round(style.dim * 255), 0, 0, 0));
        return true;
    }

    void drawPhoto(Canvas c, int w, int h, int i, float p, float alpha) {
        Bitmap b = style.photoBlur > .01f ? photos.blurredBy(i, style.photoBlur) : photos.photo(i);
        if (b == null) return;
        p = Math.max(0, Math.min(1, p));
        image.setAlpha(Math.round(255 * alpha));
        float bw = b.getWidth(), bh = b.getHeight();
        boolean in = (i & 1) == 0;
        if (style.photoFit == 1) {
            Bitmap soft = photos.blurred(i);
            if (soft != null) {
                float s = Math.max(w / (float) soft.getWidth(), h / (float) soft.getHeight()) * 1.1f;
                place(soft.getWidth(), soft.getHeight(), s, w / 2f, h / 2f);
                c.drawBitmap(soft, matrix, image);
                fill.setColor(Color.argb(Math.round(90 * alpha), 0, 0, 0));
                c.drawRect(0, 0, w, h, fill);
            }
            float zoom = style.kenBurns ? (in ? 1 + .05f * p : 1.05f - .05f * p) : 1;
            float s = Math.min(w / bw, h / bh) * .9f * zoom;
            place(bw, bh, s, w / 2f, h / 2f);
            c.drawBitmap(b, matrix, image);
        } else {
            float zoom = (style.kenBurns ? (in ? 1.04f + .12f * p : 1.16f - .12f * p) : 1f) * (1 + .01f * lv);
            float s = Math.max(w / bw, h / bh) * zoom;
            float dx = style.kenBurns ? (((i % 3) - 1) * .035f * (p - .5f)) * w : 0, dy = style.kenBurns ? (in ? -1 : 1) * .02f * (p - .5f) * h : 0;
            place(bw, bh, s, w / 2f + dx, h / 2f + dy);
            c.drawBitmap(b, matrix, image);
        }
        image.setAlpha(255);
    }

    void place(float bw, float bh, float scale, float cx, float cy) {
        matrix.reset();
        matrix.postTranslate(-bw / 2, -bh / 2);
        matrix.postScale(scale, scale);
        matrix.postTranslate(cx, cy);
    }

    ColorFilter filter(int i) {
        if (i <= 0) return null;
        if (filters[i] == null) filters[i] = new ColorMatrixColorFilter(colorMatrix(i));
        return filters[i];
    }

    /** The colour matrix of a photo/video filter (null for natural). */
    static ColorMatrix colorMatrix(int i) {
        if (i <= 0) return null;
        ColorMatrix m = new ColorMatrix();
        switch (i) {
            case 1: m.setSaturation(0); m.postConcat(contrast(1.12f)); break;
            case 2: m.setSaturation(1.1f); m.postConcat(new ColorMatrix(new float[]{1.08f, 0, 0, 0, 8, 0, 1.0f, 0, 0, 2, 0, 0, .86f, 0, 0, 0, 0, 0, 1, 0})); break;
            case 3: m.setSaturation(1.05f); m.postConcat(new ColorMatrix(new float[]{.9f, 0, 0, 0, 0, 0, 1.0f, 0, 0, 4, 0, 0, 1.12f, 0, 10, 0, 0, 0, 1, 0})); break;
            case 4: m.setSaturation(.45f); m.postConcat(new ColorMatrix(new float[]{1.05f, .1f, 0, 0, 18, .05f, .95f, .05f, 0, 12, 0, .1f, .8f, 0, 10, 0, 0, 0, 1, 0})); m.postConcat(contrast(.9f)); break;
            default: m.setSaturation(1.18f); m.postConcat(contrast(1.35f)); break;
        }
        return m;
    }

    static ColorMatrix contrast(float k) { float o = 128 * (1 - k); return new ColorMatrix(new float[]{k, 0, 0, 0, o, 0, k, 0, 0, o, 0, 0, k, 0, o, 0, 0, 0, 1, 0}); }

    void visualizer(Canvas c, int w, int h, long t, float bar, float centre, float width) {
        int half = 22;
        float span = width > 0 ? width * .9f : w * (style.landscape() ? .5f : .8f), gap = span / (half * 2), bw = gap * .52f;
        float base = h - Math.max(bar + 40 * u, (style.progressBar ? 120 : 90) * u);
        for (int k = 0; k < half; k++) {
            float v = level(t - k * 45L);
            float shape = 1 - k / (float) half * .55f;
            float bh = (5 + v * v * 95 * shape + v * 18) * u;
            int alpha = Math.round(255 * (.85f - .55f * k / half));
            fill.setColor((style.highlight & 0xffffff) | (alpha << 24));
            for (int side = -1; side <= 1; side += 2) {
                float x = centre + side * (k * gap + gap / 2);
                rect.set(x - bw / 2, base - bh, x + bw / 2, base + bh * .35f);
                c.drawRoundRect(rect, bw / 2, bw / 2, fill);
            }
        }
    }

    void progress(Canvas c, int w, int h, long t) {
        float y = h - 46 * u, x0 = w * .08f, x1 = w * .92f, th = 5 * u;
        fill.setColor(0x33ffffff);
        rect.set(x0, y - th / 2, x1, y + th / 2); c.drawRoundRect(rect, th, th, fill);
        float p = Math.max(0, Math.min(1, t / (float) duration));
        fill.setColor(style.highlight);
        rect.set(x0, y - th / 2, x0 + (x1 - x0) * p, y + th / 2); c.drawRoundRect(rect, th, th, fill);
    }

    void titleCard(Canvas c, int w, int h, long t, long untilFirst) {
        float alpha = untilFirst == Long.MAX_VALUE ? 1 : Lyrics.clamp((untilFirst - 250) / 650f);
        alpha *= Lyrics.clamp(t / 500f + .35f);
        if (alpha <= 0) return;
        float maxW = Math.min(areaW, w * (style.landscape() ? .7f : .82f)), size = (areaY > 0 ? 84 : 104) * u * Math.min(1.25f, style.textSize);
        String ti = title == null || title.trim().isEmpty() ? "Untitled" : title.trim();
        if (style.uppercase) ti = ti.toUpperCase(Locale.getDefault());
        paint(size, style.textColor, alpha);
        ArrayList<String> rows = fit(ti, maxW, size, 3);
        size = text.getTextSize();
        float lh = size * 1.12f, top = (areaY > 0 ? areaY : h * .47f) - rows.size() * lh / 2;
        if (artist != null && !artist.trim().isEmpty()) top -= 30 * u;
        float rise = (1 - Lyrics.ease(Lyrics.clamp(t / 900f))) * 30 * u;
        for (int r = 0; r < rows.size(); r++) glyphs(c, rows.get(r), w / 2f - text.measureText(rows.get(r)) / 2, top + r * lh + lh * .8f + rise, style.textColor, alpha);
        float y = top + rows.size() * lh + 22 * u + rise;
        fill.setColor(applyAlpha(style.highlight, alpha));
        rect.set(w / 2f - 60 * u, y, w / 2f + 60 * u, y + 7 * u); c.drawRoundRect(rect, 4 * u, 4 * u, fill);
        float below = y + 40 * u;
        if (artist != null && !artist.trim().isEmpty()) {
            paint(50 * u, style.highlight, alpha);
            ArrayList<String> arRows = fit(artist.trim(), maxW, 50 * u, 2);
            float s = text.getTextSize();
            for (int r = 0; r < arRows.size(); r++) glyphs(c, arRows.get(r), w / 2f - text.measureText(arRows.get(r)) / 2, y + 40 * u + s + r * s * 1.15f, style.highlight, alpha);
            below = y + 40 * u + s * 1.15f * arRows.size();
        }
        if (style.creditOnTitle && editor != null && !editor.trim().isEmpty()) {
            String credit = "Lyrics video by " + editor.trim();
            paint(32 * u, style.textColor, alpha * .85f);
            fitRow(credit, maxW, 32 * u);
            glyphs(c, credit, w / 2f - text.measureText(credit) / 2, below + 50 * u, style.textColor, alpha * .85f);
        }
    }

    // ------------------------------------------------------------------ text helpers

    static int applyAlpha(int color, float a) { return (color & 0xffffff) | (Math.round(Color.alpha(color) * Lyrics.clamp(a)) << 24); }

    static float luminance(int c) { return (.299f * Color.red(c) + .587f * Color.green(c) + .114f * Color.blue(c)) / 255f; }

    void paint(float size, int color, float alpha) {
        text.setTypeface(face());
        text.setTextSize(size);
        text.setLetterSpacing(style.letterSpacing);
        text.setColor(applyAlpha(color, alpha));
        text.setTextAlign(Paint.Align.LEFT);
        text.setMaskFilter(null);
        shadow(alpha);
    }

    void shadow(float alpha) {
        float size = text.getTextSize();
        if (style.shadow && alpha > 0.02f) text.setShadowLayer(size * .14f, 0, size * .05f, Color.argb(Math.round(150 * alpha), 0, 0, 0));
        else text.clearShadowLayer();
    }

    MaskFilter blur(float radius) {
        int k = Math.max(1, Math.min(blurCache.length - 1, Math.round(radius / Math.max(.5f, u))));
        if (blurCache[k] == null) blurCache[k] = new BlurMaskFilter(Math.max(1, k * Math.max(.5f, u)), BlurMaskFilter.Blur.NORMAL);
        return blurCache[k];
    }

    /** Draws text with the chosen effects (glow, outline), then the text itself. */
    void glyphs(Canvas c, String s, float x, float y, int color, float alpha) { glyphs(c, s, 0, s.length(), x, y, color, alpha); }

    void glyphs(Canvas c, String s, int start, int end, float x, float y, int color, float alpha) {
        if (alpha <= 0.004f || end <= start) return;
        float size = text.getTextSize();
        if (style.glow) {
            fx.set(text); fx.clearShadowLayer();
            fx.setColor(applyAlpha(style.highlight, alpha * .85f));
            fx.setMaskFilter(blur(size * .22f * (1 + .35f * lv)));
            c.drawText(s, start, end, x, y, fx);
        }
        if (style.outline) {
            fx.set(text); fx.clearShadowLayer(); fx.setMaskFilter(text.getMaskFilter());
            fx.setStyle(Paint.Style.STROKE); fx.setStrokeJoin(Paint.Join.ROUND); fx.setStrokeWidth(size * .09f);
            fx.setColor(applyAlpha(0xff000000, alpha * .9f));
            c.drawText(s, start, end, x, y, fx);
        }
        if (style.shadow) shadow(alpha);
        text.setColor(applyAlpha(color, alpha));
        c.drawText(s, start, end, x, y, text);
    }

    /** Caption box behind one row (Style "caption box"). */
    void boxBehind(Canvas c, float x, float baseline, float width, float alpha) {
        if (!style.box || alpha <= 0) return;
        float size = text.getTextSize(), pad = size * .28f;
        fill.setColor(Color.argb(Math.round(150 * Lyrics.clamp(alpha)), 0, 0, 0));
        rect.set(x - pad, baseline - size * .95f, x + width + pad, baseline + size * .3f);
        c.drawRoundRect(rect, size * .18f, size * .18f, fill);
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
        }
        shadow(1);
        return rows;
    }

    /** Single row, shrunk to the width. */
    float fitRow(String s, float maxW, float size) {
        text.setTextSize(size);
        float m = text.measureText(s);
        if (m > maxW) { size = size * maxW / m; text.setTextSize(size); }
        shadow(1);
        return size;
    }

    static ArrayList<String> one(String s) { ArrayList<String> l = new ArrayList<>(); l.add(s); return l; }

    float lineHeight() { return text.getTextSize() * 1.2f; }
    float blockHeight(int rows) { return rows * lineHeight(); }
    float baseline(ArrayList<String> rows, int r, float y) { float lh = lineHeight(); return y - rows.size() * lh / 2 + r * lh + lh * .78f; }

    /** Draws a wrapped block centred on y; wipe (0..1, or -1 for none) paints the sung part in the highlight colour. */
    void block(Canvas c, int w, ArrayList<String> rows, float y, float wipe, int base, int high, float alpha) {
        float size = text.getTextSize(), total = 0;
        float[] widths = new float[rows.size()];
        for (int r = 0; r < rows.size(); r++) { widths[r] = text.measureText(rows.get(r)); total += widths[r]; }
        float filled = wipe < 0 ? 0 : wipe * total, done = 0;
        for (int r = 0; r < rows.size(); r++) boxBehind(c, w / 2f - widths[r] / 2, baseline(rows, r, y), widths[r], alpha);
        for (int r = 0; r < rows.size(); r++) {
            float x = w / 2f - widths[r] / 2, bl = baseline(rows, r, y);
            float part = Math.max(0, Math.min(widths[r], filled - done));
            done += widths[r];
            glyphs(c, rows.get(r), x, bl, base, alpha);
            if (part > 0) {
                c.save();
                c.clipRect(x - size, bl - size * 1.4f, x + part, bl + size * .6f);
                text.setColor(applyAlpha(high, alpha));
                c.drawText(rows.get(r), x, bl, text);
                c.restore();
            }
        }
    }

    /** One word or letter placed on screen. */
    static final class Piece { String s; int row, start, end; float x, y, w; }

    /** Word (letters=false) or letter positions of a centred, wrapped block. */
    ArrayList<Piece> pieces(ArrayList<String> rows, int w, float y, boolean letters) {
        ArrayList<Piece> out = new ArrayList<>();
        for (int r = 0; r < rows.size(); r++) {
            String row = rows.get(r);
            float x0 = w / 2f - text.measureText(row) / 2, bl = baseline(rows, r, y);
            int i = 0;
            while (i < row.length()) {
                if (row.charAt(i) == ' ') { i++; continue; }
                int j = letters ? i + Character.charCount(row.codePointAt(i)) : row.indexOf(' ', i);
                if (j < 0) j = row.length();
                Piece p = new Piece();
                p.s = row; p.row = r; p.start = i; p.end = j;
                p.x = x0 + text.measureText(row, 0, i); p.y = bl; p.w = text.measureText(row, i, j);
                out.add(p);
                i = j;
            }
        }
        return out;
    }

    void boxes(Canvas c, ArrayList<String> rows, int w, float y, float alpha) {
        if (!style.box) return;
        for (int r = 0; r < rows.size(); r++) { float rw = text.measureText(rows.get(r)); boxBehind(c, w / 2f - rw / 2, baseline(rows, r, y), rw, alpha); }
    }

    static float easeOutCubic(float x) { x = Lyrics.clamp(x); float k = 1 - x; return 1 - k * k * k; }
    static float easeOutBack(float x) { x = Lyrics.clamp(x); float k = x - 1; return 1 + 2.4f * k * k * k + 1.4f * k * k; }

    // ------------------------------------------------------------------ animations

    /** Shared exit for the newer effects: the previous line floats up and fades as the new one arrives. */
    void exitPrevious(Canvas c, int w, Lyrics.Frame f, float y, float maxW, float size) {
        if (f.previous < 0 || f.index < 0 || f.change >= 1) return;
        paint(size, style.textColor, 1);
        ArrayList<String> rows = fit(shown(f.previous), maxW, size, 3);
        // Leave quickly and clear the space, so the old and new lines never sit on top of each other.
        float go = Lyrics.clamp(f.change * 2.2f), k = 1 - go, a = k * k * .85f, lift = go * (blockHeight(rows.size()) * .5f + text.getTextSize() * .9f);
        if (k <= 0) return;
        if (style.animation == 6) text.setMaskFilter(blur(text.getTextSize() * .35f * go));
        block(c, w, rows, y - lift, -1, style.textColor, style.textColor, a);
        text.setMaskFilter(null);
    }

    void karaoke(Canvas c, int w, Lyrics.Frame f, float y, float maxW, float size) {
        float small = size * .6f, alpha = 1 - f.out;
        if (f.index < 0) {
            // Instrumental gap: show the next line faintly so the singer can prepare.
            float a = f.next >= 0 ? .45f * Lyrics.clamp(1 - (lyrics.lines.get(f.next).start - now) / 2500f) : 0;
            if (a > 0) {
                paint(size, style.textColor, a);
                block(c, w, fit(shown(f.next), maxW, size, 3), y, -1, style.textColor, style.highlight, a);
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
            fitRow(shown(f.previous), maxW, small);
            block(c, w, one(shown(f.previous)), y - bh / 2 - small * 1.1f + slide, -1, style.highlight, style.highlight, .55f * (1 - f.change));
        }
        if (f.next >= 0 && f.out == 0) {
            paint(small, style.textColor, 1);
            fitRow(shown(f.next), maxW, small);
            block(c, w, one(shown(f.next)), y + bh / 2 + small * 1.2f + slide, -1, style.textColor, style.textColor, .42f * f.change * alpha);
        }
    }

    void fadeRise(Canvas c, int w, Lyrics.Frame f, float y, float maxW, float size) {
        if (f.previous >= 0 && f.index >= 0 && f.change < 1) {
            paint(size, style.textColor, 1);
            block(c, w, fit(shown(f.previous), maxW, size, 3), y - f.change * 50 * u, -1, style.textColor, style.textColor, 1 - f.change);
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

    void typewriter(Canvas c, int w, Lyrics.Frame f, float y, float maxW, float size) {
        if (f.index < 0) return;
        float alpha = 1 - f.out;
        paint(size, style.textColor, alpha);
        ArrayList<String> rows = fit(shown(f.index), maxW, size, 3);
        int total = 0;
        for (String r : rows) total += r.length();
        int visible = Math.round(Lyrics.clamp(f.wipe * 1.15f) * total);
        float sz = text.getTextSize();
        int used = 0;
        float cursorX = w / 2f, cursorY = baseline(rows, 0, y);
        boxes(c, rows, w, y, alpha);
        for (int r = 0; r < rows.size(); r++) {
            String row = rows.get(r);
            float x = w / 2f - text.measureText(row) / 2, bl = baseline(rows, r, y);
            int n = Math.max(0, Math.min(row.length(), visible - used));
            used += row.length();
            if (n > 0) glyphs(c, row, 0, n, x, bl, style.textColor, alpha);
            if (n > 0 || r == 0) { cursorX = x + text.measureText(row, 0, n); cursorY = bl; }
        }
        if (visible < total || (now / 450) % 2 == 0) {
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
        ArrayList<Piece> words = pieces(rows, w, y, false);
        float sz = text.getTextSize();
        int n = words.size();
        boxes(c, rows, w, y, alpha * Math.min(1, f.in * 3));
        for (int i = 0; i < n; i++) {
            Piece p = words.get(i);
            float local = Lyrics.clamp((f.wordPos - i) / 1.3f + (i == 0 ? .25f * f.in : 0));
            if (local <= 0) continue;
            float scale = easeOutBack(local);
            c.save();
            c.scale(scale, scale, p.x + p.w / 2, p.y - sz * .35f);
            glyphs(c, p.s, p.start, p.end, p.x, p.y, local < 1 && f.wordPos < n ? style.highlight : style.textColor, alpha * Math.min(1, local * 2));
            c.restore();
        }
    }

    /** Neon sign: the line flickers on and glows with the music; sung words burn brighter. */
    void neon(Canvas c, int w, Lyrics.Frame f, float y, float maxW, float size) {
        if (f.index < 0) return;
        float alpha = Lyrics.clamp(f.in * 1.5f) * (1 - f.out);
        if (f.in < 1) alpha *= Math.sin(f.in * 37) > -.3 ? 1 : .2f;
        paint(size, style.textColor, alpha);
        ArrayList<String> rows = fit(shown(f.index), maxW, size, 3);
        float sz = text.getTextSize();
        boxes(c, rows, w, y, alpha);
        ArrayList<Piece> words = pieces(rows, w, y, false);
        int n = words.size();
        text.clearShadowLayer();
        for (int i = 0; i < n; i++) {
            Piece p = words.get(i);
            boolean lit = f.wordPos >= i + .3f;
            float a = alpha * (lit ? 1 : .55f);
            fx.set(text); fx.setMaskFilter(blur(sz * (.28f + .3f * lv)));
            fx.setColor(applyAlpha(style.highlight, a));
            c.drawText(p.s, p.start, p.end, p.x, p.y, fx);
            fx.setMaskFilter(blur(sz * .1f));
            c.drawText(p.s, p.start, p.end, p.x, p.y, fx);
            text.setColor(applyAlpha(lit ? mix(style.textColor, 0xffffffff, .5f) : mix(style.textColor, style.highlight, .4f), a));
            c.drawText(p.s, p.start, p.end, p.x, p.y, text);
        }
    }

    static int mix(int a, int b, float t) {
        return Color.argb(255, Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t), Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t), Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }

    /** Camera focus pull: the line arrives blurred and slightly large, then snaps sharp. */
    void blurFocus(Canvas c, int w, Lyrics.Frame f, float y, float maxW, float size) {
        if (f.index < 0) return;
        float e = easeOutCubic(f.in), alpha = Lyrics.clamp(f.in * 2) * (1 - f.out);
        paint(size, style.textColor, alpha);
        ArrayList<String> rows = fit(shown(f.index), maxW, size, 3);
        float r = text.getTextSize() * (.45f * (1 - e) + .4f * f.out);
        if (r > .6f * u) text.setMaskFilter(blur(r));
        float scale = 1.08f - .08f * e + .05f * f.out;
        c.save();
        c.scale(scale, scale, w / 2f, y);
        block(c, w, rows, y, f.wipe, style.textColor, style.highlight, alpha);
        c.restore();
        text.setMaskFilter(null);
    }

    /** Words rise into place one after another, the word being sung in the highlight colour. */
    void slideWords(Canvas c, int w, Lyrics.Frame f, float y, float maxW, float size) {
        if (f.index < 0) return;
        float alpha = 1 - f.out;
        paint(size, style.textColor, alpha);
        ArrayList<String> rows = fit(shown(f.index), maxW, size, 3);
        float sz = text.getTextSize();
        ArrayList<Piece> words = pieces(rows, w, y, false);
        int n = words.size(), singing = f.wordPos >= n ? -1 : (int) f.wordPos;
        boxes(c, rows, w, y, alpha * Math.min(1, f.in * 3));
        for (int i = 0; i < n; i++) {
            Piece p = words.get(i);
            // Each word starts its rise a little after the one before, a bit ahead of the singing.
            float local = Lyrics.clamp((f.in * Lyrics.FADE_MS + f.wipe * 2000 - i * 110) / 260f);
            if (local <= 0) continue;
            float e = easeOutCubic(local);
            glyphs(c, p.s, p.start, p.end, p.x, p.y + (1 - e) * sz * .75f - f.out * sz * .3f, i == singing ? style.highlight : style.textColor, alpha * e);
        }
    }

    /** The line punches in from large with motion trails, then settles. */
    void zoomBurst(Canvas c, int w, Lyrics.Frame f, float y, float maxW, float size) {
        if (f.index < 0) return;
        float e = easeOutCubic(f.in), alpha = Lyrics.clamp(f.in * 2.5f) * (1 - f.out);
        paint(size, style.textColor, alpha);
        ArrayList<String> rows = fit(shown(f.index), maxW, size, 3);
        float scale = 1.35f - .35f * e + .12f * f.out + .02f * lv;
        if (f.in < 1) for (int k = 2; k >= 1; k--) {
            float s = scale * (1 + .1f * k * (1 - e));
            c.save(); c.scale(s, s, w / 2f, y);
            block(c, w, rows, y, -1, style.highlight, style.highlight, alpha * .22f * (1 - e));
            c.restore();
        }
        c.save();
        c.scale(scale, scale, w / 2f, y);
        block(c, w, rows, y, f.wipe, style.textColor, style.highlight, alpha);
        c.restore();
    }

    /** Letters drop in one by one with a little bounce and tilt. */
    void cascade(Canvas c, int w, Lyrics.Frame f, float y, float maxW, float size) {
        if (f.index < 0) return;
        float alpha = 1 - f.out;
        paint(size, style.textColor, alpha);
        ArrayList<String> rows = fit(shown(f.index), maxW, size, 3);
        float sz = text.getTextSize();
        ArrayList<Piece> letters = pieces(rows, w, y, true);
        int n = letters.size();
        boxes(c, rows, w, y, alpha * Math.min(1, f.in * 3));
        for (int i = 0; i < n; i++) {
            Piece p = letters.get(i);
            float local = Lyrics.clamp((f.in * Lyrics.FADE_MS + f.wipe * 1500 - i * 28) / 240f);
            if (local <= 0) continue;
            float drop = (1 - easeOutBack(local)) * sz * .9f;
            c.save();
            c.rotate((1 - local) * -14, p.x + p.w / 2, p.y - sz * .35f);
            glyphs(c, p.s, p.start, p.end, p.x, p.y - drop + f.out * sz * .4f, local < 1 ? style.highlight : style.textColor, alpha * Math.min(1, local * 1.6f));
            c.restore();
        }
    }

    /** Letters ride a gentle wave that grows with the music; sung letters take the highlight colour. */
    void wave(Canvas c, int w, Lyrics.Frame f, float y, float maxW, float size) {
        if (f.index < 0) return;
        float alpha = Lyrics.ease(f.in) * (1 - f.out);
        paint(size, style.textColor, alpha);
        ArrayList<String> rows = fit(shown(f.index), maxW, size, 3);
        float sz = text.getTextSize();
        ArrayList<Piece> letters = pieces(rows, w, y, true);
        int n = letters.size();
        boxes(c, rows, w, y, alpha);
        for (int i = 0; i < n; i++) {
            Piece p = letters.get(i);
            float dy = (float) Math.sin(now * .0055 + i * .45) * sz * .07f * (.5f + lv) * (.4f + .6f * f.in);
            glyphs(c, p.s, p.start, p.end, p.x, p.y + dy, i < f.wipe * n ? style.highlight : style.textColor, alpha);
        }
    }

    /** Film-title style: wide letter spacing closes in, with fine rules above and below. */
    void cinematic(Canvas c, int w, Lyrics.Frame f, float y, float maxW, float size) {
        if (f.index < 0) return;
        float e = easeOutCubic(f.in), alpha = Lyrics.ease(f.in) * (1 - f.out);
        paint(size * .82f, style.textColor, alpha);
        text.setLetterSpacing(style.letterSpacing + .1f + .25f * (1 - e) + .05f * f.out);
        ArrayList<String> rows = fit(shown(f.index), maxW, size * .82f, 3);
        float bh = blockHeight(rows.size()), lineW = 0;
        for (String r : rows) lineW = Math.max(lineW, text.measureText(r));
        block(c, w, rows, y - (1 - e) * 12 * u, -1, style.textColor, style.textColor, alpha);
        float rw = lineW * .32f * e, th = Math.max(1, 2.5f * u);
        fill.setColor(applyAlpha(style.highlight, alpha * .9f));
        rect.set(w / 2f - rw / 2, y - bh / 2 - 26 * u, w / 2f + rw / 2, y - bh / 2 - 26 * u + th); c.drawRect(rect, fill);
        float rw2 = rw * f.wipe;
        rect.set(w / 2f - rw2 / 2, y + bh / 2 + 22 * u, w / 2f + rw2 / 2, y + bh / 2 + 22 * u + th); c.drawRect(rect, fill);
        text.setLetterSpacing(style.letterSpacing);
    }

    /** Social-caption style: a highlight block glides from word to word as they are sung. */
    void highlighter(Canvas c, int w, Lyrics.Frame f, float y, float maxW, float size) {
        if (f.previous >= 0 && f.index >= 0 && f.change < 1) {
            paint(size, style.textColor, 1);
            block(c, w, fit(shown(f.previous), maxW, size, 3), y - f.change * 40 * u, -1, style.textColor, style.textColor, (1 - f.change) * .7f);
        }
        if (f.index < 0) return;
        float alpha = Lyrics.ease(Lyrics.clamp(f.in * 1.6f)) * (1 - f.out);
        paint(size, style.textColor, alpha);
        ArrayList<String> rows = fit(shown(f.index), maxW, size, 3);
        float sz = text.getTextSize(), rise = (1 - easeOutCubic(f.in)) * 24 * u;
        ArrayList<Piece> words = pieces(rows, w, y + rise, false);
        int n = words.size();
        if (n == 0) return;
        boxes(c, rows, w, y + rise, alpha);
        float p = Math.min(n - .001f, f.wordPos);
        int k = (int) p;
        Piece cur = words.get(k);
        float e = k > 0 ? Lyrics.ease(Lyrics.clamp((p - k) / .25f)) : 1;
        float pad = sz * .16f;
        float x0 = cur.x - pad, x1 = cur.x + cur.w + pad;
        if (k > 0 && e < 1) {
            Piece prev = words.get(k - 1);
            if (prev.row == cur.row) { x0 = prev.x - pad + (x0 - (prev.x - pad)) * e; x1 = prev.x + prev.w + pad + (x1 - (prev.x + prev.w + pad)) * e; }
        }
        fill.setColor(applyAlpha(style.highlight, alpha));
        rect.set(x0, cur.y - sz * .9f, x1, cur.y + sz * .26f);
        c.drawRoundRect(rect, sz * .16f, sz * .16f, fill);
        int onBox = luminance(style.highlight) > .55f ? 0xff141414 : 0xffffffff;
        boolean keepShadow = style.shadow;
        for (int i = 0; i < n; i++) {
            Piece q = words.get(i);
            boolean boxed = i == k && (e > .5f || k == 0);
            if (boxed) { style.shadow = false; text.clearShadowLayer(); }
            glyphs(c, q.s, q.start, q.end, q.x, q.y, boxed ? onBox : style.textColor, alpha * (i > k ? .78f : 1));
            style.shadow = keepShadow;
            if (boxed) shadow(alpha);
        }
    }
}
