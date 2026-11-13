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

/** 「模块设置」：安装向导、诊断、功能列表排序。从 MainActivity 拆出，状态与通用控件仍通过宿主 {@code a} 访问。 */
final class ModulePages {
    private final MainActivity a;
    ModulePages(MainActivity host) { a = host; }

    /** 「安装」「诊断」的统一入口：跳转卡片 + 语言切换。 */
    void moduleSettings() {
        a.body.addView(a.widgets.spaced(a.widgets.navCard("安装", "导出 Magisk 安装包、启用墨控模块并勾选作用域的完整向导。",
                "", false, "打开安装向导", v -> { a.modulePage = MainActivity.INSTALL; a.render(); }), 12));
        a.body.addView(a.widgets.spaced(a.widgets.navCard("诊断", "查看后端模块、配置应用与各项生效状态，排查异常。",
                "", false, "打开诊断", v -> { a.modulePage = MainActivity.DIAGNOSE; a.render(); }), 12));
        a.body.addView(a.widgets.spaced(a.widgets.navCard("功能列表排序", "调整首页各大功能的显示顺序，按住把手上下拖即可，立即生效、随时可恢复默认。",
                "", false, "打开功能列表排序", v -> { a.modulePage = MainActivity.ORDER; a.render(); }), 12));
        a.body.addView(a.widgets.spaced(a.widgets.navCard("桌面快捷方式", "长按桌面上的墨控图标，直接跳到最常用的几个功能。",
                "", false, "选择快捷方式", v -> { a.modulePage = MainActivity.SHORTCUTS; a.render(); }), 12));
        a.body.addView(a.widgets.spaced(a.widgets.navCard("实验室", Guide.CATEGORIES.get("实验室").intro,
                "", false, "打开实验室", v -> { a.tab = MainActivity.FEATURES; a.category = "实验室"; a.featureId = ""; a.scroll = null; a.render(); }), 12));
        a.body.addView(a.widgets.spaced(a.widgets.navCard("名词解释", "刷新方式、残影、关键帧、波形动画……这些词是什么意思。",
                "", false, "名词解释", v -> a.navigate(MainActivity.TERMS, "")), 12));
        a.body.addView(a.widgets.spaced(a.widgets.navCard("关于", "版本信息、作者与来源、参考项目，以及手动检测更新。",
                "", false, "打开关于", v -> { a.modulePage = MainActivity.ABOUT; a.scroll = null; a.render(); }), 12));

        LinearLayout languageCard = a.widgets.column(a.ink.text("语言 / Language", 16, Ink.BOLD));
        TextView language = a.ink.chip("中 / EN", false, v -> UiLanguage.choose(a));
        language.setContentDescription(a.ink.t("语言 / Language"));
        languageCard.addView(a.widgets.spaced(language, 10));
        a.body.addView(a.widgets.spaced(a.widgets.card(languageCard), 12));

        // 操作结果提示条（顶部「已保存…」那行）的停留时长；默认 30 秒自动消失，也可设为不显示。
        LinearLayout noticeCard = a.widgets.column(a.ink.text("提示时长", 16, Ink.BOLD));
        TextView duration = a.ink.chip(UiNotice.label(a.noticeSeconds), false, v -> UiNotice.choose(a));
        duration.setContentDescription(a.ink.t("提示时长"));
        noticeCard.addView(a.widgets.spaced(duration, 10));
        noticeCard.addView(a.widgets.spaced(a.widgets.paragraph("修改设置后的结果提示会在页面上停留一段时间后自动消失；"
                + "选「不显示」则不再占用页面空间。失败提示不会自动消失。", 13), 8));
        a.body.addView(a.widgets.spaced(a.widgets.card(noticeCard), 12));
    }

    // ───────────────────────── 关于 ─────────────────────────

