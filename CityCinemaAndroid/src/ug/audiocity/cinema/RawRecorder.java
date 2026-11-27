package ug.audiocity.cinema;

import android.graphics.ImageFormat;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
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
 * CinemaDNG RAW video: RAW_SENSOR frames from Camera2 are copied into a pool of preallocated buffers (so the camera's
 * own buffers are returned immediately) and written as numbered DNG files by parallel writer threads.
 * Frames are numbered by their slot on the exact frame grid: when the writers fall behind and a frame has to be
 * dropped, the next frame is also written into the missing slot(s), so the sequence stays continuous and in sync
 * with the double-system audio. Drops are counted and reported.
 */
final class RawRecorder implements ImageReader.OnImageAvailableListener {
  static final String TAG = "CityCinemaRaw";

  /** Supplies a fresh header template for each frame (live white balance, black level, exposure, shading). */
  interface MetaSource { CinemaDng.Meta next(); }

  private final int width, height;
  private final ImageReader reader;
  private final HandlerThread thread;
  private final ArrayBlockingQueue<ByteBuffer> free;
  private final int poolSize;
  private ExecutorService writers;
  private volatile boolean recording;
  private File dir;
  private String clip;
  private MetaSource meta;
  private double fps;
  private boolean pack12;
  private long startFrames;
  private long firstTimestamp = -1, lastSlot = -1;
  final AtomicLong written = new AtomicLong(), filled = new AtomicLong(), bytes = new AtomicLong();
  volatile long dropped, writeErrors, firstFrameTimestampNs = -1;
  private final ThreadLocal<byte[]> packBuffer = new ThreadLocal<>();

  RawRecorder(int width, int height) {
    this.width = width; this.height = height;
    thread = new HandlerThread("cinema-raw"); thread.start();
    reader = ImageReader.newInstance(width, height, ImageFormat.RAW_SENSOR, 4);
    reader.setOnImageAvailableListener(this, new Handler(thread.getLooper()));
    long frameBytes = (long) width * height * 2;
    // Keep the pool within ~35% of the heap the app may use (largeHeap), between 3 and 12 frames.
    long budget = (long) (Runtime.getRuntime().maxMemory() * 0.35);
    poolSize = (int) Math.max(3, Math.min(12, budget / Math.max(1, frameBytes)));
    free = new ArrayBlockingQueue<>(poolSize);
    for (int i = 0; i < poolSize; i++) free.add(ByteBuffer.allocateDirect((int) frameBytes).order(ByteOrder.LITTLE_ENDIAN));
  }

  Surface surface() { return reader.getSurface(); }

  int poolSize() { return poolSize; }

  /** Frames waiting to be written (0 = keeping up, poolSize = about to drop). */
  int backlog() { return poolSize - free.size(); }

  boolean isRecording() { return recording; }

  /** Bytes per frame on disk. */
  long frameBytes(boolean packed) { CinemaDng.Meta m = new CinemaDng.Meta(); m.width = width; m.height = height; m.bits = packed ? 12 : 16; return CinemaDng.stripBytes(m) + 4096; }

  void start(File dir, String clip, double fps, boolean pack12, long startFrames, MetaSource meta) {
    this.dir = dir; this.clip = clip; this.fps = fps; this.pack12 = pack12; this.startFrames = startFrames; this.meta = meta;
    firstTimestamp = -1; lastSlot = -1; firstFrameTimestampNs = -1; dropped = 0; writeErrors = 0;
    written.set(0); filled.set(0); bytes.set(0);
    int threads = Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors() / 2));
    writers = Executors.newFixedThreadPool(threads, r -> { Thread t = new Thread(r, "cinema-dng"); t.setPriority(Thread.NORM_PRIORITY + 1); return t; });
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
    reader.close();
    thread.quitSafely();
  }

  @Override public void onImageAvailable(ImageReader r) {
    Image img;
    try { img = r.acquireNextImage(); } catch (IllegalStateException e) { return; }
    if (img == null) return;
    try {
      if (!recording) return;
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
      CinemaDng.Meta m = meta.next();
      m.width = width; m.height = height; m.bits = pack12 && m.whiteLevel <= 4095 ? 12 : 16; m.fps = fps;
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
    int rowBytes = width * 2, stride = p.getRowStride();
    dst.clear();
    if (stride == rowBytes) { src.limit(Math.min(src.capacity(), rowBytes * height)); dst.put(src); }
    else for (int y = 0; y < height; y++) { src.limit(y * stride + rowBytes); src.position(y * stride); dst.put(src); }
    dst.flip();
  }

  private void write(ByteBuffer pixels, CinemaDng.Meta m, long firstSlot, int copies) {
    try {
      ByteBuffer data;
      if (m.bits == 12) {
        int size = (int) CinemaDng.stripBytes(m);
        byte[] packed = packBuffer.get();
        if (packed == null || packed.length != size) { packed = new byte[size]; packBuffer.set(packed); }
        CinemaDng.pack12(pixels, width * height, packed);
        data = ByteBuffer.wrap(packed);
      } else data = pixels.duplicate();
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
        bytes.addAndGet(header.length + data.capacity());
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
