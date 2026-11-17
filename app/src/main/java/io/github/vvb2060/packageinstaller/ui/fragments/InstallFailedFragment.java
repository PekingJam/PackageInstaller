package io.github.vvb2060.packageinstaller.ui.fragments;

import android.content.pm.PackageInstaller;

import io.github.vvb2060.packageinstaller.R;
import io.github.vvb2060.packageinstaller.installmodel.InstallFailed;
import io.github.vvb2060.packageinstaller.ui.CardDialog;

public final class InstallFailedFragment extends BaseDialogFragment {

    private final InstallFailed mDialogData;

    public InstallFailedFragment(InstallFailed dialogData) {
        super(dialogData);
        mDialogData = dialogData;
    }

    @Override
    protected void onBuild(CardDialog card) {
        card.icon(mDialogData.apkLite.getIcon())
            .title(mDialogData.apkLite.getLabel())
            .message(getExplanation());
        card.button(getString(R.string.done), R.style.Button_Pill, v -> finish());
    }

    private String getExplanation() {
        int res = switch (mDialogData.statusCode) {
            case PackageInstaller.STATUS_FAILURE_BLOCKED -> R.string.install_failed_blocked;
            case PackageInstaller.STATUS_FAILURE_ABORTED -> R.string.install_failed_aborted;
            case PackageInstaller.STATUS_FAILURE_INVALID -> R.string.install_failed_invalid_apk;
            case PackageInstaller.STATUS_FAILURE_CONFLICT -> R.string.install_failed_conflict;
            case PackageInstaller.STATUS_FAILURE_STORAGE -> R.string.install_failed_storage;
            case PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> R.string.install_failed_incompatible;
            case PackageInstaller.STATUS_FAILURE_TIMEOUT -> R.string.install_failed_timeout;
            default -> R.string.install_failed;
        };
        var message = mDialogData.message;
        var base = requireContext().getString(res);
        return message == null || message.isEmpty() ? base : base + "\n\n" + message;
    }
}
