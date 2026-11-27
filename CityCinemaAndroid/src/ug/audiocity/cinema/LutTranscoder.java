package ug.audiocity.cinema;

import android.content.Context;
import android.graphics.SurfaceTexture;
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
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;

import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * Makes a normal-colour copy of a log clip: decodes it, applies the log-to-Rec.709 3D LUT on the GPU (the same one
 * the player shows), re-encodes it with the original codec and bitrate, and copies the sound unchanged. The result
 * plays and edits like ordinary video in any app.
 */
final class LutTranscoder {
  static final String TAG = "CityCinemaLut";

  interface Progress { void at(float fraction); }

  private LutTranscoder() {}

  static void run(Context ctx, Uri source, String look, FileDescriptor out, Progress progress) throws IOException {
    MediaExtractor ex = new MediaExtractor();
    ex.setDataSource(ctx, source, null);
    int videoTrack = -1, audioTrack = -1;
    MediaFormat vf = null, af = null;
    for (int i = 0; i < ex.getTrackCount(); i++) {
      MediaFormat f = ex.getTrackFormat(i);
      String mime = f.getString(MediaFormat.KEY_MIME);
      if (mime == null) continue;
      if (mime.startsWith("video/") && videoTrack < 0) { videoTrack = i; vf = f; }
      else if (mime.startsWith("audio/") && audioTrack < 0) { audioTrack = i; af = f; }
    }
    if (vf == null) { ex.release(); throw new IOException("no video track"); }
    String mime = vf.getString(MediaFormat.KEY_MIME);
    int w = vf.getInteger(MediaFormat.KEY_WIDTH), h = vf.getInteger(MediaFormat.KEY_HEIGHT);
    long durationUs = vf.containsKey(MediaFormat.KEY_DURATION) ? vf.getLong(MediaFormat.KEY_DURATION) : 1;
    int fps = vf.containsKey(MediaFormat.KEY_FRAME_RATE) ? vf.getInteger(MediaFormat.KEY_FRAME_RATE) : 30;
    int bitrate = vf.containsKey(MediaFormat.KEY_BIT_RATE) ? vf.getInteger(MediaFormat.KEY_BIT_RATE) : (int) Math.min(200_000_000L, (long) w * h * fps / 4);
    int rotation = vf.containsKey(MediaFormat.KEY_ROTATION) ? vf.getInteger(MediaFormat.KEY_ROTATION) : 0;

    // encoder (same codec, bitrate and size) with an input surface we draw into
    MediaFormat ef = MediaFormat.createVideoFormat(mime, w, h);
    ef.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
    ef.setInteger(MediaFormat.KEY_BIT_RATE, Math.max(8_000_000, bitrate));
    ef.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
    ef.setFloat(MediaFormat.KEY_I_FRAME_INTERVAL, 1f);
    ef.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709);
    ef.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO);
    ef.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED);
    MediaCodec encoder = MediaCodec.createEncoderByType(mime);
    encoder.configure(ef, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
    Gl gl = new Gl(encoder.createInputSurface(), w, h, look);
    encoder.start();

    MediaCodec decoder = MediaCodec.createDecoderByType(mime);
    decoder.configure(vf, gl.decoderSurface, null, 0);
    decoder.start();

    MediaMuxer muxer = new MediaMuxer(out, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
    muxer.setOrientationHint(rotation);
    int muxVideo = -1, muxAudio = -1;
    boolean muxing = false;
    ex.selectTrack(videoTrack);
    MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
    boolean inputDone = false, decodeDone = false, encodeDone = false;
    try {
      while (!encodeDone) {
        // feed the decoder
        if (!inputDone) {
          int in = decoder.dequeueInputBuffer(10_000);
          if (in >= 0) {
            ByteBuffer buf = decoder.getInputBuffer(in);
            int n = ex.readSampleData(buf, 0);
            if (n < 0) { decoder.queueInputBuffer(in, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true; }
            else { decoder.queueInputBuffer(in, 0, n, ex.getSampleTime(), 0); ex.advance(); }
          }
        }
        // decoded frames: draw through the LUT into the encoder
        if (!decodeDone) {
          int o = decoder.dequeueOutputBuffer(info, 10_000);
          if (o >= 0) {
            boolean render = info.size > 0;
            long pts = info.presentationTimeUs;
            boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
            decoder.releaseOutputBuffer(o, render);
            if (render && gl.awaitFrame()) {
              gl.draw(pts * 1000);
              if (progress != null) progress.at(Math.min(1f, pts / (float) Math.max(1, durationUs)));
            }
            if (eos) { encoder.signalEndOfInputStream(); decodeDone = true; }
          }
        }
        // encoded output to the muxer
        while (true) {
          int e = encoder.dequeueOutputBuffer(info, decodeDone ? 10_000 : 0);
          if (e == MediaCodec.INFO_TRY_AGAIN_LATER) break;
          if (e == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            muxVideo = muxer.addTrack(encoder.getOutputFormat());
            if (af != null) muxAudio = muxer.addTrack(af);
            muxer.start(); muxing = true;
            continue;
          }
          if (e < 0) continue;
          ByteBuffer data = encoder.getOutputBuffer(e);
          if (data != null && muxing && info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
            data.position(info.offset); data.limit(info.offset + info.size);
            muxer.writeSampleData(muxVideo, data, info);
          }
          boolean done = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
          encoder.releaseOutputBuffer(e, false);
          if (done) { encodeDone = true; break; }
        }
      }
      // the sound is copied as it is
      if (muxAudio >= 0) {
        ex.unselectTrack(videoTrack);
        ex.selectTrack(audioTrack);
        ex.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
        ByteBuffer buf = ByteBuffer.allocate(256 * 1024);
        while (true) {
          int n = ex.readSampleData(buf, 0);
          if (n < 0) break;
          info.set(0, n, ex.getSampleTime(), (ex.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0 ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
          muxer.writeSampleData(muxAudio, buf, info);
          ex.advance();
        }
      }
      if (progress != null) progress.at(1f);
    } finally {
      try { decoder.stop(); } catch (RuntimeException ignored) {}
      decoder.release();
      try { encoder.stop(); } catch (RuntimeException ignored) {}
      encoder.release();
      gl.release();
      if (muxing) { try { muxer.stop(); } catch (RuntimeException ex2) { Log.w(TAG, "muxer stop", ex2); } }
      muxer.release();
      ex.release();
    }
  }

  /** EGL context drawing decoder frames (via a SurfaceTexture) through the LUT into the encoder's input surface. */
  static final class Gl implements SurfaceTexture.OnFrameAvailableListener {
    private final EGLDisplay display;
    private final EGLContext context;
    private final EGLSurface eglSurface;
    private final Surface encoderSurface;
    private final HandlerThread thread = new HandlerThread("lut-frames");
    private final SurfaceTexture texture;
    final Surface decoderSurface;
    private final int program, oesTex, lutTex, width, height;
    private final float[] st = new float[16];
    private final Object lock = new Object();
    private boolean frameAvailable;
    private final FloatBuffer quad = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();

    Gl(Surface encoderInput, int width, int height, String look) {
      this.encoderSurface = encoderInput; this.width = width; this.height = height;
      display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
      int[] v = new int[2];
      EGL14.eglInitialize(display, v, 0, v, 1);
      int[] attrs = {EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
          0x3142 /* EGL_RECORDABLE_ANDROID */, 1, EGL14.EGL_NONE};
      EGLConfig[] configs = new EGLConfig[1]; int[] n = new int[1];
      EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, n, 0);
      context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, new int[] {EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE}, 0);
      eglSurface = EGL14.eglCreateWindowSurface(display, configs[0], encoderInput, new int[] {EGL14.EGL_NONE}, 0);
      EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context);
      int[] t = new int[2];
      GLES30.glGenTextures(2, t, 0);
      oesTex = t[0]; lutTex = t[1];
      GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex);
      GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
      GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
      // 33^3 LUT: log decode, gamut to Rec.709, display tone map
      final int s = 33;
      ByteBuffer lut = ByteBuffer.allocateDirect(s * s * s * 3).order(ByteOrder.nativeOrder());
      double d = s - 1;
      for (int b = 0; b < s; b++) for (int g = 0; g < s; g++) for (int r = 0; r < s; r++) {
        double[] o = CinemaMath.logToRec709(look, r / d, g / d, b / d);
        for (int i = 0; i < 3; i++) lut.put((byte) Math.round(CinemaMath.clamp01(o[i]) * 255));
      }
      lut.flip();
      GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTex);
      GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
      GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
      for (int p : new int[] {GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_TEXTURE_WRAP_R}) GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, p, GLES30.GL_CLAMP_TO_EDGE);
      GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1);
      GLES30.glTexImage3D(GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGB8, s, s, s, 0, GLES30.GL_RGB, GLES30.GL_UNSIGNED_BYTE, lut);
      program = build(
          "#version 300 es\nin vec4 aPos; in vec4 aTex; uniform mat4 uSt; out vec2 vTex;\nvoid main() { gl_Position = aPos; vTex = (uSt * aTex).xy; }\n",
          "#version 300 es\n#extension GL_OES_EGL_image_external_essl3 : require\nprecision mediump float;\n"
              + "uniform samplerExternalOES uTex; uniform mediump sampler3D uLut;\nin vec2 vTex; out vec4 o;\n"
              + "void main() { vec3 c = texture(uTex, vTex).rgb; o = vec4(texture(uLut, c * (32.0 / 33.0) + 0.5 / 33.0).rgb, 1.0); }\n");
      thread.start();
      texture = new SurfaceTexture(oesTex);
      texture.setOnFrameAvailableListener(this, new Handler(thread.getLooper()));
      decoderSurface = new Surface(texture);
      quad.put(new float[] {-1, -1, 0, 0, 1, -1, 1, 0, -1, 1, 0, 1, 1, 1, 1, 1});
    }

    @Override public void onFrameAvailable(SurfaceTexture s) { synchronized (lock) { frameAvailable = true; lock.notifyAll(); } }

    boolean awaitFrame() {
      synchronized (lock) {
        long until = System.currentTimeMillis() + 2500;
        while (!frameAvailable) {
          long left = until - System.currentTimeMillis();
          if (left <= 0) { Log.w(TAG, "frame wait timed out"); return false; }
          try { lock.wait(left); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        }
        frameAvailable = false;
      }
      texture.updateTexImage();
      return true;
    }

    void draw(long ptsNs) {
      texture.getTransformMatrix(st);
      GLES30.glViewport(0, 0, width, height);
      GLES30.glUseProgram(program);
      int aPos = GLES30.glGetAttribLocation(program, "aPos"), aTex = GLES30.glGetAttribLocation(program, "aTex");
      quad.position(0); GLES30.glVertexAttribPointer(aPos, 2, GLES30.GL_FLOAT, false, 16, quad); GLES30.glEnableVertexAttribArray(aPos);
      quad.position(2); GLES30.glVertexAttribPointer(aTex, 2, GLES30.GL_FLOAT, false, 16, quad); GLES30.glEnableVertexAttribArray(aTex);
      GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(program, "uSt"), 1, false, st, 0);
      GLES30.glActiveTexture(GLES30.GL_TEXTURE0); GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex);
      GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uTex"), 0);
      GLES30.glActiveTexture(GLES30.GL_TEXTURE1); GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTex);
      GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uLut"), 1);
      GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);
      EGLExt.eglPresentationTimeANDROID(display, eglSurface, ptsNs);
      EGL14.eglSwapBuffers(display, eglSurface);
    }

    void release() {
      decoderSurface.release();
      texture.release();
      thread.quitSafely();
      EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
      EGL14.eglDestroySurface(display, eglSurface);
      EGL14.eglDestroyContext(display, context);
      EGL14.eglTerminate(display);
      encoderSurface.release();
    }

    private static int build(String vs, String fs) {
      int v = shader(GLES30.GL_VERTEX_SHADER, vs), f = shader(GLES30.GL_FRAGMENT_SHADER, fs);
      int p = GLES30.glCreateProgram();
      GLES30.glAttachShader(p, v); GLES30.glAttachShader(p, f); GLES30.glLinkProgram(p);
      return p;
    }

    private static int shader(int type, String src) {
      int s = GLES30.glCreateShader(type);
      GLES30.glShaderSource(s, src); GLES30.glCompileShader(s);
      int[] ok = new int[1];
      GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0);
      if (ok[0] == 0) Log.w(TAG, "shader: " + GLES30.glGetShaderInfoLog(s));
      return s;
    }
  }
}
