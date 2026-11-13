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

/** 「像素」样式及其点阵字形与图标。从 LockCanvas 拆出，画布、画笔与排版基础方法仍通过宿主 {@code a} 访问。 */
final class LockPixel {
    private final LockCanvas a;
    LockPixel(LockCanvas host) { a = host; }

    void pixel() {
        // 纯白背景：原 3×3 点阵纹理按用户要求移除（2026-10-23）
        a.rect(0, 0, LockCanvas.W, LockCanvas.H, LockCanvas.PAPER);

        final float X = 18, WW = 324, IN = X + 16, IW = WW - 32;

        // 窗口 1：时钟
        float y1 = 22;
        window(X, y1, WW, 150, "CLOCK");
        pixText(a.time(), IN, y1 + 38, 9);
        pixText(a.fmt("EEE dd.MM.yyyy").toUpperCase(Locale.US), IN, y1 + 122, 1.5f);
        float tempW = a.data.temperature == null ? 0 : pixWidth(a.data.temperature, 1.5f);
        if (a.data.temperature != null) pixText(a.data.temperature, IN + IW - tempW, y1 + 122, 1.5f);
        if (a.data.weather != null) icon(LockCanvas.ICON_CLOUD, IN + IW - tempW - 30, y1 + 115, 1.5f, LockCanvas.INK);

        // 窗口 2：正在播放 / 格言
        float y2 = y1 + 150 + 22;
        boolean progress = a.data.playing && a.data.duration > 0 && a.data.position >= 0;
        a.font(LockCanvas.sSans, 21, 0, LockCanvas.INK, false);
        float textH = 0;
        StaticLayout probe = null;
        if (a.data.text != null) {
            probe = StaticLayout.Builder.obtain(a.data.text, 0, a.data.text.length(), a.tp, (int) (IW - 44))
                    .setMaxLines(3).setEllipsize(TextUtils.TruncateAt.END).build();
            textH = Math.max(32, probe.getLineCount() * 21 * 1.35f);
        }
        float subH = 0;
        if (a.data.track == null && a.data.subText != null) {
            a.font(LockCanvas.sSans, 12, 0, LockCanvas.INK, false);
            StaticLayout sub = StaticLayout.Builder.obtain(a.data.subText, 0, a.data.subText.length(), a.tp, (int) IW)
                    .setMaxLines(3).setEllipsize(TextUtils.TruncateAt.END).build();
            subH = sub.getLineCount() * 12 * 1.4f;
        }
        float h2 = 24 + 14 + textH + (a.data.track != null ? 10 + 11 : 0) + (subH > 0 ? 10 + subH : 0)
                + (progress ? 10 + 10 : 0) + 14 + 4;
        window(X, y2, WW, h2, a.data.playing ? "NOW PLAYING" : "DAILY QUOTE");
        float cy = y2 + 24 + 14;
        icon(a.data.playing ? LockCanvas.ICON_NOTE : LockCanvas.ICON_QUOTE, IN, cy, 2, LockCanvas.INK);
        if (a.data.text != null) {
            a.font(LockCanvas.sSans, 21, 0, LockCanvas.INK, false);
            a.paragraph(a.data.text, IN + 44, cy, IW - 44, 1.35f, 3);
        }
        cy += textH;
        if (a.data.track != null) {
            cy += 10;
            mixedText(a.data.track, IN, cy, IW, 1.5f, 12);
            cy += 11;
        } else if (subH > 0) {
            cy += 10;
            a.font(LockCanvas.sSans, 12, 0, LockCanvas.INK, false);
            a.paragraph(a.data.subText, IN, cy, IW, 1.4f, 3);
            cy += subH;
        }
        if (progress) {
            cy += 10;
            int filled = Math.round(20f * Math.min(1, Math.max(0, a.data.position / (float) a.data.duration)));
            for (int i = 0; i < 20; i++) {
                float sx = IN + i * 13;
                a.frame(sx, cy, 10, 10, 2);
                if (i < filled) a.rect(sx, cy, 10, 10, LockCanvas.INK);
            }
        }

        // ── 下半部三块：电量 / 倒数日 / 通知-设备状态 ──
        // 顺序按用户指定：格言 → 电量 → 倒数日 → 通知/设备状态。
        // 原来通知数与电量挤在同一行右端，四项设备状态塞不下，只能另起一块挂到电量下面，读起来像
        // 「电量的附注」；现在电量独占一块（图标按电量填充 + 大号百分比），通知与设备状态本是互斥的
        // 两个总开关，正好共用最后一块：同高 58、标题 NOTIFY / STATUS，来回切也不会整块上下跳。
        // 块间距按剩余空间在 12–22 之间取，倒数日三组时这一摞的最高点仍留有 20 余量，绝不压到底部解锁框。
        final List<FeatureDefinition.CountdownItem> shown = a.shownCountdowns();
        final List<Integer> picks = a.data.extMode ? a.extBarView.extPicks() : null;
        final boolean slot = a.data.extMode ? !picks.isEmpty() : (!a.data.hideNotify && a.data.notifications >= 0);
        final float hBat = 48, hCd = shown.isEmpty() ? 0 : 80, hSlot = 58;
        final int blocks = 1 + (hCd > 0 ? 1 : 0) + (slot ? 1 : 0);
        final float top = y2 + h2;
        final float stack = hBat + hCd + (slot ? hSlot : 0);
        final float gap = Math.max(12, Math.min(22, ((LockCanvas.H - 58) - 12 - top - stack) / blocks));
        float y = top + gap;
        pixelBattery(X, y, WW);
        y += hBat + gap;
        if (hCd > 0) {
            pixelCountdown(X, y, WW, shown);
            y += hCd + gap;
        }
        if (slot) pixelSlot(X, y, WW, picks);

        // 底部：滑动解锁
        float yb = LockCanvas.H - 18 - 40;
        a.rect(X, yb, WW, 40, LockCanvas.PAPER);
        a.frame(X, yb, WW, 40, 2);
        String hint = "↑ SLIDE TO UNLOCK ↑";
        float hw = pixWidth(hint, 1.5f);
        pixText(hint, (LockCanvas.W - hw) / 2f, yb + 15, 1.5f);
    }

