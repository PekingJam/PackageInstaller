package io.github.vvb2060.packageinstaller.ui;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
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

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.github.vvb2060.packageinstaller.R;
import io.github.vvb2060.packageinstaller.datamodel.DownloadProbe;
import io.github.vvb2060.packageinstaller.datamodel.DownloadStore;
import io.github.vvb2060.packageinstaller.datamodel.DownloadTask;
import io.github.vvb2060.packageinstaller.datamodel.ResumableDownload;
import io.github.vvb2060.packageinstaller.service.DownloadService;
import io.github.vvb2060.packageinstaller.service.InstallService;
import io.github.vvb2060.packageinstaller.ui.CardDialog;

/** 下载任务列表：进行中 / 未完成（可继续）/ 已完成。 */
public class DownloadsActivity extends FragmentActivity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private LinearLayout container;
    private TextView empty;
    private EditText input;
    private CardDialog confirmDialog;
    private boolean probing;
    private TextView addButton;

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
        addButton = findViewById(R.id.download_add);
        addButton.setOnClickListener(v -> submit());
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

    /** 剪贴板里的第一段 http(s) 链接，没有就返回 null。 */
    private String clipboardLink() {
        var clipboard = getSystemService(ClipboardManager.class);
        if (clipboard == null || !clipboard.hasPrimaryClip()) return null;
        var clip = clipboard.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) return null;
        var text = clip.getItemAt(0).coerceToText(this).toString();
        var url = io.github.vvb2060.packageinstaller.datamodel.Urls.first(text);
        if (url != null) return url;
        var trimmed = text.trim();
        return isLink(trimmed) ? trimmed : null;
    }

    private static boolean isLink(String text) {
        return text.startsWith("http://") || text.startsWith("https://");
    }

    private void submit() {
        var url = input.getText().toString().trim();
        if (url.isEmpty()) {
            // 输入框空着就直接用剪贴板里的链接，省一步粘贴
            url = clipboardLink();
            if (url == null) {
                toast(R.string.download_need_link);
                return;
            }
            input.setText(url);
        }
        if (!isLink(url)) {
            toast(R.string.error_parse);
            return;
        }
        probeAndConfirm(url);
    }

    /** 探测链接 → 弹确认框；安装包直接交回安装流程。任务卡片的「重新下载」也走这里。 */
    private void probeAndConfirm(String url) {
        if (probing) return;
        probing = true;
        // 探测最长要几十秒（禁 HEAD 的站会退到 Range GET），按钮不给反馈的话
        // 用户会以为没点上，一直重复点
        setBusy(true);
        var imm = getSystemService(InputMethodManager.class);
        if (imm != null) imm.hideSoftInputFromWindow(input.getWindowToken(), 0);

        final var target = url;
        executor.execute(() -> {
            DownloadProbe probe;
            try {
                probe = DownloadProbe.probe(target);
            } catch (Exception e) {
                // 探测失败也允许下，文件名退化成 URL 最后一段
                probe = DownloadProbe.guess(target);
            }
            final var result = probe;
            main.post(() -> {
                if (isDestroyed()) return;
                probing = false;
                setBusy(false);
                showConfirm(result);
            });
        });
    }

    /** 探测期间把「下载」按钮改成「获取中…」并禁用，不然用户以为没点上。 */
    private void setBusy(boolean busy) {
        if (addButton == null) return;
        addButton.setEnabled(!busy);
        addButton.setText(busy ? R.string.download_probing : R.string.download_start);
    }

    private void showConfirm(DownloadProbe probe) {
        if (confirmDialog != null) confirmDialog.dismiss();
        var dialog = CardDialog.create(this);
        confirmDialog = dialog;
        dialog.icon(R.drawable.ic_download)
            .title(probe.name);
        var dest = probe.clearlyApk()
            ? getString(R.string.download_install_after)
            : getString(R.string.download_field_path, DownloadStore.displayPath(probe.name));
        dialog.message(getString(R.string.download_field_size, Format.size(probe.size)) + "\n"
            + getString(R.string.download_field_type, typeLabel(probe.mime)) + "\n"
            + (probe.host().isEmpty() ? ""
                : getString(R.string.download_field_host, probe.host()) + "\n")
            + dest);
        dialog.button(getString(android.R.string.cancel), R.style.Button_Pill_Outline,
            v -> dialog.dismiss());
        dialog.button(getString(R.string.download_start), R.style.Button_Pill, v -> {
            dialog.dismiss();
            if (probe.clearlyApk()) {
                // 安装包也进下载队列：先下载，下完直接进安装流程，任务照常留在列表里
                InstallService.resume(this, probe.url, probe.name);
            } else {
                DownloadService.start(this, probe.url, probe.name, probe.mime, probe.size);
            }
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
            repairStale(tasks);
            var pending = ResumableDownload.pending(this);
            main.post(() -> {
                loading = false;
                if (isDestroyed()) return;
                render(tasks, pending);
            });
        });
    }

    /**
     * 进程被系统杀掉或本应用被覆盖安装后，记录会停在「下载中 / 安装中」，
     * 但对应的前台服务已经不在了，继续等就是一个取消不掉的僵尸任务。
     * 心跳停了 30 秒以上、服务又没在跑，就降级成「已中断」。
     */
    private void repairStale(List<DownloadTask> tasks) {
        var now = System.currentTimeMillis();
        for (var task : tasks) {
            if (!DownloadTask.STATE_ACTIVE.equals(task.state)
                && !DownloadTask.STATE_INSTALLING.equals(task.state)) continue;
            var alive = (task.isInstall() ? InstallService.RUNNING : DownloadService.RUNNING) > 0;
            if (alive && now - task.updatedAt < 30_000L) continue;
            Log.w("DownloadsActivity", "stale task " + task.id + " state=" + task.state
                + " alive=" + alive + " age=" + (now - task.updatedAt));
            DownloadTask.setState(this, task.id, DownloadTask.STATE_FAILED,
                task.target, null, -1, -1);
            task.state = DownloadTask.STATE_FAILED;
            task.error = null;
            task.updatedAt = now;
        }
    }

    private void render(List<DownloadTask> tasks, List<ResumableDownload.Pending> pending) {
        var signature = signature(tasks, pending);
        if (signature.equals(lastSignature)) {
            // 结构没变（还在下载中），只就地更新进度文字，不重建视图
            for (var task : tasks) {
                var row = rowsById.get(task.id);
                if (row == null) continue;
                ((TextView) row.findViewById(R.id.download_meta)).setText(metaOf(task));
                applyProgress((android.widget.ProgressBar) row.findViewById(R.id.download_progress),
                    task);
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
        if ((DownloadTask.STATE_ACTIVE.equals(task.state)
                || DownloadTask.STATE_INSTALLING.equals(task.state))
            && now - task.updatedAt < 20_000L) {
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
        applyProgress((android.widget.ProgressBar) row.findViewById(R.id.download_progress), task);
        rowsById.put(task.id, row);

        // 一律两个按钮：查看详情 + 删除。打开 / 继续 / 取消下载都在详情里
        row.findViewById(R.id.download_detail).setOnClickListener(v -> showDetail(task));
        row.findViewById(R.id.download_delete).setOnClickListener(v -> confirmDelete(task));
        row.setOnClickListener(v -> showDetail(task));
        container.addView(row);
    }

    private void applyProgress(android.widget.ProgressBar progress, DownloadTask task) {
        if (progress == null) return;
        var state = bucket(task, System.currentTimeMillis());
        if (state == BUCKET_DONE) {
            progress.setVisibility(View.GONE);
            return;
        }
        progress.setVisibility(View.VISIBLE);
        if (DownloadTask.STATE_INSTALLING.equals(task.state)) {
            // 包已经下完，安装本身没有字节进度
            progress.setIndeterminate(true);
            progress.setProgress(0);
            return;
        }
        if (task.size > 0) {
            progress.setIndeterminate(false);
            progress.setProgress((int) Math.min(100, task.downloaded * 100 / task.size));
        } else {
            progress.setIndeterminate(state == BUCKET_ACTIVE);
            progress.setProgress(0);
        }
    }

    /** 删正在下载的任务前先让服务停下，免得它继续往已删的 .part 里写。 */
    private void cancelIfActive(DownloadTask task) {
        if (bucket(task, System.currentTimeMillis()) != BUCKET_ACTIVE) return;
        if (task.isInstall()) {
            InstallService.requestCancel();
        } else {
            DownloadService.requestCancel();
        }
    }

    /** 删除前问一句：只删任务，还是连文件一起删。安装包没有留下文件，就只问任务。 */
    private void confirmDelete(DownloadTask task) {
        var done = bucket(task, System.currentTimeMillis()) == BUCKET_DONE;
        // 只有真正落到 Download 目录的记录才有文件可删
        var hasFile = done && !task.isInstall() && task.target != null;
        var dialog = CardDialog.create(this);
        dialog.icon(R.drawable.ic_trash)
            .title(R.string.delete_task_title)
            .message(getString(!done ? R.string.delete_task_unfinished_msg
                : hasFile ? R.string.delete_task_done_msg
                : R.string.delete_task_no_file_msg, task.name));
        dialog.button(getString(android.R.string.cancel), R.style.Button_Pill_Outline,
            v -> dialog.dismiss());
        dialog.button(getString(R.string.delete_task_only), R.style.Button_Pill_Tonal, v -> {
            dialog.dismiss();
            deleteRecord(task);
        });
        if (hasFile) {
            dialog.button(getString(R.string.delete_task_and_file),
                R.style.Button_Pill_Danger, v -> {
                dialog.dismiss();
                deleteWithFile(task, true);
            });
        } else if (!done) {
            dialog.button(getString(R.string.delete_task_and_rest),
                R.style.Button_Pill_Danger, v -> {
                dialog.dismiss();
                deleteWithFile(task, false);
            });
        }
        dialog.show();
    }

    private void deleteRecord(DownloadTask task) {
        cancelIfActive(task);
        executor.execute(() -> {
            if (!DownloadTask.STATE_DONE.equals(task.state)) {
                ResumableDownload.delete(this, task.url);
            }
            DownloadTask.remove(this, task.id);
            main.post(this::reload);
        });
    }

    private void deleteWithFile(DownloadTask task, boolean done) {
        cancelIfActive(task);
        executor.execute(() -> {
            if (done) {
                if (task.target != null) {
                    try {
                        getContentResolver().delete(android.net.Uri.parse(task.target), null, null);
                    } catch (RuntimeException ignored) {
                    }
                }
            } else {
                ResumableDownload.delete(this, task.url);
            }
            DownloadTask.remove(this, task.id);
            main.post(this::reload);
        });
    }

    private String metaOf(DownloadTask task) {
        var meta = new StringBuilder();
        var install = task.isInstall();
        if (DownloadTask.STATE_DONE.equals(task.state)) {
            meta.append(getString(install ? R.string.downloads_state_installed
                : R.string.downloads_state_done));
            if (task.size > 0) meta.append(" · ").append(Format.size(task.size));
        } else if (DownloadTask.STATE_FAILED.equals(task.state)) {
            if (task.error == null || task.error.isEmpty()) {
                // 取消 / 中断：不带原因，当「未完成」看
                meta.append(getString(R.string.downloads_state_interrupted));
            } else {
                meta.append(getString(install ? R.string.downloads_state_install_failed
                    : R.string.downloads_state_failed)).append(" · ").append(task.error);
            }
        } else if (DownloadTask.STATE_INSTALLING.equals(task.state)
            && bucket(task, System.currentTimeMillis()) == BUCKET_ACTIVE) {
            meta.append(getString(R.string.downloads_state_installing));
        } else if (bucket(task, System.currentTimeMillis()) == BUCKET_ACTIVE) {
            meta.append(getString(R.string.notify_downloading)).append(" · ")
                .append(Format.size(task.downloaded));
            if (task.size > 0) {
                meta.append(" / ").append(Format.size(task.size))
                    .append(" · ").append((int) (task.downloaded * 100 / task.size)).append('%');
            }
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
        // 孤儿记录（有 .part 没任务行）也走详情：临时造一条任务数据
        var task = new DownloadTask(DownloadTask.idOf(item.url), item.url, item.label,
            DownloadProbe.mimeFromName(item.label), DownloadTask.STATE_FAILED);
        task.downloaded = item.downloaded;
        row.findViewById(R.id.download_detail).setOnClickListener(v -> showDetail(task));
        row.findViewById(R.id.download_delete).setOnClickListener(v -> executor.execute(() -> {
            ResumableDownload.delete(this, item.url);
            main.post(this::reload);
        }));
        row.setOnClickListener(v -> showDetail(task));
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

    /**
     * 点任务卡片看详情：状态、保存位置、原始链接（可长按复制），底部「重新下载」和「打开」。
     * 安装包下载完临时文件就删了，所以这类没有「打开」，只能重新下载（会重新走安装流程）。
     */
    private void showDetail(DownloadTask task) {
        var dialog = CardDialog.create(this);
        dialog.icon(R.drawable.ic_download).title(task.name);

        var info = new StringBuilder(metaOf(task));
        if (task.target != null && !task.target.isEmpty()) {
            info.append('\n').append(getString(R.string.download_field_path,
                DownloadStore.displayPath(task.name)));
        }
        if (task.updatedAt > 0) {
            info.append('\n').append(new SimpleDateFormat("yyyy-MM-dd HH:mm",
                Locale.getDefault()).format(new Date(task.updatedAt)));
        }
        dialog.message(info.toString());

        var label = new TextView(this);
        label.setText(R.string.download_detail_link);
        label.setTextSize(11.5f);
        label.setTextColor(getColor(R.color.ui_on_surface_variant));
        dialog.addContent(label);

        var link = new TextView(this);
        link.setText(task.url);
        link.setTextSize(11.5f);
        link.setTextColor(getColor(R.color.ui_primary));
        link.setTextIsSelectable(true);
        link.setOnClickListener(v -> copyLink(task.url));
        link.setPadding(0, dp(3), 0, dp(3));
        dialog.addContent(link);

        var done = DownloadTask.STATE_DONE.equals(task.state);
        var active = bucket(task, System.currentTimeMillis()) == BUCKET_ACTIVE;

        dialog.button(getString(R.string.notify_close), R.style.Button_Pill_Outline,
            v -> dialog.dismiss());
        if (active) {
            dialog.button(getString(R.string.notify_cancel), R.style.Button_Pill_Danger, v -> {
                dialog.dismiss();
                if (task.isInstall()) {
                    InstallService.requestCancel();
                } else {
                    DownloadService.requestCancel();
                }
                toast(R.string.notify_cancelled);
            });
        } else {
            dialog.button(getString(R.string.download_redownload), R.style.Button_Pill_Tonal,
                v -> {
                    dialog.dismiss();
                    redownload(task);
                });
            if (done) {
                if (!task.isInstall() && task.target != null) {
                    dialog.button(getString(R.string.download_open), R.style.Button_Pill, v -> {
                        dialog.dismiss();
                        openFile(task);
                    });
                }
            } else {
                dialog.button(getString(R.string.resume_action), R.style.Button_Pill, v -> {
                    dialog.dismiss();
                    resume(task);
                });
            }
        }
        dialog.show();
    }

    /** 点链接直接复制到剪贴板，比长按选词再点复制快。 */
    private void copyLink(String url) {
        var clipboard = getSystemService(ClipboardManager.class);
        if (clipboard == null) return;
        clipboard.setPrimaryClip(ClipData.newPlainText("url", url));
        toast(R.string.download_link_copied);
    }

    /** 重新下载：清掉旧记录和残留的 .part，再走一遍探测 + 确认。 */
    private void redownload(DownloadTask task) {
        var url = task.url;
        var id = task.id;
        executor.execute(() -> {
            ResumableDownload.delete(this, url);
            DownloadTask.remove(this, id);
            main.post(() -> {
                reload();
                probeAndConfirm(url);
            });
        });
    }

    private void openFile(DownloadTask task) {
        // 服务器给的 mime 经常是 octet-stream，APK 要按安装包类型打开，否则系统找不到安装器
        var mime = task.looksLikeApk()
            ? "application/vnd.android.package-archive" : task.mime;
        var intent = DownloadStore.openIntent(this, task.target, mime);
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

    private void toast(int resId) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
