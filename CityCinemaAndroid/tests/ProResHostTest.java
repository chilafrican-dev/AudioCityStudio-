package ug.audiocity.cinema;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

/**
 * End-to-end ProRes check on the host: synthetic 8-bit 4:2:0 frames → native encoder → MovWriter (.mov with 24-bit
 * PCM and timecode). tools/verify_prores.py then decodes the file with FFmpeg and measures PSNR against the source.
 * Usage: ProResHostTest <dir> [width height frames]
 */
public class ProResHostTest {
  public static void main(String[] args) throws IOException {
    String dir = args[0];
    int w = args.length > 1 ? Integer.parseInt(args[1]) : 1920, h = args.length > 2 ? Integer.parseInt(args[2]) : 1080;
    int frames = args.length > 3 ? Integer.parseInt(args[3]) : 12;
    if (!ProRes.AVAILABLE) throw new IllegalStateException("native library not found");
    int cw = (w + 1) / 2, ch = (h + 1) / 2;
    ByteBuffer y = ByteBuffer.allocateDirect(w * h), u = ByteBuffer.allocateDirect(cw * ch), v = ByteBuffer.allocateDirect(cw * ch);
    ByteBuffer out = ByteBuffer.allocateDirect(ProRes.outputCapacity(w, h));
    for (int profile = 0; profile < 4; profile++) {
      String name = dir + "/test_" + ProRes.SHORT[profile];
      try (RandomAccessFile f = new RandomAccessFile(name + ".mov", "rw"); FileChannel chn = f.getChannel();
           FileOutputStream src = new FileOutputStream(name + ".yuv")) {
        f.setLength(0);
        MovWriter.Config c = new MovWriter.Config();
        c.width = w; c.height = h; c.fourcc = ProRes.FOURCC[profile]; c.compressorName = "Apple " + ProRes.NAMES[profile];
        c.fps = 23.976; c.primaries = 1; c.transfer = 1; c.matrix = 6; c.startTimecodeFrames = 24 * 3600 + 24 * 2 + 5; c.reelName = "A001";
        MovWriter mov = new MovWriter(chn, c);
        long bytes = 0, nanos = 0;
        float[] stats = new float[2];
        for (int i = 0; i < frames; i++) {
          fill(y, u, v, w, h, i);
          long t0 = System.nanoTime();
          long n = ProRes.nativeEncode(y, w, u, v, cw, w, h, profile, 1, 1, 6, out, stats);
          nanos += System.nanoTime() - t0;
          if (n <= 0) throw new IllegalStateException("encode failed " + n);
          out.position(0).limit((int) n);
          mov.writeVideo(out, i == 5 ? 2 : 1); // frame 5 also fills a dropped slot
          out.clear();
          bytes += n;
          byte[] raw = new byte[w * h + 2 * cw * ch];
          y.position(0); y.get(raw, 0, w * h); u.position(0); u.get(raw, w * h, cw * ch); v.position(0); v.get(raw, w * h + cw * ch, cw * ch);
          y.clear(); u.clear(); v.clear();
          src.write(raw);
          if (i == 5) src.write(raw);
          // 1 kHz tone at -12 dBFS, stereo, one frame's worth of samples
          int samples = (int) Math.round(48000 / CinemaMath.exactFps(c.fps) * (i == 5 ? 2 : 1));
          float[] pcm = new float[samples * 2];
          for (int s = 0; s < samples; s++) { float a = (float) (0.25 * Math.sin(2 * Math.PI * 1000 * (mov.audioFramesWritten() + s) / 48000.0)); pcm[2 * s] = a; pcm[2 * s + 1] = -a; }
          mov.writeAudio(pcm, samples);
        }
        mov.finish();
        double ms = nanos / 1e6 / frames;
        System.out.printf("%s: %d frames, %.0f KB/frame avg, q %.1f, %.1f ms/frame on one core (%.1f fps), target %.0f Mb/s%n",
            ProRes.NAMES[profile], frames, bytes / 1024.0 / frames, stats[0], ms, 1000 / ms, ProRes.bitrate(w, h, c.fps, profile) / 1e6);
      }
    }
  }

  /** Moving gradients, fine detail, hard edges and noise: a hard but realistic test picture. */
  static void fill(ByteBuffer y, ByteBuffer u, ByteBuffer v, int w, int h, int t) {
    java.util.Random r = new java.util.Random(t);
    for (int j = 0; j < h; j++) for (int i = 0; i < w; i++) {
      int base = (i * 255 / w + t * 3) & 255;
      double cx = i - w / 2.0 - t * 4, cy = j - h / 2.0;
      boolean ring = ((int) Math.sqrt(cx * cx + cy * cy) / 24) % 2 == 0;
      int detail = ((i / 2 + j / 2) % 2) * 30;
      int val = (ring ? base : 255 - base) / 2 + (j < h / 3 ? detail : 0) + 40 + r.nextInt(9) - 4;
      y.put(j * w + i, (byte) Math.max(0, Math.min(255, val)));
    }
    int cw = (w + 1) / 2, ch = (h + 1) / 2;
    for (int j = 0; j < ch; j++) for (int i = 0; i < cw; i++) {
      u.put(j * cw + i, (byte) (128 + 60 * Math.sin((i + t * 2) / 40.0)));
      v.put(j * cw + i, (byte) (128 + 50 * Math.cos(j / 30.0)));
    }
  }
}
