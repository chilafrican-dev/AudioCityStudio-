package ug.audiocity.lyrics;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.provider.MediaStore;

import java.io.*;
import java.util.concurrent.CancellationException;

/**
 * Renders the video in a foreground service, so the export keeps going when the user leaves the app or the screen
 * turns off. Progress shows in a notification (with Cancel); when done, a notification offers Watch and Share.
 */
public class ExportService extends Service {
    static final String CHANNEL = "export", ACTION_CANCEL = "cancel", EXTRA_DIR = "dir";
    static final int NOTE = 7, NOTE_DONE = 8;

    /** Shared export state, read by the app screen to show progress. */
    static final class State {
        volatile boolean running, cancelled, seen;
        volatile float progress;
        volatile String stage = "Starting…", title = "", error;
        volatile Uri result;
        volatile long started, took;
    }
    static volatile State state;
    interface Listener { void changed(State s); }
    static volatile Listener listener;

    static boolean running() { State s = state; return s != null && s.running; }

    static void start(Context c, File projectDir, String title) {
        State s = new State();
        s.running = true;
        s.title = title;
        s.started = System.currentTimeMillis();
        state = s;
        Intent i = new Intent(c, ExportService.class).putExtra(EXTRA_DIR, projectDir.getAbsolutePath());
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
    }

    static void cancel(Context c) {
        State s = state;
        if (s != null) s.cancelled = true;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_CANCEL.equals(intent.getAction())) { cancel(this); return START_NOT_STICKY; }
        channel();
        Notification n = progressNote(0, "Starting…");
        if (Build.VERSION.SDK_INT >= 35) startForeground(NOTE, n, 0x00002000 /* FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING */);
        else if (Build.VERSION.SDK_INT >= 29) startForeground(NOTE, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(NOTE, n);
        final String dir = intent == null ? null : intent.getStringExtra(EXTRA_DIR);
        final State s = state;
        if (dir == null || s == null) { stopSelf(); return START_NOT_STICKY; }
        new Thread(() -> run(new File(dir), s), "export").start();
        return START_NOT_STICKY;
    }

    void run(File dir, State s) {
        PowerManager.WakeLock lock = ((PowerManager) getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pearl:export");
        lock.acquire(6 * 60 * 60 * 1000L);
        File out = new File(getCacheDir(), "render.mp4");
        try {
            Fonts.assets = getAssets();
            Project p = Project.load(dir);
            PhotoSet set = new PhotoSet(p.style.background == 2 ? p.photos() : null, Math.max(p.style.width(), p.style.height()));
            Bitmap cover = p.style.cover || p.style.background == 3 ? PhotoSet.decode(p.cover(), 1600) : null;
            LyricRenderer r = LyricRenderer.snapshot(p, set, LyricRenderer.loadFont(p.font()), cover);
            final long[] lastNote = {0};
            VideoExporter.export(r, p.audio(), out, new VideoExporter.Listener() {
                public void progress(float f, String text) {
                    s.progress = f; s.stage = text;
                    long t = System.currentTimeMillis();
                    if (t - lastNote[0] > 1000) { lastNote[0] = t; notifyProgress(f, text); }
                    Listener l = listener; if (l != null) l.changed(s);
                }
                public boolean cancelled() { return s.cancelled; }
            }, p.video());
            s.stage = "Saving to your gallery…";
            Listener l0 = listener; if (l0 != null) l0.changed(s);
            s.result = publish(out, MainActivity.safeName(p.title) + (p.artist.isEmpty() ? "" : " - " + MainActivity.safeName(p.artist)) + " (lyric video).mp4");
            s.took = System.currentTimeMillis() - s.started;
            doneNote(s, null);
        } catch (CancellationException e) {
            s.error = "cancelled";
        } catch (Throwable e) {
            s.error = e instanceof OutOfMemoryError ? "Not enough memory. Try 720p." : String.valueOf(e.getMessage());
            doneNote(s, s.error);
        } finally {
            out.delete();
            s.running = false;
            if (lock.isHeld()) lock.release();
            Listener l = listener; if (l != null) l.changed(s);
            if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE); else stopForeground(true);
            stopSelf();
        }
    }

    /** Copies the finished MP4 into the shared Movies collection (no storage permission needed on Android 10+). */
    Uri publish(File file, String name) throws IOException {
        ContentValues v = new ContentValues();
        v.put(MediaStore.Video.Media.DISPLAY_NAME, name);
        v.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        v.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Pearl Lyrics");
        v.put(MediaStore.Video.Media.IS_PENDING, 1);
        Uri uri = getContentResolver().insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v);
        if (uri == null) throw new IOException("Cannot create the video in your gallery.");
        try (InputStream in = new FileInputStream(file); OutputStream o = getContentResolver().openOutputStream(uri, "w")) {
            if (o == null) throw new IOException("Cannot write the video.");
            byte[] b = new byte[1 << 16]; int n;
            while ((n = in.read(b)) != -1) o.write(b, 0, n);
        } catch (IOException e) { getContentResolver().delete(uri, null, null); throw e; }
        v.clear();
        v.put(MediaStore.Video.Media.IS_PENDING, 0);
        getContentResolver().update(uri, v, null, null);
        return uri;
    }

    // ------------------------------------------------------------------ notifications

    void channel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel(CHANNEL, "Video export", NotificationManager.IMPORTANCE_LOW);
            c.setDescription("Shows export progress and when your lyric video is ready");
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(c);
        }
    }

    Notification.Builder builder() {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.stat_sys_upload);
        Intent open = new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        b.setContentIntent(PendingIntent.getActivity(this, 1, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        return b;
    }

    Notification progressNote(float f, String text) {
        State s = state;
        PendingIntent cancel = PendingIntent.getService(this, 2, new Intent(this, ExportService.class).setAction(ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return builder().setContentTitle("Exporting “" + (s == null ? "" : s.title) + "”  " + Math.round(f * 100) + " %").setContentText(text)
                .setProgress(1000, Math.round(f * 1000), false).setOngoing(true).setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(null, "Cancel", cancel).build()).build();
    }

    void notifyProgress(float f, String text) { ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTE, progressNote(f, text)); }

    void doneNote(State s, String error) {
        Notification.Builder b = builder().setAutoCancel(true);
        if (error != null) b.setSmallIcon(android.R.drawable.stat_notify_error).setContentTitle("Export failed").setContentText(error);
        else {
            b.setSmallIcon(android.R.drawable.stat_sys_upload_done).setContentTitle("Lyric video ready 🎬").setContentText("“" + s.title + "” is in Movies/Pearl Lyrics");
            Intent watch = new Intent(Intent.ACTION_VIEW).setDataAndType(s.result, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            b.setContentIntent(PendingIntent.getActivity(this, 3, watch, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
            Intent share = Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, s.result).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share lyric video").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            b.addAction(new Notification.Action.Builder(null, "Watch", PendingIntent.getActivity(this, 3, watch, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT)).build());
            b.addAction(new Notification.Action.Builder(null, "Share", PendingIntent.getActivity(this, 4, share, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT)).build());
        }
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTE_DONE, b.build());
    }
}
