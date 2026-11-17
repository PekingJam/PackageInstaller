package io.github.vvb2060.packageinstaller.datamodel;

import android.net.Uri;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 下载前的轻量探测：只发 HEAD（必要时退化成 GET + Range: bytes=0-0），
 * 拿到文件名、大小、类型，给确认弹窗用，不下载正文。
 */
public final class DownloadProbe {
    public final String url;
    public final String name;
    public final String mime;
    public final long size;
    public final boolean ranged;

    private DownloadProbe(String url, String name, String mime, long size, boolean ranged) {
        this.url = url;
        this.name = name;
        this.mime = mime;
        this.size = size;
        this.ranged = ranged;
    }

    /** APK / 拆分包 / 压缩包交给安装流程，其余当普通文件下载。 */
    public boolean looksLikeApk() {
        var lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".apk") || lower.endsWith(".apks") || lower.endsWith(".xapk")
            || lower.endsWith(".apkm") || lower.endsWith(".zip")) {
            return true;
        }
        if (mime != null) {
            var m = mime.toLowerCase(Locale.ROOT);
            if (m.contains("android.package-archive")) return true;
            if (m.contains("zip")) return true;
        }
        // 既没有后缀也没有可用类型：当成 APK 试一次，解析失败会回退到下载
        return lower.indexOf('.') < 0 || mime == null || mime.isEmpty()
            || "application/octet-stream".equals(mime);
    }

    public String host() {
        try {
            var host = Uri.parse(url).getHost();
            return host == null ? "" : host;
        } catch (RuntimeException e) {
            return "";
        }
    }

    public static DownloadProbe probe(String url) throws IOException {
        IOException failure = null;
        try {
            var probe = head(url);
            if (probe != null) return probe;
        } catch (IOException e) {
            failure = e;
        }
        try {
            var probe = rangedGet(url);
            if (probe != null) return probe;
        } catch (IOException e) {
            if (failure == null) failure = e;
        }
        if (failure != null) throw failure;
        throw new IOException("无法获取文件信息");
    }

    /** 连名字都拿不到时的兜底对象，保证弹窗还能显示个东西。 */
    public static DownloadProbe guess(String url) {
        var name = nameFromUrl(url);
        return new DownloadProbe(url, name, mimeFromName(name), -1, false);
    }

    private static DownloadProbe head(String url) throws IOException {
        var connection = open(url, "HEAD", null);
        try {
            var code = connection.getResponseCode();
            if (code < 200 || code >= 400) return null;
            var name = nameFromDisposition(connection.getHeaderField("Content-Disposition"), url);
            var mime = normalizeMime(connection.getContentType(), name);
            var size = connection.getContentLengthLong();
            var ranged = "bytes".equalsIgnoreCase(connection.getHeaderField("Accept-Ranges"));
            return new DownloadProbe(url, name, mime, size, ranged);
        } finally {
            connection.disconnect();
        }
    }

    private static DownloadProbe rangedGet(String url) throws IOException {
        var connection = open(url, "GET", "bytes=0-0");
        try {
            var code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_PARTIAL && code != HttpURLConnection.HTTP_OK) {
                return null;
            }
            var name = nameFromDisposition(connection.getHeaderField("Content-Disposition"), url);
            var mime = normalizeMime(connection.getContentType(), name);
            var size = -1L;
            if (code == HttpURLConnection.HTTP_PARTIAL) {
                var range = connection.getHeaderField("Content-Range");
                if (range != null) {
                    var slash = range.lastIndexOf('/');
                    if (slash >= 0) {
                        try {
                            size = Long.parseLong(range.substring(slash + 1).trim());
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
            } else {
                size = connection.getContentLengthLong();
            }
            return new DownloadProbe(url, name, mime, size,
                code == HttpURLConnection.HTTP_PARTIAL);
        } finally {
            connection.disconnect();
        }
    }

    private static HttpURLConnection open(String url, String method, String range)
        throws IOException {
        var connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(15_000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestMethod(method);
        connection.setRequestProperty("Accept-Encoding", "identity");
        connection.setRequestProperty("User-Agent",
            "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36");
        if (range != null) connection.setRequestProperty("Range", range);
        return connection;
    }

    private static String nameFromDisposition(String disposition, String url) {
        if (disposition != null) {
            var lower = disposition.toLowerCase(Locale.ROOT);
            var starIndex = lower.indexOf("filename*=");
            if (starIndex >= 0) {
                var value = disposition.substring(starIndex + "filename*=".length()).trim();
                var quote = value.indexOf("''");
                if (quote >= 0) value = value.substring(quote + 2);
                var semi = value.indexOf(';');
                if (semi >= 0) value = value.substring(0, semi);
                var decoded = decode(value.trim().replace("\"", ""));
                if (!decoded.isEmpty()) return sanitize(decoded);
            }
            var index = lower.indexOf("filename=");
            if (index >= 0) {
                var value = disposition.substring(index + "filename=".length()).trim();
                var semi = value.indexOf(';');
                if (semi >= 0) value = value.substring(0, semi);
                value = value.trim().replace("\"", "");
                var decoded = decode(value);
                if (!decoded.isEmpty()) return sanitize(decoded);
            }
        }
        return nameFromUrl(url);
    }

    private static String nameFromUrl(String url) {
        try {
            var parsed = Uri.parse(url);
            var segment = parsed.getLastPathSegment();
            if (segment != null && !segment.isEmpty()) return sanitize(decode(segment));
        } catch (RuntimeException ignored) {
        }
        return "download";
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return value;
        }
    }

    private static String sanitize(String name) {
        var cleaned = name.replace('/', '_').replace('\\', '_').trim();
        if (cleaned.isEmpty()) return "download";
        return cleaned.length() > 120 ? cleaned.substring(0, 120) : cleaned;
    }

    private static String normalizeMime(String contentType, String name) {
        if (contentType != null) {
            var value = contentType.split(";")[0].trim().toLowerCase(Locale.ROOT);
            if (!value.isEmpty() && !"application/octet-stream".equals(value)) return value;
        }
        return mimeFromName(name);
    }

    public static String mimeFromName(String name) {
        if (name == null) return "application/octet-stream";
        var lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".apk") || lower.endsWith(".apks") || lower.endsWith(".xapk")
            || lower.endsWith(".apkm")) {
            return "application/vnd.android.package-archive";
        }
        if (lower.endsWith(".zip")) return "application/zip";
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".mp4")) return "video/mp4";
        if (lower.endsWith(".mkv")) return "video/x-matroska";
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        if (lower.endsWith(".m4a")) return "audio/mp4";
        if (lower.endsWith(".flac")) return "audio/flac";
        if (lower.endsWith(".txt")) return "text/plain";
        if (lower.endsWith(".json")) return "application/json";
        if (lower.endsWith(".doc")) return "application/msword";
        if (lower.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (lower.endsWith(".xls")) return "application/vnd.ms-excel";
        if (lower.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if (lower.endsWith(".ppt")) return "application/vnd.ms-powerpoint";
        if (lower.endsWith(".pptx")) return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
        if (lower.endsWith(".torrent")) return "application/x-bittorrent";
        return "application/octet-stream";
    }
}
