package ug.audiocity.cinema;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Pure camera math for the Cinema camera. It has no Android dependencies, so it is host-testable:
 * shutter angle / exposure, ISO and shutter stops, Kelvin white balance gains, log and look tone curves,
 * SMPTE timecode, frame guides, false colour bands, focus/zoom ramps and bitrate planning.
 */
final class CinemaMath {
  private CinemaMath() {}

  static final long NS = 1_000_000_000L;

  // ---------------------------------------------------------------- frame rates

  /** Project frame rates offered by the camera. NTSC rates use exact 1000/1001 frame durations. */
  static final double[] FRAME_RATES = {23.976, 24, 25, 29.97, 30, 48, 50, 59.94, 60, 120, 240};

  /** Extra RAW rates for sensors whose 8K / maximum-resolution readout can't reach 24 fps. */
  static final double[] RAW_SLOW_RATES = {5, 6, 8, 10, 12, 15, 20};

  static boolean isDropFrameRate(double fps) { return Math.abs(fps - 29.97) < 0.01 || Math.abs(fps - 59.94) < 0.01; }

  /** Nominal integer rate used for timecode and AE target ranges (23.976 → 24, 29.97 → 30). */
  static int nominalFps(double fps) { return (int) Math.round(fps); }

  /** Exact frame duration in nanoseconds, honouring the 1000/1001 NTSC rates. */
  static long frameDurationNs(double fps) {
    int nominal = nominalFps(fps);
    if (Math.abs(fps - nominal) > 0.001) return Math.round(NS * 1001.0 / (nominal * 1000.0));
    return Math.round((double) NS / nominal);
  }

  static String fpsLabel(double fps) {
    if (Math.abs(fps - Math.rint(fps)) < 0.001) return String.valueOf((int) Math.rint(fps));
    return String.format(Locale.US, "%.3f", fps).replaceAll("0+$", "");
  }

  // ---------------------------------------------------------------- shutter

  /** Exposure time in ns for a shutter angle at a frame rate. 180° at 24 fps = 1/48 s. */
  static long exposureForAngle(double angle, double fps) {
    angle = Math.max(1, Math.min(360, angle));
    return Math.round(frameDurationNs(fps) * angle / 360.0);
  }

  static double angleForExposure(long exposureNs, double fps) {
    return 360.0 * exposureNs / frameDurationNs(fps);
  }

  /** Cine shutter angles, including the flicker-safe 172.8° (50 Hz at 24 fps) and 144° (60 Hz at 24 fps). */
  static final double[] SHUTTER_ANGLES = {11.25, 15, 22.5, 30, 45, 60, 72, 90, 120, 144, 160, 172.8, 180, 216, 270, 300, 330, 345.6, 360};

  /** Shutter speed denominators (1/x s) in roughly 1/3-stop steps, with mains-flicker friendly values. */
  static final int[] SHUTTER_SPEEDS = {24, 25, 30, 40, 48, 50, 60, 80, 96, 100, 120, 125, 160, 200, 240, 250, 320, 400, 500, 640, 800, 1000, 1250, 1600, 2000, 2500, 3200, 4000, 5000, 6400, 8000};

  static String angleLabel(double angle) {
    if (Math.abs(angle - Math.rint(angle)) < 0.01) return (int) Math.rint(angle) + "°";
    return String.format(Locale.US, "%.1f°", angle).replace(".0°", "°");
  }

  static String speedLabel(long exposureNs) {
    if (exposureNs <= 0) return "--";
    double seconds = exposureNs / (double) NS;
    if (seconds >= 0.5) return String.format(Locale.US, "%.1fs", seconds);
    return "1/" + Math.round(1 / seconds);
  }

  /** Flicker-free shutter angles for a mains frequency at the given frame rate (exposure = n half-cycles). */
  static List<Double> flickerFreeAngles(double fps, int mainsHz) {
    List<Double> out = new ArrayList<>();
    double halfCycle = 1.0 / (2.0 * mainsHz);
    double frame = frameDurationNs(fps) / (double) NS;
    for (int n = 1; n * halfCycle <= frame + 1e-9; n++) out.add(Math.round(3600.0 * n * halfCycle / frame) / 10.0);
    return out;
  }

  // ---------------------------------------------------------------- ISO

  /** Third-stop ISO ladder, clipped to the sensor's range (always includes both ends). */
  static List<Integer> isoStops(int min, int max) {
    int[] ladder = {25, 32, 40, 50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000, 1250, 1600, 2000, 2500, 3200, 4000, 5000, 6400, 8000, 10000, 12800, 16000, 20000, 25600, 32000, 40000, 51200};
    List<Integer> out = new ArrayList<>();
    out.add(min);
    for (int iso : ladder) if (iso > min && iso < max) out.add(iso);
    if (max > min) out.add(max);
    return out;
  }

  /** Exposure value difference in stops between two (iso, exposure) settings. */
  static double stopsBetween(int isoA, long expA, int isoB, long expB) {
    return Math.log((double) isoB * expB / ((double) isoA * expA)) / Math.log(2);
  }

  // ---------------------------------------------------------------- white balance

  /** Linear-sRGB white point of a black body / daylight illuminant (normalised so the max channel = 1). */
  static double[] kelvinToRgb(double kelvin) {
    // Tanner Helland's fit of the CIE 1964 black-body locus, good to a few % between 1000 K and 40000 K.
    double t = Math.max(1000, Math.min(40000, kelvin)) / 100.0;
    double r, g, b;
    if (t <= 66) { r = 255; g = 99.4708025861 * Math.log(t) - 161.1195681661; }
    else { r = 329.698727446 * Math.pow(t - 60, -0.1332047592); g = 288.1221695283 * Math.pow(t - 60, -0.0755148492); }
    if (t >= 66) b = 255; else if (t <= 19) b = 0; else b = 138.5177312231 * Math.log(t - 10) - 305.0447927307;
    double[] rgb = {srgbToLinear(clamp01(r / 255)), srgbToLinear(clamp01(g / 255)), srgbToLinear(clamp01(b / 255))};
    double max = Math.max(rgb[0], Math.max(rgb[1], rgb[2]));
    for (int i = 0; i < 3; i++) rgb[i] = Math.max(1e-4, rgb[i] / max);
    return rgb;
  }

