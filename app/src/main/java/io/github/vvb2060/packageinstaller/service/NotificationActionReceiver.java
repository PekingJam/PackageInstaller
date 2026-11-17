package io.github.vvb2060.packageinstaller.service;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class NotificationActionReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        if (InstallService.ACTION_CANCEL.equals(intent.getAction())) {
            InstallService.requestCancel();
            return;
        }
        if (DownloadService.ACTION_CANCEL.equals(intent.getAction())) {
            DownloadService.requestCancel();
            return;
        }
        var id = intent.getIntExtra(InstallService.EXTRA_NOTIFICATION_ID, 0);
        var manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null && id != 0) manager.cancel(id);
    }
}
