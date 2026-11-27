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
  private final ThreadLocal<ByteBuffer> output = new ThreadLocal<>();
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
    int cores = Runtime.getRuntime().availableProcessors();
    encoders = Math.max(2, Math.min(8, cores - 1));
    long frameBytes = (long) width * height * 3 / 2;
    long budget = (long) (Runtime.getRuntime().maxMemory() * 0.25);
    poolSize = (int) Math.max(encoders + 1, Math.min(encoders * 2 + 2, budget / Math.max(1, frameBytes)));
    free = new ArrayBlockingQueue<>(poolSize);
    for (int i = 0; i < poolSize; i++)
      free.add(new ByteBuffer[] {ByteBuffer.allocateDirect(width * height), ByteBuffer.allocateDirect(cw * ch), ByteBuffer.allocateDirect(cw * ch)});
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
      float[] stats = new float[2];
      long t0 = System.nanoTime();
      long n = ProRes.nativeEncode(in[0], width, in[1], in[2], cw, width, height, profile, primaries, transfer, matrix, out, stats);
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
