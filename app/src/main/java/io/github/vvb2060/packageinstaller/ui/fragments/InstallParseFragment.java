package io.github.vvb2060.packageinstaller.ui.fragments;

import android.content.res.ColorStateList;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;

import io.github.vvb2060.packageinstaller.R;
import io.github.vvb2060.packageinstaller.installmodel.InstallParse;
import io.github.vvb2060.packageinstaller.ui.CardDialog;
import io.github.vvb2060.packageinstaller.ui.Format;

public final class InstallParseFragment extends BaseDialogFragment {

    public InstallParseFragment(InstallParse dialogData) {
        super(dialogData);
    }

    @Override
    protected void onBuild(CardDialog card) {
        setCancelable(false);
        var context = requireContext();
        card.icon(R.mipmap.ic_app_logo)
            .title(R.string.app_name);
        var message = card.messageView();
        message.setText(R.string.parsing);

        var progress = new ProgressBar(context, null,
            android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        progress.setIndeterminateTintList(ColorStateList.valueOf(
            context.getColor(R.color.ui_primary)));
        card.addContent(progress);

        var detail = new TextView(context, null, 0, R.style.Text_Caption);
        card.addContent(detail);

        mViewModel.getDownloadProgress().observe(this, value -> {
            if (value == null) return;
            message.setText(R.string.downloading);
            var percent = value.percent();
            progress.setIndeterminate(percent < 0);
            if (percent >= 0) progress.setProgress(percent, true);
            var total = value.total > 0 ? Format.size(value.total) : "?";
            detail.setText(Format.size(value.downloaded) + " / " + total
                + " · " + Format.speed(value.speed));
        });

        card.button(getString(android.R.string.cancel), R.style.Button_Pill_Tonal,
            v -> mViewModel.cleanup());
    }
}
