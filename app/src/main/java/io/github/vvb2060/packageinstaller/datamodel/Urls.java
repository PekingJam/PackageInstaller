package io.github.vvb2060.packageinstaller.datamodel;

import java.util.Locale;

/** 外部 App 分享过来的东西五花八门：纯链接、带标题的链接、夹在一段话里的链接。 */
public final class Urls {
    private Urls() {
    }

    public static boolean isHttp(String text) {
        return text != null && (text.startsWith("http://") || text.startsWith("https://"));
    }

    /** 取第一段 http(s) 链接，没有就返回 null。 */
    public static String first(String text) {
        if (text == null) return null;
        var lower = text.toLowerCase(Locale.ROOT);
        var http = lower.indexOf("http://");
        var https = lower.indexOf("https://");
        var start = http < 0 ? https : (https < 0 ? http : Math.min(http, https));
        if (start < 0) return null;
        var rest = text.substring(start);
        var end = rest.length();
        var stops = "\"'<>\u3000，。、；：！？）」』】》】";
        for (int i = 0; i < rest.length(); i++) {
            var c = rest.charAt(i);
            if (Character.isWhitespace(c) || stops.indexOf(c) >= 0) {
                end = i;
                break;
            }
        }
        var url = rest.substring(0, end).trim();
        return url.length() > "https://".length() ? url : null;
    }
}
