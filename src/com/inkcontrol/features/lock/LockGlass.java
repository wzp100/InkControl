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

/** 「液态玻璃」样式。从 LockCanvas 拆出，画布、画笔与排版基础方法仍通过宿主 {@code a} 访问。 */
final class LockGlass {
    private final LockCanvas a;
    LockGlass(LockCanvas host) { a = host; }

    void glass() {
        // 16 级灰阶误差扩散在构建时以 720×1440 原生分辨率完成，运行时仅解码一次。
        // 源图按 360×720 逻辑坐标缩放，2x 送屏时每个点仍是一个实际像素。
        a.fill.reset();
        a.fill.setFilterBitmap(false);
        a.c.drawBitmap(GlassTexture.bitmap(), null, new RectF(0, 0, LockCanvas.W, LockCanvas.H), a.fill);

        a.font(LockCanvas.sSansBold, 76, -0.045f, LockCanvas.INK, true);
        a.textBase(a.time(), 32, 138, 296);
        a.font(LockCanvas.sSansMedium, 16, 0, LockCanvas.INK, true);
        a.text(a.fmt("M月d日") + "  " + a.weekCn(), 36, 157, 288);
        if (a.almanac() != null) {
            a.font(LockCanvas.sSans, 11, 0, LockCanvas.INK, true);
            a.text(a.almanac(), 37, 187, 286);
        }

        if (a.hasWeather()) {
            a.font(LockCanvas.sSansMedium, 12, 0.04f, LockCanvas.INK, true);
            a.text("今日天气", 54, 289, 150);
            if (a.data.weather != null) {
                a.font(LockCanvas.sSansMedium, 17, 0, LockCanvas.INK, true);
                a.textRight(a.data.weather, 304, 291, 115);
            }
            boolean weatherIcon = WeatherIcon.available(a.data.weather);
            if (weatherIcon) WeatherIcon.draw(a.c, a.data.weather, 239, 326, 64, LockCanvas.INK);
            a.font(LockCanvas.sSansBold, 58, -0.03f, LockCanvas.INK, true);
            a.textBase(a.data.temperature == null ? "—" : a.data.temperature, 52, 386, weatherIcon ? 166 : 248);
            if (a.data.low != null && a.data.high != null) {
                a.font(LockCanvas.sSans, 13, 0, LockCanvas.INK, true);
                a.text(a.data.low + " — " + a.data.high, 55, 404, 248);
            }
        } else {
            a.font(LockCanvas.sSansMedium, 12, 0.04f, LockCanvas.INK, true);
            a.text("电量", 54, 289, 150);
            a.font(LockCanvas.sSansBold, 58, -0.03f, LockCanvas.INK, true);
            a.textBase(a.data.battery < 0 ? "—" : a.data.battery + "%", 52, 386, 248);
            a.font(LockCanvas.sSans, 13, 0, LockCanvas.INK, true);
            if (a.data.charging) a.text("正在充电", 55, 404, 80);
            if (!a.data.hideNotify && a.data.notifications >= 0) {
                a.textRight(a.notificationText(), 304, 404, 145);
            }
        }

        a.font(LockCanvas.sSansMedium, 12, 0.04f, LockCanvas.INK, true);
        a.text(a.data.playing ? "正在播放" : "今日格言", 54, 502, 250);
        a.font(LockCanvas.sSansBold, 21, 0, LockCanvas.INK, true);
        String main = a.data.text != null ? a.data.text : a.data.playing ? LockCanvas.dash(a.data.track) : "留一点时间，慢慢读。";
        a.paragraph(main, 54, 532, 252, 1.3f, 2);
        String sub = a.data.track != null && !a.data.track.equals(main) ? a.data.track : a.data.subText;
        if (sub != null) {
            a.font(LockCanvas.sSans, 11, 0, LockCanvas.INK, true);
            a.paragraph(sub, 55, 599, 250, 1.3f, 1);
        }
        if (a.data.playing && a.data.duration > 0 && a.data.position >= 0) {
            // 只在原有走时/歌词更新事件重绘，不新增定时动画或连续刷屏。
            float progress = Math.max(0f, Math.min(1f, a.data.position / (float) a.data.duration));
            a.frame(55, 622, 250, 2, .5f);
            a.rect(55, 622, 250 * progress, 2, LockCanvas.INK);
        }

        if (a.hasWeather()) {
            a.font(LockCanvas.sSansMedium, 11, 0, LockCanvas.INK, true);
            a.text("电量 " + (a.data.battery < 0 ? "—" : a.data.battery + "%") + (a.data.charging ? " · 充电中" : ""), 35, 661, 170);
            if (!a.data.hideNotify && a.data.notifications >= 0) {
                a.textRight(a.notificationText(), 325, 661, 140);
            }
        }
        a.font(LockCanvas.sSansMedium, 12, .12f, LockCanvas.INK, true);
        a.textCenter("上滑解锁", 180, 691);
    }
}
