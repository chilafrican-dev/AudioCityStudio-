package ug.audiocity.cinema;

import android.content.res.AssetManager;

/**
 * The NicoPan LUT pack: .cube files shipped inside the APK (assets/luts), shown with ◆ in the editor's Colour tab.
 * A clip refers to one as "pack:<file name>". All expect normal-colour (Rec.709) input.
 */
final class LutPack {
  private LutPack() {}

  static final String PREFIX = "pack:", DIR = "luts";

  static boolean isPack(String lut) { return lut != null && lut.startsWith(PREFIX); }

  static String key(String file) { return PREFIX + file; }

  static String assetPath(String lut) { return DIR + "/" + lut.substring(PREFIX.length()); }

  static String label(String lut) { return (isPack(lut) ? lut.substring(PREFIX.length()) : lut).replaceAll("(?i)\\.cube$", ""); }

  static String[] list(AssetManager assets) {
    try {
      String[] f = assets.list(DIR);
      if (f == null) return new String[0];
      java.util.Arrays.sort(f);
      return f;
    } catch (java.io.IOException e) { return new String[0]; }
  }
}
