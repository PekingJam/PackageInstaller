package io.github.vvb2060.packageinstaller.ui.fragments;

import android.content.pm.ApplicationInfo;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.text.Layout;
import android.text.Selection;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.method.LinkMovementMethod;
import android.text.style.LeadingMarginSpan;
import android.text.style.StyleSpan;
import android.view.MotionEvent;
import android.view.View;
import android.widget.CheckBox;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.util.Locale;

import io.github.vvb2060.packageinstaller.R;
import io.github.vvb2060.packageinstaller.apksig.Parser;
import io.github.vvb2060.packageinstaller.apksig.Signer;
import io.github.vvb2060.packageinstaller.apksig.Span;
import io.github.vvb2060.packageinstaller.core.Privilege;
import io.github.vvb2060.packageinstaller.installmodel.InstallUserAction;
import io.github.vvb2060.packageinstaller.ui.CardDialog;

public final class InstallConfirmationFragment extends BaseDialogFragment {

    private final InstallUserAction mDialogData;
    private CheckBox mCheckBox;
    private TextView mPositiveButton;
    private boolean mFirstCreate;

    public InstallConfirmationFragment(InstallUserAction dialogData) {
        super(dialogData);
        mDialogData = dialogData;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mFirstCreate = savedInstanceState == null;
    }

    @Override
    protected void onBuild(CardDialog card) {
        var context = requireContext();
        card.dialog().setCanceledOnTouchOutside(false);
        card.icon(mDialogData.apkLite.getIcon())
            .title(mDialogData.apkLite.getLabel());

        var textView = card.messageView();
        if (mDialogData.setInstaller) {
            mCheckBox = new CheckBox(context);
            mCheckBox.setText(R.string.set_installer);
            mCheckBox.setTextSize(13);
            mCheckBox.setButtonTintList(ColorStateList.valueOf(context.getColor(R.color.ui_primary)));
            card.addContent(mCheckBox);
        }

        var sb = new SpannableStringBuilder();
        appendBaseInfo(sb, textView);
        appendSignInfo(sb, textView);
        var remove = appendQuestion(sb, textView, mFirstCreate);
        textView.setText(sb, TextView.BufferType.SPANNABLE);
        textView.setTextIsSelectable(true);
        textView.setMovementMethod(new LinkMovementMethod() {
            @Override
            public boolean onTouchEvent(TextView widget, Spannable buffer, MotionEvent event) {
                var ret = super.onTouchEvent(widget, buffer, event);
                Selection.removeSelection(buffer);
                return ret;
            }
        });

        var old = mDialogData.oldApk;
        var full = mDialogData.fullInstall;

        card.button(getString(android.R.string.cancel), R.style.Button_Pill_Outline,
            v -> finish());
        var more = card.button(getString(remove ? R.string.remove_more : R.string.add_more),
            R.style.Button_Pill_Tonal,
            v -> mViewModel.initiateInstall(isChecked(), false, full, remove));
        mPositiveButton = card.button(
            getString(old != null ? R.string.update : R.string.install),
            R.style.Button_Pill,
            v -> mViewModel.initiateInstall(isChecked(), true, full, remove));
        mPositiveButton.setFilterTouchesWhenObscured(true);

        if (!mDialogData.apkLite.needSplit() || Privilege.mode() == Privilege.MODE_ROOT) {
            more.setVisibility(View.GONE);
        }
    }

    private boolean isChecked() {
        return mCheckBox != null && mCheckBox.isChecked();
    }