  /**
   * RGGB channel gains that neutralise a light source of the given colour temperature. Tint is the usual
   * green/magenta offset: positive = more magenta (less green gain), roughly 1 unit per 1% of green gain.
   * The result is normalised so the green gain is 1 and every gain is >= 1, as Camera2 expects.
   */
  static float[] whiteBalanceGains(double kelvin, double tint) {
    double[] light = kelvinToRgb(kelvin);
    double r = 1 / light[0], g = 1 / light[1], b = 1 / light[2];
    g *= 1 - Math.max(-50, Math.min(50, tint)) / 100.0;
    double min = Math.min(r, Math.min(g, b));
    float gr = (float) (g / min);
    return new float[] {(float) (r / min), gr, gr, (float) (b / min)};
  }

  // ---------------------------------------------------------------- sensor-calibrated white balance

  /** CIE 1931 xy chromaticity of a white at a colour temperature (Planckian below 4000 K, CIE daylight above). */
  static double[] kelvinToXy(double kelvin) {
    double t = Math.max(1667, Math.min(25000, kelvin)), x, y;
    if (t < 4000) {
      x = -0.2661239e9 / (t * t * t) - 0.2343589e6 / (t * t) + 0.8776956e3 / t + 0.179910;
      y = t < 2222 ? -1.1063814 * x * x * x - 1.34811020 * x * x + 2.18555832 * x - 0.20219683
                   : -0.9549476 * x * x * x - 1.37418593 * x * x + 2.09137015 * x - 0.16748867;
    } else {
      x = t <= 7000 ? -4.6070e9 / (t * t * t) + 2.9678e6 / (t * t) + 0.09911e3 / t + 0.244063
                    : -2.0064e9 / (t * t * t) + 1.9018e6 / (t * t) + 0.24748e3 / t + 0.237040;
      y = -3.0 * x * x + 2.870 * x - 0.275;
    }
    return new double[] {x, y};
  }

  /** Colour temperature of a Camera2 / DNG reference illuminant code. */
  static int illuminantKelvin(int code) {
    switch (code) {
      case 2: return 4200; case 3: return 2850; case 4: return 5500; case 9: return 5500; case 10: return 6500;
      case 11: return 7500; case 12: return 6400; case 13: return 5000; case 14: return 4150; case 15: return 3500;
      case 17: return 2856; case 18: return 4874; case 19: return 6774; case 20: return 5503; case 21: return 6504;
      case 22: return 7504; case 23: return 5003; case 24: return 3200; default: return 5500;
    }
  }

  /**
   * White balance gains from the sensor's own calibration (DNG method): interpolate the two XYZ-to-camera
   * matrices by inverse colour temperature, find the camera-native neutral of the requested white, invert.
   * Matrices are row-major 3x3. Returns RGGB gains normalised so the smallest is 1.
   */
  static float[] calibratedGains(float[] cm1, int k1, float[] cm2, int k2, double kelvin, double tint) {
    double w = k1 == k2 ? 1 : (1 / kelvin - 1 / (double) k2) / (1 / (double) k1 - 1 / (double) k2);
    w = clamp01(w);
    double[] xy = kelvinToXy(kelvin);
    double[] xyz = {xy[0] / xy[1], 1, (1 - xy[0] - xy[1]) / xy[1]};
    double[] n = new double[3];
    for (int r = 0; r < 3; r++) for (int c = 0; c < 3; c++) n[r] += (w * cm1[r * 3 + c] + (1 - w) * cm2[r * 3 + c]) * xyz[c];
    double gr = 1 / Math.max(1e-6, n[0]), gg = 1 / Math.max(1e-6, n[1]), gb = 1 / Math.max(1e-6, n[2]);
    gg *= 1 - Math.max(-50, Math.min(50, tint)) / 100.0;
    double min = Math.min(gr, Math.min(gg, gb));
    return new float[] {(float) (gr / min), (float) (gg / min), (float) (gg / min), (float) (gb / min)};
  }

  /**
   * The colour matrix the ISP needs after white balance (white-balanced camera RGB to linear Rec.709), from the
   * sensor's own factory calibration (DNG method): interpolated XYZ-to-camera matrix, inverted, Bradford-adapted
   * from the scene white to D65, rows normalised so white stays white. gains are the RGGB gains in use.
   */
  static double[] calibratedCcm(float[] cm1, int k1, float[] cm2, int k2, double kelvin, float[] gains) {
    double w = k1 == k2 ? 1 : clamp01((1 / kelvin - 1 / (double) k2) / (1 / (double) k1 - 1 / (double) k2));
    double[] cm = new double[9];
    for (int i = 0; i < 9; i++) cm[i] = w * cm1[i] + (1 - w) * cm2[i];
    double gr = gains[0], gg = (gains[1] + gains[2]) / 2, gb = gains[3];
    double[] unWb = {1 / gr, 0, 0, 0, 1 / gg, 0, 0, 0, 1 / gb};
    double[] xy = kelvinToXy(kelvin);
    double[] xyz709 = invert(rgbToXyz(gamutPrimaries("Rec.709")));
    double[] m = mul3(xyz709, mul3(bradford(xy, new double[] {0.3127, 0.3290}), mul3(invert(cm), unWb)));
    for (int r = 0; r < 3; r++) {
      double sum = m[r * 3] + m[r * 3 + 1] + m[r * 3 + 2];
      if (Math.abs(sum) > 1e-6) for (int c = 0; c < 3; c++) m[r * 3 + c] /= sum;
    }
    return m;
  }

  /** Bradford chromatic adaptation (XYZ to XYZ) from one white (xy) to another. */
  static double[] bradford(double[] from, double[] to) {
    double[] b = {0.8951, 0.2664, -0.1614, -0.7502, 1.7135, 0.0367, 0.0389, -0.0685, 1.0296};
    double[] f = mul(b, new double[] {from[0] / from[1], 1, (1 - from[0] - from[1]) / from[1]});
    double[] t = mul(b, new double[] {to[0] / to[1], 1, (1 - to[0] - to[1]) / to[1]});
    double[] d = {t[0] / f[0], 0, 0, 0, t[1] / f[1], 0, 0, 0, t[2] / f[2]};
    return mul3(invert(b), mul3(d, b));
  }

