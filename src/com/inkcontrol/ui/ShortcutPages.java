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

/** 「桌面快捷方式」页。从 MainActivity 拆出，状态与通用控件仍通过宿主 {@code a} 访问。 */
final class ShortcutPages {
    private final MainActivity a;
    ShortcutPages(MainActivity host) { a = host; }

    void shortcutPage() {
        List<String> chosen = UiShortcuts.saved(a);
        a.body.addView(a.spaced(a.card(a.column(
                a.heading("长按桌面图标", 18),
                a.spaced(a.paragraph("长按桌面上的「墨控」图标，弹出的菜单里直接列出下面这几项，点一个就到对应功能页，"
                        + "不再多一层面板。最多 " + UiShortcuts.MAX + " 个。", 14), 8),
                a.spaced(a.paragraph("说明：这几条是安卓的「动态快捷方式」，桌面把它们画成一列「图标 + 名字」，"
                        + "和系统自带的「分享 / 冻结 / 卸载」那一行是同一块菜单的不同样式。"
                        + "名字只留两三个字，字多了会被桌面截成「遮挡息…」。", 13), 8))), 16));

        LinearLayout chosenCard = a.column();
        if (chosen.isEmpty()) chosenCard.addView(a.paragraph("还没有选择任何功能。", 14));
        for (int i = 0; i < chosen.size(); i++) {
            String id = chosen.get(i);
            // 「刷新格言」不是功能页：没有 FeatureDefinition，单独给一行标题，别被 continue 掉。
            if (UiShortcuts.MOTTO.equals(id)) {
                chosenCard.addView(a.spaced(shortcutRow(i + 1, "刷新格言", "快捷动作 · 换下一条"), 10));
                continue;
            }
            FeatureDefinition feature;
            try { feature = FeatureCatalog.get(Guide.destination(id)); }
            catch (RuntimeException unknown) { continue; }
            chosenCard.addView(a.spaced(shortcutRow(i + 1, feature.title, feature.group), 10));
        }
        a.body.addView(a.spaced(a.card(chosenCard), 16));

        a.body.addView(a.spaced(a.ink.button("选择功能", Ink.Kind.PRIMARY, v -> shortcutPicker()), 12));
        a.body.addView(a.spaced(a.ink.button("恢复默认四个", Ink.Kind.SECONDARY, v -> {
            boolean ok = UiShortcuts.reset(a);
            a.dismissNotice();
            a.message = ok ? "已恢复默认：遮挡息屏、锁屏显示、自动刷新、桌面主题" : "桌面没有接受这次改动，稍后再试一次";
            a.messageFailed = !ok;
            a.render();
            if (ok) a.scheduleNoticeDismiss();
        }), 12));
        a.body.addView(a.spaced(a.paragraph("改完立即生效，不用重启桌面。个别桌面会缓存长按菜单，"
                + "第一次可能要多等几秒，或者先滑一下桌面再长按。", 13), 12));
    }

    View shortcutRow(int index, String title, String group) {
        LinearLayout row = new LinearLayout(a);
        // 序号要对齐标题那一行，不能按整块居中：描述一长，圆点会掉到中间去。
        row.setGravity(Gravity.TOP);
        TextView number = a.ink.text(String.valueOf(index), 15, Ink.BOLD);
        number.setGravity(Gravity.CENTER);
        number.setBackground(a.ink.outline(20));
        LinearLayout.LayoutParams badge = new LinearLayout.LayoutParams(a.ink.dp(26), a.ink.dp(26));
        badge.topMargin = a.ink.dp(1);
        row.addView(number, badge);
        LinearLayout text = a.column(a.ink.text(title, 16, Ink.MEDIUM),
                a.spaced(a.paragraph(group, 12), 2));
        LinearLayout.LayoutParams content = new LinearLayout.LayoutParams(0, -2, 1);
        content.leftMargin = a.ink.dp(12);
        row.addView(text, content);
        row.setContentDescription(a.ink.t(title));
        return row;
    }

