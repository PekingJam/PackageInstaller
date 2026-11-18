package io.github.vvb2060.packageinstaller.core;

import android.Manifest;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager_rename;
import android.net.Uri;
import android.os.Build;
import android.system.Os;
import android.util.Log;
import android.util.LogPrinter;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

import io.github.vvb2060.packageinstaller.BuildConfig;
import io.github.vvb2060.packageinstaller.ui.InstallLaunch;

public class PreferredActivity {

    private static final String TAG = PreferredActivity.class.getSimpleName();
    private static final String PACKAGE_MIME_TYPE = "application/vnd.android.package-archive";

    private static void list(PackageManager pm, String packageName) {
        var preferredActivities = new ArrayList<ComponentName>();
        var intentFilters = new ArrayList<IntentFilter>();
        pm.getPreferredActivities(intentFilters, preferredActivities, packageName);
        Log.d(TAG, packageName + " have " + preferredActivities.size()
            + " number of activities in preferred list");
        for (int index = 0; index < preferredActivities.size(); index++) {
            Log.d(TAG, "-Preferred activity: " + preferredActivities.get(index).getClassName());
            intentFilters.get(index).dump(new LogPrinter(Log.DEBUG, TAG), "--");
        }
    }

    private static Intent makeIntent(String action) {
        var uri = new Uri.Builder()
            .scheme(ContentResolver.SCHEME_CONTENT)
            .authority(BuildConfig.APPLICATION_ID)
            .path("base.apk")
            .build();
        return new Intent(action)
            .addCategory(Intent.CATEGORY_DEFAULT)
            .setDataAndType(uri, PACKAGE_MIME_TYPE);
    }

    @Nullable
    public static String getSystemInstaller() {
        var pm = BinderWrapper.pm;
        var intent = makeIntent(Intent.ACTION_INSTALL_PACKAGE);
        var matches = pm.queryIntentActivities(intent, PackageManager.MATCH_SYSTEM_ONLY);
        if (matches.isEmpty()) return null;
        return matches.get(0).activityInfo.packageName;
    }

    private static boolean check(PackageManager pm, String action) {
        var intent = makeIntent(action);
        var info = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);
        if (info == null) return false;
        var packageName = info.activityInfo.packageName;
        var isSelf = packageName.equals(BuildConfig.APPLICATION_ID);
        var isSystem = packageName.equals("android");
        var isShizukuUser = Os.getuid() / 100000 == 0;

        if (isShizukuUser && !isSelf && !isSystem) {
            Log.v(TAG, "Clear preferred activity for " + info.activityInfo.name);
            pm.clearPackagePreferredActivities(packageName);
        }

        return isSelf;
    }

    private static void add(PackageManager pm, String action) {
        int flags = PackageManager.MATCH_DEFAULT_ONLY;
        var intent = makeIntent(action);
        var infos = pm.queryIntentActivities(intent, flags);
        var set = new ComponentName[infos.size()];
        for (int i = 0, size = infos.size(); i < size; i++) {
            var info = infos.get(i);
            var activityInfo = info.activityInfo;
            var componentName = new ComponentName(activityInfo.packageName, activityInfo.name);
            set[i] = componentName;
        }
        IntentFilter filter;
        try {
            filter = new IntentFilter(action, PACKAGE_MIME_TYPE);
            filter.addCategory(Intent.CATEGORY_DEFAULT);
        } catch (IntentFilter.MalformedMimeTypeException e) {
            throw new RuntimeException(e);
        }
        var activity = new ComponentName(BuildConfig.APPLICATION_ID, InstallLaunch.class.getName());
        int match = IntentFilter.MATCH_CATEGORY_TYPE | IntentFilter.MATCH_ADJUSTMENT_MASK;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            var rename = (PackageManager_rename) (Object) pm;
            rename.addUniquePreferredActivity(filter, match, set, activity);
        } else {
            pm.addPreferredActivity(filter, match, set, activity);
        }
    }

    private static Uri dummyUri() {
        return new Uri.Builder()
            .scheme(ContentResolver.SCHEME_CONTENT)
            .authority(BuildConfig.APPLICATION_ID)
            .path("base.apk")
            .build();
    }

    /** 当前能处理 APK 的应用组件集合（Root 子进程只负责按这个集合写偏好）。 */
    private static ComponentName[] matchingComponents(PackageManager pm) {
        var intent = new Intent(Intent.ACTION_VIEW)
            .addCategory(Intent.CATEGORY_DEFAULT)
            .setDataAndType(dummyUri(), PACKAGE_MIME_TYPE);
        var infos = pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY);
        var list = new ArrayList<ComponentName>();
        for (var info : infos) {
            var activityInfo = info.activityInfo;
            if (activityInfo == null) continue;
            list.add(new ComponentName(activityInfo.packageName, activityInfo.name));
        }
        return list.toArray(new ComponentName[0]);
    }

    /**
     * Root 模式：pm / cmd package 没有 add-preferred-activity，只能起一个 app_process
     * 子进程（uid 0）直接调 IPackageManager。返回 null 表示成功。
     */
    public static String setViaRoot(android.content.Context context, PackageManager pm) {
        var activity = new ComponentName(BuildConfig.APPLICATION_ID, InstallLaunch.class.getName());
        var set = matchingComponents(pm);
        if (set.length == 0) return "找不到可用于 APK 的应用组件";

        var args = new StringBuilder();
        args.append("default ").append(Os.getuid() / 100000).append(' ');
        args.append(activity.flattenToString());
        for (var component : set) {
            args.append(' ').append(component.flattenToString());
        }

        var command = "/system/bin/app_process -Djava.class.path="
            + RootShell.quote(context.getPackageCodePath())
            + " /system/bin --nice-name=pi-default "
            + RootEntry.class.getName() + " " + args;
        var result = RootShell.exec(command, 60_000);
        if (result.output.contains("OK")) return null;
        return result.text();
    }

    /** 当前是不是本应用在接 APK 的默认打开方式。 */
    public static boolean isDefaultInstaller(android.content.Context context) {
        try {
            var intent = new Intent(Intent.ACTION_VIEW)
                .addCategory(Intent.CATEGORY_DEFAULT)
                .setDataAndType(dummyUri(), PACKAGE_MIME_TYPE);
            var info = context.getPackageManager()
                .resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);
            return info != null && info.activityInfo != null
                && BuildConfig.APPLICATION_ID.equals(info.activityInfo.packageName);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * 取消默认：清掉本应用的优先活动映射就能回到系统原来的处理方式。
     * clearPackagePreferredActivities 对自身包是公开可用的，不需要特权。
     */
    public static String clearDefault(android.content.Context context) {
        try {
            context.getPackageManager()
                .clearPackagePreferredActivities(BuildConfig.APPLICATION_ID);
            return null;
        } catch (RuntimeException e) {
            return e.toString();
        }
    }

    public static void set(PackageManager rawPm) {
        var pm = BinderWrapper.pm;
        if (check(pm, Intent.ACTION_VIEW) && check(pm, Intent.ACTION_INSTALL_PACKAGE)) {
            return;
        }

        rawPm.clearPackagePreferredActivities(BuildConfig.APPLICATION_ID);
        add(pm, Intent.ACTION_VIEW);
        add(pm, Intent.ACTION_INSTALL_PACKAGE);
        Log.v(TAG, "Preferred activities set for " + BuildConfig.APPLICATION_ID);

        if (BuildConfig.DEBUG) {
            list(pm, getSystemInstaller());
        }
    }
}