  /** A typical phone sensor colour matrix (rows sum to 1), for cameras that report no calibration at all. */
  static final double[] GENERIC_CCM = {1.70, -0.52, -0.18, -0.26, 1.52, -0.26, 0.02, -0.62, 1.60};

  /**
   * Whether a colour matrix reported by the camera can be used: some phones report none or the identity, and
   * recording with the identity gives the sensor's raw, nearly colourless RGB (grey, washed-out log).
   */
  static boolean usableCcm(double[] m) {
    if (m == null || m.length != 9) return false;
    double off = 0;
    for (int r = 0; r < 3; r++) {
      double sum = 0;
      for (int c = 0; c < 3; c++) { if (!Double.isFinite(m[r * 3 + c])) return false; sum += m[r * 3 + c]; if (r != c) off += Math.abs(m[r * 3 + c]); }
      if (sum < 0.6 || sum > 1.5) return false;
    }
    return off > 0.3; // a real sensor matrix mixes channels strongly
  }

  /** A function from (kelvin, tint) to RGGB gains: either sensor-calibrated or the generic sRGB model. */
  interface WbModel { float[] gains(double kelvin, double tint); }

  static final WbModel GENERIC_WB = CinemaMath::whiteBalanceGains;

  /** Estimates the colour temperature that a model maps to the given red/blue gain ratio. */
  static int estimateKelvin(WbModel model, float r, float b) {
    double target = Math.log(r / Math.max(1e-6, b));
    int best = 5600; double err = Double.MAX_VALUE;
    for (int k = 2000; k <= 12000; k += 50) {
      float[] g = model.gains(k, 0);
      double e = Math.abs(Math.log(g[0] / g[3]) - target);
      if (e < err) { err = e; best = k; }
    }
    return best;
  }

  /** The exact frame rate (23.976 becomes 24000/1001). */
  static double exactFps(double fps) { return NS / (double) frameDurationNs(fps); }

  static final int[] WB_PRESETS = {2700, 3200, 4000, 4500, 5000, 5600, 6500, 7500};

  // ---------------------------------------------------------------- looks / tone curves

  /** Looks. Index 0 is City Log (the log profile); "Flat Log" is the earlier headroom-free flat curve. */
  static final String[] LOOKS = {"City Log", "Film", "Rec.709", "Vivid", "Flat", "Flat Log", "RED Log3G10", "Sony S-Log3", "BMD Film Gen 5"};

  /**
   * Log curves that use exposure-index headroom (the sensor is exposed LOG_HEADROOM stops under the EI and the curve
   * maps the ISP signal back to scene light). RED Log3G10, Sony S-Log3 and Blackmagic Film Generation 5 are the
   * manufacturers' published transfer functions, implemented for interchange: footage grades with the matching
   * input gamma in Resolve, Premiere or Final Cut. Only the transfer curve matches: colours stay in the phone's
   * Rec.709 primaries (set the input colour space / gamut to Rec.709). The names are trademarks of their owners;
   * City Cinema is not affiliated with them.
   */
  static boolean isHeadroomLog(String look) {
    return "City Log".equals(look) || "RED Log3G10".equals(look) || "Sony S-Log3".equals(look) || "BMD Film Gen 5".equals(look);
  }

  // ---------------------------------------------------------------- log gamuts

  /**
   * The wide gamut each log is recorded in (published primaries, x/y for R, G, B and the white point). Real log
   * footage looks flat and low in saturation largely because of these wide gamuts; recording into them also makes
   * grading exact (e.g. Resolve CST input "S-Gamut3.Cine / S-Log3"). City Log and Flat Log use Rec.2020.
   */
  static String gamutName(String look) {
    switch (look) {
      case "Sony S-Log3": return "S-Gamut3.Cine";
      case "RED Log3G10": return "REDWideGamutRGB";
      case "BMD Film Gen 5": return "Blackmagic Wide Gamut Gen 5";
      case "City Log": case "Flat Log": return "Rec.2020";
      default: return null;
    }
  }

  static double[] gamutPrimaries(String gamut) {
    switch (gamut) {
      case "S-Gamut3.Cine": return new double[] {0.766, 0.275, 0.225, 0.800, 0.089, -0.087, 0.3127, 0.3290};
      case "REDWideGamutRGB": return new double[] {0.780308, 0.304253, 0.121595, 1.493994, 0.095612, -0.084589, 0.3127, 0.3290};
      case "Blackmagic Wide Gamut Gen 5": return new double[] {0.7177215, 0.3171181, 0.2280410, 0.8615690, 0.1005841, -0.0820452, 0.3127170, 0.3290312};
      case "Rec.2020": return new double[] {0.708, 0.292, 0.170, 0.797, 0.131, 0.046, 0.3127, 0.3290};
      default: return new double[] {0.640, 0.330, 0.300, 0.600, 0.150, 0.060, 0.3127, 0.3290}; // Rec.709
    }
  }

  /** RGB to XYZ matrix (row-major) for primaries and white point (SMPTE RP 177 method). */
  static double[] rgbToXyz(double[] p) {
    double[] m = new double[9];
    for (int c = 0; c < 3; c++) {
      double x = p[c * 2], y = p[c * 2 + 1];
      m[c] = x / y; m[3 + c] = 1; m[6 + c] = (1 - x - y) / y;
    }
    double wx = p[6], wy = p[7];
    double[] w = {wx / wy, 1, (1 - wx - wy) / wy};
    double[] s = mul(invert(m), w);
    for (int r = 0; r < 3; r++) for (int c = 0; c < 3; c++) m[r * 3 + c] *= s[c];
    return m;
  }

  /** Linear Rec.709 RGB to linear RGB in a log's gamut (identity for looks without one). */
  static double[] rec709ToGamut(String look) {
    String g = gamutName(look);
    if (g == null) return new double[] {1, 0, 0, 0, 1, 0, 0, 0, 1};
    return mul3(invert(rgbToXyz(gamutPrimaries(g))), rgbToXyz(gamutPrimaries("Rec.709")));
  }

  static double[] mul3(double[] a, double[] b) {
    double[] o = new double[9];
    for (int r = 0; r < 3; r++) for (int c = 0; c < 3; c++) for (int k = 0; k < 3; k++) o[r * 3 + c] += a[r * 3 + k] * b[k * 3 + c];
    return o;
  }

