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
import io.github.vvb2060.packageinstaller.datamodel.DownloadProbe;
import io.github.vvb2060.packageinstaller.datamodel.DownloadTask;
import io.github.vvb2060.packageinstaller.datamodel.FileDataSource;
import io.github.vvb2060.packageinstaller.datamodel.ResumableDownload;
import io.github.vvb2060.packageinstaller.ui.Format;
import io.github.vvb2060.packageinstaller.ui.InstallResultActivity;

/**
 * 后台「下载 + 安装」：确认之后把 URL 或本地来源交给这里，通知栏显示进度与结果。
 * 网络来源用可续传下载，装完删除临时文件；失败保留 .part，通知里可以直接继续。
 */
public class InstallService extends Service {
    private static final String TAG = InstallService.class.getSimpleName();

    /**
     * 本进程里这个服务是否活着。进程被杀后静态字段自然回到 false，
     * 下载列表靠它判断「正在安装」是真是假。同一个进程里可能有多个服务
     * 实例前后接力，用计数而不是布尔值。
     */
    public static volatile int RUNNING;

    public static final String EXTRA_PATH = "path";
    public static final String EXTRA_URI = "uri";
    public static final String EXTRA_LABEL = "label";
    public static final String EXTRA_SET_INSTALLER = "set_installer";
    public static final String EXTRA_FULL = "full";
    public static final String EXTRA_TEMP = "temp";

    public static final String ACTION_DISMISS = "io.github.vvb2060.packageinstaller.DISMISS";
    public static final String ACTION_CANCEL = "io.github.vvb2060.packageinstaller.CANCEL";
    public static final String EXTRA_NOTIFICATION_ID = "notification_id";

    private static final String CHANNEL_ID = "install_status";
    private static final int PROGRESS_ID = 0x5101;
    private static final int RESULT_ID = 0x5102;

    private static volatile boolean cancelRequested;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public static void start(Context context, String path, String uri, String label,
                             boolean setInstaller, boolean full, boolean temp) {
        start(context, new Intent(context, InstallService.class)
            .putExtra(EXTRA_PATH, path)
            .putExtra(EXTRA_URI, uri)
            .putExtra(EXTRA_LABEL, label)
            .putExtra(EXTRA_SET_INSTALLER, setInstaller)
            .putExtra(EXTRA_FULL, full)
            .putExtra(EXTRA_TEMP, temp));
    }

    /** 从「未完成的下载」继续：只需要 URL，装完自然会问要不要装。 */
    public static void resume(Context context, String url, String label) {
        start(context, new Intent(context, InstallService.class)
            .putExtra(EXTRA_URI, url)
            .putExtra(EXTRA_LABEL, label)
            .putExtra(EXTRA_SET_INSTALLER, true)
            .putExtra(EXTRA_FULL, true)
            .putExtra(EXTRA_TEMP, true));
    }

