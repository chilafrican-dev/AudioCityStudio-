package ug.audiocity.cinema;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * CinemaDNG frame writer (pure Java, host-testable). Produces one uncompressed Bayer DNG per frame, little-endian,
 * single strip, 12-bit packed or 16-bit, with the sensor's colour matrices, black/white levels, as-shot neutral,
 * a lens-shading GainMap (OpcodeList2) and the CinemaDNG TimeCodes / FrameRate / ReelName tags, so NLEs such as
 * DaVinci Resolve open a folder of frames as one clip.
 */
final class CinemaDng {
  private CinemaDng() {}

  /** CFA arrangements, as Camera2's SENSOR_INFO_COLOR_FILTER_ARRANGEMENT. */
  static final int RGGB = 0, GRBG = 1, GBRG = 2, BGGR = 3;

  /** Everything that goes into a frame's header. Arrays may be null when the camera doesn't report them. */
  static final class Meta {
    int width, height, bits = 16, cfa = RGGB, whiteLevel = 1023, orientation = 1, iso = 100;
    /** Black level per 2x2 CFA position, row-major. */
    float[] black = {64, 64, 64, 64};
    /** Row-major 3x3 matrices: ColorMatrix (XYZ→camera), CameraCalibration, ForwardMatrix (camera→XYZ D50). */
    float[] colorMatrix1, colorMatrix2, calibration1, calibration2, forward1, forward2;
    int illuminant1 = 21, illuminant2 = 17;
    /** As-shot white balance as camera-neutral RGB (green = 1). */
    float[] neutral = {0.5f, 1, 0.5f};
    long exposureNs = 20_833_333L;
    double fps = 24;
    String make = "", model = "", uniqueModel = "City Cinema", software = "City Cinema", dateTime = "", reel = "";
    /** SMPTE timecode of this frame. */
    int tcHours, tcMinutes, tcSeconds, tcFrames;
    boolean dropFrame;
    /** Lens shading gains [channel R, Geven, Godd, B][row * cols + col], or null. */
    float[][] shading;
    int shadingRows, shadingCols;
    /** Where the shading map lies relative to this (possibly cropped) image: origin and extent as fractions of the
     * image size. A map covering a full sensor of which the image is a centred crop has origin < 0, extent > 1. */
    double mapOriginH = 0, mapOriginV = 0, mapExtentH = 1, mapExtentV = 1;
    /** 1 = uncompressed strip; 7 = lossless JPEG tiles (bits is then the JPEG precision, tileSizes the tile byte counts). */
    int compression = 1, tileW = 256, tileH = 256;
    int[] tileSizes;

    Meta copy() {
      Meta m = new Meta();
      m.width = width; m.height = height; m.bits = bits; m.cfa = cfa; m.whiteLevel = whiteLevel; m.orientation = orientation; m.iso = iso;
      m.black = black.clone(); m.colorMatrix1 = colorMatrix1; m.colorMatrix2 = colorMatrix2; m.calibration1 = calibration1; m.calibration2 = calibration2;
      m.forward1 = forward1; m.forward2 = forward2; m.illuminant1 = illuminant1; m.illuminant2 = illuminant2; m.neutral = neutral.clone();
      m.exposureNs = exposureNs; m.fps = fps; m.make = make; m.model = model; m.uniqueModel = uniqueModel; m.software = software; m.dateTime = dateTime;
      m.reel = reel; m.tcHours = tcHours; m.tcMinutes = tcMinutes; m.tcSeconds = tcSeconds; m.tcFrames = tcFrames; m.dropFrame = dropFrame;
      m.shading = shading; m.shadingRows = shadingRows; m.shadingCols = shadingCols;
      m.mapOriginH = mapOriginH; m.mapOriginV = mapOriginV; m.mapExtentH = mapExtentH; m.mapExtentV = mapExtentV;
      m.compression = compression; m.tileW = tileW; m.tileH = tileH; m.tileSizes = tileSizes;
      return m;
    }
  }

