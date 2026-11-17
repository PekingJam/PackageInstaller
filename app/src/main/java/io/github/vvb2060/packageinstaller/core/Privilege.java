package io.github.vvb2060.packageinstaller.core;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.VersionedPackage;
import android.os.Build;
import android.os.Process;
import android.util.Log;

import org.lsposed.hiddenapibypass.LSPass;

import java.util.regex.Pattern;

import io.github.vvb2060.packageinstaller.BuildConfig;
import rikka.shizuku.Shizuku;

/**
 * 特权操作入口。
 *
 * 同一时刻只使用一种后端：一旦判定为 Root，本类不会再碰 Shizuku 的 API，反之亦然。
 * 业务代码只能从这里调特权操作，不要直接引用 BinderWrapper / Shizuku。
 */
public final class Privilege {
    private static final String TAG = Privilege.class.getSimpleName();
    private static final String PREF = "privilege_mode";

    public static final int MODE_NONE = 0;
    public static final int MODE_ROOT = 1;
    public static final int MODE_SHIZUKU = 2;

    /** 用户选择：自动 / 只用 Root / 只用 Shizuku。 */
    public static final int PREF_AUTO = 0;
    public static final int PREF_ROOT = 1;
    public static final int PREF_SHIZUKU = 2;

    private static final Pattern COMPONENT = Pattern.compile("([a-zA-Z][\\w.]*)/([\\w.$]+)");

    private static volatile int mode = -1;
    private static volatile Context appContext;

    static {
        // ApkAssets / XmlBlock 这些隐藏 API 在 Root 模式下不会经过 BinderWrapper 的静态块，
        // 没有豁免会抛 NoSuchMethodError（Error），直接带走进程。
        try {
            LSPass.setHiddenApiExemptions("");
        } catch (Throwable ignored) {
        }
    }

    private Privilege() {
    }

    public static void attach(Context context) {
        appContext = context.getApplicationContext();
    }

    public static Context context() {
        return appContext;
    }

    // ---------------- 模式选择 ----------------

    public static int selectedMode() {
        var context = appContext;
        if (context == null) return PREF_AUTO;
        return context.getSharedPreferences(BuildConfig.APPLICATION_ID, Context.MODE_PRIVATE)
            .getInt(PREF, PREF_AUTO);
    }

    public static void setSelectedMode(int value) {
        var context = appContext;
        if (context != null) {
            context.getSharedPreferences(BuildConfig.APPLICATION_ID, Context.MODE_PRIVATE)
                .edit().putInt(PREF, value).apply();
        }
        invalidateNow();
    }

    public static int mode() {
        var current = mode;
        if (current >= 0) return current;
        synchronized (Privilege.class) {
            if (mode < 0) mode = detect();
            return mode;
        }
    }

    /** 只读缓存，不在 UI 线程触发 su 探测；未判定时返回 -1。 */
    public static int modeOrUnknown() {
        return mode;
    }

    public static boolean usingRoot() {
        return mode() == MODE_ROOT;
    }

    public static void invalidate() {
        mode = -1;
        RootShell.invalidate();
    }

    public static void invalidateNow() {
        mode = -1;
        RootShell.invalidateNow();
    }

    private static int detect() {
        var selected = selectedMode();
        if (selected == PREF_ROOT) {
            // 只用 Root：连「Shizuku 是否在跑」都不问
            return RootShell.isAvailable() ? MODE_ROOT : MODE_NONE;
        }
        if (selected == PREF_SHIZUKU) {
            return shizukuCanInstall() ? MODE_SHIZUKU : MODE_NONE;
        }
        if (RootShell.isAvailable()) return MODE_ROOT;
        return shizukuCanInstall() ? MODE_SHIZUKU : MODE_NONE;
    }

    // ---------------- Shizuku 状态 ----------------

    public static boolean rootGranted() {
        return RootShell.isAvailable();
    }