    /**
     * 电量窗口：电池图标按电量填充 + 大号百分比；充电时右端标 CHARGING。
     * 这块原来和通知数共用一行，通知搬走后右侧会空出一大片，所以百分比放大到 u=2.5，
     * 由「图标 + 数字」自己撑满一行；没有充电标记时宁可留白也不塞无意义的装饰。
     */
    private void pixelBattery(float x, float y, float w) {
        final float IN = x + 16, IW = w - 32;
        window(x, y, w, 48, null);
        final float mid = y + 24;
        // 16 格空间里电池墨迹纵向 4..12（中心 8），scale 3 后墨迹 24 高；y 传 mid-24 让墨迹中线落在块中线
        icon(LockCanvas.ICON_BATTERY, IN - 3, mid - 24, 3, LockCanvas.INK);
        if (a.data.battery >= 0) {
            // 内腔在 16 格空间里是 x 3..9 / y 6..10，从左往右填；宽度取 3（=1 格）的整数倍才落在物理像素上
            final float ix = IN + 6, iy = mid - 6;
            a.rect(ix, iy, 18, 12, LockCanvas.PAPER);
            final float fill = Math.round(6 * a.data.battery / 100f) * 3;
            if (fill > 0) a.rect(ix, iy, fill, 12, LockCanvas.INK);
        }
        pixText(a.batteryText() + "%", IN + 46, mid - 8.75f, 2.5f);
        if (a.data.charging) {
            final String tag = "CHARGING";
            pixText(tag, IN + IW - pixWidth(tag, 1), mid - 3.5f, 1);
        }
    }

    /**
     * 倒数日窗口：每列「事项名 / N DAYS」，列的分配与竖版一致（1 组居中、2 组 1/4 与 3/4、
     * 3 组 1/6 3/6 5/6）。天数是主要信息，走 5×7 点阵（1-bit 下比黑体干净），已过用负号；
     * 事项名是中文，只能交给无抗锯齿黑体，超长按列宽横向压缩，不会串到邻列。
     */
    private void pixelCountdown(float x, float y, float w, List<FeatureDefinition.CountdownItem> shown) {
        final float IN = x + 16, IW = w - 32;
        window(x, y, w, 80, "COUNTDOWN");
        final int n = shown.size();
        final float cell = IW / n;
        for (int i = 0; i < n; i++) {
            final float cx = IN + cell * (i + 0.5f);
            final FeatureDefinition.CountdownItem it = shown.get(i);
            final long days = a.daysUntil(it);
            final String label = (days >= 0 ? String.valueOf(days) : "-" + Math.abs(days)) + " DAYS";
            final float lw = pixWidth(label, 1.5f);
            if (it.item.isEmpty()) {
                // 没填事项时只有天数，整行在内容区（y+22..y+76）里居中
                pixText(label, cx - lw / 2f, y + 44, 1.5f);
            } else {
                a.font(LockCanvas.sSans, 16, 0, LockCanvas.INK, false);
                a.textCenterFit(it.item, cx, y + 30, cell - 10);
                pixText(label, cx - lw / 2f, y + 56, 1.5f);
            }
        }
    }

