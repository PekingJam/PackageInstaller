package io.github.vvb2060.packageinstaller.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.core.content.ContextCompat;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.github.vvb2060.packageinstaller.R;
import io.github.vvb2060.packageinstaller.apk.ZipParser;
import io.github.vvb2060.packageinstaller.core.InstallRunner;
import io.github.vvb2060.packageinstaller.core.Privilege;
import io.github.vvb2060.packageinstaller.datamodel.DataSource;
import io.github.vvb2060.packageinstaller.datamodel.FileDataSource;
import io.github.vvb2060.packageinstaller.ui.InstallResultActivity;

/**
 * 后台安装：确认之后把写包/提交丢到这里跑，通知栏显示进度与结果。
 * 界面可以关掉，装完在通知里直接打开应用。
 */
public class InstallService extends Service {
    private static final String TAG = InstallService.class.getSimpleName();

    public static final String EXTRA_PATH = "path";
    public static final String EXTRA_URI = "uri";
    public static final String EXTRA_LABEL = "label";
    public static final String EXTRA_SET_INSTALLER = "set_installer";
    public static final String EXTRA_FULL = "full";
    public static final String EXTRA_TEMP = "temp";

    public static final String ACTION_DISMISS = "io.github.vvb2060.packageinstaller.DISMISS";
    public static final String EXTRA_NOTIFICATION_ID = "notification_id";

    private static final String CHANNEL_ID = "install_status";
    private static final int PROGRESS_ID = 0x5101;
    private static final int RESULT_ID = 0x5102;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public static void start(Context context, String path, String uri, String label,
                             boolean setInstaller, boolean full, boolean temp) {
        var intent = new Intent(context, InstallService.class)
            .putExtra(EXTRA_PATH, path)
            .putExtra(EXTRA_URI, uri)
            .putExtra(EXTRA_LABEL, label)
            .putExtra(EXTRA_SET_INSTALLER, setInstaller)
            .putExtra(EXTRA_FULL, full)
            .putExtra(EXTRA_TEMP, temp);
        try {
            ContextCompat.startForegroundService(context, intent);
        } catch (RuntimeException e) {
            Log.e(TAG, "start failed", e);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        var manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null && manager.getNotificationChannel(CHANNEL_ID) == null) {
            var channel = new NotificationChannel(CHANNEL_ID,
                getString(R.string.notify_channel_name), NotificationManager.IMPORTANCE_DEFAULT);
            channel.setShowBadge(false);
            manager.createNotificationChannel(channel);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        startForeground(PROGRESS_ID, progressNotification(getString(R.string.notify_preparing), -1));
        executor.execute(() -> install(intent, startId));
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private void install(Intent intent, int startId) {
        var path = intent.getStringExtra(EXTRA_PATH);
        var uri = intent.getStringExtra(EXTRA_URI);
        var label = intent.getStringExtra(EXTRA_LABEL);
        var temporary = intent.getBooleanExtra(EXTRA_TEMP, false);
        Closeable source = null;
        try {
            Privilege.attach(getApplicationContext());
            DataSource dataSource;
            if (path != null) {
                var pfd = ParcelFileDescriptor.open(new File(path),
                    ParcelFileDescriptor.MODE_READ_ONLY);
                source = pfd;
                dataSource = FileDataSource.asDataSource(pfd);
            } else if (uri != null) {
                var afd = getContentResolver().openAssetFileDescriptor(Uri.parse(uri), "r");
                if (afd == null) throw new IOException("无法打开安装包");
                source = afd;
                dataSource = FileDataSource.asDataSource(afd);
            } else {
                throw new IOException("没有安装包来源");
            }

            var parser = new ZipParser(dataSource);
            var apk = parser.parse();
            var name = apk.getLabel();
            updateProgress(name, -1);

            InstallRunner.run(this, apk, parser,
                intent.getBooleanExtra(EXTRA_SET_INSTALLER, true),
                intent.getBooleanExtra(EXTRA_FULL, true),
                new InstallRunner.Callback() {
                    @Override
                    public void onProgress(int percent) {
                        updateProgress(name, percent);
                    }

                    @Override
                    public void onSuccess() {
                        if (temporary && path != null) deleteQuietly(path);
                        notifySuccess(name, apk.packageName);
                        finish(startId);
                    }

                    @Override
                    public void onFailure(String reason) {
                        notifyFailure(name, reason);
                        finish(startId);
                    }
                });
        } catch (Throwable e) {
            Log.e(TAG, "install failed", e);
            notifyFailure(label == null ? getString(R.string.app_name) : label, e.toString());
            finish(startId);
        } finally {
            if (source != null) {
                try {
                    source.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private void updateProgress(String label, int percent) {
        var manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(PROGRESS_ID, progressNotification(label, percent));
        }
    }

    private Notification progressNotification(String label, int percent) {
        var indeterminate = percent < 0 || percent > 100;
        return new Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notify_installing))
            .setContentText(label)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, indeterminate ? 0 : percent, indeterminate)
            .build();
    }

    private void notifySuccess(String label, String packageName) {
        var manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager == null) return;
        manager.cancel(PROGRESS_ID);

        var builder = new Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notify_success_title))
            .setContentText(label)
            .setAutoCancel(true);

        var open = getPackageManager().getLaunchIntentForPackage(packageName);
        if (open != null) {
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            var openIntent = PendingIntent.getActivity(this, 1, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            builder.addAction(new Notification.Action.Builder(
                R.drawable.ic_play, getString(R.string.notify_open), openIntent).build());
            builder.setContentIntent(openIntent);
        }
        builder.addAction(new Notification.Action.Builder(
            R.drawable.ic_remove_circle, getString(R.string.notify_close), dismissIntent(RESULT_ID))
            .build());
        manager.notify(RESULT_ID, builder.build());
    }

    private void notifyFailure(String label, String reason) {
        var manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager == null) return;
        manager.cancel(PROGRESS_ID);

        var intent = new Intent(this, InstallResultActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(InstallResultActivity.EXTRA_LABEL, label)
            .putExtra(InstallResultActivity.EXTRA_REASON, reason);
        var contentIntent = PendingIntent.getActivity(this, 2, intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        var builder = new Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notify_failed_title))
            .setContentText(label)
            .setStyle(new Notification.BigTextStyle().bigText(
                label + "\n" + getString(R.string.notify_failed_open_hint)))
            .setContentIntent(contentIntent)
            .setAutoCancel(true);
        builder.addAction(new Notification.Action.Builder(
            R.drawable.ic_alert, getString(R.string.notify_failed_open),
            contentIntent).build());
        builder.addAction(new Notification.Action.Builder(
            R.drawable.ic_remove_circle, getString(R.string.notify_close), dismissIntent(RESULT_ID))
            .build());
        manager.notify(RESULT_ID, builder.build());
    }

    private PendingIntent dismissIntent(int notificationId) {
        var intent = new Intent(this, NotificationActionReceiver.class)
            .setAction(ACTION_DISMISS)
            .putExtra(EXTRA_NOTIFICATION_ID, notificationId);
        return PendingIntent.getBroadcast(this, notificationId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void finish(int startId) {
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf(startId);
    }

    private void deleteQuietly(String path) {
        try {
            var file = new File(path);
            if (file.exists() && !file.delete()) Log.w(TAG, "delete failed: " + path);
        } catch (RuntimeException ignored) {
        }
    }
}
