package io.github.vvb2060.packageinstaller.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Bundle;
import android.widget.Toast;

import androidx.fragment.app.FragmentActivity;

import io.github.vvb2060.packageinstaller.R;

/** 从「安装失败」通知点进来时展示具体原因。 */
public class InstallResultActivity extends FragmentActivity {
    public static final String EXTRA_LABEL = "label";
    public static final String EXTRA_REASON = "reason";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        var label = getIntent().getStringExtra(EXTRA_LABEL);
        var reason = getIntent().getStringExtra(EXTRA_REASON);
        if (reason == null) reason = "";

        var dialog = CardDialog.create(this);
        dialog.icon(R.drawable.ic_alert)
            .title(label == null ? getString(R.string.notify_failed_title) : label)
            .message(getString(R.string.notify_failed_title) + "\n\n" + reason);
        dialog.button(getString(R.string.crash_close), R.style.Button_Pill_Outline,
            v -> finish());
        final var report = reason;
        dialog.button(getString(R.string.crash_copy), R.style.Button_Pill, v -> {
            var clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("reason", report));
                Toast.makeText(this, R.string.crash_copied, Toast.LENGTH_SHORT).show();
            }
        });
        dialog.show();
        dialog.dialog().setOnDismissListener(d -> finish());
    }
}
