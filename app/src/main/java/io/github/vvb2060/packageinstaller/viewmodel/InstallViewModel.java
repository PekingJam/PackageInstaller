package io.github.vvb2060.packageinstaller.viewmodel;

import android.Manifest;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.os.ParcelFileDescriptor;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import io.github.vvb2060.packageinstaller.BuildConfig;
import io.github.vvb2060.packageinstaller.core.BinderWrapper;
import io.github.vvb2060.packageinstaller.core.InstallRepository;
import io.github.vvb2060.packageinstaller.core.InstallRunner;
import io.github.vvb2060.packageinstaller.core.Privilege;
import io.github.vvb2060.packageinstaller.core.PreferredActivity;
import io.github.vvb2060.packageinstaller.installmodel.DownloadProgress;
import io.github.vvb2060.packageinstaller.R;
import io.github.vvb2060.packageinstaller.apk.ApkLite;
import io.github.vvb2060.packageinstaller.apk.ZipParser;
import io.github.vvb2060.packageinstaller.core.InstallRunner;
import io.github.vvb2060.packageinstaller.datamodel.FileDataSource;
import io.github.vvb2060.packageinstaller.datamodel.ResumableDownload;
import io.github.vvb2060.packageinstaller.service.InstallService;
import io.github.vvb2060.packageinstaller.datamodel.DownloadProbe;
import io.github.vvb2060.packageinstaller.datamodel.DownloadTask;
import io.github.vvb2060.packageinstaller.datamodel.Urls;
import io.github.vvb2060.packageinstaller.installmodel.DownloadConfirm;
import io.github.vvb2060.packageinstaller.installmodel.InstallAborted;
import io.github.vvb2060.packageinstaller.installmodel.DownloadProgress;
import io.github.vvb2060.packageinstaller.installmodel.InstallInstalling;
import io.github.vvb2060.packageinstaller.installmodel.InstallParse;
import io.github.vvb2060.packageinstaller.installmodel.InstallFailed;
import io.github.vvb2060.packageinstaller.installmodel.InstallStage;
import io.github.vvb2060.packageinstaller.installmodel.InstallSuccess;
import io.github.vvb2060.packageinstaller.provider.ShizukuProvider;
import rikka.shizuku.Shizuku;

public class InstallViewModel extends AndroidViewModel {

    private final Executor executor;
    private final MutableLiveData<InstallStage> installResult;
    private final MutableLiveData<Integer> stagingProgress;
    private final MutableLiveData<DownloadProgress> downloadProgress;
    private final SharedPreferences sp;

    private InstallRepository repository;
    private boolean cancelled;

    public InstallViewModel(@NonNull Application application) {
        super(application);
        executor = Executors.newFixedThreadPool(1);
        installResult = new MutableLiveData<>(new InstallParse());
        stagingProgress = new MutableLiveData<>(-1);
        downloadProgress = new MutableLiveData<>();
        sp = application.getSharedPreferences(BuildConfig.APPLICATION_ID, Context.MODE_PRIVATE);
    }

    public LiveData<InstallStage> getCurrentInstallStage() {
        return installResult;
    }

    public LiveData<Integer> getStagingProgress() {
        return stagingProgress;
    }

    public LiveData<DownloadProgress> getDownloadProgress() {
        return downloadProgress;
    }

    public void preprocessIntent(Intent intent) {
        executor.execute(() -> {
            try {
                preprocess(intent);
            } catch (Throwable e) {
                Log.e("InstallViewModel", "preprocess failed", e);
                installResult.postValue(new InstallAborted(InstallAborted.ABORT_PARSE,
                    e.toString()));
            }
        });
    }

