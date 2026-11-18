package io.github.vvb2060.packageinstaller.core;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.core.graphics.drawable.DrawableKt;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import moe.shizuku.server.IShizukuService;

/**
 * 应用列表数据源。
 *
 * MIUI / HyperOS 的「获取应用列表」隐私开关会盖过 QUERY_ALL_PACKAGES，导致
 * getInstalledPackages() 只返回自己。特权模式下改用 pm list packages（root 走 su，
 * Shizuku 走 shell 进程），再按 APK 路径解出名称与图标，绕开这个开关。
 */
public final class AppListSource {
    private static final String TAG = AppListSource.class.getSimpleName();
    private static final String SPLIT = "__PI_SPLIT__";
    private static final String PM = RootInstaller.PM;
    /** 图标按 42dp 解，和列表里 ImageView 的实际尺寸对齐，比 48dp 省三成像素。 */
    private static final int ICON_DP = 42;

    public static final class Entry {
        public final String packageName;
        public final String apkPath;
        public final boolean system;
        public boolean enabled;
        public boolean installed;
        public long versionCode;
        public String versionName;
        public String label;

        Entry(String packageName, String apkPath, boolean system, boolean enabled,
              boolean installed, long versionCode, String versionName, String label) {
            this.packageName = packageName;
            this.apkPath = apkPath;
            this.system = system;
            this.enabled = enabled;
            this.installed = installed;
            this.versionCode = versionCode;
            this.versionName = versionName;
            this.label = label;
        }
    }

    private AppListSource() {
    }

    /**
     * 只取包名与状态，不解析图标/名称——先让列表出来，再逐条补细节。
     */
    public static List<Entry> names(Context context) {
        var mode = Privilege.mode();
        if (mode != Privilege.MODE_NONE) {
            try {
                var list = fromShell(context);
                if (!list.isEmpty()) return list;
            } catch (Exception | LinkageError e) {
                Log.w(TAG, "shell listing failed", e);
            }
        }
        return fromPackageManager(context);
    }

    /**
     * 补齐名称与版本，同时解出图标位图返回（拿不到返回 null）。
     * 只打开一次 APK，两个信息一起取；图标交给调用方缓存。
     */
    public static Bitmap details(Context context, Entry entry) {
        var pm = context.getPackageManager();
        if (entry.apkPath == null) return null;
        try {
            var info = pm.getPackageArchiveInfo(entry.apkPath, 0);
            if (info == null || info.applicationInfo == null) return null;
            var app = info.applicationInfo;
            app.sourceDir = entry.apkPath;
            app.publicSourceDir = entry.apkPath;
            var label = app.loadLabel(pm);
            if (label != null && label.length() > 0) entry.label = label.toString();
            if (info.versionName != null) entry.versionName = info.versionName;
            entry.versionCode = info.getLongVersionCode();
            var drawable = app.loadIcon(pm);
            if (drawable == null) drawable = pm.getDefaultActivityIcon();
            return toBitmap(drawable);
        } catch (RuntimeException | LinkageError e) {
            Log.w(TAG, "archive info failed: " + entry.apkPath, e);
            return null;
        }
    }

    private static Bitmap toBitmap(Drawable drawable) {
        if (drawable == null) return null;
        try {
            var resources = Resources.getSystem();
            var size = (int) (ICON_DP * resources.getDisplayMetrics().density);
            return DrawableKt.toBitmap(drawable, size, size, null);
        } catch (RuntimeException | LinkageError | OutOfMemoryError e) {
            return null;
        }
    }

    public static List<Entry> load(Context context) {
        var list = names(context);
        for (var entry : list) details(context, entry);
        return list;
    }

    // ---------------- 特权模式：pm list packages ----------------

    private static List<Entry> fromShell(Context context) throws IOException {
        var script = String.join("; ",
            PM + " list packages -f",
            "echo " + SPLIT,
            PM + " list packages -f -u",
            "echo " + SPLIT,
            PM + " list packages -d",
            "echo " + SPLIT,
            PM + " list packages -s -u");
        var output = execShell(script);
        var parts = output.split(Pattern.quote(SPLIT));
        if (parts.length < 4) throw new IOException("unexpected pm output: " + output);

        var installed = names(parts[0]);
        var all = withPaths(parts[1]);
        var disabled = names(parts[2]);
        var system = names(parts[3]);

        var list = new ArrayList<Entry>(all.size());
        for (var item : all.entrySet()) {
            var packageName = item.getKey();
            list.add(new Entry(packageName, item.getValue(),
                system.contains(packageName),
                !disabled.contains(packageName),
                installed.contains(packageName),
                0L, "", packageName));
        }
        return list;
    }