    private static void start(Context context, Intent intent) {
        try {
            ContextCompat.startForegroundService(context, intent);
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
        RUNNING++;
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
        cancelRequested = false;
        startForeground(PROGRESS_ID, progressNotification(
            getString(R.string.notify_preparing), null, -1, true));
        executor.execute(() -> install(intent, startId));
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        RUNNING = Math.max(0, RUNNING - 1);
        executor.shutdownNow();
        super.onDestroy();
    }

    private void install(Intent intent, int startId) {
        var path = intent.getStringExtra(EXTRA_PATH);
        var uri = intent.getStringExtra(EXTRA_URI);
        var label = intent.getStringExtra(EXTRA_LABEL);
        var temporary = intent.getBooleanExtra(EXTRA_TEMP, false);
        var network = path == null && uri != null
            && (uri.startsWith("http://") || uri.startsWith("https://"));
        // 网络来源从第一秒就登记成下载任务，切后台/关界面后列表里也看得到
        final var taskId = network ? DownloadTask.idOf(uri) : null;
        Closeable source = null;
        try {
            Privilege.attach(getApplicationContext());

            if (network) {
                var taskLabel = label == null || label.isEmpty()
                    ? DownloadProbe.guess(uri).name : label;
                var task = new DownloadTask(taskId, uri, taskLabel,
                    DownloadProbe.mimeFromName(taskLabel), DownloadTask.STATE_ACTIVE);
                task.kind = DownloadTask.KIND_INSTALL;
                DownloadTask.upsert(this, task);

                updateProgress(getString(R.string.notify_downloading), label, 0,
                    getString(R.string.notify_download_starting));
                var lastReport = new long[]{0L};
                var file = ResumableDownload.download(this, uri,
                    (downloaded, total, speed) -> {
                        updateProgress(getString(R.string.notify_downloading), label,
                            total > 0 ? (int) (downloaded * 100 / total) : -1,
                            Format.size(downloaded)
                                + (total > 0 ? " / " + Format.size(total) : "")
                                + " · " + Format.speed(speed));
                        var now = System.currentTimeMillis();
                        if (now - lastReport[0] > 800) {
                            lastReport[0] = now;
                            DownloadTask.updateProgress(this, taskId, downloaded, total);
                        }
                    },
                    () -> cancelRequested);
                DownloadTask.setState(this, taskId, DownloadTask.STATE_INSTALLING,
                    null, null, file.length(), file.length());
                path = file.getAbsolutePath();
                temporary = true;
            }

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
            updateProgress(getString(R.string.notify_installing), name, -1, null);

            var full = intent.getBooleanExtra(EXTRA_FULL, true);
            if (!apk.isSplit()) full = true;

            final var finalPath = path;
            final var finalTemporary = temporary;
            final var finalNetwork = network;
            final var finalUri = uri;

            InstallRunner.run(this, apk, parser,
                intent.getBooleanExtra(EXTRA_SET_INSTALLER, true), full,
                new InstallRunner.Callback() {
                    private long lastTouch;

                    @Override
                    public void onProgress(int percent) {
                        updateProgress(getString(R.string.notify_installing), name,
                            percent > 100 ? -1 : percent, null);
                        // 写安装会话期间保持任务记录新鲜，列表里才不会变成「未完成」
                        if (taskId == null) return;
                        var now = System.currentTimeMillis();
                        if (now - lastTouch < 800) return;
                        lastTouch = now;
                        DownloadTask.touch(InstallService.this, taskId);
                    }

                    @Override
                    public void onSuccess() {
                        if (finalTemporary && finalPath != null) deleteQuietly(finalPath);
                        if (taskId != null) {
                            DownloadTask.setState(InstallService.this, taskId,
                                DownloadTask.STATE_DONE, null, null, -1, -1);
                        }
                        notifySuccess(name, apk.packageName);
                        finish(startId);
                    }

                    @Override
                    public void onFailure(String reason) {
                        if (taskId != null) {
                            DownloadTask.setState(InstallService.this, taskId,
                                DownloadTask.STATE_FAILED, null, reason, -1, -1);
                        }
                        notifyFailure(name, reason, finalNetwork ? finalUri : null,
                            finalTemporary ? finalPath : null);
                        finish(startId);
                    }
                });
        } catch (Throwable e) {
            Log.e(TAG, "install failed", e);
            var reason = cancelRequested ? getString(R.string.notify_cancelled) : e.toString();
            if (taskId != null) {
                // 取消 / 中断不写错误原因：列表里算「未完成」，可以点继续
                DownloadTask.setState(this, taskId, DownloadTask.STATE_FAILED, null,
                    cancelRequested ? null : reason, -1, -1);
            }
            if (cancelRequested) {
                notifyFailure(label == null ? getString(R.string.app_name) : label,
                    getString(R.string.notify_cancelled), network ? uri : null, null);
            } else {
                notifyFailure(label == null ? getString(R.string.app_name) : label, e.toString(),
                    network ? uri : null, temporary ? path : null);
            }
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

    private void updateProgress(String title, String label, int percent, String detail) {
        var manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(PROGRESS_ID, progressNotification(title, label, percent, false, detail));
        }
    }

    private Notification progressNotification(String title, String label, int percent,
                                              boolean cancel) {
        return progressNotification(title, label, percent, cancel, null);
    }

    private Notification progressNotification(String title, String label, int percent,
                                              boolean cancel, String detail) {
        var indeterminate = percent < 0 || percent > 100;
        var text = new StringBuilder();
        if (label != null) text.append(label);
        if (detail != null && !detail.isEmpty()) {
            if (text.length() > 0) text.append('\n');
            text.append(detail);
        }
        var builder = new Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_notification)
            .setContentTitle(title)
            .setContentText(text.toString())
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, indeterminate ? 0 : percent, indeterminate);
        if (cancel) {
            var cancelIntent = new Intent(this, NotificationActionReceiver.class)
                .setAction(ACTION_CANCEL);
            builder.addAction(new Notification.Action.Builder(
                R.drawable.ic_remove_circle, getString(R.string.notify_cancel),
                PendingIntent.getBroadcast(this, 3, cancelIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE)).build());
        }
        return builder.build();
    }

    private void notifySuccess(String label, String packageName) {
        var manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager == null) return;
        manager.cancel(PROGRESS_ID);

        var builder = new Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_notification)
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

    private void notifyFailure(String label, String reason, String resumeUrl, String tempPath) {
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
            .setSmallIcon(R.mipmap.ic_notification)
            .setContentTitle(getString(R.string.notify_failed_title))
            .setContentText(label)
            .setStyle(new Notification.BigTextStyle().bigText(
                label + "\n" + getString(R.string.notify_failed_open_hint)))
            .setContentIntent(contentIntent)
            .setAutoCancel(true);

        if (resumeUrl != null) {
            var resume = new Intent(this, InstallService.class)
                .putExtra(EXTRA_URI, resumeUrl)
                .putExtra(EXTRA_LABEL, label)
                .putExtra(EXTRA_SET_INSTALLER, true)
                .putExtra(EXTRA_FULL, true)
                .putExtra(EXTRA_TEMP, true);
            var resumeIntent = PendingIntent.getForegroundService(this, 4, resume,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            builder.addAction(new Notification.Action.Builder(
                R.drawable.ic_refresh, getString(R.string.notify_resume), resumeIntent).build());
        } else if (tempPath != null) {
            deleteQuietly(tempPath);
        }

        builder.addAction(new Notification.Action.Builder(
            R.drawable.ic_alert, getString(R.string.notify_failed_open), contentIntent).build());
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