    private void preprocess(Intent intent) {
        var context = getApplication();
        var pm = context.getPackageManager();
        Privilege.attach(context);

        // 普通文件下载不需要特权，先分流，免得没 Root 的用户连下载都用不了
        var url = extractUrl(intent);
        if (url != null) {
            // 探测失败（很多站点禁 HEAD）就按后缀猜，保证 APK 和其他文件一定能分开
            var probe = probeQuietly(url);
            if (probe == null) probe = DownloadProbe.guess(url);
            if (!probe.clearlyApk()) {
                if (isWebPage(probe.mime)) {
                    installResult.postValue(new InstallAborted(InstallAborted.ABORT_PARSE,
                        context.getString(R.string.download_not_a_file)));
                    return;
                }
                installResult.postValue(new DownloadConfirm(probe.url, probe.name, probe.mime,
                    probe.size, probe.host()));
                return;
            }
        }
        var selected = Privilege.selectedMode();
        if (selected == Privilege.PREF_SHIZUKU) {
            ShizukuProvider.waitForBinder(context);
        } else if (selected == Privilege.PREF_AUTO && !Privilege.rootGranted()) {
            ShizukuProvider.waitForBinder(context);
        }
        var mode = Privilege.mode();
        if (mode == Privilege.MODE_NONE) {
            installResult.postValue(new InstallAborted(InstallAborted.ABORT_PRIVILEGE));
            return;
        }
        if (mode == Privilege.MODE_SHIZUKU && !Privilege.shizukuCanInstall()) {
            var packageName = rikka.shizuku.ShizukuProvider.MANAGER_APPLICATION_ID;
            installResult.postValue(new InstallAborted(InstallAborted.ABORT_SHIZUKU,
                pm.getLaunchIntentForPackage(packageName)));
            return;
        }
        if (cancelled) return;
        Privilege.prepare();

        var safeMode = mode != Privilege.MODE_ROOT
            && sp.getBoolean("safe_mode", needSafeMode());
        repository = new InstallRepository(context, safeMode, installResult, stagingProgress,
                downloadProgress);
        addCloseable(repository);
        if (cancelled) {
            repository.close();
            return;
        }
        repository.parseUri(intent);
    }

    public void initiateInstall(boolean setInstaller, boolean commit,
                                boolean full, boolean removeSplit) {
        if (cancelled) {
            installResult.setValue(new InstallAborted(InstallAborted.ABORT_WRITE));
            return;
        }
        executor.execute(() -> {
            try {
                var apk = repository.getApkLite();
                var parser = repository.getZipParser();
                if (apk == null || parser == null) {
                    installResult.postValue(new InstallAborted(InstallAborted.ABORT_WRITE,
                        "缺少安装包来源"));
                    return;
                }
                InstallRunner.run(getApplication(), apk, parser, setInstaller, full,
                    new InstallRunner.Callback() {
                        @Override
                        public void onProgress(int percent) {
                            stagingProgress.postValue(percent);
                        }

                        @Override
                        public void onSuccess() {
                            installResult.postValue(new InstallSuccess(apk,
                                Privilege.launchIntent(apk.packageName), false));
                        }

                        @Override
                        public void onFailure(String reason) {
                            installResult.postValue(new InstallFailed(apk,
                                PackageInstaller.STATUS_FAILURE, reason));
                        }
                    });
            } catch (Throwable e) {
                Log.e("InstallViewModel", "install failed", e);
                installResult.postValue(new InstallAborted(InstallAborted.ABORT_WRITE,
                    e.toString()));
            }
        });
    }

    /** 从外部 Intent 里抠出 http(s) 链接；没有就返回 null。 */
    private static String extractUrl(Intent intent) {
        // linkbridge 的 pium:// 转交：真实链接在 url extra 里（URL-safe base64）
        var packed = intent.getStringExtra("url");
        if (packed != null && !packed.isEmpty()) {
            var decoded = unpackUrl(packed);
            if (decoded != null) return decoded;
        }
        var uri = intent.getData();
        if (uri != null) {
            var scheme = uri.getScheme();
            // App Link 转交：https://dl.889969.xyz/pium/?u=<base64url 的真实链接>
            if ("https".equals(scheme) && "dl.889969.xyz".equals(uri.getHost())
                && uri.getPath() != null && uri.getPath().startsWith("/pium/")) {
                var packedLink = uri.getQueryParameter("u");
                if (packedLink != null) {
                    var decoded = unpackUrl(packedLink);
                    if (decoded != null) return decoded;
                }
            }
            if ("http".equals(scheme) || "https".equals(scheme)) return uri.toString();
        }
        // 分享过来的往往是「标题 + 链接」甚至一段话，取里面第一段链接
        return Urls.first(intent.getStringExtra(Intent.EXTRA_TEXT));
    }

