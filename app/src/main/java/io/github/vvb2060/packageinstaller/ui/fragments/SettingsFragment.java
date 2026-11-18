package io.github.vvb2060.packageinstaller.ui.fragments;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Handler;
import android.os.Looper;
import android.widget.CheckBox;
import android.widget.Toast;

import io.github.vvb2060.packageinstaller.R;
import io.github.vvb2060.packageinstaller.core.KeepAlive;
import io.github.vvb2060.packageinstaller.core.Privilege;
import io.github.vvb2060.packageinstaller.installmodel.SettingsUserAction;
import io.github.vvb2060.packageinstaller.service.KeepAliveService;
import io.github.vvb2060.packageinstaller.ui.CardDialog;
import io.github.vvb2060.packageinstaller.ui.InstallLaunch;

/** 设置：安全模式、后台保活、设为默认安装器。 */
public final class SettingsFragment extends BaseDialogFragment {

    private final SettingsUserAction mDialogData;

    public SettingsFragment(SettingsUserAction dialogData) {
        super(dialogData);
        mDialogData = dialogData;
    }

    @Override
    protected void onBuild(CardDialog card) {
        var context = requireContext();
        card.icon(R.mipmap.ic_app_logo)
            .title(R.string.app_name)
            .message(R.string.settings_safe_mode);

        var safeModeBox = new CheckBox(context);
        safeModeBox.setText(R.string.safe_mode);
        safeModeBox.setTextSize(13);
        safeModeBox.setButtonTintList(ColorStateList.valueOf(context.getColor(R.color.ui_primary)));
        safeModeBox.setChecked(mDialogData.safeMode);
        card.addContent(safeModeBox);

        var keepAliveBox = new CheckBox(context);
        keepAliveBox.setText(R.string.keepalive_title);
        keepAliveBox.setTextSize(13);
        keepAliveBox.setButtonTintList(ColorStateList.valueOf(context.getColor(R.color.ui_primary)));
        keepAliveBox.setChecked(KeepAlive.isEnabled(context));
        card.addContent(keepAliveBox);

        var defaultBox = new CheckBox(context);
        defaultBox.setText(R.string.default_installer_title);
        defaultBox.setTextSize(13);
        defaultBox.setButtonTintList(ColorStateList.valueOf(context.getColor(R.color.ui_primary)));
        defaultBox.setChecked(Privilege.isDefaultInstaller());
        card.addContent(defaultBox);

        card.button(getString(android.R.string.cancel), R.style.Button_Pill_Outline,
            v -> finishOrDismiss());
        card.button(getString(android.R.string.ok), R.style.Button_Pill, v -> {
            if (safeModeBox.isChecked() != mDialogData.safeMode) {
                mViewModel.setSafeMode(safeModeBox.isChecked());
            }
            applyKeepAlive(keepAliveBox.isChecked());
            applyDefaultInstaller(defaultBox.isChecked());
            finishOrDismiss();
        });
    }

    private void applyKeepAlive(boolean enabled) {
        var context = requireContext().getApplicationContext();
        if (enabled == KeepAlive.isEnabled(context)) return;
        KeepAlive.setEnabled(context, enabled);
        if (!enabled) {
            KeepAliveService.stop(context);
            runAction(KeepAlive::stopRootWatchdog);
            toast(R.string.keepalive_off);
            return;
        }
        KeepAliveService.start(context);
        toast(R.string.keepalive_on);
        runAction(() -> {
            if (Privilege.modeOrUnknown() != Privilege.MODE_ROOT) return;
            KeepAlive.applyRootTweaks(context);
            KeepAlive.startRootWatchdog(context);
        });
    }

    /** 勾上就设为默认（需要特权），取消勾选就清掉偏好回到系统默认。 */
    private void applyDefaultInstaller(boolean enabled) {
        if (enabled == Privilege.isDefaultInstaller()) return;
        if (!enabled) {
            var error = Privilege.clearDefaultInstaller();
            toast(error == null ? R.string.default_cleared
                : R.string.default_chooser_failed);
            return;
        }
        if (Privilege.modeOrUnknown() == Privilege.MODE_NONE) {
            showChooser();
            return;
        }
        runAction(() -> {
            var error = Privilege.makeDefaultInstaller();
            new Handler(Looper.getMainLooper()).post(() -> {
                if (!isAdded()) return;
                Toast.makeText(requireContext(),
                    error == null ? getString(R.string.default_done)
                        : getString(R.string.result_failed, error),
                    Toast.LENGTH_LONG).show();
                if (error != null) showChooser();
            });
        });
    }

    private void showChooser() {
        var intent = new Intent(Intent.ACTION_VIEW)
            .setType("application/vnd.android.package-archive")
            .putExtra(InstallLaunch.EXTRA_SET_DEFAULT, true);
        try {
            startActivity(Intent.createChooser(intent,
                getString(R.string.default_chooser_title)));
        } catch (RuntimeException e) {
            toast(R.string.default_chooser_failed);
        }
    }

    private void toast(int resId) {
        Toast.makeText(requireContext(), resId, Toast.LENGTH_SHORT).show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
