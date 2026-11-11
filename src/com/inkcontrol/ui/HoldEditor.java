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

/** 关键帧停留时间编辑（按温度的九点曲线）。从 MainActivity 拆出，状态与通用控件仍通过宿主 {@code a} 访问。 */
final class HoldEditor {
    private final MainActivity a;
    HoldEditor(MainActivity host) { a = host; }

    float batteryTemperature() {
        try {
            Intent battery = a.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            int t = battery == null ? Integer.MIN_VALUE : battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
            return t == Integer.MIN_VALUE ? 30f : t / 10f;
        } catch (RuntimeException error) { return 30f; }
    }
    String savedHoldMode(boolean requested, String value) {
        if (!requested || value.equals("0")) return "auto";
        int[] curve = FeatureDefinition.curve(value);
        if (curve == null) return "fixed";
        for (int v : curve) if (v != curve[0]) return "curve";
        return "fixed";
    }
    int[] savedHoldValues(String value) {
        int[] curve = FeatureDefinition.curve(value);
        if (curve != null) return curve;
        int fixed = value.matches("[0-9]+") ? Integer.parseInt(value) : 0;
        if (fixed <= 0) return Charts.autoCurve();
        int[] all = new int[FeatureDefinition.TEMPERATURE_BANDS];
        Arrays.fill(all, fixed);
        return all;
    }
    String holdSummary(String value) {
        String mode = savedHoldMode(true, value);
        if (mode.equals("auto")) return "温度自适应";
        int[] v = savedHoldValues(value);
        return mode.equals("fixed") ? "统一 " + v[0] + " 毫秒" : "自定义曲线";
    }
    /** 当前温度下实际生效的停留时长；未开启或该段为 0 时用自动值。 */
    int currentHold() {
        int band = FeatureDefinition.band(batteryTemperature());
        if (!a.snapshot.config.enabled("motion.hold")) return Geometry.autoHoldForBand(band);
        int v = savedHoldValues(a.snapshot.config.value("motion.hold"))[band];
        return v > 0 ? v : Geometry.autoHoldForBand(band);
    }

    void holdEditor(FeatureDefinition f, boolean requested, String current) {
        String saved = savedHoldMode(requested, current);
        if (a.holdMode.isEmpty()) a.holdMode = saved;
        if (a.holdDraft == null) a.holdDraft = savedHoldValues(current);
        float temperature = batteryTemperature();
        int band = FeatureDefinition.band(temperature);
        int[] auto = Charts.autoCurve();

        LinearLayout box = a.column(a.ink.text("怎么决定停留时长", 18, Ink.BOLD));
        LinearLayout modes = new LinearLayout(a);
        String[][] choices = {{"auto", "温度自适应"}, {"fixed", "统一时长"}, {"curve", "自定义曲线"}};
        for (int i = 0; i < choices.length; i++) {
            String mode = choices[i][0];
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, a.ink.dp(42), 1);
            if (i > 0) p.leftMargin = a.ink.dp(8);
            modes.addView(a.ink.chip(choices[i][1], mode.equals(a.holdMode), v -> {
                a.holdMode = mode;
                if (mode.equals("fixed")) { int v0 = a.holdDraft[band] > 0 ? a.holdDraft[band] : auto[band]; Arrays.fill(a.holdDraft, v0); }
                else if (mode.equals("curve") && saved.equals("auto")) a.holdDraft = auto.clone();
                a.render();
            }), p);
        }
        box.addView(a.spaced(modes, 12));

        String explain;
        int[] shownValues;
        if (a.holdMode.equals("auto")) { explain = "按电池温度自动取“刚好刷完 + 3 帧余量”。天冷时自动变长，避免画面糊在一起。"; shownValues = auto; }
        else if (a.holdMode.equals("fixed")) { explain = "所有温度都使用同一个时长。天冷时如果低于虚线，动画可能糊。"; shownValues = a.holdDraft; }
        else { explain = "上下拖动每根柱子，单独设置每个温度段，10 毫秒一档。虚线是该温度刷完所需的时间。"; shownValues = a.holdDraft; }
        box.addView(a.spaced(a.paragraph(explain, 14), 12));

