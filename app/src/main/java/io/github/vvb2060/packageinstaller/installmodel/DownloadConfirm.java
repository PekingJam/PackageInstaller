package io.github.vvb2060.packageinstaller.installmodel;

/** URL 不是安装包时弹出的下载确认。 */
public final class DownloadConfirm extends InstallStage {
    public final String url;
    public final String name;
    public final String mime;
    public final long size;
    public final String host;

    public DownloadConfirm(String url, String name, String mime, long size, String host) {
        super(STAGE_DOWNLOAD_CONFIRM);
        this.url = url;
        this.name = name;
        this.mime = mime;
        this.size = size;
        this.host = host;
    }
}
