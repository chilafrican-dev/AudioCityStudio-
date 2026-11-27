package ug.audiocity.cinema;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;

/**
 * Monitoring overlay drawn over the live picture: zebras, focus peaking, false colour, luma/RGB histogram,
 * luma waveform or RGB parade, frame guides with matte, thirds grid, centre mark and an electronic level.
 * Image analysis runs off the UI thread on small frames grabbed from the preview.
 */
class CinemaScopes extends View {
  // ------------------------------------------------------------------ settings (set from the UI thread)
  boolean zebra, peaking, falseColor, grid = true, center, level = true;
  int scopeMode = 1; // 0 off, 1 histogram, 2 waveform, 3 RGB parade
  int zebraIre = 95, peakColor = 0xffff3b30, guide = 0;
  /** In the log modes: the 8-bit code where the phone clips (e.g. ~191 for S-Log3 with 2 stops of headroom), so
   * zebras, false colour and the clip warning mark real clipping. 0 = normal video (clip at 255). */
  volatile int clipLuma;
  float peakSensitivity = 0.5f; // 0 = only the sharpest edges, 1 = very sensitive
  float roll; // degrees from level, from the accelerometer
  final RectF frame = new RectF();
  float insetTop, insetBottom;

  // ------------------------------------------------------------------ analysis results
  private final Object lock = new Object();
  private Bitmap mask, maskBack;
  private Bitmap scope, scopeBack;
  private final int[] histR = new int[64], histG = new int[64], histB = new int[64], histL = new int[64];
  private int[] pixels, luma, maskPixels;
  private int phase;
  private float clippedPercent, crushedPercent;

