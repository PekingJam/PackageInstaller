package io.github.vvb2060.packageinstaller.ui.fragments;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.Nullable;

import io.github.vvb2060.packageinstaller.R;
import io.github.vvb2060.packageinstaller.core.Privilege;
import io.github.vvb2060.packageinstaller.installmodel.InstallAborted;
import io.github.vvb2060.packageinstaller.ui.CardDialog;
import io.github.vvb2060.packageinstaller.ui.MainActivity;

public final class InstallErrorFragment extends BaseDialogFragment {

    private final InstallAborted mAborted;

    public InstallErrorFragment(InstallAborted aborted) {
        super(aborted);
        mAborted = aborted;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (mAborted.abortReason == InstallAborted.ABORT_CLOSE) {
            finish();
        }
    }

    @Override
    protected void onBuild(CardDialog card) {
        var code = mAborted.abortReason;
        card.icon(R.drawable.ic_alert)
            .title(R.string.error_title)
            .message(getErrorMessage(requireContext(), code));

        if (code == InstallAborted.ABORT_PRIVILEGE || code == InstallAborted.ABORT_SHIZUKU) {
            card.button(getString(android.R.string.cancel), R.style.Button_Pill_Outline,
                v -> finish());
            card.button(getString(R.string.grant), R.style.Button_Pill, v -> {
                if (code == InstallAborted.ABORT_PRIVILEGE) {
                    var intent = new Intent(requireContext(), MainActivity.class);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    requireContext().startActivity(intent);
                } else {
                    checkShizuku();
                }
                finish();
            });
        } else {
            card.button(getString(android.R.string.ok), R.style.Button_Pill, v -> finish());
        }
    }

    private void checkShizuku() {
        if (Privilege.shizukuRunning()) {
            Privilege.requestShizukuPermission();
            return;
        }
        var intent = mAborted.intent;
        if (intent != null) startActivitySafe(intent, false);
    }

    private String getErrorMessage(Context context, int code) {
        if (code == InstallAborted.ABORT_PRIVILEGE) {
            return context.getString(R.string.error_privilege);
        }
        if (mViewModel.isCancelled()) {
            return context.getString(R.string.error_cancelled);
        }
        if (code == InstallAborted.ABORT_SHIZUKU) {
            if (!Privilege.shizukuRunning()) {
                if (mAborted.intent == null) {
                    return context.getString(R.string.error_shizuku_notfound);
                } else {
                    return context.getString(R.string.error_shizuku_notrunning);
                }
            }
            if (!Privilege.shizukuGranted()) {
                return context.getString(R.string.error_shizuku_notpermitted);
            } else {
                return context.getString(R.string.error_shizuku_unavailable);
            }
        }
        var id = switch (code) {
            case InstallAborted.ABORT_PARSE -> R.string.error_parse;
            case InstallAborted.ABORT_SPLIT -> R.string.error_split;
            case InstallAborted.ABORT_NOTFOUND -> R.string.error_notfound;
            case InstallAborted.ABORT_CREATE -> R.string.error_create;
            case InstallAborted.ABORT_WRITE -> R.string.error_write;
            default -> R.string.error_title;
        };
        var string = context.getString(id);
        var message = mAborted.message;
        return message != null ? string + "\n\n" + message : string;
    }
}
