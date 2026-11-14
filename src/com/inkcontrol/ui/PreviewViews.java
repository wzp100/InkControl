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

/** 效果预览：动画演示、锁屏与桌面预览、时序说明。从 MainActivity 拆出，状态与通用控件仍通过宿主 {@code a} 访问。 */
final class PreviewViews {
    private final MainActivity a;
    PreviewViews(MainActivity host) { a = host; }

    int width() { return a.getResources().getDisplayMetrics().widthPixels - a.ink.dp(32); }

    View visual(FeatureDefinition f, Guide.Entry g, String value) {
        ApplicationServices.Snapshot s = a.snapshot;
        switch (g.visual) {
            case MOTION_SCENE: {
                String scene = f.id.substring("motion.".length());
                MotionDemo demo = demo(scene, value, steps(), origin(), direction(), spread());
                return demoBox("效果演示", demo, timing(value));
            }
            case MOTION_STEPS: {
                String style = s.config.value("motion.app_open");
                if (!style.equals("kwipe")) style = "kiris";
                return demoBox("效果演示（打开应用 · " + Guide.optionName(FeatureCatalog.get("motion.app_open"), style) + "）",
                        demo("app_open", style, Integer.parseInt(value), origin(), direction(), spread()), "共 " + (Integer.parseInt(value) - 1) + " 张中间画面。");
            }
            case MOTION_ORIGIN:
                return demoBox("效果演示（方形展开）", demo("app_open", "kiris", 3, value, direction(), spread()), "");
            case MOTION_DIRECTION:
                return demoBox("效果演示（应用内前进 · 关键帧擦除）", demo("in_app_forward", "kwipe", 3, origin(), value, spread()), "");
            case MOTION_SPREAD:
                return demoBox("效果演示（墨迹晕开）", demo("app_open", "ink", steps(), origin(), direction(), Integer.parseInt(value)),
                        "实际全程约 " + seconds(Integer.parseInt(value) * 11.65 + 117) + " 秒。");
            case LOCK_STYLE: {
                int frameWidth = Math.min(a.ink.dp(240), width());
                View phone = a.widgets.phone(LockPreview.create(a, Integer.parseInt(value), a.snapshot.config), frameWidth);
                // 效果图比一屏还高，选完别的样式要滚到列表底部才看得到「使用」：
                // 把一个同名按钮放到标题行右侧，选完当场就能用。
                View action = value.equals(a.snapshot.config.value(f.id)) ? null : a.optionViews.useChip(f, value);
                return a.widgets.framed("效果图 · " + Guide.optionName(f, value), phone, "图中为样例数据，实际显示你的时间、天气和音乐。", action);
            }
            case DESKTOP_THEME: {
                String kind = s.config.value("desktop.widget");
                return a.widgets.framed("效果图 · " + Guide.optionName(f, value), widget(value, kind), "图为主屏组件（" + Guide.optionName(FeatureCatalog.get("desktop.widget"), kind) + "），图标与字体同一风格。");
            }
            case DESKTOP_WIDGET:
                return a.widgets.framed("效果图 · " + Guide.optionName(f, value), widget(s.config.value("desktop.theme"), value), "使用当前选择的主题「" + Guide.optionName(FeatureCatalog.get("desktop.theme"), s.config.value("desktop.theme")) + "」。");
            case REFRESH_MODES: {
                Map<String, String> marks = new HashMap<>();
                if (f.id.equals("display.apps")) { marks.put("gld", "原来"); marks.put("a2", "开启后"); }
                else marks.put(value.equals("9") ? "a2" : value.equals("2") ? "gc" : "gld", "所选");
                return a.widgets.framed("三种刷新方式", new Charts.RefreshModes(a, a.ink, marks), "条越长越慢；清晰全刷的实心部分表示先闪黑。");
            }
            case BARS:
                return a.widgets.framed(g.barsCaption, new Charts.Bars(a, a.ink, g.bars), "");
            default:
                return null;
        }
    }