  static final int SCOPE_W = 240, SCOPE_H = 100;

  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG), crisp = new Paint(), text = new Paint(Paint.ANTI_ALIAS_FLAG), filtered = new Paint(Paint.FILTER_BITMAP_FLAG);
  private final float density;

  CinemaScopes(Context c) {
    super(c);
    density = c.getResources().getDisplayMetrics().density;
    text.setColor(Color.WHITE); text.setTextSize(10 * density);
    crisp.setFilterBitmap(false);
  }

  // ------------------------------------------------------------------ analysis (background thread)

  /** True when anything needs per-frame analysis. */
  boolean needsFrames() { return zebra || peaking || falseColor || scopeMode != 0; }

  void analyze(Bitmap src) {
    int w = src.getWidth(), h = src.getHeight(), n = w * h;
    if (pixels == null || pixels.length != n) { pixels = new int[n]; luma = new int[n]; maskPixels = new int[n]; }
    src.getPixels(pixels, 0, w, 0, 0, w, h);
    int[] hr = new int[64], hg = new int[64], hb = new int[64], hl = new int[64];
    int clipped = 0, crushed = 0;
    for (int i = 0; i < n; i++) {
      int p = pixels[i], r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
      int y = CinemaMath.luma(r, g, b);
      luma[i] = y;
      hr[r >> 2]++; hg[g >> 2]++; hb[b >> 2]++; hl[y >> 2]++;
      if (y >= (clipLuma > 0 ? clipLuma - 3 : 250)) clipped++; else if (y <= 4) crushed++;
    }
    boolean wantMask = zebra || peaking || falseColor;
    if (wantMask) buildMask(w, h);
    int[] scopePixels = scopeMode >= 2 ? buildScope(w, h, scopeMode == 3) : null;
    synchronized (lock) {
      System.arraycopy(hr, 0, histR, 0, 64); System.arraycopy(hg, 0, histG, 0, 64); System.arraycopy(hb, 0, histB, 0, 64); System.arraycopy(hl, 0, histL, 0, 64);
      clippedPercent = 100f * clipped / n; crushedPercent = 100f * crushed / n;
      if (wantMask) {
        if (maskBack == null || maskBack.getWidth() != w || maskBack.getHeight() != h) maskBack = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        maskBack.setPixels(maskPixels, 0, w, 0, 0, w, h);
        Bitmap t = mask; mask = maskBack; maskBack = t;
      } else mask = null;
      if (scopePixels != null) {
        if (scopeBack == null) scopeBack = Bitmap.createBitmap(SCOPE_W, SCOPE_H, Bitmap.Config.ARGB_8888);
        scopeBack.setPixels(scopePixels, 0, SCOPE_W, 0, 0, SCOPE_W, SCOPE_H);
        Bitmap t = scope; scope = scopeBack; scopeBack = t;
      } else scope = null;
    }
    postInvalidateOnAnimation();
  }

  private void buildMask(int w, int h) {
    phase = (phase + 1) & 7;
    int clip = clipLuma;
    // In log, zebras at 90-100 are relative to the clipping code (95 = 95% of the way to clipping); lower levels stay absolute.
    int zebraLuma = clip > 0 && zebraIre >= 90 ? clip - (int) Math.round((100 - zebraIre) / 100.0 * clip) - 2 : (int) Math.round(zebraIre * 255 / 100.0);
    // Peaking threshold: gradient magnitude, relative to the frame's own contrast so it works in low light too.
    long sum = 0; int count = 0;
    for (int y = 1; y < h - 1; y += 2) for (int x = 1; x < w - 1; x += 2) { int i = y * w + x; sum += Math.abs(luma[i + 1] - luma[i - 1]) + Math.abs(luma[i + w] - luma[i - w]); count++; }
    float mean = count == 0 ? 8 : sum / (float) count;
    float threshold = Math.max(18, mean * (2.5f + (1 - peakSensitivity) * 6f));
    for (int y = 0; y < h; y++) {
      for (int x = 0; x < w; x++) {
        int i = y * w + x, v = luma[i], out = 0;
        if (falseColor) {
          int fc = clip > 0 && v >= clip - 3 ? 0xffe5322d : CinemaMath.falseColor(CinemaMath.ire(v)); // log: red where it really clips
          out = fc != 0 ? fc : 0xff000000 | (v << 16) | (v << 8) | v;
        }
        if (zebra && v >= zebraLuma && (((x + y + phase) >> 2) & 1) == 0) out = 0xe6ffffff;
        if (peaking && x > 0 && y > 0 && x < w - 1 && y < h - 1) {
          int gx = (luma[i + 1 - w] + 2 * luma[i + 1] + luma[i + 1 + w]) - (luma[i - 1 - w] + 2 * luma[i - 1] + luma[i - 1 + w]);
          int gy = (luma[i + w - 1] + 2 * luma[i + w] + luma[i + w + 1]) - (luma[i - w - 1] + 2 * luma[i - w] + luma[i - w + 1]);
          if ((Math.abs(gx) + Math.abs(gy)) / 4f > threshold) out = peakColor;
        }
        maskPixels[i] = out;
      }
    }
  }

  private int[] buildScope(int w, int h, boolean parade) {
    int[] counts = new int[SCOPE_W * SCOPE_H * (parade ? 1 : 1)];
    int[] channelOf = parade ? new int[SCOPE_W * SCOPE_H] : null;
    int step = Math.max(1, h / 90);
    for (int y = 0; y < h; y += step) {
      for (int x = 0; x < w; x++) {
        int p = pixels[y * w + x];
        if (!parade) {
          int col = x * SCOPE_W / w, row = SCOPE_H - 1 - luma[y * w + x] * (SCOPE_H - 1) / 255;
          counts[row * SCOPE_W + col]++;
        } else {
          int third = SCOPE_W / 3, col = x * third / w;
          for (int c = 0; c < 3; c++) {
            int v = (p >> (16 - 8 * c)) & 255, row = SCOPE_H - 1 - v * (SCOPE_H - 1) / 255, idx = row * SCOPE_W + c * third + col;
            counts[idx]++; channelOf[idx] = c;
          }
        }
      }
    }
    int[] img = new int[SCOPE_W * SCOPE_H];
    float scale = 255f / Math.max(1, (float) Math.log1p(h / (float) step * w / (float) SCOPE_W * 0.25f));
    for (int i = 0; i < img.length; i++) {
      int c = counts[i];
      if (c == 0) { img[i] = 0; continue; }
      int a = Math.min(255, 60 + (int) (Math.log1p(c) * scale));
      if (!parade) img[i] = (a << 24) | 0x00b8ffb0;
      else img[i] = (a << 24) | (channelOf[i] == 0 ? 0x00ff5a5a : channelOf[i] == 1 ? 0x0062ff7a : 0x005aa0ff);
    }
    return img;
  }

  // ------------------------------------------------------------------ drawing (UI thread)

  @Override protected void onDraw(Canvas c) {
    if (frame.isEmpty()) return;
    synchronized (lock) {
      if (mask != null && (zebra || peaking || falseColor)) c.drawBitmap(mask, null, frame, falseColor ? filtered : crisp);
    }
    drawGuides(c);
    if (grid) drawGrid(c);
    if (center) drawCenter(c);
    if (level) drawLevel(c);
    drawScope(c);
  }

  private void drawGuides(Canvas c) {
    if (guide <= 0) return;
    float[] r = CinemaMath.guideRect(frame.width(), frame.height(), CinemaMath.GUIDE_RATIOS[guide]);
    RectF g = new RectF(frame.left + r[0], frame.top + r[1], frame.left + r[2], frame.top + r[3]);
    paint.setStyle(Paint.Style.FILL); paint.setColor(0xa0000000);
    c.drawRect(frame.left, frame.top, frame.right, g.top, paint);
    c.drawRect(frame.left, g.bottom, frame.right, frame.bottom, paint);
    c.drawRect(frame.left, g.top, g.left, g.bottom, paint);
    c.drawRect(g.right, g.top, frame.right, g.bottom, paint);
    paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(density); paint.setColor(0xccffffff);
    c.drawRect(g, paint);
  }

  private RectF active() {
    float[] r = CinemaMath.guideRect(frame.width(), frame.height(), guide > 0 ? CinemaMath.GUIDE_RATIOS[guide] : 0);
    return new RectF(frame.left + r[0], frame.top + r[1], frame.left + r[2], frame.top + r[3]);
  }

  private void drawGrid(Canvas c) {
    RectF a = active();
    paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(density * 0.75f); paint.setColor(0x66ffffff);
    for (int i = 1; i < 3; i++) {
      float x = a.left + a.width() * i / 3f, y = a.top + a.height() * i / 3f;
      c.drawLine(x, a.top, x, a.bottom, paint); c.drawLine(a.left, y, a.right, y, paint);
    }
  }

  private void drawCenter(Canvas c) {
    float cx = frame.centerX(), cy = frame.centerY(), s = 12 * density;
    paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(1.5f * density); paint.setColor(0xccffffff);
    c.drawLine(cx - s, cy, cx + s, cy, paint); c.drawLine(cx, cy - s, cx, cy + s, paint);
  }

  private void drawLevel(Canvas c) {
    float cx = frame.centerX(), cy = frame.centerY(), len = Math.min(frame.width(), frame.height()) * 0.22f;
    boolean ok = Math.abs(roll) < 0.6f;
    double rad = Math.toRadians(-roll);
    float dx = (float) Math.cos(rad) * len, dy = (float) Math.sin(rad) * len;
    paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(2 * density); paint.setColor(ok ? 0xff53e07a : 0xccffffff);
    c.drawLine(cx - dx, cy - dy, cx - dx * 0.3f, cy - dy * 0.3f, paint);
    c.drawLine(cx + dx * 0.3f, cy + dy * 0.3f, cx + dx, cy + dy, paint);
    if (!ok) {
      paint.setColor(0x88ffffff); paint.setStrokeWidth(density);
      c.drawLine(cx - len, cy, cx - len * 0.8f, cy, paint); c.drawLine(cx + len * 0.8f, cy, cx + len, cy, paint);
    }
  }

  private void drawScope(Canvas c) {
    if (scopeMode == 0) return;
    float w = 150 * density, h = 64 * density, pad = 8 * density;
    RectF box = new RectF(frame.left + pad, frame.bottom - insetBottom - pad - h, frame.left + pad + w, frame.bottom - insetBottom - pad);
    if (box.top < frame.top + insetTop) box.offsetTo(box.left, frame.top + insetTop + pad);
    paint.setStyle(Paint.Style.FILL); paint.setColor(0xb0101214);
    c.drawRoundRect(box, 6 * density, 6 * density, paint);
    RectF inner = new RectF(box.left + 4 * density, box.top + 4 * density, box.right - 4 * density, box.bottom - 4 * density);
    paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(density * 0.5f); paint.setColor(0x33ffffff);
    for (int i = 1; i < 4; i++) { float y = inner.top + inner.height() * i / 4f; c.drawLine(inner.left, y, inner.right, y, paint); }
    synchronized (lock) {
      if (scopeMode == 1) {
        drawHistogram(c, inner, histR, 0x70ff4040); drawHistogram(c, inner, histG, 0x7040ff60); drawHistogram(c, inner, histB, 0x704080ff); drawHistogram(c, inner, histL, 0x90ffffff);
        text.setColor(clippedPercent > 1 ? 0xffff5a50 : 0xb0ffffff);
        if (clippedPercent > 0.2f) c.drawText(String.format(java.util.Locale.US, "clip %.1f%%", clippedPercent), inner.right - 52 * density, inner.top + 10 * density, text);
        text.setColor(crushedPercent > 1 ? 0xff8fa0ff : 0xb0ffffff);
        if (crushedPercent > 0.2f) c.drawText(String.format(java.util.Locale.US, "crush %.1f%%", crushedPercent), inner.left + 2 * density, inner.top + 10 * density, text);
      } else if (scope != null) c.drawBitmap(scope, null, inner, filtered);
    }
    text.setColor(0x99ffffff);
    c.drawText(scopeMode == 1 ? "HIST" : scopeMode == 2 ? "WAVE" : "PARADE", box.left + 6 * density, box.bottom - 5 * density, text);
  }

  private void drawHistogram(Canvas c, RectF r, int[] hist, int color) {
    int max = 1; for (int i = 1; i < 63; i++) max = Math.max(max, hist[i]);
    Path p = new Path(); p.moveTo(r.left, r.bottom);
    for (int i = 0; i < 64; i++) {
      float x = r.left + r.width() * i / 63f, y = r.bottom - r.height() * Math.min(1f, hist[i] / (float) max);
      p.lineTo(x, y);
    }
    p.lineTo(r.right, r.bottom); p.close();
    paint.setStyle(Paint.Style.FILL); paint.setColor(color);
    c.drawPath(p, paint);
  }

  /** Small helper for tap-to-focus: maps a view point inside the frame to normalised 0..1 picture coordinates. */
  float[] normalised(float x, float y) {
    if (!frame.contains(x, y)) return null;
    return new float[] {(x - frame.left) / frame.width(), (y - frame.top) / frame.height()};
  }

  /**
   * Picture coordinates (as displayed, 0..1) to a metering rectangle in sensor coordinates. relativeRotation is
   * the rotation between the sensor and the display (0 or 180 in landscape); front cameras are mirrored.
   */
  static Rect toSensor(float nx, float ny, Rect active, int relativeRotation, boolean front, float size) {
    float sx = nx, sy = ny;
    if (front) sx = 1 - sx;
    if (relativeRotation == 180) { sx = 1 - sx; sy = 1 - sy; }
    int cx = active.left + Math.round(sx * active.width()), cy = active.top + Math.round(sy * active.height());
    int half = Math.round(Math.min(active.width(), active.height()) * size / 2);
    return new Rect(Math.max(active.left, cx - half), Math.max(active.top, cy - half), Math.min(active.right, cx + half), Math.min(active.bottom, cy + half));
  }
}
