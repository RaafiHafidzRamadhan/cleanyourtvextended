package com.raafi.cyberhome;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

/**
 * Keeps the web remote alive while the person is in another app. Shown as a quiet foreground
 * notification, the URL is also available from the Web chip on Home.
 */
public class WebService extends Service {

    private static final String CHANNEL_ID = "web_remote";
    private static final int NOTIFICATION_ID = 4207;
    private static volatile WebService instance;

    static void start(Context context) {
        try {
            Intent intent = new Intent(context, WebService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Exception ignored) {
        }
    }

    static void refreshNotification(Context context) {
        WebService service = instance;
        if (service != null) {
            try {
                service.updateNotification();
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        createChannel();
        startForeground(NOTIFICATION_ID, buildNotification());
        WebRuntime.get(this).startServer();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        WebRuntime runtime = WebRuntime.get(this);
        if (!runtime.isRunning()) {
            runtime.startServer();
        }
        updateNotification();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        instance = null;
        WebRuntime.get(this).stopServer();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void updateNotification() {
        try {
            NotificationManager manager =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.notify(NOTIFICATION_ID, buildNotification());
            }
        } catch (Exception ignored) {
        }
    }

    private Notification buildNotification() {
        WebRuntime runtime = WebRuntime.get(this);
        String text;
        if (runtime.isRunning()) {
            text = runtime.getUrl() + "  \u00B7  PIN ada di chip Web";
        } else {
            String error = runtime.getLastError();
            text = "Server belum aktif" + (error == null || error.isEmpty() ? "" : " \u00B7 " + error);
        }

        Intent open = new Intent(this, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, open, flags);

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        return builder
                .setSmallIcon(R.drawable.ic_web)
                .setContentTitle("Kendali web aktif")
                .setContentText(text)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                "Kendali web", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Server remote untuk mengatur Home dari jaringan lokal");
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }
}