    /** 选快捷方式：列全部分类下的功能，勾满上限后自动挡回。 */
    void shortcutPicker() {
        Set<String> selected = new LinkedHashSet<>(UiShortcuts.saved(a));
        List<CheckBox> boxes = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        LinearLayout list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        String lastGroup = null;
        for (FeatureDefinition feature : FeatureCatalog.all()) {
            // 纯页面项（「显示信息修改」「倒数日事项」这类）没有自己的开关，当快捷方式没意义
            if (feature.acceptsPanel()) continue;
            if (!feature.group.equals(lastGroup)) {
                lastGroup = feature.group;
                TextView header = a.ink.text(feature.group, 14, Ink.BOLD);
                header.setPadding(a.ink.dp(10), a.ink.dp(14), a.ink.dp(10), a.ink.dp(4));
                list.addView(header);
            }
            CheckBox box = new CheckBox(a);
            box.setText(feature.title);
            box.setTextSize(15);
            box.setPadding(a.ink.dp(10), a.ink.dp(9), a.ink.dp(10), a.ink.dp(9));
            box.setChecked(selected.contains(feature.id));
            box.setOnCheckedChangeListener((button, checked) -> {
                if (!checked) return;
                int count = 0;
                for (CheckBox other : boxes) if (other.isChecked()) count++;
                if (count > UiShortcuts.MAX) {
                    button.setChecked(false);
                    Toast.makeText(a, "最多选 " + UiShortcuts.MAX + " 个，先取消一个再选", Toast.LENGTH_SHORT).show();
                }
            });
            list.addView(box);
            boxes.add(box);
            ids.add(feature.id);
        }
        // 「刷新格言」不是功能页，是一个就地动作，单独归到「快捷动作」一组放在最后。
        TextView actionHead = a.ink.text("快捷动作", 14, Ink.BOLD);
        actionHead.setPadding(a.ink.dp(10), a.ink.dp(14), a.ink.dp(10), a.ink.dp(4));
        list.addView(actionHead);
        CheckBox motto = new CheckBox(a);
        motto.setText("刷新格言");
        motto.setTextSize(15);
        motto.setPadding(a.ink.dp(10), a.ink.dp(9), a.ink.dp(10), a.ink.dp(9));
        motto.setChecked(selected.contains(UiShortcuts.MOTTO));
        motto.setOnCheckedChangeListener((button, checked) -> {
            if (!checked) return;
            int count = 0;
            for (CheckBox other : boxes) if (other.isChecked()) count++;
            if (count > UiShortcuts.MAX) {
                button.setChecked(false);
                Toast.makeText(a, "最多选 " + UiShortcuts.MAX + " 个，先取消一个再选", Toast.LENGTH_SHORT).show();
            }
        });
        list.addView(motto);
        boxes.add(motto);
        ids.add(UiShortcuts.MOTTO);
        ScrollView scroller = new ScrollView(a);
        scroller.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroller.setScrollbarFadingEnabled(false);
        scroller.addView(list);
        LinearLayout content = new LinearLayout(a);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(a.ink.dp(10), a.ink.dp(4), a.ink.dp(10), a.ink.dp(4));
        content.addView(a.paragraph("勾选顺序按列表从上到下排列，与点选先后无关。", 12));
        content.addView(scroller, new LinearLayout.LayoutParams(-1, a.ink.dp(380)));
        new AlertDialog.Builder(a)
                .setTitle(a.ink.t("选择快捷方式") + "（最多 " + UiShortcuts.MAX + " 个）")
                .setView(content)
                .setNegativeButton(a.ink.t("取消"), null)
                .setPositiveButton(a.ink.t("保存"), (dialog, which) -> {
                    List<String> picked = new ArrayList<>();
                    for (int i = 0; i < boxes.size(); i++) if (boxes.get(i).isChecked()) picked.add(ids.get(i));
                    if (picked.isEmpty()) {
                        a.dismissNotice();
                        a.message = "至少要留一个，否则长按图标不会弹出菜单";
                        a.messageFailed = true;
                        a.render();
                        return;
                    }
                    boolean ok = UiShortcuts.save(a, picked);
                    a.dismissNotice();
                    a.message = ok
                            ? "已保存 " + picked.size() + " 个快捷方式，长按桌面图标就能看到"
                            : "桌面拒绝了这次更新（可能刚改过太多次），过一会儿再保存一次";
                    a.messageFailed = !ok;
                    a.render();
                    if (ok) a.scheduleNoticeDismiss();
                }).show();
    }

    // ───────────────────────── 备份系统设置 ─────────────────────────
}
