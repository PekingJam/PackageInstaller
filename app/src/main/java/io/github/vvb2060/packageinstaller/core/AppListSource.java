package io.github.vvb2060.packageinstaller.core;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.BitmapDrawable;
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
    private static final int ICON_DP = 48;

    public static final class Entry {
        public final String packageName;
        public final String apkPath;
        public final boolean system;
        public final boolean enabled;
        public final boolean installed;
        public long versionCode;
        public String versionName;
        public String label;
        public Drawable icon;

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

    /** 补齐名称、版本与图标（可能解析 APK，比较慢，放后台）。 */
    public static void details(Context context, Entry entry) {
        if (entry.icon != null && !entry.label.equals(entry.packageName)) return;
        fillFromArchive(context.getPackageManager(), entry);
        if (entry.icon == null) entry.icon = context.getPackageManager().getDefaultActivityIcon();
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

    private static void fillFromArchive(PackageManager pm, Entry entry) {
        if (entry.apkPath == null) return;
        try {
            var info = pm.getPackageArchiveInfo(entry.apkPath, 0);
            if (info == null || info.applicationInfo == null) return;
            var app = info.applicationInfo;
            app.sourceDir = entry.apkPath;
            app.publicSourceDir = entry.apkPath;
            var label = app.loadLabel(pm);
            if (label != null && label.length() > 0) entry.label = label.toString();
            if (info.versionName != null) entry.versionName = info.versionName;
            entry.versionCode = info.getLongVersionCode();
            var icon = app.loadIcon(pm);
            if (icon != null) entry.icon = shrink(icon);
        } catch (RuntimeException | LinkageError e) {
            Log.w(TAG, "archive info failed: " + entry.apkPath, e);
        }
    }

    private static Drawable shrink(Drawable drawable) {
        if (drawable == null) return null;
        var resources = Resources.getSystem();
        var size = (int) (ICON_DP * resources.getDisplayMetrics().density);
        try {
            var bitmap = DrawableKt.toBitmap(drawable, size, size, null);
            if (bitmap == null) return drawable;
            return new BitmapDrawable(resources, bitmap);
        } catch (Throwable e) {
            return drawable;
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
            try {
                entry.icon = shrink(app.loadIcon(pm));
            } catch (RuntimeException e) {
                entry.icon = null;
            }
            list.add(entry);
        }
        return list;
    }

    /** 后台线程执行的图标补全（shell 模式下个别 APK 解析失败时用）。 */
    public static Drawable loadIcon(Context context, Entry entry) {
        if (entry.icon != null) return entry.icon;
        var pm = context.getPackageManager();
        fillFromArchive(pm, entry);
        if (entry.icon == null) entry.icon = pm.getDefaultActivityIcon();
        return entry.icon;
    }
}
