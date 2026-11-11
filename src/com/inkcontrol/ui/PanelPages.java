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

/** 「快捷面板按钮」页：按钮顺序、动作与应用选择。从 MainActivity 拆出，状态与通用控件仍通过宿主 {@code a} 访问。 */
final class PanelPages {
    private final MainActivity a;
    PanelPages(MainActivity host) { a = host; }

    /**
     * 「快捷面板修改」页：总开关 + 按钮顺序 + 两个小开关。
     *
     * <p>总开关按「翻页动画总开关」那套单行紧凑样式画——标题、状态、开关挤在同一行，
     * 外面只套一圈描边。以前这里是带 18dp 内边距的卡片，只有「已生效」没有名字，
     * 既占高度又看不出开的是什么。
     */
    void quickPanelPage() {
        LinearLayout master = new LinearLayout(a);
        master.setOrientation(LinearLayout.VERTICAL);
        master.setBackground(a.ink.outline(12));
        master.addView(a.compactToggleRow("qs.panel"));
        // 作用域没注入时这页照样能看：状态会停在「等待…确认」，补一条前往诊断的入口。
        String status = a.status("qs.panel");
        if (a.snapshot.hooked.isEmpty() && (status.equals("等待运行确认") || status.equals("等待停用确认"))) {
            master.addView(a.ink.rule(1));
            LinearLayout hint = a.column(a.spaced(a.paragraph(MainActivity.NOT_INJECTED, 13), 10),
                    a.spaced(a.ink.button("前往「诊断」", Ink.Kind.SECONDARY, v -> a.switchTab(MainActivity.DIAGNOSE)), 10));
            hint.setPadding(a.ink.dp(14), a.ink.dp(10), a.ink.dp(14), a.ink.dp(12));
            master.addView(hint);
        }
        a.body.addView(a.spaced(master, 10));
        panelEditor();
    }

    /**
     * 「快捷面板按钮」页里的一块编辑：五个位置各是什么动作（位置顺序就是按钮顺序），
     * 下面挂「点完收起面板」与「截屏便签对齐尺寸」两个开关。挪位置靠拖动把手重排，
     * 写回时整排动作整体搬一次，不另存一份顺序。
     */
    void panelEditor() {
        com.inkcontrol.core.Configuration config = a.snapshot.config;
        // 刚拖完、root 应用还没跑完时先按「挂起的新顺序」画，省得等那一两秒。
        String[] order = a.panelOrderPending != null ? a.panelOrderPending : new String[PANEL_SLOTS.length];
        if (a.panelOrderPending == null) for (int i = 0; i < order.length; i++) order[i] = config.value(PANEL_SLOTS[i]);
        LinearLayout panel = a.column(a.heading("按钮顺序", 18),
                a.spaced(a.paragraph("从左到右排。按住左侧的三条横线上下拖即可换位置，点「更换」换动作。", 13), 6));
        // 每个位置单独装一层：拖动落点是按容器子视图的中线算的，标题与按钮不能混在同一个容器里。
        LinearLayout items = DragSort.container(a);
        for (int i = 0; i < PANEL_SLOTS.length; i++) {
            String id = PANEL_SLOTS[i];
            // 每格只占容器的一格：分隔线画在包装里，不额外插视图（拖动落点按子视图中线算）。
            LinearLayout item = new LinearLayout(a);
            item.setOrientation(LinearLayout.VERTICAL);
            if (i > 0) item.addView(a.ink.rule(1));
            LinearLayout row = new LinearLayout(a);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(a.ink.dp(4), a.ink.dp(4), a.ink.dp(10), a.ink.dp(4));
            View handle = DragSort.handle(a, a.ink);
            row.addView(handle);
            row.addView(a.ink.text((i + 1) + " · " + slotName(order[i]), 16, Ink.MEDIUM),
                    new LinearLayout.LayoutParams(0, -2, 1));
            row.addView(a.ink.chip("更换", false, v -> panelActionPicker(id)));
            item.addView(row);
            DragSort.attach(handle, item, (from, to) -> orderSlots(from, to));
            items.addView(a.spaced(item, 2));
        }
        panel.addView(items);
        a.body.addView(a.spaced(panel, 20));

        // 两个开关放在按钮顺序下面：点行不跳页，只有开关本身能改；用和分类页一样的紧凑行。
        LinearLayout switches = new LinearLayout(a);
        switches.setOrientation(LinearLayout.VERTICAL);
        switches.setBackground(a.ink.outline(12));
        switches.addView(a.smallToggleRow("qs.panel.close"));
        switches.addView(a.ink.rule(1));
        switches.addView(a.smallToggleRow("qs.panel.pinsize"));
        a.body.addView(a.spaced(switches, 12));

        // 恢复默认是次要操作，压在页面底部：左边小一号按钮，右边直接跟出厂顺序本身
        // （不再写「默认：」前缀，也不再占一行，省得整块贴着底部导航）。
        LinearLayout reset = new LinearLayout(a);
        reset.setOrientation(LinearLayout.HORIZONTAL);
        reset.setGravity(Gravity.CENTER_VERTICAL);
        reset.addView(smallButton("恢复默认顺序", v -> resetSlots()));
        LinearLayout.LayoutParams note = new LinearLayout.LayoutParams(0, -2, 1);
        note.leftMargin = a.ink.dp(12);
        reset.addView(a.ink.text("WiFi · 蓝牙 · 自动刷新 · 翻页动画 · 截屏便签", 11, Ink.REGULAR), note);
        a.body.addView(a.spaced(reset, 18));
    }

