package io.github.vvb2060.packageinstaller.core;

import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.IBinder;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 以 root 身份用 app_process 运行的入口（不是应用进程）。
 *
 *   su -c "app_process -Djava.class.path=<apk> /system/bin --nice-name=pi-default \
 *          io.github.vvb2060.packageinstaller.core.RootEntry default <userId> <activity> <set...>"
 *
 * 只做 CLI 做不到的一件事：把本应用设成 APK 的默认打开方式。
 * 设置完直接退出，不需要任何 IPC —— 因此不用把 InstallerX 那套 binder 回传搬过来。
 */
public final class RootEntry {
    private static final String MIME = "application/vnd.android.package-archive";

    private RootEntry() {
    }

    public static void main(String[] args) {
        try {
            if (args.length >= 3 && "default".equals(args[0])) {
                System.exit(applyDefault(args) ? 0 : 3);
            }
            System.out.println("ERR unknown command");
            System.exit(2);
        } catch (Throwable e) {
            System.out.println("ERR " + e);
            e.printStackTrace(System.out);
            System.exit(1);
        }
    }

    private static boolean applyDefault(String[] args) throws Exception {
        int userId;
        ComponentName activity;
        try {
            userId = Integer.parseInt(args[1]);
            activity = ComponentName.unflattenFromString(args[2]);
        } catch (RuntimeException e) {
            System.out.println("ERR bad args: " + e);
            return false;
        }
        if (activity == null) {
            System.out.println("ERR bad activity");
            return false;
        }

        var set = new ArrayList<ComponentName>();
        for (int i = 3; i < args.length; i++) {
            var component = ComponentName.unflattenFromString(args[i]);
            if (component != null) set.add(component);
        }

        var binder = serviceManager("package");
        if (binder == null) {
            System.out.println("ERR no package service");
            return false;
        }
        var stub = Class.forName("android.content.pm.IPackageManager$Stub");
        var pm = stub.getMethod("asInterface", IBinder.class).invoke(null, binder);
        if (pm == null) {
            System.out.println("ERR no package manager");
            return false;
        }

        var method = findAddPreferred(pm.getClass());
        if (method == null) {
            System.out.println("ERR no addPreferredActivity");
            return false;
        }
        var parameters = method.getParameterTypes();
        var match = IntentFilter.MATCH_CATEGORY_TYPE | IntentFilter.MATCH_ADJUSTMENT_MASK;
        var components = set.toArray(new ComponentName[0]);

        var applied = 0;
        for (var action : new String[]{Intent.ACTION_VIEW, Intent.ACTION_INSTALL_PACKAGE}) {
            var filter = new IntentFilter(action, MIME);
            filter.addCategory(Intent.CATEGORY_DEFAULT);
            if (parameters.length == 6) {
                method.invoke(pm, filter, match, components, activity, userId, false);
            } else {
                method.invoke(pm, filter, match, components, activity, userId);
            }
            applied++;
        }
        System.out.println("OK " + applied);
        return true;
    }

    /** API 35 之后 addPreferredActivity 多了一个 boolean 参数，按签名长度适配。 */
    private static Method findAddPreferred(Class<?> type) {
        for (var method : type.getMethods()) {
            if (!"addPreferredActivity".equals(method.getName())) continue;
            var count = method.getParameterCount();
            if (count == 5 || count == 6) return method;
        }
        return null;
    }

    private static IBinder serviceManager(String name) throws Exception {
        var type = Class.forName("android.os.ServiceManager");
        var getService = type.getDeclaredMethod("getService", String.class);
        getService.setAccessible(true);
        return (IBinder) getService.invoke(null, name);
    }
}
