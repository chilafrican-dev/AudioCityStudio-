package ug.audiocity.cinema;

import java.util.Locale;

/**
 * City Cinema's own cinematic LUT pack (original designs, so no licensing limits): each look is a function of a
 * display-referred Rec.709 RGB value, built into a 33^3 CubeLut for the GPU or written as a .cube file.
 * Input is normal-colour video (log clips are converted to Rec.709 first).
 */
final class CinematicLuts {
  private CinematicLuts() {}

  static final String PREFIX = "builtin:";
  static final String[] NAMES = {"Teal & Orange", "Golden Hour Film", "Cool Thriller", "Bleach Bypass", "Vintage Fade", "Day for Night",
      "Noir", "Desert Heat", "Moody Forest", "Neon Night", "Soft Pastel", "Code Green"};

  static boolean isBuiltin(String lut) { return lut != null && lut.startsWith(PREFIX); }

  static String key(String name) { return PREFIX + name; }

  static String nameOf(String lut) { return isBuiltin(lut) ? lut.substring(PREFIX.length()) : lut; }

  static CubeLut build(String name) {
    final int n = 33;
    CubeLut l = new CubeLut();
    l.title = name + " (City Cinema)";
    l.size = n;
    l.data = new float[n * n * n * 3];
    int k = 0;
    for (int b = 0; b < n; b++) for (int g = 0; g < n; g++) for (int r = 0; r < n; r++) {
      double[] o = apply(name, r / (n - 1.0), g / (n - 1.0), b / (n - 1.0));
      for (int i = 0; i < 3; i++) l.data[k++] = (float) clamp(o[i]);
    }
    return l;
  }

  /** The .cube text of a look (for Resolve, Premiere, Final Cut, CapCut desktop, monitors). */
  static String cube(String name) {
    CubeLut l = build(name);
    StringBuilder s = new StringBuilder(l.data.length * 10);
    s.append("TITLE \"").append(l.title).append("\"\n# input: Rec.709 (normal colour). Made by City Cinema.\nLUT_3D_SIZE ").append(l.size).append('\n');
    for (int i = 0; i < l.data.length; i += 3) s.append(String.format(Locale.US, "%.6f %.6f %.6f%n", l.data[i], l.data[i + 1], l.data[i + 2]));
    return s.toString();
  }

  static double[] apply(String name, double r, double g, double b) {
    double[] c = {r, g, b};
    double y = luma(c);
    switch (name) {
      case "Teal & Orange": {
        c = curve(c, 0.08, 0.02);
        c = splitTone(c, new double[] {0.00, 0.42, 0.50}, new double[] {1.00, 0.62, 0.32}, 0.22);
        c = protectSkin(c, r, g, b);
        return saturate(c, 1.12);
      }
      case "Golden Hour Film": {
        c = curve(c, 0.06, 0.04);
        c = lift(c, new double[] {0.025, 0.015, 0.0});
        c = gain(c, new double[] {1.035, 1.0, 0.90});
        c = rolloff(c);
        c = protectSkin(c, r, g, b);
        return saturate(c, 0.98);
      }
      case "Cool Thriller": {
        c = curve(c, 0.12, -0.02);
        c = splitTone(c, new double[] {0.05, 0.40, 0.42}, new double[] {0.75, 0.85, 0.80}, 0.18);
        return saturate(c, 0.72);
      }
      case "Bleach Bypass": {
        double[] mono = {y, y, y};
        for (int i = 0; i < 3; i++) c[i] = c[i] * 0.55 + mono[i] * 0.45;
        return curve(c, 0.22, -0.01);
      }
      case "Vintage Fade": {
        c = curve(c, -0.05, 0.03);
        c = lift(c, new double[] {0.07, 0.06, 0.05});
        c = gain(c, new double[] {1.0, 0.97, 0.88});
        c = compress(c, 0.92);
        return saturate(c, 0.78);
      }
      case "Day for Night": {
        double[] d = {c[0] * 0.55, c[1] * 0.62, c[2] * 0.95};
        d = saturate(d, 0.55);
        d = gain(d, new double[] {0.78, 0.85, 1.05});
        return curve(d, 0.10, -0.10);
      }
      case "Noir": {
        double v = luma(new double[] {r * 1.05, g, b * 0.85}); // red-ish filter for punchier skies
        double[] m = {v, v, v};
        return curve(m, 0.30, -0.02);
      }
      case "Desert Heat": {
        c = curve(c, 0.10, 0.03);
        c = curve(c, 0.04, 0.0);
        c = splitTone(c, new double[] {0.45, 0.40, 0.30}, new double[] {1.00, 0.80, 0.45}, 0.20);
        c = gain(c, new double[] {1.05, 0.99, 0.84});
        c = protectSkin(c, r, g, b);
        return saturate(c, 0.92);
      }
      case "Moody Forest": {
        c = curve(c, 0.14, -0.05);
        c = splitTone(c, new double[] {0.10, 0.35, 0.28}, new double[] {0.85, 0.80, 0.62}, 0.16);
        double[] hsv = {c[0], c[1], c[2]};
        hsv[1] = c[1] * 0.96 + c[2] * 0.04; // greens lean teal
        return saturate(hsv, 0.85);
      }
      case "Neon Night": {
        c = curve(c, 0.18, -0.04);
        c = splitTone(c, new double[] {0.30, 0.05, 0.55}, new double[] {0.10, 0.90, 1.00}, 0.20);
        return saturate(c, 1.08);
      }
      case "Soft Pastel": {
        c = curve(c, -0.12, 0.05);
        c = lift(c, new double[] {0.05, 0.05, 0.06});
        c = compress(c, 0.94);
        c = splitTone(c, new double[] {0.55, 0.60, 0.75}, new double[] {1.00, 0.85, 0.85}, 0.10);
        return saturate(c, 0.82);
      }
      case "Code Green": {
        c = curve(c, 0.16, -0.03);
        c = gain(c, new double[] {0.94, 1.02, 0.90});
        c = splitTone(c, new double[] {0.10, 0.35, 0.20}, new double[] {0.70, 0.80, 0.65}, 0.10);
        c = protectSkin(c, r, g, b);
        return saturate(c, 0.82);
      }
      default: return c;
    }
  }

