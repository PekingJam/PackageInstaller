package io.github.vvb2060.packageinstaller.ui;

import java.util.Locale;

public final class Format {
    private Format() {
    }

    /** 自适应 B / KB / MB / GB。 */
    public static String size(long bytes) {
        if (bytes < 0) return "--";
        if (bytes < 1024) return bytes + " B";
        var kb = bytes / 1024.0;
        if (kb < 1024) return String.format(Locale.ROOT, "%.0f KB", kb);
        var mb = kb / 1024.0;
        if (mb < 1024) return String.format(Locale.ROOT, "%.1f MB", mb);
        return String.format(Locale.ROOT, "%.2f GB", mb / 1024.0);
    }

    public static String speed(long bytesPerSecond) {
        if (bytesPerSecond <= 0) return "--/s";
        return size(bytesPerSecond) + "/s";
    }
}
