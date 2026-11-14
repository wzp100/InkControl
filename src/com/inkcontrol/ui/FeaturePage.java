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

/** 单个功能的详情页。从 MainActivity 拆出，状态与通用控件仍通过宿主 {@code a} 访问。 */
final class FeaturePage {
    private final MainActivity a;
    FeaturePage(MainActivity host) { a = host; }

    void featurePage(FeatureDefinition f) {
        if (DisplayAlgorithms.algorithmFeature(f.id)) { a.displayPages.displayAlgorithmPage(f); return; }
        // 字体是「页内操作」，没有开关：目录、字体列表、选择器、恢复与刷新全在一页
        if (CategoryPages.isFontNode(f.id)) { a.fontPages.fontPage(f); return; }
        // 「显示信息修改」：四项显示开关收在一页，本页自己没有开关
        if (f.id.equals("lock.ext.edit")) { a.lockPages.extItemsPage(f); return; }
        // 「备份系统设置」：进去是操作页（拍快照 / 列快照 / 写回），没有开关
        if (f.id.equals("sys.backup")) { a.backupPages.backupPage(); return; }
        // 「快捷面板修改」：整页只有总开关和按钮顺序，交给专用页
        if (f.id.equals("qs.panel")) { a.panelPages.quickPanelPage(); return; }
        ApplicationServices.Snapshot s = a.snapshot;
        Guide.Entry g = Guide.get(f.id);
        boolean requested = s.config.requested(f.id);
        boolean pending = a.busy && f.id.equals(a.pendingId);
        boolean shown = pending ? a.pendingEnabled : requested;
        String current = pending ? a.pendingValue : s.config.value(f.id);
        String selected = a.preview.isEmpty() ? current : a.preview;

        // 1. 状态
        String status = pending ? "正在保存" : a.status(f.id);
        // 必选参数没有「开启/关闭」这两个状态，标题直接写「当前选择」，右侧放当前值。
        boolean required = FeatureCatalog.required(f.id);
        if ("lock.countdown".equals(f.id)) {
            // 这一页的重点是下面的事项编辑器，状态区压成和「翻页动画总开关」一样的单行紧约条，
            // 别让一张大卡片把编辑器顶到一屏之外。
            LinearLayout bar = new LinearLayout(a);
            bar.setOrientation(LinearLayout.VERTICAL);
            bar.setBackground(a.ink.outline(12));
            bar.addView(a.featureRows.compactToggleRow(f.id));
            if (!f.dependency.isEmpty() && !s.config.enabled(f.dependency))
                dependencyHint(bar, f.dependency, "这一项属于「" + FeatureCatalog.get(f.dependency).title + "」，要先开启它才会生效。");
            a.body.addView(a.widgets.spaced(bar, 14));
        } else {
        LinearLayout statusRow = new LinearLayout(a);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout statusTitle = new LinearLayout(a);
        statusTitle.setGravity(Gravity.CENTER_VERTICAL);
        statusTitle.addView(a.ink.text(required ? "当前选择" : shown ? "已开启" : "未开启", 18, Ink.BOLD));
        if (f.experimental) statusTitle.addView(a.widgets.withMargin(a.ink.tag("实验", false), 8));
        if (f.mode == FeatureDefinition.ApplyMode.REBOOT && status.equals("待重启")) statusTitle.addView(a.widgets.withMargin(a.ink.tag("需重启", false), 6));
        LinearLayout statusText = a.widgets.column(statusTitle, a.widgets.spaced(a.ink.text(MainActivity.glyph(status) + " " + status, 14, Ink.REGULAR), 6));
        if (f.mode == FeatureDefinition.ApplyMode.REBOOT) statusText.addView(a.widgets.spaced(a.widgets.paragraph("↻ 更改后需要完整重启才生效。", 13), 4));
        statusRow.addView(statusText, new LinearLayout.LayoutParams(0, -2, 1));
        if (required) statusRow.addView(a.ink.tag(Guide.optionName(f, current), true));
        else if (g.visual != Guide.Visual.HOLD_CURVE) statusRow.addView(a.featureRows.toggle(f, shown, requested));
        LinearLayout statusCard = a.widgets.column(statusRow);
        if (!f.dependency.isEmpty() && !s.config.enabled(f.dependency)) dependencyHint(statusCard, f.dependency, "这一项属于「" + FeatureCatalog.get(f.dependency).title + "」，要先开启它才会生效。");
        if (FeatureService.needsWaveBackend(f.id, selected) && !s.config.enabled("display.gpu") && !f.dependency.equals("display.gpu"))
            dependencyHint(statusCard, "display.gpu", "所选效果是波形动画，需要先开启「GPU 显示后端」并完整重启。");
        // 停在「等待…确认」且三个作用域一个回执都没有：先说明是没注入，别让人以为功能写坏了。
        if (s.hooked.isEmpty() && (status.equals("等待运行确认") || status.equals("等待停用确认"))) {
            statusCard.addView(a.widgets.spaced(a.ink.rule(1), 14));
            statusCard.addView(a.widgets.spaced(a.widgets.paragraph(MainActivity.NOT_INJECTED, 14), 12));
            statusCard.addView(a.widgets.spaced(a.ink.button("前往「诊断」", Ink.Kind.SECONDARY, v -> a.switchTab(MainActivity.DIAGNOSE)), 10));
        }
        a.body.addView(a.widgets.card(statusCard));
        }

        // 2. 名单编辑器（模式 + 应用），放在说明之前
        if (f.id.equals("refresh.apps")) a.listEditors.appsFilterEditor(f, requested, current);
        // 2a. 翻页动画的目标阅读器：纯包名逗号列表，没有白/黑名单模式
        if (f.id.equals("pageanim.readers")) a.listEditors.pageReadersEditor(f, requested, current);
        // 2b. 倒数日事项编辑器：合并到「锁屏倒数日」页内，数据仍写 lock.countdowns
        if (f.id.equals("lock.countdown")) {
            String stored = a.busy && "lock.countdowns".equals(a.pendingId) ? a.pendingValue : s.config.value("lock.countdowns");
            a.listEditors.countdownEditor(FeatureCatalog.get("lock.countdowns"), stored);
        }
        // 2c. 字体页自己处理全部内容（目录 / 列表 / 选择器 / 恢复 / 刷新），这里不再插编辑器
        // 3. 说明：直接给正文，不再套「它做什么」这种没有信息量的小标题
        if (!g.what.isEmpty()) a.body.addView(a.widgets.spaced(a.widgets.paragraph(g.what, 15), 20));
        // 4. 演示 / 示意图
        // 锁屏样式的效果大图例外：那一页真正的「选项」是带缩略图的样式列表，
        // 大图放在列表上面会先占一屏、滚过它才看得到能点的项。按用户要求挪到列表之后（见 5b）。
        View visual = a.previews.visual(f, g, selected);
        boolean visualDeferred = visual != null && g.visual == Guide.Visual.LOCK_STYLE;
        if (visual != null && !visualDeferred) {
            a.body.addView(a.widgets.spaced(visual, 16));
            a.visualView = visual;
            // 预览了别的选项时，在演示下方直接给出“使用”，不必再滚到列表底部。
            if (g.visual != Guide.Visual.HOLD_CURVE && g.visual != Guide.Visual.BARS && FeatureRows.realValues(f).size() > 1 && !selected.equals(current))
                a.body.addView(a.widgets.spaced(a.optionViews.useButton(f, requested, selected), 12));
        }
        // 4. 开启前后
        if (!g.before.isEmpty() || !g.after.isEmpty()) a.body.addView(a.widgets.spaced(beforeAfter(g), 20));
        // 5. 选项
        if (g.visual == Guide.Visual.HOLD_CURVE) a.holdEditors.holdEditor(f, requested, current);
        else if (FeatureRows.realValues(f).size() > 1) a.optionViews.options(f, g, requested, current, selected);
        // 5b. 锁屏样式的大预览：挪到选项列表下方，选完往上对一眼效果。
        //      「使用此主题」跟着标题行走（见 visual()），不再在图片下面重复一颗大按钮。
        if (visualDeferred) {
            a.body.addView(a.widgets.spaced(visual, 16));
            a.visualView = visual;
        }
        // 5c. 「显示设备状态」页给出前往「显示信息修改」的入口：四项显示开关收在那一页
        if (f.id.equals("lock.ext"))
            a.body.addView(a.widgets.spaced(a.ink.button("选择显示哪几项", Ink.Kind.SECONDARY, v -> a.openFeature("lock.ext.edit")), 14));
        // 6. 注意
        if (!g.cost.isEmpty() || !g.note.isEmpty()) {
            LinearLayout notes = a.widgets.column();
            if (!g.cost.isEmpty()) notes.addView(a.widgets.paragraph(g.cost, 14));
            if (!g.note.isEmpty()) notes.addView(a.widgets.spaced(a.ink.text("⚠ " + g.note, 14, Ink.MEDIUM), g.cost.isEmpty() ? 0 : 10));
            // 「锁屏倒数日」这页不铺「需要知道」小标题，注意事项直接跟着编辑器。
            a.body.addView(a.widgets.spaced(f.id.equals("lock.countdown") ? notes : a.widgets.section("需要知道", notes), 20));
        }
        // 7. 名词
        if (!g.terms.isEmpty()) {
            LinearLayout terms = a.widgets.column();
            for (int i = 0; i < g.terms.size(); i++) terms.addView(a.widgets.spaced(a.categoryPages.termView(g.terms.get(i)[0], g.terms.get(i)[1]), i == 0 ? 0 : 14));
            a.body.addView(a.widgets.spaced(a.widgets.section("名词解释", terms), 20));
        }
    }

