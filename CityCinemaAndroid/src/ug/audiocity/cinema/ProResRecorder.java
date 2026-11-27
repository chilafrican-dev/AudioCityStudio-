package ug.audiocity.cinema;

import android.graphics.ImageFormat;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.TreeMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ProRes 422 recording: YUV_420_888 frames from Camera2 are copied (natively) into a pool of buffers, encoded in
 * parallel by the native encoder on several threads, and written in capture order into a QuickTime .mov with
 * 24-bit PCM audio and a timecode track. Like RAW, frames are numbered by their slot on the exact frame grid: if
 * the encoders fall behind, the next frame also stands in for the missing slot(s), so the file keeps a constant
 * frame rate and stays in sync with sound. Drops are counted.
 */
final class ProResRecorder implements ImageReader.OnImageAvailableListener, CinemaRecorder.AudioEngine.PcmSink {
  static final String TAG = "CityCinemaProRes";

  private final int width, height, cw, ch;
  private final ImageReader reader;
  private final HandlerThread thread;
  private final ArrayBlockingQueue<ByteBuffer[]> free;
  private final int poolSize, encoders;
  private final ThreadLocal<ByteBuffer> output = new ThreadLocal<>(), hints = new ThreadLocal<>();
  private ExecutorService pool;
  private volatile boolean recording;
  private MovWriter mov;
  private MovWriter.AudioAligner aligner;
  private double fps;
  private int profile, primaries = 1, transfer = 1, matrix = 6;
  private long firstTimestamp = -1, lastSlot = -1, sequence, nextToWrite;
  private final TreeMap<Long, Object[]> ready = new TreeMap<>();
  private final Object writeLock = new Object();
  final AtomicLong encoded = new AtomicLong(), encodeNanos = new AtomicLong();
  volatile long dropped, errors, firstFrameNs = -1;
  volatile float lastQ;

