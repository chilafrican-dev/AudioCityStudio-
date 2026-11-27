package ug.audiocity.cinema;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES30;
import android.opengl.GLUtils;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;

import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * The editor's engine: a GPU compositor (log LUT, colour grade, dissolve opacity, dips, rotation, letterbox, titles),
 * per-clip frame sources (decoder to texture), a streaming audio mixer (clips with crossfades + music, resampled to
 * 48 kHz stereo) and the exporter that renders a project to one MP4.
 */
final class EditEngine {
  static final String TAG = "CityCinemaEdit";
  private EditEngine() {}

  // ================================================================================================ compositor

  /** Draws layers and titles with the current GL context (preview GLSurfaceView or the exporter's EGL surface). */
  static final class Compositor {
    private int clipProgram, titleProgram;
    private final Map<String, Integer> luts = new HashMap<>();
    private final Map<EditModel.Title, int[]> titleTextures = new HashMap<>();
    private final FloatBuffer quad = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    private final float[] identity = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};

    void init() {
      clipProgram = program(VS,
          "#version 300 es\n#extension GL_OES_EGL_image_external_essl3 : require\nprecision highp float;\n"
              + "uniform samplerExternalOES uTex; uniform mediump sampler3D uLut; uniform float uLutOn, uAlpha, uBright;\n"
              + "uniform mediump sampler3D uLut2; uniform float uLut2On, uLut2Strength; uniform vec3 uDomMin, uDomMax; uniform float uL2Scale, uL2Off;\n"
              + "uniform float uExposure, uContrast, uSat, uTemp, uTint, uShadows, uHighlights, uFilm, uFilmStrength;\nin vec2 vTex; out vec4 o;\n"
              + "vec3 film(vec3 c, float look, float k) {\n"
              + "  float y = dot(c, vec3(0.2126, 0.7152, 0.0722)); vec3 f = c;\n"
              + "  if (look == 1.0) f = vec3(c.r + 0.10 * y - 0.03, c.g + 0.02 * y, c.b - 0.10 * y + 0.07);\n"
              + "  else if (look == 2.0) f = vec3(c.r * 1.06 + 0.02, c.g + 0.01, c.b * 0.88);\n"
              + "  else if (look == 3.0) f = vec3(c.r * 0.90, c.g * 0.98, c.b * 1.08 + 0.02);\n"
              + "  else if (look == 4.0) f = (c * 0.6 + vec3(y) * 0.4 - 0.5) * 1.25 + 0.5;\n"
              + "  else if (look == 5.0) f = vec3((y - 0.5) * 1.1 + 0.5);\n"
              + "  return clamp(c + (clamp(f, 0.0, 1.0) - c) * k, 0.0, 1.0);\n}\n"
              + "void main() {\n"
              + "  vec3 c = texture(uTex, vTex).rgb;\n"
              + "  if (uLutOn > 0.5) c = texture(uLut, c * (32.0 / 33.0) + 0.5 / 33.0).rgb;\n"
              + "  if (uLut2On > 0.5) { vec3 t = clamp((c - uDomMin) / max(uDomMax - uDomMin, vec3(1e-6)), 0.0, 1.0);\n"
              + "    c = mix(c, texture(uLut2, t * uL2Scale + uL2Off).rgb, uLut2Strength); }\n"
              + "  vec3 wb = vec3(1.0 + 0.12 * uTemp, 1.0 - 0.08 * uTint, 1.0 - 0.12 * uTemp);\n"
              + "  vec3 lin = pow(max(c, 0.0), vec3(2.2)) * exp2(uExposure) * wb;\n"
              + "  c = pow(min(lin, vec3(1.0)), vec3(1.0 / 2.2));\n"
              + "  c += uShadows * 0.35 * (1.0 - c) * (1.0 - c) * c * 2.0;\n"
              + "  c -= uHighlights * 0.35 * c * c * (1.0 - c) * 2.0;\n"
              + "  c = clamp((c - 0.5) * (1.0 + uContrast) + 0.5, 0.0, 1.0);\n"
              + "  if (uFilm > 0.5) c = film(c, uFilm, uFilmStrength);\n"
              + "  float y = dot(c, vec3(0.2126, 0.7152, 0.0722));\n"
              + "  c = clamp(vec3(y) + (c - vec3(y)) * (1.0 + uSat), 0.0, 1.0);\n"
              + "  o = vec4(c * uBright, uAlpha);\n}\n");
      titleProgram = program(VS,
          "#version 300 es\nprecision mediump float;\nuniform sampler2D uTex; uniform float uAlpha;\nin vec2 vTex; out vec4 o;\n"
              + "void main() { vec4 c = texture(uTex, vec2(vTex.x, 1.0 - vTex.y)); o = vec4(c.rgb, c.a * uAlpha); }\n");
    }

