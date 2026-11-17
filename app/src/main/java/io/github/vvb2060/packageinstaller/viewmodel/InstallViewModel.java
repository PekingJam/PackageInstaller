package io.github.vvb2060.packageinstaller.viewmodel;

import android.Manifest;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

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
import io.github.vvb2060.packageinstaller.core.Privilege;
import io.github.vvb2060.packageinstaller.core.PreferredActivity;
import io.github.vvb2060.packageinstaller.installmodel.InstallAborted;
import io.github.vvb2060.packageinstaller.installmodel.InstallParse;
import io.github.vvb2060.packageinstaller.installmodel.InstallStage;
import io.github.vvb2060.packageinstaller.provider.ShizukuProvider;
import rikka.shizuku.Shizuku;

public class InstallViewModel extends AndroidViewModel {

    private final Executor executor;
    private final MutableLiveData<InstallStage> installResult;
    private final MutableLiveData<Integer> stagingProgress;
    private final SharedPreferences sp;

    private InstallRepository repository;
    private boolean cancelled;

    public InstallViewModel(@NonNull Application application) {
        super(application);
        executor = Executors.newFixedThreadPool(1);
        installResult = new MutableLiveData<>(new InstallParse());
        stagingProgress = new MutableLiveData<>(-1);
        sp = application.getSharedPreferences(BuildConfig.APPLICATION_ID, Context.MODE_PRIVATE);
    }

    public LiveData<InstallStage> getCurrentInstallStage() {
        return installResult;
    }

    public LiveData<Integer> getStagingProgress() {
        return stagingProgress;
    }

    public void preprocessIntent(Intent intent) {
        executor.execute(() -> {
            var context = getApplication();
            var pm = context.getPackageManager();
            Privilege.attach(context);
            if (!Privilege.rootGranted()) {
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
            repository = new InstallRepository(context, safeMode, installResult, stagingProgress);
            addCloseable(repository);
            if (cancelled) {
                repository.close();
                return;
            }
            repository.parseUri(intent);
        });
    }

    public void initiateInstall(boolean setInstaller, boolean commit,
                                boolean full, boolean removeSplit) {
        if (cancelled) {
            installResult.setValue(new InstallAborted(InstallAborted.ABORT_WRITE));
            return;
        }
        executor.execute(() ->
            repository.install(setInstaller, commit, full, removeSplit)
        );
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
        // 解析阶段还可能在等 su / Shizuku，此时 repository 尚未创建
        if (repository != null) repository.close();
    }

    public boolean isCancelled() {
        return cancelled;
    }
}