    /** 首页大功能的显示顺序：按保存的顺序排列；空表示默认顺序。 */
    List<Guide.Category> orderedCategories() {
        Map<String, Guide.Category> pool = new LinkedHashMap<>();
        for (Guide.Category c : Guide.CATEGORIES.values()) pool.put(c.name, c);
        List<Guide.Category> result = new ArrayList<>();
        for (String name : UiCategoryOrder.saved(a).split(";", -1)) {
            Guide.Category c = pool.remove(name);
            if (c != null) result.add(c);
        }
        if (pool.isEmpty()) return result;
        // 保存的顺序里没有的分类（通常是升级后新加的）：插到默认顺序里它前一个「已排好」的分类后面，
        // 而不是一律追加到末尾——否则用户排序过一次之后，以后新增的分类永远跑到最后。
        List<String> defaults = new ArrayList<>(Guide.CATEGORIES.keySet());
        for (Guide.Category c : Guide.CATEGORIES.values()) {
            if (!pool.containsKey(c.name)) continue;
            int insert = 0;
            for (int i = defaults.indexOf(c.name) - 1; i >= 0; i--) {
                int pos = indexOfCategory(result, defaults.get(i));
                if (pos >= 0) { insert = pos + 1; break; }
            }
            result.add(insert, c);
        }
        return result;
    }

    private static int indexOfCategory(List<Guide.Category> list, String name) {
        for (int i = 0; i < list.size(); i++) if (list.get(i).name.equals(name)) return i;
        return -1;
    }

    void categoryOrderPage() {
        a.body.addView(a.widgets.card(a.widgets.column(a.widgets.heading("功能列表排序", 20),
                a.widgets.spaced(a.widgets.paragraph("调整首页各大功能的显示顺序。按住左侧的三条横线上下拖，松手就放到新位置；改动立即生效，只影响排列，不影响任何功能本身；「实验室」在「模块设置」里，不参与排序。", 14), 8))));
        // 编辑列表不含「实验室」：它不在首页显示，顺序由导航位置决定。
        List<Guide.Category> list = new ArrayList<>();
        for (Guide.Category c : orderedCategories()) if (!c.name.equals("实验室")) list.add(c);
        LinearLayout box = DragSort.container(a);
        for (int i = 0; i < list.size(); i++) {
            Guide.Category c = list.get(i);
            // 每一项只占容器的「一格」：上面的细分隔线画在这个包装里，不额外插视图——
            // 拖动落点是按容器子视图的中线算的，多插一个分隔视图就会错位。
            LinearLayout item = new LinearLayout(a);
            item.setOrientation(LinearLayout.VERTICAL);
            if (i > 0) item.addView(a.ink.rule(1));
            LinearLayout row = new LinearLayout(a);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(a.ink.dp(4), a.ink.dp(4), a.ink.dp(12), a.ink.dp(4));
            View handle = DragSort.handle(a, a.ink);
            row.addView(handle);
            TextView name = a.ink.text(c.name, 16, Ink.BOLD);
            name.setPadding(a.ink.dp(8), 0, 0, 0);
            row.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
            item.addView(row);
            DragSort.attach(handle, item, (from, to) -> orderCategories(from, to));
            box.addView(item);
        }
        a.body.addView(a.widgets.spaced(a.widgets.card(box), 16));
        a.body.addView(a.widgets.spaced(a.ink.button("恢复默认顺序", Ink.Kind.SECONDARY, v -> {
            UiCategoryOrder.save(a, "");
            a.render();
        }), 12));
    }

    /** 把第 {@code from} 项拖到第 {@code to} 位后立即保存；保存的是不含「实验室」的完整顺序。 */
    private void orderCategories(int from, int to) {
        List<Guide.Category> list = new ArrayList<>();
        for (Guide.Category c : orderedCategories()) if (!c.name.equals("实验室")) list.add(c);
        if (from < 0 || from >= list.size()) return;
        Guide.Category moved = list.remove(from);
        list.add(Math.max(0, Math.min(to, list.size())), moved);
        StringBuilder value = new StringBuilder();
        for (Guide.Category c : list) {
            if (value.length() > 0) value.append(';');
            value.append(c.name);
        }
        UiCategoryOrder.save(a, value.toString());
        a.render();
    }

    // ───────────────────────── 安装 ─────────────────────────

