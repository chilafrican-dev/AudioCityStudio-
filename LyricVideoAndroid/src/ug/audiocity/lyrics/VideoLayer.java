package ug.audiocity.lyrics;

import android.graphics.ColorMatrix;
import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * A background video for the exporter: decoded by the phone's video decoder straight into an OpenGL texture, looped
 * to the length of the song, and drawn centre-cropped to fill the frame with optional colour filter and blur. Must be
 * created and used on the exporter's GL thread (with its EGL context current).
 */
final class VideoLayer implements AutoCloseable {
    private final MediaExtractor extractor = new MediaExtractor();
    private MediaCodec codec;
    private final SurfaceTexture texture;
    private final Surface surface;
    private final HandlerThread callbacks = new HandlerThread("video-frames");
    private final Object lock = new Object();
    private boolean frameReady, inputDone, outputDone, started;
    private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
    private int held = -1;
    private long heldPts, shownPts = -1;
    final long durationUs;
    final int width, height; // as displayed (rotation applied)
    private final int tex, program, aPos, aTex, uST, uCrop, uColor, uOffset, uBlur, uTexel, uBright;
    private final float[] st = new float[16];

    static final String VERTEX = "attribute vec4 aPos; attribute vec2 aTex; uniform mat4 uST; uniform vec2 uCrop; varying vec2 vTex;"
            + "void main(){ gl_Position = aPos; vec2 t = vec2(0.5) + (aTex - vec2(0.5)) * uCrop; vTex = (uST * vec4(t, 0.0, 1.0)).xy; }";
    // 25-tap disc blur (three rings, turned against each other) when uBlur > 0, then a colour matrix and brightness.
    static final String FRAGMENT = "#extension GL_OES_EGL_image_external : require\n"
            + "precision mediump float; varying vec2 vTex; uniform samplerExternalOES uTex; uniform mat4 uColor; uniform vec4 uOffset; uniform float uBlur; uniform vec2 uTexel; uniform float uBright;"
            + "void main(){ vec4 c = texture2D(uTex, vTex);"
            + " if (uBlur > 0.0) { vec2 d = uTexel * uBlur; c *= 0.0;"
            + "  for (int i = 0; i < 8; i++) { float a = float(i) * 0.785398;"
            + "   c += texture2D(uTex, vTex + vec2(cos(a), sin(a)) * d) * 0.7"
            + "      + texture2D(uTex, vTex + vec2(cos(a + 0.3927), sin(a + 0.3927)) * d * 0.66)"
            + "      + texture2D(uTex, vTex + vec2(cos(a + 0.1963), sin(a + 0.1963)) * d * 0.33) * 1.3; }"
            + "  c = (c + texture2D(uTex, vTex) * 1.5) / (8.0 * 3.0 + 1.5); }"
            + " gl_FragColor = vec4(clamp((uColor * vec4(c.rgb, 1.0)).rgb + uOffset.rgb, 0.0, 1.0) * uBright, 1.0); }";

