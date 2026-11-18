package io.github.vvb2060.packageinstaller.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import io.github.vvb2060.packageinstaller.core.KeepAlive;
import io.github.vvb2060.packageinstaller.core.Privilege;
import io.github.vvb2060.packageinstaller.service.KeepAliveService;

/** 开机自启：只做用户明确开过的保活，不偷偷干活。 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!KeepAlive.isEnabled(context)) return;
        KeepAliveService.start(context);
        var pending = goAsync();
        new Thread(() -> {
            try {
                Privilege.attach(context);
                if (Privilege.modeOrUnknown() == Privilege.MODE_ROOT) {
                    KeepAlive.applyRootTweaks(context);
                    KeepAlive.startRootWatchdog(context);
                }
            } finally {
                pending.finish();
            }
        }, "boot-keepalive").start();
    }
}
