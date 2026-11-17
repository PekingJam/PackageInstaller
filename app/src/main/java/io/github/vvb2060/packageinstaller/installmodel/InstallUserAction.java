package io.github.vvb2060.packageinstaller.installmodel;

import android.content.pm.PackageInfo;

import androidx.annotation.Nullable;

import io.github.vvb2060.packageinstaller.apk.ApkLite;

public final class InstallUserAction extends InstallStage {

    public final ApkLite apkLite;
    @Nullable
    public final PackageInfo oldApk;
    public final boolean fullInstall;
    public final boolean setInstaller;
    /** true 表示「重新启用已存在的系统应用」，不需要写包。 */
    public boolean installExisting;
    /** 网络下载下来的临时文件路径；为 null 时用 sourceUri。 */
    @Nullable
    public final String sourcePath;
    /** 原始 content:// URI；为 null 时用 sourcePath。 */
    @Nullable
    public final String sourceUri;

    public InstallUserAction(ApkLite apkLite, PackageInfo oldApk) {
        this(apkLite, oldApk, true, false, null, null);
    }

    public InstallUserAction(ApkLite apkLite, PackageInfo oldApk, boolean fullInstall,
                             boolean setInstaller, @Nullable String sourcePath,
                             @Nullable String sourceUri) {
        super(STAGE_USER_ACTION);
        this.apkLite = apkLite;
        this.oldApk = oldApk;
        this.fullInstall = fullInstall;
        this.setInstaller = setInstaller;
        this.sourcePath = sourcePath;
        this.sourceUri = sourceUri;
    }
}
