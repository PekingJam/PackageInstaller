package io.github.vvb2060.packageinstaller.core;

import android.content.Context;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageInstaller_rename;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager_rename;
import android.os.Build;

import androidx.core.graphics.drawable.DrawableKt;

import java.io.IOException;

import io.github.vvb2060.packageinstaller.BuildConfig;
import io.github.vvb2060.packageinstaller.apk.ApkLite;
import io.github.vvb2060.packageinstaller.apk.ZipParser;

/**
 * 真正干活的安装执行器：Root 走 pm 会话命令，Shizuku 走 binder 会话。
 * 放在这里是为了让界面弹窗和后台 Service 共用同一套逻辑。
 */
public final class InstallRunner {

    public interface Callback {
        void onProgress(int percent);

        void onSuccess();

        void onFailure(String reason);
    }

    private InstallRunner() {
    }

    public static void run(Context context, ApkLite apk, ZipParser parser, boolean setInstaller,
                           boolean full, Callback callback) {
        try {
            if (Privilege.mode() == Privilege.MODE_ROOT) {
                runRoot(apk, parser, setInstaller, full, callback);
            } else {
                runSession(context, apk, parser, setInstaller, full, callback);
            }
        } catch (Throwable e) {
            callback.onFailure(e.toString());
        }
    }

    private static void runRoot(ApkLite apk, ZipParser parser, boolean setInstaller,
                                boolean full, Callback callback) {
        var installer = setInstaller
            ? Privilege.installerPackageName() : BuildConfig.APPLICATION_ID;
        try {
            RootInstaller.install(parser, installer, apk.packageName, full, callback::onProgress);
            callback.onProgress(101);
            callback.onSuccess();
        } catch (RootInstaller.InstallException e) {
            callback.onFailure(e.reason + "\n" + e.getMessage());
        } catch (IOException e) {
            callback.onFailure(e.toString());
        }
    }

    private static void runSession(Context context, ApkLite apk, ZipParser parser,
                                   boolean setInstaller, boolean full, Callback callback) {
        var installer = Privilege.packageInstaller();
        Privilege.disableVerification();
        Privilege.addDeveloperVerificationExperiment(apk.packageName);

        int sessionId;
        try {
            sessionId = installer.createSession(
                createSessionParams(apk, setInstaller, full));
        } catch (IOException | RuntimeException e) {
            callback.onFailure("创建安装会话失败：" + e);
            return;
        }

        try (var session = installer.openSession(sessionId)) {
            if (Privilege.mode() == Privilege.MODE_SHIZUKU) BinderWrapper.wrap(session);
            parser.sessionWrite(session, callback::onProgress);
            callback.onProgress(101);

            var receiver = new LocalIntentReceiver((statusCode, legacyStatus, message) -> {
                if (statusCode == PackageInstaller.STATUS_SUCCESS) {
                    callback.onSuccess();
                } else if (statusCode == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    Privilege.setPermissionsResult(sessionId);
                } else if (message != null) {
                    callback.onFailure(message);
                } else {
                    callback.onFailure(PackageManager_rename.installStatusToString(legacyStatus));
                }
            });
            session.commit(receiver.getIntentSender());
        } catch (IOException | RuntimeException e) {
            callback.onFailure(e.toString());
        }
    }

    private static PackageInstaller.SessionParams createSessionParams(
        ApkLite apkLite, boolean setInstaller, boolean full) {
        var mode = full ? PackageInstaller.SessionParams.MODE_FULL_INSTALL
            : PackageInstaller.SessionParams.MODE_INHERIT_EXISTING;
        var params = new PackageInstaller.SessionParams(mode);

        String installer;
        if (setInstaller) {
            installer = Privilege.installerPackageName();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                params.setPackageSource(PackageInstaller.PACKAGE_SOURCE_STORE);
            }
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                params.setPackageSource(PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE);
            }
            installer = BuildConfig.APPLICATION_ID;
        }
        params.setInstallerPackageName(installer);
        params.setInstallReason(PackageManager.INSTALL_REASON_USER);
        params.setAppPackageName(apkLite.packageName);

        if (apkLite.needSplit()) {
            if (apkLite.icon != null) {
                var bitmap = DrawableKt.toBitmap(apkLite.icon, apkLite.icon.getIntrinsicWidth(),
                    apkLite.icon.getIntrinsicHeight(), null);
                params.setAppIcon(bitmap);
            }
            params.setAppLabel(apkLite.label);
        }

        var p = (PackageInstaller_rename.SessionParams) (Object) params;
        p.installFlags |= PackageManager_rename.INSTALL_ALLOW_TEST;
        p.installFlags |= PackageManager_rename.INSTALL_REPLACE_EXISTING;
        p.installFlags |= PackageManager_rename.INSTALL_REQUEST_DOWNGRADE;
        p.installFlags |= PackageManager_rename.INSTALL_FULL_APP;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            p.installFlags |= PackageManager_rename.INSTALL_BYPASS_LOW_TARGET_SDK_BLOCK;
            p.installFlags |= PackageManager_rename.INSTALL_REQUEST_UPDATE_OWNERSHIP;
        }
        return params;
    }
}
