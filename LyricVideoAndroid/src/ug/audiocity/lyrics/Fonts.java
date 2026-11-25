package ug.audiocity.lyrics;

import android.content.res.AssetManager;
import android.graphics.Typeface;

import java.io.File;

/** The bundled professional fonts (assets/fonts), loaded once and shared by the preview and the exporter. */
final class Fonts {
    static AssetManager assets;
    /** Folder with the .ttf files, used instead of assets when rendering on a desktop for checks. */
    static File dir;
    private static final Typeface[] cache = new Typeface[Style.FONT_NAMES.length];

    static synchronized Typeface get(int i, Typeface imported) {
        if (i < 0 || i >= cache.length) i = 0;
        if (i == Style.FONT_IMPORTED) return imported != null ? imported : get(0, null);
        if (cache[i] != null) return cache[i];
        Typeface t = null;
        String file = Style.FONT_FILES[i];
        if (file != null) {
            try {
                if (dir != null) t = Typeface.createFromFile(new File(dir, file + ".ttf"));
                else if (assets != null) t = Typeface.createFromAsset(assets, "fonts/" + file + ".ttf");
            } catch (RuntimeException ignored) {}
        }
        if (t == null) t = Typeface.create("sans-serif-black", Typeface.NORMAL);
        return cache[i] = t;
    }
}
