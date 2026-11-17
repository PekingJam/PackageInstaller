package io.github.vvb2060.packageinstaller.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * 通过 su 执行命令。所有方法都会阻塞，必须在后台线程调用。
 * 设备上没有 su 或用户拒绝授权时 {@link #isAvailable()} 返回 false。
 */
public final class RootShell {
    private static final long DEFAULT_TIMEOUT = 120_000L;

    private static volatile Boolean available;

    private RootShell() {
    }

    public static final class Result {
        public final int code;
        public final String output;

        Result(int code, String output) {
            this.code = code;
            this.output = output == null ? "" : output;
        }

        public boolean ok() {
            var lower = output.toLowerCase();
            return code == 0
                && !output.contains("Failure")
                && !output.contains("Exception")
                && !lower.contains("error:");
        }

        public boolean succeeded() {
            return code == 0 && output.contains("Success");
        }

        public String text() {
            return output.trim();
        }
    }

    public static boolean isAvailable() {
        var cached = available;
        if (cached != null) return cached;
        synchronized (RootShell.class) {
            if (available == null) {
                var result = exec("id", 30_000L);
                available = result.code == 0 && result.output.contains("uid=0");
            }
            return available;
        }
    }

    public static void invalidate() {
        available = null;
    }

    public static String quote(String arg) {
        if (arg == null) return "''";
        return "'" + arg.replace("'", "'\\''") + "'";
    }

    public static Result exec(String command) {
        return exec(command, DEFAULT_TIMEOUT);
    }

    public static Result exec(String command, long timeoutMs) {
        Handle handle;
        try {
            handle = start(command);
        } catch (IOException e) {
            return new Result(-1, e.toString());
        }
        return handle.finish(timeoutMs);
    }

    public static Handle start(String command) throws IOException {
        var process = new ProcessBuilder("su", "-c", command)
            .redirectErrorStream(true)
            .start();
        return new Handle(process);
    }

    /** 一次 su 调用，用于向命令的 stdin 灌数据（例如 pm install-write）。 */
    public static final class Handle {
        private final Process process;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private final Thread reader;

        Handle(Process process) {
            this.process = process;
            reader = new Thread(() -> {
                try (InputStream in = process.getInputStream()) {
                    var buf = new byte[8 * 1024];
                    int read;
                    while ((read = in.read(buf)) != -1) {
                        buffer.write(buf, 0, read);
                    }
                } catch (IOException ignored) {
                }
            }, "root-shell-reader");
            reader.setDaemon(true);
            reader.start();
        }

        public OutputStream stdin() {
            return process.getOutputStream();
        }

        public Result finish(long timeoutMs) {
            try {
                process.getOutputStream().close();
            } catch (IOException ignored) {
            }
            try {
                if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                    join();
                    return new Result(-1, text() + "\n(timeout)");
                }
                join();
                return new Result(process.exitValue(), text());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
                return new Result(-1, "interrupted");
            }
        }

        private void join() {
            try {
                reader.join(3_000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private String text() {
            return buffer.toString(StandardCharsets.UTF_8);
        }
    }
}
