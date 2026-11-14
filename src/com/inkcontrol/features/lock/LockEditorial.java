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

/** 「编辑排版」竖版样式。从 LockCanvas 拆出，画布、画笔与排版基础方法仍通过宿主 {@code a} 访问。 */
final class LockEditorial {
    private final LockCanvas a;
    LockEditorial(LockCanvas host) { a = host; }

    void editorial() {
        final float L = 24, R = 336, CW = R - L;

        // ── 顶部表头：换用「编辑排版-横」同一串信息，但竖版版心只有 312 宽（横版 720），
        // 一行塞不下 26 号字，拆两行：主行「第N日 | 中文日期·星期」18 号，
        // 副行「农历 九月十六」16 号；无农历日期时只剩主行，分隔线下移量随之变化。
        // 字号也按实际宽度兜底：主行超宽就等比缩（下限 13），保证不被裁切。
        String head = "第" + a.data.now.get(Calendar.DAY_OF_YEAR) + "日 | " + a.chineseDate() + "·" + a.weekCn();
        float hSize = 18;
        a.font(LockCanvas.sSerif, hSize, 0.04f, LockCanvas.INK, true);
        float hw = a.tp.measureText(head);
        if (hw > CW) {
            hSize = Math.max(13f, hSize * CW / hw);
            a.font(LockCanvas.sSerif, hSize, 0.04f, LockCanvas.INK, true);
        }
        a.textBase(head, L, 34, CW);
        float headBottom = 34;
        if (a.almanac() != null) {
            a.font(LockCanvas.sSerif, 18, 0.06f, LockCanvas.INK, true);
            headBottom = 62;
            a.textBase("农历 " + a.almanac(), L, headBottom, CW);
        }
        a.rect(L, headBottom + 10, CW, 1, LockCanvas.INK);

        // 大号时间：衬线体横向收窄铺满版心，接近样张里 Instrument Serif 的瘦长比例
        // 日期行上移到表头后下方留白收紧，基线 200 → 194
        a.font(LockCanvas.sSerif, 136, -0.03f, LockCanvas.INK, true);
        float tw = a.tp.measureText(a.time());
        a.tp.setTextScaleX(Math.max(0.7f, Math.min(1f, (CW + 4) / tw)));
        a.c.drawText(a.time(), L - 4, 194, a.tp);
        a.tp.setTextScaleX(1f);

        // 原「中文日期 + 星期·农历」那一行已并入表头，这里不再重复；表格上移补位
        float gy = 234;
        a.rect(L, gy, CW, 1, LockCanvas.INK);
        a.rect(L, gy + 66, CW, 1, LockCanvas.INK);
        if (a.hasWeather()) {
            a.rect(L + 104, gy, 1, 66, LockCanvas.INK);
            a.rect(L + 208, gy, 1, 66, LockCanvas.INK);
            // 三个格子等宽 104：小标题与数值都在各自格内居中（2026-10-25 用户要求，原来靠左沿排）
            String temp = a.data.high != null ? a.data.high + " / " + a.data.low : LockCanvas.dash(a.data.temperature);
            a.metricCell("天气", LockCanvas.dash(a.data.weather), L, gy, 104, 66);
            a.metricCell("气温", temp, L + 104, gy, 104, 66);
            a.metricCell("电量", a.data.battery < 0 ? "—" : a.data.battery + "%", L + 208, gy, 104, 66);
        } else {
            a.font(LockCanvas.sSerif, 14, 0.2f, LockCanvas.INK, true);
            a.textBase("电量", L, gy + 23, 0);
            a.font(LockCanvas.sSerif, 22, 0, LockCanvas.INK, true);
            String battery = a.data.battery < 0 ? "—" : a.data.battery + "%";
            a.textBase(battery + (a.data.charging ? " · 充电中" : ""), L, gy + 48, CW - 150);
            if (!a.data.hideNotify && a.data.notifications > 0) {
                a.font(LockCanvas.sSerif, 14, 0, LockCanvas.INK, true);
                a.textRight(a.notificationText(), R, gy + 48, 160);
            }
        }

        // 正在播放 / 今日格言（整体上移 50，承接上移后的表格）
        float py = 334;
        a.font(LockCanvas.sSerif, 10, 0.3f, LockCanvas.INK, true);
        a.text(a.data.playing ? "正在播放" : "今日格言", L, py, 0);
        a.font(LockCanvas.sSerif, 64, 0, LockCanvas.INK, true);
        a.textBase("“", L - 2, py + 62, 0);
        float ph = 0;
        if (a.data.text != null) {
            a.font(LockCanvas.sSerifBold, 21, 0, LockCanvas.INK, true);
            ph = a.paragraph(a.data.text, L, py + 50, CW, 1.55f, 3);
        }
        if (a.data.track != null) {
            a.font(LockCanvas.sSerif, 12, 0.08f, LockCanvas.INK, true);
            a.text("— " + a.data.track, L, py + 50 + ph + 10, CW);
        } else if (a.data.subText != null) {
            a.font(LockCanvas.sSerif, 12, 0.02f, LockCanvas.INK, true);
            a.paragraph(a.data.subText, L, py + 50 + ph + 8, CW, 1.5f, 3);
        }

        // 底部：倒数日（通知条上方）→ 通知提醒条 → 上滑解锁
        // 倒数日沿用横版那套呈现：上方「倒数日」小标题 + 细方框，框内每组的「事项名 / 剩 N 日」两行。
        // 竖版比横版宽得多，横版竖排的清单在这里横过来排：一/二/三组按格均分、各自在格内居中，
        // 组间不画竖线（用户 2026-10-25：竖线分割难看，两组时也不好分配）；只有一组时正好落在正中。
        // 「显示」关闭的组不绘制；无任何可见组时整块不出现，通知条位置保持不变。
        List<FeatureDefinition.CountdownItem> shown = a.shownCountdowns();
        if (!shown.isEmpty()) {
            a.font(LockCanvas.sSerifBold, 18, 0.12f, LockCanvas.INK, true);
            a.textCenter("倒数日", LockCanvas.W / 2f, 511);
            final float CY = 536, CH = 64;
            a.frame(L, CY, CW, CH, 1);
            int n = Math.min(shown.size(), FeatureDefinition.COUNTDOWNS_MAX);
            final float cellW = CW / n;
            final float lineH = 18, gapIn = 4;
            for (int i = 0; i < n; i++) {
                FeatureDefinition.CountdownItem entry = shown.get(i);
                float cx = L + cellW * (i + 0.5f);
                long days = a.daysUntil(entry);
                String left = (days >= 0 ? "剩 " : "过 ") + Math.abs(days) + " 日";
                if (!entry.item.isEmpty()) {
                    float top = CY + (CH - (lineH * 2 + gapIn)) / 2f;
                    a.font(LockCanvas.sSerifBold, 15, 0, LockCanvas.INK, true);
                    a.textCenterFit(entry.item, cx, top, cellW - 14);
                    a.font(LockCanvas.sSerif, 13, 0, LockCanvas.INK, true);
                    a.textCenterFit(left, cx, top + lineH + gapIn, cellW - 14);
                } else {
                    a.font(LockCanvas.sSerif, 13, 0, LockCanvas.INK, true);
                    a.textCenterFit(left, cx, CY + (CH - 13) / 2f + 1, cellW - 14);
                }
            }
        }

        float hintTop = 671;
        if (a.data.extMode) {
            // 「显示设备状态」开着：同一块位置（613 起、高 42）把通知换成四项开关状态
            a.extBarView.extBar(L, 613, CW, 42);
        } else if (!a.data.hideNotify) {
            if (a.data.notifications > 0) {
                a.frame(L, 613, CW, 42, 1);
                a.font(LockCanvas.sSerifBold, 14, 0, LockCanvas.INK, true);
                float w = a.textBase(a.data.notificationLabel, L + 14, 639, 0);
                a.font(LockCanvas.sSerif, 14, 0, LockCanvas.INK, true);
                a.textBase("　" + a.data.notifications + " 条", L + 14 + w, 639, CW - 40);
            } else if (a.data.notifications == 0) {
                a.frame(L, 613, CW, 42, 1);
                a.font(LockCanvas.sSerif, 14, 0, LockCanvas.INK, true);
                a.textBase("暂无新消息", L + 14, 639, 0);
            }
        }
        a.rect(L, hintTop, CW, 1, LockCanvas.INK);
        a.font(LockCanvas.sSerif, 11, 0.6f, LockCanvas.INK, true);
        a.textCenter("上滑解锁", LockCanvas.W / 2f, hintTop + 10);
    }
}
