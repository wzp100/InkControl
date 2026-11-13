package com.inkcontrol.features.lock;

import com.inkcontrol.core.FeatureDefinition;
import com.inkcontrol.core.LunarCalendar;
import com.inkcontrol.platform.RuntimeConfig;
import com.inkcontrol.shared.WeatherIcon;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 「编辑排版-横」与「横-逆」样式。从 LockCanvas 拆出，画布、画笔与排版基础方法仍通过宿主 {@code a} 访问。 */
final class LockEditorialLand {
    private final LockCanvas a;
    LockEditorialLand(LockCanvas host) { a = host; }

    /**
     * 编辑排版的横版：按 720×360 横版逻辑画布作画，paint() 里整体旋转 90° 铺满竖屏面板。
     * 构图（2026-10-29 重排）：竖排「上滑解锁」列——正版落在画布右端（手机横转后即屏幕最右），
     * 逆版落在画布左端（整幅旋转 180° 后同样在面板最右），箭头随样式翻转、整体居 Y 中心 180；
     * 表头「第N日 | 中文日期·星期 | 农历 | 农历日期」+ 上划线自 x=42 起；
     * 大号横版时间 + 细方框（方框宽贴合时间实际宽度）同列 x=40；时间右侧倒数日框（横排标签在框子上方，
     * 事项+日期最多 3 组，框高与时间方框一致）；格言（中英加粗）与通知框整体下沉至下划线下方、
     * 与电量同水平带；电量在右下、天气位在电量正下方（仅原厂控件有真实天气数据时绘制，
     * 左沿与电量对齐，无数据留空不显示占位）。
     * 天气仅在有真实数据时绘制；倒数日只占位不取数：无数据显示的组不绘制。
     */
    void editorialLand() {
        final boolean rev = a.reverseLand();
        // 竖排解锁列占 16 宽 + 左右各 12 边距。正版在画布右端（692-708），内容右界 R_END 退到 668 让位；
        // 逆版在画布左端（12-28），内容右界仍是 700——两版共用同一份坐标，只让「内容右界」跟着版本走。
        final float R_END = rev ? 700 : 668;
        final float LX = 42;                      // 左栏左沿（样张：表头/方框/格言均锚定此列）
        final float QW = 375;                     // 格言栏宽（x 42-417；横版下方不够高，右侧带整块左移让给电量/天气）
        final float BX = R_END - 155;              // 通知/状态框左沿（贴着电量带右侧，右缘与倒数日框对齐）

        // ── 顶部：合并表头（第N日 | 中文日期·星期 | 农历 | 农历日期；y 参考样张 ≈60）──
        // 「|」小间隔分隔；农历日期缺失时只保留「第 N 日 | 中文日期·星期」。
        String head = "第" + a.data.now.get(Calendar.DAY_OF_YEAR) + "日 | " + a.chineseDate() + "·" + a.weekCn();
        if (a.almanac() != null) head += " | 农历 | " + a.almanac();
        a.font(LockCanvas.sSerif, 26, 0.04f, LockCanvas.INK, true);
        float hw = a.textBase(head, LX, 60, R_END - LX - 18);
        a.rect(LX, 70, hw, 1, LockCanvas.INK);

        // ── 底部中：电量 + 天气（样张移至右下方，与格言同一水平带）──
        // 横版只有 360 高，竖版的三列网格放不下：这一带压成一个电量块（小标题 + 数字两行）+ 天气一行。
        // 左沿贴住格言栏右端（417 后留 3px）；电量行右界 BATT_R2 要给通知框（左沿 BX）让位，
        // 天气行在通知框下方，可以一直用到倒数日框右缘 WEA_R2，宽度足够写下长天气名。
        final float BATT_X = 420f;                 // 电量/天气共用左沿（格言栏右端 417 后留 3px）
        final float BATT_R2 = BX - 5f;             // 电量行右界（通知框左沿前留 5px）
        final float WEA_R2 = R_END;                // 天气行右界（在通知框下方，占满到倒数日框右缘）
        // 一行「电量 66%」放不下：这一格只有 BATT_R2-BATT_X = 88 宽（左界 420 到通知框 513 前），
        // 24sp 的「电量」被 textScaleX 压到约五成宽，字又扁又小。改成两行——小标题在上、大数字在下，
        // 层级与竖版 metricCell 一致（小标题 + 大数字）；两行都按自然宽度画，不再横压。
        // 竖排位置：标题基线 290、数字基线 322，整块（275-322）与右侧通知/设备状态框（272-320）对齐；
        // 数字墨迹底 322 与天气行（基线 348）字身顶 333 留 11，不会顶在一起。
        // 标题 22sp、数字 30sp：同 30 时标题抢戏（看着跟数据一样重），22 是竖版 metricCell 的
        // 14/22 与数据 30 之间的折中——够大能一眼读到，又明显低于数字。
        // 标题按宽度等比降字号而不做横向压缩，避免再次出现「扁字」。
        final String label = a.data.charging ? "充电中" : "电量";
        final String val = a.data.battery < 0 ? "—" : a.data.battery + "%";
        final float bw = BATT_R2 - BATT_X;
        final float lSize = 22;
        a.font(LockCanvas.sSerif, lSize, 0.04f, LockCanvas.INK, true);
        float lw = a.tp.measureText(label);
        if (lw > bw) a.font(LockCanvas.sSerif, lSize * bw / lw, 0.04f, LockCanvas.INK, true);
        a.textBase(label, BATT_X, 290, 0);
        a.font(LockCanvas.sSerifBold, 30, -0.02f, LockCanvas.INK, true);
        a.textBase(val, BATT_X, 322, bw);

        // ── 电量正下方：天气行（仅原厂控件有真实数据时绘制；无数据整行不留空）──
        // 竖版把「天气 / 气温 / 电量」拆成三列，横版没有纵向空间，压成一行：
        // 「天气 阴 26°/18°」——只留「天气」一个标签，天气与高低温同字号顺排，
        // 不再单列「气温」（两个标签太占横向空间，数据本身已经带度数符号）。
        // 基线 348，descender 到 353，距画布底沿还有 7px（旧版排到 y=392，整块被裁掉）。
        if (a.hasWeather()) {
            String temp = a.data.high != null ? a.data.high + "/" + a.data.low : a.data.temperature;
            String value = LockCanvas.dash(a.data.weather);
            if (!TextUtils.isEmpty(temp)) value += " " + temp;
            a.pair("天气", value, BATT_X, 348, WEA_R2);
        }

        // ── 左下：中英格言（整体上移，给英文译文留出下半屏空间；中文加粗，英文同电量数据加黑加粗）──
        // 画布只有 360 高：原来从 282 起，中文 2 行占 48、空 6、英文 2 行占 28，收尾落在 364
        // 被底沿裁掉。上提 20 到 262（时间方框底 245，还留 17），英文整段就能落在 344 以内。
        float my = 262;
        if (a.data.text != null) {
            a.font(LockCanvas.sSerifBold, 16, 0, LockCanvas.INK, true);
            my += a.paragraph(a.data.text, LX, my, QW, 1.5f, 2) + 6;
        }
        if (a.data.track != null) {
            a.font(LockCanvas.sSerifBold, 12, 0.04f, LockCanvas.INK, true);
            a.text("— " + a.data.track, LX, my, QW);
        } else if (a.data.subText != null) {
            a.font(LockCanvas.sSerifBold, 10.5f, 0, LockCanvas.INK, true);
            a.paragraph(a.data.subText, LX, my, QW, 1.35f, 2);
        }

        // ── 通知框 / 设备状态框（白底细框黑字；左移贴着电量带，右缘与倒数日框对齐）──
        // 原来框在右下角 x605、只有 95 宽，通知挤在边缘且离电量一整条空白。左移并加宽到 155 后：
        // 通知能居中留白，设备状态也能靠 extBar 的 2×2 降级塞下四项。右缘跟随内容右界 R_END
        // （正版 668、逆版 700），正版要给它右边的竖排解锁列让出 24。
        // 高度 40 → 48、框顶 280 → 272（框底 320，仍压在天气行字身顶 323 之上）。
        final float BH = 48;
        final float BW = 155;                          // 外框宽（左沿 BX，右缘 R_END）
        final float boxY = 272;                        // 框顶（水平带上移，与电量/格言带居中对齐）
        if (a.data.extMode) {
            // 「显示设备状态」：同一块位置改画四项开关状态（与竖版同一套图标与文字）
            a.extBarView.extBar(BX, boxY, BW, BH);
        } else if (!a.data.hideNotify) {
            final float bBase = boxY + BH / 2f + 5f;   // 框内文字基线（垂直居中）
            if (a.data.notifications > 0) {
                a.frame(BX, boxY, BW, BH, 1);
                String rest = "　" + a.data.notifications + " 条";
                a.font(LockCanvas.sSerifBold, 15, 0, LockCanvas.INK, true);
                float w = a.tp.measureText(a.data.notificationLabel);
                a.font(LockCanvas.sSerif, 15, 0, LockCanvas.INK, true);
                float start = BX + (BW - (w + a.tp.measureText(rest))) / 2f;
                a.font(LockCanvas.sSerifBold, 15, 0, LockCanvas.INK, true);
                a.textBase(a.data.notificationLabel, start, bBase, 0);
                a.font(LockCanvas.sSerif, 15, 0, LockCanvas.INK, true);
                a.textBase(rest, start + w, bBase, 0);
            } else if (a.data.notifications == 0) {
                a.frame(BX, boxY, BW, BH, 1);
                a.font(LockCanvas.sSerif, 15, 0, LockCanvas.INK, true);
                a.textCenterBase("暂无新消息", BX + BW / 2f, bBase, BW - 24);
            }
        }

        // ── 中右部：大号时间（三次样张：整体下沉，不贴上划线）──
        // 样张构图：时间居中偏右（x 185-565）、基线 y=250；字号 220 时字形带 y≈95-250，
        // 与表头上划线（y=70）保持约 25px 间距，不顶线；右缘 580 起是倒数日框、694 留给解锁提示。
        // ── 中左部：大号时间 + 细方框（方框宽度贴合时间实际宽度，消除右侧多余空白）──
        // 字号按框高取最大（cap≈框高-2*pad）让时间尽量大；框宽 = 时间实际绘制宽 + 2*pad，
        // 紧贴时间本身；仅当时间过宽超过最大框宽（T_MAXW）时才等比缩字号兜底封顶。
        // 逐字符绘制：HH:mm 是「两两成对」结构，对内（HH 之间、MM 之间）加宽 pairGap，
        // 避免 44/11 等相同数字相邻时字形相近显得重合；冒号两侧留 colonGap。
        final float T_FX = 40;                       // 方框左沿（右移 30，给左缘解锁提示列让位）
        final float T_FY = 90, T_FH = 155;           // 方框 y 90-245（加高，吃掉时间下方空当）
        final float T_PAD = 10;                       // 框内边距
        final float T_MAXW = R_END - 75 - 20 - T_FX;  // 方框最大宽（右缘给倒数日框留 20px）
        final float pairGap = 16f;                    // 对内间隔（HH 两数字间、MM 两数字间）
        final float colonGap = 10f;                   // 冒号两侧间隔
        String t = a.time();                            // "HH:mm"（12 小时制同为两位）
        float tSize = (T_FH - T_PAD * 2) / 0.7f;      // 初取字号：cap 填满框高
        a.font(LockCanvas.sSerif, tSize, 0f, LockCanvas.INK, true);
        float total = pairGap * 2 + colonGap * 2;
        for (int i = 0; i < t.length(); i++) total += a.tp.measureText(t, i, i + 1);
        if (total + T_PAD * 2 > T_MAXW) {             // 过宽则缩字号，框宽封顶 T_MAXW
            tSize *= (T_MAXW - T_PAD * 2) / total;
            a.font(LockCanvas.sSerif, tSize, 0f, LockCanvas.INK, true);
            total = pairGap * 2 + colonGap * 2;
            for (int i = 0; i < t.length(); i++) total += a.tp.measureText(t, i, i + 1);
        }
        float T_BASE = T_FY + (T_FH + tSize * 0.7f) / 2f;   // 数字基线（框内垂直居中）
        final float T_FW = Math.min(total + T_PAD * 2, T_MAXW);
        a.frame(T_FX, T_FY, T_FW, T_FH, 1);             // 细方框（先画框，数字后画在上层）
        float tx = T_FX + T_PAD;
        for (int i = 0; i < t.length(); i++) {
            String ch = String.valueOf(t.charAt(i));
            a.c.drawText(ch, tx, T_BASE, a.tp);
            tx += a.tp.measureText(ch);
            if (i == 0 || i == 3) tx += pairGap;    // 对内间隔（第 1、4 字符之后）
            else if (i == 1 || i == 2) tx += colonGap;  // 冒号两侧
        }

        // ── 时间右侧：倒数日框（事项+日期，最多 3 组；框收窄）──
        // 横排「倒数日」标签置于框子上方（居中于框 X 中心）；框内每组两行（事项名 / 剩 N 日），
        // 事项为空只画天数行；整体按框 Y 轴中心居中：1 组居中，2/3 组以固定组间距排布后整体居中。
        // 「显示」开关关闭的组不绘制；农历组的目标日按所选农历年/月/日换算公历后再算天数。
        List<FeatureDefinition.CountdownItem> shown = a.shownCountdowns();
        if (!shown.isEmpty()) {
            final float FW = 75, FX = R_END - FW;      // 框靠内容右界（与时间方框右缘错开 20）
            final float FY = 90, FH = 155;            // 框 y 90-245（拉高至与时间方框同高）
            a.frame(FX, FY, FW, FH, 1);
            float cx = FX + FW / 2f;
            a.font(LockCanvas.sSerifBold, 15, 0, LockCanvas.INK, true);
            a.textCenter("倒数日", cx, 73);              // 横排标签在框子上方
            int n = Math.min(shown.size(), FeatureDefinition.COUNTDOWNS_MAX);
            final float lineH = 17, gapIn = 3;        // 组内行高、行距
            final float pairH = lineH * 2 + gapIn;    // 一组（两行）的高度
            final float gapGroup = n >= 3 ? 6 : 16;  // 组间距（框高 125：3 组时需收紧）
            float top = FY + (FH - (n * pairH + (n - 1) * gapGroup)) / 2f;   // 整体垂直居中
            for (int i = 0; i < n; i++) {
                FeatureDefinition.CountdownItem entry = shown.get(i);
                String item = entry.item;
                long days = a.daysUntil(entry);
                String left = (days >= 0 ? "剩 " : "过 ") + Math.abs(days) + " 日";
                if (!item.isEmpty()) {
                    a.font(LockCanvas.sSerifBold, 15, 0, LockCanvas.INK, true);
                    a.textCenterFit(item, cx, top, FW - 10);
                    a.font(LockCanvas.sSerif, 13, 0, LockCanvas.INK, true);
                    a.textCenterFit(left, cx, top + lineH + gapIn, FW - 10);
                } else {
                    a.font(LockCanvas.sSerif, 13, 0, LockCanvas.INK, true);
                    a.textCenterFit(left, cx, top + (lineH - 13) / 2f + 2, FW - 10);
                }
                top += pairH + gapGroup;
            }
        }

        // ── 竖排「上滑解锁」提示：正版在画布右端，逆版在画布左端 ──
        // 正版横版画布旋转 90° 铺到竖屏面板：画布 x 小 → 面板上沿，x 大 → 面板下沿，
        // 手机横转阅读时「面板下沿」就是屏幕最右边，所以解锁提示要落在画布右端；
        // 逆版整幅相对正版再转 180°，画布 x 小反而落到面板下沿（横转后仍是最右），故留在左端。
        // 箭头方向随样式翻转：正版 ←，逆版 →，面板上始终呈 ↑（真实解锁手势方向）。
        // 文字保持正立、自上而下：整块再旋转 180° 虽能让面板画面与正版完全一致，
        // 但横转阅读时文字会上下颠倒，反而不可读，故只翻转箭头。
        // 实测墨迹带 y≈133-227，中心 180（箭头字形在字身框内居中，故比字顶参数低约 6px）。
        a.font(LockCanvas.sSerifBold, 16, 0, LockCanvas.INK, true);
        String arrow = rev ? "\u2192" : "\u2190";
        float uw = Math.max(a.tp.measureText(arrow), a.tp.measureText("上"));
        final float VX = rev ? 12 : 720 - 12 - uw;
        a.text(arrow, VX, 123f, 0f);       // 上表针
        textVertical("上滑解锁", VX, 141, 18);
        a.text(arrow, VX, 213f, 0f);       // 下表针
    }

    /** 竖排文字：逐字自上而下绘制。top 为字身顶端（与 text() 一致，避免与箭头混用时间距不均）。 */
    private void textVertical(String s, float x, float top, float step) {
        for (int i = 0; i < s.length(); i++) {
            a.c.drawText(s, i, i + 1, x, top + i * step - a.tp.ascent(), a.tp);
        }
    }

    // ───────────────────────── B · 瑞士网格 ─────────────────────────
}
