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

/** 名单与倒数日编辑：目标阅读器、应用黑白名单、应用选择弹窗、倒数日事项。从 MainActivity 拆出，状态与通用控件仍通过宿主 {@code a} 访问。 */
final class ListEditors {
    private final MainActivity a;
    ListEditors(MainActivity host) { a = host; }

    /** 纯包名逗号列表（不走白/黑名单三段序列化）。 */
    static List<String> plainList(String value) {
        List<String> result = new ArrayList<>();
        if (value == null || value.isEmpty()) return result;
        for (String part : value.split(",")) if (!part.isEmpty()) result.add(part);
        return result;
    }

    /** 翻页动画只对名单里的阅读器生效；这里只管选应用，参数由各功能页自己保存。 */
    void pageReadersEditor(FeatureDefinition f, boolean requested, String current) {
        List<String> chosen = plainList(current);
        LinearLayout box = a.widgets.column(
                a.ink.text("目标阅读器", 18, Ink.BOLD),
                a.widgets.spaced(a.widgets.paragraph("只有名单里的应用会触发翻页动画；不选则不会触发。上下滚动仍走原厂刷新。", 13), 10),
                a.widgets.spaced(a.ink.text(chosen.isEmpty() ? "尚未选择应用" : "当前名单", 15, Ink.BOLD), 10));
        if (!chosen.isEmpty()) box.addView(a.widgets.spaced(a.widgets.paragraph(String.join("、", chosen), 13), 6));
        box.addView(a.widgets.spaced(a.ink.button("选择阅读器", Ink.Kind.PRIMARY,
                v -> appPickerDialog(f, requested, current, true)), 10));
        a.body.addView(a.widgets.spaced(box, 22));
    }

    void appsFilterEditor(FeatureDefinition f, boolean requested, String current) {
        boolean white = FeatureDefinition.filterWhitelist(current);
        List<String> chosen = MainActivity.appList(current);
        LinearLayout box = a.widgets.column(a.ink.text("名单模式", 18, Ink.BOLD),
                a.widgets.spaced(modeCard(f, requested, current, true, "白名单", "只有名单内应用会触发刷新", white), 10),
                a.widgets.spaced(modeCard(f, requested, current, false, "黑名单", "名单内应用会被跳过，其余照常", !white), 10),
                a.widgets.spaced(a.ink.text((white ? "白名单" : "黑名单") + " · " + (chosen.isEmpty() ? "尚未选择应用" : "已加入 " + chosen.size() + " 个应用"), 15, Ink.BOLD), 14),
                a.widgets.spaced(a.ink.button("选择应用", Ink.Kind.PRIMARY, v -> appPickerDialog(f, requested, current)), 10));
        if (!chosen.isEmpty()) box.addView(a.widgets.spaced(a.ink.button("清空名单", Ink.Kind.SECONDARY, v -> {
            String value = FeatureDefinition.withActiveList(current, "");
            if (!a.begin(f.id, requested, value)) return;
            a.perform("清空名单", () -> {
                a.optionViews.saveValueKeeping(f, value);
                return "已清空名单应用";
            }, false);
        }), 10));
        a.body.addView(a.widgets.spaced(box, 22));
    }

    /** 白/黑名单模式卡片；点按立即写入，保留已选名单。 */
    private View modeCard(FeatureDefinition f, boolean requested, String current, boolean white, String name, String detail, boolean selected) {
        LinearLayout card = new LinearLayout(a);
        card.setPadding(a.ink.dp(14), a.ink.dp(14), a.ink.dp(14), a.ink.dp(14));
        card.setBackground(a.ink.box(Ink.PAPER, Ink.INK, selected ? 3 : 1.5f, 12));
        LinearLayout content = a.widgets.column(a.ink.text(name, 16, selected ? Ink.BOLD : Ink.MEDIUM),
                a.widgets.spaced(a.widgets.paragraph(detail, 13), 4));
        if (selected) content.addView(a.widgets.spaced(a.ink.tag(requested ? "✓ 正在使用" : "已选（未开启）", true), 6));
        card.addView(content, new LinearLayout.LayoutParams(0, -2, 1));
        card.setContentDescription(a.ink.t(name));
        card.setOnClickListener(v -> {
            if (selected) return;
            String value = FeatureDefinition.filterMode(current, white);
            if (!a.begin(f.id, requested, value)) return;
            a.perform("使用「" + name + "」", () -> {
                a.optionViews.saveValueKeeping(f, value);
                return "已保存「" + f.title + "」：" + name;
            }, false);
        });
        return card;
    }

