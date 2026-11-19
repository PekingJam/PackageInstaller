package io.github.vvb2060.packageinstaller.datamodel;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** 下载任务的轻量记录，存在 filesDir/download_tasks.json，给「下载任务」列表用。 */
public final class DownloadTask {
    private static final String TAG = DownloadTask.class.getSimpleName();
    private static final String FILE_NAME = "download_tasks.json";
    private static final int MAX_RECORDS = 60;
    private static final Object LOCK = new Object();

    public static final String STATE_ACTIVE = "active";
    /** 包已经下完，正在写安装会话。 */
    public static final String STATE_INSTALLING = "installing";
    public static final String STATE_DONE = "done";
    public static final String STATE_FAILED = "failed";
    /** 用户主动点了暂停：和「中断」区分开，文案更准确，仍然可以继续。 */
    public static final String STATE_PAUSED = "paused";

    /** 普通文件下载，完成后落在系统 Download 目录。 */
    public static final String KIND_SAVE = "save";
    /** 安装包下载，下完直接进安装流程，不落 Download 目录。 */
    public static final String KIND_INSTALL = "install";

    public final String id;
    public final String url;
    public String name;
    public String mime;
    public String kind;
    public long size;
    public long downloaded;
    public long updatedAt;
    public String state;
    public String target;
    public String error;
    /** 解析出来的包名（安装包类任务），用来取真实的应用图标。 */
    public String packageName;

    public DownloadTask(String id, String url, String name, String mime, String state) {
        this.id = id;
        this.url = url;
        this.name = name;
        this.mime = mime;
        this.kind = KIND_SAVE;
        this.state = state;
        this.updatedAt = System.currentTimeMillis();
    }

    public boolean looksLikeApk() {
        return KIND_INSTALL.equals(kind)
            || DownloadProbe.mimeFromName(name).contains("package-archive");
    }

    public boolean isInstall() {
        return KIND_INSTALL.equals(kind);
    }

    private JSONObject toJson() throws JSONException {
        var json = new JSONObject();
        json.put("id", id);
        json.put("url", url);
        json.put("name", name);
        json.put("mime", mime);
        json.put("kind", kind);
        json.put("size", size);
        json.put("downloaded", downloaded);
        json.put("updatedAt", updatedAt);
        json.put("state", state);
        json.put("target", target);
        json.put("error", error);
        json.put("packageName", packageName);
        return json;
    }

    private static DownloadTask fromJson(JSONObject json) {
        var task = new DownloadTask(json.optString("id"), json.optString("url"),
            json.optString("name"), json.optString("mime"), json.optString("state"));
        task.size = json.optLong("size", -1);
        task.downloaded = json.optLong("downloaded", 0);
        task.updatedAt = json.optLong("updatedAt", 0);
        var kind = json.optString("kind", "");
        task.kind = kind.isEmpty() ? KIND_SAVE : kind;
        task.target = json.optString("target", null);
        task.error = json.optString("error", null);
        task.packageName = json.optString("packageName", null);
        return task;
    }

    public static List<DownloadTask> all(Context context) {
        synchronized (LOCK) {
            var list = new ArrayList<DownloadTask>();
            var file = new File(context.getFilesDir(), FILE_NAME);
            if (!file.exists()) return list;
            try (var in = new FileInputStream(file)) {
                var buffer = new byte[(int) file.length()];
                var read = in.read(buffer);
                if (read <= 0) return list;
                var array = new JSONArray(new String(buffer, 0, read, StandardCharsets.UTF_8));
                for (int i = 0; i < array.length(); i++) {
                    var json = array.optJSONObject(i);
                    if (json != null) list.add(fromJson(json));
                }
            } catch (Exception e) {
                Log.w(TAG, "read tasks failed", e);
            }
            list.sort((a, b) -> Long.compare(b.updatedAt, a.updatedAt));
            return list;
        }
    }

    /** 新增或刷新一条任务记录；同一个 id 已经存在时保留已下载的进度。 */
    public static void upsert(Context context, DownloadTask task) {
        synchronized (LOCK) {
            var list = all(context);
            for (var old : list) {
                if (!old.id.equals(task.id)) continue;
                if (old.downloaded > 0 && !STATE_DONE.equals(old.state)) {
                    task.downloaded = Math.max(task.downloaded, old.downloaded);
                }
                if (task.size <= 0) task.size = old.size;
                if (task.name == null || task.name.isEmpty()) task.name = old.name;
                list.remove(old);
                break;
            }
            list.add(0, task);
            while (list.size() > MAX_RECORDS) list.remove(list.size() - 1);
            write(context, list);
        }
    }

    /**
     * 心跳：安装阶段没有字节进度，只把 updatedAt 往前推，
     * 免得列表把正在写安装会话的任务当成「已中断」。
     */
    public static void touch(Context context, String id) {
        synchronized (LOCK) {
            var list = all(context);
            for (var item : list) {
                if (!item.id.equals(id)) continue;
                item.updatedAt = System.currentTimeMillis();
                write(context, list);
                return;
            }
        }
    }

    /** 只更新进度，不改变状态。 */
    public static void updateProgress(Context context, String id, long downloaded, long size) {
        synchronized (LOCK) {
            var list = all(context);
            for (var item : list) {
                if (!item.id.equals(id)) continue;
                item.downloaded = downloaded;
                if (size > 0) item.size = size;
                item.updatedAt = System.currentTimeMillis();
                write(context, list);
                return;
            }
        }
    }

    public static void setState(Context context, String id, String state, String target,
                                String error, long downloaded, long size) {
        synchronized (LOCK) {
            var list = all(context);
            for (var item : list) {
                if (!item.id.equals(id)) continue;
                item.state = state;
                item.target = target;
                item.error = error;
                if (downloaded >= 0) item.downloaded = downloaded;
                if (size > 0) item.size = size;
                item.updatedAt = System.currentTimeMillis();
                write(context, list);
                return;
            }
        }
    }

    /** 记录解析出来的包名，列表里就能用系统应用图标。 */
    public static void setPackageName(Context context, String id, String packageName) {
        if (packageName == null || packageName.isEmpty()) return;
        synchronized (LOCK) {
            var list = all(context);
            for (var item : list) {
                if (!item.id.equals(id)) continue;
                if (packageName.equals(item.packageName)) return;
                item.packageName = packageName;
                write(context, list);
                return;
            }
        }
    }

    public static void remove(Context context, String id) {
        synchronized (LOCK) {
            var list = all(context);
            list.removeIf(item -> item.id.equals(id));
            write(context, list);
        }
    }

    public static String idOf(String url) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(url.getBytes(StandardCharsets.UTF_8));
            var builder = new StringBuilder();
            for (int i = 0; i < 12; i++) builder.append(String.format("%02x", digest[i]));
            return builder.toString();
        } catch (Exception e) {
            return Integer.toHexString(url.hashCode());
        }
    }

    private static void write(Context context, List<DownloadTask> list) {
        var array = new JSONArray();
        for (var item : list) {
            try {
                array.put(item.toJson());
            } catch (JSONException ignored) {
            }
        }
        var file = new File(context.getFilesDir(), FILE_NAME);
        var temp = new File(context.getFilesDir(), FILE_NAME + ".tmp");
        try (var out = new FileOutputStream(temp)) {
            out.write(array.toString().getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        } catch (Exception e) {
            Log.w(TAG, "write tasks failed", e);
            return;
        }
        if (!temp.renameTo(file)) Log.w(TAG, "rename tasks failed");
    }
}
