package ug.audiocity.cinema;

import android.graphics.ImageFormat;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * CinemaDNG RAW video: RAW_SENSOR frames from Camera2 are copied (cropped, and remosaiced from quad Bayer when the
 * sensor runs in maximum-resolution mode) into a pool of preallocated buffers, so the camera's buffers return
 * immediately, then written as numbered DNG files by parallel writers: lossless JPEG compressed (about 2:1) or
 * uncompressed 12/16-bit. Frames are numbered by their slot on the exact frame grid; if the writers fall behind,
 * the next frame also fills the missing slot(s), so the sequence stays continuous and in sync with the sound.
 * Optionally it also produces a small RGB monitor image (used for the 8K preview, where the sensor mode leaves no
 * room for a separate preview stream).
 */
final class RawRecorder implements ImageReader.OnImageAvailableListener {
  static final String TAG = "CityCinemaRaw";

  /** Supplies a fresh header template for each frame (live white balance, black level, exposure, shading). */
  interface MetaSource { CinemaDng.Meta next(); }

  /** Receives monitor images (ARGB, MONITOR_W x monitorHeight) on the RAW thread. */
  interface MonitorSink { void onMonitor(int[] argb, int width, int height); }

  /** How the frame is taken out of the camera buffer. */
  static final class Geometry {
    int sensorW, sensorH, cropX, cropY, width, height;
    boolean quad;
    static Geometry full(int w, int h) { Geometry g = new Geometry(); g.sensorW = g.width = w; g.sensorH = g.height = h; return g; }
  }

  static final int MONITOR_W = 480;

  private final Geometry geo;
  private final int width, height;
  private final ImageReader reader;
  private final HandlerThread thread;
  private final ArrayBlockingQueue<ByteBuffer> free;
  private final int poolSize, writerThreads;
  private ExecutorService writers;
  private volatile boolean recording;
  private File dir;
  private String clip;
  private MetaSource meta;
  private double fps;
  private boolean pack12, compress;
  private long startFrames;
  private long firstTimestamp = -1, lastSlot = -1;
  final AtomicLong written = new AtomicLong(), filled = new AtomicLong(), bytes = new AtomicLong();
  volatile long dropped, writeErrors, firstFrameTimestampNs = -1, uncompressedFallbacks;
  private final ThreadLocal<byte[]> packBuffer = new ThreadLocal<>();
  private final ThreadLocal<ByteBuffer> jpegBuffer = new ThreadLocal<>();
  private volatile MonitorSink monitor;
  private volatile float[] monitorGains = {2f, 1f, 1.6f};
  private volatile int monitorCfa;
  private volatile float[] monitorBlack = {64, 64, 64, 64};
  private volatile int monitorWhite = 1023;
  private ByteBuffer monitorFrame;
  private int[] monitorPixels;
  private long lastMonitorMs;

  RawRecorder(Geometry g) {
    geo = g; width = g.width; height = g.height;
    long frameBytes = (long) width * height * 2;
    boolean huge = frameBytes > 32L * 1024 * 1024;
    thread = new HandlerThread("cinema-raw"); thread.start();
    // Each camera buffer at maximum resolution is ~100 MB of native memory: keep only two in flight there.
    reader = ImageReader.newInstance(g.sensorW, g.sensorH, ImageFormat.RAW_SENSOR, huge ? 2 : 4);
    reader.setOnImageAvailableListener(this, new Handler(thread.getLooper()));
    long heap = Runtime.getRuntime().maxMemory();
    // Pool within ~35% of the heap (largeHeap): 3..12 frames, or 2 for 8K-class frames.
    poolSize = (int) Math.max(huge ? 2 : 3, Math.min(12, (long) (heap * 0.35) / Math.max(1, frameBytes)));
    // Writers: each compressing writer holds an output buffer of ~1.25x a frame.
    long perWriter = jpegCapacity(width, height);
    int cores = Runtime.getRuntime().availableProcessors();
    writerThreads = (int) Math.max(1, Math.min(Math.max(2, Math.min(4, cores / 2)), (long) (heap * 0.3) / Math.max(1, perWriter)));
    free = new ArrayBlockingQueue<>(poolSize);
    for (int i = 0; i < poolSize; i++) free.add(ByteBuffer.allocateDirect((int) frameBytes).order(ByteOrder.LITTLE_ENDIAN));
  }

