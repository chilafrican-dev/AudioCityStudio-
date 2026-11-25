package ug.audiocity.lyrics;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.opengl.*;
import android.view.Surface;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.concurrent.CancellationException;

/**
 * Renders a lyric video to MP4: H.264 video drawn by {@link LyricRenderer} through an EGL surface on the hardware
 * encoder, and the song re-encoded to AAC. Runs on a background thread.
 */
final class VideoExporter {
    interface Listener { void progress(float fraction, String stage); boolean cancelled(); }

    static final String AVC = MediaFormat.MIMETYPE_VIDEO_AVC;
    static final int EGL_RECORDABLE_ANDROID = 0x3142;

    /** Encoded AAC frames kept in memory until the video track exists (MediaMuxer needs every track before it starts). */
    static final class Audio {
        MediaFormat format;
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final ArrayList<long[]> samples = new ArrayList<>(); // offset, size, ptsUs, flags
        ByteBuffer direct;
        int next;
    }

    /** Returns the size actually used (encoders differ in what they accept). */
    static int[] export(LyricRenderer r, File song, File out, Listener l, File backgroundVideo) throws IOException { return export(r, song, out, l, backgroundVideo, null); }

    /** clipsDir: the project folder when the background is the clip edit (renderer.seq), else null. */
    static int[] export(LyricRenderer r, File song, File out, Listener l, File backgroundVideo, File clipsDir) throws IOException {
        Audio audio = null;
        if (song != null && song.isFile()) { l.progress(0, "Encoding the song…"); audio = encodeAudio(song, r.duration, l); }
        int[] size = pickSize(r.style.width(), r.style.height());
        encodeVideo(r, size[0], size[1], audio, out, l, r.style.background == 4 ? backgroundVideo : null, r.style.background == Style.BG_CLIPS ? clipsDir : null);
        return size;
    }

    // ------------------------------------------------------------------ audio

    static Audio encodeAudio(File song, long durationMs, Listener l) throws IOException {
        final Audio a = new Audio();
        final MediaCodec[] enc = {null};
        final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        final long[] queued = {0};
        final int[] rate = {44100}, ch = {2}, decimate = {1};
        try {
            PcmDecoder.decode(song, new PcmDecoder.Sink() {
                public void format(int r, int c) throws IOException {
                    while (r / decimate[0] > 48000) decimate[0] *= 2; // 88.2/96/192 kHz sources
                    rate[0] = r / decimate[0]; ch[0] = c;
                    MediaFormat f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate[0], ch[0]);
                    f.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
                    f.setInteger(MediaFormat.KEY_BIT_RATE, ch[0] == 1 ? 128000 : 192000);
                    f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
                    enc[0] = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
                    enc[0].configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                    enc[0].start();
                }
                public void pcm(short[] d, int frames) throws IOException {
                    int step = decimate[0], outFrames = frames / step, done = 0, waited = 0;
                    while (done < outFrames) {
                        if (l.cancelled()) throw new CancellationException();
                        int id = enc[0].dequeueInputBuffer(10000);
                        if (id < 0) { drainAudio(enc[0], info, a, false); if (++waited > 3000) throw new IOException("The AAC encoder stopped responding."); continue; }
                        waited = 0;
                        ByteBuffer in = enc[0].getInputBuffer(id);
                        in.clear(); in.order(ByteOrder.LITTLE_ENDIAN);
                        int n = Math.min(outFrames - done, in.remaining() / (2 * ch[0]));
                        for (int i = 0; i < n; i++) for (int c = 0; c < ch[0]; c++) {
                            int src = (done + i) * step, sum = 0;
                            for (int k = 0; k < step; k++) sum += d[(src + k) * ch[0] + c];
                            in.putShort((short) (sum / step));
                        }
                        enc[0].queueInputBuffer(id, 0, n * 2 * ch[0], queued[0] * 1_000_000L / rate[0], 0);
                        queued[0] += n; done += n;
                        drainAudio(enc[0], info, a, false);
                    }
                    if (durationMs > 0) l.progress(.15f * Math.min(1, queued[0] * 1000f / rate[0] / durationMs), "Encoding the song…");
                }
            }, l::cancelled);
            int id, waited = 0;
            while ((id = enc[0].dequeueInputBuffer(10000)) < 0) { drainAudio(enc[0], info, a, false); if (++waited > 3000) throw new IOException("The AAC encoder stopped responding."); }
            enc[0].queueInputBuffer(id, 0, 0, queued[0] * 1_000_000L / rate[0], MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            drainAudio(enc[0], info, a, true);
        } finally {
            if (enc[0] != null) { try { enc[0].stop(); } catch (Exception ignored) {} enc[0].release(); }
        }
        if (a.format == null || a.samples.isEmpty()) throw new IOException("The song could not be encoded to AAC.");
        byte[] all = a.bytes.toByteArray();
        a.direct = ByteBuffer.allocateDirect(Math.max(1, all.length));
        a.direct.put(all);
        return a;
    }

