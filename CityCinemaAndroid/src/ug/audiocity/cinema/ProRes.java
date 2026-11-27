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
      int profile, int primaries, int transfer, int matrix, ByteBuffer out, float[] stats);

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

  /** A practical output buffer: the target size with generous headroom for difficult frames. */
  static int outputCapacity(int width, int height) {
    long mbs = (long) ((width + 15) / 16) * ((height + 15) / 16);
    return (int) Math.min(Integer.MAX_VALUE - 8, mbs * 800 + 512 * 1024);
  }
}
