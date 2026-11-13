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

/** 「瑞士网格」样式。从 LockCanvas 拆出，画布、画笔与排版基础方法仍通过宿主 {@code a} 访问。 */
final class LockSwiss {
    private final LockCanvas a;
    LockSwiss(LockCanvas host) { a = host; }

    void swiss() {
        final float L = 20, CW = 320, COL = 74, GAP = 8;

        // 表头固定三列，字号从 12 提到 14（用户要求放大）：
        //   周三 第44周 / 10.29 | 9.20(农历) / 2026年 | 第302天
        // 三格等宽（各 101.3），但「2026年 | 第302天」这种 14sp 要 107 宽、塞不进，
        // 所以先按 14sp 量一遍取三格里最小的比例，**统一缩放**：只缩塞不下的那一格会让
        // 表头字号参差，瑞士网格最忌讳这个。段内不再用横向压缩（会把数字压瘦变形）。
        // 农历日期的「(农历)」注释比日期小一号（12sp vs 14sp），但不能再小——
        // 原来按 10sp 排，1-bit 下比日期矮一截，看着像掉下去的角标、不像同一行。
        final int dow = a.data.now.get(Calendar.DAY_OF_WEEK) - 1;
        final String[][] top = {
                {"周" + LockCanvas.WEEK_CN[dow], "  第" + a.data.now.get(Calendar.WEEK_OF_YEAR) + "周"},
                {a.fmt("MM.dd"), " | ", a.lunarNumeric(), "(农历)"},
                {a.fmt("yyyy") + "年", " | ", "第" + a.data.now.get(Calendar.DAY_OF_YEAR) + "天"}};
        final float[][] topSz = {{14, 14}, {14, 14, 14, 12}, {14, 14, 14}};
        final float col = (CW - 2 * GAP) / 3f;
        float topScale = 1f;
        for (int i = 0; i < top.length; i++) topScale = Math.min(topScale, a.segFit(top[i], topSz[i], col));
        // 基线定位：segRow 内部按字形框中线对齐（小一号的「(农历)」不会掉到日期下面）。
        for (int i = 0; i < top.length; i++) {
            final float x = L + i * (col + GAP);
            a.rect(x, 22, col, 4, LockCanvas.INK);
            a.segRow(top[i], topSz[i], topScale, x, 46);
        }

        // 两行叠放的特大时间。184sp 的数字墨迹实测 125 高（数字没有降部，墨迹下沿落在基线上）。
        // 用户口径：横向要松、纵向要贴，两行的间距收到原来的 1/3。上一版基线 188/360 的墨迹间隙
        // 实测 47（设备像素 94），现在基线 188/329、间隙 16，两行归成一个整块。
        // 注意别按 0.77×字号去估墨迹高——那是黑体上沿的粗算，数字实际只有 0.68 倍。
        a.font(LockCanvas.sHeavy, 184, 0.06f, LockCanvas.INK, true);
        a.textBase(a.hour(), L - 6, 188, 290);
        a.textBase(a.fmt("mm"), L - 6, 329, 290);

        // 右缘竖排（第 N 天 / 空槽 / 第 N 周）已按用户要求整列删除：这两项现在跟在表头
        // 第一、三格的右半边，整块右缘留白。

        // 天气块：不管有没有数据都占住 352-428 这一段（用户要求「留相应的空位即可」）。
        // 原来没天气时格言直接顶到 376，天气数据一回来整摞内容就往下跳一格；现在位置恒定，
        // 天气关着时这块就是留白。模块设置里没开天气显示，所以这条路径是常态、不是异常。
        // 整块比上一版上移 24：格言正文加英文译文的底边离设备状态带只有 28（实机量到的），
        // 而预览样张里格言只有一两行、显得远；把天气留位和格言线一起上提，把这点空间让给下方。
        final float by = 352;
        if (a.hasWeather()) {
            boolean range = a.data.high != null && a.data.low != null;
            a.font(LockCanvas.sSansBold, 10, 0.12f, LockCanvas.INK, true);
            a.text("WEATHER", L + 16, by + 14, 0);
            a.text(range ? "HI / LO" : "TEMP", L + 164, by + 14, 0);
            a.font(LockCanvas.sSansMedium, 28, 0, LockCanvas.INK, true);
            a.text(LockCanvas.dash(a.data.weather), L + 16, by + 32, 140);
            a.font(LockCanvas.sHeavy, 28, -0.03f, LockCanvas.INK, true);
            a.text(range ? LockCanvas.strip(a.data.high) + " / " + LockCanvas.strip(a.data.low) : LockCanvas.dash(a.data.temperature), L + 164, by + 32, 140);
        }
        final float ny = 428;
        a.rect(L, ny, CW, 1, LockCanvas.INK);
        a.font(LockCanvas.sSansBold, 10, 0.12f, LockCanvas.INK, true);
        a.text(a.data.playing ? "NOW" : "DAILY", L, ny + 9, COL);
        a.text(a.data.playing ? "PLAYING" : "QUOTE", L, ny + 22, COL);
        float tx = L + COL + GAP, tw = CW - COL - GAP;
        // 天气块固定留位后格言恒为 2 行：行数一变，下面整摞（通知带、底部）的位置就跟着变。
        final int quoteLines = 2;
        float th = 0, sh = 0;
        if (a.data.text != null) {
            a.font(LockCanvas.sSansBold, 17, 0, LockCanvas.INK, true);
            th = a.paragraph(a.data.text, tx, ny + 8, tw, 1.45f, quoteLines);
        }
        // 播放媒体时在格言下面补一行当前曲目（曲名 · 歌手），与上游瑞士风格一致：
        // 左边那两行 NOW PLAYING / DAILY QUOTE 只是栏目标签，曲名必须单独成行才读得到。
        // 12sp、前面一个长破折号与格言正文拉开距离；单行显示（超宽横向压缩），不许换行顶到
        // 下面的设备状态带。
        if (a.data.track != null) {
            a.font(LockCanvas.sSans, 12, 0.01f, LockCanvas.INK, true);
            a.text("— " + a.data.track, tx, ny + 8 + th + 7, tw);
            sh = 18;
        } else if (a.data.subText != null) {
            a.font(LockCanvas.sSans, 12, 0, LockCanvas.INK, true);
            sh = a.paragraph(a.data.subText, tx, ny + 8 + th + 4, tw, 1.4f, quoteLines);
        }

        // ── 通知带 / 设备状态带：底部「电量 + 倒数日」正上方一条（1px 分隔线 + 同一基线的一行数据）──
        // 位置用固定值而不是「跟着格言底边走」：格言正文 2 行 + 英文译文 2 行时块底会落到 523，
        // 带子若跟着它走就只剩 26 的空白，实机看着贴着（预览样张格言只有一两行，所以显得远）。
        // 现在带子顶固定在 586（基线 616、文字墨迹底 619），格言块底到带子留 63，
        // 带子到 646 那根 4px 横线还有 27。钳位 586-594 只是安全阀，格言异常长时最多下移 8，
        // 再往下就会蹭到 646 的横线。
        final float quoteBottom = ny + 8 + th + (th > 0 ? 4 : 0) + sh;
        final float bandTop = Math.max(586, Math.min(594, quoteBottom + 40));
        final boolean bandExt = a.data.extMode;
        final boolean bandNotify = !bandExt && !a.data.hideNotify;
        if (bandExt || bandNotify) {
            a.rect(L, bandTop, CW, 1, LockCanvas.INK);
            final float base = bandTop + 30;
            if (bandExt) {
                // 四项均分整条 320（原来按 74+8 的格），类型与状态同字号、同一条基线，
                // 中间一条 1px 竖线画成「无线|开」。用户要求再放大一点：15sp 在 320 宽的带子里
                // 和下面 30sp 的电量大数字比还是像注脚，目标字号定 17sp。
                // 但 4 项均分时最宽的「声音+静音」是 34+5+1+5+34 = 79，正好顶满 80 的格子、
                // 与邻项只隔 1px（实机看着像「静音飞行」连在一起）；所以先量一遍，塞不下就整行退 16sp。
                List<Integer> pick = a.extBarView.extPicks();
                final float cell = CW / pick.size();
                float fs = 17;
                for (Integer idx : pick) {
                    a.font(LockCanvas.sSans, fs, 0, LockCanvas.INK, true);
                    float need = a.tp.measureText(LockCanvas.EXT_NAMES[idx]);
                    a.font(LockCanvas.sSansBold, fs, 0, LockCanvas.INK, true);
                    need += 11 + a.tp.measureText(a.extBarView.extValue(idx));
                    if (need + 4 > cell) { fs = 16; break; }
                }
                for (int k = 0; k < pick.size(); k++) {
                    final int i = pick.get(k);
                    final float x = L + k * cell;
                    a.font(LockCanvas.sSans, fs, 0, LockCanvas.INK, true);
                    final float lw = a.tp.measureText(LockCanvas.EXT_NAMES[i]);
                    a.textBase(LockCanvas.EXT_NAMES[i], x, base, 0);
                    a.rect(x + lw + 5, base - 13, 1, 15, LockCanvas.INK);
                    a.font(LockCanvas.sSansBold, fs, 0, LockCanvas.INK, true);
                    a.textBase(a.extBarView.extValue(i), x + lw + 10, base, cell - lw - 10);
                }
            } else {
                // 「通知」与条数贴在一起、按墨迹中线对齐：原来标签 10sp 贴左沿、条数 28sp 贴右沿，
                // 中间隔了三百像素，既远又不是一条视觉中线。
                final String count = a.data.notifications < 0 ? "—" : String.valueOf(a.data.notifications);
                a.font(LockCanvas.sHeavy, 26, -0.02f, LockCanvas.INK, true);
                final float countW = a.tp.measureText(count);
                final float mid = base + a.inkMid(count);        // 大数字的墨迹中线
                a.font(LockCanvas.sSans, 13, 0.06f, LockCanvas.INK, true);
                final String label = a.data.notificationLabel;
                final float labelW = a.tp.measureText(label) - a.tp.getLetterSpacing() * a.tp.getTextSize();
                final float numX = L + labelW + 9;
                a.textBase(label, L, mid - a.inkMid(label), 0);
                a.font(LockCanvas.sHeavy, 26, -0.02f, LockCanvas.INK, true);
                a.textBase(count, numX, base, 0);
                a.font(LockCanvas.sSans, 12, 0, LockCanvas.INK, true);
                a.textBase("条", numX + countW + 4, mid - a.inkMid("条"), 0);
            }
        }

        // ── 底部：电量 + 倒数日 ──
        // 电量沿用「大号数字 + 小字标签」的老口径占左起一格（20-94）；「倒数日」由横排小字改成
        // 14sp 竖排标签（列心 104），倒数日各列从 118 起一直排到右边界 340——原来右端让给了
        // 「↑ 解锁」，那行提示已按用户要求删除。每列「事项 14sp / 剩 N 日 13sp」两行，
        // 与竖版编辑排版同一套顺序与居中规则。
        // 用户要求整块再往屏幕底下靠：横线 620 → 646，最低的「电量 %」标签基线 690，
        // 离屏幕底沿只留 30（原来 76 显得悬在半空）。
        final float fy = 646;
        a.rect(L, fy, CW, 4, LockCanvas.INK);
        a.font(LockCanvas.sHeavy, 30, -0.04f, LockCanvas.INK, true);
        a.text(a.batteryText(), L, fy + 10, COL);
        a.font(LockCanvas.sSans, 10, 0, LockCanvas.INK, true);
        a.text("电量 %", L, fy + 44, COL);

        final List<FeatureDefinition.CountdownItem> shown = a.shownCountdowns();
        if (!shown.isEmpty()) {
            // 「倒数日」标签由 10sp 横排改成 14sp 竖排：横排小字跟左边 30sp 电量、右边 14sp 事项名
            // 都不成比例，竖排正好当电量与倒数日之间的一条竖向分隔。电量占 20-94，标签列心 104。
            final String vyLabel = "倒数日";
            final float labelCx = 104;
            a.font(LockCanvas.sSansBold, 14, 0, LockCanvas.INK, true);
            float ly = fy + 16;
            for (int i = 0; i < vyLabel.length(); i++) {
                final String ch = vyLabel.substring(i, i + 1);
                final float w = a.tp.measureText(ch);
                a.text(ch, labelCx - w / 2f, ly, 0);
                ly += 17;
            }
            // 底部去掉「↑ 解锁」后右端整条都能用：倒数日从竖排标签右侧起、一直排到右边界。
            final float cx0 = labelCx + 14, cw = L + CW - cx0;
            final float cell = cw / shown.size();
            for (int i = 0; i < shown.size(); i++) {
                final float cx = cx0 + cell * (i + 0.5f);
                final FeatureDefinition.CountdownItem entry = shown.get(i);
                final long days = a.daysUntil(entry);
                final String left = (days >= 0 ? "剩 " : "过 ") + Math.abs(days) + " 日";
                if (entry.item.isEmpty()) {
                    a.font(LockCanvas.sSerif, 13, 0, LockCanvas.INK, true);
                    a.textCenterFit(left, cx, fy + 30, cell - 8);
                } else {
                    a.font(LockCanvas.sSerifBold, 14, 0, LockCanvas.INK, true);
                    a.textCenterFit(entry.item, cx, fy + 20, cell - 8);
                    a.font(LockCanvas.sSerif, 13, 0, LockCanvas.INK, true);
                    a.textCenterFit(left, cx, fy + 39, cell - 8);
                }
            }
        }
    }

    // ───────────────────────── C · 1-bit 像素 ─────────────────────────
}
