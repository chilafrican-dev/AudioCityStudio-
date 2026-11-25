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
 * The background video in the live preview: a muted, looping player on a TextureView placed exactly under the
 * preview frame, following the song (play, pause, seek). Colour filter and blur are applied to the view.
 */
final class VideoBackdrop implements TextureView.SurfaceTextureListener {
    final TextureView view;
    private MediaPlayer player;
    private Surface surface;
    private File file;
    private boolean ready;
    private int vw = 16, vh = 9;
    private long durationMs = 1;
    private final RectF frame = new RectF();
    private long lastCheck;

    VideoBackdrop(Context c) {
        view = new TextureView(c);
        view.setSurfaceTextureListener(this);
        view.setVisibility(View.GONE);
    }

    /** Shows a video (or hides the view when file is null). */
    void setVideo(File f) {
        if (f == null ? file == null : f.equals(file)) return;
        release();
        file = f;
        view.setVisibility(f == null ? View.GONE : View.VISIBLE);
        if (f == null) return;
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(f.getAbsolutePath());
            int w = parse(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH), 16), h = parse(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT), 9);
            int rot = parse(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION), 0);
            vw = rot % 180 == 0 ? w : h; vh = rot % 180 == 0 ? h : w;
            durationMs = Math.max(1, parse(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION), 1));
        } catch (Exception ignored) {
        } finally { try { r.release(); } catch (Exception ignored) {} }
        if (surface != null) open();
    }

    static int parse(String s, int d) { try { return s == null ? d : Integer.parseInt(s); } catch (NumberFormatException e) { return d; } }

    private void open() {
        if (file == null || surface == null || player != null) return;
        MediaPlayer mp = new MediaPlayer();
        player = mp;
        try {
            mp.setDataSource(file.getAbsolutePath());
            mp.setSurface(surface);
            mp.setVolume(0, 0);
            mp.setLooping(true);
            mp.setOnPreparedListener(p -> { if (player == p) { ready = true; p.seekTo(0); } });
            mp.prepareAsync();
        } catch (Exception e) { release(); }
    }

    /** Places the view exactly on the preview frame (in parent coordinates) and crops the video to fill it. */
    void place(RectF r) {
        if (r.equals(frame) && view.getWidth() == Math.round(r.width())) return;
        frame.set(r);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Math.round(r.width()), Math.round(r.height()));
        lp.leftMargin = Math.round(r.left); lp.topMargin = Math.round(r.top);
        view.setLayoutParams(lp);
        float va = vw / (float) vh, fa = r.width() / Math.max(1, r.height());
        Matrix m = new Matrix();
        if (va > fa) m.setScale(va / fa, 1, r.width() / 2, r.height() / 2); else m.setScale(1, fa / va, r.width() / 2, r.height() / 2);
        view.setTransform(m);
    }

    /** Colour filter and blur, as in the export. */
    void look(ColorMatrix filter, float blur) {
        Paint p = new Paint();
        if (filter != null) p.setColorFilter(new ColorMatrixColorFilter(filter));
        view.setLayerType(View.LAYER_TYPE_HARDWARE, p);
        if (Build.VERSION.SDK_INT >= 31) {
            float radius = blur * 28 * Math.max(.3f, frame.height() / 1080f);
            view.setRenderEffect(blur > .01f ? android.graphics.RenderEffect.createBlurEffect(radius, radius, android.graphics.Shader.TileMode.CLAMP) : null);
        }
    }

    /** Keeps the video in step with the song: same play state, position (looped) and occasional drift correction. */
    void follow(long songMs, boolean playing) {
        if (player == null || !ready) return;
        long want = songMs % durationMs;
        try {
            if (playing && !player.isPlaying()) { player.seekTo((int) want); player.start(); }
            else if (!playing && player.isPlaying()) { player.pause(); player.seekTo((int) want); }
            long now = System.currentTimeMillis();
            if (now - lastCheck > 700) {
                lastCheck = now;
                long at = player.getCurrentPosition(), diff = Math.abs(at - want);
                if (diff > 250 && diff < durationMs - 250) player.seekTo((int) want);
            }
        } catch (IllegalStateException ignored) {}
    }

    /** Shows the frame for a song time while paused (after a seek). */
    void seek(long songMs) { if (player != null && ready) try { player.seekTo((int) (songMs % durationMs)); } catch (IllegalStateException ignored) {} }

    void release() {
        if (player != null) try { player.release(); } catch (Exception ignored) {}
        player = null; ready = false;
    }

    @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) { surface = new Surface(st); open(); }
    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {}
    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) { release(); if (surface != null) surface.release(); surface = null; return true; }
    @Override public void onSurfaceTextureUpdated(SurfaceTexture st) {}
}
