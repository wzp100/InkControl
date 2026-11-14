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

/** 功能行：开关行、下拉行与取值摘要。从 MainActivity 拆出，状态与通用控件仍通过宿主 {@code a} 访问。 */
final class FeatureRows {
    private final MainActivity a;
    FeatureRows(MainActivity host) { a = host; }

    /** 一行「标签 + 当前值 ▾」：点右侧出单选列表，点标签进原详情页（自定义值、预览都在那里）。 */
    View dropdownRow(FeatureDefinition f, String label) {
        LinearLayout row = new LinearLayout(a);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(a.ink.dp(14), a.ink.dp(13), a.ink.dp(12), a.ink.dp(13));
        TextView title = a.ink.text(label, 17, Ink.MEDIUM);
        title.setOnClickListener(v -> a.openFeature(f.id));
        row.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        String value = a.snapshot.config.value(f.id);
        row.addView(a.ink.chip(Guide.optionName(f, value) + " ▾", false, v -> showOptionDialog(f, label)));
        return row;
    }

    /**
     * 单选列表：当前选中的那一项上下各压一条 2dp 粗线「双下划线包裹」，
     * 不靠勾选图标或高亮底色（墨水屏上灰度不可靠）。选完即保存，不用预览＋使用两步。
     * 允许手动输入数值的项把「自定义」放在最上面；已经输入过就直接显示「4次(自定义)」。
     */
    private void showOptionDialog(FeatureDefinition f, String label) {
        List<String> values = realValues(f);
        // 数值项的预设一律按数值从小到大排（遮挡延时、周期性刷新都是）。目录里的首值
        // 仍是默认档，显示顺序不影响默认值；比如周期性刷新默认 5 分钟，列表仍从 1 分钟起。
        if (f.acceptsNumber())
            Collections.sort(values, (a, b) -> {
                Integer left = FeatureDefinition.number(a), right = FeatureDefinition.number(b);
                return left == null || right == null ? 0 : Integer.compare(left, right);
            });
        String current = a.snapshot.config.value(f.id);
        boolean customValue = f.acceptsNumber() && FeatureDefinition.number(current) != null && !values.contains(current);
        LinearLayout list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, a.ink.dp(6), 0, a.ink.dp(6));
        AlertDialog dialog = new AlertDialog.Builder(a).setTitle(a.ink.t(label)).setView(list).create();
        if (f.acceptsNumber()) {
            if (customValue) list.addView(a.widgets.optionMark());
            TextView custom = a.ink.text(customValue ? Guide.optionName(f, current) : "自定义", 17,
                    customValue ? Ink.BOLD : Ink.REGULAR);
            custom.setPadding(a.ink.dp(22), a.ink.dp(11), a.ink.dp(22), a.ink.dp(11));
            custom.setMinHeight(a.ink.dp(44));
            custom.setGravity(Gravity.CENTER_VERTICAL);
            custom.setContentDescription(a.ink.t("自定义 " + f.title));
            custom.setOnClickListener(v -> {
                dialog.dismiss();
                a.optionViews.customNumberDialog(f, a.snapshot.config.requested(f.id), customValue ? current : "");
            });
            list.addView(custom);
            if (customValue) list.addView(a.widgets.optionMark());
        }
        for (String value : values) {
            boolean selected = value.equals(current);
            if (selected) list.addView(a.widgets.optionMark());
            TextView item = a.ink.text(Guide.optionName(f, value), 17, selected ? Ink.BOLD : Ink.REGULAR);
            item.setPadding(a.ink.dp(22), a.ink.dp(11), a.ink.dp(22), a.ink.dp(11));
            item.setMinHeight(a.ink.dp(44));
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setOnClickListener(v -> { dialog.dismiss(); a.optionViews.use(f, value); });
            list.addView(item);
            if (selected) list.addView(a.widgets.optionMark());
        }
        dialog.show();
    }

    /** 紧凑开关行：只留名字和 Switch，点名字仍可进原详情页。 */
    View smallToggleRow(String id) {
        FeatureDefinition f = FeatureCatalog.get(id);
        boolean requested = a.snapshot.config.requested(id);
        LinearLayout row = new LinearLayout(a);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(a.ink.dp(14), a.ink.dp(10), a.ink.dp(12), a.ink.dp(10));
        TextView title = a.ink.text(f.title, 16, Ink.MEDIUM);
        title.setOnClickListener(v -> a.openFeature(id));
        row.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(toggle(f, requested, requested));
        return row;
    }

    View compactToggleRow(String id) { return compactToggleRow(id, false); }

    /**
     * 单行「标题 + 状态 + 开关」：状态（已生效 / 待重启 / 异常）挤到开关左侧，不再单独占一行。
     * 翻页动画总开关这类只有开关、没有参数的项用它，比三行的 {@link #featureRow} 省一半高度。
     * chevron=true 时右侧再加一个「›」，表示点标题还能进详情页（锁屏倒数日这种带编辑页的项）。
     */
    View compactToggleRow(String id, boolean chevron) {
        FeatureDefinition f = FeatureCatalog.get(id);
        boolean requested = a.snapshot.config.requested(id);
        boolean pending = a.busy && id.equals(a.pendingId);
        boolean shown = pending ? a.pendingEnabled : requested;
        String status = pending ? "正在保存" : a.status(id);
        LinearLayout row = new LinearLayout(a);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(a.ink.dp(14), a.ink.dp(9), a.ink.dp(10), a.ink.dp(9));
        TextView title = a.ink.text(f.title, 16, Ink.MEDIUM);
        title.setContentDescription(a.ink.t(f.title + "，打开详情"));
        title.setOnClickListener(v -> a.openFeature(id));
        row.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        TextView state = a.ink.text(MainActivity.glyph(status) + " " + status, 12,
                status.equals("已生效") ? Ink.BOLD : Ink.REGULAR);
        LinearLayout.LayoutParams stateParams = new LinearLayout.LayoutParams(-2, -2);
        stateParams.rightMargin = a.ink.dp(10);
        row.addView(state, stateParams);
        row.addView(toggle(f, shown, requested));
        if (chevron) row.addView(a.widgets.withMargin(a.ink.text("›", 20, Ink.REGULAR), 6));
        return row;
    }

    View featureRow(FeatureDefinition feature) { return featureRow(feature, true); }

    /** {@code links} 为假时只留开关、不显示「›」也不可点（用于收在子页里的行）。 */
    View featureRow(FeatureDefinition feature, boolean links) {
        if (DisplayAlgorithms.algorithmFeature(feature.id)) {
            String scope = DisplayAlgorithms.scope(feature.id);
            String chosen = a.snapshot.config.requested(feature.id) ? a.snapshot.config.value(feature.id) : "default";
            return a.widgets.navCard(DisplayAlgorithms.name(scope), Guide.get(feature.id).summary,
                    Guide.optionName(feature, chosen) + " · " + Guide.selectionStatus(!chosen.equals("default"), a.status(feature.id)), a.snapshot.config.requested(feature.id),
                    feature.title + "，打开算法与迟滞设置", v -> a.openFeature(feature.id), false);
        }
        // 字体是页内操作项，不带开关，直接进页面挑字体
        if (CategoryPages.isFontNode(feature.id)) {
            String applied = a.snapshot.config.value("font.pick");
            String saved = a.snapshot.config.value("font.dir");
            String meta = applied == null || applied.isEmpty() ? "未换过字体" : "上次应用 " + applied;
            if (saved != null && !saved.isEmpty() && !saved.equals(MainActivity.FONT_DIR_DEFAULT)) meta += " · 自定义目录";
            return a.widgets.navCard(feature.title, Guide.get(feature.id).summary, meta, false,
                    feature.title + "，打开字体设置", v -> a.openFeature(feature.id), false);
        }
        // 「显示信息修改」同样是页内操作项：自己没有开关，进去逐项开关
        if (feature.id.equals("lock.ext.edit")) {
            int shown = 0;
            for (String id : MainActivity.EXT_ITEMS) if (a.snapshot.config.enabled(id)) shown++;
            String layout = Guide.optionName(FeatureCatalog.get("lock.ext"), a.snapshot.config.value("lock.ext"));
            String meta = shown == 0 ? "四项都不显示" : layout;
            return a.widgets.navCard(feature.title, Guide.get(feature.id).summary, meta, shown > 0,
                    feature.title + "，选择显示哪几项", v -> a.openFeature(feature.id), false);
        }
        // 「备份系统设置」也是页内操作项：列表里显示有几张快照，不画开关
        if (feature.id.equals("sys.backup")) {
            if (a.backups == null && !a.backupsLoading) a.backupPages.loadBackups();
            String meta;
            if (a.backups == null) meta = "正在读取…";
            else if (a.backups.isEmpty()) meta = "还没有快照";
            else meta = a.backups.size() + " 个快照 · 最近 " + BackupPages.stamp(a.backups.get(0)[1]);
            return a.widgets.navCard(feature.title, Guide.get(feature.id).summary, meta, a.backups != null && !a.backups.isEmpty(),
                    feature.title + "，打开备份", v -> a.openFeature(feature.id), false);
        }
        ApplicationServices.Snapshot s = a.snapshot;
        boolean requested = s.config.requested(feature.id);
        boolean pending = a.busy && feature.id.equals(a.pendingId);
        boolean shown = pending ? a.pendingEnabled : requested;
        boolean child = !feature.dependency.isEmpty();

        LinearLayout row = new LinearLayout(a);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(a.ink.dp(child ? 12 : 16), a.ink.dp(14), a.ink.dp(8), a.ink.dp(14));
        // 常态透明，露出外层列表的描边；按下时加粗框反馈。
        row.setBackground(a.ink.pressable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT), a.ink.box(Ink.PAPER, Ink.INK, 2, 12)));
        if (child) {
            View bar = new View(a);
            bar.setBackgroundColor(Ink.INK);
            LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(a.ink.dp(3), -1);
            barParams.setMargins(0, a.ink.dp(2), a.ink.dp(12), a.ink.dp(2));
            row.addView(bar, barParams);
        }
        String status = pending ? "正在保存" : a.status(feature.id);
        boolean strong = status.equals("已生效") || status.startsWith("异常") || status.equals("待重启");
        boolean rebootPending = status.equals("待重启");
        LinearLayout content = new LinearLayout(a);
        content.setOrientation(LinearLayout.VERTICAL);
        content.addView(titleLine(feature, 17, rebootPending));
        String summary = Guide.get(feature.id).summary;
        content.addView(a.widgets.spaced(a.widgets.paragraph(summary.isEmpty() ? feature.description : summary, 13), 5));
        if (child && shown && links && !s.config.enabled(feature.dependency))
            content.addView(a.widgets.spaced(a.ink.text("需先开启「" + FeatureCatalog.get(feature.dependency).title + "」", 13, Ink.MEDIUM), 5));
        String value = valueSummary(feature, pending ? a.pendingValue : s.config.value(feature.id), shown);
        // 必选参数不是开关：列表里只写当前值，也不画那颗可以拨的 Switch。
        boolean required = FeatureCatalog.required(feature.id);
        String state = required ? Guide.optionName(feature, s.config.value(feature.id))
                : MainActivity.glyph(status) + " " + status + (value.isEmpty() ? "" : " · " + value);
        content.addView(a.widgets.spaced(a.ink.text(state, 13, required ? Ink.MEDIUM : strong ? Ink.BOLD : Ink.REGULAR), 7));
        row.addView(content, new LinearLayout.LayoutParams(0, -2, 1));

        if (!required) {
            CompoundButton toggle = toggle(feature, shown, requested);
            LinearLayout.LayoutParams switchParams = new LinearLayout.LayoutParams(-2, -2);
            switchParams.setMargins(a.ink.dp(10), 0, 0, 0);
            row.addView(toggle, switchParams);
        }
        if (links) {
            row.addView(a.widgets.withMargin(a.ink.text("›", 24, Ink.REGULAR), 8));
            row.setContentDescription(a.ink.t(feature.title + "，打开详情"));
            row.setOnClickListener(v -> a.openFeature(feature.id));
        } else {
            // 收在子页里的行：只留开关，点行不跳页（避免返回时跳出「收纳」回到分类页）
            row.setPadding(a.ink.dp(child ? 12 : 16), a.ink.dp(12), a.ink.dp(16), a.ink.dp(12));
            row.setContentDescription(a.ink.t(feature.title));
        }
        return row;
    }

    /** 列表里显示的当前选择；只有一个选项的功能不显示。 */
    private String valueSummary(FeatureDefinition f, String value, boolean on) {
        if (f.id.equals("motion.hold")) return on ? a.holdEditors.holdSummary(value) : "温度自适应";
        if (f.id.equals("refresh.apps")) {
            String mode = FeatureDefinition.filterWhitelist(value) ? "白名单" : "黑名单";
            int count = MainActivity.appList(value).size();
            return count == 0 ? mode : mode + " · " + count + " 个应用";
        }
        if (f.id.equals("pageanim.readers")) {
            int count = ListEditors.plainList(value).size();
            return count == 0 ? "未选择阅读器" : count + " 个阅读器";
        }
        List<String> real = realValues(f);
        if (real.size() <= 1) return "";
        return Guide.optionName(f, value);
    }
    static List<String> realValues(FeatureDefinition f) {
        List<String> result = new ArrayList<>(f.values);
        result.remove(FeatureDefinition.CURVE_TOKEN);
        result.remove(FeatureDefinition.NUMBER_TOKEN);
        result.remove(FeatureDefinition.APPS_TOKEN);
        result.remove(FeatureDefinition.PACKAGES_TOKEN);
        result.remove(FeatureDefinition.PATH_TOKEN);
        result.remove(FeatureDefinition.FONT_TOKEN);
        result.remove(FeatureDefinition.PACKAGE_TOKEN);
        return result;
    }

    private LinearLayout titleLine(FeatureDefinition feature, float sp, boolean rebootPending) {
        LinearLayout line = new LinearLayout(a);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.addView(a.ink.text(feature.title, sp, Ink.MEDIUM));
        if (feature.experimental) line.addView(a.widgets.withMargin(a.ink.tag("实验", false), 8));
        // 只有回执还没追上配置时才标「需重启」；已生效的 REBOOT 功能不再常驻标签
        if (rebootPending) line.addView(a.widgets.withMargin(a.ink.tag("需重启", false), 6));
        return line;
    }

    /** 不让 CompoundButton 自行翻转：只有提交成功的更改才改变显示状态。 */
    CompoundButton toggle(FeatureDefinition feature, boolean shown, boolean requested) {
        CompoundButton toggle = new CompoundButton(a) {
            @Override public void toggle() { }
            @Override public CharSequence getAccessibilityClassName() { return Switch.class.getName(); }
        };
        toggle.setButtonDrawable(a.ink.switchDrawable());
        toggle.setChecked(shown);
        toggle.setContentDescription(a.ink.t(feature.title));
        toggle.setOnClickListener(v -> {
            boolean next = !requested;
            String value = a.snapshot.config.value(feature.id);
            if (!a.begin(feature.id, next, value)) return;
            a.perform((next ? "开启" : "关闭") + "「" + feature.title + "」", () -> {
                a.change(feature.id, next, value);
                return "已保存「" + feature.title + "」：" + (next ? "开启" : "关闭");
            }, false);
        });
        return toggle;
    }

    // ───────────────────────── 功能：详情页 ─────────────────────────
}
