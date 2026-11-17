package io.github.vvb2060.packageinstaller.core;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.Intent_rename;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.provider.OpenableColumns;
import android.util.Log;

import androidx.lifecycle.MutableLiveData;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.net.URI;

import javax.net.ssl.SSLContext;

import io.github.vvb2060.packageinstaller.apk.ApkLite;
import io.github.vvb2060.packageinstaller.apk.ZipParser;
import io.github.vvb2060.packageinstaller.apksig.Block;
import io.github.vvb2060.packageinstaller.apksig.Parser;
import io.github.vvb2060.packageinstaller.datamodel.FileDataSource;
import io.github.vvb2060.packageinstaller.datamodel.DownloadEngine;
import io.github.vvb2060.packageinstaller.datamodel.DownloadProbe;
import io.github.vvb2060.packageinstaller.datamodel.NetDataSource;
import io.github.vvb2060.packageinstaller.installmodel.DownloadProgress;
import io.github.vvb2060.packageinstaller.installmodel.InstallAborted;
import io.github.vvb2060.packageinstaller.installmodel.DownloadConfirm;
import io.github.vvb2060.packageinstaller.installmodel.InstallParse;
import io.github.vvb2060.packageinstaller.installmodel.InstallStage;
import io.github.vvb2060.packageinstaller.installmodel.InstallUserAction;
import io.github.vvb2060.packageinstaller.installmodel.PackageUserAction;
import io.github.vvb2060.packageinstaller.installmodel.SettingsUserAction;

/**
 * 负责「看懂要装什么」：解析 URI / URL，产出确认阶段需要的 InstallStage。
 * 真正的写包与提交在 {@link InstallRunner}（界面弹窗和后台 Service 共用）。
 */
public class InstallRepository implements Closeable {
    private static final String TAG = InstallRepository.class.getSimpleName();

    private final MutableLiveData<InstallStage> installResult;
    private final MutableLiveData<Integer> stagingProgress;
    private final MutableLiveData<DownloadProgress> downloadProgress;
    private final ContentResolver contentResolver;
    private final PackageManager packageManager;
    private final Context context;
    private final boolean safeMode;
    private int callingUid = Process.INVALID_UID;
    private ApkLite apkLite;
    private ZipParser zipParser;
    private Closeable closeable;
    private String downloadedPath;
    private volatile boolean cancelled;

    public InstallRepository(Context context, boolean safeMode,
                             MutableLiveData<InstallStage> installResult,
                             MutableLiveData<Integer> stagingProgress) {
        this(context, safeMode, installResult, stagingProgress, null);
    }

    public InstallRepository(Context context, boolean safeMode,
                             MutableLiveData<InstallStage> installResult,
                             MutableLiveData<Integer> stagingProgress,
                             MutableLiveData<DownloadProgress> downloadProgress) {
        this.context = context.getApplicationContext();
        this.contentResolver = context.getContentResolver();
        this.packageManager = context.getPackageManager();
        this.safeMode = safeMode;
        this.installResult = installResult;
        this.stagingProgress = stagingProgress;
        this.downloadProgress = downloadProgress;
    }

    public void parseUri(Intent intent) {
        installResult.postValue(new InstallParse());
        Log.v(TAG, "Intent: " + intent);
        callingUid = intent.getIntExtra(Intent_rename.EXTRA_ORIGINATING_UID, Process.INVALID_UID);

        var uri = intent.getData();
        if (uri == null) {
            uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        }
        if (uri != null) {
            var scheme = uri.getScheme();
            if ("package".equals(scheme)) {
                installResult.postValue(processPackageUri(uri.getSchemeSpecificPart()));
                return;
            }
            if ("market".equals(scheme) && "details".equals(uri.getAuthority())) {
                var packageName = uri.getQueryParameter("id");
                if (packageName != null) {
                    installResult.postValue(processPackageUri(packageName));
                    return;
                }
            }
            if (ContentResolver.SCHEME_CONTENT.equals(scheme)
                || ContentResolver.SCHEME_FILE.equals(scheme)) {
                installResult.postValue(processContentUri(uri));
                return;
            }
            if ("https".equals(scheme) || "http".equals(scheme)) {
                installResult.postValue(processUrl(uri.toString()));
                return;
            }
        }
        var packageName = intent.getStringExtra(Intent.EXTRA_PACKAGE_NAME);
        if (packageName != null) {
            installResult.postValue(processPackageUri(packageName));
            return;
        }
        var url = intent.getStringExtra(Intent.EXTRA_TEXT);
        if (url != null) {
            installResult.postValue(processUrl(url.trim()));
            return;
        }
        installResult.postValue(new SettingsUserAction(safeMode));
    }