    /** 比主按钮小一号：14sp、最小高度 40dp、宽度随文字，用在页内的次要操作上。 */
    TextView smallButton(String label, View.OnClickListener click) {
        TextView view = a.ink.text(label, 14, Ink.MEDIUM);
        view.setGravity(Gravity.CENTER);
        view.setMinHeight(a.ink.dp(40));
        view.setPadding(a.ink.dp(16), a.ink.dp(8), a.ink.dp(16), a.ink.dp(8));
        view.setBackground(a.ink.pressable(a.ink.box(Ink.PAPER, Ink.INK, 1.5f, 10), a.ink.solid(10)));
        view.setTextColor(a.ink.pressedColors(Ink.INK, Ink.PAPER));
        view.setClickable(true);
        view.setFocusable(true);
        // 显式给 wrap_content：外层 spaced() 在拿不到 LayoutParams 时会按整行宽铺开。
        view.setLayoutParams(new LinearLayout.LayoutParams(-2, -2));
        view.setOnClickListener(click);
        return view;
    }

    /** 某一格现在是什么：普通动作取选项名；存的是包名时显示应用名。 */
    String slotName(String value) {
        FeatureDefinition slot = FeatureCatalog.get(PANEL_SLOTS[0]);
        if (slot.values.contains(value)) return Guide.optionName(slot, value);
        return value == null || value.isEmpty() ? "无" : appLabel(value);
    }