  // ---- building blocks (display-referred, 0..1)

  static double luma(double[] c) { return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2]; }

  static double clamp(double v) { return v < 0 ? 0 : v > 1 ? 1 : v; }

  /** S-curve contrast around mid grey (amount) plus a brightness shift. */
  static double[] curve(double[] c, double amount, double shift) {
    double[] o = new double[3];
    for (int i = 0; i < 3; i++) {
      double x = clamp(c[i]);
      double s = x + amount * (x - 0.5) * (1 - Math.abs(2 * x - 1)) * 2; // strongest in the mids, keeps 0 and 1
      o[i] = clamp(s + shift * (1 - x) * x * 4);
    }
    return o;
  }

  /** Tints shadows toward one colour and highlights toward another (amount 0..1). */
  static double[] splitTone(double[] c, double[] shadow, double[] highlight, double amount) {
    double y = clamp(luma(c));
    double ws = (1 - y) * (1 - y), wh = y * y;
    double[] o = new double[3];
    for (int i = 0; i < 3; i++) {
      double t = (shadow[i] - 0.5) * ws + (highlight[i] - 0.5) * wh;
      o[i] = clamp(c[i] + t * amount);
    }
    return o;
  }

  static double[] lift(double[] c, double[] l) { double[] o = new double[3]; for (int i = 0; i < 3; i++) o[i] = clamp(l[i] + c[i] * (1 - l[i])); return o; }

  static double[] gain(double[] c, double[] g) { double[] o = new double[3]; for (int i = 0; i < 3; i++) o[i] = clamp(c[i] * g[i]); return o; }

  static double[] compress(double[] c, double top) { double[] o = new double[3]; for (int i = 0; i < 3; i++) o[i] = clamp(c[i] * top); return o; }

  /** Soft highlight shoulder. */
  static double[] rolloff(double[] c) {
    double[] o = new double[3];
    for (int i = 0; i < 3; i++) { double x = clamp(c[i]); o[i] = x < 0.75 ? x : 0.75 + (1 - Math.exp(-(x - 0.75) * 4)) * 0.25 / (1 - Math.exp(-1)); }
    for (int i = 0; i < 3; i++) o[i] = clamp(o[i]);
    return o;
  }

  static double[] saturate(double[] c, double s) {
    double y = luma(c);
    double[] o = new double[3];
    for (int i = 0; i < 3; i++) o[i] = clamp(y + (c[i] - y) * s);
    return o;
  }

  /** Keeps skin tones (orange hues of moderate saturation) closer to the original in Teal & Orange. */
  static double[] protectSkin(double[] c, double r, double g, double b) {
    boolean skin = r > g && g > b && (r - b) > 0.06 && (r - b) < 0.45 && r > 0.2;
    if (!skin) return c;
    double[] o = new double[3];
    double[] orig = {r, g, b};
    for (int i = 0; i < 3; i++) o[i] = c[i] * 0.5 + orig[i] * 0.5;
    return o;
  }
}
