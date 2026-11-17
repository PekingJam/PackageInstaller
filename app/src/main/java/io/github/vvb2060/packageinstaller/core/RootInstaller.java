package io.github.vvb2060.packageinstaller.core;

import android.os.Build;

import androidx.lifecycle.MutableLiveData;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import io.github.vvb2060.packageinstaller.apk.ZipParser;

/**
 * Root 模式的安装流程：用 pm install-create / install-write / install-commit，
 * APK 内容通过 stdin 直接流进安装会话，不落盘。
 */
public final class RootInstaller {
    /** su 环境的 PATH 不一定包含 /system/bin，用绝对路径。 */
    public static final String PM = "/system/bin/pm";

    private static final Pattern SESSION_ID = Pattern.compile("install session \\[(\\d+)]");
    private static final Pattern UNKNOWN_OPTION = Pattern.compile("Unknown option:?\\s+(\\S+)");
    private static final Pattern FAILURE = Pattern.compile("Failure \\[([^\\]:]+)");

    private RootInstaller() {
    }

    public static final class InstallException extends Exception {
        public final String reason;

        InstallException(String reason, String detail) {
            super(detail);
            this.reason = reason;
        }
    }

    public static void install(ZipParser parser, String installerPackage, String packageName,
                               boolean full, MutableLiveData<Integer> progress)
        throws InstallException, IOException {
        var sessionId = createSession(parser.totalSize(), installerPackage, packageName, full);
        try {
            parser.writeTo((name, size) -> openWrite(sessionId, name, size), progress);
        } catch (IOException e) {
            RootShell.exec(PM + " install-abandon " + sessionId);
            throw e;
        }
        commit(sessionId);
    }

    private static int createSession(long totalSize, String installerPackage,
                                     String packageName, boolean full)
        throws InstallException {
        var flags = new ArrayList<String>();
        flags.add("-r");
        flags.add("-d");
        flags.add("--install-reason");
        flags.add("4");
        flags.add("--skip-verification");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            flags.add("--bypass-low-target-sdk-block");
            flags.add("--update-ownership");
        }
        if (!full && packageName != null) {
            flags.add("-p");
            flags.add(packageName);
        }

        RootShell.Result result = null;
        for (int attempt = 0; attempt < 4; attempt++) {
            var command = new StringBuilder(PM + " install-create");
            for (var flag : flags) {
                command.append(' ').append(flag);
            }
            command.append(" -i ").append(RootShell.quote(installerPackage));
            command.append(" -S ").append(totalSize);
            command.append(" --user ").append(Privilege.userId());
            result = RootShell.exec(command.toString(), 90_000);
            if (result.succeeded()) break;

            var matcher = UNKNOWN_OPTION.matcher(result.output);
            if (!matcher.find()) break;
            var unknown = matcher.group(1);
            if (!flags.remove(unknown)) break;
        }
        if (result == null || !result.succeeded()) {
            throw new InstallException("INSTALL_FAILED_SESSION",
                result == null ? "install-create 失败" : result.text());
        }
        var matcher = SESSION_ID.matcher(result.output);
        if (!matcher.find()) {
            throw new InstallException("INSTALL_FAILED_SESSION", result.text());
        }
        return Integer.parseInt(matcher.group(1));
    }

    private static OutputStream openWrite(int sessionId, String name, long size)
        throws IOException {
        var handle = RootShell.start(PM + " install-write -S " + size + " " + sessionId
            + " " + RootShell.quote(name) + " -");
        return new FilterOutputStream(handle.stdin()) {
            @Override
            public void close() throws IOException {
                var result = handle.finish(300_000);
                if (!result.succeeded()) {
                    throw new IOException("写入 " + name + " 失败: " + result.text());
                }
            }
        };
    }

    private static void commit(int sessionId) throws InstallException {
        var result = RootShell.exec(PM + " install-commit " + sessionId, 300_000);
        if (result.succeeded()) return;
        var matcher = FAILURE.matcher(result.output);
        var reason = matcher.find() ? matcher.group(1).trim() : "INSTALL_FAILED";
        throw new InstallException(reason, result.text());
    }
}