    /**
     * 最后一块（互斥的那块）：显示设备状态时是四项「图标 / ON|OFF」，否则是通知数。
     * 两者同高 58、都带标题，切换总开关时下面的解锁框与上面的块都不会跳。
     * 像素风整体是英文标牌（CLOCK / NOW PLAYING / COUNTDOWN / SLIDE TO UNLOCK），
     * 设备状态也就只用 ON / OFF 不混中文；通知只能写系统给的中文来源名，那是数据不是标签。
     */
    private void pixelSlot(float x, float y, float w, List<Integer> picks) {
        final float IN = x + 16, IW = w - 32;
        if (a.data.extMode) {
            window(x, y, w, 58, "STATUS");
            final boolean[] on = {a.data.wifi, a.data.bt, !a.data.silent, a.data.airplane};
            final float cw = IW / picks.size();
            for (int i = 0; i < picks.size(); i++) {
                final float cx = IN + cw * (i + 0.5f);
                // 内容区只有 32 高（58 - 标题栏 22 - 投影 4），墨迹收到 11 才放得下下面一行 ON/OFF
                final int type = LockCanvas.EXT_TYPES[picks.get(i)];
                final float[] ink = LockCanvas.INK_BOX[type - 1];
                final float k = 11f / (ink[3] - ink[1]);
                final float inkW = (ink[2] - ink[0]) * k;
                a.extBarView.drawIcon(type, cx - inkW / 2f - ink[0] * k, y + 35 - (ink[1] + ink[3]) / 2f * k, 16 * k);
                final String label = on[picks.get(i)] ? "ON" : "OFF";
                pixText(label, cx - pixWidth(label, 1.5f) / 2f, y + 43, 1.5f);
            }
            return;
        }
        window(x, y, w, 58, "NOTIFY");
        // ICON_NOTIFY 墨迹在 16 格空间里是 x 2..15 / y 3..14，scale 2 后 x 传 IN-4 让墨迹左沿落在 IN
        icon(LockCanvas.ICON_NOTIFY, IN - 4, y + 24, 2, LockCanvas.INK);
        final String cnt = String.valueOf(a.data.notifications);
        pixText(cnt, IN + 30, y + 29.25f, 2.5f);
        // 来源名靠右端：紧挨着数字排会变成「铃铛 + 方框(0) + 中文」三块连着的方块，0 偏偏就是个方框
        a.font(LockCanvas.sSans, 15, 0, LockCanvas.INK, false);
        a.textRight(a.data.notificationLabel, IN + IW, y + 30, IW * 0.45f);
    }

    /** 老式 Mac 窗口：2px 边框、4px 实心投影、条纹标题栏 */
    private void window(float x, float y, float w, float h, String title) {
        a.rect(x + 4, y + 4, w, h, LockCanvas.INK);
        a.rect(x, y, w, h, LockCanvas.INK);
        a.rect(x + 2, y + 2, w - 4, h - 4, LockCanvas.PAPER);
        if (title == null) return;
        float sx = x + 2 + 6, sy = y + 2 + 5, swd = w - 4 - 12;
        for (int i = 0; i < 5; i++) a.rect(sx, sy + i * 2, swd, 1, LockCanvas.INK);
        a.rect(x + 2, y + 22, w - 4, 2, LockCanvas.INK);
        float tw = pixWidth(title, 1);
        a.rect(x + (w - tw) / 2f - 8, sy - 1, tw + 16, 12, LockCanvas.PAPER);
        pixText(title, x + (w - tw) / 2f, sy + 1.5f, 1);
    }

    // ───────────── 5×7 点阵字（只含锁屏用到的 ASCII，按位图逐格画方块） ─────────────