    public static boolean shizukuRunning() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable e) {
            return false;
        }
    }

    public static boolean shizukuGranted() {
        try {
            return shizukuRunning()
                && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable e) {
            return false;
        }
    }

    public static boolean shizukuCanInstall() {
        try {
            return shizukuGranted()
                && Shizuku.checkRemotePermission(Manifest.permission.INSTALL_PACKAGES)
                == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable e) {
            return false;
        }
    }

    public static void requestShizukuPermission() {
        try {
            if (shizukuRunning() && !shizukuGranted()) Shizuku.requestPermission(0);
        } catch (Throwable e) {
            Log.w(TAG, "requestShizukuPermission", e);
        }
    }

    // ---------------- 通路 ----------------

    public static int userId() {
        return Process.myUid() / 100_000;
    }

    /** 初始化当前模式需要的 binder 代理；Root 模式下什么也不做。 */
    public static void prepare() {
        var context = appContext;
        if (context == null) return;
        if (mode() == MODE_SHIZUKU) {
            BinderWrapper.init(context);
            BinderWrapper.disableAdbVerify(context);
            PreferredActivity.set(context.getPackageManager());
        }
    }

    public static PackageInstaller packageInstaller() {
        if (mode() == MODE_SHIZUKU) return BinderWrapper.installer;
        return appContext.getPackageManager().getPackageInstaller();
    }

    public static String installerPackageName() {
        try {
            appContext.getPackageManager()
                .getPackageInfo("com.android.vending", PackageManager.MATCH_SYSTEM_ONLY);
            return "com.android.vending";
        } catch (PackageManager.NameNotFoundException ignored) {
            return "com.android.shell";
        }
    }

    public static PackageInfo getPackageInfo(String packageName, int flags)
        throws PackageManager.NameNotFoundException {
        if (mode() == MODE_SHIZUKU) {
            return BinderWrapper.getPackageInfo(packageName, flags);
        }
        var pm = appContext.getPackageManager();
        var all = flags | PackageManager.MATCH_UNINSTALLED_PACKAGES
            | PackageManager.MATCH_DISABLED_COMPONENTS;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            var versioned = new VersionedPackage(packageName, PackageManager.VERSION_CODE_HIGHEST);
            return pm.getPackageInfo(versioned, all);
        }
        return pm.getPackageInfo(packageName, all);
    }

    public static void disableVerification() {
        if (mode() == MODE_SHIZUKU) {
            BinderWrapper.disableVerificationForUid();
        }
    }

    public static void addDeveloperVerificationExperiment(String packageName) {
        if (mode() == MODE_SHIZUKU) {
            BinderWrapper.addDeveloperVerificationExperiment(packageName);
        }
    }

    public static void setPermissionsResult(int sessionId) {
        if (mode() == MODE_SHIZUKU) {
            BinderWrapper.setPermissionsResult(sessionId);
        }
    }

    public static void startActivity(Intent intent) {
        if (mode() == MODE_SHIZUKU) {
            BinderWrapper.startActivity(intent);
            return;
        }
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            appContext.startActivity(intent);
        } catch (RuntimeException e) {
            Log.w(TAG, "startActivity", e);
        }
    }

    /** 返回 null 表示成功，否则是错误信息。 */
    public static String uninstall(String packageName, long versionCode, boolean systemApp) {
        var current = mode();
        if (current == MODE_SHIZUKU) {
            BinderWrapper.uninstallPackage(packageName, versionCode);
            return null;
        }
        if (current == MODE_ROOT) {
            var pkg = RootShell.quote(packageName);
            var result = RootShell.exec(RootInstaller.PM + " uninstall --user " + userId() + " " + pkg);
            if (result.succeeded()) return null;
            // 系统应用不能按用户卸载，退而卸载更新（等价 DELETE_SYSTEM_APP 的效果）。
            // 故意不做不带 --user 的重试：那会把应用从所有用户（含工作资料/分身）里删掉。
            if (systemApp) {
                var updates = RootShell.exec(RootInstaller.PM + " uninstall-system-updates " + pkg);
                if (updates.ok()) return null;
            }
            return result.text();
        }
        return notGranted();
    }

    public static String setEnabled(String packageName, boolean enabled) {
        var current = mode();
        if (current == MODE_SHIZUKU) {
            BinderWrapper.setPackageEnabled(packageName, enabled);
            return null;
        }
        if (current == MODE_ROOT) {
            var pkg = RootShell.quote(packageName);
            var cmd = (enabled ? RootInstaller.PM + " enable --user "
                : RootInstaller.PM + " disable-user --user ") + userId() + " " + pkg;
            var result = RootShell.exec(cmd);
            return result.ok() ? null : result.text();
        }
        return notGranted();
    }

    public static String clearApplicationUserData(String packageName) {
        var current = mode();
        if (current == MODE_SHIZUKU) {
            BinderWrapper.clearApplicationUserData(packageName);
            return null;
        }
        if (current == MODE_ROOT) {
            var result = RootShell.exec(RootInstaller.PM + " clear --user " + userId() + " "
                + RootShell.quote(packageName));
            return result.succeeded() ? null : result.text();
        }
        return notGranted();
    }

    /** 仅 Root 模式：重新启用被卸载/停用的系统应用。Shizuku 模式走 InstallRepository 原路径。 */
    public static String installExistingPackage(String packageName) {
        if (mode() != MODE_ROOT) return "unsupported";
        var result = RootShell.exec(RootInstaller.PM + " install-existing --user " + userId() + " "
            + RootShell.quote(packageName));
        return result.ok() ? null : result.text();
    }

    public static void setPowerSaveWhitelist(boolean add) {
        var current = mode();
        if (current == MODE_SHIZUKU) {
            if (add) {
                BinderWrapper.addPowerSaveWhitelistApp();
            } else {
                BinderWrapper.removePowerSaveWhitelistApp();
            }
            return;
        }
        if (current == MODE_ROOT) {
            var prefix = add ? "+" : "-";
            RootShell.exec("dumpsys deviceidle whitelist " + prefix + BuildConfig.APPLICATION_ID);
        }
    }

    /**
     * 打开应用。系统限制了应用列表可见性时（MIUI「获取应用列表」关闭），自己的 PM 拿不到
     * launch intent，这时用 Root 解析组件名再 am start。
     */
    /** 把本应用设为 APK 的默认打开方式；返回 null 表示成功。 */
    public static String makeDefaultInstaller() {
        var context = appContext;
        var current = mode();
        if (current == MODE_ROOT) {
            return PreferredActivity.setViaRoot(context, context.getPackageManager());
        }
        if (current == MODE_SHIZUKU) {
            PreferredActivity.set(context.getPackageManager());
            return null;
        }
        return notGranted();
    }

    public static String launchApp(String packageName) {
        var context = appContext;
        var intent = context.getPackageManager().getLaunchIntentForPackage(packageName);
        if (intent != null) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
                return null;
            } catch (RuntimeException e) {
                Log.w(TAG, "launchApp", e);
            }
        }
        if (mode() == MODE_ROOT) {
            var resolved = RootShell.exec("/system/bin/cmd package resolve-activity --brief"
                + " -a android.intent.action.MAIN -c android.intent.category.LAUNCHER"
                + " --user " + userId() + " " + RootShell.quote(packageName));
            var matcher = COMPONENT.matcher(resolved.output);
            String component = null;
            while (matcher.find()) {
                component = matcher.group(0);
            }
            if (component != null) {
                var start = RootShell.exec("/system/bin/am start --user " + userId()
                    + " -n " + RootShell.quote(component));
                return start.ok() ? null : start.text();
            }
            return resolved.text();
        }
        return notGranted();
    }

    private static String notGranted() {
        return "未获得 Root 或 Shizuku 授权";
    }
}
