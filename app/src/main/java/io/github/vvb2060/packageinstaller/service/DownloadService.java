package io.github.vvb2060.packageinstaller.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import androidx.core.content.ContextCompat;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.github.vvb2060.packageinstaller.R;
import io.github.vvb2060.packageinstaller.datamodel.DownloadProbe;
import io.github.vvb2060.packageinstaller.datamodel.DownloadStore;
import io.github.vvb2060.packageinstaller.datamodel.DownloadTask;
import io.github.vvb2060.packageinstaller.datamodel.ResumableDownload;
import io.github.vvb2060.packageinstaller.ui.Format;

/**
 * 普通文件下载：APK 以外的文件走这里，下完放进系统 Download 目录。
 * 复用 ResumableDownload 的 .part 续传，通知栏显示进度/速度，失败可继续。
 */
public class DownloadService extends Service {
    private static final String TAG = DownloadService.class.getSimpleName();

    public static final String EXTRA_URL = "url";
    public static final String EXTRA_NAME = "name";
    public static final String EXTRA_MIME = "mime";
    public static final String EXTRA_SIZE = "size";

    public static final String ACTION_CANCEL = "io.github.vvb2060.packageinstaller.DOWNLOAD_CANCEL";
    public static final String EXTRA_NOTIFICATION_ID = "notification_id";

    private static final String CHANNEL_ID = "download_status";
    private static final int PROGRESS_ID = 0x5201;
    private static final int RESULT_ID = 0x5202;

