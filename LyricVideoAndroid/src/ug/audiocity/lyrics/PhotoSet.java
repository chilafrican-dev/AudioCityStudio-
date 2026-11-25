package ug.audiocity.lyrics;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The project's photos for one renderer. Only a few are decoded at a time (a slideshow needs the current and the next
 * one), so twenty full-size photos never sit in memory together.
 */
final class PhotoSet {
    final File[] files;
    final int maxSide;
    private final LinkedHashMap<Integer, Bitmap[]> cache = new LinkedHashMap<Integer, Bitmap[]>(4, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Integer, Bitmap[]> e) { return size() > 3; }
    };

    PhotoSet(File[] files, int maxSide) { this.files = files == null ? new File[0] : files; this.maxSide = maxSide; }

    int size() { return files.length; }

    /** The photo (index wraps around), or null when it cannot be read. */
    Bitmap photo(int i) { Bitmap[] b = load(i); return b == null ? null : b[0]; }

    /** A soft, blurred copy for the "whole photo on blurred background" look. */
    Bitmap blurred(int i) { Bitmap[] b = load(i); return b == null ? null : b[1]; }

    static final int BLUR_STEPS = 8;

    /** The photo blurred by amount (0 sharp … 1 very soft), made once per strength and kept with the photo. */
    synchronized Bitmap blurredBy(int i, float amount) {
        Bitmap[] b = load(i);
        if (b == null) return null;
        int step = Math.round(Math.max(0, Math.min(1, amount)) * BLUR_STEPS);
        if (step == 0) return b[0];
        if (b[1 + step] == null) b[1 + step] = blur(b[0], step / (float) BLUR_STEPS);
        return b[1 + step];
    }

    /**
     * Smooth blur: shrinks the photo to a quarter, then runs three box-blur passes each way (very close to a Gaussian
     * blur, without blocky steps). amount 0..1; the result is smaller than the source and is drawn scaled up.
     */
    static Bitmap blur(Bitmap src, float amount) {
        if (src == null || amount <= .01f) return src;
        int w = src.getWidth(), h = src.getHeight();
        float shrink = Math.max(1, Math.max(w, h) / 360f);
        int sw = Math.max(4, Math.round(w / shrink)), sh = Math.max(4, Math.round(h / shrink));
        Bitmap small = Bitmap.createScaledBitmap(src, sw, sh, true);
        int[] px = new int[sw * sh];
        small.getPixels(px, 0, sw, 0, 0, sw, sh);
        int radius = Math.max(1, Math.round(amount * 22));
        int[] tmp = new int[px.length];
        for (int pass = 0; pass < 3; pass++) { boxBlur(px, tmp, sw, sh, radius, true); boxBlur(tmp, px, sw, sh, radius, false); }
        Bitmap out = small.isMutable() && small != src ? small : Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888);
        out.setPixels(px, 0, sw, 0, 0, sw, sh);
        return out;
    }

    /** One box-blur pass along rows (horizontal) or columns, with edge pixels repeated. */
    static void boxBlur(int[] in, int[] out, int w, int h, int r, boolean horizontal) {
        int lines = horizontal ? h : w, len = horizontal ? w : h, step = horizontal ? 1 : w, size = 2 * r + 1;
        for (int line = 0; line < lines; line++) {
            int base = horizontal ? line * w : line;
            int sa = 0, sr = 0, sg = 0, sb = 0;
            for (int k = -r; k <= r; k++) {
                int c = in[base + Math.max(0, Math.min(len - 1, k)) * step];
                sa += c >>> 24; sr += (c >> 16) & 255; sg += (c >> 8) & 255; sb += c & 255;
            }
            for (int i = 0; i < len; i++) {
                out[base + i * step] = ((sa / size) << 24) | ((sr / size) << 16) | ((sg / size) << 8) | (sb / size);
                int add = in[base + Math.min(len - 1, i + r + 1) * step], sub = in[base + Math.max(0, i - r) * step];
                sa += (add >>> 24) - (sub >>> 24); sr += ((add >> 16) & 255) - ((sub >> 16) & 255);
                sg += ((add >> 8) & 255) - ((sub >> 8) & 255); sb += (add & 255) - (sub & 255);
            }
        }
    }

    private synchronized Bitmap[] load(int i) {
        if (files.length == 0) return null;
        i = ((i % files.length) + files.length) % files.length;
        Bitmap[] b = cache.get(i);
        if (b != null) return b;
        Bitmap full = decode(files[i], maxSide);
        if (full == null) return null;
        Bitmap soft = blur(full, .8f);
        b = new Bitmap[2 + BLUR_STEPS + 1];
        b[0] = full; b[1] = soft;
        cache.put(i, b);
        return b;
    }

    static Bitmap decode(File f, int maxSide) {
        if (f == null || !f.isFile()) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        int sample = 1;
        while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= maxSide) sample *= 2;
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inSampleSize = sample;
        return BitmapFactory.decodeFile(f.getAbsolutePath(), d);
    }
}