    static void drainAudio(MediaCodec codec, MediaCodec.BufferInfo info, Audio a, boolean end) throws IOException {
        int idle = 0;
        while (true) {
            int id = codec.dequeueOutputBuffer(info, end ? 10000 : 0);
            if (id == MediaCodec.INFO_TRY_AGAIN_LATER) { if (!end) return; if (++idle > 500) throw new IOException("The AAC encoder did not finish."); continue; }
            if (id == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { a.format = codec.getOutputFormat(); continue; }
            if (id < 0) continue;
            ByteBuffer data = codec.getOutputBuffer(id);
            if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && info.size > 0 && data != null) {
                byte[] b = new byte[info.size];
                data.position(info.offset); data.get(b, 0, info.size);
                a.samples.add(new long[]{a.bytes.size(), info.size, info.presentationTimeUs, info.flags & ~MediaCodec.BUFFER_FLAG_END_OF_STREAM});
                a.bytes.write(b, 0, b.length);
            }
            codec.releaseOutputBuffer(id, false);
            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return;
        }
    }

    // ------------------------------------------------------------------ video

    static boolean supported(int w, int h) {
        for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if (!info.isEncoder()) continue;
            for (String type : info.getSupportedTypes()) {
                if (!type.equalsIgnoreCase(AVC)) continue;
                try { if (info.getCapabilitiesForType(AVC).getVideoCapabilities().isSizeSupported(w, h)) return true; } catch (Exception ignored) {}
            }
        }
        return false;
    }

    /** The requested size if the phone's encoder takes it, else the same shape at 1080p, then 720p (4K and 21:9 are not universal). */
    static int[] pickSize(int w, int h) {
        int min = Math.min(w, h);
        ArrayList<int[]> tries = new ArrayList<>();
        for (int side : new int[]{min, 1080, 720}) {
            if (side > min) continue;
            int sw = (int) ((long) w * side / min) & ~1, sh = (int) ((long) h * side / min) & ~1;
            tries.add(new int[]{sw, sh});
            tries.add(new int[]{sw & ~15, sh & ~15});
        }
        for (int[] t : tries) if (supported(t[0], t[1])) return t;
        return tries.get(tries.size() - 1);
    }

    static final String VERTEX = "attribute vec4 aPos; attribute vec2 aTex; varying vec2 vTex; void main(){ gl_Position = aPos; vTex = aTex; }";
    static final String FRAGMENT = "precision mediump float; varying vec2 vTex; uniform sampler2D uTex; void main(){ gl_FragColor = texture2D(uTex, vTex); }";

    static void encodeVideo(LyricRenderer r, int w, int h, Audio audio, File out, Listener l, File backgroundVideo, File clipsDir) throws IOException {
        int fps = r.style.fps;
        long durationMs = Math.max(r.duration, audio == null || audio.samples.isEmpty() ? 0 : audio.samples.get(audio.samples.size() - 1)[2] / 1000);
        long frames = Math.max(1, (durationMs * fps + 999) / 1000);

        MediaFormat vf = MediaFormat.createVideoFormat(AVC, w, h);
        vf.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        vf.setInteger(MediaFormat.KEY_BIT_RATE, (int) (r.style.bitrate() * (fps > 30 ? 1.5f : 1f) * ((float) w * h / (r.style.width() * r.style.height()))));
        vf.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
        vf.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);

        MediaCodec enc = MediaCodec.createEncoderByType(AVC);
        Surface surface = null;
        MediaMuxer mux = null;
        EGLDisplay dpy = EGL14.EGL_NO_DISPLAY;
        EGLContext ctx = EGL14.EGL_NO_CONTEXT;
        EGLSurface egl = EGL14.EGL_NO_SURFACE;
        Bitmap frame = null;
        VideoLayer video = null;
        String clipLayerFile = null; // which clip file the video layer is decoding (Clips background)
        boolean started = false;
        try {
            enc.configure(vf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            surface = enc.createInputSurface();
            enc.start();
            started = true;
            mux = new MediaMuxer(out.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            int[] version = new int[2];
            if (!EGL14.eglInitialize(dpy, version, 0, version, 1)) throw new IOException("OpenGL is not available.");
            int[] attrs = {EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL_RECORDABLE_ANDROID, 1, EGL14.EGL_NONE};
            EGLConfig[] configs = new EGLConfig[1];
            int[] num = new int[1];
            if (!EGL14.eglChooseConfig(dpy, attrs, 0, configs, 0, 1, num, 0) || num[0] < 1) throw new IOException("No video surface configuration on this phone.");
            ctx = EGL14.eglCreateContext(dpy, configs[0], EGL14.EGL_NO_CONTEXT, new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
            egl = EGL14.eglCreateWindowSurface(dpy, configs[0], surface, new int[]{EGL14.EGL_NONE}, 0);
            if (ctx == EGL14.EGL_NO_CONTEXT || egl == EGL14.EGL_NO_SURFACE) throw new IOException("Cannot connect to the video encoder.");
            if (!EGL14.eglMakeCurrent(dpy, egl, egl, ctx)) throw new IOException("Cannot use the video encoder surface.");

            int program = program();
            int aPos = GLES20.glGetAttribLocation(program, "aPos"), aTex = GLES20.glGetAttribLocation(program, "aTex");
            // Clip-space quad; texture rows are flipped because bitmap row 0 is the top of the picture.
            FloatBuffer quad = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
            quad.put(new float[]{-1, -1, 0, 1, 1, -1, 1, 1, -1, 1, 0, 0, 1, 1, 1, 0}).position(0);
            int[] tex = new int[1];
            GLES20.glGenTextures(1, tex, 0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0]);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

            frame = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(frame);
            FloatBuffer videoQuad = null;
            android.graphics.ColorMatrix videoFilter = null;
            if (clipsDir != null) { videoQuad = VideoLayer.quad(); videoFilter = LyricRenderer.colorMatrix(r.style.photoFilter); }
            if (backgroundVideo != null && backgroundVideo.isFile()) {
                // Background video: decoded into a GL texture, drawn first; everything else is drawn over it.
                video = new VideoLayer(backgroundVideo);
                videoQuad = VideoLayer.quad();
                videoFilter = LyricRenderer.colorMatrix(r.style.photoFilter);
                r.videoUnder = true;
            }
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo(), audioInfo = new MediaCodec.BufferInfo();
            int[] tracks = {-1, -1};
            boolean[] muxing = {false};

            for (long i = 0; i < frames; i++) {
                if (l.cancelled()) throw new CancellationException();
                long t = i * 1000 / fps;
                long sourceUs = -1;
                if (clipsDir != null) {
                    // The clip edit: open the decoder for the clip that is on screen now (one at a time).
                    Sequence.At at = r.seq.at(t);
                    boolean isVideo = at.clip != null && !at.clip.photo;
                    if (isVideo && !at.clip.file.equals(clipLayerFile)) {
                        if (video != null) video.close();
                        video = new VideoLayer(new File(clipsDir, at.clip.file));
                        clipLayerFile = at.clip.file;
                    }
                    if (!isVideo && video != null) { video.close(); video = null; clipLayerFile = null; }
                    sourceUs = isVideo ? at.source * 1000 : -1;
                    r.videoUnder = isVideo;
                }
                frame.eraseColor(video != null ? 0 : 0xff000000);
                r.draw(canvas, w, h, t);
                GLES20.glViewport(0, 0, w, h);
                if (video != null) {
                    GLES20.glClearColor(0, 0, 0, 1);
                    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
                    if (sourceUs >= 0) video.advanceToUs(sourceUs); else video.advanceTo(t);
                    video.draw(w, h, videoQuad, videoFilter, r.style.videoBlur, r.style.videoFit);
                    GLES20.glEnable(GLES20.GL_BLEND);
                    GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA); // the overlay is premultiplied
                }
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0]);
                if (i == 0) GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, frame, 0); else GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, frame);
                GLES20.glUseProgram(program);
                quad.position(0); GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, quad); GLES20.glEnableVertexAttribArray(aPos);
                quad.position(2); GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, quad); GLES20.glEnableVertexAttribArray(aTex);
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
                if (video != null) GLES20.glDisable(GLES20.GL_BLEND);
                EGLExt.eglPresentationTimeANDROID(dpy, egl, i * 1_000_000_000L / fps);
                if (!EGL14.eglSwapBuffers(dpy, egl)) throw new IOException("The video encoder rejected a frame.");
                drainVideo(enc, info, mux, tracks, muxing, audio, audioInfo, false);
                if (i % 15 == 0) l.progress(.15f + .85f * i / frames, "Drawing frame " + (i + 1) + " of " + frames + "…");
            }
            enc.signalEndOfInputStream();
            drainVideo(enc, info, mux, tracks, muxing, audio, audioInfo, true);
            if (!muxing[0]) throw new IOException("The video encoder produced no frames.");
            writeAudio(mux, tracks[1], audio, audioInfo, Long.MAX_VALUE);
            mux.stop();
            l.progress(1, "Finishing…");
        } finally {
            if (video != null) video.close();
            if (dpy != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                if (egl != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(dpy, egl);
                if (ctx != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(dpy, ctx);
                EGL14.eglReleaseThread();
                EGL14.eglTerminate(dpy);
            }
            if (surface != null) surface.release();
            if (started) try { enc.stop(); } catch (Exception ignored) {}
            enc.release();
            if (mux != null) try { mux.release(); } catch (Exception ignored) {}
            if (frame != null) frame.recycle();
        }
    }

    static void drainVideo(MediaCodec enc, MediaCodec.BufferInfo info, MediaMuxer mux, int[] tracks, boolean[] muxing, Audio audio, MediaCodec.BufferInfo audioInfo, boolean end) throws IOException {
        int idle = 0;
        while (true) {
            int id = enc.dequeueOutputBuffer(info, end ? 10000 : 0);
            if (id == MediaCodec.INFO_TRY_AGAIN_LATER) { if (!end) return; if (++idle > 600) throw new IOException("The video encoder did not finish."); continue; }
            if (id == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (muxing[0]) throw new IOException("The video encoder changed format twice.");
                tracks[0] = mux.addTrack(enc.getOutputFormat());
                tracks[1] = audio == null ? -1 : mux.addTrack(audio.format); // no song: a video without sound
                mux.start();
                muxing[0] = true;
                continue;
            }
            if (id < 0) continue;
            ByteBuffer data = enc.getOutputBuffer(id);
            if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;
            if (info.size > 0 && data != null) {
                if (!muxing[0]) throw new IOException("The video encoder sent frames before its format.");
                writeAudio(mux, tracks[1], audio, audioInfo, info.presentationTimeUs);
                data.position(info.offset); data.limit(info.offset + info.size);
                mux.writeSampleData(tracks[0], data, info);
            }
            enc.releaseOutputBuffer(id, false);
            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return;
        }
    }

    /** Interleaves audio: writes every AAC frame up to the given time. */
    static void writeAudio(MediaMuxer mux, int track, Audio a, MediaCodec.BufferInfo info, long upToUs) {
        if (a == null || track < 0) return;
        while (a.next < a.samples.size() && a.samples.get(a.next)[2] <= upToUs) {
            long[] s = a.samples.get(a.next++);
            info.set((int) s[0], (int) s[1], s[2], (int) s[3]);
            a.direct.limit(a.direct.capacity()).position(0);
            mux.writeSampleData(track, a.direct, info);
        }
    }

    static int program() throws IOException { return program(VERTEX, FRAGMENT); }

    static int program(String vertex, String fragment) throws IOException {
        int vs = shader(GLES20.GL_VERTEX_SHADER, vertex), fs = shader(GLES20.GL_FRAGMENT_SHADER, fragment);
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, vs); GLES20.glAttachShader(p, fs);
        GLES20.glLinkProgram(p);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) throw new IOException("Video shader failed: " + GLES20.glGetProgramInfoLog(p));
        return p;
    }

    static int shader(int type, String src) throws IOException {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) throw new IOException("Video shader failed: " + GLES20.glGetShaderInfoLog(s));
        return s;
    }
}
