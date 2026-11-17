package io.github.vvb2060.packageinstaller.datamodel;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * 把下载完成的临时文件放进系统 Download 目录。
 * Android 10+ 走 MediaStore（不需要存储权限），写完再清掉 IS_PENDING。
 */
public final class DownloadStore {
    private static final String TAG = DownloadStore.class.getSimpleName();

    private DownloadStore() {
    }

    public static Uri save(Context context, File source, String displayName, String mime)
        throws IOException {
        var resolver = context.getContentResolver();
        var name = uniqueName(resolver, displayName);
        var collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);

        var values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, name);
        values.put(MediaStore.Downloads.MIME_TYPE,
            mime == null || mime.isEmpty() ? "application/octet-stream" : mime);
        values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
        values.put(MediaStore.Downloads.IS_PENDING, 1);

        var uri = resolver.insert(collection, values);
        if (uri == null) throw new IOException("无法在 Download 目录创建文件");

        try (var in = new FileInputStream(source);
             OutputStream out = resolver.openOutputStream(uri)) {
            if (out == null) throw new IOException("无法写入 Download 目录");
            var buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            out.flush();
        } catch (IOException | RuntimeException e) {
            try {
                resolver.delete(uri, null, null);
            } catch (RuntimeException ignored) {
            }
            throw e;
        }

        var done = new ContentValues();
        done.put(MediaStore.Downloads.IS_PENDING, 0);
        try {
            resolver.update(uri, done, null, null);
        } catch (RuntimeException e) {
            Log.w(TAG, "clear pending failed", e);
        }
        return uri;
    }

    /** 打开已下载文件的 Intent；拿不到 content uri 时返回 null。 */
    public static Intent openIntent(Context context, String uriString, String mime) {
        if (uriString == null || uriString.isEmpty()) return null;
        var intent = new Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.parse(uriString),
                mime == null || mime.isEmpty() ? "application/octet-stream" : mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        var packageManager = context.getPackageManager();
        if (packageManager.resolveActivity(intent, 0) == null) {
            // 没有能打开这种类型的应用时，用通用查看器兜底
            intent.setDataAndType(Uri.parse(uriString), "*/*");
        }
        return intent;
    }

    public static String displayPath(String name) {
        return Environment.DIRECTORY_DOWNLOADS + "/" + name;
    }

    private static String uniqueName(android.content.ContentResolver resolver, String name) {
        var base = name;
        var extension = "";
        var dot = name.lastIndexOf('.');
        if (dot > 0) {
            base = name.substring(0, dot);
            extension = name.substring(dot);
        }
        var candidate = name;
        for (int i = 1; i < 500; i++) {
            if (!exists(resolver, candidate)) return candidate;
            candidate = base + " (" + i + ")" + extension;
        }
        return candidate;
    }

    private static boolean exists(android.content.ContentResolver resolver, String name) {
        var collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        try (var cursor = resolver.query(collection, new String[]{MediaStore.Downloads._ID},
            MediaStore.Downloads.DISPLAY_NAME + "=? AND " + MediaStore.Downloads.RELATIVE_PATH + "=?",
            new String[]{name, Environment.DIRECTORY_DOWNLOADS + "/"}, null)) {
            return cursor != null && cursor.getCount() > 0;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
