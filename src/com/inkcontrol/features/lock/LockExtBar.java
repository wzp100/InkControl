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

/** 设备状态条（无线 / 蓝牙 / 声音 / 飞行）及其图标。从 LockCanvas 拆出，画布、画笔与排版基础方法仍通过宿主 {@code a} 访问。 */
final class LockExtBar {
    private final LockCanvas a;
    LockExtBar(LockCanvas host) { a = host; }

    /**
     * 「设备状态」条：左边一个类别图标（16×16 逻辑框内矢量作画，不引外部图片），
     * 右边是该类别当前状态；图标与文字按同一视觉中线对齐、文字 13sp 加粗。
     * 不画竖线（与倒数日同一套观感）。
     *
     * 画图标版还是文字版由「显示设备状态」的选项决定：auto 跟随样式（瑞士网格用文字版，
     * 其余用图标版），icon / text 强制一种。文字版画成「无线|开」——类型与状态同为 13sp，
     * 中间一条 1px 竖线，不用「无线 开」那种一大一小、看起来不像一组的排法。
     * 画哪几项由 lock.ext.wifi/.bt/.audio/.airplane 决定，关掉的项不占位、其余各项重新均分。
     *
     * 竖版（x24 w312）单行四格，每格 78 宽；横版右下那块只有 155 宽，四格各 38.75，
     * 图标 +「静音」这类两字值一定崩格，所以这里按实际测量自动降级成 2×2（上行两项、下行两项）。
     * 判定用的是「每格装得下的最宽一项」，不是框总宽——总宽够但单格挤同样会串字。
     */
    void extBar(float x, float top, float w, float h) {
        List<Integer> pick = extPicks();
        final int n = pick.size();
        if (n == 0) return;
        final boolean text = textLayout();
        a.frame(x, top, w, h, 1);
        // 单行能否成立：逐项量「图标 / 类型名 + 间隔 + 状态值」，任何一项超过格宽就换 2×2
        boolean single = true;
        for (int i = 0; i < n && single; i++)
            single = extItemWidth(pick.get(i), text) <= w / n - 4;
        if (single) {
            for (int i = 0; i < n; i++) extCell(pick.get(i), x + w * i / n, top, w / n, h, text);
        } else {
            // 降级成两列（行数按项数取整），按行优先填：四项正好上行两项、下行两项，三项是上二下一。
            // 每列先按「该列最宽的左半 + 最宽的右半」拼一行再整列居中，图标左沿与文字左沿才对得上；
            // 若各自居中，wifi 墨迹宽 18.4、喇叭 13.2，两行图标起点会错开 2-3px，看上去不齐。
            final int rows = (n + 1) / 2;
            final float cw = w / 2f, rh = h / rows, gap = extGap(text);
            for (int col = 0; col < 2; col++) {
                float lead = 0, tail = 0;
                boolean any = false;
                for (int r = 0; r < rows; r++) {
                    final int i = r * 2 + col;
                    if (i >= n) break;
                    lead = any ? Math.max(lead, extLeadWidth(pick.get(i), text)) : extLeadWidth(pick.get(i), text);
                    tail = any ? Math.max(tail, extTailWidth(pick.get(i), text)) : extTailWidth(pick.get(i), text);
                    any = true;
                }
                if (!any) continue;
                final float start = x + cw * col + (cw - (lead + gap + tail)) / 2f;
                for (int r = 0; r < rows; r++) {
                    final int i = r * 2 + col;
                    if (i >= n) break;
                    extCellAt(pick.get(i), start, lead, top + rh * r, rh, text);
                }
            }
        }
    }

    /** 要显示的四项（下标即 EXT_TYPES 顺序），按配置里的四个显示开关过滤 */
    List<Integer> extPicks() {
        List<Integer> pick = new ArrayList<>();
        if (a.data.showWifi) pick.add(0);
        if (a.data.showBt) pick.add(1);
        if (a.data.showAudio) pick.add(2);
        if (a.data.showAirplane) pick.add(3);
        return pick;
    }

    /** 该项当前状态的白话值 */
    String extValue(int index) {
        switch (index) {
            case 0: return a.data.wifi ? "开" : "关";
            case 1: return a.data.bt ? "开" : "关";
            case 2: return a.data.silent ? "静音" : "有声";
            default: return a.data.airplane ? "开" : "关";
        }
    }

    /** 图标版还是文字版：auto 跟随样式，瑞士网格是纯文字排版，固定用文字版 */
    private boolean textLayout() {
        if ("text".equals(a.data.extLayout)) return true;
        if ("icon".equals(a.data.extLayout)) return false;
        return a.style == LockStyle.SWISS;
    }

