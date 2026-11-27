package ug.audiocity.cinema;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/** Host tests for the camera math and file formats (no phone needed). Run with run-tests.sh. */
public class CinemaTests {
  static int passed = 0;

  static void check(boolean ok, String what) {
    if (!ok) throw new AssertionError("FAILED: " + what);
    passed++;
  }

  static void near(double actual, double expected, double tolerance, String what) {
    check(Math.abs(actual - expected) <= tolerance, what + " (expected " + expected + " ± " + tolerance + ", got " + actual + ")");
  }

  public static void main(String[] args) throws IOException {
    frameRates();
    shutter();
    iso();
    whiteBalance();
    looks();
    monitoring();
    timecode();
    ramps();
    bitrate();
    wav();
    dng();
    if (args.length > 0) writeSampleDngs(args[0]);
    System.out.println("CinemaTests: " + passed + " checks passed");
  }

  static void frameRates() {
    check(CinemaMath.frameDurationNs(24) == 41_666_667L, "24 fps frame duration");
    check(CinemaMath.frameDurationNs(23.976) == 41_708_333L, "23.976 fps is exactly 1001/24000 s");
    check(CinemaMath.frameDurationNs(29.97) == 33_366_667L, "29.97 fps is exactly 1001/30000 s");
    near(CinemaMath.exactFps(23.976), 24000.0 / 1001, 1e-6, "exact 23.976");
    check(CinemaMath.nominalFps(59.94) == 60, "nominal 59.94");
    check(CinemaMath.fpsLabel(23.976).equals("23.976") && CinemaMath.fpsLabel(25).equals("25") && CinemaMath.fpsLabel(29.97).equals("29.97"), "fps labels");
    check(CinemaMath.isDropFrameRate(29.97) && !CinemaMath.isDropFrameRate(24) && !CinemaMath.isDropFrameRate(23.976), "drop-frame rates");
  }

  static void shutter() {
    near(CinemaMath.exposureForAngle(180, 24), 1e9 / 48, 1, "180° at 24 fps = 1/48 s");
    near(CinemaMath.exposureForAngle(360, 25), 40_000_000, 1, "360° at 25 fps = 1/25 s");
    near(CinemaMath.angleForExposure(CinemaMath.exposureForAngle(172.8, 24), 24), 172.8, 0.01, "angle round trip");
    check(CinemaMath.flickerFreeAngles(24, 50).contains(172.8), "172.8° is flicker free at 24 fps under 50 Hz light");
    check(CinemaMath.flickerFreeAngles(24, 60).contains(144.0), "144° is flicker free at 24 fps under 60 Hz light");
    check(CinemaMath.flickerFreeAngles(25, 50).contains(180.0), "180° is flicker free at 25 fps under 50 Hz light");
    check(CinemaMath.speedLabel(CinemaMath.NS / 48).equals("1/48"), "speed label");
    check(CinemaMath.angleLabel(172.8).equals("172.8°") && CinemaMath.angleLabel(180).equals("180°"), "angle labels");
  }

  static void iso() {
    List<Integer> stops = CinemaMath.isoStops(55, 7100);
    check(stops.get(0) == 55 && stops.get(stops.size() - 1) == 7100, "ISO stops include the sensor limits");
    for (int i = 1; i < stops.size(); i++) check(stops.get(i) > stops.get(i - 1), "ISO stops ascend");
    check(stops.contains(100) && stops.contains(800) && stops.contains(6400), "third-stop ladder");
    near(CinemaMath.stopsBetween(100, 10_000_000, 200, 10_000_000), 1, 1e-9, "doubling ISO is one stop");
    near(CinemaMath.stopsBetween(400, 20_000_000, 400, 10_000_000), -1, 1e-9, "halving exposure is minus one stop");
  }

