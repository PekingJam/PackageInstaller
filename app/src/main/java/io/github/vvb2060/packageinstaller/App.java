package io.github.vvb2060.packageinstaller;

import android.app.Application;
import android.content.Context;

import org.lsposed.hiddenapibypass.LSPass;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.util.Date;

public class App extends Application {
    private static final String CRASH_FILE = "last_crash.txt";

    @Override
    public void onCreate() {
        super.onCreate();
        // ApkAssets / XmlBlock 等隐藏 API：Root 模式下不会经过 BinderWrapper 的静态块，
        // 这里统一放开，否则解析本地 APK 会抛 NoSuchMethodError 直接杀进程。
        try {
            LSPass.setHiddenApiExemptions("");
        } catch (Throwable ignored) {
        }
        io.github.vvb2060.packageinstaller.core.Privilege.attach(this);
        io.github.vvb2060.packageinstaller.datamodel.RequestHeaders.init(this);

        var previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            writeCrash(throwable);
            if (previous != null) previous.uncaughtException(thread, throwable);
        });
    }

    private void writeCrash(Throwable throwable) {
        try (var writer = new PrintWriter(new FileOutputStream(crashFile(this), false))) {
            writer.println(new Date().toString());
            throwable.printStackTrace(writer);
        } catch (Throwable ignored) {
        }
    }

    public static File crashFile(Context context) {
        return new File(context.getFilesDir(), CRASH_FILE);
    }
}