  static double[] mul(double[] m, double[] v) {
    return new double[] {m[0] * v[0] + m[1] * v[1] + m[2] * v[2], m[3] * v[0] + m[4] * v[1] + m[5] * v[2], m[6] * v[0] + m[7] * v[1] + m[8] * v[2]};
  }

  static double[] invert(double[] m) {
    double a = m[0], b = m[1], c = m[2], d = m[3], e = m[4], f = m[5], g = m[6], h = m[7], i = m[8];
    double A = e * i - f * h, B = -(d * i - f * g), C = d * h - e * g, det = a * A + b * B + c * C;
    return new double[] {A / det, -(b * i - c * h) / det, (b * f - c * e) / det, B / det, (a * i - c * g) / det, -(a * f - c * d) / det, C / det, -(a * h - b * g) / det, (a * e - b * d) / det};
  }

  /** Any curve that City Cinema encodes in its own gamut (the headroom logs and Flat Log). */
  static boolean isLogLook(String look) { return gamutName(look) != null; }

  /** Code value to scene-linear light for every log look (Flat Log has no headroom). */
  static double decodeLook(String look, double code) {
    if ("Flat Log".equals(look)) {
      double lo = 0, hi = 1;
      for (int k = 0; k < 40; k++) { double mid = (lo + hi) / 2; if (acLog(mid) < code) lo = mid; else hi = mid; }
      return (lo + hi) / 2;
    }
    return logDecode(look, code);
  }

  /** Scene-linear light (18% grey = 0.18) to code value, for the headroom logs. */
  // ---------------------------------------------------------------- log auto exposure

  /** Target centre-weighted geometric mean of scene light for log auto exposure (a touch under 18%: protects highlights). */
  static final double LOG_AE_TARGET = 0.13;

  /**
   * Meters a frame of log-encoded preview pixels (ARGB) by decoding each sample back to scene light (EI units,
   * 18% grey = 0.18) and taking a centre-weighted geometric mean. Doesn't depend on the phone's own metering,
   * which on some phones measures after the log curve and then runs away into deep underexposure.
   */
  static double meterLog(int[] argb, int w, int h, String look) {
    double sum = 0, weight = 0;
    int step = Math.max(1, Math.min(w, h) / 90);
    for (int y = 0; y < h; y += step)
      for (int x = 0; x < w; x += step) {
        int p = argb[y * w + x];
        double code = luma((p >> 16) & 255, (p >> 8) & 255, p & 255) / 255.0;
        double s = Math.max(1e-3, Math.min(16, logDecode(look, code)));
        boolean centre = Math.abs(x - w / 2) < w / 4 && Math.abs(y - h / 2) < h / 4;
        double wt = centre ? 2 : 1;
        sum += wt * Math.log(s); weight += wt;
      }
    return weight == 0 ? LOG_AE_TARGET : Math.exp(sum / weight);
  }

  /** Fraction of a frame's (subsampled) pixels at or above an 8-bit luma: the clipped share in the log modes. */
  static double clippedFraction(int[] argb, int w, int h, int clipLuma) {
    int step = Math.max(1, Math.min(w, h) / 90), n = 0, c = 0;
    for (int y = 0; y < h; y += step)
      for (int x = 0; x < w; x += step) {
        int p = argb[y * w + x];
        if (luma((p >> 16) & 255, (p >> 8) & 255, p & 255) >= clipLuma) c++;
        n++;
      }
    return n == 0 ? 0 : c / (double) n;
  }

  /**
   * Highlight protection for auto exposure: pull down when part of the picture clips, and hold (never brighten)
   * while a little still clips, so protection and the brightness target don't take turns and make exposure hunt.
   */
  static double protectHighlights(double stops, double clipped) {
    if (clipped > 0.08) return Math.min(stops, -0.6);
    if (clipped > 0.02) return Math.min(stops, -0.25);
    if (clipped > 0.005) return Math.min(stops, 0);
    return stops;
  }

  /**
   * Scene light (18% grey = 0.18, in exposure-index units) for each 8-bit code of a look's output, by inverting its
   * tone curve (x * 2^headroom). Rec.709 approximates the phone's own curve. Used to meter any look.
   */
  static double[] decodeTable(String look, int headroom) {
    double[] t = new double[256];
    for (int i = 0; i < 256; i++) {
      double code = i / 255.0, lo = 0, hi = 1;
      if (code <= toneValue(look, 0, headroom)) { t[i] = 0; continue; }
      for (int k = 0; k < 40; k++) { double mid = (lo + hi) / 2; if (toneValue(look, mid, headroom) < code) lo = mid; else hi = mid; }
      t[i] = (lo + hi) / 2 * (1 << headroom);
    }
    return t;
  }

  /** Centre-weighted geometric mean of scene light over a frame, decoded with a decodeTable. */
  static double meterTable(int[] argb, int w, int h, double[] table) {
    double sum = 0, weight = 0;
    int step = Math.max(1, Math.min(w, h) / 90);
    for (int y = 0; y < h; y += step)
      for (int x = 0; x < w; x += step) {
        int p = argb[y * w + x];
        double s = Math.max(1e-3, Math.min(16, table[luma((p >> 16) & 255, (p >> 8) & 255, p & 255)]));
        double wt = Math.abs(x - w / 2) < w / 4 && Math.abs(y - h / 2) < h / 4 ? 2 : 1;
        sum += wt * Math.log(s); weight += wt;
      }
    return weight == 0 ? LOG_AE_TARGET : Math.exp(sum / weight);
  }

  /**
   * Cinema exposure: the shutter stays at the chosen angle and only ISO moves (clamped to the sensor range).
   * Returns {iso, exposure ns, stops still needed beyond the ISO range: negative = too bright (needs ND), positive = too dark}.
   */
  static double[] keepShutter(double isoSeconds, long shutterNs, int isoMin, int isoMax) {
    double needed = isoSeconds / (shutterNs / (double) NS);
    double iso = Math.max(isoMin, Math.min(isoMax, needed));
    return new double[] {Math.round(iso), shutterNs, Math.log(needed / iso) / Math.log(2)};
  }

