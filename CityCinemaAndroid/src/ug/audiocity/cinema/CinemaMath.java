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
  static final String[] LOOKS = {"City Log", "Film", "Rec.709", "Vivid", "Flat", "Flat Log"};

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
  static double cityLogToRec709(double code) {
    double s = Math.max(0, cityLogInverse(code)) * 1.18; // keeps 18% grey close to its Rec.709 level
    final double white = 16;                             // ~6.5 stops over grey reaches display white
    double d = s * (1 + s / (white * white)) / (1 + s);
    return rec709(clamp01(d));
  }

  /** A 3D .cube LUT (33 points) converting City Log to Rec.709, for Resolve, Premiere, Final Cut and monitors. */
  static String cityLogRec709Cube() {
    final int n = 33;
    StringBuilder b = new StringBuilder(n * n * n * 24);
    b.append("TITLE \"City Log to Rec.709 (City Cinema)\"\n# City Log: y = 0.255825*log10(s + 0.01) + 0.604514\n");
    b.append("LUT_3D_SIZE ").append(n).append("\nDOMAIN_MIN 0.0 0.0 0.0\nDOMAIN_MAX 1.0 1.0 1.0\n");
    double[] v = new double[n];
    for (int i = 0; i < n; i++) v[i] = cityLogToRec709(i / (double) (n - 1));
    for (int bl = 0; bl < n; bl++) for (int g = 0; g < n; g++) for (int r = 0; r < n; r++)
      b.append(String.format(Locale.US, "%.6f %.6f %.6f%n", v[r], v[g], v[bl]));
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
      case "City Log": return cityLog(x * (1 << headroom));
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
   * Target video bitrate in bits/s. Bits per pixel per frame at each quality level, scaled for codec
   * efficiency (HEVC needs ~60% of H.264), 10-bit and intra-only GOP.
   */
  static long bitrate(int width, int height, double fps, int quality, boolean hevc, boolean tenBit, boolean allIntra) {
    double[] bpp = {0.05, 0.12, 0.22, 0.40};
    double bits = width * (double) height * Math.min(fps, 60) * bpp[Math.max(0, Math.min(3, quality))];
    if (fps > 60) bits *= 1 + (fps - 60) / 120.0; // high frame rate: more data, but not linearly
    if (hevc) bits *= 0.6;
    if (tenBit) bits *= 1.25;
    if (allIntra) bits *= 3;
    return Math.max(2_000_000L, Math.round(bits));
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