  static void whiteBalance() {
    double[] d65 = CinemaMath.kelvinToXy(6504);
    near(d65[0], 0.3127, 0.003, "D65 x"); near(d65[1], 0.3290, 0.003, "D65 y");
    double[] a = CinemaMath.kelvinToXy(2856);
    near(a[0], 0.4476, 0.004, "illuminant A x"); near(a[1], 0.4074, 0.004, "illuminant A y");

    float[] tungsten = CinemaMath.whiteBalanceGains(3200, 0), daylight = CinemaMath.whiteBalanceGains(5600, 0), shade = CinemaMath.whiteBalanceGains(8000, 0);
    check(tungsten[3] > tungsten[0], "tungsten light needs more blue gain than red");
    check(shade[0] > shade[3], "shade needs more red gain than blue");
    check(tungsten[3] / tungsten[0] > daylight[3] / daylight[0], "warmer light, more blue gain");
    for (float g : daylight) check(g >= 0.999f, "gains are normalised to >= 1");
    check(CinemaMath.whiteBalanceGains(5600, 20)[1] < daylight[1], "magenta tint lowers green gain");

    // Calibrated model with sRGB's XYZ→RGB matrix standing in for a sensor: D65 white should be neutral.
    float[] xyzToRgb = {3.2406f, -1.5372f, -0.4986f, -0.9689f, 1.8758f, 0.0415f, 0.0557f, -0.2040f, 1.0570f};
    float[] neutral = CinemaMath.calibratedGains(xyzToRgb, 2856, xyzToRgb, 6504, 6504, 0);
    near(neutral[0], neutral[1], 0.03, "calibrated D65 red ≈ green"); near(neutral[3], neutral[1], 0.03, "calibrated D65 blue ≈ green");
    CinemaMath.WbModel model = (k, t) -> CinemaMath.calibratedGains(xyzToRgb, 2856, xyzToRgb, 6504, k, t);
    for (int k : new int[] {2800, 3200, 4300, 5600, 7500}) {
      float[] g = model.gains(k, 0);
      near(CinemaMath.estimateKelvin(model, g[0], g[3]), k, 100, "calibrated Kelvin round trip " + k);
      float[] h = CinemaMath.whiteBalanceGains(k, 0);
      near(CinemaMath.estimateKelvin(CinemaMath.GENERIC_WB, h[0], h[3]), k, 100, "generic Kelvin round trip " + k);
    }
    check(CinemaMath.illuminantKelvin(21) == 6504 && CinemaMath.illuminantKelvin(17) == 2856, "reference illuminants");
  }

  static void looks() {
    for (String look : CinemaMath.LOOKS) {
      float[] c = CinemaMath.toneCurve(look, 64);
      check(c.length == 128, look + " curve size");
      check(c[0] == 0 && c[126] == 1, look + " curve spans the input range");
      for (int i = 2; i < c.length; i += 2) {
        check(c[i] > c[i - 2], look + " input increases");
        check(c[i + 1] >= c[i - 1] - 1e-6, look + " output is monotonic");
        check(c[i + 1] >= 0 && c[i + 1] <= 1, look + " output in range");
      }
    }
    near(CinemaMath.acLog(0.18), 0.42, 0.03, "AC Log puts 18% grey near 42%");
    near(CinemaMath.acLog(0), 0.0625, 1e-9, "AC Log legal black");
    check(CinemaMath.acLog(0.02) > CinemaMath.rec709(0.02), "log lifts shadows above Rec.709");
    check(CinemaMath.acLog(0.9) < CinemaMath.rec709(0.9), "log holds highlights below Rec.709");
    near(CinemaMath.rec709(1), 1, 1e-3, "Rec.709 white");
  }