  /** DNG colour (0 R, 1 G, 2 B) at a CFA position. */
  static int cfaColor(int cfa, int row, int col) {
    int[][] p = {{0, 1, 1, 2}, {1, 0, 2, 1}, {1, 2, 0, 1}, {2, 1, 1, 0}};
    return p[Math.max(0, Math.min(3, cfa))][(row & 1) * 2 + (col & 1)];
  }

  /** Lens-shading map channel (R, Geven, Godd, B) for a CFA position. */
  static int shadingChannel(int cfa, int row, int col) {
    int c = cfaColor(cfa, row, col);
    return c == 0 ? 0 : c == 2 ? 3 : (row & 1) == 0 ? 1 : 2;
  }

  /** Bytes of image data for a frame. */
  static long stripBytes(Meta m) {
    if (m.compression == 7) { long n = 0; for (int t : m.tileSizes) n += t; return n; }
    return m.bits == 12 ? (long) m.width * m.height * 3 / 2 : (long) m.width * m.height * 2;
  }

  /**
   * Packs little-endian 16-bit samples into 12-bit, MSB-first (TIFF bit order): two samples → three bytes.
   * Width must be even (Bayer sensors always are). Values above 4095 are clipped.
   */
  static void pack12(ByteBuffer src16le, int samples, byte[] dst) {
    ByteBuffer s = src16le.duplicate().order(ByteOrder.LITTLE_ENDIAN);
    int o = 0;
    for (int i = 0; i + 1 < samples; i += 2) {
      int a = s.getShort() & 0xffff, b = s.getShort() & 0xffff;
      if (a > 4095) a = 4095;
      if (b > 4095) b = 4095;
      dst[o++] = (byte) (a >> 4);
      dst[o++] = (byte) (((a & 0xf) << 4) | (b >> 8));
      dst[o++] = (byte) b;
    }
  }

  /** SMPTE 12M timecode as 8 bytes of BCD (CinemaDNG TimeCodes tag). */
  static byte[] smpte(int h, int m, int s, int f, boolean drop) {
    byte[] t = new byte[8];
    t[0] = (byte) (bcd(f) & 0x3f | (drop ? 0x40 : 0));
    t[1] = (byte) (bcd(s) & 0x7f);
    t[2] = (byte) (bcd(m) & 0x7f);
    t[3] = (byte) (bcd(h) & 0x3f);
    return t;
  }

  private static int bcd(int v) { return ((v / 10) << 4) | (v % 10); }

  // ------------------------------------------------------------------------------------------------ TIFF writing

  private static final int BYTE = 1, ASCII = 2, SHORT = 3, LONG = 4, RATIONAL = 5, UNDEFINED = 7, SRATIONAL = 10;

  private static final class Entry {
    final int tag, type, count; final byte[] data;
    Entry(int tag, int type, int count, byte[] data) { this.tag = tag; this.type = type; this.count = count; this.data = data; }
  }

