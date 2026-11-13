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

/** 「快捷设置」与「翻页动画」页。从 MainActivity 拆出，状态与通用控件仍通过宿主 {@code a} 访问。 */
final class QuickPages {
    private final MainActivity a;
    QuickPages(MainActivity host) { a = host; }

    /**
     * 「快捷设置」页：E-Ink 刷新面板的「墨控快捷设置」按钮进来。
     *
     * <p>只放三个最常用的入口——遮挡息屏、锁屏主题、自动刷新——开关就地能改，点进去分别是
     * 完整的功能页（延时、版式、触发次数等细项都在那边）。首页的分类列表不列这一页。
     */
    void quickPage() {
        a.body.addView(a.widgets.card(a.widgets.column(a.widgets.paragraph("常用三项放在一页，开关直接改；点进每一项可以调细节。", 14))));
        String[] ids = {"wave.lock", "lock.style", "refresh.master"};
        LinearLayout list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setBackground(a.ink.outline(12));
        for (int i = 0; i < ids.length; i++) {
            if (i > 0) list.addView(a.ink.rule(1));
            list.addView(a.featureRows.featureRow(FeatureCatalog.get(ids[i])));
        }
        a.body.addView(a.widgets.spaced(list, 18));
        a.body.addView(a.widgets.spaced(a.widgets.paragraph("遮挡息屏关闭屏幕后要按电源键唤醒；锁屏显示与自动刷新在锁屏、系统界面里生效。", 13), 12));
    }

    /**
     * 「翻页动画」专用页：参数用下拉、操作用单行开关，一屏就能看完。
     * 对齐原插件「02 选择翻页风格」那种紧凑排法，不再铺「它做什么」＋六张选项卡片。
     */
    void pageAnimPage() {
        LinearLayout master = new LinearLayout(a);
        master.setOrientation(LinearLayout.VERTICAL);
        master.setBackground(a.ink.outline(12));
        master.addView(a.featureRows.compactToggleRow("pageanim.master"));
        a.body.addView(a.widgets.spaced(master, 14));

        a.body.addView(a.widgets.spaced(a.ink.text("01 选择翻页风格", 20, Ink.BOLD), 22));
        LinearLayout choices = new LinearLayout(a);
        choices.setOrientation(LinearLayout.VERTICAL);
        choices.setBackground(a.ink.outline(12));
        String[][] picks = {{"pageanim.style", "翻页风格"}, {"pageanim.spacing", "展开速度"},
                {"pageanim.bands", "分块数量"}};
        for (int i = 0; i < picks.length; i++) {
            if (i > 0) choices.addView(a.ink.rule(1));
            choices.addView(a.featureRows.dropdownRow(FeatureCatalog.get(picks[i][0]), picks[i][1]));
        }
        a.body.addView(choices);

        a.body.addView(a.widgets.spaced(a.ink.text("02 翻页操作", 20, Ink.BOLD), 22));
        LinearLayout ops = new LinearLayout(a);
        ops.setOrientation(LinearLayout.VERTICAL);
        ops.setBackground(a.ink.outline(12));
        String[] toggles = {"pageanim.reverse", "pageanim.vkey"};
        for (int i = 0; i < toggles.length; i++) {
            if (i > 0) ops.addView(a.ink.rule(1));
            ops.addView(a.featureRows.smallToggleRow(toggles[i]));
        }
        a.body.addView(ops);

        a.body.addView(a.widgets.spaced(a.ink.text("03 目标阅读器", 20, Ink.BOLD), 22));
        FeatureDefinition readers = FeatureCatalog.get("pageanim.readers");
        String current = a.snapshot.config.value(readers.id);
        List<String> chosen = ListEditors.plainList(current);
        LinearLayout box = a.widgets.column(
                a.ink.text(chosen.isEmpty() ? "未选择阅读器" : "目标阅读器", 15, Ink.MEDIUM),
                a.widgets.spaced(a.widgets.paragraph(chosen.isEmpty() ? "不选则动画不触发；上下滚动仍走原厂刷新。" : String.join("、", chosen), 13), 5));
        box.addView(a.widgets.spaced(a.ink.button("选择阅读器", Ink.Kind.SECONDARY,
                v -> a.listEditors.appPickerDialog(readers, a.snapshot.config.requested(readers.id), current, true)), 10));
        a.body.addView(a.widgets.card(box));
    }
}
