package ug.audiocity.cinema;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Host test of the native RAW tools: lossless JPEG CinemaDNG (verified by tools/verify_dng.py with LibRaw:
 * decoded samples must equal the source exactly), quad-Bayer remosaic copy and the RAW monitor image.
 * Usage: RawHostTest <dir>
 */
public class RawHostTest {
  static int checks;

  static void check(boolean ok, String what) { if (!ok) throw new AssertionError("FAILED: " + what); checks++; }

  /** A synthetic frame with smooth gradients, edges and noise (realistic for compression). */
  static int sample(int x, int y, int white, int seed) {
    int v = (x * 3 + y * 2) % (white / 2) + ((x / 64 + y / 48) % 3) * white / 8 + ((x * 7919 + y * 104729 + seed) % 23);
    return Math.max(0, Math.min(white, v));
  }

  public static void main(String[] args) throws IOException {
    String dir = args[0];
    long totalRaw = 0, totalPacked = 0;
    int[][] cases = {{1000, 750, 4095}, {600, 400, 16383}, {512, 256, 65535}, {258, 35, 1023}};
    for (int[] c : cases) {
      int w = c[0], h = c[1], white = c[2];
      ByteBuffer px = ByteBuffer.allocateDirect(w * h * 2).order(ByteOrder.LITTLE_ENDIAN);
      for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) px.putShort((short) sample(x, y, white, w));
      px.clear();
      int bits = RawNative.precisionFor(white);
      int[] sizes = new int[RawNative.tiles(w, h)];
      ByteBuffer out = ByteBuffer.allocateDirect((int) RawNative.lj92MaxBytes(w, h, RawNative.TILE, RawNative.TILE));
      long n = RawNative.lj92(px, w, h, bits, RawNative.TILE, RawNative.TILE, out, sizes);
      check(n > 0, "lj92 " + w + "x" + h);
      long sum = 0; for (int s : sizes) sum += s;
      check(sum == n, "tile sizes add up");
      CinemaDng.Meta m = CinemaTests.sampleMeta(16);
      m.width = w; m.height = h; m.whiteLevel = white; m.bits = bits; m.compression = 7; m.tileSizes = sizes;
      m.black = new float[] {0, 0, 0, 0};
      byte[] header = CinemaDng.header(m);
      byte[] data = new byte[(int) n]; out.position(0); out.get(data);
      // 16-bit precision is valid lossless JPEG (checked by tools/lj92_decode.py), but LibRaw mis-decodes it, so the
      // app only compresses <= 14-bit sensors; that file is named so only the spec decoder checks it.
      String name = dir + (bits > 14 ? "/spec16_" : "/lj92_") + w + "x" + h + "_" + bits + ".dng";
      try (FileOutputStream o = new FileOutputStream(name)) { o.write(header); o.write(data); }
      try (FileOutputStream o = new FileOutputStream(name + ".raw")) { byte[] raw = new byte[w * h * 2]; px.position(0); px.get(raw); o.write(raw); }
      totalRaw += (long) w * h * bits / 8; totalPacked += n;
      System.out.printf("lossless JPEG %dx%d %d-bit: %d tiles, %.2f:1 vs packed %d-bit%n", w, h, bits, sizes.length, w * (double) h * bits / 8 / n, bits);
    }
    // quad Bayer remosaic: in each 4x4 tile, rows 1<->2 and columns 1<->2 are swapped, then cropped
    int qw = 16, qh = 12;
    ByteBuffer quad = ByteBuffer.allocateDirect(qw * qh * 2).order(ByteOrder.LITTLE_ENDIAN);
    for (int y = 0; y < qh; y++) for (int x = 0; x < qw; x++) quad.putShort((short) (y * 100 + x));
    quad.clear();
    ByteBuffer dst = ByteBuffer.allocateDirect(8 * 8 * 2).order(ByteOrder.LITTLE_ENDIAN);
    RawNative.copy(quad, qw * 2, 4, 4, 8, 8, true, dst);
    int[] sh = {0, 2, 1, 3};
    for (int r = 0; r < 8; r++) for (int c = 0; c < 8; c++) {
      int sr = 4 + (r & ~3) + sh[r & 3], sc = 4 + (c & ~3) + sh[c & 3];
      check(dst.getShort((r * 8 + c) * 2) == sr * 100 + sc, "quad remosaic pixel " + r + "," + c);
    }
    RawNative.copy(quad, qw * 2, 2, 2, 8, 8, false, dst);
    check(dst.getShort(0) == 202 && dst.getShort((7 * 8 + 7) * 2) == 909, "plain crop");
    // monitor image: a neutral grey frame comes out grey, and the white balance gains act per channel
    int w = 64, h = 48;
    ByteBuffer grey = ByteBuffer.allocateDirect(w * h * 2).order(ByteOrder.LITTLE_ENDIAN);
    for (int i = 0; i < w * h; i++) grey.putShort((short) 1100);
    grey.clear();
    int[] argb = new int[16 * 12];
    RawNative.preview(grey, w, h, CinemaDng.RGGB, new float[] {64, 64, 64, 64}, 4095, new float[] {1, 1, 1}, 16, 12, argb);
    int p = argb[5 * 16 + 5], r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
    check(r == g && g == b && r > 100 && r < 200, "grey stays grey in the RAW monitor: " + r + "," + g + "," + b);
    RawNative.preview(grey, w, h, CinemaDng.RGGB, new float[] {64, 64, 64, 64}, 4095, new float[] {2, 1, 0.5f}, 16, 12, argb);
    p = argb[0];
    check(((p >> 16) & 255) > ((p >> 8) & 255) && ((p >> 8) & 255) > (p & 255), "monitor applies white balance gains");
    System.out.printf("RawHostTest: %d checks passed; lossless JPEG overall %.2f:1 against packed samples%n", checks, totalRaw / (double) totalPacked);
  }
}
