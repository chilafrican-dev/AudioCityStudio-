package ug.audiocity.appvault;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.view.View;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * The home screen wallpaper and a blurred copy of it, used for the frosted-glass dock, widgets and menus.
 * It is Nico Pan Vault's own picture (a generated colour blend, or a photo the user picks), because
 * Android 13+ doesn't let apps read the system wallpaper without storage permission.
 */
final class Wallpaper {
    private static Bitmap sharp;
    private static Bitmap blurred;
    private static final Paint FILTER = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private static final Paint VIBRANT = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    static {
        ColorMatrix m = new ColorMatrix();
        m.setSaturation(1.6f); // frosted glass on iPhone looks more colourful than what's behind it
        VIBRANT.setColorFilter(new ColorMatrixColorFilter(m));
    }

    private Wallpaper() {}

    private static File file(Context c) { return new File(c.getFilesDir(), "wallpaper.png"); }

    static synchronized void ensureLoaded(Context c) {
        if (sharp != null) return;
        int w = c.getResources().getDisplayMetrics().widthPixels;
        int h = c.getResources().getDisplayMetrics().heightPixels;
        Bitmap b = null;
        File f = file(c);
        if (f.exists()) b = decodeFitting(f.getAbsolutePath(), w, h);
        if (b == null) b = generated(Math.max(1, w / 2), Math.max(1, h / 2));
        set(b);
    }

    private static void set(Bitmap b) {
        sharp = b;
        int sw = Math.max(8, b.getWidth() / 10), sh = Math.max(8, b.getHeight() / 10);
        Bitmap small = Bitmap.createScaledBitmap(b, sw, sh, true);
        blurred = blur(small.copy(Bitmap.Config.ARGB_8888, true), 3);
    }

    /** Saves the picked photo as the wallpaper. Returns false if it couldn't be read. */
    static synchronized boolean setFromUri(Context c, Uri uri) {
        int w = c.getResources().getDisplayMetrics().widthPixels;
        int h = c.getResources().getDisplayMetrics().heightPixels;
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = c.getContentResolver().openInputStream(uri)) { BitmapFactory.decodeStream(in, null, o); }
            o.inSampleSize = sampleSize(o.outWidth, o.outHeight, w, h);
            o.inJustDecodeBounds = false;
            Bitmap b;
            try (InputStream in = c.getContentResolver().openInputStream(uri)) { b = BitmapFactory.decodeStream(in, null, o); }
            if (b == null) return false;
            try (FileOutputStream out = new FileOutputStream(file(c))) { b.compress(Bitmap.CompressFormat.PNG, 100, out); }
            set(b);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static synchronized void reset(Context c) {
        //noinspection ResultOfMethodCallIgnored
        file(c).delete();
        sharp = null;
        ensureLoaded(c);
    }

