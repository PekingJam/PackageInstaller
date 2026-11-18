package io.github.vvb2060.packageinstaller.ui.fragments;

import android.content.res.ColorStateList;
import android.widget.ProgressBar;
import android.widget.TextView;

import io.github.vvb2060.packageinstaller.R;
import io.github.vvb2060.packageinstaller.installmodel.InstallInstalling;
import io.github.vvb2060.packageinstaller.ui.CardDialog;

public final class InstallInstallingFragment extends BaseDialogFragment {

    private final InstallInstalling mDialogData;

    public InstallInstallingFragment(InstallInstalling dialogData) {
        super(dialogData);
        mDialogData = dialogData;
    }

    @Override
    protected void onBuild(CardDialog card) {
        setCancelable(false);
        var context = requireContext();
        card.icon(mDialogData.apkLite.getIcon())
            .title(mDialogData.apkLite.getLabel());

        var progress = new ProgressBar(context, null,
            android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        progress.setIndeterminateTintList(ColorStateList.valueOf(context.getColor(R.color.ui_primary)));
        progress.setProgressTintList(ColorStateList.valueOf(context.getColor(R.color.ui_primary)));
        card.addContent(progress);

        var text = new TextView(context, null, 0, R.style.Text_Caption);
        text.setText(R.string.copying);
        card.addContent(text);

        var cancel = card.button(getString(android.R.string.cancel), R.style.Button_Pill_Tonal,
            v -> mViewModel.cleanup());

        mViewModel.getDownloadProgress().observe(this, value -> {
            if (value == null) return;
            var staging = mViewModel.getStagingProgress().getValue();
            if (staging != null && staging > 0) return;   // 已经在写会话了，交给下面那段
            var percent = value.percent();
            progress.setIndeterminate(percent < 0);
            if (percent >= 0) progress.setProgress(percent, true);
            text.setText(io.github.vvb2060.packageinstaller.ui.Format.size(value.downloaded)
                + (value.total > 0
                    ? " / " + io.github.vvb2060.packageinstaller.ui.Format.size(value.total) : "")
                + " · " + io.github.vvb2060.packageinstaller.ui.Format.speed(value.speed));
        });

        mViewModel.getStagingProgress().observe(this, value -> {
            var percent = value == null ? -1 : value;
            cancel.setEnabled(percent < 100);
            text.setText(percent <= 100 ? R.string.copying : R.string.installing);
            progress.setIndeterminate(percent < 0 || percent > 100);
            if (percent >= 0 && percent <= 100) progress.setProgress(percent, true);
        });
    }
}
