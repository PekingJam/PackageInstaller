package io.github.vvb2060.packageinstaller.ui;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import io.github.vvb2060.packageinstaller.R;

/**
 * 圆角卡片弹窗。安装流程的 DialogFragment 和主界面/应用管理里的操作弹窗共用这一套外壳。
 */
public final class CardDialog {
    private final Dialog dialog;
    private final ImageView icon;
    private final TextView title;
    private final TextView status;
    private final TextView message;
    private final LinearLayout content;
    private final LinearLayout buttons;
    private final ScrollView scroll;

    private CardDialog(Dialog dialog) {
        this.dialog = dialog;
        icon = dialog.findViewById(R.id.dialog_icon);
        title = dialog.findViewById(R.id.dialog_title);
        status = dialog.findViewById(R.id.dialog_status);
        message = dialog.findViewById(R.id.dialog_message);
        content = dialog.findViewById(R.id.dialog_content);
        buttons = dialog.findViewById(R.id.dialog_buttons);
        scroll = dialog.findViewById(R.id.dialog_scroll);
    }

    public static CardDialog create(Context context) {
        var dialog = new Dialog(context, R.style.Theme_CardDialog);
        dialog.setContentView(R.layout.dialog_card);
        var window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            // 大屏/横屏下不要铺满整屏
            var metrics = context.getResources().getDisplayMetrics();
            var width = Math.min(metrics.widthPixels - dp(36, context), dp(560, context));
            window.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT);
        }
        return new CardDialog(dialog);
    }

    public Dialog dialog() {
        return dialog;
    }

    public CardDialog icon(Drawable drawable) {
        icon.setImageDrawable(drawable);
        return this;
    }

    public CardDialog icon(int resId) {
        icon.setImageResource(resId);
        return this;
    }

    public CardDialog title(CharSequence text) {
        title.setText(text);
        return this;
    }

    public CardDialog title(int resId) {
        title.setText(resId);
        return this;
    }

    public CardDialog status(int resId) {
        return status(dialog.getContext().getString(resId));
    }

    public CardDialog status(CharSequence text) {
        if (text == null || text.length() == 0) {
            status.setVisibility(View.GONE);
        } else {
            status.setText(text);
            status.setVisibility(View.VISIBLE);
        }
        return this;
    }

    public CardDialog message(CharSequence text) {
        message.setText(text);
        message.setVisibility(View.VISIBLE);
        return this;
    }

    public CardDialog message(int resId) {
        message.setText(resId);
        message.setVisibility(View.VISIBLE);
        return this;
    }

    public TextView messageView() {
        message.setVisibility(View.VISIBLE);
        return message;
    }

    public LinearLayout contentView() {
        return content;
    }

    public CardDialog addContent(View view) {
        content.addView(view, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return this;
    }

    /** styleRes 取 R.style.Button_Pill / Button_Pill_Tonal / Button_Pill_Outline。 */
    public TextView button(CharSequence text, int styleRes, View.OnClickListener listener) {
        var context = dialog.getContext();
        var button = new TextView(context, null, 0, styleRes);
        button.setText(text);
        var params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMarginStart(dp(8, dialog.getContext()));
        buttons.addView(button, params);
        if (listener != null) button.setOnClickListener(listener);
        return button;
    }

    public void show() {
        dialog.show();
        constrainHeight();
        fixButtonLayout();
    }

    /** 三个按钮在窄屏放不下时改成整行竖排，避免主按钮被裁掉。 */
    private void fixButtonLayout() {
        buttons.post(() -> {
            var available = buttons.getWidth();
            var count = buttons.getChildCount();
            if (available <= 0 || count < 2) return;
            var needed = 0;
            for (int i = 0; i < count; i++) {
                var child = buttons.getChildAt(i);
                needed += child.getMeasuredWidth();
                var params = (LinearLayout.LayoutParams) child.getLayoutParams();
                needed += params.getMarginStart() + params.getMarginEnd();
            }
            if (needed <= available) return;
            buttons.setOrientation(LinearLayout.VERTICAL);
            for (int i = 0; i < count; i++) {
                var child = buttons.getChildAt(i);
                var params = (LinearLayout.LayoutParams) child.getLayoutParams();
                params.width = LinearLayout.LayoutParams.MATCH_PARENT;
                params.setMarginStart(0);
                params.topMargin = i == 0 ? 0 : dp(8, dialog.getContext());
                child.setLayoutParams(params);
            }
        });
    }

    public void dismiss() {
        dialog.dismiss();
    }

    /** 内容过长时把滚动区限制在屏幕一半左右，避免按钮被挤出屏幕。 */
    public void constrainHeight() {
        scroll.post(() -> {
            if (scroll.getChildCount() == 0) return;
            var contentHeight = scroll.getChildAt(0).getHeight();
            if (scroll.getHeight() > 0 && scroll.getHeight() >= contentHeight) return;
            var max = (int) (dialog.getContext().getResources().getDisplayMetrics().heightPixels * 0.52f);
            if (contentHeight > max) {
                var params = scroll.getLayoutParams();
                params.height = max;
                scroll.setLayoutParams(params);
            }
        });
    }

    private static int dp(int value, Context context) {
        return (int) (value * context.getResources().getDisplayMetrics().density);
    }
}