        int now = a.holdMode.equals("auto") || shownValues[band] <= 0 ? auto[band] : shownValues[band];
        box.addView(a.spaced(a.ink.text(String.format(Locale.ROOT, "当前电池温度 %.1f°C → 每张中间画面停留 %d 毫秒", temperature, now), 14, Ink.BOLD), 12));

        Charts.HoldCurve curve = new Charts.HoldCurve(a, a.ink, shownValues, band, a.holdMode.equals("curve"),
                values -> { a.holdDraft = values; a.render(); });
        box.addView(a.spaced(curve, 14));
        box.addView(a.spaced(a.paragraph("实心柱：设置的时长　虚线：该温度刷完所需　空心柱：低于所需，可能糊", 12), 6));

        if (a.holdMode.equals("fixed")) {
            LinearLayout stepper = new LinearLayout(a);
            stepper.setGravity(Gravity.CENTER_VERTICAL);
            stepper.addView(a.ink.button("−", Ink.Kind.SECONDARY, v -> { Arrays.fill(a.holdDraft, Math.max(30, a.holdDraft[0] - 10)); a.render(); }), new LinearLayout.LayoutParams(a.ink.dp(64), -2));
            TextView value = a.ink.text(a.holdDraft[0] + " 毫秒", 22, Ink.BOLD);
            value.setGravity(Gravity.CENTER);
            stepper.addView(value, new LinearLayout.LayoutParams(0, -2, 1));
            stepper.addView(a.ink.button("+", Ink.Kind.SECONDARY, v -> { Arrays.fill(a.holdDraft, Math.min(FeatureDefinition.CURVE_MAX_MS, a.holdDraft[0] + 10)); a.render(); }), new LinearLayout.LayoutParams(a.ink.dp(64), -2));
            box.addView(a.spaced(stepper, 14));
            FlowLayout presets = new FlowLayout(a, a.ink.dp(8), a.ink.dp(8));
            for (int ms : new int[]{100, 150, 200, 300, 500, 1000}) presets.addView(a.ink.chip(ms + " 毫秒", a.holdDraft[0] == ms, v -> { Arrays.fill(a.holdDraft, ms); a.render(); }));
            box.addView(a.spaced(presets, 12));
        } else if (a.holdMode.equals("curve")) {
            LinearLayout actions = new LinearLayout(a);
            actions.addView(a.ink.button("从自动值开始", Ink.Kind.SECONDARY, v -> { a.holdDraft = auto.clone(); a.render(); }), new LinearLayout.LayoutParams(0, -2, 1));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1);
            p.leftMargin = a.ink.dp(10);
            actions.addView(a.ink.button("整体加长 20%", Ink.Kind.SECONDARY, v -> {
                for (int i = 0; i < a.holdDraft.length; i++) a.holdDraft[i] = Math.min(FeatureDefinition.CURVE_MAX_MS, Math.round(a.holdDraft[i] * 1.2f / 10) * 10);
                a.render();
            }), p);
            box.addView(a.spaced(actions, 14));
        }

        boolean changed = !a.holdMode.equals(saved) || !a.holdMode.equals("auto") && !Arrays.equals(a.holdDraft, savedHoldValues(current));
        if (changed) {
            String mode = a.holdMode;
            int[] values = a.holdDraft.clone();
            box.addView(a.spaced(a.ink.button("保存", Ink.Kind.PRIMARY, v -> {
                String next = mode.equals("auto") ? current : FeatureDefinition.curveValue(values);
                boolean enable = !mode.equals("auto");
                if (!a.begin(f.id, enable, next)) return;
                a.perform("保存关键帧停留", () -> {
                    a.change(f.id, enable, next);
                    return "已保存「关键帧停留」：" + (mode.equals("auto") ? "温度自适应" : mode.equals("fixed") ? "统一 " + values[0] + " 毫秒" : "自定义曲线");
                }, false);
                a.holdMode = ""; a.holdDraft = null;
            }), 16));
        }
        a.body.addView(a.spaced(box, 22));
    }

    // ───────────────────────── 模块设置 ─────────────────────────
}