    private static volatile boolean cancelRequested;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public static void start(Context context, String url, String name, String mime, long size) {
        try {
            ContextCompat.startForegroundService(context, new Intent(context, DownloadService.class)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_NAME, name)
                .putExtra(EXTRA_MIME, mime)
                .putExtra(EXTRA_SIZE, size));
        } catch (RuntimeException e) {
            Log.e(TAG, "start failed", e);
        }
    }

    public static void requestCancel() {
        cancelRequested = true;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        var manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null && manager.getNotificationChannel(CHANNEL_ID) == null) {
            var channel = new NotificationChannel(CHANNEL_ID,
                getString(R.string.notify_channel_download), NotificationManager.IMPORTANCE_LOW);
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
        cancelRequested = false;
        startForeground(PROGRESS_ID, notification(getString(R.string.notify_downloading),
            intent.getStringExtra(EXTRA_NAME), -1, null, true));
        executor.execute(() -> download(intent, startId));
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private void download(Intent intent, int startId) {
        var url = intent.getStringExtra(EXTRA_URL);
        var name = intent.getStringExtra(EXTRA_NAME);
        var mime = intent.getStringExtra(EXTRA_MIME);
        var size = intent.getLongExtra(EXTRA_SIZE, -1);
        if (url == null) {
            stopSelf(startId);
            return;
        }
        if (name == null || name.isEmpty()) name = DownloadProbe.guess(url).name;
        if (mime == null || mime.isEmpty()) mime = DownloadProbe.mimeFromName(name);

        var id = DownloadTask.idOf(url);
        var task = new DownloadTask(id, url, name, mime, DownloadTask.STATE_ACTIVE);
        task.size = size;
        DownloadTask.upsert(this, task);

        final var displayName = name;
        final var displayMime = mime;
        var lastReport = new long[]{0L};

        try {
            var file = ResumableDownload.download(this, url, (downloaded, total, speed) -> {
                updateProgress(getString(R.string.notify_downloading), displayName,
                    total > 0 ? (int) (downloaded * 100 / total) : -1,
                    Format.size(downloaded)
                        + (total > 0 ? " / " + Format.size(total) : "")
                        + " · " + Format.speed(speed));
                var now = System.currentTimeMillis();
                if (now - lastReport[0] > 800) {
                    lastReport[0] = now;
                    DownloadTask.updateProgress(this, id, downloaded, total);
                }
            }, () -> cancelRequested);

            updateProgress(getString(R.string.notify_download_saving), displayName, -1, null);
            var completed = file.length();
            var uri = DownloadStore.save(this, file, displayName, displayMime);
            deleteQuietly(file);

            DownloadTask.setState(this, id, DownloadTask.STATE_DONE, uri.toString(), null,
                completed, completed);
            notifySuccess(displayName, uri.toString(), displayMime);
            finish(startId);
        } catch (Throwable e) {
            Log.e(TAG, "download failed", e);
            var cancelled = cancelRequested;
            var reason = cancelled ? getString(R.string.notify_cancelled) : String.valueOf(e);
            DownloadTask.setState(this, id, DownloadTask.STATE_FAILED, null, reason, -1, -1);
            notifyFailure(displayName, reason, url, displayMime);
            finish(startId);
        }
    }

    private void updateProgress(String title, String label, int percent, String detail) {
        var manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(PROGRESS_ID, notification(title, label, percent, detail, false));
        }
    }

    private Notification notification(String title, String label, int percent, String detail,
                                      boolean cancel) {
        var indeterminate = percent < 0 || percent > 100;
        var text = new StringBuilder();
        if (label != null) text.append(label);
        if (detail != null && !detail.isEmpty()) {
            if (text.length() > 0) text.append('\n');
            text.append(detail);
        }
        var builder = new Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(title)
            .setContentText(text.toString())
            .setStyle(new Notification.BigTextStyle().bigText(text.toString()))
            .setOnlyAlertOnce(true)
            .setOngoing(!cancel)
            .setProgress(100, indeterminate ? 0 : percent, indeterminate);
        if (cancel) {
            var cancelIntent = new Intent(this, NotificationActionReceiver.class)
                .setAction(ACTION_CANCEL);
            builder.addAction(new Notification.Action.Builder(
                R.drawable.ic_remove_circle, getString(R.string.notify_cancel),
                PendingIntent.getBroadcast(this, 13, cancelIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE)).build());
        }
        return builder.build();
    }

    private void notifySuccess(String label, String uri, String mime) {
        var manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager == null) return;
        manager.cancel(PROGRESS_ID);

        var builder = new Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(getString(R.string.notify_download_done_title))
            .setContentText(getString(R.string.notify_download_saved, DownloadStore.displayPath(label)))
            .setStyle(new Notification.BigTextStyle().bigText(label + "\n"
                + getString(R.string.notify_download_saved, DownloadStore.displayPath(label))))
            .setAutoCancel(true);

        var open = DownloadStore.openIntent(this, uri, mime);
        if (open != null) {
            var openIntent = PendingIntent.getActivity(this, 11, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            builder.addAction(new Notification.Action.Builder(
                R.drawable.ic_play, getString(R.string.notify_open), openIntent).build());
            builder.setContentIntent(openIntent);
        }
        builder.addAction(new Notification.Action.Builder(
            R.drawable.ic_remove_circle, getString(R.string.notify_close), dismissIntent())
            .build());
        manager.notify(RESULT_ID, builder.build());
    }

    private void notifyFailure(String label, String reason, String url, String mime) {
        var manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager == null) return;
        manager.cancel(PROGRESS_ID);

        var builder = new Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(getString(R.string.notify_download_failed_title))
            .setContentText(label)
            .setStyle(new Notification.BigTextStyle().bigText(label + "\n" + reason))
            .setAutoCancel(true);

        var resume = new Intent(this, DownloadService.class)
            .putExtra(EXTRA_URL, url)
            .putExtra(EXTRA_NAME, label)
            .putExtra(EXTRA_MIME, mime);
        builder.addAction(new Notification.Action.Builder(
            R.drawable.ic_refresh, getString(R.string.notify_resume),
            PendingIntent.getForegroundService(this, 12, resume,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE)).build());
        builder.addAction(new Notification.Action.Builder(
            R.drawable.ic_remove_circle, getString(R.string.notify_close), dismissIntent())
            .build());
        manager.notify(RESULT_ID, builder.build());
    }

    private PendingIntent dismissIntent() {
        var intent = new Intent(this, NotificationActionReceiver.class)
            .setAction(InstallService.ACTION_DISMISS)
            .putExtra(EXTRA_NOTIFICATION_ID, RESULT_ID);
        return PendingIntent.getBroadcast(this, RESULT_ID, intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void finish(int startId) {
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf(startId);
    }

    private void deleteQuietly(File file) {
        try {
            if (file.exists() && !file.delete()) Log.w(TAG, "delete failed: " + file);
        } catch (RuntimeException ignored) {
        }
    }
}
