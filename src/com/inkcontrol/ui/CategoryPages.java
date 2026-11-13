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

/** 首页分类卡片、分类页与名词解释。从 MainActivity 拆出，状态与通用控件仍通过宿主 {@code a} 访问。 */
final class CategoryPages {
    private final MainActivity a;
    CategoryPages(MainActivity host) { a = host; }

    /** 字体条目是页内操作（目录、字体列表、应用），没有开关，也不计入开启数量。 */
    static boolean isFontNode(String id) { return id.startsWith("font."); }

    /**
     * 没有开关、点进去只做修改的页内操作项：字体（挑字体、应用）与「显示信息修改」
     * （把设备状态的四项显示开关收进一页）。它们不计入「已开启 / 总数」。
     */
    static boolean isPageNode(String id) { return isFontNode(id) || id.equals("lock.ext.edit"); }


    /**
     * 点大功能卡片不经过分类列表、直接落到某个功能页的分类：
     * 字体那一页全是页内操作；「快捷面板修改」原来中间那页只摆一个开关，四格编辑器全收在
     * 「快捷面板按钮」页里，多一层没有内容的列表没有意义。返回值同时让返回跳过这一层。
     */
    static String landingFeature(Guide.Category c) {
        String only = "";
        int count = 0;
        for (String[] section : c.sections) for (int i = 1; i < section.length; i++) { count++; only = section[i]; }
        if (count != 1) return null;
        return isFontNode(only) || only.equals("qs.panel") ? only : null;
    }

    void home() {
        ApplicationServices.Snapshot s = a.snapshot;
        // 状态概览（已开启/待重启/异常）已合并到标题下方的一行（headerStats）。
        if (!s.ready()) {
            LinearLayout overview = a.widgets.column(a.widgets.spaced(a.ink.rule(1), 14));
            String hint = s.checked() ? "环境未就绪：开启增强前请按「模块设置 → 安装」完成安装并完整重启。"
                    : s.failure.equals("尚未检查环境") ? "尚未检查环境。开启任何增强前，先到「模块设置 → 安装」完成检查与安装。"
                    : "环境检查未完成：" + s.failure + "。开启增强前请先到「模块设置 → 安装」处理。";
            overview.addView(a.widgets.spaced(a.widgets.paragraph(hint, 14), 12));
            overview.addView(a.widgets.spaced(a.ink.button("前往安装向导", Ink.Kind.SECONDARY, v -> a.openModule(MainActivity.INSTALL)), 12));
            a.body.addView(a.widgets.card(overview));
        }

        for (Guide.Category c : a.modulePages.orderedCategories()) {
            if (c.name.equals("实验室")) continue;   // 实验室入口移到「模块设置」
            // 只保留异常提示；开启数量与总数不再统计、也不再展示。
            int bad = 0;
            for (String[] section : c.sections) for (int i = 1; i < section.length; i++) {
                if (isPageNode(section[i])) continue;
                if (a.status(section[i]).startsWith("异常")) bad++;
            }
            for (String id : c.nested) if (a.status(id).startsWith("异常")) bad++;
            // 单入口分类（字体、快捷面板修改）跳过中间那层只有一个条目的列表，点卡片直接进详情页
            String landing = landingFeature(c);
            View.OnClickListener open = v -> {
                if (landing != null) a.navigate("", landing); else a.navigate(c.name, "");
            };
            a.body.addView(a.widgets.spaced(categoryCard(c, bad, open), 12));
        }

        a.body.addView(a.widgets.spaced(a.ink.button("检查当前生效状态", Ink.Kind.PRIMARY, v -> a.perform("检查生效状态", () -> a.services.inspect(), false)), 28));
        a.body.addView(a.widgets.spaced(a.ink.button("关闭全部增强", Ink.Kind.DANGER, v -> a.confirm("关闭全部增强", "保留各项选择，并恢复墨控接管的行为。启动期覆盖需完整重启后才完全恢复。",
                () -> a.perform("关闭全部增强", () -> { a.services.disableAll(); return "已提交关闭；请完整重启并检查。"; }, true))), 12));
    }

    /** 分类入口卡：只有分类名、一行简介和异常提示，不再统计「已开启 / 总数」。 */
    private View categoryCard(Guide.Category c, int bad, View.OnClickListener click) {
        LinearLayout card = new LinearLayout(a);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(a.ink.dp(18), a.ink.dp(16), a.ink.dp(14), a.ink.dp(16));
        card.setBackground(a.ink.pressable(a.ink.outline(14), a.ink.box(Ink.PAPER, Ink.INK, 3, 14)));
        LinearLayout content = a.widgets.column();
        LinearLayout titleRow = new LinearLayout(a);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.addView(a.ink.text(c.name, 20, Ink.BOLD));
        if (bad > 0) {
            TextView fault = a.ink.text(bad + " 项异常", 13, Ink.BOLD);
            fault.setPadding(a.ink.dp(10), 0, 0, 0);
            titleRow.addView(fault);
        }
        content.addView(titleRow);
        content.addView(a.widgets.spaced(a.widgets.paragraph(c.intro, 13), 6));
        card.addView(content, new LinearLayout.LayoutParams(0, -2, 1));
        card.addView(a.widgets.withMargin(a.ink.text("›", 28, Ink.REGULAR), 12));
        card.setContentDescription(a.ink.t("分类 " + c.name));
        card.setOnClickListener(click);
        return card;
    }