  static void monitoring() {
    check(CinemaMath.luma(255, 255, 255) == 255 && CinemaMath.luma(0, 0, 0) == 0, "luma range");
    check(CinemaMath.luma(0, 255, 0) > CinemaMath.luma(255, 0, 0) && CinemaMath.luma(255, 0, 0) > CinemaMath.luma(0, 0, 255), "Rec.709 luma weights");
    check(CinemaMath.falseColor(100) == 0xffe5322d, "clipping is red");
    check(CinemaMath.falseColor(41) == 0xff3ecc5a, "18% grey is green");
    check(CinemaMath.falseColor(55) == 0xffef78b4, "skin is pink");
    check(CinemaMath.falseColor(1) == 0xff7a1fd1, "crushed black is purple");
    check(CinemaMath.falseColor(30) == 0, "mid tones stay grey");
    float[] scope = CinemaMath.guideRect(1920, 1080, 2.39);
    near(scope[2] - scope[0], 1920, 1e-3, "2.39 guide uses full width");
    near((scope[2] - scope[0]) / (scope[3] - scope[1]), 2.39, 1e-3, "2.39 guide ratio");
    float[] vertical = CinemaMath.guideRect(1920, 1080, 9 / 16.0);
    near(vertical[3] - vertical[1], 1080, 1e-3, "9:16 guide uses full height");
    near(vertical[0] + (vertical[2] - vertical[0]) / 2, 960, 1e-3, "guides are centred");
    near(CinemaMath.meterPosition(0), 1, 1e-9, "0 dBFS at the top of the meter");
    near(CinemaMath.dbfs(0.5), -6.02, 0.01, "half scale is -6 dBFS");
  }

  static void timecode() {
    check(CinemaMath.timecode(0, 24).equals("00:00:00:00"), "zero timecode");
    check(CinemaMath.timecode(24 * 3600, 24).equals("01:00:00:00"), "one hour at 24");
    check(CinemaMath.timecode(23, 23.976).equals("00:00:00:23"), "23.976 counts 24 frames per second (non-drop)");
    check(CinemaMath.timecode(25 * 61 + 3, 25).equals("00:01:01:03"), "25 fps timecode");
    // 29.97 drop frame: frames ;00 and ;01 are skipped at each minute except every tenth.
    check(CinemaMath.timecode(1799, 29.97).equals("00:00:59;29"), "DF before the first minute");
    check(CinemaMath.timecode(1800, 29.97).equals("00:01:00;02"), "DF skips ;00 and ;01");
    check(CinemaMath.timecode(17982, 29.97).equals("00:10:00;00"), "DF keeps ;00 on the tenth minute");
    check(CinemaMath.timecode(107892, 29.97).equals("01:00:00;00"), "DF one hour");
    check(CinemaMath.timeOfDay((13L * 3600 + 5 * 60 + 9) * CinemaMath.NS + CinemaMath.NS / 2, 24).equals("13:05:09:12"), "time of day timecode");
    check(CinemaMath.framesFor(CinemaMath.NS, 24) == 24 && CinemaMath.framesFor(CinemaMath.NS * 1001 / 1000, 23.976) == 24, "frames for elapsed time");
    check(CinemaMath.clipName('A', 1, 7, "20261127").equals("A001_C007_20261127"), "clip name");
  }

  static void ramps() {
    near(CinemaMath.ease(0), 0, 1e-12, "ease start"); near(CinemaMath.ease(1), 1, 1e-12, "ease end"); near(CinemaMath.ease(0.5), 0.5, 1e-12, "ease middle");
    check(CinemaMath.ease(0.1) < 0.1 && CinemaMath.ease(0.9) > 0.9, "ease in and out");
    near(CinemaMath.zoomAt(1, 4, 0), 1, 1e-9, "zoom start"); near(CinemaMath.zoomAt(1, 4, 1), 4, 1e-9, "zoom end");
    near(CinemaMath.zoomAt(1, 4, 0.5), 2, 1e-9, "zoom is geometric: halfway from 1x to 4x is 2x");
  }

  static void bitrate() {
    long uhd = CinemaMath.bitrate(3840, 2160, 24, 2, true, false, false), hd = CinemaMath.bitrate(1920, 1080, 24, 2, true, false, false);
    check(uhd > hd * 3, "4K needs about four times the data of 1080p");
    check(CinemaMath.bitrate(3840, 2160, 24, 2, false, false, false) > uhd, "H.264 needs more than HEVC");
    check(CinemaMath.bitrate(3840, 2160, 24, 2, true, true, false) > uhd, "10-bit needs more");
    check(CinemaMath.bitrate(3840, 2160, 24, 2, true, false, true) > uhd * 2, "All-Intra needs much more");
    check(CinemaMath.bitrate(3840, 2160, 24, 3, true, false, false) > uhd, "Extreme beats High");
    near(CinemaMath.minutesRemaining(60L * 1_000_000 / 8 * 60, 1_000_000), 60, 1e-6, "minutes remaining");
    check(CinemaMath.formatBitrate(120_400_000).equals("120 Mb/s"), "bitrate label");
    check(CinemaMath.formatMinutes(75).equals("1h15m") && CinemaMath.formatMinutes(42).equals("42min"), "minutes label");
  }