    VideoLayer(File file) throws IOException {
        extractor.setDataSource(file.getAbsolutePath());
        int track = -1;
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            String mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("video/")) { track = i; break; }
        }
        if (track < 0) { extractor.release(); throw new IOException("The background video has no picture track."); }
        extractor.selectTrack(track);
        MediaFormat f = extractor.getTrackFormat(track);
        int rot = f.containsKey(MediaFormat.KEY_ROTATION) ? f.getInteger(MediaFormat.KEY_ROTATION) : 0;
        int w = f.getInteger(MediaFormat.KEY_WIDTH), h = f.getInteger(MediaFormat.KEY_HEIGHT);
        width = rot % 180 == 0 ? w : h;
        height = rot % 180 == 0 ? h : w;
        durationUs = f.containsKey(MediaFormat.KEY_DURATION) ? Math.max(1, f.getLong(MediaFormat.KEY_DURATION)) : Long.MAX_VALUE;

        int[] t = new int[1];
        GLES20.glGenTextures(1, t, 0);
        tex = t[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        callbacks.start();
        texture = new SurfaceTexture(tex);
        texture.setOnFrameAvailableListener(s -> { synchronized (lock) { frameReady = true; lock.notifyAll(); } }, new Handler(callbacks.getLooper()));
        surface = new Surface(texture);
        try { codec = MediaCodec.createDecoderByType(f.getString(MediaFormat.KEY_MIME)); }
        catch (Exception e) { close(); throw new IOException("This phone cannot decode the background video."); }
        codec.configure(f, surface, null, 0);
        codec.start();
        started = true;

        program = VideoExporter.program(VERTEX, FRAGMENT);
        aPos = GLES20.glGetAttribLocation(program, "aPos");
        aTex = GLES20.glGetAttribLocation(program, "aTex");
        uST = GLES20.glGetUniformLocation(program, "uST");
        uCrop = GLES20.glGetUniformLocation(program, "uCrop");
        uColor = GLES20.glGetUniformLocation(program, "uColor");
        uOffset = GLES20.glGetUniformLocation(program, "uOffset");
        uBlur = GLES20.glGetUniformLocation(program, "uBlur");
        uTexel = GLES20.glGetUniformLocation(program, "uTexel");
        uBright = GLES20.glGetUniformLocation(program, "uBright");
        android.opengl.Matrix.setIdentityM(st, 0);
    }

    /** Shows the video frame for song time t (ms), looping the video. */
    void advanceTo(long tMs) throws IOException {
        long target = durationUs == Long.MAX_VALUE ? tMs * 1000 : (tMs * 1000) % durationUs;
        if (target < shownPts) restart();
        int idle = 0;
        while (true) {
            if (held >= 0) {
                if (heldPts <= target || shownPts < 0) { render(held); shownPts = heldPts; held = -1; continue; }
                break;
            }
            if (outputDone) break;
            feed();
            int id = codec.dequeueOutputBuffer(info, 10_000);
            if (id >= 0) {
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) { outputDone = true; codec.releaseOutputBuffer(id, false); break; }
                held = id; heldPts = info.presentationTimeUs; idle = 0;
            } else if (id == MediaCodec.INFO_TRY_AGAIN_LATER && ++idle > 300) throw new IOException("The background video decoder stopped responding.");
        }
    }

    private void feed() {
        if (inputDone) return;
        int in = codec.dequeueInputBuffer(0);
        if (in < 0) return;
        ByteBuffer buf = codec.getInputBuffer(in);
        int n = buf == null ? -1 : extractor.readSampleData(buf, 0);
        if (n < 0) { codec.queueInputBuffer(in, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true; }
        else { codec.queueInputBuffer(in, 0, n, extractor.getSampleTime(), 0); extractor.advance(); }
    }

    private void render(int id) throws IOException {
        synchronized (lock) { frameReady = false; }
        codec.releaseOutputBuffer(id, true);
        synchronized (lock) {
            long until = System.currentTimeMillis() + 2500;
            while (!frameReady) {
                long left = until - System.currentTimeMillis();
                if (left <= 0) throw new IOException("The background video did not deliver a frame.");
                try { lock.wait(left); } catch (InterruptedException e) { throw new IOException("Interrupted."); }
            }
        }
        texture.updateTexImage();
        texture.getTransformMatrix(st);
    }

    private void restart() {
        if (held >= 0) { codec.releaseOutputBuffer(held, false); held = -1; }
        codec.flush();
        extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
        inputDone = false; outputDone = false; shownPts = -1;
    }

    /**
     * Draws the current frame for a w×h picture: fit 0 fills it (centre-cropped); fit 1 fills it with a blurred, darker
     * copy and then shows the whole video sharp in the middle.
     */
    void draw(int w, int h, FloatBuffer quad, ColorMatrix filter, float blur, int fit) {
        float va = width / (float) height, fa = w / (float) h;
        if (fit == 0) { pass(w, h, quad, filter, blur, va > fa ? fa / va : 1f, va > fa ? 1f : va / fa, 1f); return; }
        pass(w, h, quad, filter, Math.max(.75f, blur), va > fa ? fa / va : 1f, va > fa ? 1f : va / fa, .62f);
        int fw = va > fa ? w : Math.round(h * va), fh = va > fa ? Math.round(w / va) : h;
        GLES20.glViewport((w - fw) / 2, (h - fh) / 2, fw, fh);
        pass(fw, fh, quad, filter, blur * .35f, 1f, 1f, 1f);
        GLES20.glViewport(0, 0, w, h);
    }

    private void pass(int w, int h, FloatBuffer quad, ColorMatrix filter, float blur, float cropX, float cropY, float bright) {
        GLES20.glUseProgram(program);
        GLES20.glUniform2f(uCrop, cropX, cropY);
        GLES20.glUniform1f(uBright, bright);
        GLES20.glUniformMatrix4fv(uST, 1, false, st, 0);
        float[] m = filter == null ? new ColorMatrix().getArray() : filter.getArray();
        // Android's 4×5 row-major matrix (offsets 0..255) → GLSL column-major mat4 + offset.
        float[] gl = {m[0], m[5], m[10], m[15], m[1], m[6], m[11], m[16], m[2], m[7], m[12], m[17], m[3], m[8], m[13], m[18]};
        GLES20.glUniformMatrix4fv(uColor, 1, false, gl, 0);
        GLES20.glUniform4f(uOffset, m[4] / 255f, m[9] / 255f, m[14] / 255f, 0);
        GLES20.glUniform1f(uBlur, blur * 28f * Math.max(1, Math.min(w, h) / 1080f));
        GLES20.glUniform2f(uTexel, 1f / width, 1f / height);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex);
        quad.position(0); GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, quad); GLES20.glEnableVertexAttribArray(aPos);
        quad.position(2); GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, quad); GLES20.glEnableVertexAttribArray(aTex);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }

    /** Quad with texture coordinates in video orientation (row 0 at the bottom, as SurfaceTexture expects). */
    static FloatBuffer quad() {
        FloatBuffer q = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        q.put(new float[]{-1, -1, 0, 0, 1, -1, 1, 0, -1, 1, 0, 1, 1, 1, 1, 1}).position(0);
        return q;
    }

    @Override public void close() {
        try { if (started) codec.stop(); } catch (Exception ignored) {}
        try { if (codec != null) codec.release(); } catch (Exception ignored) {}
        try { extractor.release(); } catch (Exception ignored) {}
        try { if (surface != null) surface.release(); } catch (Exception ignored) {}
        try { if (texture != null) texture.release(); } catch (Exception ignored) {}
        callbacks.quitSafely();
    }
}