    void installation() {
        ApplicationServices.Snapshot s = a.snapshot;
        // 配置读取失败时 snapshot 可能是 null：安装页必须照样打开（导出 ZIP 就在这里）
        Map<String, String> env = s == null ? Collections.emptyMap() : s.environment;
        a.body.addView(a.widgets.card(a.widgets.column(
                a.widgets.heading("开始使用", 20),
                a.widgets.spaced(a.widgets.paragraph("适用于 HLTE203T · Android 10 · 固件 L1732.6.01.02，需要 Magisk 与 LSPosed 类框架（如 Vector）。"
                        + "没有 Root 也能浏览功能说明、观看演示和导出安装包。", 14), 8))));

        boolean checked = s != null && s.checked();
        boolean supported = checked && "HLTE203T".equals(env.get("device")) && "29".equals(env.get("sdk"));
        boolean installed = "1".equals(env.get("installed")) && BuildInfo.VERSION.equals(env.get("backend_version"));
        String conflict = env.getOrDefault("conflict", "");
        int hooks = s == null ? 0 : s.hooked.size();

        String checkText = "授予 Root 后读取设备型号、固件和后端状态。";
        if (checked && !supported) checkText += "\n当前设备 " + env.getOrDefault("device", "?") + " 不在适配范围内，增强不会生效。";
        a.body.addView(step(1, checked, "检查环境", checkText,
                a.ink.button("检查环境", Ink.Kind.PRIMARY, v -> a.perform("检查环境", () -> a.services.inspect(), true))));

        String installText = "导出安装包，在 Magisk →「模块」→「从本地安装」中选择它。";
        if (checked && !installed && !env.getOrDefault("backend_version", "").isEmpty())
            installText += "\n已安装的后端是 " + env.get("backend_version") + "，与本 App 不一致，请重新安装。";
        if (!conflict.isEmpty())
            installText += "\n检测到同类模块 " + conflict + " 仍在启用，它会与墨控争用显示库，请在 Magisk 中停用。";
        a.body.addView(step(2, installed && conflict.isEmpty(), "安装 Magisk 后端", installText,
                a.ink.button("导出 Magisk 安装包", Ink.Kind.PRIMARY, v -> a.exportZip())));

        String hookText = "在框架管理器中启用「墨控」，作用域勾选：系统框架、系统界面、桌面（com.hmct.vision）。";
        if (checked && installed && hooks > 0 && hooks < 3) hookText += "\n目前只有 " + hooks + " / 3 个作用域已加载，请检查勾选后重启。";
        if (checked && installed && hooks == 0) hookText += "\n" + MainActivity.NOT_INJECTED;
        a.body.addView(step(3, hooks == 3, "启用框架模块", hookText, null));

        a.body.addView(step(4, s != null && s.ready() && hooks == 3, "重启并开启功能",
                "完成以上步骤后完整重启手机，回到这里重新检查环境，然后到「功能」按需开启。所有增强默认关闭，可随时一键全部关闭。",
                a.ink.button("前往功能", Ink.Kind.SECONDARY, v -> { a.category = ""; a.featureId = ""; a.switchTab(MainActivity.FEATURES); })));
    }

    View step(int number, boolean done, String title, String text, View actions) {
        LinearLayout row = new LinearLayout(a);
        row.setPadding(0, a.ink.dp(20), 0, 0);
        TextView marker = a.ink.text(done ? "✓" : String.valueOf(number), 15, Ink.BOLD);
        marker.setGravity(Gravity.CENTER);
        marker.setBackground(done ? a.ink.solid(16) : a.ink.outline(16));
        marker.setTextColor(done ? Ink.PAPER : Ink.INK);
        marker.setContentDescription(a.ink.t("第 " + number + " 步" + (done ? "，已完成" : "")));
        LinearLayout.LayoutParams markerParams = new LinearLayout.LayoutParams(a.ink.dp(32), a.ink.dp(32));
        markerParams.setMargins(0, 0, a.ink.dp(14), 0);
        row.addView(marker, markerParams);
        LinearLayout content = a.widgets.column(a.ink.text(title, 18, Ink.BOLD), a.widgets.spaced(a.widgets.paragraph(text, 14), 8));
        if (actions != null) content.addView(a.widgets.spaced(actions, 12));
        row.addView(content, new LinearLayout.LayoutParams(0, -2, 1));
        return row;
    }