    private static ApplicationInfo archiveInfo(PackageManager pm, Entry entry) {
        if (entry.apkPath == null) return null;
        try {
            var info = pm.getPackageArchiveInfo(entry.apkPath, 0);
            if (info == null || info.applicationInfo == null) return null;
            var app = info.applicationInfo;
            app.sourceDir = entry.apkPath;
            app.publicSourceDir = entry.apkPath;
            return app;
        } catch (RuntimeException | LinkageError e) {
            Log.w(TAG, "archive open failed: " + entry.apkPath, e);
            return null;
        }
    }

    /**
     * 按需取一个应用的图标位图。调用方负责缓存与回收 —— 这里不往 Entry 上挂，
     * 否则几百个图标会跟着列表一起常驻。
     */
    public static Bitmap loadIcon(Context context, Entry entry) {
        var pm = context.getPackageManager();
        Drawable drawable = null;
        try {
            var app = archiveInfo(pm, entry);
            if (app != null) drawable = app.loadIcon(pm);
            if (drawable == null) drawable = pm.getDefaultActivityIcon();
            if (drawable == null) return null;
            var resources = Resources.getSystem();
            var size = (int) (ICON_DP * resources.getDisplayMetrics().density);
            return DrawableKt.toBitmap(drawable, size, size, null);
        } catch (RuntimeException | LinkageError | OutOfMemoryError e) {
            Log.w(TAG, "load icon failed: " + entry.packageName, e);
            return null;
        }
    }

    private static Set<String> names(String section) {
        var set = new LinkedHashSet<String>();
        for (var line : section.split("\n")) {
            var value = line.trim();
            if (value.startsWith("package:")) value = value.substring("package:".length());
            if (value.isEmpty()) continue;
            var index = value.lastIndexOf('=');
            set.add(index >= 0 ? value.substring(index + 1) : value);
        }
        return set;
    }

    private static LinkedHashMap<String, String> withPaths(String section) {
        var map = new LinkedHashMap<String, String>();
        for (var line : section.split("\n")) {
            var value = line.trim();
            if (value.isEmpty()) continue;
            if (value.startsWith("package:")) value = value.substring("package:".length());
            var index = value.lastIndexOf('=');
            if (index < 0) {
                map.put(value, null);
            } else {
                map.put(value.substring(index + 1), value.substring(0, index));
            }
        }
        return map;
    }

    private static String execShell(String script) throws IOException {
        if (Privilege.mode() == Privilege.MODE_ROOT) {
            var result = RootShell.exec(script, 180_000);
            if (result.code != 0 || result.output.isEmpty()) throw new IOException(result.text());
            return result.output;
        }
        if (Privilege.mode() != Privilege.MODE_SHIZUKU) {
            throw new IOException("no privilege");
        }
        try {
            var binder = rikka.shizuku.Shizuku.getBinder();
            if (binder == null) throw new IOException("shizuku not running");
            var service = IShizukuService.Stub.asInterface(binder);
            var process = service.newProcess(
                new String[]{"/system/bin/sh", "-c", script}, null, null);
            var builder = new StringBuilder();
            var error = new StringBuilder();
            var errorThread = new Thread(() -> {
                try (var stream = new ParcelFileDescriptor.AutoCloseInputStream(
                    process.getErrorStream())) {
                    var buffer = new byte[4096];
                    int read;
                    while ((read = stream.read(buffer)) != -1) {
                        error.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
                    }
                } catch (Exception ignored) {
                }
            });
            errorThread.setDaemon(true);
            errorThread.start();
            try (var stream = new ParcelFileDescriptor.AutoCloseInputStream(
                process.getInputStream())) {
                var buffer = new byte[8192];
                int read;
                while ((read = stream.read(buffer)) != -1) {
                    builder.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
                }
            }
            process.waitFor();
            if (builder.length() == 0 && error.length() > 0) {
                throw new IOException(error.toString());
            }
            return builder.toString();
        } catch (android.os.RemoteException e) {
            throw new IOException(e);
        }
    }

    // ---------------- 降级：普通 PackageManager ----------------

    private static List<Entry> fromPackageManager(Context context) {
        var pm = context.getPackageManager();
        var list = new ArrayList<Entry>();
        List<PackageInfo> packages;
        try {
            packages = pm.getInstalledPackages(PackageManager.MATCH_UNINSTALLED_PACKAGES);
        } catch (RuntimeException e) {
            packages = new ArrayList<>();
        }
        for (var info : packages) {
            var app = info.applicationInfo;
            if (app == null) continue;
            var label = app.loadLabel(pm);
            var entry = new Entry(info.packageName, app.sourceDir,
                (app.flags & ApplicationInfo.FLAG_SYSTEM) != 0,
                app.enabled,
                (app.flags & ApplicationInfo.FLAG_INSTALLED) != 0,
                info.getLongVersionCode(),
                info.versionName == null ? "" : info.versionName,
                label == null ? info.packageName : label.toString());
            list.add(entry);
        }
        return list;
    }
}
