package io.github.vvb2060.packageinstaller.core;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import io.github.vvb2060.packageinstaller.BuildConfig;
import io.github.vvb2060.packageinstaller.datamodel.UserAgent;

/**
 * 检查更新：读直链站上的 pium-latest.json（publish.sh 发布时自动写入）。
 * 只做「有没有比当前更新的版本」判断，不做增量。
 */
public final class UpdateChecker {
    public static final String MANIFEST_URL = "https://dl.889969.xyz/pium-latest.json";

    public static final class Release {
        public final int versionCode;
        public final String versionName;
        public final String url;
        public final String notes;

        Release(int versionCode, String versionName, String url, String notes) {
            this.versionCode = versionCode;
            this.versionName = versionName;
            this.url = url;
            this.notes = notes;
        }
    }

    private UpdateChecker() {
    }

    /** 有更新返回 Release，已是最新返回 null。异常向上抛，交给界面提示。 */
    public static Release check() throws Exception {
        var connection = (HttpURLConnection) new URL(MANIFEST_URL + "?t="
            + System.currentTimeMillis()).openConnection();
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(10_000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", UserAgent.CHROME_ANDROID);
        try {
            var code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new Exception("HTTP " + code);
            }
            var builder = new StringBuilder();
            try (var reader = new BufferedReader(new InputStreamReader(
                connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) builder.append(line);
            }
            var json = new JSONObject(builder.toString());
            var versionCode = json.optInt("versionCode", 0);
            if (versionCode <= BuildConfig.VERSION_CODE) return null;
            return new Release(versionCode,
                json.optString("versionName", String.valueOf(versionCode)),
                json.optString("url", ""),
                json.optString("notes", ""));
        } finally {
            connection.disconnect();
        }
    }
}
