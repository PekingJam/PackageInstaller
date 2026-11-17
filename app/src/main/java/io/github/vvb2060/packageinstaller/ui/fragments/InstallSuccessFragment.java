package io.github.vvb2060.packageinstaller.ui.fragments;

import io.github.vvb2060.packageinstaller.R;
import io.github.vvb2060.packageinstaller.installmodel.InstallSuccess;
import io.github.vvb2060.packageinstaller.ui.CardDialog;

public final class InstallSuccessFragment extends BaseDialogFragment {

    private final InstallSuccess mDialogData;

    public InstallSuccessFragment(InstallSuccess dialogData) {
        super(dialogData);
        mDialogData = dialogData;
    }

    @Override
    protected void onBuild(CardDialog card) {
        var context = requireContext();
        card.icon(mDialogData.apkLite.getIcon())
            .title(mDialogData.apkLite.getLabel())
            .message(context.getString(R.string.install_done));

        card.button(getString(R.string.done), R.style.Button_Pill_Outline, v -> finish());
        if (mDialogData.startIntent != null) {
            card.button(getString(R.string.launch), R.style.Button_Pill, v -> {
                startActivitySafe(mDialogData.startIntent, mDialogData.safeMode);
                finish();
            });
        }
    }
}
