package io.github.vvb2060.packageinstaller.ui.fragments;

import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Handler;
import android.os.Looper;
import android.widget.CheckBox;
import android.widget.Toast;

import io.github.vvb2060.packageinstaller.R;
import io.github.vvb2060.packageinstaller.core.Privilege;
import io.github.vvb2060.packageinstaller.installmodel.SettingsUserAction;
import io.github.vvb2060.packageinstaller.ui.CardDialog;
import io.github.vvb2060.packageinstaller.ui.InstallLaunch;

/** 设置：安全模式、设为默认安装器。 */
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
            applyDefaultInstaller(defaultBox.isChecked());
            finishOrDismiss();
        });
    }

    /** 勾上就设为默认（需要特权），取消勾选就清掉偏好回到系统默认。 */
    private void applyDefaultInstaller(boolean enabled) {
        if (enabled == Privilege.isDefaultInstaller()) return;
        var appContext = requireContext().getApplicationContext();
        if (!enabled) {
            var error = Privilege.clearDefaultInstaller();
            toast(error == null ? R.string.default_cleared
                : R.string.default_chooser_failed);
            return;
        }
        if (Privilege.modeOrUnknown() == Privilege.MODE_NONE) {
            showChooser(appContext);
            return;
        }
        runAction(() -> {
            var error = Privilege.makeDefaultInstaller();
            new Handler(Looper.getMainLooper()).post(() -> {
                // 对话框这时已经关掉了，不能用 fragment 的 context
                Toast.makeText(appContext,
                    error == null ? appContext.getString(R.string.default_done)
                        : appContext.getString(R.string.result_failed, error),
                    Toast.LENGTH_LONG).show();
                if (error != null) showChooser(appContext);
            });
        });
    }

    private static void showChooser(Context context) {
        var intent = new Intent(Intent.ACTION_VIEW)
            .setType("application/vnd.android.package-archive")
            .putExtra(InstallLaunch.EXTRA_SET_DEFAULT, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(Intent.createChooser(intent,
                    context.getString(R.string.default_chooser_title))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            Toast.makeText(context, R.string.default_chooser_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void toast(int resId) {
        Toast.makeText(requireContext(), resId, Toast.LENGTH_SHORT).show();
    }
}