  /**
   * Cinema auto exposure without an ND filter: keep the chosen shutter angle and move ISO, but when the light is too
   * bright even at the lowest ISO, shorten the shutter rather than overexpose (a blown picture can't be fixed; a
   * crisper motion can). Too dark keeps the shutter (more ISO, then the "add light" hint).
   * Returns {iso, exposure ns, stops beyond the range at the chosen shutter (negative = brighter than ISO min allows)}.
   */
  static double[] cinemaAe(double isoSeconds, long preferredNs, long frameNs, int isoMin, int isoMax, long expMin, long expMax) {
    double[] k = keepShutter(isoSeconds, preferredNs, isoMin, isoMax);
    if (k[2] >= -0.3) return k;
    long ns = Math.max(expMin, Math.min(preferredNs, Math.round(isoSeconds / isoMin * NS)));
    return new double[] {isoMin, ns, k[2]};
  }

  /** Exposure change in stops to bring a metered value to the target: damped, with a small dead band, at most 3 stops. */
  static double logAeCorrection(double metered, double target) {
    double stops = Math.log(target / Math.max(1e-4, metered)) / Math.log(2);
    if (Math.abs(stops) < 0.12) return 0;
    return Math.max(-3, Math.min(3, stops * 0.5));
  }

  /**
   * Splits an exposure (sensor ISO x seconds) into ISO and exposure time: keep the preferred shutter (cinema motion
   * blur) and move ISO; below the lowest ISO shorten the shutter; above isoSoftMax (noise) lengthen the shutter up to
   * the frame duration first, then use more ISO up to the sensor maximum. Returns {iso, exposure ns}.
   */
  static long[] allocateExposure(double isoSeconds, long preferredNs, long frameNs, int isoMin, int isoSoftMax, int isoMax, long expMin, long expMax) {
    long maxTime = Math.min(frameNs, expMax);
    double time = Math.min(preferredNs, maxTime) / (double) NS;
    double iso = isoSeconds / time;
    if (iso < isoMin) { iso = isoMin; time = isoSeconds / isoMin; }
    else if (iso > isoSoftMax) {
      time = Math.min(maxTime / (double) NS, isoSeconds / isoSoftMax);
      iso = isoSeconds / time;
    }
    iso = Math.max(isoMin, Math.min(isoMax, iso));
    long ns = Math.max(expMin, Math.min(maxTime, Math.round(time * NS)));
    return new long[] {Math.round(iso), ns};
  }

  /** The shutter angle closest to the wanted one that is flicker-free under mains lighting (0 Hz = no adjustment). */
  static double flickerSafeAngle(double wanted, double fps, int mainsHz) {
    if (mainsHz <= 0) return wanted;
    List<Double> safe = flickerFreeAngles(fps, mainsHz);
    if (safe.isEmpty()) return wanted;
    double best = safe.get(0);
    for (double a : safe) if (Math.abs(a - wanted) < Math.abs(best - wanted)) best = a;
    return best;
  }

  static double logEncode(String look, double s) {
    switch (look) {
      case "RED Log3G10": return log3g10(s);
      case "Sony S-Log3": return slog3(s);
      case "BMD Film Gen 5": return bmdFilm5(s);
      default: return cityLog(s);
    }
  }

  static double logDecode(String look, double y) {
    switch (look) {
      case "RED Log3G10": return log3g10Inverse(y);
      case "Sony S-Log3": return slog3Inverse(y);
      case "BMD Film Gen 5": return bmdFilm5Inverse(y);
      default: return cityLogInverse(y);
    }
  }

  // RED Log3G10 (RED white paper "REDWideGamutRGB and Log3G10", with the 0.01 offset): 18% grey at 1/3.
  static final double L3G10_A = 0.224282, L3G10_B = 155.975327, L3G10_C = 0.01, L3G10_G = 15.1927;

  static double log3g10(double x) {
    double v = x + L3G10_C;
    return v < 0 ? v * L3G10_G : L3G10_A * Math.log10(v * L3G10_B + 1);
  }

  static double log3g10Inverse(double y) {
    return y < 0 ? y / L3G10_G - L3G10_C : (Math.pow(10, y / L3G10_A) - 1) / L3G10_B - L3G10_C;
  }

  // Sony S-Log3 (Sony "Technical Summary for S-Gamut3.Cine/S-Log3"): 18% grey at 420/1023, black at 95/1023.
  static double slog3(double x) {
    if (x >= 0.01125) return (420.0 + Math.log10((x + 0.01) / (0.18 + 0.01)) * 261.5) / 1023.0;
    return (x * (171.2102946929 - 95.0) / 0.01125 + 95.0) / 1023.0;
  }

  static double slog3Inverse(double y) {
    if (y >= 171.2102946929 / 1023.0) return Math.pow(10, (y * 1023.0 - 420.0) / 261.5) * (0.18 + 0.01) - 0.01;
    return (y * 1023.0 - 95.0) * 0.01125 / (171.2102946929 - 95.0);
  }

  // Blackmagic Film Generation 5 (Blackmagic Generation 5 Color Science): 18% grey at ~0.384.
  static final double BMD_A = 0.08692876065491224, BMD_B = 0.005494072432257808, BMD_C = 0.5300133392291939,
      BMD_D = 8.283605932402494, BMD_E = 0.09246575342465753, BMD_LIN_CUT = 0.005;

  static double bmdFilm5(double x) {
    return x < BMD_LIN_CUT ? BMD_D * x + BMD_E : BMD_A * Math.log(x + BMD_B) + BMD_C;
  }

  static double bmdFilm5Inverse(double y) {
    return y < BMD_D * BMD_LIN_CUT + BMD_E ? (y - BMD_E) / BMD_D : Math.exp((y - BMD_C) / BMD_A) - BMD_B;
  }

  // ---------------------------------------------------------------- City Log

  /*
   * City Log: y = A * log10(s + B) + C on scene-linear light s (18% grey = 0.18), with
   * A = 0.255825, B = 0.01, C = 0.604514. Black (s = 0) sits at 95/1023 and 18% grey at 0.42; each stop above
   * grey takes ~0.077 (~79 ten-bit codes); y reaches 1.0 at s = 35 (7.6 stops over grey).
   * Phones apply the curve to the ISP's linear signal x (1 = clip). City Log gains highlight range the way
   * cinema log modes do: the sensor is exposed LOG_HEADROOM stops under the selected exposure index (EI) and the
   * curve maps x back to scene light s = x * 2^headroom, so the ISP clip point lands at s = 4 (2 stops over
   * "normal" clipping), code ~0.76.
   */
  static final double CITY_LOG_A = 0.255825, CITY_LOG_B = 0.01, CITY_LOG_C = 0.604514;
  static final int LOG_HEADROOM = 2;

