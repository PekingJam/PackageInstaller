package io.github.vvb2060.packageinstaller.ui.fragments;

import android.content.res.ColorStateList;
import android.widget.CheckBox;
import android.widget.TextView;

import io.github.vvb2060.packageinstaller.R;
import io.github.vvb2060.packageinstaller.core.Privilege;
import io.github.vvb2060.packageinstaller.installmodel.PackageUserAction;
import io.github.vvb2060.packageinstaller.ui.CardDialog;

public final class UninstallConfirmationFragment extends BaseDialogFragment {

    private final PackageUserAction mDialogData;

    public UninstallConfirmationFragment(PackageUserAction dialogData) {
        super(dialogData);
        mDialogData = dialogData;
    }

    @Override
    protected void onBuild(CardDialog card) {
        var context = requireContext();
        var apk = mDialogData.apkLite;
        var app = mDialogData.info.applicationInfo;
        var enabled = app == null || app.enabled;
        var system = app != null
            && (app.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0;

        card.icon(apk.getIcon())
            .title(apk.getLabel())
            .message(context.getString(R.string.uninstall_confirm_question));

        var checkBox = new CheckBox(context);
        checkBox.setText(R.string.only_clear_data);
        checkBox.setTextSize(13);
        checkBox.setButtonTintList(ColorStateList.valueOf(context.getColor(R.color.ui_primary)));
        card.addContent(checkBox);

        card.button(getString(android.R.string.cancel), R.style.Button_Pill_Outline,
            v -> finish());
        card.button(getString(enabled ? R.string.disable : R.string.enable),
            R.style.Button_Pill_Tonal, v -> {
                runAction(() -> Privilege.setEnabled(apk.packageName, !enabled));
                finish();
            });
        var positive = card.button(getString(R.string.uninstall), R.style.Button_Pill_Danger,
            v -> {
                var clear = checkBox.isChecked();
                runAction(() -> {
                    if (clear) {
                        Privilege.clearApplicationUserData(apk.packageName);
                    } else {
                        Privilege.uninstall(apk.packageName,
                            mDialogData.info.getLongVersionCode(), system);
                    }
                });
                finish();
            });
        checkBox.setOnCheckedChangeListener((button, checked) ->
            positive.setText(checked ? R.string.clear : R.string.uninstall));
    }
}