  static int jpegCapacity(int w, int h) { return (int) Math.min(Integer.MAX_VALUE - 8, (long) w * h * 5 / 4 + 1024 * 1024); }

  Surface surface() { return reader.getSurface(); }

  int poolSize() { return poolSize; }

  int writerThreads() { return writerThreads; }

  /** Frames waiting to be written (0 = keeping up, poolSize = about to drop). */
  int backlog() { return poolSize - free.size(); }

  boolean isRecording() { return recording; }

  /** Enables the RAW monitor image (for previewless sessions). White balance gains are R, G, B multipliers. */
  void setMonitor(MonitorSink sink, int cfa, float[] black, int white) {
    monitorCfa = cfa; monitorBlack = black.clone(); monitorWhite = white; monitor = sink;
  }

  void setMonitorGains(float[] rgb) { monitorGains = rgb; }

  void start(File dir, String clip, double fps, boolean pack12, boolean compress, long startFrames, MetaSource meta) {
    this.dir = dir; this.clip = clip; this.fps = fps; this.pack12 = pack12; this.startFrames = startFrames; this.meta = meta;
    this.compress = compress && RawNative.AVAILABLE;
    firstTimestamp = -1; lastSlot = -1; firstFrameTimestampNs = -1; dropped = 0; writeErrors = 0; uncompressedFallbacks = 0;
    written.set(0); filled.set(0); bytes.set(0);
    writers = Executors.newFixedThreadPool(writerThreads, r -> { Thread t = new Thread(r, "cinema-dng"); t.setPriority(Thread.NORM_PRIORITY + 1); return t; });
    recording = true;
  }

