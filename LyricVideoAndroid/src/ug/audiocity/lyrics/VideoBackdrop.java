package ug.audiocity.lyrics;

import android.content.Context;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.os.Build;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;

import java.io.File;

/**
 * The background video in the live preview: muted, looping players on TextureViews placed exactly under the preview
 * frame, following the song (play, pause, seek). "Whole video + blur" uses two layers: a blurred copy filling the
 * frame and the whole video sharp in the middle. Colour filter and blur are applied to the views.
 */
final class VideoBackdrop {
    /** One video player on its own view. */
    final class Layer implements TextureView.SurfaceTextureListener {
        final TextureView view;
        MediaPlayer player;
        Surface surface;
        boolean ready;
        long lastCheck;

        Layer(Context c) { view = new TextureView(c); view.setSurfaceTextureListener(this); view.setVisibility(View.GONE); }

        void open() {
            if (file == null || surface == null || player != null || view.getVisibility() != View.VISIBLE) return;
            MediaPlayer mp = new MediaPlayer();
            player = mp;
            try {
                mp.setDataSource(file.getAbsolutePath());
                mp.setSurface(surface);
                mp.setVolume(0, 0);
                mp.setLooping(true);
                mp.setOnPreparedListener(p -> { if (player == p) { ready = true; p.seekTo((int) (lastSong % durationMs)); } });
                mp.prepareAsync();
            } catch (Exception e) { release(); }
        }

        void follow(long songMs, boolean playing) {
            if (player == null || !ready) return;
            long want = songMs % durationMs;
            try {
                if (playing && !player.isPlaying()) { player.seekTo((int) want); player.start(); }
                else if (!playing && player.isPlaying()) { player.pause(); player.seekTo((int) want); }
                long now = System.currentTimeMillis();
                if (now - lastCheck > 700) {
                    lastCheck = now;
                    long diff = Math.abs(player.getCurrentPosition() - want);
                    if (diff > 250 && diff < durationMs - 250) player.seekTo((int) want);
                }
            } catch (IllegalStateException ignored) {}
        }

        void seek(long songMs) { if (player != null && ready) try { player.seekTo((int) (songMs % durationMs)); } catch (IllegalStateException ignored) {} }
        void release() { if (player != null) try { player.release(); } catch (Exception ignored) {} player = null; ready = false; }

        @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) { surface = new Surface(st); open(); }
        @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {}
        @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) { release(); if (surface != null) surface.release(); surface = null; return true; }
        @Override public void onSurfaceTextureUpdated(SurfaceTexture st) {}
    }

    /** Blurred full-frame copy (only in "Whole video + blur"), then the main video. Add both views in this order. */
    final Layer soft, main;
    private File file;
    private int vw = 16, vh = 9, fit;
    private long durationMs = 1, lastSong;
    private final RectF frame = new RectF();
    private ColorMatrix filter;
    private float blur;

    VideoBackdrop(Context c) { soft = new Layer(c); main = new Layer(c); }

    /** Shows a video (or hides the views when file is null) with the given layout (Style.videoFit). */
    void setVideo(File f, int fitMode) {
        boolean same = f == null ? file == null : f.equals(file);
        if (same && fitMode == fit) return;
        fit = fitMode;
        if (!same) {
            soft.release(); main.release();
            file = f;
            if (f != null) readInfo(f);
        }
        main.view.setVisibility(f == null ? View.GONE : View.VISIBLE);
        soft.view.setVisibility(f != null && fit == 1 ? View.VISIBLE : View.GONE);
        if (fit != 1) soft.release();
        main.open(); soft.open();
        frame.setEmpty();
        look(filter, blur);
    }

    private void readInfo(File f) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(f.getAbsolutePath());
            int w = parse(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH), 16), h = parse(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT), 9);
            int rot = parse(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION), 0);
            vw = rot % 180 == 0 ? w : h; vh = rot % 180 == 0 ? h : w;
            durationMs = Math.max(1, parse(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION), 1));
        } catch (Exception ignored) {
        } finally { try { r.release(); } catch (Exception ignored) {} }
    }

    static int parse(String s, int d) { try { return s == null ? d : Integer.parseInt(s); } catch (NumberFormatException e) { return d; } }

    /** Places the views on the preview frame (parent coordinates): cropped to fill, or whole in the middle over the blurred copy. */
    void place(RectF r) {
        if (r.equals(frame)) return;
        frame.set(r);
        float va = vw / (float) vh, fa = r.width() / Math.max(1, r.height());
        if (fit == 1) {
            position(soft.view, r, va, true);
            float fw = va > fa ? r.width() : r.height() * va, fh = va > fa ? r.width() / va : r.height();
            position(main.view, new RectF(r.centerX() - fw / 2, r.centerY() - fh / 2, r.centerX() + fw / 2, r.centerY() + fh / 2), va, false);
        } else position(main.view, r, va, true);
    }

    private static void position(TextureView v, RectF r, float va, boolean crop) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Math.round(r.width()), Math.round(r.height()));
        lp.leftMargin = Math.round(r.left); lp.topMargin = Math.round(r.top);
        v.setLayoutParams(lp);
        Matrix m = new Matrix();
        float fa = r.width() / Math.max(1, r.height());
        if (crop) { if (va > fa) m.setScale(va / fa, 1, r.width() / 2, r.height() / 2); else m.setScale(1, fa / va, r.width() / 2, r.height() / 2); }
        v.setTransform(m);
    }

    /** Colour filter and blur, as in the export (the blurred copy is also darker). */
    void look(ColorMatrix f, float amount) {
        filter = f; blur = amount;
        float scale = Math.max(.3f, frame.height() / 1080f);
        apply(main.view, f, 1f, fit == 1 ? amount * .35f : amount, scale);
        apply(soft.view, f, .62f, Math.max(.75f, amount), scale);
    }

    private static void apply(TextureView v, ColorMatrix f, float bright, float amount, float scale) {
        ColorMatrix m = new ColorMatrix();
        if (f != null) m.set(f);
        if (bright < 1) { ColorMatrix d = new ColorMatrix(); d.setScale(bright, bright, bright, 1); m.postConcat(d); }
        Paint p = new Paint();
        p.setColorFilter(new ColorMatrixColorFilter(m));
        v.setLayerType(View.LAYER_TYPE_HARDWARE, p);
        if (Build.VERSION.SDK_INT >= 31) {
            float radius = amount * 28 * scale;
            v.setRenderEffect(amount > .01f ? android.graphics.RenderEffect.createBlurEffect(radius, radius, android.graphics.Shader.TileMode.CLAMP) : null);
        }
    }

    void follow(long songMs, boolean playing) { lastSong = songMs; main.follow(songMs, playing); soft.follow(songMs, playing); }
    void seek(long songMs) { lastSong = songMs; main.seek(songMs); soft.seek(songMs); }
    void release() { main.release(); soft.release(); }
}
