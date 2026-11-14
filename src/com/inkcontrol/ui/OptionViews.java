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

/** 选项卡片、自定义数值与「使用此项」。从 MainActivity 拆出，状态与通用控件仍通过宿主 {@code a} 访问。 */
final class OptionViews {
    private final MainActivity a;
    OptionViews(MainActivity host) { a = host; }

    void options(FeatureDefinition f, Guide.Entry g, boolean requested, String current, String selected) {
        boolean thumbs = g.visual == Guide.Visual.MOTION_SCENE || g.visual == Guide.Visual.LOCK_STYLE;
        // 锁屏样式的效果图比一屏还高，「使用」按钮挪到了标题行右侧，提示要跟着改；
        // 动画场景的按钮仍在列表下方。
        String hint = g.visual == Guide.Visual.LOCK_STYLE ? "点按卡片先预览效果，满意后点标题右侧的「使用此主题」。"
                : g.visual == Guide.Visual.NONE || g.visual == Guide.Visual.BARS ? "点按卡片先查看说明，满意后再点下方按钮使用。"
                : "点按卡片先预览效果，满意后再点下方按钮使用。";
        LinearLayout list = a.widgets.column(a.ink.text(g.visual == Guide.Visual.MOTION_SCENE ? "选择动画" : "选择", 18, Ink.BOLD),
                a.widgets.spaced(a.widgets.paragraph(hint, 13), 6));
        for (String value : FeatureRows.realValues(f)) {
            Guide.Option o = g.options.get(value);
            String name = Guide.optionName(f, value), detail = o == null ? "" : o.detail;
            boolean isCurrent = value.equals(current), isSelected = value.equals(selected);
            LinearLayout card = new LinearLayout(a);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setPadding(a.ink.dp(12), a.ink.dp(12), a.ink.dp(14), a.ink.dp(12));
            card.setBackground(a.ink.box(Ink.PAPER, Ink.INK, isSelected ? 3 : 1.5f, 12));
            if (thumbs) {
                View thumb;
                if (g.visual == Guide.Visual.LOCK_STYLE) thumb = LockPreview.create(a, Integer.parseInt(value), a.snapshot.config);
                else thumb = new MotionDemo(a, a.ink, true).configure(f.id.substring(7), value, a.previews.steps(), a.previews.origin(), a.previews.sceneDirection(f.id), a.previews.spread());
                FrameLayout frame = new FrameLayout(a);
                frame.setPadding(a.ink.dp(2), a.ink.dp(2), a.ink.dp(2), a.ink.dp(2));
                frame.setBackground(a.ink.box(Ink.PAPER, Ink.INK, 1.5f, 6));
                frame.addView(thumb, new FrameLayout.LayoutParams(-1, g.visual == Guide.Visual.LOCK_STYLE ? a.ink.dp(116) : -2));
                LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(a.ink.dp(62), -2);
                tp.rightMargin = a.ink.dp(14);
                card.addView(frame, tp);
            }
            LinearLayout content = a.widgets.column(a.ink.text(name, 16, isSelected ? Ink.BOLD : Ink.MEDIUM));
            if (!detail.isEmpty()) content.addView(a.widgets.spaced(a.widgets.paragraph(detail, 13), 4));
            if (FeatureService.needsWaveBackend(f.id, value) && f.id.startsWith("motion."))
                content.addView(a.widgets.spaced(a.ink.text("需要 GPU 显示后端，并关闭「改用原厂显示算法」", 12, Ink.REGULAR), 4));
            if (isCurrent || isSelected) {
                LinearLayout tags = new LinearLayout(a);
                if (isCurrent) tags.addView(a.ink.tag(requested ? "✓ 正在使用" : "已选（未开启）", true));
                if (isSelected && !isCurrent) tags.addView(a.ink.tag("预览中", false));
                content.addView(a.widgets.spaced(tags, 6));
            }
            card.addView(content, new LinearLayout.LayoutParams(0, -2, 1));
            card.setContentDescription(a.ink.t("选项 " + name + (isCurrent ? "，正在使用" : "") + (isSelected && !isCurrent ? "，预览中" : "")));
            card.setOnClickListener(v -> {
                if (value.equals(selected)) { if (a.mainDemo != null) a.mainDemo.play(); return; }
                a.preview = value.equals(current) ? "" : value;
                a.autoplay = true; a.scrollToVisual = true;
                a.render();
            });
            list.addView(a.widgets.spaced(card, 10));
        }
        if (f.acceptsNumber()) list.addView(a.widgets.spaced(customNumberCard(f, requested, current, selected), 10));
        if (!selected.equals(current) || !requested) list.addView(a.widgets.spaced(useButton(f, requested, selected), 14));
        a.body.addView(a.widgets.spaced(list, 22));
    }