  /** Stops taking frames and waits for every queued frame to reach the disk. Returns the number of frames written. */
  long stop(long timeoutMs) {
    recording = false;
    ExecutorService w = writers;
    if (w != null) {
      w.shutdown();
      try { if (!w.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS)) Log.w(TAG, "writers still busy after " + timeoutMs + " ms"); }
      catch (InterruptedException e) { Thread.currentThread().interrupt(); }
      writers = null;
    }
    return written.get() + filled.get();
  }

  void release() {
    if (recording) stop(20_000);
    monitor = null;
    reader.close();
    thread.quitSafely();
  }

  @Override public void onImageAvailable(ImageReader r) {
    Image img;
    try { img = r.acquireNextImage(); } catch (IllegalStateException e) { return; }
    if (img == null) return;
    try {
      if (!recording) { monitorFromImage(img); return; }
      long ts = img.getTimestamp();
      long frameNs = CinemaMath.frameDurationNs(fps);
      if (firstTimestamp < 0) { firstTimestamp = ts; firstFrameTimestampNs = ts; }
      long slot = Math.round((ts - firstTimestamp) / (double) frameNs);
      if (slot <= lastSlot) return; // camera ran a hair fast: this slot already has a frame
      ByteBuffer buf = free.poll();
      if (buf == null) { dropped++; return; } // writers behind: the next frame fills this slot
      int copies = (int) Math.min(12, slot - lastSlot);
      long firstSlot = slot - copies + 1;
      lastSlot = slot;
      copy(img, buf);
      maybeMonitor(buf);
      CinemaDng.Meta m = meta.next();
      m.width = width; m.height = height; m.fps = fps;
      try { writers.execute(() -> write(buf, m, firstSlot, copies)); }
      catch (RuntimeException e) { free.offer(buf); throw e; } // stopping: give the buffer back
    } catch (RuntimeException e) {
      Log.w(TAG, "frame", e);
    } finally {
      img.close();
    }
  }

  private void copy(Image img, ByteBuffer dst) {
    Image.Plane p = img.getPlanes()[0];
    ByteBuffer src = p.getBuffer();
    int stride = p.getRowStride();
    dst.clear();
    if (RawNative.AVAILABLE) {
      RawNative.copy(src, stride, geo.cropX, geo.cropY, width, height, geo.quad, dst);
      dst.limit(width * height * 2);
      return;
    }
    int rowBytes = width * 2;
    for (int y = 0; y < height; y++) {
      int at = (geo.cropY + y) * stride + geo.cropX * 2;
      src.limit(at + rowBytes); src.position(at); dst.put(src);
    }
    dst.flip();
  }

  /** While not recording: copy into a scratch buffer and make a monitor image, at most ~8 times a second. */
  private void monitorFromImage(Image img) {
    MonitorSink sink = monitor;
    if (sink == null || !RawNative.AVAILABLE) return;
    long now = SystemClock.uptimeMillis();
    if (now - lastMonitorMs < 120) return;
    if (monitorFrame == null) monitorFrame = ByteBuffer.allocateDirect(width * height * 2).order(ByteOrder.LITTLE_ENDIAN);
    copy(img, monitorFrame);
    maybeMonitor(monitorFrame);
  }

  private void maybeMonitor(ByteBuffer frame) {
    MonitorSink sink = monitor;
    if (sink == null || !RawNative.AVAILABLE) return;
    long now = SystemClock.uptimeMillis();
    if (now - lastMonitorMs < 120 && recording) return;
    lastMonitorMs = now;
    int mh = Math.max(1, MONITOR_W * height / width);
    if (monitorPixels == null || monitorPixels.length != MONITOR_W * mh) monitorPixels = new int[MONITOR_W * mh];
    RawNative.preview(frame, width, height, monitorCfa, monitorBlack, monitorWhite, monitorGains, MONITOR_W, mh, monitorPixels);
    sink.onMonitor(monitorPixels, MONITOR_W, mh);
  }

  private void write(ByteBuffer pixels, CinemaDng.Meta m, long firstSlot, int copies) {
    try {
      ByteBuffer data = null;
      if (compress && RawNative.compressible(m.whiteLevel)) {
        ByteBuffer out = jpegBuffer.get();
        if (out == null) { out = ByteBuffer.allocateDirect(jpegCapacity(width, height)); jpegBuffer.set(out); }
        int[] sizes = new int[RawNative.tiles(width, height)];
        int bits = RawNative.precisionFor(m.whiteLevel);
        long n = RawNative.lj92(pixels, width, height, bits, RawNative.TILE, RawNative.TILE, out, sizes);
        if (n > 0) {
          m.compression = 7; m.bits = bits; m.tileW = RawNative.TILE; m.tileH = RawNative.TILE; m.tileSizes = sizes;
          out.position(0).limit((int) n);
          data = out;
        } else uncompressedFallbacks++; // an unusually noisy frame didn't fit: store it uncompressed
      }
      if (data == null) {
        m.compression = 1;
        m.bits = pack12 && m.whiteLevel <= 4095 ? 12 : 16;
        if (m.bits == 12) {
          int size = (int) CinemaDng.stripBytes(m);
          byte[] packed = packBuffer.get();
          if (packed == null || packed.length != size) { packed = new byte[size]; packBuffer.set(packed); }
          CinemaDng.pack12(pixels, width * height, packed);
          data = ByteBuffer.wrap(packed);
        } else data = pixels.duplicate();
      }
      for (int i = 0; i < copies; i++) {
        long slot = firstSlot + i;
        int[] tc = CinemaMath.timecodeFields(startFrames + slot, fps);
        m.tcHours = tc[0]; m.tcMinutes = tc[1]; m.tcSeconds = tc[2]; m.tcFrames = tc[3]; m.dropFrame = CinemaMath.isDropFrameRate(fps);
        byte[] header = CinemaDng.header(m);
        File f = new File(dir, String.format(Locale.US, "%s_%06d.dng", clip, slot));
        try (RandomAccessFile out = new RandomAccessFile(f, "rw"); FileChannel ch = out.getChannel()) {
          ByteBuffer h = ByteBuffer.wrap(header), d = data.duplicate();
          while (h.hasRemaining()) ch.write(h);
          while (d.hasRemaining()) ch.write(d);
        }
        bytes.addAndGet(header.length + data.remaining());
        if (i == copies - 1) written.incrementAndGet(); else filled.incrementAndGet();
      }
    } catch (IOException | RuntimeException e) {
      writeErrors++;
      Log.w(TAG, "write", e);
    } finally {
      free.offer(pixels);
    }
  }
}