    private static Bitmap decodeFitting(String path, int w, int h) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, o);
        o.inSampleSize = sampleSize(o.outWidth, o.outHeight, w, h);
        o.inJustDecodeBounds = false;
        return BitmapFactory.decodeFile(path, o);
    }

    private static int sampleSize(int bw, int bh, int w, int h) {
        int s = 1;
        while (bw / (s * 2) >= w && bh / (s * 2) >= h) s *= 2;
        return s;
    }

    /** The default wallpaper: soft overlapping colour glows on deep blue. */
    static Bitmap generated(int w, int h) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0, 0, 0, h, 0xFF1A2466, 0xFF070A1F, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        glow(c, p, 0.15f * w, 0.22f * h, 0.75f * w, 0xFF7B4DFF);
        glow(c, p, 0.95f * w, 0.12f * h, 0.60f * w, 0xFF2EC5FF);
        glow(c, p, 0.90f * w, 0.58f * h, 0.70f * w, 0xFFFF4F8B);
        glow(c, p, 0.20f * w, 0.88f * h, 0.75f * w, 0xFFFF9F43);
        glow(c, p, 0.50f * w, 0.45f * h, 0.45f * w, 0x99B44DFF);
        return b;
    }

    private static void glow(Canvas c, Paint p, float x, float y, float r, int color) {
        p.setShader(new RadialGradient(x, y, r, color, color & 0x00FFFFFF, Shader.TileMode.CLAMP));
        c.drawCircle(x, y, r, p);
    }

    /** Box blur, three passes (close to a Gaussian). Done on a tenth-size copy, so it's quick. */
    static Bitmap blur(Bitmap b, int radius) {
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h];
        int[] tmp = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        for (int pass = 0; pass < 3; pass++) {
            boxPass(px, tmp, w, h, radius, true);
            boxPass(tmp, px, w, h, radius, false);
        }
        b.setPixels(px, 0, w, 0, 0, w, h);
        return b;
    }

    private static void boxPass(int[] in, int[] out, int w, int h, int r, boolean horizontal) {
        int lines = horizontal ? h : w, len = horizontal ? w : h;
        int div = r * 2 + 1;
        for (int line = 0; line < lines; line++) {
            int sa = 0, sr = 0, sg = 0, sb = 0;
            for (int k = -r; k <= r; k++) {
                int c = in[idx(line, Math.max(0, Math.min(len - 1, k)), w, horizontal)];
                sa += c >>> 24; sr += (c >> 16) & 255; sg += (c >> 8) & 255; sb += c & 255;
            }
            for (int i = 0; i < len; i++) {
                out[idx(line, i, w, horizontal)] = ((sa / div) << 24) | ((sr / div) << 16) | ((sg / div) << 8) | (sb / div);
                int add = in[idx(line, Math.min(len - 1, i + r + 1), w, horizontal)];
                int rem = in[idx(line, Math.max(0, i - r), w, horizontal)];
                sa += (add >>> 24) - (rem >>> 24);
                sr += ((add >> 16) & 255) - ((rem >> 16) & 255);
                sg += ((add >> 8) & 255) - ((rem >> 8) & 255);
                sb += (add & 255) - (rem & 255);
            }
        }
    }

    private static int idx(int line, int i, int w, boolean horizontal) { return horizontal ? line * w + i : i * w + line; }

    /**
     * Draws the part of the wallpaper that lies behind {@code v}, so the view looks like glass over it.
     * The wallpaper fills the whole window, scaled to cover it.
     */
    static void drawBehind(Canvas canvas, View v, boolean frosted) {
        Bitmap b = frosted ? blurred : sharp;
        if (b == null) return;
        View root = v.getRootView();
        int[] loc = new int[2];
        v.getLocationInWindow(loc);
        canvas.save();
        canvas.translate(-loc[0], -loc[1]);
        canvas.concat(cover(b, root.getWidth(), root.getHeight()));
        canvas.drawBitmap(b, 0, 0, frosted ? VIBRANT : FILTER);
        canvas.restore();
    }

    static Matrix cover(Bitmap b, int w, int h) {
        float s = Math.max(w / (float) b.getWidth(), h / (float) b.getHeight());
        Matrix m = new Matrix();
        m.setScale(s, s);
        m.postTranslate((w - b.getWidth() * s) / 2f, (h - b.getHeight() * s) / 2f);
        return m;
    }

    /** A drawable that fills its bounds with the wallpaper (sharp or frosted) and an optional tint on top. */
    static Drawable drawable(boolean frosted, int tint) {
        return new Drawable() {
            @Override public void draw(Canvas c) {
                Bitmap b = frosted ? blurred : sharp;
                Rect r = getBounds();
                if (b != null) {
                    c.save();
                    c.translate(r.left, r.top);
                    c.concat(cover(b, r.width(), r.height()));
                    c.drawBitmap(b, 0, 0, frosted ? VIBRANT : FILTER);
                    c.restore();
                }
                if (tint != 0) c.drawColor(tint);
            }
            @Override public void setAlpha(int a) {}
            @Override public void setColorFilter(ColorFilter f) {}
            @Override public int getOpacity() { return PixelFormat.OPAQUE; }
        };
    }
}