  static double cityLog(double sceneLinear) {
    return CITY_LOG_A * Math.log10(Math.max(0, sceneLinear) + CITY_LOG_B) + CITY_LOG_C;
  }

  static double cityLogInverse(double code) {
    return Math.pow(10, (code - CITY_LOG_C) / CITY_LOG_A) - CITY_LOG_B;
  }

  /** Display rendering used by the City Log to Rec.709 LUT: exposure-normalised soft-shoulder tone map, then BT.709. */
  static double cityLogToRec709(double code) { return logToRec709("City Log", code); }

  /** The same display rendering for any of the headroom logs. */
  static double logToRec709(String look, double code) {
    double s = Math.max(0, logDecode(look, code)) * 1.18; // keeps 18% grey close to its Rec.709 level
    final double white = 16;                             // ~6.5 stops over grey reaches display white
    double d = s * (1 + s / (white * white)) / (1 + s);
    return rec709(clamp01(d));
  }

  /** A 3D .cube LUT (33 points) converting City Log to Rec.709, for Resolve, Premiere, Final Cut and monitors. */
  static String cityLogRec709Cube() { return logRec709Cube("City Log"); }

  /** File name of a log's Rec.709 LUT, e.g. "Sony_S-Log3_to_Rec709.cube". */
  static String lutName(String look) { return look.replace(' ', '_').replace('.', '_') + "_to_Rec709.cube"; }

  /** Rendering of one log RGB code triple to Rec.709 display: decode, gamut back to Rec.709, tone map, BT.709. */
  /** Looks the editor and player turn back into normal colour: the logs, and the (non-log) Flat profile. */
  static boolean convertsInPost(String look) { return look != null && (isLogLook(look) || "Flat".equals(look)); }

  /** Undoes the Flat profile's curve (same Rec.709 primaries, so only the tone curve changes). */
  static double flatToRec709(double code) { return clamp01(Math.pow(Math.max(0, (code - 0.06) / 0.88), 1 / 0.82)); }

  static double[] logToRec709(String look, double r, double g, double b) {
    if ("Flat".equals(look)) return new double[] {flatToRec709(r), flatToRec709(g), flatToRec709(b)};
    double[] o = logToRec709Raw(look, r, g, b);
    // Near the sensor's clip point the channels clip unevenly and video compression adds chroma noise (pink/green
    // speckle in blown skies): fade highlights to neutral there, like a cinema camera's highlight handling.
    double t = highlightFade(look, Math.max(r, Math.max(g, b)));
    if (t > 0) { double mx = Math.max(o[0], Math.max(o[1], o[2])); for (int i = 0; i < 3; i++) o[i] += (mx - o[i]) * t; }
    return o;
  }

  /** 0 below the top of a log's range, rising to 1 at the code where the sensor clips. */
  static double highlightFade(String look, double code) {
    double clip = "Flat Log".equals(look) ? acLog(1) : logEncode(look, isHeadroomLog(look) ? 1 << LOG_HEADROOM : 1);
    double x = (code - (clip - 0.09)) / 0.07;
    x = clamp01(x);
    return x * x * (3 - 2 * x);
  }

  private static double[] logToRec709Raw(String look, double r, double g, double b) {
    double[] lin = {Math.max(0, decodeLook(look, r)), Math.max(0, decodeLook(look, g)), Math.max(0, decodeLook(look, b))};
    double[] rgb = mul(invert(rec709ToGamut(look)), lin);
    double[] out = new double[3];
    for (int i = 0; i < 3; i++) {
      double s = Math.max(0, rgb[i]) * 1.18;
      final double white = 16;
      out[i] = rec709(clamp01(s * (1 + s / (white * white)) / (1 + s)));
    }
    return out;
  }

  /** A 3D .cube LUT (33 points) converting a log (in its gamut) to Rec.709 display. */
  static String logRec709Cube(String look) {
    final int n = 33;
    StringBuilder b = new StringBuilder(n * n * n * 24);
    b.append("TITLE \"").append(look).append(" to Rec.709 (City Cinema)\"\n# input: ").append(look).append(" transfer, ").append(gamutName(look)).append(" primaries\n");
    b.append("LUT_3D_SIZE ").append(n).append("\nDOMAIN_MIN 0.0 0.0 0.0\nDOMAIN_MAX 1.0 1.0 1.0\n");
    double d = n - 1;
    for (int bl = 0; bl < n; bl++) for (int g = 0; g < n; g++) for (int r = 0; r < n; r++) {
      double[] o = logToRec709(look, r / d, g / d, bl / d);
      b.append(String.format(Locale.US, "%.6f %.6f %.6f%n", o[0], o[1], o[2]));
    }
    return b.toString();
  }

  /** A 1D .cube LUT converting City Log code values to scene-linear light (18% grey = 0.18). */
  static String cityLogLinearCube() {
    final int n = 4096;
    StringBuilder b = new StringBuilder(n * 30);
    b.append("TITLE \"City Log to scene linear (City Cinema)\"\n# output: scene-linear, 18% grey = 0.18\n");
    b.append("LUT_1D_SIZE ").append(n).append("\nDOMAIN_MIN 0.0 0.0 0.0\nDOMAIN_MAX 1.0 1.0 1.0\n");
    for (int i = 0; i < n; i++) {
      double s = Math.max(0, cityLogInverse(i / (double) (n - 1)));
      b.append(String.format(Locale.US, "%.6f %.6f %.6f%n", s, s, s));
    }
    return b.toString();
  }

  /**
   * Tone curve for a look, as interleaved (in, out) pairs in 0..1 suitable for Camera2's TonemapCurve.
   * Input is the ISP's linear signal (1 = clip). "City Log" is the log profile (see cityLog); "Flat Log" is a
   * gentler flat curve without exposure headroom.
   */
  static float[] toneCurve(String look, int points) { return toneCurve(look, points, LOG_HEADROOM); }