    MotionDemo demo(String scene, String style, int steps, String origin, String direction, int spread) {
        // 全刷扫过用自己的方向、速度与形状，别让「动画擦除方向 / 波形动画速度」的取值带进预览。
        if ("gc_clear".equals(scene)) { direction = gcDirection(); spread = gcSpread(); }
        MotionDemo demo = new MotionDemo(a, a.ink, false).configure(scene, style, steps, origin, direction, spread);
        if ("gc_clear".equals(scene)) demo.variant(gcStyle());
        a.mainDemo = demo;
        return demo;
    }
    private View demoBox(String title, MotionDemo demo, String caption) {
        int w = Math.min(a.ink.dp(220), width() - a.ink.dp(40));
        FrameLayout frame = new FrameLayout(a);
        frame.setPadding(a.ink.dp(5), a.ink.dp(5), a.ink.dp(5), a.ink.dp(5));
        frame.setBackground(a.ink.box(Ink.PAPER, Ink.INK, 2.5f, 18));
        frame.addView(demo, new FrameLayout.LayoutParams(-1, -2));
        LinearLayout box = a.widgets.column(a.ink.text(title, 18, Ink.BOLD));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(w + a.ink.dp(10), -2);
        params.gravity = Gravity.CENTER_HORIZONTAL;
        params.topMargin = a.ink.dp(12);
        box.addView(frame, params);
        box.addView(a.widgets.spaced(a.widgets.paragraph("点按画面播放一次。演示把时间放慢，方便看清过程。" + (caption.isEmpty() ? "" : "\n" + caption), 13), 10));
        return box;
    }
    View widget(String theme, String kind) {
        FrameLayout frame = new FrameLayout(a);
        frame.setPadding(a.ink.dp(2), a.ink.dp(2), a.ink.dp(2), a.ink.dp(2));
        frame.setBackground(a.ink.outline(12));
        View content;
        try { content = DesktopPreview.create(a, theme, kind); }
        catch (RuntimeException error) { content = a.widgets.paragraph("预览素材不可用：" + MainActivity.reason(error), 13); }
        frame.addView(content, new FrameLayout.LayoutParams(-1, -1));
        // 组件设计稿 632×432。
        frame.setLayoutParams(new LinearLayout.LayoutParams(-1, Math.round(width() * 432f / 632f)));
        return frame;
    }

    /** 当前设置下的动画参数；子项未开启时取运行端的默认值。 */
    int steps() { return a.snapshot.config.enabled("motion.steps") ? Integer.parseInt(a.snapshot.config.value("motion.steps")) : 2; }
    String origin() { return a.snapshot.config.enabled("motion.origin") ? a.snapshot.config.value("motion.origin") : "touch"; }
    String direction() { return a.snapshot.config.enabled("motion.direction") ? a.snapshot.config.value("motion.direction") : "auto"; }
    /** 全刷扫过自己的方向；auto 时沿用原厂「从左向右」。 */
    private String gcDirection() { return a.snapshot.config.enabled("motion.gc_direction") ? a.snapshot.config.value("motion.gc_direction") : "auto"; }
    /** 场景取哪个方向：全刷扫过用独立设置，其余共用「擦除方向」。 */
    String sceneDirection(String id) { return "motion.gc_clear".equals(id) ? gcDirection() : direction(); }
    int spread() { return a.snapshot.config.enabled("motion.spread") ? Integer.parseInt(a.snapshot.config.value("motion.spread")) : 12; }
    /** 全刷扫过自己的速度；没单独设置时跟随「波形动画速度」。 */
    private int gcSpread() { return a.snapshot.config.enabled("motion.gc_speed") ? Integer.parseInt(a.snapshot.config.value("motion.gc_speed")) : spread(); }
    /** 全刷扫过自己的形状；没单独设置时为横向展开。 */
    private String gcStyle() { return a.snapshot.config.enabled("motion.gc_style") ? a.snapshot.config.value("motion.gc_style") : "horizontal"; }
    private String timing(String style) {
        if (style.equals("kiris") || style.equals("kwipe")) {
            int hold = a.holdEditors.currentHold();
            return "按当前设置，实际约 " + seconds((steps() - 1) * hold + 117) + " 秒（" + (steps() - 1) + " 张中间画面，每张停留 " + hold + " 毫秒）。";
        }
        if (style.equals("gcsweep"))
            return "光带扫过的快慢由「全刷扫过速度」决定；整段仍是一次原厂清晰全刷（约 0.9 秒），去残影效果不变。";
        if (style.equals("none") || style.equals("instant") || style.equals("gc16")) return "保持原厂：画面直接切换，不额外增加刷新。";
        return "按当前设置，实际约 " + seconds(spread() * 11.65 + 117) + " 秒，仍在一次刷新内完成。";
    }
    static String seconds(double ms) { return String.format(Locale.ROOT, "%.2f", ms / 1000.0); }

    // ── 选项卡片 ──
}