    /** 左半部分（图标墨迹 / 类型名）与右半部分（状态值）之间的间隔；文字版要给竖线留位 */
    private static float extGap(boolean text) { return text ? 11 : 4; }

    /** 左半部分的宽度：图标版的图标墨迹宽，文字版的类型名宽 */
    private float extLeadWidth(int index, boolean text) {
        if (!text) return extIconWidth(LockCanvas.EXT_TYPES[index]);
        a.font(LockCanvas.sSerifBold, 13, 0, LockCanvas.INK, true);
        return a.tp.measureText(LockCanvas.EXT_NAMES[index]);
    }

    /** 右半部分（状态值）的宽度 */
    private float extTailWidth(int index, boolean text) {
        a.font(LockCanvas.sSerifBold, 13, 0, LockCanvas.INK, true);
        return a.tp.measureText(extValue(index));
    }

    /** 图标墨迹在目标高度下的实际宽度（逻辑像素） */
    private float extIconWidth(int type) {
        float[] ink = LockCanvas.INK_BOX[type - 1];
        return (ink[2] - ink[0]) * (LockCanvas.INK_H[type - 1] / (ink[3] - ink[1]));
    }

    /** 一格「图标 + 状态」的实测宽度（逻辑像素），用于判断单行是否放得下 */
    private float extItemWidth(int index, boolean text) {
        return extLeadWidth(index, text) + extGap(text) + extTailWidth(index, text);
    }

    /**
     * 画一格（单行用）：内容在格内水平居中，图标墨迹与文字墨迹共用一条中线。
     * 图标对齐的是「文字墨迹」而不是 16×16 方框：13sp 中文墨迹约 11.5 高、视觉中线在基线上方 5。
     * 每个图标在 16 空间里墨迹的纵向范围不同（wifi 天生扁、飞机最高），逐个等比缩放后墨迹才齐。
     */
    private void extCell(int index, float x, float top, float cw, float ch, boolean text) {
        float lead = extLeadWidth(index, text);
        float total = lead + extGap(text) + extTailWidth(index, text);
        if (total > cw - 4) {                        // 极窄格子兜底：图标与类型让位，只留状态值
            a.font(LockCanvas.sSerifBold, 13, 0, LockCanvas.INK, true);
            a.textCenterBase(extValue(index), x + cw / 2f, top + ch / 2f + 4.5f, cw - 6);
            return;
        }
        extCellAt(index, x + cw / 2f - total / 2f, lead, top, ch, text);
    }

    /** 左半部分左沿落在 start、宽度固定为 lead；状态值接在 lead + gap 之后，基线在格内垂直居中 */
    private void extCellAt(int index, float start, float lead, float top, float ch, boolean text) {
        final float baseline = top + ch / 2f + 4.5f;
        final float textMid = baseline - 5f;
        final String value = extValue(index);
        if (text) {
            a.font(LockCanvas.sSerifBold, 13, 0, LockCanvas.INK, true);
            a.c.drawText(LockCanvas.EXT_NAMES[index], start, baseline, a.tp);
            // 1px 竖线：与文字墨迹同高，落在两段的正中间，1-bit 下比字形「|」干净
            a.rect(start + lead + (extGap(true) - 1) / 2f, textMid - 5f, 1, 10, LockCanvas.INK);
            a.c.drawText(value, start + lead + extGap(true), baseline, a.tp);
            return;
        }
        final int type = LockCanvas.EXT_TYPES[index];
        float[] ink = LockCanvas.INK_BOX[type - 1];
        float k = LockCanvas.INK_H[type - 1] / (ink[3] - ink[1]);
        // 把图标的墨迹左沿/中线接到 start、textMid 上，方框位置按墨迹反推
        drawIcon(type, start - ink[0] * k, textMid - (ink[1] + ink[3]) / 2f * k, 16 * k);
        a.font(LockCanvas.sSerifBold, 13, 0, LockCanvas.INK, true);
        a.c.drawText(value, start + lead + extGap(false), baseline, a.tp);
    }