    /** 手动输入数值的卡片；当前值不在预设档位时高亮显示。 */
    private View customNumberCard(FeatureDefinition f, boolean requested, String current, String selected) {
        boolean previewing = FeatureDefinition.number(selected) != null && !f.values.contains(selected);
        String shown = previewing ? selected : current;
        boolean highlighted = FeatureDefinition.number(shown) != null && !f.values.contains(shown);
        LinearLayout card = new LinearLayout(a);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(a.ink.dp(12), a.ink.dp(12), a.ink.dp(14), a.ink.dp(12));
        card.setBackground(a.ink.box(Ink.PAPER, Ink.INK, highlighted ? 3 : 1.5f, 12));
        LinearLayout content = a.widgets.column(a.ink.text("自定义", 16, highlighted ? Ink.BOLD : Ink.MEDIUM),
                a.widgets.spaced(a.widgets.paragraph(highlighted ? "当前：" + shown + Guide.numberUnit(f) : "点按后手动输入数值。", 13), 4));
        if (highlighted) content.addView(a.widgets.spaced(a.ink.tag(requested ? "✓ 正在使用" : "已选（未开启）", true), 6));
        card.addView(content, new LinearLayout.LayoutParams(0, -2, 1));
        card.setContentDescription(a.ink.t("自定义"));
        card.setOnClickListener(v -> customNumberDialog(f, requested, highlighted ? shown : ""));
        return card;
    }

    void customNumberDialog(FeatureDefinition f, boolean requested, String initial) {
        EditText input = new EditText(a);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        input.setText(initial);
        input.setHint(a.ink.t("输入数值"));
        input.setSelection(input.getText().length());
        new AlertDialog.Builder(a)
                .setTitle(a.ink.t("自定义") + " · " + a.ink.t(f.title))
                .setView(input)
                .setNegativeButton(a.ink.t("取消"), null)
                .setPositiveButton(a.ink.t("使用"), (dialog, which) -> {
                    String value = input.getText().toString().trim();
                    if (FeatureDefinition.number(value) == null) {
                        Toast.makeText(a, a.ink.t("请输入 1–999 之间的整数"), Toast.LENGTH_SHORT).show();
                        return;
                    }
                    boolean enable = enableOnSave(f);
                    if (!a.begin(f.id, enable || a.snapshot.config.requested(f.id), value)) return;
                    a.perform(a.ink.t("使用"), () -> {
                        saveValue(f, value);
                        return "已保存「" + f.title + "」：" + value + Guide.numberUnit(f);
                    }, false);
                    a.preview = "";
                }).show();
    }

    View useButton(FeatureDefinition f, boolean requested, String selected) {
        String name = Guide.optionName(f, selected);
        // 依赖没开时按钮只保存，不假装会开启；否则点下去像被拒了。
        String label = !enableOnSave(f) ? "保存「" + name + "」"
                : requested ? "使用「" + name + "」" : "开启并使用「" + name + "」";
        return a.ink.button(label, Ink.Kind.PRIMARY, v -> use(f, selected));
    }

    /** 标题行右侧那一小颗：「使用此主题」。 */
    View useChip(FeatureDefinition f, String selected) {
        return a.ink.chip("使用此主题", true, v -> use(f, selected));
    }

    void use(FeatureDefinition f, String selected) {
        String name = Guide.optionName(f, selected);
        // 依赖的总开关没开时只保存取值、不顺手把这一项打开：否则会被「请先开启…」拦住，
        // 用户想先把参数配好、回头再开总开关就做不到（自动刷新的触发次数、体感的遮挡延时都是这种）。
        boolean enable = enableOnSave(f);
        if (!a.begin(f.id, enable || a.snapshot.config.requested(f.id), selected)) return;
        a.perform("使用「" + name + "」", () -> {
            saveValue(f, selected);
            return "已保存「" + f.title + "」：" + name + (enable ? "" : "（开启后生效）");
        }, false);
        a.preview = "";
    }

    /**
     * 保存一个取值：依赖的总开关开着才顺手打开这一项，否则只落盘、不动开关。
     *
     * <p>「只落盘」走 {@link com.inkcontrol.core.FeatureService#changeValue}，既不触发
     * 「请先开启…」的依赖校验，也不会把原本开着的项关掉。
     */
    void saveValue(FeatureDefinition f, String value) throws Exception {
        if (enableOnSave(f)) a.change(f.id, true, value);
        else a.services.changeValue(f.id, value);
    }

    /** 沿用当前开关状态保存取值（名单、按钮格这类「改内容不改开关」的编辑器用）。 */
    void saveValueKeeping(FeatureDefinition f, String value) throws Exception {
        if (a.snapshot.config.requested(f.id) && enableOnSave(f)) a.change(f.id, true, value);
        else a.services.changeValue(f.id, value);
    }

    /** 保存取值时能不能顺手把这一项打开：只取决于依赖的总开关，必选参数/顶层项恒为 true。 */
    boolean enableOnSave(FeatureDefinition f) {
        return f.dependency.isEmpty() || a.snapshot.config.enabled(f.dependency);
    }

    // ── 应用名单：黑白名单 ──
}