    /** 应用选择器：列出可启动应用，支持搜索与多选，保存为逗号分隔的包名。 */
    void appPickerDialog(FeatureDefinition f, boolean requested, String current) {
        appPickerDialog(f, requested, current, false);
    }

    /** plain=true 时保存纯包名逗号列表（翻页动画用），false 保存白/黑名单三段值（自动刷新用）。 */
    void appPickerDialog(FeatureDefinition f, boolean requested, String current, boolean plain) {
        LinkedHashMap<String, String> apps = a.launcherApps();
        List<CheckBox> boxes = new ArrayList<>();
        List<String> packages = new ArrayList<>();
        Set<String> selected = new LinkedHashSet<>(plain ? plainList(current) : MainActivity.appList(current));
        LinearLayout list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        for (Map.Entry<String, String> app : apps.entrySet()) {
            CheckBox box = new CheckBox(a);
            box.setText(app.getValue());
            box.setTextSize(15);
            box.setChecked(selected.contains(app.getKey()));
            box.setPadding(a.ink.dp(10), a.ink.dp(9), a.ink.dp(10), a.ink.dp(9));
            list.addView(box);
            boxes.add(box);
            packages.add(app.getKey());
        }
        if (boxes.isEmpty()) list.addView(a.widgets.paragraph("没有可选择的启动器应用。", 14));
        ScrollView scroller = new ScrollView(a);
        scroller.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroller.addView(list);
        EditText search = new EditText(a);
        search.setHint(a.ink.t("搜索应用"));
        search.setSingleLine(true);
        search.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) { }
            @Override public void afterTextChanged(android.text.Editable text) {
                String query = text.toString().trim().toLowerCase(Locale.ROOT);
                for (int i = 0; i < boxes.size(); i++) {
                    boolean match = query.isEmpty() || boxes.get(i).getText().toString().toLowerCase(Locale.ROOT).contains(query)
                            || packages.get(i).toLowerCase(Locale.ROOT).contains(query);
                    boxes.get(i).setVisibility(match ? View.VISIBLE : View.GONE);
                }
            }
        });
        LinearLayout content = new LinearLayout(a);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(a.ink.dp(16), a.ink.dp(4), a.ink.dp(16), a.ink.dp(4));
        content.addView(search);
        content.addView(scroller, new LinearLayout.LayoutParams(-1, a.ink.dp(360)));
        new AlertDialog.Builder(a)
                .setTitle(a.ink.t("选择应用") + " · " + a.ink.t(f.title)
                        + (plain ? "" : " · " + a.ink.t(FeatureDefinition.filterWhitelist(current) ? "白名单" : "黑名单")))
                .setView(content)
                .setNegativeButton(a.ink.t("取消"), null)
                .setPositiveButton(a.ink.t("保存"), (dialog, which) -> {
                    List<String> chosen = new ArrayList<>();
                    for (int i = 0; i < boxes.size(); i++) if (boxes.get(i).isChecked()) chosen.add(packages.get(i));
                    String value = plain ? String.join(",", chosen)
                            : FeatureDefinition.withActiveList(current, String.join(",", chosen));
                    if (!a.begin(f.id, requested, value)) return;
                    a.perform(a.ink.t("保存"), () -> {
                        a.optionViews.saveValueKeeping(f, value);
                        return "已保存「" + f.title + "」：" + chosen.size() + " 个应用";
                    }, false);
                }).show();
    }

    // ── 倒数日事项：最多 3 组「事项;日期」，事项可空，日期为空该组停用 ──
    // target 是数据落盘的 feature（lock.countdowns）；入口页是 lock.countdown，开关只控制显示。

    void countdownEditor(FeatureDefinition target, String current) {
        final String[] dates = new String[FeatureDefinition.COUNTDOWNS_MAX];
        final boolean[] lunar = new boolean[FeatureDefinition.COUNTDOWNS_MAX];
        final boolean[] shown = new boolean[FeatureDefinition.COUNTDOWNS_MAX];
        // 兼容旧 6 段「事项;日期」值：农历=否、显示=是
        String[] parts = current == null ? new String[0] : current.split(";", -1);
        boolean legacy = parts.length == FeatureDefinition.COUNTDOWNS_MAX * FeatureDefinition.COUNTDOWN_LEGACY_FIELDS;
        boolean modern = parts.length == FeatureDefinition.COUNTDOWNS_MAX * FeatureDefinition.COUNTDOWN_FIELDS;
        int step = legacy ? FeatureDefinition.COUNTDOWN_LEGACY_FIELDS : (modern ? FeatureDefinition.COUNTDOWN_FIELDS : 0);
        LinearLayout box = a.widgets.column(a.ink.text("倒数日事项", 18, Ink.BOLD),
                a.widgets.spaced(a.widgets.paragraph("最多三个，按目标日先后显示。事项名可留空；日期为空该组停用。开启「农历日期」时按农历年/月/日计算（如选 2027-01-01 即 2027 年春节）；关闭「显示」可暂时隐藏该组。", 13), 12));
        final TextView[] dateButtons = new TextView[FeatureDefinition.COUNTDOWNS_MAX];
        final EditText[] itemFields = new EditText[FeatureDefinition.COUNTDOWNS_MAX];
        for (int i = 0; i < FeatureDefinition.COUNTDOWNS_MAX; i++) {
            final int index = i;
            int base = i * step;
            dates[i] = step > 0 && base + 1 < parts.length ? parts[base + 1].trim() : "";
            lunar[i] = !legacy && step > 0 && base + 2 < parts.length && "1".equals(parts[base + 2].trim());
            shown[i] = legacy || step <= 0 || base + 3 >= parts.length || !"0".equals(parts[base + 3].trim());
            LinearLayout row = new LinearLayout(a);
            row.setGravity(Gravity.CENTER_VERTICAL);
            EditText item = new EditText(a);
            item.setHint(a.ink.t("事项（可空）"));
            item.setText(step > 0 && base < parts.length ? parts[base].trim() : "");
            item.setSingleLine(true);
            item.setFilters(new android.text.InputFilter[]{
                    new android.text.InputFilter.LengthFilter(FeatureDefinition.COUNTDOWN_ITEM_MAX),
                    (source, start, end, dest, dstart, dend) -> {
                        // 事项名不得含 ; 与 =：会破坏「事项;日期」序列化与 key=value 配置行
                        StringBuilder out = new StringBuilder();
                        for (int k = start; k < end; k++) {
                            char ch = source.charAt(k);
                            if (ch != ';' && ch != '=' && ch != '\n') out.append(ch);
                        }
                        return out.length() == end - start ? null : out.toString();
                    }});
            item.setBackground(a.ink.box(Ink.PAPER, Ink.INK, 1.5f, 8));
            item.setPadding(a.ink.dp(10), a.ink.dp(8), a.ink.dp(10), a.ink.dp(8));
            itemFields[i] = item;
            row.addView(item, new LinearLayout.LayoutParams(0, -2, 1));
            TextView date = a.ink.button(dates[i].isEmpty() ? "选日期" : dates[i] + (lunar[i] ? "（农历）" : ""), Ink.Kind.SECONDARY, v -> {
                Calendar initial = FeatureDefinition.parseDate(dates[index]);
                if (initial == null) initial = Calendar.getInstance();
                new DatePickerDialog(a, (view, year, month, day) -> {
                    dates[index] = String.format(Locale.ROOT, "%04d-%02d-%02d", year, month + 1, day);
                    dateButtons[index].setText(dates[index] + (lunar[index] ? "（农历）" : ""));
                }, initial.get(Calendar.YEAR), initial.get(Calendar.MONTH), initial.get(Calendar.DAY_OF_MONTH)).show();
            });
            dateButtons[i] = date;
            LinearLayout.LayoutParams dateParams = new LinearLayout.LayoutParams(-2, -2);
            dateParams.leftMargin = a.ink.dp(8);
            row.addView(date, dateParams);
            box.addView(a.widgets.spaced(row, 6));
            // 日期按钮下方：本组独立的「农历日期」「显示」两个开关
            LinearLayout sub = new LinearLayout(a);
            sub.setGravity(Gravity.CENTER_VERTICAL);
            sub.addView(a.ink.text("农历日期", 13, Ink.MEDIUM));
            final CompoundButton lunarToggle = a.widgets.miniToggle(lunar[i]);
            lunarToggle.setOnClickListener(v -> {
                lunar[index] = lunarToggle.isChecked();
                dateButtons[index].setText(dates[index].isEmpty() ? "选日期" : dates[index] + (lunarToggle.isChecked() ? "（农历）" : ""));
            });
            sub.addView(lunarToggle);
            TextView showLabel = a.ink.text("显示", 13, Ink.MEDIUM);
            LinearLayout.LayoutParams showParams = new LinearLayout.LayoutParams(-2, -2);
            showParams.leftMargin = a.ink.dp(18);
            sub.addView(showLabel, showParams);
            final CompoundButton showToggle = a.widgets.miniToggle(shown[i]);
            showToggle.setOnClickListener(v -> shown[index] = showToggle.isChecked());
            sub.addView(showToggle);
            box.addView(a.widgets.spaced(sub, 8));
        }
        box.addView(a.widgets.spaced(a.ink.button("保存", Ink.Kind.PRIMARY, v -> {
            // 填了事项却没选日期：该组不会显示（日期为空停用），先明确提示再保存
            for (int i = 0; i < FeatureDefinition.COUNTDOWNS_MAX; i++) {
                if (!itemFields[i].getText().toString().trim().isEmpty() && dates[i].isEmpty()) {
                    Toast.makeText(a, a.ink.t("事项 " + (i + 1) + " 没选日期，不会显示"), Toast.LENGTH_LONG).show();
                    return;
                }
            }
            StringBuilder value = new StringBuilder();
            for (int i = 0; i < FeatureDefinition.COUNTDOWNS_MAX; i++) {
                if (i > 0) value.append(';');
                value.append(itemFields[i].getText().toString().trim()).append(';').append(dates[i]).append(';')
                        .append(lunar[i] ? '1' : '0').append(';').append(shown[i] ? '1' : '0');
            }
            String text = value.toString();
            if (FeatureDefinition.countdowns(text) == null) {
                // 农历月无此日（选择器按公历选日，30/31 日可能不是合法农历日）
                for (int i = 0; i < FeatureDefinition.COUNTDOWNS_MAX; i++) {
                    if (lunar[i] && !dates[i].isEmpty() && FeatureDefinition.targetDate(dates[i], true) == null) {
                        Toast.makeText(a, a.ink.t("农历日期「" + dates[i] + "」不存在，请改选其他日期"), Toast.LENGTH_LONG).show();
                        return;
                    }
                }
                Toast.makeText(a, a.ink.t("日期格式不正确"), Toast.LENGTH_SHORT).show();
                return;
            }
            if (!a.begin(target.id, a.optionViews.enableOnSave(target) || a.snapshot.config.requested(target.id), text)) return;
            a.perform(a.ink.t("保存"), () -> {
                a.optionViews.saveValue(target, text);
                return "已保存「" + target.title + "」";
            }, false);
        }), 14));
        a.body.addView(a.widgets.spaced(box, 16));
    }

    // ── 字体：目录、字体列表、文件选择、恢复与刷新都在同一页 ─────────────────
}