    static void g(char ch, String... rows) {
        LockCanvas.GLYPHS.put(ch, rows);
    }

    static {
        g('0', "01110", "10001", "10001", "10001", "10001", "10001", "01110");
        g('1', "00100", "01100", "00100", "00100", "00100", "00100", "01110");
        g('2', "01110", "10001", "00001", "00010", "00100", "01000", "11111");
        g('3', "11111", "00010", "00100", "00010", "00001", "10001", "01110");
        g('4', "00010", "00110", "01010", "10010", "11111", "00010", "00010");
        g('5', "11111", "10000", "11110", "00001", "00001", "10001", "01110");
        g('6', "00110", "01000", "10000", "11110", "10001", "10001", "01110");
        g('7', "11111", "00001", "00010", "00100", "01000", "01000", "01000");
        g('8', "01110", "10001", "10001", "01110", "10001", "10001", "01110");
        g('9', "01110", "10001", "10001", "01111", "00001", "00010", "01100");
        g('A', "01110", "10001", "10001", "10001", "11111", "10001", "10001");
        g('B', "11110", "10001", "10001", "11110", "10001", "10001", "11110");
        g('C', "01110", "10001", "10000", "10000", "10000", "10001", "01110");
        g('D', "11100", "10010", "10001", "10001", "10001", "10010", "11100");
        g('E', "11111", "10000", "10000", "11110", "10000", "10000", "11111");
        g('F', "11111", "10000", "10000", "11110", "10000", "10000", "10000");
        g('G', "01110", "10001", "10000", "10111", "10001", "10001", "01111");
        g('H', "10001", "10001", "10001", "11111", "10001", "10001", "10001");
        g('I', "01110", "00100", "00100", "00100", "00100", "00100", "01110");
        g('J', "00111", "00010", "00010", "00010", "00010", "10010", "01100");
        g('K', "10001", "10010", "10100", "11000", "10100", "10010", "10001");
        g('L', "10000", "10000", "10000", "10000", "10000", "10000", "11111");
        g('M', "10001", "11011", "10101", "10101", "10001", "10001", "10001");
        g('N', "10001", "10001", "11001", "10101", "10011", "10001", "10001");
        g('O', "01110", "10001", "10001", "10001", "10001", "10001", "01110");
        g('P', "11110", "10001", "10001", "11110", "10000", "10000", "10000");
        g('Q', "01110", "10001", "10001", "10001", "10101", "10010", "01101");
        g('R', "11110", "10001", "10001", "11110", "10100", "10010", "10001");
        g('S', "01111", "10000", "10000", "01110", "00001", "00001", "11110");
        g('T', "11111", "00100", "00100", "00100", "00100", "00100", "00100");
        g('U', "10001", "10001", "10001", "10001", "10001", "10001", "01110");
        g('V', "10001", "10001", "10001", "10001", "10001", "01010", "00100");
        g('W', "10001", "10001", "10001", "10101", "10101", "10101", "01010");
        g('X', "10001", "10001", "01010", "00100", "01010", "10001", "10001");
        g('Y', "10001", "10001", "10001", "01010", "00100", "00100", "00100");
        g('Z', "11111", "00001", "00010", "00100", "01000", "10000", "11111");
        g(':', "00", "11", "11", "00", "11", "11", "00");
        g('.', "00", "00", "00", "00", "00", "11", "11");
        g(',', "00", "00", "00", "00", "11", "01", "10");
        g('\'', "1", "1", "0", "0", "0", "0", "0");
        g('!', "1", "1", "1", "1", "1", "0", "1");
        g('?', "01110", "10001", "00001", "00010", "00100", "00000", "00100");
        g('%', "11000", "11001", "00010", "00100", "01000", "10011", "00011");
        g('-', "0000", "0000", "0000", "1111", "0000", "0000", "0000");
        g('>', "100", "010", "001", "010", "100", "000", "000");
        g('<', "001", "010", "100", "010", "001", "000", "000");
        g('↑', "00100", "01110", "11111", "00100", "00100", "00100", "00100");
        g('/', "00001", "00010", "00010", "00100", "01000", "01000", "10000");
        g('°', "010", "101", "010", "000", "000", "000", "000");
        g('(', "01", "10", "10", "10", "10", "10", "01");
        g(')', "10", "01", "01", "01", "01", "01", "10");
        g('&', "01100", "10010", "10100", "01000", "10101", "10010", "01101");
        g('·', "0", "0", "0", "1", "0", "0", "0");
        g(' ', "000", "000", "000", "000", "000", "000", "000");
    }

