package ug.audiocity.cinema;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.util.Log;
import android.util.LruCache;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Timeline pictures and sound shapes, made in the background and cached: small video thumbnails (on a half-second
 * grid of source time) and audio peak envelopes (100 peaks per second). The UI asks with get...(); a miss queues the
 * work once and calls `changed` when it is ready, so the timeline never waits.
 */
final class MediaCache {
  static final long THUMB_GRID_US = 500_000;
  static final int PEAKS_PER_SECOND = 100;

  private final Context ctx;
  private final Runnable changed;
  private final ExecutorService thumbs = Executors.newSingleThreadExecutor(), waves = Executors.newSingleThreadExecutor();
  private final LruCache<String, Bitmap> pictures = new LruCache<String, Bitmap>(24 * 1024 * 1024) {
    @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
  };
  private final Map<String, float[]> peaks = new HashMap<>();
  private final Set<String> pending = new HashSet<>();
  private final Map<String, MediaMetadataRetriever> retrievers = new HashMap<>(); // thumbs thread only
  private volatile boolean closed;

  MediaCache(Context ctx, Runnable changed) { this.ctx = ctx; this.changed = changed; }

  static long gridTime(long sourceUs) { return Math.max(0, Math.round(sourceUs / (double) THUMB_GRID_US) * THUMB_GRID_US); }

  /** The thumbnail nearest to source time us, or null (then it is being made). */
  Bitmap thumbnail(String uri, long us) {
    if (uri == null) return null;
    final long t = gridTime(us);
    final String key = uri + "@" + t;
    Bitmap b = pictures.get(key);
    if (b != null) return b;
    synchronized (pending) { if (!pending.add(key)) return null; }
    thumbs.execute(() -> {
      if (closed) return;
      try {
        MediaMetadataRetriever r = retrievers.get(uri);
        if (r == null) {
          if (retrievers.size() >= 4) { for (MediaMetadataRetriever o : retrievers.values()) try { o.release(); } catch (Exception ignored) {} retrievers.clear(); }
          r = new MediaMetadataRetriever(); r.setDataSource(ctx, Uri.parse(uri)); retrievers.put(uri, r);
        }
        Bitmap f = r.getScaledFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 192, 108);
        if (f != null) { pictures.put(key, f); changed.run(); }
      } catch (Exception e) { Log.w("CityCinema", "thumb " + uri, e); }
      finally { synchronized (pending) { pending.remove(key); } }
    });
    return null;
  }

  /** Peak envelope of a file's sound (PEAKS_PER_SECOND values 0..1 from its start), or null while it is measured. */
  float[] peaks(String uri, long durationUs) {
    if (uri == null || durationUs <= 0) return null;
    synchronized (peaks) { float[] p = peaks.get(uri); if (p != null) return p; }
    synchronized (pending) { if (!pending.add("wave:" + uri)) return null; }
    waves.execute(() -> {
      if (closed) return;
      float[] out = measure(uri, Math.min(durationUs, 20 * 60_000_000L));
      synchronized (peaks) { peaks.put(uri, out); }
      synchronized (pending) { pending.remove("wave:" + uri); }
      changed.run();
    });
    return null;
  }

  private float[] measure(String uri, long durationUs) {
    int n = (int) (durationUs * PEAKS_PER_SECOND / 1_000_000L) + 1;
    float[] out = new float[n];
    int perPeak = 48_000 / PEAKS_PER_SECOND;
    float[] buf = new float[perPeak * 2];
    try {
      EditEngine.AudioSource s = new EditEngine.AudioSource(ctx, uri, 0);
      try {
        for (int i = 0; i < n && !closed; i++) {
          s.read(buf, perPeak);
          out[i] = envelopeOf(buf, perPeak);
          if (s.finished()) break;
        }
      } finally { s.release(); }
    } catch (Exception e) { Log.w("CityCinema", "wave " + uri, e); }
    return out;
  }

  /** Peak of interleaved stereo samples, 0..1. */
  static float envelopeOf(float[] stereo, int frames) {
    float m = 0;
    for (int i = 0; i < frames * 2; i++) { float a = Math.abs(stereo[i]); if (a > m) m = a; }
    return Math.min(1f, m);
  }

  void close() {
    closed = true;
    thumbs.execute(() -> { for (MediaMetadataRetriever r : retrievers.values()) try { r.release(); } catch (Exception ignored) {} retrievers.clear(); });
    thumbs.shutdown(); waves.shutdown();
  }
}
