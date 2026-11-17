package io.github.vvb2060.packageinstaller.ui;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.FragmentActivity;

import java.text.Collator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

import io.github.vvb2060.packageinstaller.R;
import io.github.vvb2060.packageinstaller.core.AppListSource;
import io.github.vvb2060.packageinstaller.core.Privilege;

public class AppListActivity extends FragmentActivity {
    private static final int FILTER_ALL = 0;
    private static final int FILTER_USER = 1;
    private static final int FILTER_SYSTEM = 2;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<AppListSource.Entry> all = new ArrayList<>();
    private final List<AppListSource.Entry> shown = new ArrayList<>();

    private AppAdapter adapter;
    private ProgressBar progress;
    private TextView empty;
    private TextView countText;
    private TextView[] filters;
    private CardDialog currentDialog;
    private int filter = FILTER_ALL;
    private String query = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_app_list);
        setupInsets();
        Privilege.attach(getApplicationContext());

        progress = findViewById(R.id.progress);
        empty = findViewById(R.id.empty_text);
        countText = findViewById(R.id.count_text);
        filters = new TextView[]{findViewById(R.id.filter_all),
            findViewById(R.id.filter_user), findViewById(R.id.filter_system)};

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        for (int i = 0; i < filters.length; i++) {
            final var index = i;
            filters[i].setOnClickListener(v -> {
                filter = index;
                updateFilterStyle();
                applyFilter();
            });
        }
        updateFilterStyle();

        var search = (EditText) findViewById(R.id.search_input);
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                query = s.toString().trim().toLowerCase(Locale.ROOT);
                applyFilter();
            }
        });

        adapter = new AppAdapter();
        var list = (ListView) findViewById(R.id.app_list);
        list.setAdapter(adapter);

        load();
    }

    private void setupInsets() {
        var root = findViewById(R.id.root);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        var baseLeft = root.getPaddingLeft();
        var baseTop = root.getPaddingTop();
        var baseRight = root.getPaddingRight();
        var baseBottom = root.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            var bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(baseLeft + bars.left, baseTop + bars.top,
                baseRight + bars.right, baseBottom + bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
    }

    @Override
    protected void onDestroy() {
        if (currentDialog != null) currentDialog.dismiss();
        main.removeCallbacksAndMessages(null);
        executor.shutdownNow();
        super.onDestroy();
    }

    private void updateFilterStyle() {
        for (int i = 0; i < filters.length; i++) {
            var selected = i == filter;
            filters[i].setBackgroundResource(selected
                ? R.drawable.bg_chip : R.drawable.bg_chip_outline);
            filters[i].setTextColor(getColor(selected
                ? R.color.ui_on_primary_container
                : R.color.ui_on_surface_variant));
        }
    }

    private void load() {
        if (isDestroyed()) return;
        progress.setVisibility(View.VISIBLE);
        empty.setVisibility(View.GONE);
        executor.execute(() -> {
            var list = new ArrayList<>(AppListSource.names(this));
            sort(list);
            main.post(() -> {
                if (isDestroyed()) return;
                all.clear();
                all.addAll(list);
                progress.setVisibility(View.GONE);
                applyFilter();
                if (list.size() <= 3 && Privilege.mode() == Privilege.MODE_NONE) {
                    Toast.makeText(this, R.string.apps_limited_hint, Toast.LENGTH_LONG).show();
                }
            });

            // 名称/图标要解 APK，慢慢补，凑够一批刷一次
            var count = 0;
            for (var entry : list) {
                AppListSource.details(this, entry);
                if (++count % 8 == 0) {
                    main.post(() -> {
                        if (!isDestroyed()) adapter.notifyDataSetChanged();
                    });
                }
            }
            sort(list);
            main.post(() -> {
                if (isDestroyed()) return;
                all.clear();
                all.addAll(list);
                applyFilter();
            });
        });
    }

    private static void sort(List<AppListSource.Entry> list) {
        var collator = Collator.getInstance(Locale.CHINA);
        list.sort((a, b) -> {
            if (a.system != b.system) return a.system ? 1 : -1;
            return collator.compare(a.label, b.label);
        });
    }

    private void applyFilter() {
        shown.clear();
        for (var entry : all) {
            if (filter == FILTER_USER && entry.system) continue;
            if (filter == FILTER_SYSTEM && !entry.system) continue;
            if (!query.isEmpty()) {
                var label = entry.label.toLowerCase(Locale.ROOT);
                if (!label.contains(query) && !entry.packageName.contains(query)) continue;
            }
            shown.add(entry);
        }
        adapter.notifyDataSetChanged();
        countText.setText(getString(R.string.apps_count, shown.size()));
        empty.setVisibility(shown.isEmpty() && progress.getVisibility() != View.VISIBLE
            ? View.VISIBLE : View.GONE);
    }

    private void showAppDialog(AppListSource.Entry entry) {
        var dialog = CardDialog.create(this);
        currentDialog = dialog;

        var subtitle = new StringBuilder(entry.packageName);
        if (!entry.versionName.isEmpty()) subtitle.append(" · v").append(entry.versionName);
        if (entry.system) subtitle.append(" · ").append(getString(R.string.app_system_tag));
        if (!entry.enabled) subtitle.append(" · ").append(getString(R.string.app_state_disabled));
        if (!entry.installed) {
            subtitle.append(" · ").append(getString(R.string.app_state_uninstalled));
        }

        dialog.icon(entry.icon)
            .title(entry.label)
            .status(subtitle.toString());

        var content = dialog.contentView();
        if (entry.installed && entry.enabled) {
            addActionRow(content, R.drawable.ic_play, R.string.app_action_open, false,
                v -> {
                    dialog.dismiss();
                    launchApp(entry);
                });
        }
        addActionRow(content, R.drawable.ic_power,
            entry.enabled ? R.string.app_action_disable : R.string.app_action_enable, false,
            v -> {
                dialog.dismiss();
                toggleEnabled(entry);
            });
        addActionRow(content, R.drawable.ic_trash, R.string.app_action_clear, false,
            v -> {
                dialog.dismiss();
                confirmClear(entry);
            });
        addActionRow(content, R.drawable.ic_remove_circle, R.string.app_action_uninstall, true,
            v -> {
                dialog.dismiss();
                confirmUninstall(entry);
            });
        addActionRow(content, R.drawable.ic_info, R.string.app_action_info, false,
            v -> {
                dialog.dismiss();
                openAppInfo(entry.packageName);
            });

        dialog.button(getString(android.R.string.cancel), R.style.Button_Pill_Outline,
            v -> dialog.dismiss());
        dialog.show();
    }

    private void addActionRow(LinearLayout parent, int icon, int label, boolean danger,
                              View.OnClickListener listener) {
        var row = getLayoutInflater().inflate(R.layout.item_action_row, parent, false);
        var color = getColor(danger ? R.color.ui_error : R.color.ui_on_surface_variant);
        var image = (ImageView) row.findViewById(R.id.action_icon);
        image.setImageResource(icon);
        image.setImageTintList(ColorStateList.valueOf(color));
        var text = (TextView) row.findViewById(R.id.action_label);
        text.setText(label);
        if (danger) text.setTextColor(getColor(R.color.ui_error));
        row.setOnClickListener(listener);
        parent.addView(row, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void launchApp(AppListSource.Entry entry) {
        executor.execute(() -> {
            var error = Privilege.launchApp(entry.packageName);
            if (error != null) {
                main.post(() -> Toast.makeText(this,
                    getString(R.string.result_failed, error), Toast.LENGTH_LONG).show());
            }
        });
    }

    private void openAppInfo(String packageName) {
        var intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.error_notfound, Toast.LENGTH_SHORT).show();
        }
    }

    private void toggleEnabled(AppListSource.Entry entry) {
        var enable = !entry.enabled;
        runAction(enable ? R.string.app_action_enable : R.string.app_action_disable,
            () -> Privilege.setEnabled(entry.packageName, enable));
    }

    private void confirmClear(AppListSource.Entry entry) {
        confirm(R.string.app_confirm_clear_title, R.string.app_confirm_clear_message,
            R.string.app_action_clear, () -> runAction(R.string.app_action_clear,
                () -> Privilege.clearApplicationUserData(entry.packageName)));
    }

    private void confirmUninstall(AppListSource.Entry entry) {
        confirm(R.string.app_confirm_uninstall_title, R.string.app_confirm_uninstall_message,
            R.string.app_action_uninstall, () -> runAction(R.string.app_action_uninstall,
                () -> Privilege.uninstall(entry.packageName, entry.versionCode, entry.system)));
    }

    private void confirm(int title, int message, int positive, Runnable onConfirm) {
        var dialog = CardDialog.create(this);
        currentDialog = dialog;
        dialog.icon(R.drawable.ic_alert)
            .title(getString(title))
            .message(getString(message));
        dialog.button(getString(android.R.string.cancel), R.style.Button_Pill_Outline,
            v -> dialog.dismiss());
        dialog.button(getString(positive), R.style.Button_Pill_Danger, v -> {
            dialog.dismiss();
            onConfirm.run();
        });
        dialog.show();
    }

    private void runAction(int successLabel, Supplier<String> operation) {
        executor.execute(() -> {
            if (Privilege.mode() == Privilege.MODE_NONE) {
                main.post(() -> Toast.makeText(this, R.string.no_privilege,
                    Toast.LENGTH_SHORT).show());
                return;
            }
            String error;
            try {
                error = operation.get();
            } catch (RuntimeException | LinkageError e) {
                error = e.toString();
            }
            final var result = error;
            main.post(() -> {
                if (isDestroyed()) return;
                if (result == null) {
                    Toast.makeText(this, getString(successLabel), Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, getString(R.string.result_failed, result),
                        Toast.LENGTH_LONG).show();
                }
                main.postDelayed(this::load, 800);
            });
        });
    }

    private final class AppAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return shown.size();
        }

        @Override
        public Object getItem(int position) {
            return shown.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            var view = convertView != null ? convertView
                : getLayoutInflater().inflate(R.layout.item_app, parent, false);
            var entry = shown.get(position);
            var icon = (ImageView) view.findViewById(R.id.app_icon);
            if (entry.icon != null) {
                icon.setImageDrawable(entry.icon);
            } else {
                icon.setImageResource(android.R.drawable.sym_def_app_icon);
            }
            ((TextView) view.findViewById(R.id.app_label)).setText(entry.label);
            var sub = new StringBuilder(entry.packageName);
            if (!entry.versionName.isEmpty()) sub.append(" · v").append(entry.versionName);
            if (!entry.installed) {
                sub.append(" · ").append(getString(R.string.app_state_uninstalled));
            } else if (!entry.enabled) {
                sub.append(" · ").append(getString(R.string.app_state_disabled));
            }
            ((TextView) view.findViewById(R.id.app_sub)).setText(sub);
            // ListView 的 onItemClick 会被行自身消费，这里直接绑定
            view.setOnClickListener(v -> showAppDialog(entry));
            return view;
        }
    }
}