  /** Tone curve with an explicit City Log headroom (stops the sensor is exposed under the exposure index). */
  static float[] toneCurve(String look, int points, int headroom) {
    points = Math.max(2, Math.min(points, 128));
    float[] curve = new float[points * 2];
    for (int i = 0; i < points; i++) {
      // Sample more densely in the shadows, where log curves change fastest.
      double x = Math.pow(i / (double) (points - 1), 2.2);
      double y = toneValue(look, x, headroom);
      curve[i * 2] = (float) x;
      curve[i * 2 + 1] = (float) clamp01(y);
    }
    curve[0] = 0; curve[1] = (float) clamp01(toneValue(look, 0, headroom));
    curve[points * 2 - 2] = 1; curve[points * 2 - 1] = (float) clamp01(toneValue(look, 1, headroom));
    return curve;
  }

  /** Output code value (0..1) for a linear scene value (0..1, 1 = sensor clip). */
  static double toneValue(String look, double linear) { return toneValue(look, linear, LOG_HEADROOM); }

  static double toneValue(String look, double linear, int headroom) {
    double x = clamp01(linear);
    switch (look) {
      case "City Log": case "RED Log3G10": case "Sony S-Log3": case "BMD Film Gen 5":
        return logEncode(look, x * (1 << headroom));
      case "Flat Log": return acLog(x);
      case "Film": return filmic(x);
      case "Vivid": return clamp01(Math.pow(rec709(x), 0.92) * 1.04 - 0.02);
      case "Flat": return 0.06 + 0.88 * Math.pow(rec709(x), 0.82);
      default: return rec709(x);
    }
  }

  /** Flat Log (formerly "AC Log"): no exposure headroom; black at 6.25%, 18% grey at ~42%, clip at 95%. */
  static double acLog(double x) {
    final double a = 8.0, black = 0.0625, top = 0.95;
    return black + (top - black) * Math.log1p(a * x) / Math.log1p(a);
  }

  static double rec709(double x) {
    return x < 0.018 ? 4.5 * x : 1.099 * Math.pow(x, 0.45) - 0.099;
  }

  /** Filmic S-curve on top of a gentle log, with a soft shoulder that rolls off highlights. */
  static double filmic(double x) {
    double l = Math.log1p(40 * x) / Math.log1p(40);
    return clamp01(l * l * (3 - 2 * l) * 0.55 + l * 0.45);
  }

  // ---------------------------------------------------------------- monitoring

  /** Rec.709 luma (0..255) of an 8-bit RGB pixel. */
  static int luma(int r, int g, int b) { return (r * 54 + g * 183 + b * 19) >> 8; }

  /** IRE (0..100+) of an 8-bit legal-range luma code. Full-range input maps 0..255 → 0..100. */
  static double ire(int luma) { return luma * 100.0 / 255.0; }

  /** False colour (ARGB) for an IRE value, or 0 when the value should be shown as greyscale. */
  static int falseColor(double ire) {
    if (ire < 2) return 0xff7a1fd1;      // crushed black: purple
    if (ire < 10) return 0xff1f5fe0;     // deep shadow: blue
    if (ire < 20) return 0xff2aa6c9;     // shadow detail: teal
    if (ire >= 38 && ire <= 44) return 0xff3ecc5a; // 18% grey: green
    if (ire >= 52 && ire <= 58) return 0xffef78b4; // light skin: pink
    if (ire >= 90 && ire < 98) return 0xfff5d33b;  // bright highlights: yellow
    if (ire >= 98) return 0xffe5322d;    // clipping: red
    return 0;
  }

  /** Frame guides: aspect ratio label and value. */
  static final String[] GUIDE_NAMES = {"Off", "2.39:1", "2.00:1", "1.85:1", "16:9", "4:3", "1:1", "9:16", "4:5"};
  static final double[] GUIDE_RATIOS = {0, 2.39, 2.0, 1.85, 16 / 9.0, 4 / 3.0, 1, 9 / 16.0, 4 / 5.0};

  /** Inner rectangle {left, top, right, bottom} of a guide ratio centred inside a frame. */
  static float[] guideRect(float width, float height, double ratio) {
    if (ratio <= 0) return new float[] {0, 0, width, height};
    double frame = width / (double) height;
    if (ratio > frame) { float h = (float) (width / ratio); float t = (height - h) / 2; return new float[] {0, t, width, t + h}; }
    float w = (float) (height * ratio); float l = (width - w) / 2; return new float[] {l, 0, l + w, height};
  }

  static final String[] DESQUEEZE_NAMES = {"1.0x", "1.33x", "1.5x", "1.8x", "2.0x"};
  static final double[] DESQUEEZE = {1, 1.33, 1.5, 1.8, 2.0};

  // ---------------------------------------------------------------- timecode

  /** SMPTE timecode fields {hours, minutes, seconds, frames} for a frame count, with drop-frame counting at 29.97/59.94. */
  static int[] timecodeFields(long frames, double fps) {
    int base = nominalFps(fps);
    if (frames < 0) frames = 0;
    if (isDropFrameRate(fps)) {
      int drop = base == 60 ? 4 : 2;
      long perTenMinutes = base * 600L - drop * 9L, perMinute = base * 60L - drop;
      long tens = frames / perTenMinutes, rest = frames % perTenMinutes;
      frames += drop * 9L * tens + (rest > drop ? drop * ((rest - drop) / perMinute) : 0);
    }
    long totalSeconds = frames / base;
    return new int[] {(int) ((totalSeconds / 3600) % 24), (int) ((totalSeconds / 60) % 60), (int) (totalSeconds % 60), (int) (frames % base)};
  }

  /** SMPTE timecode HH:MM:SS:FF (drop-frame uses ';' before the frames, per convention). */
  static String timecode(long frames, double fps) {
    int[] t = timecodeFields(frames, fps);
    return String.format(Locale.US, "%02d:%02d:%02d%s%02d", t[0], t[1], t[2], isDropFrameRate(fps) ? ";" : ":", t[3]);
  }

