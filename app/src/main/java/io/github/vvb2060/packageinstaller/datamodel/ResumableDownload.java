package io.github.vvb2060.packageinstaller.datamodel;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 支持断点续传的单文件下载。
 *
 * 用 cacheDir/downloads 下的 .part 文件保存已下载内容，下次同一个 URL 会带
 * Range: bytes=<已下载>- 续传；服务器返回 200 时从头再来。下载完成后改名为 .apk。
 */
public final class ResumableDownload {
    private static final String TAG = ResumableDownload.class.getSimpleName();
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final long PROGRESS_INTERVAL = 200L;

    public interface Listener {
        void onProgress(long downloaded, long total, long speed);
    }

    public interface Canceller {
        boolean isCancelled();
    }

    /** 未完成的下载。 */
    public static final class Pending {
        public final String url;
        public final String label;
        public final long downloaded;
        public final long modified;

        Pending(String url, String label, long downloaded, long modified) {
            this.url = url;
            this.label = label;
            this.downloaded = downloaded;
            this.modified = modified;
        }
    }

    private ResumableDownload() {
    }

    /** 扫描 cacheDir/downloads 里还没下完的条目。 */
    public static java.util.List<Pending> pending(Context context) {
        var list = new java.util.ArrayList<Pending>();
        var dir = new File(context.getCacheDir(), "downloads");
        var files = dir.listFiles();
        if (files == null) return list;
        for (var meta : files) {
            var name = meta.getName();
            if (!name.endsWith(".meta")) continue;
            var key = name.substring(0, name.length() - ".meta".length());
            var part = new File(dir, key + ".part");
            if (!part.exists() || part.length() == 0) continue;
            var url = readMeta(meta);
            if (url.isEmpty()) continue;
            // 10 秒内还在写的条目说明正在下载，别在「未完成」里重复启动
            if (System.currentTimeMillis() - part.lastModified() < 10_000L) continue;
            var label = android.net.Uri.parse(url).getLastPathSegment();
            if (label == null || label.isEmpty()) label = url;
            list.add(new Pending(url, label, part.length(), part.lastModified()));
        }
        list.sort((a, b) -> Long.compare(b.modified, a.modified));
        return list;
    }

    /** 删除某个 URL 的残留数据。 */
    public static void delete(Context context, String url) {
        var dir = new File(context.getCacheDir(), "downloads");
        var key = key(url);
        deleteQuietly(new File(dir, key + ".part"));
        deleteQuietly(new File(dir, key + ".meta"));
        deleteQuietly(new File(dir, key + ".apk"));
    }

    public static File download(Context context, String url, Listener listener,
                                Canceller canceller) throws IOException {
        var file = download(context, url, listener, canceller, true);
        return file;
    }

    private static File download(Context context, String url, Listener listener,
                                 Canceller canceller, boolean retryOnRangeError) throws IOException {
        var dir = new File(context.getCacheDir(), "downloads");
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("无法创建下载目录");
        var key = key(url);
        var part = new File(dir, key + ".part");
        var meta = new File(dir, key + ".meta");
        var done = new File(dir, key + ".apk");

        var existing = 0L;
        if (part.exists() && url.equals(readMeta(meta))) {
            existing = part.length();
        } else {
            deleteQuietly(part);
            writeMeta(meta, url);
            if (done.exists()) deleteQuietly(done);
        }

        var connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(30_000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("Accept-Encoding", "identity");
        RequestHeaders.forUrl(url).apply(connection);
        if (existing > 0) {
            connection.setRequestProperty("Range", "bytes=" + existing + "-");
        }

        var code = connection.getResponseCode();
        long total;
        boolean append;
        if (code == HttpURLConnection.HTTP_PARTIAL) {
            append = true;
            total = parseTotal(connection.getHeaderField("Content-Range"), existing);
        } else if (code == HttpURLConnection.HTTP_OK) {
            // 服务器不支持续传，或 URL 换了文件
            append = false;
            existing = 0;
            total = connection.getContentLengthLong();
        } else if (code == 416 && retryOnRangeError) {
            // 本地比远端还大，丢掉重来
            connection.disconnect();
            deleteQuietly(part);
            deleteQuietly(meta);
            Log.w(TAG, "range not satisfiable, restart");
            return download(context, url, listener, canceller, false);
        } else {
            connection.disconnect();
            throw new IOException("HTTP " + code + " " + connection.getResponseMessage());
        }

        var downloaded = existing;
        var start = SystemClock.elapsedRealtime();
        var lastReport = 0L;
        var lastBytes = downloaded;
        var lastTime = start;

        try (var in = connection.getInputStream();
             var out = new FileOutputStream(part, append)) {
            var buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                downloaded += read;
                var now = SystemClock.elapsedRealtime();
                if (now - lastReport >= PROGRESS_INTERVAL) {
                    lastReport = now;
                    var speed = 0L;
                    var elapsed = now - lastTime;
                    if (elapsed > 0) {
                        speed = (downloaded - lastBytes) * 1000 / elapsed;
                    }
                    lastBytes = downloaded;
                    lastTime = now;
                    if (listener != null) listener.onProgress(downloaded, total, speed);
                }
                if (canceller != null && canceller.isCancelled()) {
                    throw new IOException("已取消");
                }
            }
            out.flush();
        } finally {
            connection.disconnect();
        }

        if (total > 0 && downloaded != total) {
            throw new IOException("下载不完整：" + downloaded + "/" + total);
        }
        deleteQuietly(done);
        if (!part.renameTo(done)) throw new IOException("重命名下载文件失败");
        deleteQuietly(meta);
        if (listener != null) listener.onProgress(downloaded, total == 0 ? downloaded : total, 0);
        return done;
    }

    private static long parseTotal(String contentRange, long existing) {
        if (contentRange == null) return -1;
        var slash = contentRange.lastIndexOf('/');
        if (slash < 0) return -1;
        try {
            return Long.parseLong(contentRange.substring(slash + 1).trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String key(String url) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(
                url.getBytes(StandardCharsets.UTF_8));
            var builder = new StringBuilder();
            for (int i = 0; i < 12; i++) builder.append(String.format("%02x", digest[i]));
            return builder.toString();
        } catch (Exception e) {
            return Integer.toHexString(url.hashCode());
        }
    }

    private static String readMeta(File file) {
        if (!file.exists()) return "";
        try (var in = new FileInputStream(file)) {
            var buffer = new byte[(int) Math.min(file.length(), 4096)];
            var read = in.read(buffer);
            return read <= 0 ? "" : new String(buffer, 0, read, StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return "";
        }
    }

    private static void writeMeta(File file, String url) {
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(url.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
        }
    }

    private static void deleteQuietly(File file) {
        try {
            if (file.exists() && !file.delete()) Log.w(TAG, "delete failed: " + file);
        } catch (RuntimeException ignored) {
        }
    }
}
