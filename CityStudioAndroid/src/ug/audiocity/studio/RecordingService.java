package ug.audiocity.studio;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/** Keeps a microphone take running while the app is minimised or the screen is off, with a Stop button in the notification. */
public final class RecordingService extends Service {
    static final String STOP = "ug.audiocity.studio.STOP_RECORDING";
    static final String CHANNEL = "recording";
    static final int ID = 7101;

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && STOP.equals(intent.getAction())) {
            MainActivity.stopRecordingFromNotification();
            stopSelf();
            return START_NOT_STICKY;
        }
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null && manager.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "Recording", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Shown while Audio City Studio is recording");
            manager.createNotificationChannel(channel);
        }
        Intent open = new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        PendingIntent openApp = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, RecordingService.class).setAction(STOP), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        String track = intent == null ? null : intent.getStringExtra("track");
        Notification notification = new Notification.Builder(this, CHANNEL)
            .setSmallIcon(getApplicationInfo().icon)
            .setContentTitle("Recording" + (track == null ? "" : " · " + track))
            .setContentText("Audio City Studio keeps recording while minimised. Tap to return.")
            .setUsesChronometer(true).setWhen(System.currentTimeMillis()).setShowWhen(true)
            .setOngoing(true).setCategory(Notification.CATEGORY_SERVICE)
            .setContentIntent(openApp)
            .addAction(new Notification.Action.Builder(null, "Stop recording", stop).build())
            .build();
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            else startForeground(ID, notification);
        } catch (RuntimeException e) {
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Override public void onTaskRemoved(Intent rootIntent) {
        MainActivity.stopRecordingFromNotification();
        stopSelf();
    }
}
