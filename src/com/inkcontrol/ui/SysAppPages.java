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

/** 「系统应用卸载/还原」页。从 MainActivity 拆出，状态与通用控件仍通过宿主 {@code a} 访问。 */
final class SysAppPages {
    private final MainActivity a;
    SysAppPages(MainActivity host) { a = host; }

    void sysAppPage() {
        String key = a.pageKey();
        if (!key.equals(sysAppPageKey)) {
            // 每次重新进入这一页都重读一遍，避免看到离开前的旧状态
            sysAppPageKey = key;
            sysAppStates.clear();
            a.body.postDelayed(() -> { if (!a.busy) a.perform("读取卸载状态", this::readSysAppStates, false); }, 60);
        }
        // 页头已经有分类名与 intro，这里只补一句「卸载到底做了什么」，不再重复标题
        a.body.addView(a.card(a.column(a.paragraph("「卸载」只从机主账号移除，系统文件保留，点「还原」即可装回。", 14))));
        LinearLayout list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setBackground(a.ink.outline(12));
        for (int i = 0; i < SYS_APPS.length; i++) {
            if (i > 0) list.addView(a.ink.rule(1));
            list.addView(sysAppRow(SYS_APPS[i][0], SYS_APPS[i][1]));
        }
        a.body.addView(a.spaced(list, 18));
        a.body.addView(a.spaced(a.paragraph("常驻组件不在此列：冻结压不住，卸载会让系统软重启。", 13), 10));
        a.body.addView(a.spaced(a.ink.button("重新读取状态", Ink.Kind.SECONDARY,
                v -> a.perform("读取卸载状态", this::readSysAppStates, false)), 14));
    }

    View sysAppRow(String pkg, String name) {
        String state = sysAppStates.get(pkg);
        boolean installed = "installed".equals(state);
        LinearLayout row = new LinearLayout(a);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(a.ink.dp(16), a.ink.dp(14), a.ink.dp(12), a.ink.dp(14));
        LinearLayout content = new LinearLayout(a);
        content.setOrientation(LinearLayout.VERTICAL);
        content.addView(a.ink.text(name, 17, Ink.MEDIUM));
        content.addView(a.spaced(a.paragraph(pkg, 12), 4));
        content.addView(a.spaced(a.ink.text("状态：" + sysAppStateLabel(state), 13, installed ? Ink.BOLD : Ink.REGULAR), 5));
        row.addView(content, new LinearLayout.LayoutParams(0, -2, 1));
        TextView action = installed
                ? a.ink.button("卸载", Ink.Kind.DANGER, v -> a.confirm("卸载「" + name + "」",
                        "只从机主账号里移除，系统分区里的原始文件不动；以后在任何时候点「还原」都能原样装回。", () ->
                                a.perform("卸载「" + name + "」", () -> sysAppChange(pkg, name, true), false)))
                : a.ink.button("还原", Ink.Kind.SECONDARY, v ->
                        a.perform("还原「" + name + "」", () -> sysAppChange(pkg, name, false), false));
        row.addView(action);
        return row;
    }

    /** 执行卸载或还原，并把新状态记下来；返回值直接作为顶部提示条。 */
    String sysAppChange(String pkg, String name, boolean remove) throws Exception {
        if (remove) a.services.sysAppRemove(pkg); else a.services.sysAppRestore(pkg);
        String state = a.services.sysAppState(pkg)[0];
        sysAppStates.put(pkg, state);
        return name + "：" + sysAppStateLabel(state);
    }

    /** 一次读完所有预置应用的状态，返回给提示条；perform 成功后会自动重画页面。 */
    String readSysAppStates() throws Exception {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < SYS_APPS.length; i++) {
            String state;
            try { state = a.services.sysAppState(SYS_APPS[i][0])[0]; } catch (Exception error) { state = ""; }
            if (!state.isEmpty()) sysAppStates.put(SYS_APPS[i][0], state);
            if (i > 0) out.append('\n');
            out.append(SYS_APPS[i][1]).append('：').append(sysAppStateLabel(state));
        }
        return out.toString();
    }

    static String sysAppStateLabel(String state) {
        if ("installed".equals(state)) return "已安装";
        if ("removed".equals(state)) return "已卸载";
        if ("absent".equals(state)) return "设备上没有这个应用";
        return "未读取";
    }
}