    private static String[] glyph(char ch) {
        return LockCanvas.GLYPHS.get(Character.toUpperCase(ch));
    }

    private static boolean pixelable(String s) {
        for (int i = 0; i < s.length(); i++) if (glyph(s.charAt(i)) == null) return false;
        return true;
    }

    private static float pixWidth(String s, float u) {
        float w = 0;
        for (int i = 0; i < s.length(); i++) {
            String[] gl = glyph(s.charAt(i));
            if (gl == null) continue;
            w += (gl[0].length() + 1) * u;
        }
        return Math.max(0, w - u);
    }

    /** 点阵字，u 为一格的逻辑像素数（×2 后须为整数物理像素） */
    private float pixText(String s, float x, float top, float u) {
        float cx = x;
        for (int i = 0; i < s.length(); i++) {
            String[] gl = glyph(s.charAt(i));
            if (gl == null) continue;
            for (int r = 0; r < gl.length; r++) {
                String row = gl[r];
                for (int col = 0; col < row.length(); col++) {
                    if (row.charAt(col) == '1') a.rect(cx + col * u, top + r * u, u, u, LockCanvas.INK);
                }
            }
            cx += (gl[0].length() + 1) * u;
        }
        return cx - x;
    }

    /** 能用点阵字就用点阵字，否则（中文等）用无抗锯齿的黑体 */
    private void mixedText(String s, float x, float top, float maxW, float u, float size) {
        if (pixelable(s) && pixWidth(s, u) <= maxW) {
            pixText(s, x, top, u);
        } else {
            a.font(LockCanvas.sSans, size, 0, LockCanvas.INK, false);
            a.text(s, x, top - 2, maxW);
        }
    }

    // ───────────── 16×16 像素图标（只用水平/垂直线段的路径，奇偶填充） ─────────────


    private void icon(String d, float x, float y, float scale, int color) {
        Path path = parse(d);
        path.setFillType(Path.FillType.EVEN_ODD);
        a.fill.reset();
        a.fill.setColor(color);
        a.fill.setAntiAlias(false);
        a.c.save();
        a.c.translate(x, y);
        a.c.scale(scale, scale);
        a.c.drawPath(path, a.fill);
        a.c.restore();
    }

    /** 只支持 M/m H/h V/v Z/z（像素图标够用） */
    static Path parse(String d) {
        Path p = new Path();
        float cx = 0, cy = 0, sx = 0, sy = 0;
        int i = 0, n = d.length();
        char cmd = 'M';
        while (i < n) {
            char ch = d.charAt(i);
            if (Character.isLetter(ch)) {
                cmd = ch;
                i++;
                if (cmd == 'z' || cmd == 'Z') {
                    p.close();
                    cx = sx;
                    cy = sy;
                }
                continue;
            }
            if (ch == ' ' || ch == ',') {
                i++;
                continue;
            }
            int[] pos = {i};
            float a = number(d, pos);
            i = pos[0];
            switch (cmd) {
                case 'M':
                case 'm': {
                    while (i < n && (d.charAt(i) == ' ' || d.charAt(i) == ',')) i++;
                    pos[0] = i;
                    float b = number(d, pos);
                    i = pos[0];
                    if (cmd == 'm') {
                        a += cx;
                        b += cy;
                    }
                    cx = sx = a;
                    cy = sy = b;
                    p.moveTo(cx, cy);
                    cmd = cmd == 'm' ? 'l' : 'L';
                    break;
                }
                case 'H':
                    cx = a;
                    p.lineTo(cx, cy);
                    break;
                case 'h':
                    cx += a;
                    p.lineTo(cx, cy);
                    break;
                case 'V':
                    cy = a;
                    p.lineTo(cx, cy);
                    break;
                case 'v':
                    cy += a;
                    p.lineTo(cx, cy);
                    break;
                default:
                    break;
            }
        }
        return p;
    }

    private static float number(String s, int[] pos) {
        int i = pos[0], start = i;
        if (i < s.length() && (s.charAt(i) == '-' || s.charAt(i) == '+')) i++;
        while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) i++;
        pos[0] = i;
        return Float.parseFloat(s.substring(start, i));
    }
}
