package ug.audiocity.cinema;

import java.nio.ByteBuffer;

/** Native RAW helpers (jni/rawtools.c): lossless JPEG DNG tiles, crop/remosaic copy, RAW monitor image. */
final class RawNative {
  private RawNative() {}

  /** Lossless JPEG tile size (DNG convention: 256 x 256). */
  static final int TILE = 256;

  static final boolean AVAILABLE = ProRes.AVAILABLE; // same native library

  static native void copy(ByteBuffer src, int rowStride, int x0, int y0, int w, int h, boolean quad, ByteBuffer dst);

  static native void preview(ByteBuffer pixels, int w, int h, int cfa, float[] black, int white, float[] gains, int outW, int outH, int[] argb);

  /** Compresses a frame into TILE x TILE lossless JPEG tiles. Returns total bytes (or negative); fills tileSizes. */
  static native long lj92(ByteBuffer pixels, int w, int h, int bits, int tw, int th, ByteBuffer out, int[] tileSizes);

  static native long lj92MaxBytes(int w, int h, int tw, int th);

  static int tiles(int w, int h) { return ((w + TILE - 1) / TILE) * ((h + TILE - 1) / TILE); }

  /**
   * Whether frames from a sensor with this white level are compressed. Lossless JPEG allows up to 16-bit precision,
   * but LibRaw (and so many raw tools) mis-decode 16-bit precision, so only sensors up to 14 bits are compressed;
   * phone sensors are 10, 12 or 14-bit.
   */
  static boolean compressible(int whiteLevel) { return AVAILABLE && whiteLevel <= 16383; }

  /** Smallest JPEG precision that holds the sensor's white level (lossless JPEG allows 2..16 bits). */
  static int precisionFor(int whiteLevel) {
    int bits = 2;
    while (bits < 16 && (1 << bits) - 1 < whiteLevel) bits++;
    return bits;
  }
}
