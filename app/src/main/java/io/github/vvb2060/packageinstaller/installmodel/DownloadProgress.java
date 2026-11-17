package io.github.vvb2060.packageinstaller.installmodel;

/** 下载进度：已下载 / 总大小 / 瞬时速度，单位都是字节。 */
public final class DownloadProgress {
    public final long downloaded;
    public final long total;
    public final long speed;

    public DownloadProgress(long downloaded, long total, long speed) {
        this.downloaded = downloaded;
        this.total = total;
        this.speed = speed;
    }

    public int percent() {
        if (total <= 0) return -1;
        return (int) Math.min(100, downloaded * 100 / total);
    }
}
