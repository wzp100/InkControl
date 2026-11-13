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

/** 锁屏相关页面：主题徽标、设备状态显示项。从 MainActivity 拆出，状态与通用控件仍通过宿主 {@code a} 访问。 */
final class LockPages {
    private final MainActivity a;
    LockPages(MainActivity host) { a = host; }

    /** 锁屏主题的分类页：标题行右上角挂「刷新格言」，页内不再单列一块。 */
    boolean isLockTheme() {
        return a.tab.equals(MainActivity.FEATURES) && a.featureId.isEmpty() && "锁屏显示".equals(a.category);
    }

    /** 「刷新格言」小徽标：与首页「关于」同一套样式，点一下让原厂格言往前跳一条。 */
    TextView mottoBadge() {
        TextView badge = a.ink.text("刷新格言", 13, Ink.MEDIUM);
        badge.setPadding(a.ink.dp(12), a.ink.dp(7), a.ink.dp(12), a.ink.dp(7));
        badge.setBackground(a.ink.pressable(a.ink.outline(16), a.ink.solid(16)));
        badge.setTextColor(Ink.pressedColors(Ink.INK, Ink.PAPER));
        badge.setContentDescription(a.ink.t("刷新系统的格言"));
        badge.setOnClickListener(v -> com.inkcontrol.features.lock.MottoCommand.request(a));
        return badge;
    }

    /**
     * 「显示信息修改」：点标题就地展开四项，每项前面一个方框，勾一项存一项。
     * 收起时右侧显示「2 / 4」，跟下拉行一样只占一行，不占版面。
     */
    View lockExtBlock() {
        LinearLayout block = new LinearLayout(a);
        block.setOrientation(LinearLayout.VERTICAL);
        int on = 0;
        for (String id : MainActivity.EXT_ITEMS) if (a.snapshot.config.requested(id)) on++;
        LinearLayout head = new LinearLayout(a);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(a.ink.dp(14), a.ink.dp(13), a.ink.dp(12), a.ink.dp(13));
        head.addView(a.ink.text("显示信息修改", 17, Ink.MEDIUM), new LinearLayout.LayoutParams(0, -2, 1));
        head.addView(a.ink.text(on + " / " + MainActivity.EXT_ITEMS.length + (a.lockExtOpen ? " ▴" : " ▾"), 13, Ink.REGULAR));
        head.setContentDescription(a.ink.t("显示信息修改，已选 " + on + " 项" + (a.lockExtOpen ? "，已展开" : "，点开可勾选")));
        head.setOnClickListener(v -> { a.lockExtOpen = !a.lockExtOpen; a.render(); });
        block.addView(head);
        if (!a.lockExtOpen) return block;
        for (String id : MainActivity.EXT_ITEMS) {
            block.addView(a.ink.rule(1));
            block.addView(lockExtItem(FeatureCatalog.get(id)));
        }
        block.addView(a.ink.rule(1));
        // 逐项说明与「图标版 / 文字版」还在原来那一页，留个入口，别把那条路堵死。
        TextView more = a.ink.text("显示方式与说明 ›", 14, Ink.MEDIUM);
        more.setPadding(a.ink.dp(26), a.ink.dp(12), a.ink.dp(12), a.ink.dp(12));
        more.setOnClickListener(v -> a.openFeature("lock.ext.edit"));
        block.addView(more);
        return block;
    }

    /** 展开后的一行：方框 + 名称，点一下勾选／取消并立即保存。 */
    private View lockExtItem(FeatureDefinition f) {
        boolean on = a.snapshot.config.requested(f.id);
        LinearLayout row = new LinearLayout(a);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(a.ink.dp(26), a.ink.dp(11), a.ink.dp(12), a.ink.dp(11));
        row.setBackground(a.ink.pressable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT),
                a.ink.box(Ink.PAPER, Ink.INK, 1.5f, 0)));
        row.addView(a.widgets.checkBox(on));
        row.addView(a.ink.text(f.title, 16, on ? Ink.MEDIUM : Ink.REGULAR), new LinearLayout.LayoutParams(0, -2, 1));
        row.setContentDescription(a.ink.t(f.title + (on ? "，已勾选" : "，未勾选")));
        row.setOnClickListener(v -> {
            boolean next = !on;
            String value = a.snapshot.config.value(f.id);
            if (!a.begin(f.id, next, value)) return;
            a.perform((next ? "勾选「" : "取消「") + f.title + "」", () -> {
                a.change(f.id, next, value);
                return "已保存「" + f.title + "」：" + (next ? "显示" : "不显示");
            }, false);
        });
        return row;
    }

    /**
     * 「显示信息修改」：把设备状态条的四项显示开关收进一页。
     * 本页自己没有开关（值是 {@code @panel}），改的都是 lock.ext.*。
     */
    void extItemsPage(FeatureDefinition f) {
        ApplicationServices.Snapshot s = a.snapshot;
        Guide.Entry g = Guide.get(f.id);
        int shown = 0;
        for (String id : MainActivity.EXT_ITEMS) if (s.config.enabled(id)) shown++;
        String layout = Guide.optionName(FeatureCatalog.get("lock.ext"), s.config.value("lock.ext"));
        LinearLayout state = a.widgets.column(a.widgets.heading(shown + " / " + MainActivity.EXT_ITEMS.length + " 项显示 · " + layout, 18),
                a.widgets.spaced(a.widgets.paragraph(shown == 0 ? "四项都关着，锁屏底部那块位置留空。"
                        : "锁屏底部那块位置按这四项从左到右排，关掉的项不占位，其余各项自动均分宽度。", 14), 6));
        // 总开关关着时四项都画不出来：这页只留提示和前往入口，开关仍可先设好
        if (!s.config.enabled("lock.ext")) a.featurePageView.dependencyHint(state, "lock.ext", "「显示设备状态」没开时这四项都不显示，开关照常保存。");
        a.body.addView(a.widgets.card(state));

        if (!g.what.isEmpty()) a.body.addView(a.widgets.spaced(a.widgets.paragraph(g.what, 15), 20));

        LinearLayout list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setBackground(a.ink.outline(12));
        for (int i = 0; i < MainActivity.EXT_ITEMS.length; i++) {
            if (i > 0) list.addView(a.ink.rule(1));
            // 收在页里的行只留开关：点行不跳页，免得返回时跳出「收纳」回到分类列表
            list.addView(a.featureRows.featureRow(FeatureCatalog.get(MainActivity.EXT_ITEMS[i]), false));
        }
        a.body.addView(a.widgets.spaced(a.widgets.section("显示哪几项", list), 16));
        a.body.addView(a.widgets.spaced(a.ink.button("画成图标版还是文字版（当前：" + layout + "）", Ink.Kind.SECONDARY,
                v -> a.openFeature("lock.ext")), 14));

        if (!g.cost.isEmpty()) a.body.addView(a.widgets.spaced(a.widgets.section("需要知道", a.widgets.paragraph(g.cost, 14)), 20));
    }
}
