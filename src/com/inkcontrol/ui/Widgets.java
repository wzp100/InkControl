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

/** 通用小控件：标题、段落、卡片、间距、勾选框等。从 MainActivity 拆出，状态与通用控件仍通过宿主 {@code a} 访问。 */
final class Widgets {
    private final MainActivity a;
    Widgets(MainActivity host) { a = host; }

    View navCard(String title, String text, String meta, boolean strong, String description, View.OnClickListener click) {
        return navCard(title, text, meta, strong, description, click, true);
    }

    /**
     * {@code framed} 为假时这张卡片自己不画外框：它被放进带 {@code outline(12)} 描边的分组列表里，
     * 再叠一层圆角 14 的框会在四角错开、直边叠成 4px，看着像「方框里套了个扁圆框」。
     * 采用与普通功能行同一套处理：常态透明露出分组描边，按下时加粗框（圆角 12 与外框一致）。
     */
    View navCard(String title, String text, String meta, boolean strong, String description,
                         View.OnClickListener click, boolean framed) {
        LinearLayout card = new LinearLayout(a);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(a.ink.dp(18), a.ink.dp(16), a.ink.dp(14), a.ink.dp(16));
        card.setBackground(framed
                ? a.ink.pressable(a.ink.outline(14), a.ink.box(Ink.PAPER, Ink.INK, 3, 14))
                : a.ink.pressable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT),
                        a.ink.box(Ink.PAPER, Ink.INK, 2, 12)));
        LinearLayout content = column(a.ink.text(title, 20, Ink.BOLD), spaced(paragraph(text, 13), 6));
        if (!meta.isEmpty()) content.addView(spaced(a.ink.text(meta, 13, strong ? Ink.BOLD : Ink.REGULAR), 8));
        card.addView(content, new LinearLayout.LayoutParams(0, -2, 1));
        card.addView(withMargin(a.ink.text("›", 28, Ink.REGULAR), 12));
        card.setContentDescription(a.ink.t(description));
        card.setOnClickListener(click);
        return card;
    }

    /** 自绘方框：系统 CheckBox 在灰度墨水屏上靠不住，实心就代表已勾选。 */
    View checkBox(boolean checked) {
        View box = new View(a);
        box.setBackground(checked ? a.ink.box(Ink.INK, Ink.INK, 2, 3) : a.ink.box(Ink.PAPER, Ink.INK, 2, 3));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(a.ink.dp(18), a.ink.dp(18));
        params.setMargins(0, 0, a.ink.dp(14), 0);
        box.setLayoutParams(params);
        return box;
    }

    /** 选中项上下的那条粗线：2dp 实线，颜色与正文一致。 */
    View optionMark() {
        View line = new View(a);
        line.setBackgroundColor(Ink.INK);
        line.setLayoutParams(new LinearLayout.LayoutParams(-1, a.ink.dp(2)));
        return line;
    }

    View section(String title, View content) {
        return column(a.ink.text(title, 18, Ink.BOLD), spaced(content, 8));
    }

    View framed(String title, View content, String caption) { return framed(title, content, caption, null); }
    /** {@code action} 非空时挂在标题行右侧，省得为了一个按钮再滚一屏。 */
    View framed(String title, View content, String caption, View action) {
        LinearLayout head = new LinearLayout(a);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(a.ink.text(title, 18, Ink.BOLD), new LinearLayout.LayoutParams(0, -2, 1));
        if (action != null) head.addView(action);
        LinearLayout box = column(head);
        box.addView(spaced(content, 12));
        if (!caption.isEmpty()) box.addView(spaced(paragraph(caption, 13), 10));
        return box;
    }
    View phone(View screen, int frameWidth) {
        FrameLayout frame = new FrameLayout(a);
        frame.setPadding(a.ink.dp(6), a.ink.dp(6), a.ink.dp(6), a.ink.dp(6));
        frame.setBackground(a.ink.box(Ink.PAPER, Ink.INK, 2.5f, 22));
        // 屏幕内容按机身内圆角裁切，避免直角露出外框。
        screen.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View view, android.graphics.Outline outline) { outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), a.ink.px(16)); }
        });
        screen.setClipToOutline(true);
        frame.addView(screen, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(frameWidth, (frameWidth - a.ink.dp(12)) * 2 + a.ink.dp(12));
        params.gravity = Gravity.CENTER_HORIZONTAL;
        frame.setLayoutParams(params);
        return frame;
    }
    /** 编辑器行内小开关：无动画黑白开关图形，状态由调用方读取。
     *  注意：不可重写 toggle() 为空——CompoundButton.performClick() 靠它切换选中态，
     *  置空后点击无反应、OnClickListener 里读到的 isChecked() 永远是旧值。 */
    CompoundButton miniToggle(boolean checked) {
        CompoundButton toggle = new CompoundButton(a) {
            @Override public CharSequence getAccessibilityClassName() { return Switch.class.getName(); }
        };
        toggle.setButtonDrawable(a.ink.switchDrawable());
        toggle.setChecked(checked);
        return toggle;
    }

    TextView heading(String value, float sp) { return a.ink.text(value, sp, Ink.BOLD); }
    TextView paragraph(String value, float sp) { return a.ink.text(value, sp, Ink.REGULAR); }
    TextView note(String value) { TextView view = paragraph(value, 15); view.setPadding(a.ink.dp(4), a.ink.dp(24), 0, 0); return view; }
    LinearLayout column(View... children) {
        LinearLayout column = new LinearLayout(a);
        column.setOrientation(LinearLayout.VERTICAL);
        for (View child : children) column.addView(child);
        return column;
    }
    LinearLayout card(View content) {
        LinearLayout card = new LinearLayout(a);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(a.ink.outline(14));
        card.setPadding(a.ink.dp(18), a.ink.dp(16), a.ink.dp(18), a.ink.dp(18));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, a.ink.dp(8), 0, 0);
        card.setLayoutParams(params);
        card.addView(content);
        return card;
    }
    /** 给视图加上外边距；保留调用方之后传入的 LayoutParams 宽高。 */
    View spaced(View view, int topDp) {
        ViewGroup.LayoutParams current = view.getLayoutParams();
        LinearLayout.LayoutParams params = current instanceof LinearLayout.LayoutParams ? (LinearLayout.LayoutParams) current
                : new LinearLayout.LayoutParams(current == null ? -1 : current.width, current == null ? -2 : current.height);
        params.topMargin = a.ink.dp(topDp);
        view.setLayoutParams(params);
        return view;
    }

    View withMargin(View view, int leftDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
        params.leftMargin = a.ink.dp(leftDp);
        view.setLayoutParams(params);
        return view;
    }
}