  /** Frame count for elapsed nanoseconds at a real (possibly fractional) frame rate. */
  static long framesFor(long elapsedNs, double fps) {
    int nominal = nominalFps(fps);
    long denominator = Math.abs(fps - nominal) > 0.001 ? 1001 : 1000; // exact rational rate: nominal*1000/denominator
    return elapsedNs / 1000 * nominal * 1000 / (denominator * 1_000_000L);
  }

  /** Time-of-day timecode for nanoseconds since local midnight. */
  static String timeOfDay(long nanosSinceMidnight, double fps) {
    long seconds = nanosSinceMidnight / NS;
    long ff = (nanosSinceMidnight % NS) * nominalFps(fps) / NS;
    return String.format(Locale.US, "%02d:%02d:%02d:%02d", (seconds / 3600) % 24, (seconds / 60) % 60, seconds % 60, ff);
  }

  /** Camera-style clip name: A001_C007_20261127 → reel A001, clip 7. */
  static final String[][] LOOK_TAGS = {{"City Log", "CLOG"}, {"Flat Log", "FLOG"}, {"Sony S-Log3", "SLOG3"}, {"RED Log3G10", "RLOG"},
      {"BMD Film Gen 5", "BMDFILM"}, {"Flat", "FLAT"}};

  /**
   * "_SLOG3" etc. for looks that are converted in post, so the file name itself says how to convert it: it survives
   * copies, sharing and reinstalls, when the JSON sidecar can't be found. Empty for normal-colour looks.
   */
  static String lookTag(String look) {
    for (String[] t : LOOK_TAGS) if (t[0].equals(look)) return "_" + t[1];
    return "";
  }

  /** The look named by a file name's tag ("A001_C001_20261127_SLOG3 (2).mp4" is Sony S-Log3), or null. */
  static String lookFromName(String name) {
    if (name == null || name.contains("_Rec709")) return null;
    java.util.regex.Matcher m = java.util.regex.Pattern.compile("_([A-Z0-9]+)(?: \\(\\d+\\))?(?:\\.[A-Za-z0-9]+)?$").matcher(name);
    if (!m.find()) return null;
    for (String[] t : LOOK_TAGS) if (t[1].equals(m.group(1))) return t[0];
    return null;
  }

  static String clipName(char camera, int reel, int clip, String yyyymmdd) {
    return String.format(Locale.US, "%c%03d_C%03d_%s", camera, reel, clip, yyyymmdd);
  }

  // ---------------------------------------------------------------- ramps

  /** Smooth cinematic ease (smootherstep) for focus pulls and zoom moves, 0..1 → 0..1. */
  static double ease(double t) {
    t = clamp01(t);
    return t * t * t * (t * (t * 6 - 15) + 10);
  }

  static double lerp(double a, double b, double t) { return a + (b - a) * t; }

  /** Zoom ramps look natural when interpolated in log space (constant perceived speed). */
  static double zoomAt(double from, double to, double t) {
    return Math.exp(lerp(Math.log(from), Math.log(to), ease(t)));
  }

  // ---------------------------------------------------------------- bitrate & storage

  static final String[] QUALITY = {"Proxy", "Standard", "High", "Extreme"};

  /**
   * Target video bitrate in bits/s, in line with cinema-camera practice: HEVC bits per pixel per frame of 0.06
   * (Proxy), 0.30 (Standard), 0.50 (High: 4K24 ~100 Mb/s) and 0.75 (Extreme: 4K24 ~150 Mb/s); H.264 needs ~1.6x,
   * 10-bit 1.2x, All-Intra 2.5x. Floors per level (8 / 20 / 40 / 60 Mb/s) keep 1080p from being starved: foliage,
   * gravel and water break up badly at low rates. The encoder's maximum caps it (see targetBitrate).
   */
  static long bitrate(int width, int height, double fps, int quality, boolean hevc, boolean tenBit, boolean allIntra) {
    int q = Math.max(0, Math.min(3, quality));
    double[] bpp = {0.06, 0.30, 0.50, 0.75};
    long[] floor = {8_000_000L, 20_000_000L, 40_000_000L, 60_000_000L};
    double bits = width * (double) height * Math.min(fps, 60) * bpp[q];
    if (fps > 60) bits *= 1 + (fps - 60) / 120.0; // high frame rate: more data, but not linearly
    if (!hevc) bits *= 1.6;
    if (tenBit) bits *= 1.2;
    if (allIntra) bits *= 2.5;
    return Math.max(floor[q], Math.round(bits));
  }

  /** Minutes of recording that fit in the free space at a total bitrate (bits/s). */
  static double minutesRemaining(long freeBytes, long bitsPerSecond) {
    if (bitsPerSecond <= 0) return 0;
    return freeBytes * 8.0 / bitsPerSecond / 60.0;
  }

  static String formatMinutes(double minutes) {
    if (minutes >= 600) return String.format(Locale.US, "%.0fh", minutes / 60);
    if (minutes >= 60) return String.format(Locale.US, "%dh%02dm", (int) (minutes / 60), (int) minutes % 60);
    return String.format(Locale.US, "%dmin", (int) minutes);
  }

  static String formatBitrate(long bps) {
    return bps >= 1_000_000 ? (bps + 500_000) / 1_000_000 + " Mb/s" : (bps + 500) / 1000 + " kb/s";
  }

  // ---------------------------------------------------------------- audio

  static double dbfs(double linear) { return linear <= 1e-9 ? -120 : 20 * Math.log10(linear); }

  static double dbToGain(double db) { return Math.pow(10, db / 20.0); }

  /** Meter position 0..1 for -60..0 dBFS. */
  static double meterPosition(double db) { return clamp01((db + 60) / 60.0); }

  // ---------------------------------------------------------------- utils

  static double clamp01(double v) { return v < 0 ? 0 : v > 1 ? 1 : v; }

  static double srgbToLinear(double c) { return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4); }

  /** Index of the value in the list nearest to the target. */
  static int nearest(double[] values, double target) {
    int best = 0;
    for (int i = 1; i < values.length; i++) if (Math.abs(values[i] - target) < Math.abs(values[best] - target)) best = i;
    return best;
  }

  static int nearest(List<Integer> values, double target) {
    int best = 0;
    for (int i = 1; i < values.size(); i++) if (Math.abs(values.get(i) - target) < Math.abs(values.get(best) - target)) best = i;
    return best;
  }
}