  static void wav() {
    ByteBuffer h = CinemaRecorder.wavHeader(2, 48_000 * 2 * 4);
    check(h.remaining() == 58, "float WAV header with fact chunk is 58 bytes");
    h.order(ByteOrder.LITTLE_ENDIAN);
    check(h.get(0) == 'R' && h.get(8) == 'W' && h.get(12) == 'f', "RIFF/WAVE/fmt");
    check(h.getInt(4) == 50 + 384_000, "RIFF size");
    check(h.getShort(20) == 3, "IEEE float format");
    check(h.getShort(22) == 2 && h.getInt(24) == 48_000 && h.getShort(34) == 32, "stereo 48 kHz 32-bit");
    check(h.getInt(46) == 48_000, "fact chunk sample frames");
    check(h.get(50) == 'd' && h.getInt(54) == 384_000, "data chunk size");
  }

  // ------------------------------------------------------------------ CinemaDNG

  /** A synthetic Bayer frame: a horizontal ramp with a per-CFA-position offset, so every sample is checkable. */
  static int sample(int x, int y, int white) { return (x * 7 + y * 3 + ((y & 1) * 2 + (x & 1)) * 50) % (white + 1); }

  static CinemaDng.Meta sampleMeta(int bits) {
    CinemaDng.Meta m = new CinemaDng.Meta();
    m.width = 64; m.height = 48; m.bits = bits; m.cfa = CinemaDng.GRBG; m.whiteLevel = 4095;
    m.black = new float[] {256, 256, 256, 256};
    m.colorMatrix1 = new float[] {0.9f, -0.3f, -0.1f, -0.4f, 1.2f, 0.2f, -0.1f, 0.2f, 0.6f};
    m.colorMatrix2 = new float[] {1.2f, -0.5f, -0.1f, -0.3f, 1.1f, 0.2f, 0f, 0.1f, 0.5f};
    m.forward1 = new float[] {0.6f, 0.3f, 0.1f, 0.25f, 0.7f, 0.05f, 0.03f, 0.1f, 0.7f};
    m.forward2 = m.forward1;
    m.neutral = new float[] {0.52f, 1f, 0.61f};
    m.fps = 23.976; m.make = "Test"; m.model = "Phone"; m.uniqueModel = "Test Phone"; m.reel = "A001";
    m.tcHours = 1; m.tcMinutes = 2; m.tcSeconds = 3; m.tcFrames = 4;
    m.shadingRows = 3; m.shadingCols = 4;
    m.shading = new float[4][12];
    for (int c = 0; c < 4; c++) for (int i = 0; i < 12; i++) m.shading[c][i] = 1 + 0.1f * c + 0.02f * i;
    return m;
  }

  static ByteBuffer samplePixels(CinemaDng.Meta m) {
    ByteBuffer px = ByteBuffer.allocate(m.width * m.height * 2).order(ByteOrder.LITTLE_ENDIAN);
    for (int y = 0; y < m.height; y++) for (int x = 0; x < m.width; x++) px.putShort((short) sample(x, y, m.whiteLevel));
    px.flip();
    return px;
  }

  static byte[] sampleFile(int bits) {
    CinemaDng.Meta m = sampleMeta(bits);
    byte[] h = CinemaDng.header(m);
    ByteBuffer px = samplePixels(m);
    byte[] data = new byte[(int) CinemaDng.stripBytes(m)];
    if (bits == 12) CinemaDng.pack12(px, m.width * m.height, data); else px.get(data);
    byte[] file = new byte[h.length + data.length];
    System.arraycopy(h, 0, file, 0, h.length); System.arraycopy(data, 0, file, h.length, data.length);
    return file;
  }

