package io.github.vvb2060.packageinstaller.ui;

import android.content.ActivityNotFoundException;
import android.content.ClipboardManager;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.FragmentActivity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.github.vvb2060.packageinstaller.R;
import io.github.vvb2060.packageinstaller.datamodel.DownloadProbe;
import io.github.vvb2060.packageinstaller.datamodel.DownloadStore;
import io.github.vvb2060.packageinstaller.datamodel.DownloadTask;
import io.github.vvb2060.packageinstaller.datamodel.ResumableDownload;
import io.github.vvb2060.packageinstaller.service.DownloadService;
import io.github.vvb2060.packageinstaller.ui.CardDialog;
import io.github.vvb2060.packageinstaller.service.InstallService;

/** 下载任务列表：进行中 / 未完成（可继续）/ 已完成。 */
public class DownloadsActivity extends FragmentActivity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private LinearLayout container;
    private TextView empty;
    private EditText input;
    private CardDialog confirmDialog;
    private boolean probing;

    private ScrollView scroll;
    private final Map<String, View> rowsById = new HashMap<>();
    private String lastSignature = "";
    private boolean loading;

    /** 列表页的进度跟随下载服务：每 800ms 读一次任务文件，只刷新变化的文字。 */
    private boolean hasActive;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            reload();
            main.postDelayed(this, hasActive ? 800 : 3000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_downloads);
        setupInsets();
        container = findViewById(R.id.download_list);
        empty = findViewById(R.id.download_empty);
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        scroll = findViewById(R.id.download_scroll);
        input = findViewById(R.id.download_input);
        findViewById(R.id.download_add).setOnClickListener(v -> submit());
        input.setOnEditorActionListener((v, actionId, event) -> {
            submit();
            return true;
        });
        prefillFromClipboard();
    }

    /** 剪贴板里正好是链接就替用户填上，省一次粘贴。 */
    private void prefillFromClipboard() {
        var clipboard = getSystemService(ClipboardManager.class);
        if (clipboard == null || !clipboard.hasPrimaryClip()) return;
        var clip = clipboard.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) return;
        var text = clip.getItemAt(0).coerceToText(this).toString().trim();
        if (isLink(text) && input.getText().length() == 0) input.setText(text);
    }

    private static boolean isLink(String text) {
        return text.startsWith("http://") || text.startsWith("https://");
    }

    private void submit() {
        var url = input.getText().toString().trim();
        if (url.isEmpty()) return;
        if (!isLink(url)) {
            toast(R.string.error_parse);
            return;
        }
        if (probing) return;
        probing = true;
        var imm = getSystemService(InputMethodManager.class);
        if (imm != null) imm.hideSoftInputFromWindow(input.getWindowToken(), 0);

        executor.execute(() -> {
            DownloadProbe probe;
            try {
                probe = DownloadProbe.probe(url);
            } catch (Exception e) {
                // 探测失败也允许下，文件名退化成 URL 最后一段
                probe = DownloadProbe.guess(url);
            }
            final var result = probe;
            main.post(() -> {
                if (isDestroyed()) return;
                probing = false;
                if (result.looksLikeApk()) {
                    // 安装包交回原有的安装流程（预览 + 确认安装）
                    startActivity(new Intent(this, InstallLaunch.class)
                        .setAction(Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, url));
                    input.setText("");
                    return;
                }
                showConfirm(result);
            });
        });
    }

    private void showConfirm(DownloadProbe probe) {
        if (confirmDialog != null) confirmDialog.dismiss();
        var dialog = CardDialog.create(this);
        confirmDialog = dialog;
        dialog.icon(R.drawable.ic_download)
            .title(probe.name);
        dialog.message(getString(R.string.download_field_size, Format.size(probe.size)) + "\n"
            + getString(R.string.download_field_type, typeLabel(probe.mime)) + "\n"
            + (probe.host().isEmpty() ? ""
                : getString(R.string.download_field_host, probe.host()) + "\n")
            + getString(R.string.download_field_path, DownloadStore.displayPath(probe.name)));
        dialog.button(getString(android.R.string.cancel), R.style.Button_Pill_Outline,
            v -> dialog.dismiss());
        dialog.button(getString(R.string.download_start), R.style.Button_Pill, v -> {
            dialog.dismiss();
            DownloadService.start(this, probe.url, probe.name, probe.mime, probe.size);
            input.setText("");
            toast(R.string.download_started);
            main.postDelayed(this::reload, 600);
        });
        dialog.show();
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

    private void setupInsets() {
        var root = findViewById(R.id.root);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        var baseTop = root.getPaddingTop();
        var baseBottom = root.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            var bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(view.getPaddingLeft(), baseTop + bars.top,
                view.getPaddingRight(), baseBottom + bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
        main.removeCallbacks(ticker);
        main.postDelayed(ticker, 800);
    }

    @Override
    protected void onPause() {
        super.onPause();
        main.removeCallbacks(ticker);
    }

    @Override
    protected void onDestroy() {
        if (confirmDialog != null) confirmDialog.dismiss();
        main.removeCallbacks(ticker);
        main.removeCallbacksAndMessages(null);
        executor.shutdownNow();
        super.onDestroy();
    }

    private void reload() {
        if (loading) return;
        loading = true;
        executor.execute(() -> {
            var tasks = DownloadTask.all(this);
            var pending = ResumableDownload.pending(this);
            main.post(() -> {
                loading = false;
                if (isDestroyed()) return;
                render(tasks, pending);
            });
        });
    }

    private void render(List<DownloadTask> tasks, List<ResumableDownload.Pending> pending) {
        var signature = signature(tasks, pending);
        if (signature.equals(lastSignature)) {
            // 结构没变（还在下载中），只就地更新进度文字，不重建视图
            for (var task : tasks) {
                var row = rowsById.get(task.id);
                if (row != null) ((TextView) row.findViewById(R.id.download_meta)).setText(metaOf(task));
            }
            return;
        }
        lastSignature = signature;
        rowsById.clear();
        var scrollY = scroll == null ? 0 : scroll.getScrollY();
        container.removeAllViews();
        var now = System.currentTimeMillis();
        var active = new ArrayList<DownloadTask>();
        var unfinished = new ArrayList<DownloadTask>();
        var done = new ArrayList<DownloadTask>();
        for (var task : tasks) {
            switch (bucket(task, now)) {
                case BUCKET_ACTIVE -> active.add(task);
                case BUCKET_DONE -> done.add(task);
                default -> unfinished.add(task);
            }
        }

        // 缓存里还有 .part 但没有记录的（例如安装包下载中断），也算未完成
        var known = new java.util.HashSet<String>();
        for (var task : tasks) known.add(task.url);
        var orphans = new ArrayList<ResumableDownload.Pending>();
        for (var item : pending) {
            if (!known.contains(item.url)) orphans.add(item);
        }

        var total = active.size() + unfinished.size() + done.size() + orphans.size();
        empty.setVisibility(total == 0 ? View.VISIBLE : View.GONE);

        hasActive = !active.isEmpty() || !orphans.isEmpty();
        addSection(R.string.downloads_section_active, active);
        addSection(R.string.downloads_section_unfinished, unfinished);
        for (var item : orphans) addOrphan(item);
        addSection(R.string.downloads_section_done, done);

        if (scroll != null) {
            main.post(() -> scroll.scrollTo(0, Math.min(scrollY, scroll.getChildAt(0) == null
                ? 0 : Math.max(0, scroll.getChildAt(0).getHeight() - scroll.getHeight()))));
        }
    }

    /** 只反映结构：分组归属 + 状态；进度变化不算，免得每 800ms 重建一次。 */
    private String signature(List<DownloadTask> tasks, List<ResumableDownload.Pending> pending) {
        var builder = new StringBuilder();
        var known = new HashSet<String>();
        for (var task : tasks) {
            known.add(task.url);
            builder.append(task.id).append(':').append(bucket(task, System.currentTimeMillis()))
                .append(';');
        }
        for (var item : pending) {
            if (!known.contains(item.url)) builder.append('p').append(item.url).append(';');
        }
        return builder.toString();
    }

    private static final int BUCKET_ACTIVE = 0;
    private static final int BUCKET_UNFINISHED = 1;
    private static final int BUCKET_DONE = 2;

    private static int bucket(DownloadTask task, long now) {
        if (DownloadTask.STATE_DONE.equals(task.state)) return BUCKET_DONE;
        if (DownloadTask.STATE_ACTIVE.equals(task.state) && now - task.updatedAt < 20_000L) {
            return BUCKET_ACTIVE;
        }
        return BUCKET_UNFINISHED;
    }

    private void addSection(int titleRes, List<DownloadTask> items) {
        if (items.isEmpty()) return;
        var header = new TextView(this, null, 0, R.style.Text_Caption);
        header.setText(titleRes);
        header.setPadding(0, dp(14), 0, dp(6));
        container.addView(header);
        for (var task : items) addRow(task);
    }

    private void addRow(DownloadTask task) {
        var row = getLayoutInflater().inflate(R.layout.item_download, container, false);
        ((TextView) row.findViewById(R.id.download_name)).setText(task.name);

        ((TextView) row.findViewById(R.id.download_meta)).setText(metaOf(task));
        rowsById.put(task.id, row);

        var primary = (TextView) row.findViewById(R.id.download_primary);
        var deleteButton = (TextView) row.findViewById(R.id.download_delete);
        if (bucket(task, System.currentTimeMillis()) == BUCKET_ACTIVE) {
            // 正在下载：不给「继续」「删除」，避免重复启动服务或删掉正在写的 .part
            primary.setText(R.string.notify_cancel);
            primary.setOnClickListener(v -> {
                DownloadService.requestCancel();
                toast(R.string.notify_cancelled);
            });
            deleteButton.setVisibility(View.GONE);
        } else if (DownloadTask.STATE_DONE.equals(task.state)) {
            primary.setText(R.string.download_open);
            primary.setOnClickListener(v -> openFile(task));
            deleteButton.setOnClickListener(v -> delete(task));
        } else {
            primary.setText(R.string.resume_action);
            primary.setOnClickListener(v -> resume(task));
            deleteButton.setOnClickListener(v -> delete(task));
        }
        container.addView(row);
    }

    private String metaOf(DownloadTask task) {
        var meta = new StringBuilder();
        if (DownloadTask.STATE_DONE.equals(task.state)) {
            meta.append(getString(R.string.downloads_state_done));
            if (task.size > 0) meta.append(" · ").append(Format.size(task.size));
        } else if (DownloadTask.STATE_FAILED.equals(task.state)) {
            meta.append(getString(R.string.downloads_state_failed));
            if (task.error != null) meta.append(" · ").append(task.error);
        } else {
            meta.append(Format.size(task.downloaded));
            if (task.size > 0) {
                meta.append(" / ").append(Format.size(task.size))
                    .append(" · ").append((int) (task.downloaded * 100 / task.size)).append('%');
            }
        }
        return meta.toString();
    }

    private void addOrphan(ResumableDownload.Pending item) {
        var row = getLayoutInflater().inflate(R.layout.item_download, container, false);
        ((TextView) row.findViewById(R.id.download_name)).setText(item.label);
        ((TextView) row.findViewById(R.id.download_meta)).setText(
            getString(R.string.resume_downloaded, Format.size(item.downloaded)));
        ((TextView) row.findViewById(R.id.download_primary)).setText(R.string.resume_action);
        row.findViewById(R.id.download_primary).setOnClickListener(v -> {
            InstallService.resume(this, item.url, item.label);
            toast(R.string.resume_started);
        });
        row.findViewById(R.id.download_delete).setOnClickListener(v -> executor.execute(() -> {
            ResumableDownload.delete(this, item.url);
            main.post(this::reload);
        }));
        container.addView(row);
    }

    private void resume(DownloadTask task) {
        if (task.looksLikeApk()) {
            InstallService.resume(this, task.url, task.name);
            toast(R.string.resume_started);
            return;
        }
        DownloadService.start(this, task.url, task.name, task.mime, task.size);
        toast(R.string.resume_started);
    }

    private void openFile(DownloadTask task) {
        var intent = DownloadStore.openIntent(this, task.target, task.mime);
        if (intent == null) {
            toast(R.string.download_open_failed);
            return;
        }
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            toast(R.string.download_open_failed);
        }
    }

    private void delete(DownloadTask task) {
        executor.execute(() -> {
            // 只删缓存里的残留和记录；已经保存进 Download 的文件留给用户自己处理
            if (!DownloadTask.STATE_DONE.equals(task.state)) {
                ResumableDownload.delete(this, task.url);
            }
            DownloadTask.remove(this, task.id);
            main.post(this::reload);
        });
    }

    private void toast(int resId) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
