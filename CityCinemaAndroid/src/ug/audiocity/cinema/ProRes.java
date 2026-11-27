package ug.audiocity.cinema;

import java.nio.ByteBuffer;

/** The native ProRes 422 encoder (jni/prores.c). Absent when the APK was built without the NDK. */
final class ProRes {
  private ProRes() {}

  static final String[] NAMES = {"ProRes 422 Proxy", "ProRes 422 LT", "ProRes 422", "ProRes 422 HQ"};
  static final String[] SHORT = {"Proxy", "LT", "422", "HQ"};
  static final String[] FOURCC = {"apco", "apcs", "apcn", "apch"};

  static final boolean AVAILABLE;
  static {
    boolean ok;
    try { System.loadLibrary("citycinema"); ok = true; } catch (UnsatisfiedLinkError e) { ok = false; }
    AVAILABLE = ok;
  }

  /** Encodes one 8-bit 4:2:0 frame (direct buffers). Returns the frame size in bytes, or negative on error. */
  static native long nativeEncode(ByteBuffer y, int yStride, ByteBuffer u, ByteBuffer v, int cStride, int width, int height,
      int profile, int primaries, int transfer, int matrix, ByteBuffer out, float[] stats, ByteBuffer qHint);

  /** Slices per frame: the size of the per-thread quantiser memory passed as qHint. */
  static native int nativeSlices(int width, int height);

  /** True when the library was built with the NEON fast paths (ARM). */
  static native boolean nativeUsesNeon();

  static native void nativeCopyPlane(ByteBuffer src, int rowStride, int pixelStride, int width, int height, ByteBuffer dst, int dstStride);

  static native long nativeMaxFrameBytes(int width, int height);

  static native int nativeBitsPerMb(int profile);

  /** Bits per macroblock of a profile, as targeted by the encoder (matches jni/prores.c). */
  static int bitsPerMb(int profile) { return new int[] {184, 417, 601, 900}[Math.max(0, Math.min(3, profile))]; }

  /** Target data rate in bits per second. */
  static long bitrate(int width, int height, double fps, int profile) {
    long mbs = (long) ((width + 15) / 16) * ((height + 15) / 16);
    return (long) (mbs * bitsPerMb(profile) * CinemaMath.exactFps(fps));
  }

  /**
   * Output buffer per encoder thread: about 2.5x the HQ target per macroblock plus headroom (~11 MB at 4K), enough
   * for all but pathological frames; those get one retry with the worst-case size.
   */
  static int outputCapacity(int width, int height) {
    long mbs = (long) ((width + 15) / 16) * ((height + 15) / 16);
    return (int) Math.min(Integer.MAX_VALUE - 8, mbs * 300 + 1024 * 1024);
  }

  /** The size that always suffices. */
  static int worstCaseCapacity(int width, int height) { return (int) Math.min(Integer.MAX_VALUE - 8, nativeMaxFrameBytes(width, height)); }
}