  /** Reads a TIFF IFD entry's first value (SHORT or LONG) from a little-endian DNG. */
  static long tag(ByteBuffer f, int tag) {
    int ifd = f.getInt(4), n = f.getShort(ifd) & 0xffff;
    int last = -1;
    for (int i = 0; i < n; i++) {
      int at = ifd + 2 + i * 12, t = f.getShort(at) & 0xffff, type = f.getShort(at + 2);
      check(t > last, "IFD tags ascend"); last = t;
      if (t == tag) return type == 3 ? f.getShort(at + 8) & 0xffff : f.getInt(at + 8) & 0xffffffffL;
    }
    return -1;
  }

  static void dng() {
    check(CinemaDng.cfaColor(CinemaDng.RGGB, 0, 0) == 0 && CinemaDng.cfaColor(CinemaDng.RGGB, 1, 1) == 2 && CinemaDng.cfaColor(CinemaDng.BGGR, 0, 0) == 2, "CFA colours");
    check(CinemaDng.shadingChannel(CinemaDng.RGGB, 0, 1) == 1 && CinemaDng.shadingChannel(CinemaDng.RGGB, 1, 0) == 2 && CinemaDng.shadingChannel(CinemaDng.GRBG, 1, 0) == 3, "shading channels");
    byte[] t = CinemaDng.smpte(23, 59, 58, 29, true);
    check(t[0] == (byte) 0x69 && t[1] == 0x58 && t[2] == 0x59 && t[3] == 0x23, "SMPTE BCD timecode with drop flag");

    ByteBuffer in = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
    in.putShort((short) 0xABC).putShort((short) 0x123).putShort((short) 4095).putShort((short) 5000); in.flip();
    byte[] packed = new byte[6]; CinemaDng.pack12(in, 4, packed);
    check(packed[0] == (byte) 0xAB && packed[1] == (byte) 0xC1 && packed[2] == 0x23, "12-bit packing is MSB first");
    check(packed[3] == (byte) 0xFF && packed[4] == (byte) 0xFF && packed[5] == (byte) 0xFF, "12-bit packing clips to 4095");

    for (int bits : new int[] {12, 16}) {
      CinemaDng.Meta m = sampleMeta(bits);
      byte[] h = CinemaDng.header(m);
      ByteBuffer f = ByteBuffer.wrap(sampleFile(bits)).order(ByteOrder.LITTLE_ENDIAN);
      check(f.get(0) == 'I' && f.get(1) == 'I' && f.getShort(2) == 42, bits + "-bit TIFF header");
      check(tag(f, 273) == h.length && h.length % 16 == 0, bits + "-bit strip starts right after the header");
      check(tag(f, 279) == CinemaDng.stripBytes(m) && f.capacity() == h.length + CinemaDng.stripBytes(m), bits + "-bit strip size");
      check(tag(f, 256) == 64 && tag(f, 257) == 48 && tag(f, 258) == bits && tag(f, 262) == 32803, bits + "-bit CFA image tags");
      long tc = tag(f, 51043);
      check(tag(f, 50717) == 4095 && tag(f, 51009) > 0 && tc > 0 && f.getInt((int) tc) == 0x01020304 && tag(f, 51081) > 0, bits + "-bit DNG and CinemaDNG tags");
    }
    check(CinemaDng.stripBytes(sampleMeta(12)) * 4 == CinemaDng.stripBytes(sampleMeta(16)) * 3, "12-bit is 25% smaller");
  }

  /** Writes sample DNGs so an external reader (LibRaw) can verify them: see tools/verify_dng.py. */
  static void writeSampleDngs(String dir) throws IOException {
    for (int bits : new int[] {12, 16}) try (FileOutputStream o = new FileOutputStream(dir + "/sample" + bits + ".dng")) { o.write(sampleFile(bits)); }
  }
}
