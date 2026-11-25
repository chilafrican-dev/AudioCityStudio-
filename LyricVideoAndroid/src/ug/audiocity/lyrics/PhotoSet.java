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

    private synchronized Bitmap[] load(int i) {
        if (files.length == 0) return null;
        i = ((i % files.length) + files.length) % files.length;
        Bitmap[] b = cache.get(i);
        if (b != null) return b;
        Bitmap full = decode(files[i], maxSide);
        if (full == null) return null;
        // Down to 1/16 and back up to 1/4 with filtering: a cheap, smooth blur.
        int sw = Math.max(2, full.getWidth() / 16), sh = Math.max(2, full.getHeight() / 16);
        Bitmap tiny = Bitmap.createScaledBitmap(full, sw, sh, true);
        Bitmap soft = Bitmap.createScaledBitmap(tiny, Math.max(4, full.getWidth() / 4), Math.max(4, full.getHeight() / 4), true);
        if (tiny != soft) tiny.recycle();
        b = new Bitmap[]{full, soft};
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