    /** 解开 pium:// 携带的链接；不是 base64 时按明文处理。 */
    private static String unpackUrl(String value) {
        try {
            var builder = new StringBuilder(value.replace('-', '+').replace('_', '/'));
            while (builder.length() % 4 != 0) builder.append('=');
            var text = new String(Base64.decode(builder.toString(), Base64.DEFAULT),
                java.nio.charset.StandardCharsets.UTF_8);
            if (Urls.isHttp(text)) return text;
        } catch (RuntimeException ignored) {
        }
        return Urls.isHttp(value) ? value : null;
    }

    private static DownloadProbe probeQuietly(String url) {
        try {
            return DownloadProbe.probe(url);
        } catch (Exception e) {
            Log.w("InstallViewModel", "probe failed: " + url, e);
            return null;
        }
    }

    private static boolean isWebPage(String mime) {
        return mime != null && mime.toLowerCase(java.util.Locale.ROOT).startsWith("text/html");
    }

    /**
     * 前台安装：留在弹窗里显示进度。网络来源先把包完整下载下来（带进度），再从文件安装；
     * 本地来源直接写安装会话。
     */
    private volatile boolean handoff;

    /**
     * 前台下载安装途中「转后台」：让前台循环自行退出（.part 保留），
     * 再由 InstallService 续传安装。不能直接起 Service —— 两个线程写同一个
     * .part 会把文件写坏，所以必须先停前台再交接。
     */
    public void moveToBackground() {
        handoff = true;
    }

    /** 前台下载过程中登记的下载任务 id；列表里同步显示这条下载。 */
    private volatile String trackedTaskId;

    public void initiateForegroundInstall(boolean setInstaller, boolean full) {
        if (cancelled) {
            installResult.setValue(new InstallAborted(InstallAborted.ABORT_WRITE));
            return;
        }
        executor.execute(() -> {
            try {
                var apk = repository.getApkLite();
                var parser = repository.getZipParser();
                if (apk == null || parser == null) {
                    installResult.postValue(new InstallAborted(InstallAborted.ABORT_WRITE,
                        "缺少安装包来源"));
                    return;
                }
                installResult.postValue(new InstallInstalling(apk, repository.getSourceUrl() != null));
                var url = repository.getSourceUrl();
                if (url != null) {
                    // 前台下载同样写进下载任务列表，免得关掉弹窗就「凭空消失」
                    var taskId = DownloadTask.idOf(url);
                    trackedTaskId = taskId;
                    var taskName = apk.getLabel();
                    if (taskName == null || taskName.isEmpty()) {
                        taskName = DownloadProbe.guess(url).name;
                    }
                    var task = new DownloadTask(taskId, url, taskName,
                        DownloadProbe.mimeFromName(taskName), DownloadTask.STATE_ACTIVE);
                    task.kind = DownloadTask.KIND_INSTALL;
                    DownloadTask.upsert(getApplication(), task);

                    var lastReport = new long[]{0L};
                    var file = ResumableDownload.download(getApplication(), url,
                        (downloaded, total, speed) -> {
                            downloadProgress.postValue(new DownloadProgress(downloaded, total, speed));
                            var now = System.currentTimeMillis();
                            if (now - lastReport[0] > 800) {
                                lastReport[0] = now;
                                DownloadTask.updateProgress(getApplication(), taskId,
                                    downloaded, total);
                            }
                        },
                        () -> cancelled || handoff);
                    DownloadTask.setState(getApplication(), taskId,
                        DownloadTask.STATE_INSTALLING, null, null, file.length(), file.length());
                    try (var pfd = ParcelFileDescriptor.open(file,
                        ParcelFileDescriptor.MODE_READ_ONLY)) {
                        var fileParser = new ZipParser(FileDataSource.asDataSource(pfd));
                        var fileApk = fileParser.parse();
                        installResult.postValue(new InstallInstalling(fileApk, false));
                        runInstall(fileApk, fileParser, setInstaller, full, taskId);
                    } finally {
                        //noinspection ResultOfMethodCallIgnored
                        file.delete();
                    }
                    return;
                }
                runInstall(apk, parser, setInstaller, full, null);
            } catch (Throwable e) {
                if (handoff) {
                    // 用户点了「转后台」：前台已经退出，交给 Service 续传 + 安装。
                    // 任务记录不在这里收尾，Service 会用同一个 id 接着更新。
                    Log.i("InstallViewModel", "handoff to background service");
                    var url2 = repository == null ? null : repository.getSourceUrl();
                    var label = repository == null || repository.getApkLite() == null
                        ? null : repository.getApkLite().getLabel();
                    InstallService.start(getApplication(), null, url2, label,
                        setInstaller, full, true);
                    return;
                }
                Log.e("InstallViewModel", "foreground install failed", e);
                if (trackedTaskId != null) {
                    DownloadTask.setState(getApplication(), trackedTaskId,
                        DownloadTask.STATE_FAILED, null,
                        cancelled ? null : e.toString(), -1, -1);
                }
                installResult.postValue(new InstallAborted(InstallAborted.ABORT_WRITE, e.toString()));
            }
        });
    }