  ProResRecorder(int width, int height) {
    this.width = width; this.height = height; cw = (width + 1) / 2; ch = (height + 1) / 2;
    thread = new HandlerThread("cinema-yuv"); thread.start();
    reader = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 4);
    reader.setOnImageAvailableListener(this, new Handler(thread.getLooper()));
    long frameBytes = (long) width * height * 3 / 2;
    encoders = threadsFor(width, height);
    long budget = (long) (Runtime.getRuntime().maxMemory() * 0.25);
    poolSize = (int) Math.max(encoders + 1, Math.min(encoders * 2 + 2, budget / Math.max(1, frameBytes)));
    free = new ArrayBlockingQueue<>(poolSize);
    for (int i = 0; i < poolSize; i++)
      free.add(new ByteBuffer[] {ByteBuffer.allocateDirect(width * height), ByteBuffer.allocateDirect(cw * ch), ByteBuffer.allocateDirect(cw * ch)});
  }

  /**
   * Encoder threads: one per core except one (left for the camera, audio and UI), at most 8, and no more than the
   * memory allows: each thread holds an output buffer and keeps about two input frames in flight (~37 MB at 4K).
   */
  static int threadsFor(int width, int height) {
    int cores = Runtime.getRuntime().availableProcessors();
    long perThread = ProRes.outputCapacity(width, height) + 2L * width * height * 3 / 2;
    long budget = (long) (Runtime.getRuntime().maxMemory() * 0.45);
    return (int) Math.max(2, Math.min(Math.min(8, cores - 1), budget / Math.max(1, perThread)));
  }

  /**
   * Measures real-time capability: encodes frames of a camera-like test picture on all encoder threads for about
   * `millis` and returns the sustained frame rate. Run it before shooting to see whether e.g. 4K HQ keeps up.
   */
  static double benchmark(int width, int height, int profile, long millis) throws InterruptedException {
    int threads = threadsFor(width, height), cw = (width + 1) / 2, ch = (height + 1) / 2;
    ByteBuffer y = ByteBuffer.allocateDirect(width * height), u = ByteBuffer.allocateDirect(cw * ch), v = ByteBuffer.allocateDirect(cw * ch);
    java.util.Random r = new java.util.Random(7);
    for (int j = 0; j < height; j++) for (int i = 0; i < width; i++) {
      int val = 30 + i * 140 / width + j * 50 / height + ((i / 97 + j / 61) % 7 == 0 ? 40 : 0) + r.nextInt(3) - 1;
      y.put(j * width + i, (byte) val);
    }
    for (int i = 0; i < cw * ch; i++) { u.put(i, (byte) (100 + (i % cw) * 50 / cw)); v.put(i, (byte) (140 - (i / cw) * 30 / ch)); }
    final AtomicLong frames = new AtomicLong();
    final long end = System.nanoTime() + millis * 1_000_000L;
    Thread[] workers = new Thread[threads];
    for (int t = 0; t < threads; t++) {
      workers[t] = new Thread(() -> {
        ByteBuffer out = ByteBuffer.allocateDirect(ProRes.outputCapacity(width, height));
        ByteBuffer hint = ByteBuffer.allocateDirect(ProRes.nativeSlices(width, height));
        float[] stats = new float[2];
        while (System.nanoTime() < end) {
          if (ProRes.nativeEncode(y, width, u, v, cw, width, height, profile, 1, 1, 6, out, stats, hint) > 0) frames.incrementAndGet();
          out.clear();
        }
      }, "prores-bench");
      workers[t].setPriority(Thread.MAX_PRIORITY - 1);
    }
    long start = System.nanoTime();
    for (Thread w : workers) w.start();
    for (Thread w : workers) w.join();
    return frames.get() / ((System.nanoTime() - start) / 1e9);
  }

  Surface surface() { return reader.getSurface(); }

  int poolSize() { return poolSize; }

  int backlog() { return poolSize - free.size(); }

  int encoderThreads() { return encoders; }

  boolean isRecording() { return recording; }

  /** Average encode speed of one thread, and the resulting throughput of all threads, in frames per second. */
  double throughputFps() {
    long n = encoded.get();
    if (n == 0) return 0;
    double perFrameMs = encodeNanos.get() / 1e6 / n;
    return encoders * 1000 / perFrameMs;
  }

  void start(FileChannel file, MovWriter.Config config, int profile) throws IOException {
    this.fps = config.fps; this.profile = profile;
    primaries = config.primaries; transfer = config.transfer; matrix = config.matrix;
    mov = new MovWriter(file, config);
    aligner = config.audioChannels > 0 ? new MovWriter.AudioAligner(config.audioChannels, config.sampleRate) : null;
    firstTimestamp = -1; lastSlot = -1; sequence = 0; nextToWrite = 0; firstFrameNs = -1;
    dropped = 0; errors = 0; encoded.set(0); encodeNanos.set(0); ready.clear();
    pool = Executors.newFixedThreadPool(encoders, r -> { Thread t = new Thread(r, "cinema-prores"); t.setPriority(Thread.MAX_PRIORITY - 1); return t; });
    recording = true;
  }

  /** Stops, waits for queued frames to be encoded and written, and finalises the .mov. Returns frame slots written. */
  long stop(long timeoutMs) {
    recording = false;
    ExecutorService p = pool;
    if (p != null) {
      p.shutdown();
      try { if (!p.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS)) Log.w(TAG, "encoders still busy"); }
      catch (InterruptedException e) { Thread.currentThread().interrupt(); }
      pool = null;
    }
    synchronized (writeLock) {
      try { if (mov != null) mov.finish(); } catch (IOException e) { errors++; Log.w(TAG, "finish", e); }
      long n = mov == null ? 0 : mov.videoFrames();
      mov = null;
      return n;
    }
  }

  long bytesWritten() { MovWriter m = mov; return m == null ? 0 : m.bytesWritten(); }

  void release() {
    if (recording) stop(20_000);
    reader.close();
    thread.quitSafely();
  }

  // ------------------------------------------------------------------------------------------------ frames

  @Override public void onImageAvailable(ImageReader r) {
    Image img;
    try { img = r.acquireNextImage(); } catch (IllegalStateException e) { return; }
    if (img == null) return;
    try {
      if (!recording) return;
      long ts = img.getTimestamp();
      if (firstTimestamp < 0) {
        firstTimestamp = ts; firstFrameNs = ts;
        synchronized (writeLock) { try { if (aligner != null) aligner.videoStarted(ts, mov); } catch (IOException e) { errors++; } }
      }
      long slot = Math.round((ts - firstTimestamp) / (double) CinemaMath.frameDurationNs(fps));
      if (slot <= lastSlot) return;
      ByteBuffer[] buf = free.poll();
      if (buf == null) { dropped++; return; } // encoders behind: the next frame fills this slot
      int copies = (int) Math.min(12, slot - lastSlot);
      lastSlot = slot;
      Image.Plane[] p = img.getPlanes();
      ProRes.nativeCopyPlane(p[0].getBuffer(), p[0].getRowStride(), p[0].getPixelStride(), width, height, buf[0], width);
      ProRes.nativeCopyPlane(p[1].getBuffer(), p[1].getRowStride(), p[1].getPixelStride(), cw, ch, buf[1], cw);
      ProRes.nativeCopyPlane(p[2].getBuffer(), p[2].getRowStride(), p[2].getPixelStride(), cw, ch, buf[2], cw);
      long seq = sequence++;
      try { pool.execute(() -> encode(buf, seq, copies)); }
      catch (RuntimeException e) { free.offer(buf); throw e; }
    } catch (RuntimeException e) {
      Log.w(TAG, "frame", e);
    } finally {
      img.close();
    }
  }

  private void encode(ByteBuffer[] in, long seq, int copies) {
    byte[] frame = null;
    try {
      ByteBuffer out = output.get();
      if (out == null) { out = ByteBuffer.allocateDirect(ProRes.outputCapacity(width, height)); output.set(out); }
      ByteBuffer hint = hints.get();
      if (hint == null) { hint = ByteBuffer.allocateDirect(ProRes.nativeSlices(width, height)); hints.set(hint); }
      float[] stats = new float[2];
      long t0 = System.nanoTime();
      long n = ProRes.nativeEncode(in[0], width, in[1], in[2], cw, width, height, profile, primaries, transfer, matrix, out, stats, hint);
      if (n == -2) { // a pathological frame overflowed the normal buffer: retry once with the worst-case size
        out = ByteBuffer.allocateDirect(ProRes.worstCaseCapacity(width, height)); output.set(out);
        n = ProRes.nativeEncode(in[0], width, in[1], in[2], cw, width, height, profile, primaries, transfer, matrix, out, stats, hint);
      }
      encodeNanos.addAndGet(System.nanoTime() - t0);
      if (n > 0) {
        frame = new byte[(int) n];
        out.position(0); out.get(frame); out.clear();
        encoded.incrementAndGet();
        lastQ = stats[0];
      } else { errors++; Log.w(TAG, "encode returned " + n); }
    } catch (RuntimeException e) {
      errors++; Log.w(TAG, "encode", e);
    } finally {
      free.offer(in);
    }
    // Write in capture order: frames finish out of order on parallel threads.
    synchronized (writeLock) {
      ready.put(seq, new Object[] {frame, copies});
      while (!ready.isEmpty() && ready.firstKey() == nextToWrite) {
        Object[] e = ready.pollFirstEntry().getValue();
        nextToWrite++;
        byte[] data = (byte[]) e[0];
        if (data == null || mov == null) continue;
        try { mov.writeVideo(ByteBuffer.wrap(data), (Integer) e[1]); } catch (IOException ex) { errors++; Log.w(TAG, "write", ex); }
      }
    }
  }

  // ------------------------------------------------------------------------------------------------ audio

  @Override public void onPcm(float[] interleaved, int frames, int channels, long ptsNs) {
    if (!recording || aligner == null) return;
    synchronized (writeLock) {
      try { if (mov != null) aligner.add(interleaved, frames, ptsNs, mov); } catch (IOException e) { errors++; }
    }
  }
}