    String appLabel(String pkg) {
        try {
            android.content.pm.PackageManager manager = a.getPackageManager();
            return manager.getApplicationLabel(manager.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception error) { return pkg; }
    }

    /**
     * 换某个位置的动作：最上面是「无」（这个位置不显示），中间是动作表，最下面是 APP。
     * 动作表见 {@code FeatureCatalog.PANEL_ACTIONS}。
     */
    void panelActionPicker(String id) {
        String current = a.snapshot.config.value(id);
        final List<String> values = new ArrayList<>();
        final List<String> names = new ArrayList<>();
        values.add(FeatureCatalog.PANEL_NONE); names.add("无（这个位置不显示）");
        for (String[] action : FeatureCatalog.PANEL_ACTIONS) { values.add(action[0]); names.add(action[1]); }
        values.add(FeatureDefinition.PACKAGE_TOKEN);
        String app = FeatureCatalog.get(id).values.contains(current) ? "APP…" : "APP…（当前：" + appLabel(current) + "）";
        names.add(app);
        int checked = values.indexOf(current);
        new AlertDialog.Builder(a).setTitle(a.ink.t("这个按钮做什么"))
                .setSingleChoiceItems(names.toArray(new String[0]), checked, (dialog, which) -> {
                    dialog.dismiss();
                    // 最后一项不是取值，而是「去挑一个应用」
                    if (which == values.size() - 1) { slotAppPicker(id); return; }
                    saveSlot(id, values.get(which));
                })
                .setNegativeButton(a.ink.t("取消"), null).show();
    }

    void saveSlot(String id, String value) {
        FeatureDefinition slot = FeatureCatalog.get(id);
        String name = slot.values.contains(value) ? Guide.optionName(slot, value) : appLabel(value);
        a.perform("更换按钮动作", () -> {
            a.saveValue(slot, value);
            return "已保存「" + slot.title + "」：" + name;
        }, false);
    }

    /**
     * 挑一个应用当这一格的按钮。带搜索框，应用名和包名都能搜——两个应用的显示名可能一样，
     * 包名才是唯一的，所以每一行都把包名写出来。
     */
    void slotAppPicker(String slotId) {
        LinkedHashMap<String, String> apps = a.launcherApps();
        // 这个按钮最常见的用法之一就是打开墨控，而 launcherApps() 排除了自己，这里补回去。
        LinkedHashMap<String, String> all = new LinkedHashMap<>();
        all.put(a.getPackageName(), a.ink.t("墨控"));
        all.putAll(apps);
        final List<String> packages = new ArrayList<>(all.keySet());
        final List<LinearLayout> rows = new ArrayList<>();
        LinearLayout list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        for (String pkg : packages) {
            LinearLayout row = a.column(a.ink.text(all.get(pkg), 15, Ink.MEDIUM), a.spaced(a.paragraph(pkg, 12), 2));
            row.setPadding(a.ink.dp(10), a.ink.dp(9), a.ink.dp(10), a.ink.dp(9));
            row.setBackground(a.ink.pressable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT),
                    a.ink.box(Ink.PAPER, Ink.INK, 2, 10)));
            list.addView(row);
            rows.add(row);
        }
        ScrollView scroller = new ScrollView(a);
        scroller.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroller.addView(list);
        EditText search = new EditText(a);
        search.setHint(a.ink.t("搜索应用名或包名"));
        search.setSingleLine(true);
        search.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) { }
            @Override public void afterTextChanged(android.text.Editable text) {
                String query = text.toString().trim().toLowerCase(Locale.ROOT);
                for (int i = 0; i < rows.size(); i++) {
                    boolean match = query.isEmpty()
                            || all.get(packages.get(i)).toLowerCase(Locale.ROOT).contains(query)
                            || packages.get(i).toLowerCase(Locale.ROOT).contains(query);
                    rows.get(i).setVisibility(match ? View.VISIBLE : View.GONE);
                }
            }
        });
        LinearLayout content = new LinearLayout(a);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(a.ink.dp(16), a.ink.dp(4), a.ink.dp(16), a.ink.dp(4));
        content.addView(search);
        content.addView(scroller, new LinearLayout.LayoutParams(-1, a.ink.dp(360)));
        AlertDialog dialog = new AlertDialog.Builder(a).setTitle(a.ink.t("点它打开哪个应用"))
                .setView(content).setNegativeButton(a.ink.t("取消"), null).create();
        for (int i = 0; i < rows.size(); i++) {
            final String pkg = packages.get(i);
            rows.get(i).setOnClickListener(v -> { dialog.dismiss(); saveSlot(slotId, pkg); });
        }
        dialog.show();
    }

    /**
     * 拖动换位：把第 {@code from} 格的动作用搬到第 {@code to} 位。
     *
     * <p>先按新顺序重画一版再后台落盘 + 应用，中间不弹「处理中」——这些动作只有 SystemUI 读，
     * 但顺序要写进配置，所以仍要跑一次应用；一次应用一两秒，拖完立刻回弹原位会很难用。
     */
    void orderSlots(int from, int to) {
        final int count = PANEL_SLOTS.length;
        if (a.panelOrderBusy || from < 0 || from >= count || to < 0 || to >= count || from == to) return;
        final String[] before = new String[count];
        final List<String> order = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            before[i] = a.snapshot.config.value(PANEL_SLOTS[i]);
            order.add(before[i]);
        }
        order.add(to, order.remove(from));
        a.panelOrderPending = order.toArray(new String[0]);
        a.panelOrderBusy = true;
        a.render();
        MainActivity.WORKER.execute(() -> {
            String error = "";
            try {
                java.util.LinkedHashMap<String, String> values = new java.util.LinkedHashMap<>();
                for (int i = 0; i < count; i++)
                    if (!order.get(i).equals(before[i])) values.put(PANEL_SLOTS[i], order.get(i));
                a.services.changeAll(values);
            } catch (Exception failure) { error = MainActivity.reason(failure); }
            ApplicationServices.Snapshot next = null;
            try { next = a.services.snapshot(); } catch (Exception failure) { if (error.isEmpty()) error = MainActivity.reason(failure); }
            String problem = error;
            ApplicationServices.Snapshot loaded = next;
            a.runOnUiThread(() -> {
                a.panelOrderBusy = false; a.panelOrderPending = null;
                if (a.isFinishing() || a.isDestroyed()) return;
                if (loaded != null) a.snapshot = loaded;
                a.render();
                if (!problem.isEmpty()) a.showText("按钮顺序未保存", problem);
            });
        });
    }

    void resetSlots() {
        final String[] defaults = {"wifi", "bt", "refresh", "pageanim", "pin"};
        a.perform("恢复默认顺序", () -> {
            for (int i = 0; i < PANEL_SLOTS.length; i++) a.saveValue(FeatureCatalog.get(PANEL_SLOTS[i]), defaults[i]);
            return "已恢复默认顺序";
        }, false);
    }
}
