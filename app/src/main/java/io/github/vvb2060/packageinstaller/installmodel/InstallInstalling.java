package io.github.vvb2060.packageinstaller.installmodel;

import io.github.vvb2060.packageinstaller.apk.ApkLite;

public final class InstallInstalling extends InstallStage {

    public final ApkLite apkLite;
    /** 网络来源才有「转后台」的意义（本地包复制很快）。 */
    public final boolean fromNetwork;

    public InstallInstalling(ApkLite apkLite) {
        this(apkLite, false);
    }

    public InstallInstalling(ApkLite apkLite, boolean fromNetwork) {
        super(STAGE_INSTALLING);
        this.apkLite = apkLite;
        this.fromNetwork = fromNetwork;
    }

}