    void dependencyHint(LinearLayout card, String dependency, String text) {
        card.addView(a.widgets.spaced(a.ink.rule(1), 14));
        card.addView(a.widgets.spaced(a.widgets.paragraph(text, 14), 12));
        card.addView(a.widgets.spaced(a.ink.button("前往「" + FeatureCatalog.get(dependency).title + "」", Ink.Kind.SECONDARY, v -> a.openFeature(dependency)), 10));
    }

    private View beforeAfter(Guide.Entry g) {
        LinearLayout box = a.widgets.column(a.ink.text("开启前后", 18, Ink.BOLD));
        if (!g.before.isEmpty()) box.addView(a.widgets.spaced(compare("开启前", g.before, false), 10));
        if (!g.after.isEmpty()) box.addView(a.widgets.spaced(compare("开启后", g.after, true), 8));
        return box;
    }
    View compare(String label, String text, boolean after) {
        LinearLayout row = new LinearLayout(a);
        row.setPadding(a.ink.dp(14), a.ink.dp(12), a.ink.dp(14), a.ink.dp(12));
        row.setBackground(a.ink.box(Ink.PAPER, Ink.INK, after ? 2.5f : 1, 10));
        TextView tag = a.ink.tag(label, after);
        row.addView(tag);
        TextView content = a.widgets.paragraph(text, 14);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
        params.leftMargin = a.ink.dp(12);
        row.addView(content, params);
        return row;
    }

    // ── 演示与示意图 ──
}