    @Override
    public void onPause() {
        super.onPause();
        if (mPositiveButton != null) mPositiveButton.setEnabled(false);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mPositiveButton != null) mPositiveButton.setEnabled(true);
    }

    private void appendBaseInfo(SpannableStringBuilder sb, TextView textView) {
        var apk = mDialogData.apkLite;
        var old = mDialogData.oldApk;
        append(sb, textView, R.string.package_name, apk.packageName);
        append(sb, textView, R.string.size, formatBytes(apk.size));
        if (apk.needSplit()) {
            append(sb, textView, R.string.split_name, apk.splitName);
            append(sb, textView, R.string.split_types, apk.splitTypes);
            append(sb, textView, R.string.required_split_types, apk.requiredSplitTypes);
            append(sb, textView, R.string.config_for_split, apk.configForSplit);
        }
        if (apk.isSplit()) return;

        var ver = apk.versionName + " (" + apk.versionCode + ")";
        var min = getAndroidName(apk.minSdkVersion);
        var target = getAndroidName(apk.targetSdkVersion);
        if (old != null) {
            var oldVer = old.versionName + " (" + old.getLongVersionCode() + ")";
            var oldMin = getAndroidName(old.applicationInfo.minSdkVersion);
            var oldTarget = getAndroidName(old.applicationInfo.targetSdkVersion);
            append(sb, textView, oldVer, old.getLongVersionCode(), ver, apk.versionCode);
            append(sb, textView, R.string.min_sdk, oldMin, min);
            append(sb, textView, R.string.target_sdk, oldTarget, target);
        } else {
            append(sb, textView, R.string.version, ver);
            append(sb, textView, R.string.min_sdk, min);
            append(sb, textView, R.string.target_sdk, target);
        }
    }

    private void append(SpannableStringBuilder sb, TextView textView, int label, String now) {
        if (now != null && !now.isEmpty()) {
            sb.append(textView.getResources().getString(label));
            sb.append(now).append("\n");
        }
    }

    private void append(SpannableStringBuilder sb, TextView textView, int label,
                        String old, String now) {
        if (old.equals(now)) {
            append(sb, textView, label, now);
        } else {
            sb.append(textView.getResources().getString(label));
            sb.append(old);
            sb.append(" → ");
            sb.append(now, new StyleSpan(Typeface.BOLD),
                SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.append("\n");
        }
    }

    private void append(SpannableStringBuilder sb, TextView textView, String old, long oldVer,
                        String now, long nowVer) {
        if (old.equals(now)) {
            append(sb, textView, R.string.version, now);
            return;
        }
        var label = textView.getResources().getString(R.string.version);
        var oldStart = sb.length();
        sb.append(old).append("\n");
        var newStart = sb.length();
        sb.append(now, new StyleSpan(Typeface.BOLD),
            SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.append("\n");
        var end = sb.length();

        String indicator;
        if (oldVer < nowVer) {
            indicator = "▲";
        } else if (oldVer > nowVer) {
            indicator = "▼";
        } else {
            indicator = "=";
        }

        var leadingMarginSpan = new LeadingMarginSpan() {
            float offset = 0;

            @Override
            public int getLeadingMargin(boolean first) {
                return (int) offset;
            }

            @Override
            public void drawLeadingMargin(Canvas c, Paint p, int x, int dir, int top,
                                          int baseline, int bottom, CharSequence text,
                                          int start, int end, boolean first, Layout layout) {
                if (start == oldStart) {
                    offset = p.measureText(label);
                    c.drawText(label, x, baseline, p);
                } else if (start == newStart) {
                    offset = p.measureText(label);
                    var textX = x + (offset - p.measureText(indicator)) / 2;
                    c.drawText(indicator, textX, baseline, p);
                } else offset = 0;
            }
        };
        sb.setSpan(leadingMarginSpan, oldStart, end,
            SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private void appendSignInfo(SpannableStringBuilder sb, TextView textView) {
        var map = mDialogData.apkLite.signingBlock;
        if (map == null || map.isEmpty()) return;
        sb.append(textView.getResources().getString(R.string.signing_block));
        Span.appendSignInfo(sb, textView, map);
        sb.append("\n");

        var stamp = map.get(Parser.V2_SOURCE_STAMP_BLOCK_ID);
        if (stamp instanceof Signer signer && signer.certs.get(0).isGoogle() &&
            signer.stampTime != null) {
            var stampTime = Signer.FORMATTER.format(signer.stampTime);
            var hasFrosting = map.get(Parser.GOOGLE_PLAY_FROSTING_BLOCK_ID) != null;
            var id = hasFrosting ? R.string.google_source_play : R.string.google_source;
            sb.append(textView.getResources().getString(id, stampTime));
            sb.append("\n");
        }
    }

    private boolean appendQuestion(SpannableStringBuilder sb, TextView textView,
                                   boolean needDefault) {
        var old = mDialogData.oldApk;
        var full = mDialogData.fullInstall;
        int question = R.string.install_confirm_question;
        var removeSplit = false;

        if (!full) {
            question = R.string.install_confirm_question_split;
            for (String splitName : old.splitNames) {
                if (splitName.equals(mDialogData.apkLite.splitName)) {
                    question = R.string.install_confirm_question_split_remove;
                    removeSplit = true;
                    break;
                }
            }
        }

        if (full && old != null) {
            var flags = old.applicationInfo.flags;
            var system = (flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            var installed = (flags & ApplicationInfo.FLAG_INSTALLED) != 0;
            if (mCheckBox != null && mDialogData.setInstaller && system && needDefault) {
                mCheckBox.setChecked(true);
            }
            if (installed) {
                question = R.string.install_confirm_question_update;
            }
        }

        var resources = textView.getResources();
        var string = resources.getString(question);

        var isSame = mDialogData.apkLite.isSame;
        if (isSame != null) {
            var builder = new StringBuilder();
            if (isSame[0]) {
                builder.append(resources.getString(R.string.install_confirm_warn_content));
            }
            if (!isSame[1]) {
                builder.append(resources.getString(R.string.install_confirm_warn_signer));
            }
            if (builder.length() > 0) {
                builder.append(resources.getString(R.string.install_confirm_question_warn));
                string = builder.toString();
            }
        }

        sb.append("\n");
        sb.append(string, new StyleSpan(Typeface.ITALIC),
            SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
        return removeSplit;
    }

    private static String formatBytes(long bytes) {
        if (bytes <= 0) return "";
        if (bytes < 1024) return bytes + " bytes";
        int unit = (int) (Math.log(bytes) / Math.log(1024));
        double value = bytes / Math.pow(1024, unit);
        return String.format(Locale.ROOT, "%.2f %ciB", value, "KMGTPE".charAt(unit - 1));
    }

    private static String getAndroidName(String apiLevel) {
        try {
            var api = Integer.parseInt(apiLevel);
            return getAndroidName(api);
        } catch (NumberFormatException ignored) {
            return "Android " + apiLevel;
        }
    }

    private static String getAndroidName(int apiLevel) {
        var name = switch (apiLevel) {
            case Build.VERSION_CODES.CUR_DEVELOPMENT -> {
                var codename = Build.VERSION.CODENAME;
                if (codename.equals("REL")) codename = "DEV";
                yield codename;
            }
            case Build.VERSION_CODES.BASE -> "1.0";
            case Build.VERSION_CODES.BASE_1_1 -> "1.1";
            case Build.VERSION_CODES.CUPCAKE -> "1.5";
            case Build.VERSION_CODES.DONUT -> "1.6";
            case Build.VERSION_CODES.ECLAIR -> "2.0";
            case Build.VERSION_CODES.ECLAIR_0_1 -> "2.0.1";
            case Build.VERSION_CODES.ECLAIR_MR1 -> "2.1";
            case Build.VERSION_CODES.FROYO -> "2.2";
            case Build.VERSION_CODES.GINGERBREAD -> "2.3";
            case Build.VERSION_CODES.GINGERBREAD_MR1 -> "2.3.3";
            case Build.VERSION_CODES.HONEYCOMB -> "3.0";
            case Build.VERSION_CODES.HONEYCOMB_MR1 -> "3.1";
            case Build.VERSION_CODES.HONEYCOMB_MR2 -> "3.2";
            case Build.VERSION_CODES.ICE_CREAM_SANDWICH -> "4.0";
            case Build.VERSION_CODES.ICE_CREAM_SANDWICH_MR1 -> "4.0.3";
            case Build.VERSION_CODES.JELLY_BEAN -> "4.1";
            case Build.VERSION_CODES.JELLY_BEAN_MR1 -> "4.2";
            case Build.VERSION_CODES.JELLY_BEAN_MR2 -> "4.3";
            case Build.VERSION_CODES.KITKAT -> "4.4";
            case Build.VERSION_CODES.KITKAT_WATCH -> "4.4W";
            case Build.VERSION_CODES.LOLLIPOP -> "5.0";
            case Build.VERSION_CODES.LOLLIPOP_MR1 -> "5.1";
            case Build.VERSION_CODES.M -> "6.0";
            case Build.VERSION_CODES.N -> "7.0";
            case Build.VERSION_CODES.N_MR1 -> "7.1";
            case Build.VERSION_CODES.O -> "8.0";
            case Build.VERSION_CODES.O_MR1 -> "8.1";
            case Build.VERSION_CODES.P -> "9";
            case Build.VERSION_CODES.Q -> "10";
            case Build.VERSION_CODES.R -> "11";
            case Build.VERSION_CODES.S -> "12";
            case Build.VERSION_CODES.S_V2 -> "12L";
            case Build.VERSION_CODES.TIRAMISU -> "13";
            case Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> "14";
            case Build.VERSION_CODES.VANILLA_ICE_CREAM -> "15";
            case Build.VERSION_CODES.BAKLAVA -> "16";
            case Build.VERSION_CODES.CINNAMON_BUN -> "17";
            default -> "???";
        };
        return "Android " + name + " (" + apiLevel + ")";
    }
}
