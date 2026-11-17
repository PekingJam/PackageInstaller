package io.github.vvb2060.packageinstaller.ui.fragments;

import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.lifecycle.ViewModelProvider;

import io.github.vvb2060.packageinstaller.core.Privilege;
import io.github.vvb2060.packageinstaller.installmodel.InstallStage;
import io.github.vvb2060.packageinstaller.ui.CardDialog;
import io.github.vvb2060.packageinstaller.ui.InstallLaunch;
import io.github.vvb2060.packageinstaller.viewmodel.InstallViewModel;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public abstract sealed class BaseDialogFragment extends DialogFragment permits
    InstallConfirmationFragment, InstallErrorFragment, InstallFailedFragment,
    InstallInstallingFragment, InstallParseFragment, InstallSuccessFragment,
    UninstallConfirmationFragment, SettingsFragment {

    private static final ExecutorService ACTION_EXECUTOR = Executors.newSingleThreadExecutor();

    public final int stageCode;
    InstallViewModel mViewModel;
    private CardDialog card;

    /** 特权操作会阻塞（su 起进程），统一丢到后台线程。 */
    protected static void runAction(Runnable task) {
        ACTION_EXECUTOR.execute(task);
    }

    public BaseDialogFragment(InstallStage dialogData) {
        stageCode = dialogData.stageCode;
    }

    protected abstract void onBuild(CardDialog card);

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mViewModel = new ViewModelProvider(requireActivity())
            .get(InstallViewModel.class);
    }

    @NonNull
    @Override
    public final Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        card = CardDialog.create(requireContext());
        onBuild(card);
        return card.dialog();
    }

    @Override
    public void onStart() {
        super.onStart();
        if (card != null) card.constrainHeight();
    }

    @Override
    public void onCancel(@NonNull DialogInterface dialog) {
        super.onCancel(dialog);
        // 从主界面打开的弹窗按返回只应关自己，不能把首页也关掉
        finishOrDismiss();
    }

    protected void finish() {
        dismissAllowingStateLoss();
        var activity = getActivity();
        if (activity == null) return;
        var intent = activity.getIntent();
        // 外部（浏览器/文件管理器）带着 NEW_TASK 打开时独占一个 task，需要连 task 一起清掉；
        // 从主界面用 startActivity 进来的和主界面同 task，只 finish 自己。
        var removeTask = intent != null
            && (intent.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0;
        var handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(() -> {
            if (removeTask) {
                activity.finishAndRemoveTask();
            } else {
                activity.finish();
            }
        }, 100);
    }

    /** 仅关闭弹窗本身（主界面打开的子弹窗用）。 */
    protected void dismissDialog() {
        dismissAllowingStateLoss();
    }

    /** 宿主是安装器弹窗页时顺手关掉宿主，从主界面打开时只关子弹窗。 */
    protected void finishOrDismiss() {
        if (getActivity() instanceof InstallLaunch) {
            finish();
        } else {
            dismissDialog();
        }
    }

    protected void startActivitySafe(Intent intent, boolean safeMode) {
        if (intent == null) return;
        if (safeMode) {
            Privilege.startActivity(intent);
            return;
        }
        try {
            requireActivity().startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException ignored) {
        }
    }
}
