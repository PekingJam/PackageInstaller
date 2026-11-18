package io.github.vvb2060.packageinstaller.ui.fragments;

import android.widget.TextView;

import io.github.vvb2060.packageinstaller.R;
import io.github.vvb2060.packageinstaller.datamodel.DownloadStore;
import io.github.vvb2060.packageinstaller.installmodel.DownloadConfirm;
import io.github.vvb2060.packageinstaller.service.DownloadService;
import io.github.vvb2060.packageinstaller.service.InstallService;
import io.github.vvb2060.packageinstaller.ui.CardDialog;
import io.github.vvb2060.packageinstaller.ui.Format;

/** 外部调用（浏览器/其他 App）或首页新建下载时的确认弹窗：先看文件信息，再决定下不下。 */
public final class DownloadConfirmFragment extends BaseDialogFragment {

    private final DownloadConfirm mData;

    public DownloadConfirmFragment(DownloadConfirm dialogData) {
        super(dialogData);
        mData = dialogData;
    }

    @Override
    protected void onBuild(CardDialog card) {
        card.dialog().setCanceledOnTouchOutside(false);
        card.icon(R.drawable.ic_download)
            .title(mData.name);

        var textView = card.messageView();
        var sb = new StringBuilder();
        sb.append(getString(R.string.download_field_size, Format.size(mData.size))).append('\n');
        sb.append(getString(R.string.download_field_type, typeLabel(mData.mime))).append('\n');
        if (!mData.host.isEmpty()) {
            sb.append(getString(R.string.download_field_host, mData.host)).append('\n');
        }
        sb.append(getString(mData.install ? R.string.download_install_hint
            : R.string.download_field_path,
            mData.install ? mData.name : DownloadStore.displayPath(mData.name)));
        textView.setText(sb.toString());

        card.button(getString(android.R.string.cancel), R.style.Button_Pill_Outline,
            v -> finishOrDismiss());
        card.button(getString(mData.install ? R.string.download_and_install
            : R.string.download_start), R.style.Button_Pill, v -> {
            if (mData.install) {
                InstallService.start(requireContext(), null, mData.url, mData.name, true, true, true);
            } else {
                DownloadService.start(requireContext(), mData.url, mData.name, mData.mime,
                    mData.size);
            }
            finish();
        });
    }

    private String typeLabel(String mime) {
        if (mime == null || mime.isEmpty()) return getString(R.string.download_type_unknown);
        var value = mime.toLowerCase(java.util.Locale.ROOT);
        if (value.contains("package-archive")) return getString(R.string.download_type_apk);
        if (value.contains("zip")) return getString(R.string.download_type_archive);
        if (value.startsWith("image/")) return getString(R.string.download_type_image);
        if (value.startsWith("video/")) return getString(R.string.download_type_video);
        if (value.startsWith("audio/")) return getString(R.string.download_type_audio);
        if (value.startsWith("text/")) return getString(R.string.download_type_text);
        if (value.contains("pdf")) return getString(R.string.download_type_pdf);
        if (value.contains("octet-stream")) return getString(R.string.download_type_unknown);
        return value;
    }
}
