package io.github.vvb2060.packageinstaller.core;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 后台保活。
 *
 * 通用部分：前台服务 + 开机自启 + 电池白名单。
 * Root 部分：往 /data/local/tmp 丢一个 shell 守护脚本，进程被划掉后由它把服务拉回来
 * （脚本以 root 身份常驻，不依赖应用进程，所以应用被杀也能自愈）。
 */
public final class KeepAlive {
    private static final String PREF = "keep_alive";
    private static final String SCRIPT_NAME = "hyperosmod_keepalive.sh";
    private static final String SCRIPT_PATH = "/data/local/tmp/" + SCRIPT_NAME;

    private KeepAlive() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(context.getPackageName(), Context.MODE_PRIVATE);
    }

    public static boolean isEnabled(Context context) {
        return prefs(context).getBoolean(PREF, false);
    }

    public static void setEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(PREF, enabled).apply();
    }

    /** 电池白名单 + appops 后台运行许可，MIUI/HyperOS 上明显减少被杀概率。 */
    public static void applyRootTweaks(Context context) {
        var pkg = context.getPackageName();
        RootShell.exec("cmd deviceidle whitelist +" + pkg
            + "; cmd appops set " + pkg + " RUN_IN_BACKGROUND allow"
            + "; cmd appops set " + pkg + " RUN_ANY_IN_BACKGROUND allow", 20_000L);
    }

    /** 启动 root 守护脚本；返回是否成功（没有 root 就返回 false）。 */
    public static boolean startRootWatchdog(Context context) {
        if (!RootShell.isAvailable()) return false;
        var script = buildScript(context.getPackageName());
        var file = new File(context.getCacheDir(), SCRIPT_NAME);
        try (var out = new FileOutputStream(file)) {
            out.write(script.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            return false;
        }
        var command = "cp " + RootShell.quote(file.getAbsolutePath()) + " " + SCRIPT_PATH
            + "; chmod 700 " + SCRIPT_PATH
            + "; pkill -f " + SCRIPT_NAME + " 2>/dev/null"
            + "; nohup sh " + SCRIPT_PATH + " >/dev/null 2>&1 </dev/null &";
        var result = RootShell.exec(command, 20_000L);
        return result.code == 0;
    }

    public static void stopRootWatchdog() {
        RootShell.exec("pkill -f " + SCRIPT_NAME + " 2>/dev/null; rm -f " + SCRIPT_PATH, 20_000L);
    }

    public static boolean rootWatchdogRunning() {
        var result = RootShell.exec("pgrep -f " + SCRIPT_NAME, 10_000L);
        return result.code == 0 && !result.text().isEmpty();
    }

    private static String buildScript(String packageName) {
        return "#!/system/bin/sh\n"
            + "PKG=" + packageName + "\n"
            + "while true; do\n"
            + "  if ! pidof \"$PKG\" >/dev/null 2>&1; then\n"
            + "    am start-foreground-service -n \"$PKG/.service.KeepAliveService\" >/dev/null 2>&1\n"
            + "    sleep 8\n"
            + "  fi\n"
            + "  sleep 45\n"
            + "done\n";
    }
}