  private static ByteBuffer le(int n) { return ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN); }

  private static Entry shorts(int tag, int... v) { ByteBuffer b = le(v.length * 2); for (int x : v) b.putShort((short) x); return new Entry(tag, SHORT, v.length, b.array()); }

  private static Entry longs(int tag, long... v) { ByteBuffer b = le(v.length * 4); for (long x : v) b.putInt((int) x); return new Entry(tag, LONG, v.length, b.array()); }

  private static Entry bytes(int tag, int type, byte... v) { return new Entry(tag, type, v.length, v); }

  private static Entry ascii(int tag, String s) {
    byte[] raw = (s == null ? "" : s).getBytes(StandardCharsets.US_ASCII);
    byte[] z = new byte[raw.length + 1]; System.arraycopy(raw, 0, z, 0, raw.length);
    return new Entry(tag, ASCII, z.length, z);
  }

  private static Entry rationals(int tag, boolean signed, double... v) {
    ByteBuffer b = le(v.length * 8);
    for (double x : v) {
      int den = 10000;
      if (!signed && x > 200_000) den = 1;
      long num = Math.round(x * den);
      if (signed) num = Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, num)); else num = Math.max(0, Math.min(0xffffffffL, num));
      b.putInt((int) num).putInt(den);
    }
    return new Entry(tag, signed ? SRATIONAL : RATIONAL, v.length, b.array());
  }

  private static Entry matrix(int tag, float[] m) {
    double[] d = new double[9]; for (int i = 0; i < 9; i++) d[i] = m[i];
    return rationals(tag, true, d);
  }

  /** Lens shading as four GainMap opcodes (one per CFA position), big-endian as the DNG spec requires. */
  static byte[] gainMapOpcodes(Meta m) {
    int rows = m.shadingRows, cols = m.shadingCols;
    int params = 4 * 8 + 4 * 2 + 8 * 4 + 4 + rows * cols * 4; // rect+plane info, map points, spacing/origin, mapPlanes, gains
    ByteBuffer b = ByteBuffer.allocate(4 + 4 * (16 + params)).order(ByteOrder.BIG_ENDIAN);
    b.putInt(4);
    for (int r = 0; r < 2; r++) for (int c = 0; c < 2; c++) {
      b.putInt(9).putInt(0x01030000).putInt(1 /* optional */).putInt(params);
      b.putInt(r).putInt(c).putInt(m.height).putInt(m.width);   // top, left, bottom, right
      b.putInt(0).putInt(1).putInt(2).putInt(2);                // plane, planes, row pitch, col pitch
      b.putInt(rows).putInt(cols);
      b.putDouble(m.mapExtentV / Math.max(1, rows - 1)).putDouble(m.mapExtentH / Math.max(1, cols - 1));
      b.putDouble(m.mapOriginV).putDouble(m.mapOriginH);
      b.putInt(1);
      float[] g = m.shading[shadingChannel(m.cfa, r, c)];
      for (int i = 0; i < rows * cols; i++) b.putFloat(g[i]);
    }
    return b.array();
  }

  /**
   * The complete file up to the start of the image data. Image data (stripBytes(m) bytes, 16-bit little-endian
   * samples or 12-bit packed) must follow immediately: the strip offset is header.length.
   */
  static byte[] header(Meta m) {
    List<Entry> e = new ArrayList<>();
    e.add(longs(254, 0));
    e.add(longs(256, m.width));
    e.add(longs(257, m.height));
    e.add(shorts(258, m.bits));
    e.add(shorts(259, m.compression == 7 ? 7 : 1));
    e.add(shorts(262, 32803));
    e.add(ascii(271, m.make));
    e.add(ascii(272, m.model));
    boolean tiled = m.compression == 7;
    if (!tiled) e.add(longs(273, 0)); // StripOffsets, patched below
    e.add(shorts(274, m.orientation));
    e.add(shorts(277, 1));
    if (!tiled) { e.add(longs(278, m.height)); e.add(longs(279, stripBytes(m))); }
    else {
      long[] zero = new long[m.tileSizes.length], counts = new long[m.tileSizes.length];
      for (int i = 0; i < counts.length; i++) counts[i] = m.tileSizes[i];
      e.add(longs(322, m.tileW));
      e.add(longs(323, m.tileH));
      e.add(longs(324, zero));  // TileOffsets, patched below
      e.add(longs(325, counts));
    }
    e.add(shorts(284, 1));
    e.add(ascii(305, m.software));
    if (m.dateTime != null && !m.dateTime.isEmpty()) e.add(ascii(306, m.dateTime));
    e.add(shorts(33421, 2, 2));
    byte[] pattern = new byte[4];
    for (int i = 0; i < 4; i++) pattern[i] = (byte) cfaColor(m.cfa, i / 2, i % 2);
    e.add(bytes(33422, BYTE, pattern));
    ByteBuffer exposure = le(8).putInt((int) Math.min(0xffffffffL, Math.max(1, m.exposureNs / 1000))).putInt(1_000_000); // microseconds
    e.add(new Entry(33434, RATIONAL, 1, exposure.array()));
    e.add(shorts(34855, Math.min(65535, m.iso)));
    e.add(bytes(50706, BYTE, (byte) 1, (byte) 4, (byte) 0, (byte) 0));
    e.add(bytes(50707, BYTE, (byte) 1, (byte) 3, (byte) 0, (byte) 0));
    e.add(ascii(50708, m.uniqueModel));
    e.add(bytes(50710, BYTE, (byte) 0, (byte) 1, (byte) 2));
    e.add(shorts(50711, 1));
    e.add(shorts(50713, 2, 2));
    e.add(rationals(50714, false, m.black[0], m.black[1], m.black[2], m.black[3]));
    e.add(longs(50717, m.whiteLevel));
    if (m.colorMatrix1 != null) e.add(matrix(50721, m.colorMatrix1));
    if (m.colorMatrix2 != null) e.add(matrix(50722, m.colorMatrix2));
    if (m.calibration1 != null) e.add(matrix(50723, m.calibration1));
    if (m.calibration2 != null) e.add(matrix(50724, m.calibration2));
    e.add(rationals(50728, false, m.neutral[0], m.neutral[1], m.neutral[2]));
    e.add(rationals(50730, true, 0));
    if (m.colorMatrix1 != null) e.add(shorts(50778, m.illuminant1));
    if (m.colorMatrix2 != null) e.add(shorts(50779, m.illuminant2));
    if (m.forward1 != null) e.add(matrix(50964, m.forward1));
    if (m.forward2 != null) e.add(matrix(50965, m.forward2));
    if (m.shading != null && m.shadingRows >= 2 && m.shadingCols >= 2) {
      byte[] op = gainMapOpcodes(m);
      e.add(new Entry(51009, UNDEFINED, op.length, op));
    }
    e.add(bytes(51043, BYTE, smpte(m.tcHours, m.tcMinutes, m.tcSeconds, m.tcFrames, m.dropFrame)));
    int nominal = CinemaMath.nominalFps(m.fps);
    boolean ntsc = Math.abs(m.fps - nominal) > 0.001;
    ByteBuffer fr = le(8).putInt(ntsc ? nominal * 1000 : nominal).putInt(ntsc ? 1001 : 1);
    e.add(new Entry(51044, SRATIONAL, 1, fr.array()));
    if (m.reel != null && !m.reel.isEmpty()) e.add(ascii(51081, m.reel));
    Collections.sort(e, (a, b) -> Integer.compare(a.tag, b.tag));

    int ifdSize = 2 + e.size() * 12 + 4;
    int extra = 0;
    for (Entry x : e) if (x.data.length > 4) extra += (x.data.length + 1) & ~1;
    int dataStart = 8 + ifdSize;
    int stripOffset = (dataStart + extra + 15) & ~15;
    ByteBuffer out = le(stripOffset);
    out.put((byte) 'I').put((byte) 'I').putShort((short) 42).putInt(8);
    out.putShort((short) e.size());
    int cursor = dataStart;
    List<Entry> deferred = new ArrayList<>();
    if (tiled) { // tiles follow the header back to back
      ByteBuffer offs = le(m.tileSizes.length * 4);
      long at = stripOffset;
      for (int t : m.tileSizes) { offs.putInt((int) at); at += t; }
      for (int i = 0; i < e.size(); i++) if (e.get(i).tag == 324) e.set(i, new Entry(324, LONG, m.tileSizes.length, offs.array()));
    }
    for (Entry x : e) {
      out.putShort((short) x.tag).putShort((short) x.type).putInt(x.count);
      if (x.tag == 273) { out.putInt(stripOffset); continue; }
      if (x.data.length <= 4) { byte[] v = new byte[4]; System.arraycopy(x.data, 0, v, 0, x.data.length); out.put(v); }
      else { out.putInt(cursor); cursor += (x.data.length + 1) & ~1; deferred.add(x); }
    }
    out.putInt(0); // no further IFDs
    for (Entry x : deferred) { out.put(x.data); if ((x.data.length & 1) == 1) out.put((byte) 0); }
    return out.array();
  }
}
