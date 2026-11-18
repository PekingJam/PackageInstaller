package io.github.vvb2060.packageinstaller.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import androidx.core.content.ContextCompat;

import io.github.vvb2060.packageinstaller.R;

/** 常驻的前台服务，用来「占住」进程，配合开机自启和 root 守护脚本做保活。 */
public class KeepAliveService extends Service {
    private static final String TAG = KeepAliveService.class.getSimpleName();
    private static final String CHANNEL_ID = "keep_alive";
    private static final int NOTIFICATION_ID = 0x5301;

    public static void start(Context context) {
        try {
            ContextCompat.startForegroundService(context,
                new Intent(context, KeepAliveService.class));
        } catch (RuntimeException e) {
            Log.w(TAG, "start failed", e);
        }
    }

    public static void stop(Context context) {
        try {
            context.stopService(new Intent(context, KeepAliveService.class));
        } catch (RuntimeException ignored) {
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        var manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null && manager.getNotificationChannel(CHANNEL_ID) == null) {
            var channel = new NotificationChannel(CHANNEL_ID,
                getString(R.string.keepalive_channel), NotificationManager.IMPORTANCE_MIN);
            channel.setShowBadge(false);
            manager.createNotificationChannel(channel);
        }
        startForeground(NOTIFICATION_ID, notification());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification notification() {
        return new Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_notification)
            .setContentTitle(getString(R.string.keepalive_notification_title))
            .setContentText(getString(R.string.keepalive_notification_text))
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build();
    }
}
