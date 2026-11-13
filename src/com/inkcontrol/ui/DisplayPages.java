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

/** 显示算法与迟滞选择页。从 MainActivity 拆出，状态与通用控件仍通过宿主 {@code a} 访问。 */
final class DisplayPages {
    private final MainActivity a;
    DisplayPages(MainActivity host) { a = host; }

    /** 算法页直接选值，保持现状即撤下本页覆盖；不改旧视频、锁屏细线或独立迟滞。 */
    void displayAlgorithmPage(FeatureDefinition feature) {
        String scope = DisplayAlgorithms.scope(feature.id);
        com.inkcontrol.core.Configuration config = a.snapshot.config;
        Guide.Entry guide = Guide.get(feature.id);
        boolean pending = a.busy && a.pendingId.equals(feature.id);
        boolean requested = pending ? a.pendingEnabled : config.requested(feature.id);
        String selected = requested ? (pending ? a.pendingValue : config.value(feature.id)) : "default";
        LinearLayout state = a.widgets.column(a.widgets.heading("算法选择 · " + Guide.optionName(feature, selected), 18),
                a.widgets.spaced(a.widgets.paragraph(pending ? "正在保存" : "算法状态：" + Guide.selectionStatus(!selected.equals("default"), a.status(feature.id)), 14), 6),
                a.widgets.spaced(a.widgets.paragraph("保持现状时：" + displayBaseline(scope), 14), 8));
        if (!config.enabled("display.gpu")) a.featurePageView.dependencyHint(state, "display.gpu", "GPU 显示后端未开启，当前沿用系统显示路径；已保存的选择仍会保留。");
        a.body.addView(a.widgets.card(state));
        a.body.addView(a.widgets.spaced(a.widgets.paragraph(guide.what, 15), 16));
        LinearLayout options = a.widgets.column();
        for (String value : feature.values) {
            Guide.Option option = guide.options.get(value);
            LinearLayout choice = a.widgets.column(a.ink.text((value.equals(selected) ? "● " : "○ ") + option.name, 17, Ink.BOLD),
                    a.widgets.spaced(a.widgets.paragraph(option.detail, 14), 6));
            choice.setPadding(a.ink.dp(16), a.ink.dp(14), a.ink.dp(16), a.ink.dp(14));
            choice.setBackground(a.ink.box(Ink.PAPER, Ink.INK, value.equals(selected) ? 2.5f : 1, 12));
            choice.setContentDescription(a.ink.t("算法 " + option.name + (value.equals(selected) ? "，已选择" : "")));
            choice.setOnClickListener(v -> {
                if (!a.busy && value.equals(selected)) return;
                saveDisplayChoice(feature.id, value, option.name);
            });
            options.addView(a.widgets.spaced(choice, 10));
        }
        a.body.addView(a.widgets.spaced(a.widgets.section("本模式算法", options), 16));
        if (!DisplayAlgorithms.gray(scope)) displayHysteresis(scope);
        else a.body.addView(a.widgets.spaced(a.widgets.paragraph("16级灰阶算法没有独立迟滞选项。保持现状会保留锁屏细线处理；明确选择算法会覆盖本模式的灰阶量化。", 14), 18));
        a.body.addView(a.widgets.spaced(a.widgets.section("需要知道", a.widgets.paragraph(guide.cost, 14)), 20));
        if (scope.equals("video")) a.body.addView(a.widgets.spaced(a.widgets.paragraph("旧实验室的视频选择继续保存在配置中，本页选「保持现状」即可沿用。新算法选择优先；恢复默认不会清除旧选择。", 14), 12));
    }

    private String displayBaseline(String scope) {
        if (DisplayAlgorithms.gray(scope)) return "沿用原有16级灰阶处理，由原分支决定是否需要抖动；需要抖动且符合锁屏细线条件时，仍沿用「消除锁屏细线」的点量化。该项当前"
                + (a.snapshot.config.enabled("lock.dither") ? "已开启。" : "未开启。" );
        FeatureDefinition feature = FeatureCatalog.get("display.alg." + scope);
        return Guide.optionName(feature, DisplayAlgorithms.baselineAlgorithm(a.snapshot.config, scope))
                + (scope.equals("video") && a.snapshot.config.enabled("experiment.video") ? "（沿用旧视频选择）。" : "。");
    }

    private void displayHysteresis(String scope) {
        com.inkcontrol.core.Configuration config = a.snapshot.config;
        if (!config.enabled("display.gpu")) {
            a.body.addView(a.widgets.spaced(a.widgets.section("独立迟滞", a.widgets.paragraph("开启 GPU 显示后端后，可按当前算法设置。视频默认开启，快速和极速默认关闭。", 14)), 20));
            return;
        }
        // 算法提交期间保持原页开关静止，快照更新后才按新算法改变可用性。
        if (!DisplayAlgorithms.supportsHysteresis(config, scope)) {
            a.body.addView(a.widgets.spaced(a.widgets.section("历史处理", a.widgets.paragraph("原厂误差扩散有内部历史处理，不能使用独立迟滞开关。已保存的独立迟滞选择会保留，切回 Bayer、蓝噪声或固定阈值时继续使用。", 14)), 20));
            return;
        }
        String id = "display.hyst." + scope;
        boolean pending = a.busy && a.pendingId.equals(id);
        boolean current = DisplayAlgorithms.hysteresis(config, scope);
        String policy = pending ? a.pendingValue : DisplayAlgorithms.hysteresisOverride(config, scope);
        boolean shown = pending ? policy.equals("on") || policy.equals("default") && scope.equals("video") : current;
        LinearLayout row = a.widgets.column(a.ink.text("独立迟滞", 18, Ink.BOLD),
                a.widgets.spaced(a.widgets.paragraph("减少静止区域闪点，可能增加拖尾。与算法选择独立保存。", 14), 6));
        CompoundButton toggle = new CompoundButton(a) {
            @Override public void toggle() { }
            @Override public CharSequence getAccessibilityClassName() { return Switch.class.getName(); }
        };
        toggle.setButtonDrawable(a.ink.switchDrawable());
        toggle.setChecked(shown);
        toggle.setContentDescription(a.ink.t(DisplayAlgorithms.name(scope) + "独立迟滞"));
        toggle.setOnClickListener(v -> saveDisplayChoice(id, current ? "off" : "on", current ? "关闭迟滞" : "开启迟滞"));
        row.addView(a.widgets.spaced(toggle, 12));
        row.addView(a.widgets.spaced(a.widgets.paragraph((policy.equals("default") ? "跟随默认：" : "单独设置：") + (shown ? "开启" : "关闭")
                + " · " + (pending ? "正在保存" : "迟滞状态：" + Guide.selectionStatus(!policy.equals("default"), a.status(id))), 14), 8));
        if (!policy.equals("default")) row.addView(a.widgets.spaced(a.ink.button("恢复默认（" + (scope.equals("video") ? "开启" : "关闭") + "）", Ink.Kind.SECONDARY,
                v -> saveDisplayChoice(id, "default", "跟随默认迟滞")), 12));
        a.body.addView(a.widgets.spaced(a.widgets.card(row), 20));
    }

    private void saveDisplayChoice(String id, String value, String label) {
        boolean enabled = !value.equals("default");
        if (!a.begin(id, enabled, value)) return;
        a.perform("保存「" + label + "」", () -> {
            a.change(id, enabled, value);
            return "已保存「" + FeatureCatalog.get(id).title + "」：" + label;
        }, false);
    }
}
