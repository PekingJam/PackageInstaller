package io.github.vvb2060.packageinstaller.ui;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentFactory;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.github.vvb2060.packageinstaller.App;
import io.github.vvb2060.packageinstaller.BuildConfig;
import io.github.vvb2060.packageinstaller.R;
import io.github.vvb2060.packageinstaller.core.Privilege;
import io.github.vvb2060.packageinstaller.core.RootShell;
import io.github.vvb2060.packageinstaller.installmodel.SettingsUserAction;
import io.github.vvb2060.packageinstaller.ui.fragments.SettingsFragment;

public class MainActivity extends FragmentActivity {
    private static final int REQUEST_APK = 1;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private View statusDot;
    private TextView statusTitle;
    private TextView statusMode;
    private TextView statusSubtitle;
    private LinearLayout checks;
    private TextView btnPrepare;
    private TextView[] modeChips;
    private CardDialog currentDialog;
    private boolean checking;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Fragment 恢复走默认无参构造会崩，这里按类名自己造
        getSupportFragmentManager().setFragmentFactory(new FragmentFactory() {
            @NonNull
            @Override
            public Fragment instantiate(@NonNull ClassLoader classLoader, @NonNull String className) {
                if (className.equals(SettingsFragment.class.getName())) {
                    return new SettingsFragment(new SettingsUserAction(readSafeMode()));
                }
                return super.instantiate(classLoader, className);
            }
        });
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        setupInsets();
        Privilege.attach(getApplicationContext());

        statusDot = findViewById(R.id.status_dot);
        statusTitle = findViewById(R.id.status_title);
        statusMode = findViewById(R.id.status_mode);
        statusSubtitle = findViewById(R.id.status_subtitle);
        checks = findViewById(R.id.checks_container);
        btnPrepare = findViewById(R.id.btn_prepare);
        statusTitle.setText(R.string.status_checking);
        statusMode.setText(R.string.mode_checking);

        bindCard(R.id.action_install, R.drawable.ic_install, R.string.action_install,
            R.string.action_install_desc, v -> pickApk());
        bindCard(R.id.action_url, R.drawable.ic_globe, R.string.action_url,
            R.string.action_url_desc, v -> showUrlDialog());
        bindCard(R.id.action_apps, R.drawable.ic_apps, R.string.action_apps,
            R.string.action_apps_desc, v -> startActivity(
                new Intent(this, AppListActivity.class)));
        bindCard(R.id.action_settings, R.drawable.ic_settings, R.string.action_settings,
            R.string.action_settings_desc, v -> showSettings());
        bindCard(R.id.action_default, R.drawable.ic_check_circle,
            R.string.default_installer_title, R.string.default_installer_desc,
            v -> setDefaultInstaller());

        modeChips = new TextView[]{findViewById(R.id.mode_auto),
            findViewById(R.id.mode_root_chip), findViewById(R.id.mode_shizuku_chip)};
        for (int i = 0; i < modeChips.length; i++) {
            final var selected = i;
            modeChips[i].setOnClickListener(v -> switchMode(selected));
        }
        updateModeChips();

        findViewById(R.id.btn_refresh).setOnClickListener(v -> refresh(true));
        btnPrepare.setOnClickListener(v -> {
            if (Privilege.selectedMode() == Privilege.PREF_SHIZUKU) {
                if (Privilege.shizukuRunning()) {
                    Privilege.requestShizukuPermission();
                } else {
                    openShizuku();
                }
                main.postDelayed(() -> refresh(true), 1500);
            } else {
                requestRoot();
            }
        });

        ((TextView) findViewById(R.id.version_text))
            .setText("v" + BuildConfig.VERSION_NAME + " · HyperOS Mod");

