package io.github.vvb2060.packageinstaller.ui.fragments;

import android.content.res.ColorStateList;
import android.view.ViewGroup;
import android.widget.ProgressBar;

import io.github.vvb2060.packageinstaller.R;
import io.github.vvb2060.packageinstaller.installmodel.InstallParse;
import io.github.vvb2060.packageinstaller.ui.CardDialog;

public final class InstallParseFragment extends BaseDialogFragment {

    public InstallParseFragment(InstallParse dialogData) {
        super(dialogData);
    }

    @Override
    protected void onBuild(CardDialog card) {
        setCancelable(false);
        card.icon(R.drawable.ic_app_icon)
            .title(R.string.app_name)
            .message(R.string.parsing);

        var progress = new ProgressBar(requireContext(), null,
            android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        progress.setIndeterminateTintList(ColorStateList.valueOf(
            requireContext().getColor(R.color.ui_primary)));
        card.addContent(progress);

        card.button(getString(android.R.string.cancel), R.style.Button_Pill_Tonal,
            v -> mViewModel.cleanup());
    }
}