    void clear() {
      GLES30.glClearColor(0, 0, 0, 1);
      GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);
      GLES30.glEnable(GLES30.GL_BLEND);
      GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA);
    }

    /** Draws one clip frame, rotated and letterboxed into the output, through its LUT and grade. */
    void drawClip(FrameSource src, EditModel.Clip c, double alpha, double brightness, int outW, int outH) {
      if (clipProgram == 0 || !src.hasFrame()) return;
      int dw = c.rotation % 180 == 0 ? Math.max(1, c.width) : Math.max(1, c.height), dh = c.rotation % 180 == 0 ? Math.max(1, c.height) : Math.max(1, c.width);
      double scale = Math.min(outW / (double) dw, outH / (double) dh);
      float sx = (float) (dw * scale / outW), sy = (float) (dh * scale / outH);
      // texture corners for BL, BR, TL, TR of the displayed picture, by rotation (clockwise)
      float[][] uv = c.rotation == 90 ? new float[][] {{1, 0}, {1, 1}, {0, 0}, {0, 1}} : c.rotation == 180 ? new float[][] {{1, 1}, {0, 1}, {1, 0}, {0, 0}}
          : c.rotation == 270 ? new float[][] {{0, 1}, {0, 0}, {1, 1}, {1, 0}} : new float[][] {{0, 0}, {1, 0}, {0, 1}, {1, 1}};
      quad.clear();
      quad.put(new float[] {-sx, -sy, uv[0][0], uv[0][1], sx, -sy, uv[1][0], uv[1][1], -sx, sy, uv[2][0], uv[2][1], sx, sy, uv[3][0], uv[3][1]});
      GLES30.glUseProgram(clipProgram);
      bindQuad(clipProgram, src.transform());
      GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
      GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, src.texture());
      GLES30.glUniform1i(loc(clipProgram, "uTex"), 0);
      boolean log = CinemaMath.isLogLook(c.look);
      // The LUT sampler always gets its own texture unit: left at its default (unit 0, where the video is) two
      // samplers of different types would share a unit and GL refuses to draw (a black picture).
      GLES30.glActiveTexture(GLES30.GL_TEXTURE1);
      GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, log ? lut(c.look) : lut("City Log"));
      GLES30.glUniform1i(loc(clipProgram, "uLut"), 1);
      // user LUT on unit 2 (always bound, for the same reason)
      CubeLut user = c.lut != null && !c.lut.isEmpty() ? userLut(c.lut) : null;
      GLES30.glActiveTexture(GLES30.GL_TEXTURE2);
      GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, user != null ? userLutTexture(c.lut, user) : lut("City Log"));
      GLES30.glUniform1i(loc(clipProgram, "uLut2"), 2);
      GLES30.glUniform1f(loc(clipProgram, "uLut2On"), user != null ? 1 : 0);
      if (user != null) {
        GLES30.glUniform1f(loc(clipProgram, "uLut2Strength"), (float) Math.max(0, Math.min(1, c.lutStrength)));
        GLES30.glUniform3f(loc(clipProgram, "uDomMin"), user.domainMin[0], user.domainMin[1], user.domainMin[2]);
        GLES30.glUniform3f(loc(clipProgram, "uDomMax"), user.domainMax[0], user.domainMax[1], user.domainMax[2]);
        GLES30.glUniform1f(loc(clipProgram, "uL2Scale"), (user.size - 1f) / user.size);
        GLES30.glUniform1f(loc(clipProgram, "uL2Off"), 0.5f / user.size);
      }
      EditModel.Grade g = c.grade;
      GLES30.glUniform1f(loc(clipProgram, "uLutOn"), log ? 1 : 0);
      GLES30.glUniform1f(loc(clipProgram, "uAlpha"), (float) alpha);
      GLES30.glUniform1f(loc(clipProgram, "uBright"), (float) brightness);
      GLES30.glUniform1f(loc(clipProgram, "uExposure"), (float) g.exposure);
      GLES30.glUniform1f(loc(clipProgram, "uContrast"), (float) g.contrast);
      GLES30.glUniform1f(loc(clipProgram, "uSat"), (float) g.saturation);
      GLES30.glUniform1f(loc(clipProgram, "uTemp"), (float) g.temperature);
      GLES30.glUniform1f(loc(clipProgram, "uTint"), (float) g.tint);
      GLES30.glUniform1f(loc(clipProgram, "uShadows"), (float) g.shadows);
      GLES30.glUniform1f(loc(clipProgram, "uHighlights"), (float) g.highlights);
      GLES30.glUniform1f(loc(clipProgram, "uFilm"), g.film);
      GLES30.glUniform1f(loc(clipProgram, "uFilmStrength"), (float) g.filmStrength);
      GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);
    }

    /** Draws a title over the whole frame (its bitmap is the size of the output). */
    void drawTitle(EditModel.Title t, double alpha, int outW, int outH) {
      if (titleProgram == 0 || alpha <= 0) return;
      int[] tex = titleTextures.get(t);
      String key = t.text + "|" + t.place + "|" + t.color + "|" + t.box + "|" + t.size + "|" + outW + "x" + outH;
      if (tex == null || tex[1] != key.hashCode()) {
        if (tex != null) GLES30.glDeleteTextures(1, tex, 0);
        tex = new int[] {0, key.hashCode()};
        GLES30.glGenTextures(1, tex, 0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[0]);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        Bitmap b = titleBitmap(t, Math.min(outW, 1920), Math.min(outH, 1920 * outH / Math.max(1, outW)));
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, b, 0);
        b.recycle();
        titleTextures.put(t, tex);
      }
      quad.clear();
      quad.put(new float[] {-1, -1, 0, 0, 1, -1, 1, 0, -1, 1, 0, 1, 1, 1, 1, 1});
      GLES30.glUseProgram(titleProgram);
      bindQuad(titleProgram, identity);
      GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
      GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[0]);
      GLES30.glUniform1i(loc(titleProgram, "uTex"), 0);
      GLES30.glUniform1f(loc(titleProgram, "uAlpha"), (float) alpha);
      GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);
    }

    /** Renders a title's text into a transparent bitmap the size of the frame. */
    static Bitmap titleBitmap(EditModel.Title t, int w, int h) {
      Bitmap b = Bitmap.createBitmap(Math.max(2, w), Math.max(2, h), Bitmap.Config.ARGB_8888);
      Canvas c = new Canvas(b);
      Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
      p.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
      p.setTextSize((float) (h / 14.0 * t.size));
      p.setColor(t.color);
      p.setShadowLayer(h / 200f, 0, h / 400f, 0xaa000000);
      String[] lines = t.text.split("\n");
      float lineH = p.getTextSize() * 1.25f, blockH = lineH * lines.length;
      float top = t.place == 0 ? (h - blockH) / 2 : t.place == 2 ? h * 0.08f : h * 0.78f - blockH;
      float maxW = 0;
      for (String l : lines) maxW = Math.max(maxW, p.measureText(l));
      float left = t.place == 1 ? w * 0.07f : (w - maxW) / 2;
      if (t.box) {
        Paint box = new Paint(Paint.ANTI_ALIAS_FLAG);
        box.setColor(0x99000000);
        float pad = p.getTextSize() * 0.4f;
        c.drawRoundRect(new RectF(left - pad, top - pad * 0.6f, left + maxW + pad, top + blockH + pad * 0.2f), pad / 2, pad / 2, box);
      }
      for (int i = 0; i < lines.length; i++) c.drawText(lines[i], left, top + lineH * i + p.getTextSize(), p);
      return b;
    }

    // ---- user .cube LUTs (parsed once, uploaded as 16-bit float 3D textures)
    private java.io.File lutDir;
    private final Map<String, CubeLut> userLuts = new HashMap<>();
    private final Map<String, Integer> userLutTextures = new HashMap<>();

    private android.content.res.AssetManager assets;

    void setLutDir(java.io.File dir) { lutDir = dir; }

    void setAssets(android.content.res.AssetManager a) { assets = a; }

    private CubeLut userLut(String name) {
      if (userLuts.containsKey(name)) return userLuts.get(name);
      CubeLut l = null;
      if (CinematicLuts.isBuiltin(name)) l = CinematicLuts.build(CinematicLuts.nameOf(name));
      else if (LutPack.isPack(name) && assets != null) {
        try (java.io.Reader r = new java.io.InputStreamReader(assets.open(LutPack.assetPath(name)), java.nio.charset.StandardCharsets.UTF_8)) { l = CubeLut.parse(r); }
        catch (IOException | RuntimeException e) { Log.w(TAG, "LUT " + name, e); }
      }
      else if (lutDir != null) {
        try (java.io.Reader r = new java.io.InputStreamReader(new java.io.FileInputStream(new java.io.File(lutDir, name)), java.nio.charset.StandardCharsets.UTF_8)) { l = CubeLut.parse(r); }
        catch (IOException | RuntimeException e) { Log.w(TAG, "LUT " + name, e); }
      }
      userLuts.put(name, l);
      return l;
    }

    private int userLutTexture(String name, CubeLut l) {
      Integer t = userLutTextures.get(name);
      if (t != null) return t;
      int[] id = new int[1];
      GLES30.glGenTextures(1, id, 0);
      ByteBuffer data = ByteBuffer.allocateDirect(l.data.length * 2).order(ByteOrder.nativeOrder());
      for (float v : l.data) data.putShort(CubeLut.toHalf(v));
      data.flip();
      GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, id[0]);
      GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
      GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
      for (int p : new int[] {GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_TEXTURE_WRAP_R}) GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, p, GLES30.GL_CLAMP_TO_EDGE);
      GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 2);
      GLES30.glTexImage3D(GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGB16F, l.size, l.size, l.size, 0, GLES30.GL_RGB, GLES30.GL_HALF_FLOAT, data);
      userLutTextures.put(name, id[0]);
      return id[0];
    }

    private int lut(String look) {
      Integer t = luts.get(look);
      if (t != null) return t;
      int[] id = new int[1];
      GLES30.glGenTextures(1, id, 0);
      final int n = 33;
      ByteBuffer data = ByteBuffer.allocateDirect(n * n * n * 3).order(ByteOrder.nativeOrder());
      double d = n - 1;
      for (int b = 0; b < n; b++) for (int g = 0; g < n; g++) for (int r = 0; r < n; r++) {
        double[] o = CinemaMath.logToRec709(look, r / d, g / d, b / d);
        for (int i = 0; i < 3; i++) data.put((byte) Math.round(CinemaMath.clamp01(o[i]) * 255));
      }
      data.flip();
      GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, id[0]);
      GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
      GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
      for (int p : new int[] {GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_TEXTURE_WRAP_R}) GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, p, GLES30.GL_CLAMP_TO_EDGE);
      GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1);
      GLES30.glTexImage3D(GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGB8, n, n, n, 0, GLES30.GL_RGB, GLES30.GL_UNSIGNED_BYTE, data);
      luts.put(look, id[0]);
      return id[0];
    }

    private void bindQuad(int prog, float[] st) {
      int aPos = GLES30.glGetAttribLocation(prog, "aPos"), aTex = GLES30.glGetAttribLocation(prog, "aTex");
      quad.position(0); GLES30.glVertexAttribPointer(aPos, 2, GLES30.GL_FLOAT, false, 16, quad); GLES30.glEnableVertexAttribArray(aPos);
      quad.position(2); GLES30.glVertexAttribPointer(aTex, 2, GLES30.GL_FLOAT, false, 16, quad); GLES30.glEnableVertexAttribArray(aTex);
      GLES30.glUniformMatrix4fv(loc(prog, "uSt"), 1, false, st, 0);
    }

    private static int loc(int prog, String name) { return GLES30.glGetUniformLocation(prog, name); }

    static final String VS = "#version 300 es\nin vec4 aPos; in vec4 aTex; uniform mat4 uSt; out vec2 vTex;\nvoid main() { gl_Position = aPos; vTex = (uSt * aTex).xy; }\n";

    static int program(String vs, String fs) {
      int v = shader(GLES30.GL_VERTEX_SHADER, vs), f = shader(GLES30.GL_FRAGMENT_SHADER, fs);
      if (v == 0 || f == 0) return 0;
      int p = GLES30.glCreateProgram();
      GLES30.glAttachShader(p, v); GLES30.glAttachShader(p, f); GLES30.glLinkProgram(p);
      int[] ok = new int[1];
      GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0);
      if (ok[0] == 0) { Log.w(TAG, "link: " + GLES30.glGetProgramInfoLog(p)); return 0; }
      return p;
    }

    static int shader(int type, String src) {
      int s = GLES30.glCreateShader(type);
      GLES30.glShaderSource(s, src); GLES30.glCompileShader(s);
      int[] ok = new int[1];
      GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0);
      if (ok[0] == 0) { Log.w(TAG, "shader: " + GLES30.glGetShaderInfoLog(s)); return 0; }
      return s;
    }
  }

  // ================================================================================================ frame source

  /** One clip's video: decodes into its own texture and can show the frame at any source time (seeking as needed). */
  static final class FrameSource implements SurfaceTexture.OnFrameAvailableListener {
    private final MediaExtractor ex = new MediaExtractor();
    private final MediaCodec decoder;
    private final SurfaceTexture st;
    private final Surface surface;
    private final int tex;
    private final HandlerThread thread = new HandlerThread("frames");
    private final Object lock = new Object();
    private boolean available, inputDone, eos, shown;
    private long shownUs = Long.MIN_VALUE, pendingPts;
    private int pending = -1;
    private final long frameUs;
    private final float[] matrix = new float[16];
    private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
    final String uri;

    /** Must be created on the GL thread (it creates the texture). */
    FrameSource(Context ctx, EditModel.Clip c) throws IOException {
      uri = c.uri;
      ex.setDataSource(ctx, Uri.parse(c.uri), null);
      MediaFormat f = null;
      for (int i = 0; i < ex.getTrackCount(); i++) {
        MediaFormat t = ex.getTrackFormat(i);
        String m = t.getString(MediaFormat.KEY_MIME);
        if (m != null && m.startsWith("video/")) { ex.selectTrack(i); f = t; break; }
      }
      if (f == null) throw new IOException("no video in " + c.name);
      frameUs = (long) (1e6 / Math.max(1, c.fps));
      int[] t = new int[1];
      GLES30.glGenTextures(1, t, 0);
      tex = t[0];
      GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex);
      GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
      GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
      thread.start();
      st = new SurfaceTexture(tex);
      st.setOnFrameAvailableListener(this, new Handler(thread.getLooper()));
      surface = new Surface(st);
      decoder = MediaCodec.createDecoderByType(f.getString(MediaFormat.KEY_MIME));
      f.setInteger(MediaFormat.KEY_ROTATION, 0); // rotation is applied by the compositor
      decoder.configure(f, surface, null, 0);
      decoder.start();
    }

    private boolean started;

    int texture() { return tex; }
    float[] transform() { return matrix; }
    boolean hasFrame() { return shown; }

    @Override public void onFrameAvailable(SurfaceTexture s) { synchronized (lock) { available = true; lock.notifyAll(); } }

    /** Shows the latest frame at or before sourceUs (seeks when moving backwards or far ahead). Call on the GL thread. */
    void frameAt(long sourceUs) {
      if (!started) { started = true; ex.seekTo(sourceUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC); }
      else if (shown && (sourceUs < shownUs - frameUs / 2 || sourceUs > shownUs + 3_000_000)) seek(sourceUs);
      long deadline = System.currentTimeMillis() + 4000;
      while (System.currentTimeMillis() < deadline) {
        if (pending >= 0) {
          if (pendingPts > sourceUs + frameUs / 2 && shown) return; // the next frame is in the future: keep showing this one
          boolean render = pendingPts >= sourceUs - 2 * frameUs || eos;
          decoder.releaseOutputBuffer(pending, render);
          pending = -1;
          if (render && awaitFrame()) { st.updateTexImage(); st.getTransformMatrix(matrix); shownUs = pendingPts; shown = true; }
          if (shown && shownUs >= sourceUs - frameUs / 2) return;
          continue;
        }
        if (eos) return;
        if (!inputDone) {
          int in = decoder.dequeueInputBuffer(2000);
          if (in >= 0) {
            int n = ex.readSampleData(decoder.getInputBuffer(in), 0);
            if (n < 0) { decoder.queueInputBuffer(in, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true; }
            else { decoder.queueInputBuffer(in, 0, n, ex.getSampleTime(), 0); ex.advance(); }
          }
        }
        int out = decoder.dequeueOutputBuffer(info, 5000);
        if (out >= 0) {
          if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) eos = true;
          if (info.size > 0 || !eos) { pending = out; pendingPts = info.presentationTimeUs; }
          else decoder.releaseOutputBuffer(out, false);
        }
      }
    }

    private void seek(long us) {
      if (pending >= 0) { decoder.releaseOutputBuffer(pending, false); pending = -1; }
      decoder.flush();
      ex.seekTo(us, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
      inputDone = eos = false;
    }

    private boolean awaitFrame() {
      synchronized (lock) {
        long until = System.currentTimeMillis() + 1000;
        while (!available) {
          long left = until - System.currentTimeMillis();
          if (left <= 0) return false;
          try { lock.wait(left); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        }
        available = false;
        return true;
      }
    }

    void release() {
      try { decoder.stop(); } catch (RuntimeException ignored) {}
      decoder.release();
      ex.release();
      surface.release();
      st.release();
      thread.quitSafely();
      GLES30.glDeleteTextures(1, new int[] {tex}, 0);
    }
  }

  /** Keeps a FrameSource for each clip on screen and draws a timeline time. Call on the GL thread. */
  static final class FrameRenderer {
    private final Context ctx;
    final Compositor compositor = new Compositor();
    private final Map<Long, FrameSource> sources = new HashMap<>(); // by clip id

    FrameRenderer(Context ctx) { this.ctx = ctx; }

    void init() { compositor.init(); compositor.setLutDir(lutDir(ctx)); compositor.setAssets(ctx.getAssets()); }

    void render(EditModel.Project p, long t, int outW, int outH) { render(p, t, 0, 0, outW, outH); }

    /** Renders timeline time t into the rectangle (x, y, w, h) of the current surface (black around it). */
    void render(EditModel.Project p, long t, int x, int y, int outW, int outH) {
      compositor.clear();
      GLES30.glViewport(x, y, outW, outH);
      List<EditModel.Layer> layers = EditModel.layersAt(p, t);
      List<Long> used = new ArrayList<>();
      for (EditModel.Layer l : layers) {
        EditModel.Clip c = p.clips.get(l.clip);
        used.add(c.id);
        FrameSource s = sources.get(c.id);
        try {
          if (s == null || !s.uri.equals(c.uri)) { if (s != null) s.release(); s = new FrameSource(ctx, c); sources.put(c.id, s); }
          s.frameAt(l.sourceUs);
          compositor.drawClip(s, c, l.alpha, l.brightness, outW, outH);
        } catch (Exception e) { Log.w(TAG, "frame " + c.name, e); }
      }
      // free decoders of clips that are no longer on screen
      for (Iterator<Map.Entry<Long, FrameSource>> it = sources.entrySet().iterator(); it.hasNext(); ) {
        Map.Entry<Long, FrameSource> e = it.next();
        if (!used.contains(e.getKey())) { e.getValue().release(); it.remove(); }
      }
      for (EditModel.Title title : p.titles) compositor.drawTitle(title, EditModel.titleAlpha(title, t), outW, outH);
    }

    void release() { for (FrameSource s : sources.values()) s.release(); sources.clear(); }
  }

  // ================================================================================================ audio

  /** Streams one file's sound from a start time as 48 kHz stereo floats. */
  static final class AudioSource {
    private final MediaExtractor ex = new MediaExtractor();
    private MediaCodec decoder;
    private EditModel.Resampler resampler;
    private float[] fifo = new float[0];
    private int fifoFrames;
    private boolean inputDone, done;
    private final long startUs;
    private int channels = 2, rate = 48_000;
    private boolean floatPcm;
    private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

    AudioSource(Context ctx, String uri, long startUs) throws IOException {
      this.startUs = startUs;
      ex.setDataSource(ctx, Uri.parse(uri), null);
      for (int i = 0; i < ex.getTrackCount(); i++) {
        MediaFormat f = ex.getTrackFormat(i);
        String m = f.getString(MediaFormat.KEY_MIME);
        if (m != null && m.startsWith("audio/")) {
          ex.selectTrack(i);
          decoder = MediaCodec.createDecoderByType(m);
          decoder.configure(f, null, null, 0);
          decoder.start();
          channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
          rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE);
          break;
        }
      }
      if (decoder == null) { done = true; return; }
      ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
      resampler = new EditModel.Resampler(rate, channels);
    }

    /** Fills `frames` 48 kHz stereo frames (adds silence once the sound ends). */
    void read(float[] out, int frames) {
      while (fifoFrames < frames && !done) decodeMore();
      int n = Math.min(frames, fifoFrames);
      System.arraycopy(fifo, 0, out, 0, n * 2);
      for (int i = n * 2; i < frames * 2; i++) out[i] = 0;
      System.arraycopy(fifo, n * 2, fifo, 0, (fifoFrames - n) * 2);
      fifoFrames -= n;
    }

    private void decodeMore() {
      if (!inputDone) {
        int in = decoder.dequeueInputBuffer(5000);
        if (in >= 0) {
          int n = ex.readSampleData(decoder.getInputBuffer(in), 0);
          if (n < 0) { decoder.queueInputBuffer(in, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true; }
          else { decoder.queueInputBuffer(in, 0, n, ex.getSampleTime(), 0); ex.advance(); }
        }
      }
      int out = decoder.dequeueOutputBuffer(info, 5000);
      if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
        MediaFormat f = decoder.getOutputFormat();
        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT); rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE);
        floatPcm = f.containsKey(MediaFormat.KEY_PCM_ENCODING) && f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT;
        resampler = new EditModel.Resampler(rate, channels);
        return;
      }
      if (out < 0) return;
      ByteBuffer b = decoder.getOutputBuffer(out);
      boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
      if (b != null && info.size > 0) {
        b.position(info.offset); b.limit(info.offset + info.size);
        b.order(ByteOrder.nativeOrder());
        int samples = info.size / (floatPcm ? 4 : 2), frames = samples / Math.max(1, channels);
        float[] pcm = new float[samples];
        if (floatPcm) b.asFloatBuffer().get(pcm);
        else { ShortBuffer sb = b.asShortBuffer(); for (int i = 0; i < samples; i++) pcm[i] = sb.get(i) / 32768f; }
        // drop what comes before the start time (seeking lands on the previous sync point)
        long skipFrames = Math.max(0, (startUs - info.presentationTimeUs) * rate / 1_000_000L);
        if (skipFrames < frames) {
          int keep = (int) (frames - skipFrames);
          float[] in = skipFrames == 0 ? pcm : java.util.Arrays.copyOfRange(pcm, (int) skipFrames * channels, frames * channels);
          float[] res = new float[(int) (keep * 48_000L / rate + 4) * 2];
          int n = resampler.process(in, keep, res, res.length / 2);
          ensure(fifoFrames + n);
          System.arraycopy(res, 0, fifo, fifoFrames * 2, n * 2);
          fifoFrames += n;
        }
      }
      decoder.releaseOutputBuffer(out, false);
      if (eos) done = true;
    }

    private void ensure(int frames) { if (fifo.length < frames * 2) fifo = java.util.Arrays.copyOf(fifo, Math.max(frames * 2, fifo.length * 2)); }

    void release() {
      if (decoder != null) { try { decoder.stop(); } catch (RuntimeException ignored) {} decoder.release(); }
      ex.release();
    }
  }

  /** Mixes a project's sound (clips with their gains and crossfades, plus music) block by block from any time. */
  static final class Mixer {
    private final Context ctx;
    private final Map<Object, AudioSource> sources = new HashMap<>();
    private final float[] block = new float[4096 * 2];

    Mixer(Context ctx) { this.ctx = ctx; }

    /** Mixes `frames` (<= 4096) frames of 48 kHz stereo starting at timeline time t into out. */
    void mix(EditModel.Project p, long t, int frames, float[] out) {
      for (int i = 0; i < frames * 2; i++) out[i] = 0;
      long t1 = t + frames * 1_000_000L / 48_000;
      long[] starts = EditModel.starts(p);
      List<Object> live = new ArrayList<>();
      for (int i = 0; i < p.clips.size(); i++) {
        EditModel.Clip c = p.clips.get(i);
        if (t1 <= starts[i] || t >= starts[i] + c.durationUs() || c.muted || !c.hasAudio) continue;
        live.add(c.id);
        double g0 = EditModel.clipGain(p, i, Math.max(t, starts[i])), g1 = EditModel.clipGain(p, i, Math.min(t1, starts[i] + c.durationUs() - 1));
        add(c.id, c.uri, c.inUs + Math.max(0, t - starts[i]), Math.max(0, starts[i] - t), frames, g0, g1, out);
      }
      EditModel.Music m = p.music;
      if (m != null && t1 > m.startUs && t < m.startUs + m.sourceDurationUs) {
        live.add(m);
        add(m, m.uri, Math.max(0, t - m.startUs), Math.max(0, m.startUs - t), frames, EditModel.musicGain(p, t), EditModel.musicGain(p, t1), out);
      }
      for (Iterator<Map.Entry<Object, AudioSource>> it = sources.entrySet().iterator(); it.hasNext(); ) {
        Map.Entry<Object, AudioSource> e = it.next();
        if (!live.contains(e.getKey())) { e.getValue().release(); it.remove(); }
      }
      for (int i = 0; i < frames * 2; i++) out[i] = Math.max(-1f, Math.min(1f, out[i]));
    }

    private void add(Object key, String uri, long sourceUs, long delayUs, int frames, double g0, double g1, float[] out) {
      AudioSource s = sources.get(key);
      try {
        if (s == null) { s = new AudioSource(ctx, uri, sourceUs); sources.put(key, s); }
      } catch (IOException e) { Log.w(TAG, "audio " + uri, e); return; }
      int skip = (int) Math.min(frames, delayUs * 48_000 / 1_000_000L);
      int n = frames - skip;
      if (n <= 0) return;
      s.read(block, n);
      for (int i = 0; i < n; i++) {
        float g = (float) (g0 + (g1 - g0) * i / Math.max(1, n - 1));
        out[(skip + i) * 2] += block[i * 2] * g;
        out[(skip + i) * 2 + 1] += block[i * 2 + 1] * g;
      }
    }

    /** Forget stream positions (after the playhead jumps). */
    void reset() { release(); }

    void release() { for (AudioSource s : sources.values()) s.release(); sources.clear(); }
  }

  // ================================================================================================ export

  /** Where imported .cube LUTs live (app storage, shared by all projects). */
  static java.io.File lutDir(Context ctx) { java.io.File d = new java.io.File(ctx.getFilesDir(), "luts"); d.mkdirs(); return d; }

  interface Progress { void at(float fraction, String stage); }

  static final class ExportSettings { int width = 1920, height = 1080; double fps = 30; boolean hevc = true; long bitrate = 40_000_000L; }

  /** Renders a project to one MP4 (video through the compositor, mixed sound as AAC). Runs on the calling thread. */
  static void export(Context ctx, EditModel.Project p, ExportSettings s, FileDescriptor out, Progress progress) throws IOException {
    long duration = EditModel.duration(p);
    if (duration <= 0) throw new IOException("the timeline is empty");
    // ---- sound first (small: AAC kept in memory), so the muxer can start with both tracks
    progress.at(0, "Mixing sound");
    MediaFormat af = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 48_000, 2);
    af.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
    af.setInteger(MediaFormat.KEY_BIT_RATE, 256_000);
    af.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4096 * 4);
    MediaCodec aac = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
    aac.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
    aac.start();
    Mixer mixer = new Mixer(ctx);
    List<byte[]> audioData = new ArrayList<>();
    List<long[]> audioInfo = new ArrayList<>(); // {pts, flags}
    MediaFormat audioFormat = null;
    float[] mix = new float[1024 * 2];
    long frame = 0, totalFrames = duration * 48_000 / 1_000_000L;
    MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
    boolean audioInDone = false, audioOutDone = false;
    try {
      while (!audioOutDone) {
        if (!audioInDone) {
          int in = aac.dequeueInputBuffer(10_000);
          if (in >= 0) {
            if (frame >= totalFrames) { aac.queueInputBuffer(in, 0, 0, frame * 1_000_000L / 48_000, MediaCodec.BUFFER_FLAG_END_OF_STREAM); audioInDone = true; }
            else {
              int n = (int) Math.min(1024, totalFrames - frame);
              mixer.mix(p, frame * 1_000_000L / 48_000, n, mix);
              ByteBuffer b = aac.getInputBuffer(in).order(ByteOrder.nativeOrder());
              b.clear();
              for (int i = 0; i < n * 2; i++) b.putShort((short) Math.round(mix[i] * 32767));
              aac.queueInputBuffer(in, 0, n * 4, frame * 1_000_000L / 48_000, 0);
              frame += n;
              progress.at(0.15f * frame / Math.max(1, totalFrames), "Mixing sound");
            }
          }
        }
        int o = aac.dequeueOutputBuffer(info, audioInDone ? 10_000 : 0);
        if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { audioFormat = aac.getOutputFormat(); continue; }
        if (o >= 0) {
          ByteBuffer b = aac.getOutputBuffer(o);
          if (b != null && info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
            byte[] d = new byte[info.size]; b.position(info.offset); b.get(d);
            audioData.add(d); audioInfo.add(new long[] {info.presentationTimeUs, info.flags & ~MediaCodec.BUFFER_FLAG_END_OF_STREAM});
          }
          if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) audioOutDone = true;
          aac.releaseOutputBuffer(o, false);
        }
      }
    } finally {
      mixer.release();
      try { aac.stop(); } catch (RuntimeException ignored) {}
      aac.release();
    }

    // ---- picture
    String mime = s.hevc ? MediaFormat.MIMETYPE_VIDEO_HEVC : MediaFormat.MIMETYPE_VIDEO_AVC;
    MediaFormat vf = MediaFormat.createVideoFormat(mime, s.width, s.height);
    vf.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
    vf.setInteger(MediaFormat.KEY_BIT_RATE, (int) Math.min(Integer.MAX_VALUE, s.bitrate));
    vf.setInteger(MediaFormat.KEY_FRAME_RATE, (int) Math.round(s.fps));
    vf.setFloat(MediaFormat.KEY_I_FRAME_INTERVAL, 1f);
    vf.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709);
    vf.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO);
    vf.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED);
    MediaCodec enc = MediaCodec.createEncoderByType(mime);
    enc.configure(vf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
    Surface input = enc.createInputSurface();
    EglTarget egl = new EglTarget(input);
    enc.start();
    FrameRenderer renderer = new FrameRenderer(ctx);
    renderer.init();
    MediaMuxer muxer = new MediaMuxer(out, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
    int vTrack = -1;
    boolean muxing = false;
    long frames = (long) Math.ceil(duration * s.fps / 1e6);
    try {
      for (long n = 0; n <= frames; n++) {
        boolean last = n == frames;
        if (!last) {
          long t = Math.round(n * 1e6 / s.fps);
          renderer.render(p, Math.min(t, duration - 1), s.width, s.height);
          egl.swap(t * 1000);
          progress.at(0.15f + 0.85f * n / Math.max(1, frames), "Rendering picture");
        } else enc.signalEndOfInputStream();
        // drain
        while (true) {
          int o = enc.dequeueOutputBuffer(info, last ? 10_000 : 0);
          if (o == MediaCodec.INFO_TRY_AGAIN_LATER) { if (last) continue; break; }
          if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            vTrack = muxer.addTrack(enc.getOutputFormat());
            int aTrack = audioFormat != null ? muxer.addTrack(audioFormat) : -1;
            muxer.start(); muxing = true;
            if (aTrack >= 0) for (int i = 0; i < audioData.size(); i++) {
              MediaCodec.BufferInfo ai = new MediaCodec.BufferInfo();
              ai.set(0, audioData.get(i).length, audioInfo.get(i)[0], (int) audioInfo.get(i)[1]);
              muxer.writeSampleData(aTrack, ByteBuffer.wrap(audioData.get(i)), ai);
            }
            continue;
          }
          if (o < 0) continue;
          ByteBuffer b = enc.getOutputBuffer(o);
          if (b != null && muxing && info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
            b.position(info.offset); b.limit(info.offset + info.size);
            muxer.writeSampleData(vTrack, b, info);
          }
          boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
          enc.releaseOutputBuffer(o, false);
          if (eos) break;
        }
      }
      progress.at(1, "Done");
    } finally {
      renderer.release();
      try { enc.stop(); } catch (RuntimeException ignored) {}
      enc.release();
      egl.release();
      if (muxing) { try { muxer.stop(); } catch (RuntimeException e) { Log.w(TAG, "muxer", e); } }
      muxer.release();
    }
  }

  /** An EGL ES 3 context rendering into a Surface (the encoder's input), with presentation times. */
  static final class EglTarget {
    private final EGLDisplay display;
    private final EGLContext context;
    private final EGLSurface surface;
    private final Surface target;

    EglTarget(Surface target) {
      this.target = target;
      display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
      int[] v = new int[2];
      EGL14.eglInitialize(display, v, 0, v, 1);
      int[] attrs = {EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR, 0x3142, 1, EGL14.EGL_NONE};
      EGLConfig[] configs = new EGLConfig[1]; int[] n = new int[1];
      EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, n, 0);
      context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, new int[] {EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE}, 0);
      surface = EGL14.eglCreateWindowSurface(display, configs[0], target, new int[] {EGL14.EGL_NONE}, 0);
      EGL14.eglMakeCurrent(display, surface, surface, context);
    }

    void swap(long ptsNs) {
      EGLExt.eglPresentationTimeANDROID(display, surface, ptsNs);
      EGL14.eglSwapBuffers(display, surface);
    }

    void release() {
      EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
      EGL14.eglDestroySurface(display, surface);
      EGL14.eglDestroyContext(display, context);
      EGL14.eglTerminate(display);
      target.release();
    }
  }
}
