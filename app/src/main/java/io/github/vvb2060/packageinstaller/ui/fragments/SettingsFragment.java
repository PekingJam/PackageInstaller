package io.github.vvb2060.packageinstaller.ui.fragments;

import android.content.res.ColorStateList;
import android.widget.CheckBox;

import io.github.vvb2060.packageinstaller.R;
import io.github.vvb2060.packageinstaller.installmodel.SettingsUserAction;
import io.github.vvb2060.packageinstaller.ui.CardDialog;

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

        var checkBox = new CheckBox(context);
        checkBox.setText(R.string.safe_mode);
        checkBox.setTextSize(13);
        checkBox.setButtonTintList(ColorStateList.valueOf(context.getColor(R.color.ui_primary)));
        checkBox.setChecked(mDialogData.safeMode);
        card.addContent(checkBox);

        card.button(getString(android.R.string.cancel), R.style.Button_Pill_Outline,
            v -> finishOrDismiss());
        card.button(getString(android.R.string.ok), R.style.Button_Pill, v -> {
            var checked = checkBox.isChecked();
            if (checked != mDialogData.safeMode) {
                mViewModel.setSafeMode(checked);
            }
            finishOrDismiss();
        });
    }
}
