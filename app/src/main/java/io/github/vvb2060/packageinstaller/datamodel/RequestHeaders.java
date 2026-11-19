package io.github.vvb2060.packageinstaller.datamodel;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Base64;
import android.util.Log;

import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 从浏览器扩展（linkbridge）转交过来的请求头：Referer、Cookie、UA。
 *
 * <p>扩展把链接交给安装器时只传 URL，浏览器那边的登录态和来源页就丢了，
 * 防盗链站点会直接 403。扩展现在会把这三样塞进 intent 的 {@code lbhdr}
 * extra（URL-safe base64 的 JSON），这里解析后按 URL 记住。
 *
 * <p>按 URL 登记而不是把参数串进每个方法签名：下载要经过探测、前台下载、
 * 后台 Service、断点续传好几条路径，它们手里都只有 URL。登记一次，处处生效。
 */
public final class RequestHeaders {
    private static final String TAG = RequestHeaders.class.getSimpleName();
    private static final String PREF = "request_headers";
    private static final String KEY = "map";
    /** linkbridge 约定的 extra 名（intent:// 里的 S.lbhdr）。 */
    public static final String EXTRA = "lbhdr";
    private static final int MAX_RECORDS = 20;
    private static final int MAX_COOKIE = 4000;
    private static final Object LOCK = new Object();
    private static final Map<String, RequestHeaders> CACHE = new ConcurrentHashMap<>();

    public static final RequestHeaders EMPTY = new RequestHeaders(null, null, null);

    private static volatile Context context;

    public final String referer;
    public final String cookie;
    public final String ua;

    private RequestHeaders(String referer, String cookie, String ua) {
        this.referer = referer;
        this.cookie = cookie;
        this.ua = ua;
    }

    public static void init(Context ctx) {
        context = ctx.getApplicationContext();
    }

    /**
     * 解析转交过来的 extra 并登记。没有 extra 时静默返回，
     * 所以普通分享 / 手动输入链接调用它没有副作用。
     */
    public static void fromIntent(Intent intent) {
        if (intent == null) return;
        String raw;
        try {
            raw = intent.getStringExtra(EXTRA);
        } catch (RuntimeException e) {
            return;
        }
        if (raw == null || raw.isEmpty()) return;
        try {
            var json = new JSONObject(new String(decode(raw), StandardCharsets.UTF_8));
            var referer = json.optString("referer", null);
            var cookie = json.optString("cookie", null);
            var ua = json.optString("ua", null);
            // 扩展给的 URL 和 intent 里的 data 理论上一致，两个都登记，
            // 免得 fragment / 编码差异导致取不到头。
            remember(json.optString("url", null), referer, cookie, ua);
            remember(intent.getDataString(), referer, cookie, ua);
        } catch (Exception e) {
            Log.w(TAG, "parse lbhdr failed", e);
        }
    }

    public static void remember(String url, String referer, String cookie, String ua) {
        if (url == null || url.isEmpty()) return;
        if (isEmpty(referer) && isEmpty(cookie) && isEmpty(ua)) return;
        if (cookie != null && cookie.length() > MAX_COOKIE) {
            cookie = cookie.substring(0, MAX_COOKIE);
        }
        var sp = pref();
        if (sp == null) return;
        synchronized (LOCK) {
            var map = read(sp);
            try {
                var json = new JSONObject();
                if (!isEmpty(referer)) json.put("r", referer);
                if (!isEmpty(cookie)) json.put("c", cookie);
                if (!isEmpty(ua)) json.put("u", ua);
                json.put("t", System.currentTimeMillis());
                map.put(url, json);
                trim(map);
            } catch (Exception e) {
                Log.w(TAG, "remember failed", e);
                return;
            }
            sp.edit().putString(KEY, map.toString()).apply();
            CACHE.remove(url);
        }
    }

    public static RequestHeaders forUrl(String url) {
        if (url == null || url.isEmpty()) return EMPTY;
        var cached = CACHE.get(url);
        if (cached != null) return cached;
        var sp = pref();
        if (sp == null) return EMPTY;
        RequestHeaders headers = EMPTY;
        synchronized (LOCK) {
            var json = read(sp).optJSONObject(url);
            if (json != null) {
                headers = new RequestHeaders(json.optString("r", null),
                    json.optString("c", null), json.optString("u", null));
            }
        }
        CACHE.put(url, headers);
        return headers;
    }

    /** HttpURLConnection 用：按浏览器的方式补上头。 */
    public void apply(HttpURLConnection connection) {
        connection.setRequestProperty("User-Agent", userAgent());
        connection.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
        if (!isEmpty(referer)) connection.setRequestProperty("Referer", clean(referer));
        if (!isEmpty(cookie)) connection.setRequestProperty("Cookie", clean(cookie));
    }

    /** 手写 HTTP 请求用：返回若干 "Name: value" 行，已清掉 CR/LF 防止注入。 */
    public List<String> headerLines() {
        var lines = new ArrayList<String>(4);
        lines.add("User-Agent: " + userAgent());
        lines.add("Accept-Language: zh-CN,zh;q=0.9,en;q=0.8");
        if (!isEmpty(referer)) lines.add("Referer: " + clean(referer));
        if (!isEmpty(cookie)) lines.add("Cookie: " + clean(cookie));
        return lines;
    }

    private String userAgent() {
        return isEmpty(ua) ? UserAgent.CHROME_ANDROID : clean(ua);
    }

    private static SharedPreferences pref() {
        var ctx = context;
        return ctx == null ? null : ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    private static JSONObject read(SharedPreferences sp) {
        try {
            return new JSONObject(sp.getString(KEY, "{}"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    /** 超过上限就丢最旧的记录，别让这个文件无限长。 */
    private static void trim(JSONObject map) {
        while (map.length() > MAX_RECORDS) {
            String oldest = null;
            long oldestAt = Long.MAX_VALUE;
            for (var it = map.keys(); it.hasNext(); ) {
                var key = it.next();
                var item = map.optJSONObject(key);
                var at = item == null ? 0 : item.optLong("t", 0);
                if (at < oldestAt) {
                    oldestAt = at;
                    oldest = key;
                }
            }
            if (oldest == null) return;
            map.remove(oldest);
            CACHE.remove(oldest);
        }
    }

    private static byte[] decode(String raw) {
        var builder = new StringBuilder(raw.replace('-', '+').replace('_', '/'));
        while (builder.length() % 4 != 0) builder.append('=');
        return Base64.decode(builder.toString(), Base64.DEFAULT);
    }

    private static String clean(String value) {
        return value.replace('\r', ' ').replace('\n', ' ');
    }

    private static boolean isEmpty(String value) {
        return value == null || value.isEmpty();
    }
}