        showCrashReportIfAny();
        requestNotificationPermission();
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED) return;
        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 42);
    }

    /** 设为默认安装器：Shizuku 下静默设置，Root/未授权下让系统弹一次「始终」选择。 */
    private void setDefaultInstaller() {
        if (Privilege.modeOrUnknown() == Privilege.MODE_SHIZUKU) {
            executor.execute(() -> {
                Privilege.prepare();
                main.post(() -> {
                    if (isDestroyed()) return;
                    Toast.makeText(this, R.string.default_done, Toast.LENGTH_LONG).show();
                });
            });
            return;
        }
        var intent = new Intent(Intent.ACTION_VIEW)
            .setType("application/vnd.android.package-archive")
            .putExtra(InstallLaunch.EXTRA_SET_DEFAULT, true);
        try {
            startActivity(Intent.createChooser(intent,
                getString(R.string.default_chooser_title)));
        } catch (RuntimeException e) {
            Toast.makeText(this, getString(R.string.result_failed, e.toString()),
                Toast.LENGTH_LONG).show();
        }
    }

    /** 上次进程被杀（多半是未捕获异常）时把栈打出来，方便远程排查。 */
    private void showCrashReportIfAny() {
        var file = App.crashFile(this);
        if (!file.exists()) return;
        var text = new StringBuilder();
        try (var reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                text.append(line).append('\n');
            }
        } catch (Throwable e) {
            text.append(e);
        }
        if (!file.delete()) file.deleteOnExit();
        if (text.length() == 0) return;
        var report = text.toString();

        var dialog = CardDialog.create(this);
        currentDialog = dialog;
        dialog.icon(R.drawable.ic_alert)
            .title(R.string.crash_title)
            .message(report.length() > 4000 ? report.substring(0, 4000) : report);
        dialog.button(getString(R.string.crash_close), R.style.Button_Pill_Outline,
            v -> dialog.dismiss());
        dialog.button(getString(R.string.crash_copy), R.style.Button_Pill, v -> {
            var clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("crash", report));
                Toast.makeText(this, R.string.crash_copied, Toast.LENGTH_SHORT).show();
            }
            dialog.dismiss();
        });
        dialog.show();
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
    protected void onResume() {
        super.onResume();
        refresh(true);
    }

    @Override
    protected void onDestroy() {
        if (currentDialog != null) currentDialog.dismiss();
        main.removeCallbacksAndMessages(null);
        executor.shutdownNow();
        super.onDestroy();
    }

    private boolean readSafeMode() {
        return getSharedPreferences(BuildConfig.APPLICATION_ID, MODE_PRIVATE)
            .getBoolean("safe_mode", false);
    }

    private void bindCard(int id, int icon, int title, int desc, View.OnClickListener listener) {
        var card = findViewById(id);
        ((ImageView) card.findViewById(R.id.card_icon)).setImageResource(icon);
        ((TextView) card.findViewById(R.id.card_title)).setText(title);
        ((TextView) card.findViewById(R.id.card_desc)).setText(desc);
        card.setOnClickListener(listener);
    }

    private void refresh(boolean invalidate) {
        if (checking) return;
        checking = true;
        executor.execute(() -> {
            int mode;
            int selected;
            boolean root;
            boolean running = false;
            boolean granted = false;
            boolean canInstall = false;
            try {
                if (invalidate) Privilege.invalidate();
                selected = Privilege.selectedMode();
                mode = Privilege.mode();
                root = mode == Privilege.MODE_ROOT;
                // 已经在跑 Root 时不再问 Shizuku，避免两种后端同时使用
                if (mode != Privilege.MODE_ROOT && selected != Privilege.PREF_ROOT) {
                    running = Privilege.shizukuRunning();
                    if (running) {
                        granted = Privilege.shizukuGranted();
                        if (granted) canInstall = Privilege.shizukuCanInstall();
                    }
                }
            } catch (Throwable e) {
                main.post(() -> {
                    checking = false;
                    statusTitle.setText(R.string.status_none);
                    statusSubtitle.setText(R.string.status_subtitle_none);
                });
                return;
            }
            final var resolvedMode = mode;
            final var resolvedSelected = selected;
            final var resolvedRoot = root;
            final var resolvedRunning = running;
            final var resolvedGranted = granted;
            final var resolvedCanInstall = canInstall;
            main.post(() -> {
                checking = false;
                if (isDestroyed()) return;
                render(resolvedMode, resolvedSelected, resolvedRoot, resolvedRunning,
                    resolvedGranted, resolvedCanInstall);
            });
        });
    }

    private void switchMode(int selected) {
        Privilege.setSelectedMode(selected);
        updateModeChips();
        Toast.makeText(this, R.string.mode_switched, Toast.LENGTH_SHORT).show();
        refresh(true);
    }

    private void updateModeChips() {
        if (modeChips == null) return;
        var selected = Privilege.selectedMode();
        for (int i = 0; i < modeChips.length; i++) {
            var active = i == selected;
            modeChips[i].setBackgroundResource(active
                ? R.drawable.bg_chip : R.drawable.bg_chip_outline);
            modeChips[i].setTextColor(getColor(active
                ? R.color.ui_on_primary_container
                : R.color.ui_on_surface_variant));
        }
    }

    private void render(int mode, int selected, boolean root, boolean running, boolean granted,
                        boolean canInstall) {
        var ready = mode != Privilege.MODE_NONE;
        statusDot.setBackgroundTintList(ColorStateList.valueOf(
            getColor(ready ? R.color.ui_ok : R.color.ui_warn)));

        String modeText;
        if (mode == Privilege.MODE_ROOT) {
            statusTitle.setText(R.string.status_root_ok);
            modeText = getString(R.string.mode_root);
        } else if (mode == Privilege.MODE_SHIZUKU) {
            statusTitle.setText(R.string.status_shizuku_ok);
            modeText = getString(R.string.mode_shizuku);
        } else {
            statusTitle.setText(R.string.status_none);
            modeText = getString(R.string.mode_none);
        }
        statusMode.setText(modeText);
        statusSubtitle.setText(ready
            ? getString(R.string.status_subtitle_ready, modeText)
            : getString(R.string.status_subtitle_none));
        btnPrepare.setVisibility(ready ? View.GONE : View.VISIBLE);
        updateModeChips();

        checks.removeAllViews();
        if (mode == Privilege.MODE_ROOT) {
            addCheck(true, R.string.check_root_title, R.string.check_root_ok, 0, null);
            return;
        }
        if (mode == Privilege.MODE_SHIZUKU) {
            addCheck(true, R.string.check_shizuku_service_title,
                R.string.check_shizuku_service_ok, 0, null);
            addCheck(true, R.string.check_shizuku_perm_title,
                R.string.check_shizuku_perm_ok, 0, null);
            addCheck(true, R.string.check_install_perm_title,
                R.string.check_install_perm_ok, 0, null);
            return;
        }
        // 一个都没拿到：只提示当前选择需要的那个后端
        if (selected != Privilege.PREF_SHIZUKU) {
            addCheck(root, R.string.check_root_title,
                root ? R.string.check_root_ok : R.string.check_root_missing,
                root ? 0 : R.string.btn_request, v -> requestRoot());
        }
        if (selected != Privilege.PREF_ROOT) {
            addCheck(running, R.string.check_shizuku_service_title,
                running ? R.string.check_shizuku_service_ok
                    : R.string.check_shizuku_service_missing,
                running ? 0 : R.string.btn_open, v -> openShizuku());
            if (running) {
                addCheck(granted, R.string.check_shizuku_perm_title,
                    granted ? R.string.check_shizuku_perm_ok
                        : R.string.check_shizuku_perm_missing,
                    granted ? 0 : R.string.btn_request, v -> {
                        Privilege.requestShizukuPermission();
                        main.postDelayed(() -> refresh(true), 1500);
                    });
            }
        }
    }

    private void addCheck(boolean ok, int title, int desc, int action,
                          View.OnClickListener listener) {
        var row = getLayoutInflater().inflate(R.layout.item_check_row, checks, false);
        var icon = (ImageView) row.findViewById(R.id.check_icon);
        icon.setImageResource(ok ? R.drawable.ic_check_circle : R.drawable.ic_alert);
        icon.setImageTintList(ColorStateList.valueOf(
            getColor(ok ? R.color.ui_ok : R.color.ui_warn)));
        ((TextView) row.findViewById(R.id.check_title)).setText(title);
        ((TextView) row.findViewById(R.id.check_desc)).setText(desc);
        var button = (TextView) row.findViewById(R.id.check_action);
        if (action != 0) {
            button.setText(action);
            button.setVisibility(View.VISIBLE);
            button.setOnClickListener(listener);
        }
        checks.addView(row);
    }

    private void requestRoot() {
        executor.execute(() -> {
            RootShell.invalidateNow();
            var granted = RootShell.isAvailable();
            main.post(() -> {
                if (isDestroyed()) return;
                Toast.makeText(this,
                    granted ? R.string.status_root_ok : R.string.root_request_failed,
                    Toast.LENGTH_SHORT).show();
                refresh(true);
            });
        });
    }

    private void openShizuku() {
        var intent = getPackageManager()
            .getLaunchIntentForPackage(rikka.shizuku.ShizukuProvider.MANAGER_APPLICATION_ID);
        if (intent == null) {
            intent = new Intent(Intent.ACTION_VIEW,
                Uri.parse("https://github.com/RikkaApps/Shizuku"));
        }
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.error_shizuku_notfound, Toast.LENGTH_SHORT).show();
        }
    }

    private void pickApk() {
        var intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/vnd.android.package-archive",
                "application/zip",
                "application/octet-stream"});
        try {
            startActivityForResult(intent, REQUEST_APK);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.error_parse, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_APK || resultCode != RESULT_OK) return;
        if (data == null || data.getData() == null) return;
        var uri = data.getData();
        var type = getContentResolver().getType(uri);
        var intent = new Intent(this, InstallLaunch.class)
            .setAction(Intent.ACTION_VIEW)
            .setDataAndType(uri, type != null ? type : "application/octet-stream")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(intent);
    }

    private void showUrlDialog() {
        var dialog = CardDialog.create(this);
        currentDialog = dialog;
        dialog.icon(R.drawable.ic_globe)
            .title(R.string.url_dialog_title)
            .message(R.string.url_dialog_label);

        var input = new EditText(this);
        input.setHint(R.string.url_dialog_hint);
        input.setSingleLine(true);
        input.setTextSize(14);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        dialog.addContent(input);

        dialog.button(getString(android.R.string.cancel), R.style.Button_Pill_Outline,
            v -> dialog.dismiss());
        dialog.button(getString(android.R.string.ok), R.style.Button_Pill, v -> {
            var url = input.getText().toString().trim();
            dialog.dismiss();
            if (url.isEmpty()) return;
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                Toast.makeText(this, R.string.error_parse, Toast.LENGTH_SHORT).show();
                return;
            }
            startActivity(new Intent(this, InstallLaunch.class)
                .setAction(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, url));
        });
        var window = dialog.dialog().getWindow();
        if (window != null) {
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        dialog.show();
        input.post(input::requestFocus);
    }

    private void showSettings() {
        new SettingsFragment(new SettingsUserAction(readSafeMode()))
            .show(getSupportFragmentManager(), "settings");
    }
}