    void glossary() {
        for (Map.Entry<String, String> term : Guide.TERMS.entrySet()) a.body.addView(a.widgets.spaced(termView(term.getKey(), term.getValue()), 16));
    }
    View termView(String term, String text) {
        return a.widgets.column(a.ink.text(term, 16, Ink.BOLD), a.widgets.spaced(a.widgets.paragraph(text, 14), 4));
    }

    // ───────────────────────── 功能：分类页 ─────────────────────────

    // ───────────────────────── 系统应用卸载 / 还原 ─────────────────────────


    void categoryPage(Guide.Category c) {
        if (MainActivity.SYSAPP_CATEGORY.equals(c.name)) { a.sysAppPages.sysAppPage(); return; }
        // 翻页动画有自己的编号小节与阅读器卡片，其余大功能一律走通用紧凑页。
        if (c.name.equals("翻页动画")) { a.quickPages.pageAnimPage(); return; }
        compactCategoryPage(c);
    }

    /**
     * 全局大功能页：分区标题（带编号）＋ 下拉／单行开关，说明文字全部收进详情页。
     * 页内操作、名单编辑、带预览或自定义输入的项留一个「名称 ›」入口，不丢功能。
     */
    private void compactCategoryPage(Guide.Category c) {
        // 只有一个分组的分类（体感）不再画「01 行为」这种编号小标题：页头已经说明这一页是什么，
        // 编号只剩一个反而占掉一行。
        boolean single = c.sections.length == 1;
        int sectionNo = 0;
        for (String[] section : c.sections) {
            sectionNo++;
            if (!single) {
                TextView head = a.ink.text(String.format(Locale.ROOT, "%02d %s", sectionNo, section[0]), 20, Ink.BOLD);
                head.setPadding(a.ink.dp(4), a.ink.dp(20), 0, a.ink.dp(10));
                a.body.addView(head);
            }
            LinearLayout list = new LinearLayout(a);
            list.setOrientation(LinearLayout.VERTICAL);
            list.setBackground(a.ink.outline(12));
            int rows = 0;
            for (int i = 1; i < section.length; i++) {
                String id = section[i];
                FeatureDefinition f = FeatureCatalog.get(id);
                // 「显示信息修改」是四项多选，直接在页里展开勾选，不再单开一页来回跳。
                // 「锁屏倒数日」既是开关又有自己的编辑页：右侧给状态与开关，点标题仍可进去编辑。
                View row = id.equals("lock.ext.edit") ? a.lockPages.lockExtBlock()
                        : "lock.countdown".equals(id) ? a.featureRows.compactToggleRow(id, true)
                        : compactSpecial(id) ? entryRow(f)
                        : FeatureRows.realValues(f).size() > 1 ? a.featureRows.dropdownRow(f, f.title) : a.featureRows.smallToggleRow(id);
                if (rows++ > 0) list.addView(a.ink.rule(1));
                // 打个 tag：面板长按「全刷扫过」进来时要滚到这一行（见 FOCUS_EXTRA）。
                row.setTag(id);
                list.addView(row);
            }
            a.body.addView(single ? a.widgets.spaced(list, 14) : list);
        }
        if (c.name.equals("桌面")) a.body.addView(a.widgets.spaced(a.ink.button("还原默认设置", Ink.Kind.SECONDARY,
                v -> a.confirm("还原默认设置", "只把桌面主题、图标、字体、壁纸和组件恢复到默认，不改动桌面布局与图标位置。",
                        () -> a.perform("还原桌面设置", () -> { a.services.restoreGroup("桌面"); return "已提交还原；请回到桌面确认。"; }, true))), 20));
    }

    /** 这些项有自己的编辑页／预览／名单，紧凑行放不下，给一个入口即可。 */
    private static boolean compactSpecial(String id) {
        FeatureDefinition f = FeatureCatalog.get(id);
        return isPageNode(id) || id.equals("sys.backup") || id.equals("lock.countdown")
                || id.equals("lock.style") || id.equals("desktop.theme") || id.equals("desktop.icons")
                || id.equals("desktop.wallpaper") || id.equals("desktop.widget")
                || id.equals("qs.panel") || id.equals("refresh.apps") || id.equals("experiment.cache")
                || f.acceptsPanel() || f.acceptsApps() || f.acceptsPackages()
                || f.acceptsPath() || f.acceptsFont() || f.acceptsCountdowns() || f.acceptsPackage()
                || DisplayAlgorithms.algorithmFeature(id);
    }

    /** 「名称 ›」入口行：点进原详情页，右侧顺带显示当前值。 */
    private View entryRow(FeatureDefinition f) {
        LinearLayout row = new LinearLayout(a);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(a.ink.dp(14), a.ink.dp(11), a.ink.dp(12), a.ink.dp(11));
        row.setBackground(a.ink.pressable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT),
                a.ink.box(Ink.PAPER, Ink.INK, 2, 12)));
        row.addView(a.ink.text(f.title, 16, Ink.MEDIUM), new LinearLayout.LayoutParams(0, -2, 1));
        if (FeatureRows.realValues(f).size() > 1)
            row.addView(a.widgets.withMargin(a.ink.text(Guide.optionName(f, a.snapshot.config.value(f.id)), 13, Ink.REGULAR), 6));
        row.addView(a.widgets.withMargin(a.ink.text("›", 24, Ink.REGULAR), 8));
        row.setOnClickListener(v -> a.openFeature(f.id));
        return row;
    }
}
