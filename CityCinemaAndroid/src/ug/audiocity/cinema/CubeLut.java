package ug.audiocity.cinema;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.Locale;

/**
 * A .cube LUT (Adobe/Resolve format), 3D or 1D, parsed into a 3D table with red varying fastest (pure Java,
 * host-testable). 1D LUTs are expanded into an equivalent 33^3 3D table so the GPU path is the same.
 */
final class CubeLut {
  String title = "";
  int size;              // points per axis of the 3D table
  float[] data;          // size^3 * 3, red fastest, then green, then blue
  float[] domainMin = {0, 0, 0}, domainMax = {1, 1, 1};

  static final int MAX_3D = 65, MAX_1D = 65536;

  static CubeLut parse(Reader in) throws IOException {
    BufferedReader r = new BufferedReader(in);
    CubeLut lut = new CubeLut();
    int size3 = 0, size1 = 0, n = 0;
    float[] values = null;
    String line;
    int lineNo = 0;
    while ((line = r.readLine()) != null) {
      lineNo++;
      line = line.trim();
      if (line.isEmpty() || line.startsWith("#")) continue;
      String upper = line.toUpperCase(Locale.US);
      if (upper.startsWith("TITLE")) { lut.title = line.substring(5).trim().replace("\"", ""); continue; }
      if (upper.startsWith("LUT_3D_SIZE")) { size3 = Integer.parseInt(line.substring(11).trim()); if (size3 < 2 || size3 > MAX_3D) throw new IOException("3D LUT size " + size3 + " not supported (2.." + MAX_3D + ")"); values = new float[size3 * size3 * size3 * 3]; continue; }
      if (upper.startsWith("LUT_1D_SIZE")) { size1 = Integer.parseInt(line.substring(11).trim()); if (size1 < 2 || size1 > MAX_1D) throw new IOException("1D LUT size " + size1 + " not supported"); values = new float[size1 * 3]; continue; }
      if (upper.startsWith("DOMAIN_MIN")) { lut.domainMin = floats(line.substring(10), lineNo); continue; }
      if (upper.startsWith("DOMAIN_MAX")) { lut.domainMax = floats(line.substring(10), lineNo); continue; }
      if (upper.startsWith("LUT_1D_INPUT_RANGE") || upper.startsWith("LUT_3D_INPUT_RANGE")) {
        float[] range = floats(line.substring(18), lineNo, 2);
        lut.domainMin = new float[] {range[0], range[0], range[0]}; lut.domainMax = new float[] {range[1], range[1], range[1]};
        continue;
      }
      if (Character.isLetter(line.charAt(0))) continue; // other keywords are optional and ignored
      if (values == null) throw new IOException("line " + lineNo + ": data before LUT_3D_SIZE or LUT_1D_SIZE");
      float[] rgb = floats(line, lineNo);
      if (n * 3 + 3 > values.length) throw new IOException("more entries than the size says");
      values[n * 3] = rgb[0]; values[n * 3 + 1] = rgb[1]; values[n * 3 + 2] = rgb[2];
      n++;
    }
    if (values == null) throw new IOException("not a .cube LUT (no LUT_3D_SIZE or LUT_1D_SIZE)");
    if (n * 3 != values.length) throw new IOException("expected " + values.length / 3 + " entries, found " + n);
    if (size3 > 0) { lut.size = size3; lut.data = values; }
    else lut.expand1d(values, size1);
    return lut;
  }

  private static float[] floats(String s, int lineNo) throws IOException { return floats(s, lineNo, 3); }

  private static float[] floats(String s, int lineNo, int count) throws IOException {
    String[] parts = s.trim().split("\\s+");
    if (parts.length < count) throw new IOException("line " + lineNo + ": expected " + count + " numbers");
    float[] f = new float[count];
    try { for (int i = 0; i < count; i++) f[i] = Float.parseFloat(parts[i]); }
    catch (NumberFormatException e) { throw new IOException("line " + lineNo + ": not a number"); }
    return f;
  }

  /** A 1D LUT applies a curve per channel: build the equivalent 33^3 3D table. */
  private void expand1d(float[] curve, int n) {
    size = 33;
    data = new float[33 * 33 * 33 * 3];
    float[][] ch = new float[3][33];
    for (int c = 0; c < 3; c++)
      for (int i = 0; i < 33; i++) {
        double pos = i / 32.0 * (n - 1);
        int i0 = (int) Math.floor(pos), i1 = Math.min(n - 1, i0 + 1);
        double f = pos - i0;
        ch[c][i] = (float) (curve[i0 * 3 + c] * (1 - f) + curve[i1 * 3 + c] * f);
      }
    int k = 0;
    for (int b = 0; b < 33; b++) for (int g = 0; g < 33; g++) for (int r = 0; r < 33; r++) { data[k++] = ch[0][r]; data[k++] = ch[1][g]; data[k++] = ch[2][b]; }
  }

  /** Applies the LUT to one RGB value (trilinear), as the GPU does. Used by tests and thumbnails. */
  float[] apply(float r, float g, float b) {
    float[] in = {r, g, b}, out = new float[3];
    double[] p = new double[3];
    int[] i0 = new int[3], i1 = new int[3];
    for (int c = 0; c < 3; c++) {
      double t = (in[c] - domainMin[c]) / Math.max(1e-9, domainMax[c] - domainMin[c]);
      t = Math.max(0, Math.min(1, t)) * (size - 1);
      i0[c] = (int) Math.floor(t); i1[c] = Math.min(size - 1, i0[c] + 1); p[c] = t - i0[c];
    }
    for (int c = 0; c < 3; c++) {
      double v = 0;
      for (int dz = 0; dz < 2; dz++) for (int dy = 0; dy < 2; dy++) for (int dx = 0; dx < 2; dx++) {
        int x = dx == 0 ? i0[0] : i1[0], y = dy == 0 ? i0[1] : i1[1], z = dz == 0 ? i0[2] : i1[2];
        double w = (dx == 0 ? 1 - p[0] : p[0]) * (dy == 0 ? 1 - p[1] : p[1]) * (dz == 0 ? 1 - p[2] : p[2]);
        v += w * data[((z * size + y) * size + x) * 3 + c];
      }
      out[c] = (float) v;
    }
    return out;
  }

  /** IEEE half-float bits for a float (for the GPU's RGB16F LUT texture). */
  static short toHalf(float f) {
    int bits = Float.floatToIntBits(f);
    int sign = (bits >>> 16) & 0x8000, exp = ((bits >>> 23) & 0xff) - 127 + 15, mant = bits & 0x7fffff;
    if (exp <= 0) {
      if (exp < -10) return (short) sign;
      mant = (mant | 0x800000) >> (1 - exp);
      return (short) (sign | ((mant + 0x1000) >> 13));
    }
    if (exp >= 31) return (short) (sign | 0x7c00);
    int h = sign | (exp << 10) | ((mant + 0x1000) >> 13);
    return (short) h;
  }

  static float fromHalf(short h) {
    int s = (h >> 15) & 1, e = (h >> 10) & 0x1f, m = h & 0x3ff;
    float v = e == 0 ? m / 1024f * (float) Math.pow(2, -14) : e == 31 ? Float.POSITIVE_INFINITY : (1 + m / 1024f) * (float) Math.pow(2, e - 15);
    return s == 1 ? -v : v;
  }
}