    private void runInstall(ApkLite apk, ZipParser parser, boolean setInstaller, boolean full,
                            String taskId) {
        InstallRunner.run(getApplication(), apk, parser, setInstaller, full,
            new InstallRunner.Callback() {
                private long lastTouch;

                @Override
                public void onProgress(int percent) {
                    stagingProgress.postValue(percent);
                    if (taskId == null) return;
                    var now = System.currentTimeMillis();
                    if (now - lastTouch < 800) return;
                    lastTouch = now;
                    DownloadTask.touch(getApplication(), taskId);
                }

                @Override
                public void onSuccess() {
                    if (taskId != null) {
                        DownloadTask.setState(getApplication(), taskId,
                            DownloadTask.STATE_DONE, null, null, -1, -1);
                    }
                    installResult.postValue(new InstallSuccess(apk,
                        Privilege.launchIntent(apk.packageName), false));
                }

                @Override
                public void onFailure(String reason) {
                    if (taskId != null) {
                        DownloadTask.setState(getApplication(), taskId,
                            DownloadTask.STATE_FAILED, null, reason, -1, -1);
                    }
                    installResult.postValue(new InstallFailed(apk,
                        PackageInstaller.STATUS_FAILURE, reason));
                }
            });
    }

    private static boolean needSafeMode() {
        if (Privilege.mode() != Privilege.MODE_SHIZUKU) return false;
        var packageName = PreferredActivity.getSystemInstaller();
        if (packageName == null) return false;
        try {
            var info = Privilege.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS);
            var perms = info.requestedPermissions;
            if (perms == null) return false;
            var list = List.of(perms);
            if (list.contains(Manifest.permission.UPDATE_PACKAGES_WITHOUT_USER_ACTION)) {
                return false;
            }
            return list.contains(Manifest.permission.INTERNET);
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    public void setSafeMode(boolean safeMode) {
        sp.edit().putBoolean("safe_mode", safeMode).apply();
    }

    public void cleanup() {
        if (cancelled) return;
        cancelled = true;
        // 用户主动放弃：列表里的这条算「未完成」，.part 留着可以继续
        var taskId = trackedTaskId;
        if (taskId != null) {
            DownloadTask.setState(getApplication(), taskId, DownloadTask.STATE_FAILED,
                null, null, -1, -1);
        }
        // 解析阶段还可能在等 su / Shizuku，此时 repository 尚未创建
        if (repository != null) repository.close();
    }

    public boolean isCancelled() {
        return cancelled;
    }
}