    // ───────────────────────── 桌面快捷方式 ─────────────────────────

    void diagnostics() {
        ApplicationServices.Snapshot s = a.snapshot;
        // 配置读取失败时 snapshot 可能是 null：诊断页必须照样打开
        Map<String, String> env = s == null ? Collections.emptyMap() : s.environment;
        LinearLayout summary = a.widgets.column(a.widgets.heading("运行环境", 20));
        if (s == null || !s.checked()) {
            summary.addView(a.widgets.spaced(a.widgets.paragraph(s == null ? "环境还没读到（配置读取失败）。检查动作依赖 Root；仍可在「安装」导出 ZIP。"
                    : s.failure.equals("尚未检查环境") ? "尚未检查。点击下方按钮会申请 Root，并读取当前进程与后端状态。"
                    : "检查未完成：" + s.failure + "\n仍可浏览说明和导出 ZIP。", 14), 10));
        } else {
            summary.addView(a.widgets.spaced(fact("设备", env.getOrDefault("device", "?") + " · SDK " + env.getOrDefault("sdk", "?")), 12));
            summary.addView(fact("固件", env.getOrDefault("firmware", "?")));
            summary.addView(fact("后端模块", "1".equals(env.get("installed")) ? "已安装 " + env.getOrDefault("backend_version", "") : "未安装或已停用"));
            summary.addView(fact("框架模块", s.hooked.size() + " / 3 个作用域已加载"));
            if (s.hooked.isEmpty()) {
                summary.addView(a.widgets.spaced(a.ink.rule(1), 12));
                summary.addView(a.widgets.spaced(a.ink.text(MainActivity.NOT_INJECTED, 14, Ink.MEDIUM), 10));
            }
            if (!env.getOrDefault("conflict", "").isEmpty()) summary.addView(fact("冲突模块", env.get("conflict")));
            summary.addView(fact("整体就绪", s.ready() ? "是" : "否"));
            if (!env.getOrDefault("error", "").isEmpty()) summary.addView(fact("后端错误", env.get("error")));
        }
        a.body.addView(a.widgets.card(summary));
        a.body.addView(a.widgets.spaced(a.ink.button("刷新环境与当前进程确认", Ink.Kind.PRIMARY, v -> a.perform("刷新环境", () -> a.services.inspect(), false)), 16));
        a.body.addView(a.widgets.spaced(a.ink.button("重试应用已保存配置", Ink.Kind.SECONDARY, v -> a.perform("重试应用配置", () -> a.services.retry(), true)), 10));

        if (!a.output.isEmpty()) {
            LinearLayout raw = new LinearLayout(a);
            raw.setOrientation(LinearLayout.VERTICAL);
            LinearLayout head = new LinearLayout(a);
            head.setGravity(Gravity.CENTER_VERTICAL);
            head.addView(a.ink.text("最近一次完整输出", 16, Ink.BOLD), new LinearLayout.LayoutParams(0, -2, 1));
            head.addView(a.ink.text(a.outputExpanded ? "收起 ▾" : "展开 ▸", 14, Ink.MEDIUM));
            head.setOnClickListener(v -> { a.outputExpanded = !a.outputExpanded; a.render(); });
            raw.addView(head);
            if (a.outputExpanded) {
                TextView text = a.ink.text(a.output, 11, Ink.MONO);
                text.setTextIsSelectable(true);
                raw.addView(a.widgets.spaced(text, 12));
            }
            a.body.addView(a.widgets.spaced(a.widgets.card(raw), 24));
        }
    }
    private View fact(String key, String value) {
        LinearLayout row = new LinearLayout(a);
        row.setPadding(0, a.ink.dp(6), 0, a.ink.dp(6));
        row.addView(a.ink.text(key, 14, Ink.REGULAR), new LinearLayout.LayoutParams(a.ink.dp(96), -2));
        row.addView(a.ink.text(value, 14, Ink.MEDIUM), new LinearLayout.LayoutParams(0, -2, 1));
        return row;
    }

    // ───────────────────────── 操作 ─────────────────────────
}
