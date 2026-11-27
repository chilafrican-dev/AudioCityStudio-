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
    audioAlign();
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
    near(CinemaMath.acLog(0.18), 0.42, 0.03, "Flat Log puts 18% grey near 42%");
    near(CinemaMath.acLog(0), 0.0625, 1e-9, "Flat Log legal black");
    cityLog();
    cameraLogs();
    logAutoExposure();
    gamuts();
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
    check(uhd > hd * 2, "4K needs much more data than 1080p");
    near(uhd / 1e6, 99.5, 1, "4K24 HEVC High is ~100 Mb/s");
    near(CinemaMath.bitrate(3840, 2160, 24, 3, true, false, false) / 1e6, 149, 1, "4K24 HEVC Extreme is ~150 Mb/s");
    check(hd >= 40_000_000L, "1080p High never drops below 40 Mb/s");
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
    try (FileOutputStream o = new FileOutputStream(dir + "/CityLog_to_Rec709.cube")) { o.write(CinemaMath.cityLogRec709Cube().getBytes("US-ASCII")); }
    for (String look : new String[] {"Flat Log", "RED Log3G10", "Sony S-Log3", "BMD Film Gen 5"})
      try (FileOutputStream o = new FileOutputStream(dir + "/" + CinemaMath.lutName(look))) { o.write(CinemaMath.logRec709Cube(look).getBytes("US-ASCII")); }
  }

  /** Audio that starts before frame 0 is trimmed; a gap is filled with silence, so sound stays in sync. */
  static void audioAlign() throws IOException {
    java.io.File f = java.io.File.createTempFile("align", ".mov");
    try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "rw")) {
      MovWriter.Config c = new MovWriter.Config(); c.width = 64; c.height = 32; c.audioChannels = 2;
      MovWriter w = new MovWriter(raf.getChannel(), c);
      MovWriter.AudioAligner a = new MovWriter.AudioAligner(2, 48_000);
      a.add(new float[960 * 2], 960, 990_000_000L, w);           // before video starts: held back
      check(w.audioFramesWritten() == 0, "audio waits for the first video frame");
      a.videoStarted(1_000_000_000L, w);                          // frame 0 at t = 1 s: first 10 ms of that chunk trimmed
      check(w.audioFramesWritten() == 480, "audio before frame 0 is trimmed");
      a.add(new float[960 * 2], 960, 1_030_000_000L, w);          // 20 ms gap after the first chunk
      check(w.audioFramesWritten() == 480 + 960 + 960, "a gap is filled with silence");
      a.add(new float[960 * 2], 960, 1_030_000_000L, w);          // a repeated chunk adds nothing
      check(w.audioFramesWritten() == 2400, "overlapping audio is not written twice");
      MovWriter.AudioAligner skewed = new MovWriter.AudioAligner(2, 48_000);
      skewed.videoStarted(1_000_000_000L, w);
      long before = w.audioFramesWritten();
      skewed.add(new float[960 * 2], 960, 1_000_000_000L + 900 * CinemaMath.NS, w); // audio clock 15 minutes off
      check(w.audioFramesWritten() - before == 960, "audio on a different clock is re-aligned, not padded with 15 minutes of silence");
      w.writeVideo(ByteBuffer.wrap(new byte[100]), 3);
      check(w.videoFrames() == 3, "one frame can fill several slots");
      w.finish();
    } finally { f.delete(); }
  }

  static void cityLog() {
    near(CinemaMath.cityLog(0), 95 / 1023.0, 1e-4, "City Log black at 95/1023");
    near(CinemaMath.cityLog(0.18), 0.42, 1e-4, "City Log 18% grey at 0.42");
    double stop = CinemaMath.cityLog(0.36) - CinemaMath.cityLog(0.18);
    near(stop * 1023, 75, 6, "City Log: ~79 ten-bit codes per stop around grey");
    near(CinemaMath.cityLog(35), 1.0, 0.01, "City Log reaches 1.0 about 7.6 stops over grey");
    for (double s : new double[] {0, 0.01, 0.18, 1, 4, 20}) near(CinemaMath.cityLogInverse(CinemaMath.cityLog(s)), s, 1e-9, "City Log inverse " + s);
    // With 2 stops of headroom, ISP grey (0.045 linear, 2 stops under) lands on the City Log grey code ...
    near(CinemaMath.toneValue("City Log", 0.18 / 4, 2), 0.42, 1e-4, "EI headroom: underexposed grey maps to 0.42");
    // ... and the ISP clip point holds 2 stops more highlight than normal.
    near(CinemaMath.toneValue("City Log", 1, 2), CinemaMath.cityLog(4), 1e-9, "ISP clip maps to scene 4.0");
    check(CinemaMath.toneValue("City Log", 1, 2) < 0.8, "City Log leaves codes above the clip point");
    // LUTs
    near(CinemaMath.cityLogToRec709(0.42), CinemaMath.rec709(0.18), 0.03, "LUT keeps 18% grey near Rec.709 grey");
    near(CinemaMath.cityLogToRec709(95 / 1023.0), 0, 0.005, "LUT maps log black to black");
    double prev = -1;
    for (int i = 0; i <= 100; i++) { double v = CinemaMath.cityLogToRec709(i / 100.0); check(v >= prev - 1e-12 && v <= 1, "LUT monotonic, in range"); prev = v; }
    String cube = CinemaMath.cityLogRec709Cube();
    check(cube.contains("LUT_3D_SIZE 33") && cube.split("\n").length == 33 * 33 * 33 + 5, "3D cube header and size");
    String[] lines = cube.split("\n");
    String[] e = lines[5 + 20].split(" "); // entry r = 20, g = 0, b = 0
    check(Double.parseDouble(e[0]) > 0.3 && e[1].equals(e[2]) && Double.parseDouble(e[1]) == 0, "cube: red varies fastest");
    String lin = CinemaMath.cityLogLinearCube();
    check(lin.contains("LUT_1D_SIZE 4096") && lin.split("\n").length == 4096 + 5, "1D cube header and size");
  }

  /** RED Log3G10, Sony S-Log3, Blackmagic Film Gen 5 against reference values from colour-science 0.4.7. */
  static void cameraLogs() {
    double[] x = {0, 0.005, 0.01125, 0.18, 1, 4, 16};
    double[][] ref = {
      {0.092864, 0.125974, 0.167361, 0.410557, 0.596027, 0.749099, 0.902790},   // S-Log3
      {0.091551, 0.117456, 0.142403, 0.333333, 0.493449, 0.627293, 0.762025},   // Log3G10
      {0.092466, 0.133884, 0.174500, 0.383562, 0.530490, 0.650642, 0.771061}};  // BMD Film Gen 5
    String[] looks = {"Sony S-Log3", "RED Log3G10", "BMD Film Gen 5"};
    for (int l = 0; l < 3; l++) {
      String look = looks[l];
      check(CinemaMath.isHeadroomLog(look), look + " uses EI headroom");
      for (int i = 0; i < x.length; i++) {
        near(CinemaMath.logEncode(look, x[i]), ref[l][i], 2e-6, look + " at " + x[i]);
        near(CinemaMath.logDecode(look, CinemaMath.logEncode(look, x[i])), x[i], 1e-9, look + " inverse at " + x[i]);
      }
      double prev = -1;
      for (int i = 0; i <= 1000; i++) { double v = CinemaMath.logEncode(look, i / 1000.0 * 8); check(v > prev, look + " increases"); prev = v; }
      near(CinemaMath.toneValue(look, 0.18 / 4, 2), CinemaMath.logEncode(look, 0.18), 1e-12, look + " EI headroom keeps grey on its code");
      near(CinemaMath.logToRec709(look, CinemaMath.logEncode(look, 0.18)), CinemaMath.rec709(0.18), 0.03, look + " LUT keeps grey");
      check(CinemaMath.logRec709Cube(look).contains("TITLE \"" + look + " to Rec.709"), look + " cube title");
    }
    check(CinemaMath.lutName("Sony S-Log3").equals("Sony_S-Log3_to_Rec709.cube") && CinemaMath.lutName("RED Log3G10").equals("RED_Log3G10_to_Rec709.cube"), "LUT names");
    check(!CinemaMath.isHeadroomLog("Flat Log") && !CinemaMath.isHeadroomLog("Rec.709"), "Flat Log has no EI headroom");
  }

  static void logAutoExposure() {
    for (String look : new String[] {"Sony S-Log3", "City Log", "RED Log3G10"}) {
      for (double scene : new double[] {0.02, 0.13, 0.6}) {
        int code = (int) Math.round(CinemaMath.logEncode(look, scene) * 255);
        int[] px = new int[64 * 36];
        java.util.Arrays.fill(px, 0xff000000 | code << 16 | code << 8 | code);
        double m = CinemaMath.meterLog(px, 64, 36, look);
        near(Math.log(m / scene) / Math.log(2), 0, 0.15, look + " meter reads the scene back within 0.15 stop at " + scene);
      }
    }
    // the runaway seen on a Galaxy S20 Ultra: a dark frame must push exposure up, not down
    check(CinemaMath.logAeCorrection(0.01, CinemaMath.LOG_AE_TARGET) > 1, "dark frame raises exposure");
    check(CinemaMath.logAeCorrection(1.0, CinemaMath.LOG_AE_TARGET) < -1, "bright frame lowers exposure");
    check(CinemaMath.logAeCorrection(0.135, CinemaMath.LOG_AE_TARGET) == 0, "dead band near the target");
    long frame = CinemaMath.frameDurationNs(24), half = CinemaMath.exposureForAngle(180, 24);
    long[] a = CinemaMath.allocateExposure(400 * half / 1e9, half, frame, 50, 3200, 6400, 100_000, 100_000_000);
    check(a[0] == 400 && a[1] == half, "normal light: keep 180 degrees, ISO 400");
    a = CinemaMath.allocateExposure(50 * half / 1e9 / 8, half, frame, 50, 3200, 6400, 100_000, 100_000_000);
    check(a[0] == 50 && Math.abs(a[1] - half / 8) < 1000, "bright: lowest ISO, shorter shutter");
    a = CinemaMath.allocateExposure(3200 * half / 1e9 * 2, half, frame, 50, 3200, 6400, 100_000, 100_000_000);
    check(a[0] == 3200 && Math.abs(a[1] - frame) < 1000, "dark: 360 degrees before more ISO");
    a = CinemaMath.allocateExposure(6400 * frame / 1e9 * 4, half, frame, 50, 3200, 6400, 100_000, 100_000_000);
    check(a[0] == 6400 && a[1] == frame, "very dark: clamps at the sensor limits");
    near(CinemaMath.flickerSafeAngle(180, 24, 50), 172.8, 0.01, "50 Hz at 24 fps: 172.8 degrees");
    int[] px = new int[100 * 50];
    for (int i = 0; i < px.length; i++) { int v = i % 100 < 10 ? 200 : 90; px[i] = 0xff000000 | v << 16 | v << 8 | v; }
    near(CinemaMath.clippedFraction(px, 100, 50, 191), 0.1, 0.02, "clipped share of a frame");
    check(CinemaMath.protectHighlights(1.0, 0.1) <= -0.6 && CinemaMath.protectHighlights(0, 0.03) == -0.25 && CinemaMath.protectHighlights(0.5, 0.0) == 0.5, "highlight protection");
    near(CinemaMath.toneValue("Sony S-Log3", 1, 2) * 255, 191, 1, "S-Log3 with 2 stops of headroom clips at 8-bit code ~191");
    check(CinemaMath.protectHighlights(0.8, 0.01) == 0, "a little clipping holds exposure (no hunting)");
    for (String look : new String[] {"Rec.709", "Film", "Sony S-Log3"}) {
      int hr = CinemaMath.isHeadroomLog(look) ? 2 : 0;
      double[] t = CinemaMath.decodeTable(look, hr);
      int grey = (int) Math.round(CinemaMath.toneValue(look, 0.18 / (1 << hr), hr) * 255);
      near(Math.log(t[grey] / 0.18) / Math.log(2), 0, 0.12, look + " decode table finds 18% grey");
      for (int i = 1; i < 256; i++) check(t[i] >= t[i - 1], look + " decode table is monotonic");
    }
    double[] k = CinemaMath.keepShutter(50 * 0.02 / 4, 20_000_000L, 50, 6400);
    check(k[0] == 50 && k[1] == 20_000_000L && Math.abs(k[2] + 2) < 1e-9, "too bright: shutter kept, 2 stops of ND needed");
    k = CinemaMath.keepShutter(800 * 0.02, 20_000_000L, 50, 6400);
    check(k[0] == 800 && k[2] == 0, "normal: ISO 800 at the same shutter");
    near(CinemaMath.flickerSafeAngle(180, 25, 50), 180, 0.01, "50 Hz at 25 fps: 180 degrees");
    near(CinemaMath.flickerSafeAngle(180, 24, 60), 144, 0.01, "60 Hz at 24 fps: 144 degrees (180 would flicker)");
  }

  static void gamuts() {
    double[] m = CinemaMath.rgbToXyz(CinemaMath.gamutPrimaries("Rec.709"));
    near(m[0], 0.4124, 2e-4, "Rec.709 to XYZ"); near(m[4], 0.7152, 2e-4, "Rec.709 to XYZ Y green"); near(m[8], 0.9505, 2e-4, "Rec.709 to XYZ Z blue");
    double[] to2020 = CinemaMath.rec709ToGamut("City Log");
    near(to2020[0], 0.6274, 3e-4, "709 to 2020 [0]"); near(to2020[1], 0.3293, 3e-4, "709 to 2020 [1]"); near(to2020[4], 0.9195, 3e-4, "709 to 2020 [4]");
    // reference matrices from colour-science 0.4.7 (matrix_RGB_to_RGB, no chromatic adaptation)
    String[] refLooks = {"Sony S-Log3", "RED Log3G10", "BMD Film Gen 5"};
    double[][] refs = {{0.6457, 0.2591, 0.0952, 0.0875, 0.7597, 0.1528, 0.0370, 0.1293, 0.8338},
        {0.5419, 0.3601, 0.0979, 0.0770, 0.7680, 0.1550, 0.0589, 0.2735, 0.6676},
        {0.6549, 0.2687, 0.0764, 0.0489, 0.7920, 0.1591, 0.0356, 0.1624, 0.8022}};
    for (int l = 0; l < 3; l++) { double[] t = CinemaMath.rec709ToGamut(refLooks[l]); for (int i = 0; i < 9; i++) near(t[i], refs[l][i], 1e-4, refLooks[l] + " gamut matrix [" + i + "]"); }
    for (String look : new String[] {"Sony S-Log3", "RED Log3G10", "BMD Film Gen 5", "City Log", "Flat Log"}) {
      double[] t = CinemaMath.rec709ToGamut(look);
      for (int r = 0; r < 3; r++) near(t[r * 3] + t[r * 3 + 1] + t[r * 3 + 2], 1, 2e-3, look + ": white stays white (row " + r + ")");
      double[] red = CinemaMath.mul(t, new double[] {1, 0, 0});
      check(red[1] > 0.04 && red[2] >= -1e-3, look + ": a Rec.709 red is less saturated in the wide gamut");
      // LUT round trip: grey and a saturated Rec.709 colour come back after log + gamut encoding
      double hr = CinemaMath.isHeadroomLog(look) ? 1 : 0;
      for (double[] c : new double[][] {{0.18, 0.18, 0.18}, {0.30, 0.05, 0.02}}) {
        double[] w = CinemaMath.mul(t, c);
        double[] out = CinemaMath.logToRec709(look, enc(look, w[0]), enc(look, w[1]), enc(look, w[2]));
        double[] ref = CinemaMath.logToRec709(look, enc(look, c[0]), enc(look, c[0]), enc(look, c[0]));
        if (c[1] == 0.18) for (int i = 0; i < 3; i++) near(out[i], ref[0], 0.01, look + " grey stays neutral through the LUT");
        else check(out[0] > out[1] * 1.8 && out[0] > out[2] * 1.8, look + " a red stays red through the LUT");
      }
    }
    check(CinemaMath.logRec709Cube("Sony S-Log3").contains("S-Gamut3.Cine primaries"), "cube names the gamut");
  }

  static double enc(String look, double s) { return "Flat Log".equals(look) ? CinemaMath.acLog(s) : CinemaMath.logEncode(look, s); }
}