    /**
     * 16×16 逻辑框内的单色图标：x/y 为左上角，s 为边长，按比例缩放。
     * 全部现画矢量，不打包 PNG/SVG —— 墨水屏只有黑白，矢量在任意尺寸都不糊，也免得为外部图标库背许可证。
     */
    void drawIcon(int type, float x, float y, float s) {
        final float k = s / 16f;
        a.line.reset();
        a.line.setAntiAlias(true);
        a.line.setStyle(Paint.Style.STROKE);
        a.line.setColor(LockCanvas.INK);
        a.line.setStrokeWidth(1.5f * k);
        a.line.setStrokeCap(Paint.Cap.ROUND);
        a.line.setStrokeJoin(Paint.Join.ROUND);
        a.fill.reset();
        a.fill.setAntiAlias(true);
        a.fill.setStyle(Paint.Style.FILL);
        a.fill.setColor(LockCanvas.INK);
        if (type == LockCanvas.IC_WIFI) {
            // 三段同心圆弧（从 225° 扫 90°，即正上方那段）+ 中心实心圆点
            a.path.reset();
            arc(8, 12.6f, 7.9f); arc(8, 12.6f, 5.4f); arc(8, 12.6f, 2.9f);
            stamp(x, y, k); a.c.drawPath(a.path, a.line);
            a.path.reset(); a.path.addCircle(8, 13.4f, 1.15f, Path.Direction.CW);
            stamp(x, y, k); a.c.drawPath(a.path, a.fill);
            return;
        }
        a.path.reset();
        if (type == LockCanvas.IC_BT) {
            // 蓝牙符号就是卢恩字母 Bjarkan：一条竖线 + 两组交叉斜线
            a.path.moveTo(8, 2.6f); a.path.lineTo(8, 13.4f);
            a.path.moveTo(8, 2.6f); a.path.lineTo(11.8f, 5.7f); a.path.lineTo(4.2f, 10.3f);
            a.path.moveTo(8, 13.4f); a.path.lineTo(11.8f, 10.3f); a.path.lineTo(4.2f, 5.7f);
        } else if (type == LockCanvas.IC_AUDIO) {
            // 实心喇叭（正面 1.2-4.2、锥口 8.6），声波弧在右侧 3 点钟方向（见下方 arc 调用）
            a.path.moveTo(1.2f, 5.2f); a.path.lineTo(4.2f, 5.2f); a.path.lineTo(8.6f, 1.6f);
            a.path.lineTo(8.6f, 14.4f); a.path.lineTo(4.2f, 10.8f); a.path.lineTo(1.2f, 10.8f);
            a.path.close();
        } else if (type == LockCanvas.IC_AIRPLANE) {
            // 俯视飞机：机头朝上。三条硬约束——翼展（15）要明显大于机身长（12.2），否则整块接近正方形，
            // 在 1-bit 下只剩「十字」；翼厚 2.2 别低于 2，薄了会被量化成一条线；平尾只有翼展一半宽。
            float[] pts = {8.0f, 2.0f, 9.0f, 5.4f, 15.5f, 7.0f, 15.5f, 9.2f, 9.0f, 8.0f,
                    9.0f, 10.8f, 11.3f, 12.2f, 11.3f, 13.4f, 9.0f, 12.8f, 9.0f, 14.2f,
                    7.0f, 14.2f, 7.0f, 12.8f, 4.7f, 13.4f, 4.7f, 12.2f, 7.0f, 10.8f,
                    7.0f, 8.0f, 0.5f, 9.2f, 0.5f, 7.0f, 7.0f, 5.4f};
            a.path.moveTo(pts[0], pts[1]);
            for (int i = 2; i < pts.length; i += 2) a.path.lineTo(pts[i], pts[i + 1]);
            a.path.close();
        } else return;
        stamp(x, y, k);
        a.c.drawPath(a.path, type == LockCanvas.IC_BT ? a.line : a.fill);
        if (type == LockCanvas.IC_AUDIO) {                 // 喇叭右侧两道声波弧（315° 起扫 90° = 正右半圆）
            a.path.reset();
            arc(9.0f, 8.0f, 3.2f, -45); arc(9.0f, 8.0f, 5.6f, -45);
            stamp(x, y, k);
            a.c.drawPath(a.path, a.line);
        }
    }

    /** 把 16×16 逻辑坐标下的 path 放大 k 倍并平移到 (x, y) */
    private void stamp(float x, float y, float k) {
        a.mat.reset();
        a.mat.setScale(k, k);
        a.mat.postTranslate(x, y);
        a.path.transform(a.mat);
    }

    /** 以 (cx, cy) 为圆心、r 为半径，追加正上方 90°（225°→315°）的圆弧到当前 path（wifi 用） */
    private void arc(float cx, float cy, float r) { arc(cx, cy, r, 225); }

    /** 同上，可指定起始角度。Android 的 0° 在 3 点钟方向、顺时针为正：喇叭声波用 -45（315°）扫出右半圆 */
    private void arc(float cx, float cy, float r, float start) {
        a.box.set(cx - r, cy - r, cx + r, cy + r);
        a.path.addArc(a.box, start, 90);
    }

    // ───────────────────────── A' · 编辑排版-横（横版构图） ─────────────────────────
}