    /**
     * 网络安装只做「预览」：用带 Range 的随机读取拿到中央目录 / AndroidManifest /
     * 签名块（几百 KB），不把整个包拉下来。确认之后由 InstallService 负责续传下载 + 安装。
     */
    private InstallStage processUrl(String url) {
        Log.v(TAG, "URL: " + url);
        var probe = probeQuietly(url);
        if (probe != null && probe.mime != null
            && probe.mime.toLowerCase(java.util.Locale.ROOT).startsWith("text/html")) {
            return new InstallAborted(InstallAborted.ABORT_PARSE, "这是一个网页链接，不是文件");
        }
        if (probe != null && !probe.looksLikeApk()) {
            // 明确不是安装包（mp4/pdf/jpg…），直接走下载确认
            return new DownloadConfirm(probe.url, probe.name, probe.mime, probe.size, probe.host());
        }
        ApkLite parsedApk;
        try {
            var engine = new DownloadEngine(SSLContext.getDefault());
            closeable = engine;
            var dataSource = NetDataSource.open(engine, new URI(url));
            zipParser = new ZipParser(dataSource);
            parsedApk = zipParser.parse();
            dataSource.stopPersistentCache();
        } catch (Exception | LinkageError e) {
            if (cancelled) return new InstallAborted(InstallAborted.ABORT_CLOSE);
            Log.e(TAG, "Failed to preview: " + url, e);
            if (probe != null) {
                // 当成 APK 试过但解析不了（或者是普通文件），降级成下载
                return new DownloadConfirm(probe.url, probe.name, probe.mime, probe.size,
                    probe.host());
            }
            return new InstallAborted(InstallAborted.ABORT_PARSE, e.toString());
        }

        var stage = processApkLite(parsedApk, null, url);
        if (parsedApk.label == null) {
            var name = Uri.parse(url).getLastPathSegment();
            if (name != null) parsedApk.label = name;
        }
        return stage;
    }

    private static DownloadProbe probeQuietly(String url) {
        try {
            return DownloadProbe.probe(url);
        } catch (Exception e) {
            Log.w(TAG, "probe failed: " + url, e);
            return null;
        }
    }

    private InstallStage processContentUri(Uri uri) {
        Log.v(TAG, "URI: " + uri);
        ApkLite parsedApk;
        try {
            var afd = contentResolver.openAssetFileDescriptor(uri, "r");
            if (afd == null) throw new IOException("Failed to open uri");
            closeable = afd;
            var dataSource = FileDataSource.asDataSource(afd);
            zipParser = new ZipParser(dataSource);
            parsedApk = zipParser.parse();
        } catch (Exception | LinkageError e) {
            if (cancelled) return new InstallAborted(InstallAborted.ABORT_CLOSE);
            Log.e(TAG, "Failed to parse APK: " + uri, e);
            return new InstallAborted(InstallAborted.ABORT_PARSE, e.toString());
        }

        if (callingUid == Process.INVALID_UID && uri.getAuthority() != null) {
            var provider = packageManager.resolveContentProvider(uri.getAuthority(), 0);
            if (provider != null && provider.applicationInfo != null) {
                callingUid = provider.applicationInfo.uid;
            }
        }

        var stage = processApkLite(parsedApk, null, uri.toString());
        if (parsedApk.label == null) {
            try (var cursor = contentResolver.query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    var displayNameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (displayNameIndex != -1) {
                        parsedApk.label = cursor.getString(displayNameIndex);
                    }
                }
            } catch (RuntimeException ignored) {
            }
        }
        return stage;
    }

    private InstallStage processApkLite(ApkLite apk, String sourcePath, String sourceUri) {
        PackageInfo old;
        try {
            old = Privilege.getPackageInfo(apk.packageName, 0);
        } catch (PackageManager.NameNotFoundException ignored) {
            old = null;
        }

        var full = true;
        if (apk.isSplit()) {
            if (old != null && old.getLongVersionCode() == apk.versionCode) {
                // 给已装应用补 split：用 MODE_INHERIT_EXISTING
                full = false;
            } else {
                return new InstallAborted(InstallAborted.ABORT_SPLIT);
            }
        }

        if (old != null) {
            if (apk.label == null) {
                apk.label = old.applicationInfo.loadLabel(packageManager).toString();
            }
            if (apk.icon == null) {
                apk.icon = old.applicationInfo.loadIcon(packageManager);
            }
        }

        if (full && old != null && Block.findBlock(apk.signingBlock) != null) {
            var base = old.applicationInfo.sourceDir;
            try (var pfd = ParcelFileDescriptor.open(new File(base),
                ParcelFileDescriptor.MODE_READ_ONLY)) {
                var dataSource = FileDataSource.asDataSource(pfd);
                var oldMap = Parser.perse(dataSource);
                apk.isSame = Block.check(oldMap, apk.signingBlock);
            } catch (IOException | RuntimeException e) {
                Log.w(TAG, "Failed to parse APK: " + base, e);
            }
        }

        apkLite = apk;
        var setInstaller = !safeMode;
        return new InstallUserAction(apk, old, full, setInstaller, sourcePath, sourceUri);
    }

    private InstallStage processPackageUri(String packageName) {
        PackageInfo info;
        try {
            info = Privilege.getPackageInfo(packageName, 0);
        } catch (PackageManager.NameNotFoundException e) {
            return new InstallAborted(InstallAborted.ABORT_NOTFOUND, e.toString());
        }

        var apk = ApkLite.fromPackageInfo(packageManager, info);
        apkLite = apk;

        var app = info.applicationInfo;
        if ((app.flags & ApplicationInfo.FLAG_INSTALLED) != 0) {
            return new PackageUserAction(apk, info);
        } else if (app.sourceDir != null && new File(app.sourceDir).exists()) {
            var action = new InstallUserAction(apk, info, true, !safeMode, null, null);
            action.installExisting = true;
            return action;
        }
        return processUrl("https://d.apkpure.com/b/XAPK/" + packageName + "?version=latest");
    }

    /** 安装包来源（供 Service 使用）。 */
    public ZipParser getZipParser() {
        return zipParser;
    }

    public ApkLite getApkLite() {
        return apkLite;
    }

    @Override
    public void close() {
        cancelled = true;
        if (closeable != null) {
            try {
                closeable.close();
            } catch (IOException ignored) {
            }
            closeable = null;
        }
    }
}
