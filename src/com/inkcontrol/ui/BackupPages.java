package com.inkcontrol.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import com.inkcontrol.core.FeatureCatalog;
import com.inkcontrol.core.FeatureDefinition;
import com.inkcontrol.core.FeatureService;
import com.inkcontrol.core.DisplayAlgorithms;
import com.inkcontrol.features.desktop.DesktopPreview;
import com.inkcontrol.features.lock.LockPreview;
import com.inkcontrol.features.motion.Geometry;
import com.inkcontrol.platform.ApplicationServices;
import com.inkcontrol.platform.BuildInfo;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/** 「备份系统设置」页。从 MainActivity 拆出，状态与通用控件仍通过宿主 {@code a} 访问。 */
final class BackupPages {
    private final MainActivity a;
    BackupPages(MainActivity host) { a = host; }

    void backupPage() {
        a.body.addView(a.widgets.spaced(a.widgets.card(a.widgets.column(
                a.widgets.heading("存的是什么", 18),
                a.widgets.spaced(a.widgets.paragraph("系统设置本身：息屏与锁屏时长、各声道音量、默认输入法、字体名、护眼与游戏模式、"
                        + "显示模式、开发者选项这些。真机上约 420 项，恢复时逐条写回。", 14), 8),
                a.widgets.spaced(a.widgets.paragraph("不备份应用自己的数据、不备份锁屏密码、不碰应用安装信息。", 13), 6))), 16));

        a.body.addView(a.widgets.spaced(a.ink.button("立即备份当前设置", Ink.Kind.PRIMARY, v ->
                a.perform("正在备份系统设置…", () -> {
                    String[] saved = a.services.backupSave();
                    a.backups = null;
                    return "已备份 " + saved[1] + " 项设置，编号 " + saved[0];
                }, false)), 12));

        if (a.backups == null && !a.backupsLoading) loadBackups();
        if (a.backups == null) { a.body.addView(a.widgets.note("正在读取已有快照…")); return; }

        a.body.addView(a.widgets.spaced(a.widgets.heading("已有快照", 18), 18));
        if (a.backups.isEmpty()) {
            a.body.addView(a.widgets.paragraph("还没有备份过。点上面的按钮拍第一张。", 14));
            return;
        }
        for (String[] item : a.backups) a.body.addView(a.widgets.spaced(snapshotCard(item), 10));
        a.body.addView(a.widgets.spaced(a.widgets.paragraph("快照存在 /data/adb/inkcontrol/backup 下，清除应用数据不会带走。"
                + "恢复只改设置项，不会覆盖系统文件；写之前还会先给当前状态拍一张，写坏了能退回去。", 13), 14));
    }

    private View snapshotCard(String[] item) {
        String id = item[0], count = item[2], size = item[3];
        LinearLayout card = a.widgets.column(a.ink.text(stamp(item[1]) + " · " + count + " 项 · " + size + " KB", 15, Ink.BOLD));
        LinearLayout actions = new LinearLayout(a);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, a.ink.dp(10), 0, 0);
        actions.addView(a.ink.chip("写回", false, v -> restoreConfirm(id, count)));
        LinearLayout.LayoutParams removeParams = new LinearLayout.LayoutParams(-2, -2);
        removeParams.leftMargin = a.ink.dp(10);
        actions.addView(a.ink.chip("删除", false, v -> a.confirm("删除快照",
                "删掉 " + id + "？这张快照里的 " + count + " 项就找不回来了。", () -> removeSnapshot(id))), removeParams);
        card.addView(actions);
        return card;
    }

    private void restoreConfirm(String id, String count) {
        a.confirm("写回系统设置",
                "把 " + id + " 里的 " + count + " 项写回当前系统。\n\n"
                        + "写之前会自动给现在的状态拍一张快照，写坏了可以用它退回去。"
                        + "无线网络、字体这类要重启系统界面才看得出来；个别项可能要重启手机。",
                () -> a.perform("正在写回设置…", () -> {
                    String[] done = a.services.backupRestore(id);
                    a.backups = null;
                    String text = "已写回 " + done[0] + " 项，跳过 " + done[1] + " 项，失败 " + done[2] + " 项。";
                    if (!done[3].isEmpty()) text += "\n写回前的状态已存为 " + done[3] + "。";
                    return text;
                }, true));
    }

    private void removeSnapshot(String id) {
        a.perform("正在删除快照…", () -> {
            a.services.backupDelete(id);
            a.backups = null;
            return "已删除快照 " + id;
        }, false);
    }

    void loadBackups() {
        a.backupsLoading = true;
        MainActivity.WORKER.execute(() -> {
            List<String[]> loaded = null;
            String error = "";
            try { loaded = a.services.backupSnapshots(); } catch (Exception failure) { error = MainActivity.reason(failure); }
            List<String[]> result = loaded;
            String problem = error;
            a.runOnUiThread(() -> {
                a.backupsLoading = false;
                if (a.isFinishing() || a.isDestroyed()) return;
                // 读不到（多半是没给 Root）也要把页面画出来，否则只剩下「正在读取」一句
                if (result == null) {
                    a.backups = new ArrayList<>();
                    a.message = problem;
                    a.messageFailed = true;
                } else a.backups = result;
                a.render();
            });
        });
    }

    /** 秒级时间戳转成本地「MM-dd HH:mm」；解析不了就说明时间未知，别把数字直接丢给用户。 */
    static String stamp(String seconds) {
        try {
            long value = Long.parseLong(seconds) * 1000L;
            if (value <= 0) return "时间未知";
            return new java.text.SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(new java.util.Date(value));
        } catch (NumberFormatException invalid) {
            return "时间未知";
        }
    }

    // ───────────────────────── 诊断 ─────────────────────────
}
