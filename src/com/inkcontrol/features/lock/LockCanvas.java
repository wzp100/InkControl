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

/**
 * 墨水屏锁屏（编辑排版 / 编辑排版-横 / 编辑排版-横-逆 / 瑞士网格 / 1-bit 像素 / 液态玻璃）的绘制器。
 * 竖版样式按样张的 360 × 720 逻辑坐标作画，整体放大到实际尺寸（A5 为 720 × 1440，正好 2 倍）。
 * 「编辑排版-横」按 720 × 360 横版逻辑坐标作画，绘制时整体旋转 90° 铺满竖屏面板：
 * 锁屏图本身就是横版画面（顶部合并日期表头 / 中右大号时间 / 底部电量与天气一行 / 左下格言 /
 * 右下通知与倒数日，横转手机阅读）；「编辑排版-横-逆」在此基础上再旋转 180°，用于反向横转阅读。
 * 横版画布高度只有 360（竖版是 720），纵向排不下竖版的三列信息带，电量与天气在横版压成两行。
 * 天气区仅在有真实数据时绘制（lock.weather 开关默认关，故默认不显示）；无数据时留空。
 * 前三种以黑白图形为主；玻璃用 16 级灰阶材质，配合清晰全刷保留明暗层次。
 */
final class LockCanvas extends View {
    static final int W = 360, H = 720;
    /** 横版样式的逻辑画布：旋转 90° 后正好铺满 720×1440 竖屏面板 */
    static final int LAND_W = 720, LAND_H = 360;
    private static final int INK = Color.BLACK, PAPER = Color.WHITE;
    /** 次级文字（英文格言等）用的中灰，墨水屏 16 级灰阶下清晰可辨 */
    private static final int GREY = Color.rgb(0x59, 0x59, 0x59);

    private static Typeface sSerif, sSerifBold, sSans, sSansMedium, sSansBold, sHeavy;

    private int style;
    private LockData data;

    // 每次 draw() 时有效
    private Canvas c;
    private final Paint fill = new Paint();
    private final Paint line = new Paint();
    private final Path path = new Path();
    private final Matrix mat = new Matrix();
    private final RectF box = new RectF();
    private final android.graphics.Rect inkRect = new android.graphics.Rect();
    private final TextPaint tp = new TextPaint();

    LockCanvas(Context context) {
        super(context);
        initFonts();
    }

    void set(int style, LockData data) {
        this.style = style;
        this.data = data;
        invalidate();
    }

    private static void initFonts() {
        if (sSerif != null) return;
        // 中文由系统回退到 Noto Serif CJK / Noto Sans CJK
        sSerif = Typeface.create("serif", Typeface.NORMAL);
        sSerifBold = Typeface.create("serif", Typeface.BOLD);
        sSans = Typeface.create("sans-serif", Typeface.NORMAL);
        sSansMedium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        sSansBold = Typeface.create("sans-serif", Typeface.BOLD);
        sHeavy = Typeface.create("sans-serif-black", Typeface.NORMAL);
    }

    /** 直接画成一张位图（调试出图、设置预览用） */
    static Bitmap render(Context context, int style, LockData data, int width, int height) {
        LockCanvas v = new LockCanvas(context);
        v.set(style, data);
        Bitmap b = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(b);
        v.paint(canvas, width, height);
        return b;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        paint(canvas, getWidth(), getHeight());
    }

    private void paint(Canvas canvas, int width, int height) {
        if (data == null || width <= 0 || height <= 0) return;
        boolean land = style == LockStyle.EDITORIAL_LAND || style == LockStyle.EDITORIAL_LAND_R;
        boolean reverse = land && reverseLand();
        float s = land ? Math.min(width / (float) LAND_H, height / (float) LAND_W)
                : Math.min(width / (float) W, height / (float) H);
        canvas.drawColor(PAPER);
        canvas.save();
        if (land) {
            if (reverse) {
                // 逆：整体旋转 270°（= -90°），相对正版相差 180°，用于手机反向横转阅读。
                canvas.translate(0, height);
                canvas.rotate(-90);
            } else {
                // 正版：横版画布旋转 90° 铺满竖屏面板：画布左沿（大时间）落到面板顶边，画布顶沿落到面板右边。
                // 竖屏面板上即得样张的横版构图；阅读时把手机逆时针横转（或头偏向右侧）。
                canvas.translate(width, 0);
                canvas.rotate(90);
            }
            canvas.scale(s, s);
            canvas.translate((height - LAND_W * s) / (2f * s), (width - LAND_H * s) / (2f * s));
        } else {
            canvas.translate((width - W * s) / 2f, (height - H * s) / 2f);
            canvas.scale(s, s);
        }
        c = canvas;
        try {
            if (style == LockStyle.GLASS) glass();
            else if (style == LockStyle.SWISS) swiss();
            else if (style == LockStyle.PIXEL) pixel();
            else if (land) editorialLand();
            else editorial();
        } finally {
            c = null;
            canvas.restore();
        }
    }

    /**
     * 横版样式是否反向（旋转 180°）。设备实机下「编辑排版-横」与「编辑排版-横-逆」
     * 共用物理样式 9（原厂只接受 0–9 的 eink_keyguard_style），「逆」在配置里以逻辑值 10 表示；
     * 调试出图可直接传入物理样式 10。
     */
    private boolean reverseLand() {
        if (style == LockStyle.EDITORIAL_LAND_R) return true;
        if (style != LockStyle.EDITORIAL_LAND) return false;
        try { return "10".equals(RuntimeConfig.value("lock.style")); }
        catch (RuntimeException ignored) { return false; }
    }

    // ───────────────────────── 基础绘制 ─────────────────────────

    private void rect(float x, float y, float w, float h, int color) {
        fill.reset();
        fill.setColor(color);
        fill.setAntiAlias(false);
        c.drawRect(x, y, x + w, y + h, fill);
    }

    private void frame(float x, float y, float w, float h, float t) {
        rect(x, y, w, t, INK);
        rect(x, y + h - t, w, t, INK);
        rect(x, y, t, h, INK);
        rect(x + w - t, y, t, h, INK);
    }

    private TextPaint font(Typeface face, float size, float spacingEm, int color, boolean aa) {
        tp.reset();
        tp.setTypeface(face);
        tp.setTextSize(size);
        tp.setLetterSpacing(spacingEm);
        tp.setColor(color);
        tp.setAntiAlias(aa);
        tp.setSubpixelText(aa);
        return tp;
    }

    /** 在 (x, top) 处画一行字（top 为字身顶端），超宽时横向压缩到 maxW。返回实际宽度 */
    private float text(String s, float x, float top, float maxW) {
        if (s == null || s.isEmpty()) return 0;
        float w = tp.measureText(s);
        if (maxW > 0 && w > maxW) {
            tp.setTextScaleX(maxW / w);
            w = maxW;
        }
        c.drawText(s, x, top - tp.ascent(), tp);
        tp.setTextScaleX(1f);
        return w;
    }

    /** 以基线定位画一行字 */
    private float textBase(String s, float x, float baseline, float maxW) {
        if (s == null || s.isEmpty()) return 0;
        float w = tp.measureText(s);
        if (maxW > 0 && w > maxW) {
            tp.setTextScaleX(maxW / w);
            w = maxW;
        }
        c.drawText(s, x, baseline, tp);
        tp.setTextScaleX(1f);
        return w;
    }

    private void textRight(String s, float right, float top, float maxW) {
        if (s == null) return;
        float w = Math.min(tp.measureText(s), maxW);
        text(s, right - w, top, maxW);
    }

    private void textCenter(String s, float cx, float top) {
        if (s == null) return;
        // 字间距会在末尾多留一个间距，居中时扣掉
        float w = tp.measureText(s) - tp.getLetterSpacing() * tp.getTextSize();
        text(s, cx - w / 2f, top, 0);
    }

    /**
     * 一格内并排多段不同字号的文字时，按基准字号量总宽、返回需要缩小的比例（≤1）。
     * 分段各带自己的字号（例如「10.29 | 9.20(农历)」里括号注释要小一号），
     * 所以不能靠 text() 的横向压缩——那会把数字压瘦变形，字号才是该缩的地方。
     */
    private float segFit(String[] parts, float[] sizes, float maxW) {
        float total = 0;
        for (int i = 0; i < parts.length; i++) {
            font(sSansBold, sizes[i], 0, INK, true);
            total += tp.measureText(parts[i]);
        }
        return (maxW > 0 && total > maxW) ? maxW / total : 1f;
    }

    /**
     * 按统一比例 scale 把多段文字从左往右排开，返回右端 x。
     *
     * 垂直方向**不能统一挂同一条基线**：分段字号不同（14sp 日期 + 10sp 括号注释），
     * 字形框是跟着字号等比缩放的，小一号那段的框整体往上收，挂同一基线就会看着
     * 像掉下去一截的角标——「9.20(农历)」的括号还有下探，更明显。
     * 所以按**字形框中线**对齐：先用第一段的字号取中线，其余段反算自己的基线。
     */
    private float segRow(String[] parts, float[] sizes, float scale, float x, float baseline) {
        font(sSansBold, sizes[0] * scale, 0, INK, true);
        final Paint.FontMetrics base = tp.getFontMetrics();
        final float center = baseline + (base.ascent + base.descent) / 2f;
        float cx = x;
        for (int i = 0; i < parts.length; i++) {
            font(sSansBold, sizes[i] * scale, 0, INK, true);
            final Paint.FontMetrics fm = tp.getFontMetrics();
            cx += textBase(parts[i], cx, center - (fm.ascent + fm.descent) / 2f, 0);
        }
        return cx;
    }

    /** 以基线居中绘制一行字，超宽按 maxW 横向压缩（数据格内居中用）。 */
    private void textCenterBase(String s, float cx, float baseline, float maxW) {
        if (s == null || s.isEmpty()) return;
        float w = tp.measureText(s) - tp.getLetterSpacing() * tp.getTextSize();
        if (maxW > 0 && w > maxW) {
            tp.setTextScaleX(maxW / w);
            w = maxW;
        }
        c.drawText(s, cx - w / 2f, baseline, tp);
        tp.setTextScaleX(1f);
    }

    /**
     * 竖版三列数据格（天气 / 气温 / 电量）：小标题与数值各自水平居中，两行作为一块在格内垂直居中。
     * 数值超宽按格宽压缩，不会串到相邻格。x/top/w/h 为格子左沿、顶沿、宽、高。
     */
    private void metricCell(String label, String value, float x, float top, float w, float h) {
        final float cy = x + w / 2f;
        font(sSerif, 14, 0.2f, INK, true);
        Paint.FontMetrics lfm = tp.getFontMetrics();
        float lh = lfm.descent - lfm.ascent;
        font(sSerif, 22, 0, INK, true);
        Paint.FontMetrics vfm = tp.getFontMetrics();
        float vh = vfm.descent - vfm.ascent;
        final float gap = 4;
        float blockTop = top + (h - (lh + gap + vh)) / 2f;
        font(sSerif, 14, 0.2f, INK, true);
        textCenterBase(label, cy, blockTop - lfm.ascent, w - 12);
        font(sSerif, 22, 0, INK, true);
        textCenterBase(value, cy, blockTop + lh + gap - vfm.ascent, w - 12);
    }

    /** 居中绘制，超宽时按 maxW 等比压缩（倒数日长事项名自适应框宽）。top 为字身顶（与 text 一致）。 */
    private void textCenterFit(String s, float cx, float top, float maxW) {
        if (s == null || s.isEmpty()) return;
        float w = tp.measureText(s) - tp.getLetterSpacing() * tp.getTextSize();
        if (maxW > 0 && w > maxW) {
            tp.setTextScaleX(maxW / w);
            w = maxW;
        }
        c.drawText(s, cx - w / 2f, top - tp.ascent(), tp);
        tp.setTextScaleX(1f);
    }

    /**
     * 当前字体字号下 s 的墨迹中线相对基线的偏移（负值表示在基线上方）。
     * 大小字混排时（例如「通知」13sp 配 26sp 的条数）靠它对齐视觉中线：
     * 同一条基线只会让大字显得高、小字显得往下掉，看上去像两块东西。
     */
    private float inkMid(String s) {
        inkRect.setEmpty();
        tp.getTextBounds(s, 0, s.length(), inkRect);
        return (inkRect.top + inkRect.bottom) / 2f;
    }

    /**
     * 一行里的「标签 + 数值」（横版信息带用：竖版按列排，横版只能压成一行）。
     * 标签比数值大一号、同样加粗，缩略图下也能一眼分出字段名与数据；
     * 数值超宽时横向压缩到 right 以内。返回这一段右端的 x。
     */
    private float pair(String label, String value, float x, float baseline, float right) {
        font(sSerifBold, 21, 0.12f, INK, true);
        float width = textBase(label, x, baseline, 0);
        float valueX = x + width + 10;
        font(sSerifBold, 18, 0, INK, true);
        return valueX + textBase(value, valueX, baseline, right > valueX ? right - valueX : 0);
    }

    /** 多行段落，lineHeight 为 CSS 式的行高（倍数）。返回高度 */
    private float paragraph(String s, float x, float top, float width, float lineHeight, int maxLines) {
        if (s == null || s.isEmpty()) return 0;
        Paint.FontMetrics fm = tp.getFontMetrics();
        float natural = fm.descent - fm.ascent;
        float add = tp.getTextSize() * lineHeight - natural;
        StaticLayout layout = StaticLayout.Builder.obtain(s, 0, s.length(), tp, (int) width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .setLineSpacing(add, 1f)
                .setMaxLines(maxLines)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build();
        c.save();
        c.translate(x, top + add / 2f);
        layout.draw(c);
        c.restore();
        return layout.getLineCount() * tp.getTextSize() * lineHeight;
    }

    // ───────────────────────── 数据格式化 ─────────────────────────

    // ───────────────────────── D · 液态玻璃 ─────────────────────────

    private void glass() {
        // 16 级灰阶误差扩散在构建时以 720×1440 原生分辨率完成，运行时仅解码一次。
        // 源图按 360×720 逻辑坐标缩放，2x 送屏时每个点仍是一个实际像素。
        fill.reset();
        fill.setFilterBitmap(false);
        c.drawBitmap(GlassTexture.bitmap(), null, new RectF(0, 0, W, H), fill);

        font(sSansBold, 76, -0.045f, INK, true);
        textBase(time(), 32, 138, 296);
        font(sSansMedium, 16, 0, INK, true);
        text(fmt("M月d日") + "  " + weekCn(), 36, 157, 288);
        if (almanac() != null) {
            font(sSans, 11, 0, INK, true);
            text(almanac(), 37, 187, 286);
        }

        if (hasWeather()) {
            font(sSansMedium, 12, 0.04f, INK, true);
            text("今日天气", 54, 289, 150);
            if (data.weather != null) {
                font(sSansMedium, 17, 0, INK, true);
                textRight(data.weather, 304, 291, 115);
            }
            boolean weatherIcon = WeatherIcon.available(data.weather);
            if (weatherIcon) WeatherIcon.draw(c, data.weather, 239, 326, 64, INK);
            font(sSansBold, 58, -0.03f, INK, true);
            textBase(data.temperature == null ? "—" : data.temperature, 52, 386, weatherIcon ? 166 : 248);
            if (data.low != null && data.high != null) {
                font(sSans, 13, 0, INK, true);
                text(data.low + " — " + data.high, 55, 404, 248);
            }
        } else {
            font(sSansMedium, 12, 0.04f, INK, true);
            text("电量", 54, 289, 150);
            font(sSansBold, 58, -0.03f, INK, true);
            textBase(data.battery < 0 ? "—" : data.battery + "%", 52, 386, 248);
            font(sSans, 13, 0, INK, true);
            if (data.charging) text("正在充电", 55, 404, 80);
            if (!data.hideNotify && data.notifications >= 0) {
                textRight(notificationText(), 304, 404, 145);
            }
        }

        font(sSansMedium, 12, 0.04f, INK, true);
        text(data.playing ? "正在播放" : "今日格言", 54, 502, 250);
        font(sSansBold, 21, 0, INK, true);
        String main = data.text != null ? data.text : data.playing ? dash(data.track) : "留一点时间，慢慢读。";
        paragraph(main, 54, 532, 252, 1.3f, 2);
        String sub = data.track != null && !data.track.equals(main) ? data.track : data.subText;
        if (sub != null) {
            font(sSans, 11, 0, INK, true);
            paragraph(sub, 55, 599, 250, 1.3f, 1);
        }
        if (data.playing && data.duration > 0 && data.position >= 0) {
            // 只在原有走时/歌词更新事件重绘，不新增定时动画或连续刷屏。
            float progress = Math.max(0f, Math.min(1f, data.position / (float) data.duration));
            frame(55, 622, 250, 2, .5f);
            rect(55, 622, 250 * progress, 2, INK);
        }

        if (hasWeather()) {
            font(sSansMedium, 11, 0, INK, true);
            text("电量 " + (data.battery < 0 ? "—" : data.battery + "%") + (data.charging ? " · 充电中" : ""), 35, 661, 170);
            if (!data.hideNotify && data.notifications >= 0) {
                textRight(notificationText(), 325, 661, 140);
            }
        }
        font(sSansMedium, 12, .12f, INK, true);
        textCenter("上滑解锁", 180, 691);
    }

    private static final String[] CN = {"〇", "一", "二", "三", "四", "五", "六", "七", "八", "九"};
    private static final String[] WEEK_CN = {"日", "一", "二", "三", "四", "五", "六"};

    private String fmt(String pattern) {
        return new SimpleDateFormat(pattern, Locale.US).format(data.now.getTime());
    }

    private String hour() {
        return fmt(data.is24h ? "HH" : "hh");
    }

    private String time() {
        return hour() + ":" + fmt("mm");
    }

    private static String chineseNumber(int n) {
        if (n < 10) return CN[n];
        if (n == 10) return "十";
        if (n < 20) return "十" + CN[n % 10];
        return CN[n / 10] + "十" + (n % 10 == 0 ? "" : CN[n % 10]);
    }

    private String chineseDate() {
        return chineseNumber(data.now.get(Calendar.MONTH) + 1) + "月"
                + chineseNumber(data.now.get(Calendar.DAY_OF_MONTH)) + "日";
    }

    private String weekCn() {
        return "星期" + WEEK_CN[data.now.get(Calendar.DAY_OF_WEEK) - 1];
    }

    /** 农历月份日期（数字版，如 9.18）：表头是「公历数字 | 农历数字」并排，写成「九月十八」既长又不齐。 */
    private String lunarNumeric() {
        final int[] lunar = LunarCalendar.toLunar(data.now.get(Calendar.YEAR),
                data.now.get(Calendar.MONTH) + 1, data.now.get(Calendar.DAY_OF_MONTH));
        return lunar == null ? "—" : lunar[1] + "." + lunar[2];
    }

    /** 节日/节气优先，其次农历 */
    private String almanac() {
        if (data.festival != null) return data.festival;
        return data.lunar;
    }

    private static String dash(String s) {
        return s == null || s.isEmpty() ? "—" : s;
    }

    /** 去掉温度单位（瑞士网格里只要数字） */
    private static String strip(String temp) {
        return temp == null ? "—" : temp.replace("°F", "").replace("°", "");
    }

    private String batteryText() {
        return data.battery < 0 ? "—" : String.valueOf(data.battery);
    }

    private String notificationText() {
        return data.notifications < 0 ? "—" : data.notifications > 0
                ? data.notificationLabel + " " + data.notifications + " 条" : "暂无通知";
    }

    private boolean hasWeather() {
        return !TextUtils.isEmpty(data.weather) || !TextUtils.isEmpty(data.temperature)
                || !TextUtils.isEmpty(data.high) || !TextUtils.isEmpty(data.low);
    }

    // ───────────────────────── A · 编辑排版 ─────────────────────────

    private void editorial() {
        final float L = 24, R = 336, CW = R - L;

        // ── 顶部表头：换用「编辑排版-横」同一串信息，但竖版版心只有 312 宽（横版 720），
        // 一行塞不下 26 号字，拆两行：主行「第N日 | 中文日期·星期」18 号，
        // 副行「农历 九月十六」16 号；无农历日期时只剩主行，分隔线下移量随之变化。
        // 字号也按实际宽度兜底：主行超宽就等比缩（下限 13），保证不被裁切。
        String head = "第" + data.now.get(Calendar.DAY_OF_YEAR) + "日 | " + chineseDate() + "·" + weekCn();
        float hSize = 18;
        font(sSerif, hSize, 0.04f, INK, true);
        float hw = tp.measureText(head);
        if (hw > CW) {
            hSize = Math.max(13f, hSize * CW / hw);
            font(sSerif, hSize, 0.04f, INK, true);
        }
        textBase(head, L, 34, CW);
        float headBottom = 34;
        if (almanac() != null) {
            font(sSerif, 18, 0.06f, INK, true);
            headBottom = 62;
            textBase("农历 " + almanac(), L, headBottom, CW);
        }
        rect(L, headBottom + 10, CW, 1, INK);

        // 大号时间：衬线体横向收窄铺满版心，接近样张里 Instrument Serif 的瘦长比例
        // 日期行上移到表头后下方留白收紧，基线 200 → 194
        font(sSerif, 136, -0.03f, INK, true);
        float tw = tp.measureText(time());
        tp.setTextScaleX(Math.max(0.7f, Math.min(1f, (CW + 4) / tw)));
        c.drawText(time(), L - 4, 194, tp);
        tp.setTextScaleX(1f);

        // 原「中文日期 + 星期·农历」那一行已并入表头，这里不再重复；表格上移补位
        float gy = 234;
        rect(L, gy, CW, 1, INK);
        rect(L, gy + 66, CW, 1, INK);
        if (hasWeather()) {
            rect(L + 104, gy, 1, 66, INK);
            rect(L + 208, gy, 1, 66, INK);
            // 三个格子等宽 104：小标题与数值都在各自格内居中（2026-10-25 用户要求，原来靠左沿排）
            String temp = data.high != null ? data.high + " / " + data.low : dash(data.temperature);
            metricCell("天气", dash(data.weather), L, gy, 104, 66);
            metricCell("气温", temp, L + 104, gy, 104, 66);
            metricCell("电量", data.battery < 0 ? "—" : data.battery + "%", L + 208, gy, 104, 66);
        } else {
            font(sSerif, 14, 0.2f, INK, true);
            textBase("电量", L, gy + 23, 0);
            font(sSerif, 22, 0, INK, true);
            String battery = data.battery < 0 ? "—" : data.battery + "%";
            textBase(battery + (data.charging ? " · 充电中" : ""), L, gy + 48, CW - 150);
            if (!data.hideNotify && data.notifications > 0) {
                font(sSerif, 14, 0, INK, true);
                textRight(notificationText(), R, gy + 48, 160);
            }
        }

        // 正在播放 / 今日格言（整体上移 50，承接上移后的表格）
        float py = 334;
        font(sSerif, 10, 0.3f, INK, true);
        text(data.playing ? "正在播放" : "今日格言", L, py, 0);
        font(sSerif, 64, 0, INK, true);
        textBase("“", L - 2, py + 62, 0);
        float ph = 0;
        if (data.text != null) {
            font(sSerifBold, 21, 0, INK, true);
            ph = paragraph(data.text, L, py + 50, CW, 1.55f, 3);
        }
        if (data.track != null) {
            font(sSerif, 12, 0.08f, INK, true);
            text("— " + data.track, L, py + 50 + ph + 10, CW);
        } else if (data.subText != null) {
            font(sSerif, 12, 0.02f, INK, true);
            paragraph(data.subText, L, py + 50 + ph + 8, CW, 1.5f, 3);
        }

        // 底部：倒数日（通知条上方）→ 通知提醒条 → 上滑解锁
        // 倒数日沿用横版那套呈现：上方「倒数日」小标题 + 细方框，框内每组的「事项名 / 剩 N 日」两行。
        // 竖版比横版宽得多，横版竖排的清单在这里横过来排：一/二/三组按格均分、各自在格内居中，
        // 组间不画竖线（用户 2026-10-25：竖线分割难看，两组时也不好分配）；只有一组时正好落在正中。
        // 「显示」关闭的组不绘制；无任何可见组时整块不出现，通知条位置保持不变。
        List<FeatureDefinition.CountdownItem> shown = shownCountdowns();
        if (!shown.isEmpty()) {
            font(sSerifBold, 18, 0.12f, INK, true);
            textCenter("倒数日", W / 2f, 511);
            final float CY = 536, CH = 64;
            frame(L, CY, CW, CH, 1);
            int n = Math.min(shown.size(), FeatureDefinition.COUNTDOWNS_MAX);
            final float cellW = CW / n;
            final float lineH = 18, gapIn = 4;
            for (int i = 0; i < n; i++) {
                FeatureDefinition.CountdownItem entry = shown.get(i);
                float cx = L + cellW * (i + 0.5f);
                long days = daysUntil(entry);
                String left = (days >= 0 ? "剩 " : "过 ") + Math.abs(days) + " 日";
                if (!entry.item.isEmpty()) {
                    float top = CY + (CH - (lineH * 2 + gapIn)) / 2f;
                    font(sSerifBold, 15, 0, INK, true);
                    textCenterFit(entry.item, cx, top, cellW - 14);
                    font(sSerif, 13, 0, INK, true);
                    textCenterFit(left, cx, top + lineH + gapIn, cellW - 14);
                } else {
                    font(sSerif, 13, 0, INK, true);
                    textCenterFit(left, cx, CY + (CH - 13) / 2f + 1, cellW - 14);
                }
            }
        }

        float hintTop = 671;
        if (data.extMode) {
            // 「显示设备状态」开着：同一块位置（613 起、高 42）把通知换成四项开关状态
            extBar(L, 613, CW, 42);
        } else if (!data.hideNotify) {
            if (data.notifications > 0) {
                frame(L, 613, CW, 42, 1);
                font(sSerifBold, 14, 0, INK, true);
                float w = textBase(data.notificationLabel, L + 14, 639, 0);
                font(sSerif, 14, 0, INK, true);
                textBase("　" + data.notifications + " 条", L + 14 + w, 639, CW - 40);
            } else if (data.notifications == 0) {
                frame(L, 613, CW, 42, 1);
                font(sSerif, 14, 0, INK, true);
                textBase("暂无新消息", L + 14, 639, 0);
            }
        }
        rect(L, hintTop, CW, 1, INK);
        font(sSerif, 11, 0.6f, INK, true);
        textCenter("上滑解锁", W / 2f, hintTop + 10);
    }

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
    private void extBar(float x, float top, float w, float h) {
        List<Integer> pick = extPicks();
        final int n = pick.size();
        if (n == 0) return;
        final boolean text = textLayout();
        frame(x, top, w, h, 1);
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
    private List<Integer> extPicks() {
        List<Integer> pick = new ArrayList<>();
        if (data.showWifi) pick.add(0);
        if (data.showBt) pick.add(1);
        if (data.showAudio) pick.add(2);
        if (data.showAirplane) pick.add(3);
        return pick;
    }

    /** 该项当前状态的白话值 */
    private String extValue(int index) {
        switch (index) {
            case 0: return data.wifi ? "开" : "关";
            case 1: return data.bt ? "开" : "关";
            case 2: return data.silent ? "静音" : "有声";
            default: return data.airplane ? "开" : "关";
        }
    }

    /** 图标版还是文字版：auto 跟随样式，瑞士网格是纯文字排版，固定用文字版 */
    private boolean textLayout() {
        if ("text".equals(data.extLayout)) return true;
        if ("icon".equals(data.extLayout)) return false;
        return style == LockStyle.SWISS;
    }

    /** 左半部分（图标墨迹 / 类型名）与右半部分（状态值）之间的间隔；文字版要给竖线留位 */
    private static float extGap(boolean text) { return text ? 11 : 4; }

    /** 左半部分的宽度：图标版的图标墨迹宽，文字版的类型名宽 */
    private float extLeadWidth(int index, boolean text) {
        if (!text) return extIconWidth(EXT_TYPES[index]);
        font(sSerifBold, 13, 0, INK, true);
        return tp.measureText(EXT_NAMES[index]);
    }

    /** 右半部分（状态值）的宽度 */
    private float extTailWidth(int index, boolean text) {
        font(sSerifBold, 13, 0, INK, true);
        return tp.measureText(extValue(index));
    }

    /** 图标墨迹在目标高度下的实际宽度（逻辑像素） */
    private float extIconWidth(int type) {
        float[] ink = INK_BOX[type - 1];
        return (ink[2] - ink[0]) * (INK_H[type - 1] / (ink[3] - ink[1]));
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
            font(sSerifBold, 13, 0, INK, true);
            textCenterBase(extValue(index), x + cw / 2f, top + ch / 2f + 4.5f, cw - 6);
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
            font(sSerifBold, 13, 0, INK, true);
            c.drawText(EXT_NAMES[index], start, baseline, tp);
            // 1px 竖线：与文字墨迹同高，落在两段的正中间，1-bit 下比字形「|」干净
            rect(start + lead + (extGap(true) - 1) / 2f, textMid - 5f, 1, 10, INK);
            c.drawText(value, start + lead + extGap(true), baseline, tp);
            return;
        }
        final int type = EXT_TYPES[index];
        float[] ink = INK_BOX[type - 1];
        float k = INK_H[type - 1] / (ink[3] - ink[1]);
        // 把图标的墨迹左沿/中线接到 start、textMid 上，方框位置按墨迹反推
        drawIcon(type, start - ink[0] * k, textMid - (ink[1] + ink[3]) / 2f * k, 16 * k);
        font(sSerifBold, 13, 0, INK, true);
        c.drawText(value, start + lead + extGap(false), baseline, tp);
    }

    /** 每个图标在 16 空间里墨迹的实际外框 {左, 上, 右, 下}：用来把墨迹对齐到文字，而不是对齐 16 方框 */
    private static final float[][] INK_BOX = {
            {0.10f, 4.70f, 15.90f, 14.55f},   // IC_WIFI（三段弧 + 圆点）
            {4.20f, 2.60f, 11.80f, 13.40f},   // IC_BT
            {1.20f, 1.60f, 14.60f, 14.40f},   // IC_AUDIO（喇叭 + 右侧两道声波）
            {0.50f, 2.00f, 15.50f, 14.20f},   // IC_AIRPLANE（翼展 15 × 机身长 12.2）
    };

    /** 图标的目标墨迹高度：细线图标按 11.5 与文字等重；实心图标视觉重量小，要摸到 12.8～13.2 才不显小 */
    private static final float[] INK_H = {11.5f, 11.5f, 12.8f, 13.2f};

    private static final int IC_WIFI = 1, IC_BT = 2, IC_AUDIO = 3, IC_AIRPLANE = 4;

    /** 设备状态四项，顺序即界面上的显示顺序 */
    private static final int[] EXT_TYPES = {IC_WIFI, IC_BT, IC_AUDIO, IC_AIRPLANE};
    private static final String[] EXT_NAMES = {"无线", "蓝牙", "声音", "飞行"};

    /**
     * 16×16 逻辑框内的单色图标：x/y 为左上角，s 为边长，按比例缩放。
     * 全部现画矢量，不打包 PNG/SVG —— 墨水屏只有黑白，矢量在任意尺寸都不糊，也免得为外部图标库背许可证。
     */
    private void drawIcon(int type, float x, float y, float s) {
        final float k = s / 16f;
        line.reset();
        line.setAntiAlias(true);
        line.setStyle(Paint.Style.STROKE);
        line.setColor(INK);
        line.setStrokeWidth(1.5f * k);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        fill.reset();
        fill.setAntiAlias(true);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(INK);
        if (type == IC_WIFI) {
            // 三段同心圆弧（从 225° 扫 90°，即正上方那段）+ 中心实心圆点
            path.reset();
            arc(8, 12.6f, 7.9f); arc(8, 12.6f, 5.4f); arc(8, 12.6f, 2.9f);
            stamp(x, y, k); c.drawPath(path, line);
            path.reset(); path.addCircle(8, 13.4f, 1.15f, Path.Direction.CW);
            stamp(x, y, k); c.drawPath(path, fill);
            return;
        }
        path.reset();
        if (type == IC_BT) {
            // 蓝牙符号就是卢恩字母 Bjarkan：一条竖线 + 两组交叉斜线
            path.moveTo(8, 2.6f); path.lineTo(8, 13.4f);
            path.moveTo(8, 2.6f); path.lineTo(11.8f, 5.7f); path.lineTo(4.2f, 10.3f);
            path.moveTo(8, 13.4f); path.lineTo(11.8f, 10.3f); path.lineTo(4.2f, 5.7f);
        } else if (type == IC_AUDIO) {
            // 实心喇叭（正面 1.2-4.2、锥口 8.6），声波弧在右侧 3 点钟方向（见下方 arc 调用）
            path.moveTo(1.2f, 5.2f); path.lineTo(4.2f, 5.2f); path.lineTo(8.6f, 1.6f);
            path.lineTo(8.6f, 14.4f); path.lineTo(4.2f, 10.8f); path.lineTo(1.2f, 10.8f);
            path.close();
        } else if (type == IC_AIRPLANE) {
            // 俯视飞机：机头朝上。三条硬约束——翼展（15）要明显大于机身长（12.2），否则整块接近正方形，
            // 在 1-bit 下只剩「十字」；翼厚 2.2 别低于 2，薄了会被量化成一条线；平尾只有翼展一半宽。
            float[] pts = {8.0f, 2.0f, 9.0f, 5.4f, 15.5f, 7.0f, 15.5f, 9.2f, 9.0f, 8.0f,
                    9.0f, 10.8f, 11.3f, 12.2f, 11.3f, 13.4f, 9.0f, 12.8f, 9.0f, 14.2f,
                    7.0f, 14.2f, 7.0f, 12.8f, 4.7f, 13.4f, 4.7f, 12.2f, 7.0f, 10.8f,
                    7.0f, 8.0f, 0.5f, 9.2f, 0.5f, 7.0f, 7.0f, 5.4f};
            path.moveTo(pts[0], pts[1]);
            for (int i = 2; i < pts.length; i += 2) path.lineTo(pts[i], pts[i + 1]);
            path.close();
        } else return;
        stamp(x, y, k);
        c.drawPath(path, type == IC_BT ? line : fill);
        if (type == IC_AUDIO) {                 // 喇叭右侧两道声波弧（315° 起扫 90° = 正右半圆）
            path.reset();
            arc(9.0f, 8.0f, 3.2f, -45); arc(9.0f, 8.0f, 5.6f, -45);
            stamp(x, y, k);
            c.drawPath(path, line);
        }
    }

    /** 把 16×16 逻辑坐标下的 path 放大 k 倍并平移到 (x, y) */
    private void stamp(float x, float y, float k) {
        mat.reset();
        mat.setScale(k, k);
        mat.postTranslate(x, y);
        path.transform(mat);
    }

    /** 以 (cx, cy) 为圆心、r 为半径，追加正上方 90°（225°→315°）的圆弧到当前 path（wifi 用） */
    private void arc(float cx, float cy, float r) { arc(cx, cy, r, 225); }

    /** 同上，可指定起始角度。Android 的 0° 在 3 点钟方向、顺时针为正：喇叭声波用 -45（315°）扫出右半圆 */
    private void arc(float cx, float cy, float r, float start) {
        box.set(cx - r, cy - r, cx + r, cy + r);
        path.addArc(box, start, 90);
    }

    // ───────────────────────── A' · 编辑排版-横（横版构图） ─────────────────────────

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
    private void editorialLand() {
        final boolean rev = reverseLand();
        // 竖排解锁列占 16 宽 + 左右各 12 边距。正版在画布右端（692-708），内容右界 R_END 退到 668 让位；
        // 逆版在画布左端（12-28），内容右界仍是 700——两版共用同一份坐标，只让「内容右界」跟着版本走。
        final float R_END = rev ? 700 : 668;
        final float LX = 42;                      // 左栏左沿（样张：表头/方框/格言均锚定此列）
        final float QW = 375;                     // 格言栏宽（x 42-417；横版下方不够高，右侧带整块左移让给电量/天气）
        final float BX = R_END - 155;              // 通知/状态框左沿（贴着电量带右侧，右缘与倒数日框对齐）

        // ── 顶部：合并表头（第N日 | 中文日期·星期 | 农历 | 农历日期；y 参考样张 ≈60）──
        // 「|」小间隔分隔；农历日期缺失时只保留「第 N 日 | 中文日期·星期」。
        String head = "第" + data.now.get(Calendar.DAY_OF_YEAR) + "日 | " + chineseDate() + "·" + weekCn();
        if (almanac() != null) head += " | 农历 | " + almanac();
        font(sSerif, 26, 0.04f, INK, true);
        float hw = textBase(head, LX, 60, R_END - LX - 18);
        rect(LX, 70, hw, 1, INK);

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
        final String label = data.charging ? "充电中" : "电量";
        final String val = data.battery < 0 ? "—" : data.battery + "%";
        final float bw = BATT_R2 - BATT_X;
        final float lSize = 22;
        font(sSerif, lSize, 0.04f, INK, true);
        float lw = tp.measureText(label);
        if (lw > bw) font(sSerif, lSize * bw / lw, 0.04f, INK, true);
        textBase(label, BATT_X, 290, 0);
        font(sSerifBold, 30, -0.02f, INK, true);
        textBase(val, BATT_X, 322, bw);

        // ── 电量正下方：天气行（仅原厂控件有真实数据时绘制；无数据整行不留空）──
        // 竖版把「天气 / 气温 / 电量」拆成三列，横版没有纵向空间，压成一行：
        // 「天气 阴 26°/18°」——只留「天气」一个标签，天气与高低温同字号顺排，
        // 不再单列「气温」（两个标签太占横向空间，数据本身已经带度数符号）。
        // 基线 348，descender 到 353，距画布底沿还有 7px（旧版排到 y=392，整块被裁掉）。
        if (hasWeather()) {
            String temp = data.high != null ? data.high + "/" + data.low : data.temperature;
            String value = dash(data.weather);
            if (!TextUtils.isEmpty(temp)) value += " " + temp;
            pair("天气", value, BATT_X, 348, WEA_R2);
        }

        // ── 左下：中英格言（整体下沉；中文加粗，英文同电量数据加黑加粗）──
        float my = 282;
        if (data.text != null) {
            font(sSerifBold, 16, 0, INK, true);
            my += paragraph(data.text, LX, my, QW, 1.5f, 2) + 6;
        }
        if (data.track != null) {
            font(sSerifBold, 12, 0.04f, INK, true);
            text("— " + data.track, LX, my, QW);
        } else if (data.subText != null) {
            font(sSerifBold, 10.5f, 0, INK, true);
            paragraph(data.subText, LX, my, QW, 1.35f, 2);
        }

        // ── 通知框 / 设备状态框（白底细框黑字；左移贴着电量带，右缘与倒数日框对齐）──
        // 原来框在右下角 x605、只有 95 宽，通知挤在边缘且离电量一整条空白。左移并加宽到 155 后：
        // 通知能居中留白，设备状态也能靠 extBar 的 2×2 降级塞下四项。右缘跟随内容右界 R_END
        // （正版 668、逆版 700），正版要给它右边的竖排解锁列让出 24。
        // 高度 40 → 48、框顶 280 → 272（框底 320，仍压在天气行字身顶 323 之上）。
        final float BH = 48;
        final float BW = 155;                          // 外框宽（左沿 BX，右缘 R_END）
        final float boxY = 272;                        // 框顶（水平带上移，与电量/格言带居中对齐）
        if (data.extMode) {
            // 「显示设备状态」：同一块位置改画四项开关状态（与竖版同一套图标与文字）
            extBar(BX, boxY, BW, BH);
        } else if (!data.hideNotify) {
            final float bBase = boxY + BH / 2f + 5f;   // 框内文字基线（垂直居中）
            if (data.notifications > 0) {
                frame(BX, boxY, BW, BH, 1);
                String rest = "　" + data.notifications + " 条";
                font(sSerifBold, 15, 0, INK, true);
                float w = tp.measureText(data.notificationLabel);
                font(sSerif, 15, 0, INK, true);
                float start = BX + (BW - (w + tp.measureText(rest))) / 2f;
                font(sSerifBold, 15, 0, INK, true);
                textBase(data.notificationLabel, start, bBase, 0);
                font(sSerif, 15, 0, INK, true);
                textBase(rest, start + w, bBase, 0);
            } else if (data.notifications == 0) {
                frame(BX, boxY, BW, BH, 1);
                font(sSerif, 15, 0, INK, true);
                textCenterBase("暂无新消息", BX + BW / 2f, bBase, BW - 24);
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
        String t = time();                            // "HH:mm"（12 小时制同为两位）
        float tSize = (T_FH - T_PAD * 2) / 0.7f;      // 初取字号：cap 填满框高
        font(sSerif, tSize, 0f, INK, true);
        float total = pairGap * 2 + colonGap * 2;
        for (int i = 0; i < t.length(); i++) total += tp.measureText(t, i, i + 1);
        if (total + T_PAD * 2 > T_MAXW) {             // 过宽则缩字号，框宽封顶 T_MAXW
            tSize *= (T_MAXW - T_PAD * 2) / total;
            font(sSerif, tSize, 0f, INK, true);
            total = pairGap * 2 + colonGap * 2;
            for (int i = 0; i < t.length(); i++) total += tp.measureText(t, i, i + 1);
        }
        float T_BASE = T_FY + (T_FH + tSize * 0.7f) / 2f;   // 数字基线（框内垂直居中）
        final float T_FW = Math.min(total + T_PAD * 2, T_MAXW);
        frame(T_FX, T_FY, T_FW, T_FH, 1);             // 细方框（先画框，数字后画在上层）
        float tx = T_FX + T_PAD;
        for (int i = 0; i < t.length(); i++) {
            String ch = String.valueOf(t.charAt(i));
            c.drawText(ch, tx, T_BASE, tp);
            tx += tp.measureText(ch);
            if (i == 0 || i == 3) tx += pairGap;    // 对内间隔（第 1、4 字符之后）
            else if (i == 1 || i == 2) tx += colonGap;  // 冒号两侧
        }

        // ── 时间右侧：倒数日框（事项+日期，最多 3 组；框收窄）──
        // 横排「倒数日」标签置于框子上方（居中于框 X 中心）；框内每组两行（事项名 / 剩 N 日），
        // 事项为空只画天数行；整体按框 Y 轴中心居中：1 组居中，2/3 组以固定组间距排布后整体居中。
        // 「显示」开关关闭的组不绘制；农历组的目标日按所选农历年/月/日换算公历后再算天数。
        List<FeatureDefinition.CountdownItem> shown = shownCountdowns();
        if (!shown.isEmpty()) {
            final float FW = 75, FX = R_END - FW;      // 框靠内容右界（与时间方框右缘错开 20）
            final float FY = 90, FH = 155;            // 框 y 90-245（拉高至与时间方框同高）
            frame(FX, FY, FW, FH, 1);
            float cx = FX + FW / 2f;
            font(sSerifBold, 15, 0, INK, true);
            textCenter("倒数日", cx, 73);              // 横排标签在框子上方
            int n = Math.min(shown.size(), FeatureDefinition.COUNTDOWNS_MAX);
            final float lineH = 17, gapIn = 3;        // 组内行高、行距
            final float pairH = lineH * 2 + gapIn;    // 一组（两行）的高度
            final float gapGroup = n >= 3 ? 6 : 16;  // 组间距（框高 125：3 组时需收紧）
            float top = FY + (FH - (n * pairH + (n - 1) * gapGroup)) / 2f;   // 整体垂直居中
            for (int i = 0; i < n; i++) {
                FeatureDefinition.CountdownItem entry = shown.get(i);
                String item = entry.item;
                long days = daysUntil(entry);
                String left = (days >= 0 ? "剩 " : "过 ") + Math.abs(days) + " 日";
                if (!item.isEmpty()) {
                    font(sSerifBold, 15, 0, INK, true);
                    textCenterFit(item, cx, top, FW - 10);
                    font(sSerif, 13, 0, INK, true);
                    textCenterFit(left, cx, top + lineH + gapIn, FW - 10);
                } else {
                    font(sSerif, 13, 0, INK, true);
                    textCenterFit(left, cx, top + (lineH - 13) / 2f + 2, FW - 10);
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
        font(sSerifBold, 16, 0, INK, true);
        String arrow = rev ? "\u2192" : "\u2190";
        float uw = Math.max(tp.measureText(arrow), tp.measureText("上"));
        final float VX = rev ? 12 : 720 - 12 - uw;
        text(arrow, VX, 123f, 0f);       // 上表针
        textVertical("上滑解锁", VX, 141, 18);
        text(arrow, VX, 213f, 0f);       // 下表针
    }

    /** 勾选了「显示」的倒数日组（竖版横版共用；最多 COUNTDOWNS_MAX 组，已在配置层排好序）。 */
    private List<FeatureDefinition.CountdownItem> shownCountdowns() {
        List<FeatureDefinition.CountdownItem> shown = new ArrayList<>();
        if (data.countdowns != null)
            for (FeatureDefinition.CountdownItem it : data.countdowns)
                if (it.shown && shown.size() < FeatureDefinition.COUNTDOWNS_MAX) shown.add(it);
        return shown;
    }

    /**
     * 倒数日天数差：距目标日还有几天，负值表示已过。公历日直接计算；农历日先把所选农历
     * 年/月/日换算为当年公历日（LunarCalendar），再走同一套差值。
     * 农历倒数扩展点：农历目标日→公历日的换算已在 core 层实现，此处只做差值。
     */
    private long daysUntil(FeatureDefinition.CountdownItem item) {
        Calendar target = FeatureDefinition.targetDate(item.date, item.lunar);
        if (target == null) return 0;
        Calendar today = (Calendar) data.now.clone();
        today.set(Calendar.HOUR_OF_DAY, 0);
        today.set(Calendar.MINUTE, 0);
        today.set(Calendar.SECOND, 0);
        today.set(Calendar.MILLISECOND, 0);
        return (target.getTimeInMillis() - today.getTimeInMillis()) / 86400000L;
    }

    /** 竖排文字：逐字自上而下绘制。top 为字身顶端（与 text() 一致，避免与箭头混用时间距不均）。 */
    private void textVertical(String s, float x, float top, float step) {
        for (int i = 0; i < s.length(); i++) {
            c.drawText(s, i, i + 1, x, top + i * step - tp.ascent(), tp);
        }
    }

    // ───────────────────────── B · 瑞士网格 ─────────────────────────

    private void swiss() {
        final float L = 20, CW = 320, COL = 74, GAP = 8;

        // 表头固定三列，字号从 12 提到 14（用户要求放大）：
        //   周三 第44周 / 10.29 | 9.20(农历) / 2026年 | 第302天
        // 三格等宽（各 101.3），但「2026年 | 第302天」这种 14sp 要 107 宽、塞不进，
        // 所以先按 14sp 量一遍取三格里最小的比例，**统一缩放**：只缩塞不下的那一格会让
        // 表头字号参差，瑞士网格最忌讳这个。段内不再用横向压缩（会把数字压瘦变形）。
        // 农历日期的「(农历)」注释比日期小一号（12sp vs 14sp），但不能再小——
        // 原来按 10sp 排，1-bit 下比日期矮一截，看着像掉下去的角标、不像同一行。
        final int dow = data.now.get(Calendar.DAY_OF_WEEK) - 1;
        final String[][] top = {
                {"周" + WEEK_CN[dow], "  第" + data.now.get(Calendar.WEEK_OF_YEAR) + "周"},
                {fmt("MM.dd"), " | ", lunarNumeric(), "(农历)"},
                {fmt("yyyy") + "年", " | ", "第" + data.now.get(Calendar.DAY_OF_YEAR) + "天"}};
        final float[][] topSz = {{14, 14}, {14, 14, 14, 12}, {14, 14, 14}};
        final float col = (CW - 2 * GAP) / 3f;
        float topScale = 1f;
        for (int i = 0; i < top.length; i++) topScale = Math.min(topScale, segFit(top[i], topSz[i], col));
        // 基线定位：segRow 内部按字形框中线对齐（小一号的「(农历)」不会掉到日期下面）。
        for (int i = 0; i < top.length; i++) {
            final float x = L + i * (col + GAP);
            rect(x, 22, col, 4, INK);
            segRow(top[i], topSz[i], topScale, x, 46);
        }

        // 两行叠放的特大时间。184sp 的数字墨迹实测 125 高（数字没有降部，墨迹下沿落在基线上）。
        // 用户口径：横向要松、纵向要贴，两行的间距收到原来的 1/3。上一版基线 188/360 的墨迹间隙
        // 实测 47（设备像素 94），现在基线 188/329、间隙 16，两行归成一个整块。
        // 注意别按 0.77×字号去估墨迹高——那是黑体上沿的粗算，数字实际只有 0.68 倍。
        font(sHeavy, 184, 0.06f, INK, true);
        textBase(hour(), L - 6, 188, 290);
        textBase(fmt("mm"), L - 6, 329, 290);

        // 右缘竖排（第 N 天 / 空槽 / 第 N 周）已按用户要求整列删除：这两项现在跟在表头
        // 第一、三格的右半边，整块右缘留白。

        // 天气块：不管有没有数据都占住 352-428 这一段（用户要求「留相应的空位即可」）。
        // 原来没天气时格言直接顶到 376，天气数据一回来整摞内容就往下跳一格；现在位置恒定，
        // 天气关着时这块就是留白。模块设置里没开天气显示，所以这条路径是常态、不是异常。
        // 整块比上一版上移 24：格言正文加英文译文的底边离设备状态带只有 28（实机量到的），
        // 而预览样张里格言只有一两行、显得远；把天气留位和格言线一起上提，把这点空间让给下方。
        final float by = 352;
        if (hasWeather()) {
            boolean range = data.high != null && data.low != null;
            font(sSansBold, 10, 0.12f, INK, true);
            text("WEATHER", L + 16, by + 14, 0);
            text(range ? "HI / LO" : "TEMP", L + 164, by + 14, 0);
            font(sSansMedium, 28, 0, INK, true);
            text(dash(data.weather), L + 16, by + 32, 140);
            font(sHeavy, 28, -0.03f, INK, true);
            text(range ? strip(data.high) + " / " + strip(data.low) : dash(data.temperature), L + 164, by + 32, 140);
        }
        final float ny = 428;
        rect(L, ny, CW, 1, INK);
        font(sSansBold, 10, 0.12f, INK, true);
        text(data.playing ? "NOW" : "DAILY", L, ny + 9, COL);
        text(data.playing ? "PLAYING" : "QUOTE", L, ny + 22, COL);
        float tx = L + COL + GAP, tw = CW - COL - GAP;
        // 天气块固定留位后格言恒为 2 行：行数一变，下面整摞（通知带、底部）的位置就跟着变。
        final int quoteLines = 2;
        float th = 0, sh = 0;
        if (data.text != null) {
            font(sSansBold, 17, 0, INK, true);
            th = paragraph(data.text, tx, ny + 8, tw, 1.45f, quoteLines);
        }
        // 播放媒体时在格言下面补一行当前曲目（曲名 · 歌手），与上游瑞士风格一致：
        // 左边那两行 NOW PLAYING / DAILY QUOTE 只是栏目标签，曲名必须单独成行才读得到。
        // 12sp、前面一个长破折号与格言正文拉开距离；单行显示（超宽横向压缩），不许换行顶到
        // 下面的设备状态带。
        if (data.track != null) {
            font(sSans, 12, 0.01f, INK, true);
            text("— " + data.track, tx, ny + 8 + th + 7, tw);
            sh = 18;
        } else if (data.subText != null) {
            font(sSans, 12, 0, INK, true);
            sh = paragraph(data.subText, tx, ny + 8 + th + 4, tw, 1.4f, quoteLines);
        }

        // ── 通知带 / 设备状态带：底部「电量 + 倒数日」正上方一条（1px 分隔线 + 同一基线的一行数据）──
        // 位置用固定值而不是「跟着格言底边走」：格言正文 2 行 + 英文译文 2 行时块底会落到 523，
        // 带子若跟着它走就只剩 26 的空白，实机看着贴着（预览样张格言只有一两行，所以显得远）。
        // 现在带子顶固定在 586（基线 616、文字墨迹底 619），格言块底到带子留 63，
        // 带子到 646 那根 4px 横线还有 27。钳位 586-594 只是安全阀，格言异常长时最多下移 8，
        // 再往下就会蹭到 646 的横线。
        final float quoteBottom = ny + 8 + th + (th > 0 ? 4 : 0) + sh;
        final float bandTop = Math.max(586, Math.min(594, quoteBottom + 40));
        final boolean bandExt = data.extMode;
        final boolean bandNotify = !bandExt && !data.hideNotify;
        if (bandExt || bandNotify) {
            rect(L, bandTop, CW, 1, INK);
            final float base = bandTop + 30;
            if (bandExt) {
                // 四项均分整条 320（原来按 74+8 的格），类型与状态同字号、同一条基线，
                // 中间一条 1px 竖线画成「无线|开」。用户要求再放大一点：15sp 在 320 宽的带子里
                // 和下面 30sp 的电量大数字比还是像注脚，目标字号定 17sp。
                // 但 4 项均分时最宽的「声音+静音」是 34+5+1+5+34 = 79，正好顶满 80 的格子、
                // 与邻项只隔 1px（实机看着像「静音飞行」连在一起）；所以先量一遍，塞不下就整行退 16sp。
                List<Integer> pick = extPicks();
                final float cell = CW / pick.size();
                float fs = 17;
                for (Integer idx : pick) {
                    font(sSans, fs, 0, INK, true);
                    float need = tp.measureText(EXT_NAMES[idx]);
                    font(sSansBold, fs, 0, INK, true);
                    need += 11 + tp.measureText(extValue(idx));
                    if (need + 4 > cell) { fs = 16; break; }
                }
                for (int k = 0; k < pick.size(); k++) {
                    final int i = pick.get(k);
                    final float x = L + k * cell;
                    font(sSans, fs, 0, INK, true);
                    final float lw = tp.measureText(EXT_NAMES[i]);
                    textBase(EXT_NAMES[i], x, base, 0);
                    rect(x + lw + 5, base - 13, 1, 15, INK);
                    font(sSansBold, fs, 0, INK, true);
                    textBase(extValue(i), x + lw + 10, base, cell - lw - 10);
                }
            } else {
                // 「通知」与条数贴在一起、按墨迹中线对齐：原来标签 10sp 贴左沿、条数 28sp 贴右沿，
                // 中间隔了三百像素，既远又不是一条视觉中线。
                final String count = data.notifications < 0 ? "—" : String.valueOf(data.notifications);
                font(sHeavy, 26, -0.02f, INK, true);
                final float countW = tp.measureText(count);
                final float mid = base + inkMid(count);        // 大数字的墨迹中线
                font(sSans, 13, 0.06f, INK, true);
                final String label = data.notificationLabel;
                final float labelW = tp.measureText(label) - tp.getLetterSpacing() * tp.getTextSize();
                final float numX = L + labelW + 9;
                textBase(label, L, mid - inkMid(label), 0);
                font(sHeavy, 26, -0.02f, INK, true);
                textBase(count, numX, base, 0);
                font(sSans, 12, 0, INK, true);
                textBase("条", numX + countW + 4, mid - inkMid("条"), 0);
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
        rect(L, fy, CW, 4, INK);
        font(sHeavy, 30, -0.04f, INK, true);
        text(batteryText(), L, fy + 10, COL);
        font(sSans, 10, 0, INK, true);
        text("电量 %", L, fy + 44, COL);

        final List<FeatureDefinition.CountdownItem> shown = shownCountdowns();
        if (!shown.isEmpty()) {
            // 「倒数日」标签由 10sp 横排改成 14sp 竖排：横排小字跟左边 30sp 电量、右边 14sp 事项名
            // 都不成比例，竖排正好当电量与倒数日之间的一条竖向分隔。电量占 20-94，标签列心 104。
            final String vyLabel = "倒数日";
            final float labelCx = 104;
            font(sSansBold, 14, 0, INK, true);
            float ly = fy + 16;
            for (int i = 0; i < vyLabel.length(); i++) {
                final String ch = vyLabel.substring(i, i + 1);
                final float w = tp.measureText(ch);
                text(ch, labelCx - w / 2f, ly, 0);
                ly += 17;
            }
            // 底部去掉「↑ 解锁」后右端整条都能用：倒数日从竖排标签右侧起、一直排到右边界。
            final float cx0 = labelCx + 14, cw = L + CW - cx0;
            final float cell = cw / shown.size();
            for (int i = 0; i < shown.size(); i++) {
                final float cx = cx0 + cell * (i + 0.5f);
                final FeatureDefinition.CountdownItem entry = shown.get(i);
                final long days = daysUntil(entry);
                final String left = (days >= 0 ? "剩 " : "过 ") + Math.abs(days) + " 日";
                if (entry.item.isEmpty()) {
                    font(sSerif, 13, 0, INK, true);
                    textCenterFit(left, cx, fy + 30, cell - 8);
                } else {
                    font(sSerifBold, 14, 0, INK, true);
                    textCenterFit(entry.item, cx, fy + 20, cell - 8);
                    font(sSerif, 13, 0, INK, true);
                    textCenterFit(left, cx, fy + 39, cell - 8);
                }
            }
        }
    }

    // ───────────────────────── C · 1-bit 像素 ─────────────────────────

    private void pixel() {
        // 纯白背景：原 3×3 点阵纹理按用户要求移除（2026-10-23）
        rect(0, 0, W, H, PAPER);

        final float X = 18, WW = 324, IN = X + 16, IW = WW - 32;

        // 窗口 1：时钟
        float y1 = 22;
        window(X, y1, WW, 150, "CLOCK");
        pixText(time(), IN, y1 + 38, 9);
        pixText(fmt("EEE dd.MM.yyyy").toUpperCase(Locale.US), IN, y1 + 122, 1.5f);
        float tempW = data.temperature == null ? 0 : pixWidth(data.temperature, 1.5f);
        if (data.temperature != null) pixText(data.temperature, IN + IW - tempW, y1 + 122, 1.5f);
        if (data.weather != null) icon(ICON_CLOUD, IN + IW - tempW - 30, y1 + 115, 1.5f, INK);

        // 窗口 2：正在播放 / 格言
        float y2 = y1 + 150 + 22;
        boolean progress = data.playing && data.duration > 0 && data.position >= 0;
        font(sSans, 21, 0, INK, false);
        float textH = 0;
        StaticLayout probe = null;
        if (data.text != null) {
            probe = StaticLayout.Builder.obtain(data.text, 0, data.text.length(), tp, (int) (IW - 44))
                    .setMaxLines(3).setEllipsize(TextUtils.TruncateAt.END).build();
            textH = Math.max(32, probe.getLineCount() * 21 * 1.35f);
        }
        float subH = 0;
        if (data.track == null && data.subText != null) {
            font(sSans, 12, 0, INK, false);
            StaticLayout sub = StaticLayout.Builder.obtain(data.subText, 0, data.subText.length(), tp, (int) IW)
                    .setMaxLines(3).setEllipsize(TextUtils.TruncateAt.END).build();
            subH = sub.getLineCount() * 12 * 1.4f;
        }
        float h2 = 24 + 14 + textH + (data.track != null ? 10 + 11 : 0) + (subH > 0 ? 10 + subH : 0)
                + (progress ? 10 + 10 : 0) + 14 + 4;
        window(X, y2, WW, h2, data.playing ? "NOW PLAYING" : "DAILY QUOTE");
        float cy = y2 + 24 + 14;
        icon(data.playing ? ICON_NOTE : ICON_QUOTE, IN, cy, 2, INK);
        if (data.text != null) {
            font(sSans, 21, 0, INK, false);
            paragraph(data.text, IN + 44, cy, IW - 44, 1.35f, 3);
        }
        cy += textH;
        if (data.track != null) {
            cy += 10;
            mixedText(data.track, IN, cy, IW, 1.5f, 12);
            cy += 11;
        } else if (subH > 0) {
            cy += 10;
            font(sSans, 12, 0, INK, false);
            paragraph(data.subText, IN, cy, IW, 1.4f, 3);
            cy += subH;
        }
        if (progress) {
            cy += 10;
            int filled = Math.round(20f * Math.min(1, Math.max(0, data.position / (float) data.duration)));
            for (int i = 0; i < 20; i++) {
                float sx = IN + i * 13;
                frame(sx, cy, 10, 10, 2);
                if (i < filled) rect(sx, cy, 10, 10, INK);
            }
        }

        // ── 下半部三块：电量 / 倒数日 / 通知-设备状态 ──
        // 顺序按用户指定：格言 → 电量 → 倒数日 → 通知/设备状态。
        // 原来通知数与电量挤在同一行右端，四项设备状态塞不下，只能另起一块挂到电量下面，读起来像
        // 「电量的附注」；现在电量独占一块（图标按电量填充 + 大号百分比），通知与设备状态本是互斥的
        // 两个总开关，正好共用最后一块：同高 58、标题 NOTIFY / STATUS，来回切也不会整块上下跳。
        // 块间距按剩余空间在 12–22 之间取，倒数日三组时这一摞的最高点仍留有 20 余量，绝不压到底部解锁框。
        final List<FeatureDefinition.CountdownItem> shown = shownCountdowns();
        final List<Integer> picks = data.extMode ? extPicks() : null;
        final boolean slot = data.extMode ? !picks.isEmpty() : (!data.hideNotify && data.notifications >= 0);
        final float hBat = 48, hCd = shown.isEmpty() ? 0 : 80, hSlot = 58;
        final int blocks = 1 + (hCd > 0 ? 1 : 0) + (slot ? 1 : 0);
        final float top = y2 + h2;
        final float stack = hBat + hCd + (slot ? hSlot : 0);
        final float gap = Math.max(12, Math.min(22, ((H - 58) - 12 - top - stack) / blocks));
        float y = top + gap;
        pixelBattery(X, y, WW);
        y += hBat + gap;
        if (hCd > 0) {
            pixelCountdown(X, y, WW, shown);
            y += hCd + gap;
        }
        if (slot) pixelSlot(X, y, WW, picks);

        // 底部：滑动解锁
        float yb = H - 18 - 40;
        rect(X, yb, WW, 40, PAPER);
        frame(X, yb, WW, 40, 2);
        String hint = "↑ SLIDE TO UNLOCK ↑";
        float hw = pixWidth(hint, 1.5f);
        pixText(hint, (W - hw) / 2f, yb + 15, 1.5f);
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
        icon(ICON_BATTERY, IN - 3, mid - 24, 3, INK);
        if (data.battery >= 0) {
            // 内腔在 16 格空间里是 x 3..9 / y 6..10，从左往右填；宽度取 3（=1 格）的整数倍才落在物理像素上
            final float ix = IN + 6, iy = mid - 6;
            rect(ix, iy, 18, 12, PAPER);
            final float fill = Math.round(6 * data.battery / 100f) * 3;
            if (fill > 0) rect(ix, iy, fill, 12, INK);
        }
        pixText(batteryText() + "%", IN + 46, mid - 8.75f, 2.5f);
        if (data.charging) {
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
            final long days = daysUntil(it);
            final String label = (days >= 0 ? String.valueOf(days) : "-" + Math.abs(days)) + " DAYS";
            final float lw = pixWidth(label, 1.5f);
            if (it.item.isEmpty()) {
                // 没填事项时只有天数，整行在内容区（y+22..y+76）里居中
                pixText(label, cx - lw / 2f, y + 44, 1.5f);
            } else {
                font(sSans, 16, 0, INK, false);
                textCenterFit(it.item, cx, y + 30, cell - 10);
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
        if (data.extMode) {
            window(x, y, w, 58, "STATUS");
            final boolean[] on = {data.wifi, data.bt, !data.silent, data.airplane};
            final float cw = IW / picks.size();
            for (int i = 0; i < picks.size(); i++) {
                final float cx = IN + cw * (i + 0.5f);
                // 内容区只有 32 高（58 - 标题栏 22 - 投影 4），墨迹收到 11 才放得下下面一行 ON/OFF
                final int type = EXT_TYPES[picks.get(i)];
                final float[] ink = INK_BOX[type - 1];
                final float k = 11f / (ink[3] - ink[1]);
                final float inkW = (ink[2] - ink[0]) * k;
                drawIcon(type, cx - inkW / 2f - ink[0] * k, y + 35 - (ink[1] + ink[3]) / 2f * k, 16 * k);
                final String label = on[picks.get(i)] ? "ON" : "OFF";
                pixText(label, cx - pixWidth(label, 1.5f) / 2f, y + 43, 1.5f);
            }
            return;
        }
        window(x, y, w, 58, "NOTIFY");
        // ICON_NOTIFY 墨迹在 16 格空间里是 x 2..15 / y 3..14，scale 2 后 x 传 IN-4 让墨迹左沿落在 IN
        icon(ICON_NOTIFY, IN - 4, y + 24, 2, INK);
        final String cnt = String.valueOf(data.notifications);
        pixText(cnt, IN + 30, y + 29.25f, 2.5f);
        // 来源名靠右端：紧挨着数字排会变成「铃铛 + 方框(0) + 中文」三块连着的方块，0 偏偏就是个方框
        font(sSans, 15, 0, INK, false);
        textRight(data.notificationLabel, IN + IW, y + 30, IW * 0.45f);
    }

    /** 老式 Mac 窗口：2px 边框、4px 实心投影、条纹标题栏 */
    private void window(float x, float y, float w, float h, String title) {
        rect(x + 4, y + 4, w, h, INK);
        rect(x, y, w, h, INK);
        rect(x + 2, y + 2, w - 4, h - 4, PAPER);
        if (title == null) return;
        float sx = x + 2 + 6, sy = y + 2 + 5, swd = w - 4 - 12;
        for (int i = 0; i < 5; i++) rect(sx, sy + i * 2, swd, 1, INK);
        rect(x + 2, y + 22, w - 4, 2, INK);
        float tw = pixWidth(title, 1);
        rect(x + (w - tw) / 2f - 8, sy - 1, tw + 16, 12, PAPER);
        pixText(title, x + (w - tw) / 2f, sy + 1.5f, 1);
    }

    // ───────────── 5×7 点阵字（只含锁屏用到的 ASCII，按位图逐格画方块） ─────────────

    private static final Map<Character, String[]> GLYPHS = new HashMap<>();

    private static void g(char ch, String... rows) {
        GLYPHS.put(ch, rows);
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
        return GLYPHS.get(Character.toUpperCase(ch));
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
                    if (row.charAt(col) == '1') rect(cx + col * u, top + r * u, u, u, INK);
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
            font(sSans, size, 0, INK, false);
            text(s, x, top - 2, maxW);
        }
    }

    // ───────────── 16×16 像素图标（只用水平/垂直线段的路径，奇偶填充） ─────────────

    private static final String ICON_CLOUD = "M5 5h4v1h2v1h2v1h1v4H2V9h1V7h2z";
    private static final String ICON_NOTE = "M6 2h8v2H6zM6 4h2v6H6zM12 4h2v4h-2zM3 10h5v4H3zM9 8h5v4H9z";
    private static final String ICON_QUOTE = "M2 4h5v5H5v3H2zM9 4h5v5h-2v3H9z";
    private static final String ICON_BATTERY = "M1 4h12v8H1zM2 5h10v6H2zM13 6h2v4h-2z";
    private static final String ICON_NOTIFY = "M6 3h4v1h2v1h1v1h1v5h1v1H2v-1h1V6h1V5h1V4h2z"
            + "M7 13h2v1H7z";

    private void icon(String d, float x, float y, float scale, int color) {
        Path path = parse(d);
        path.setFillType(Path.FillType.EVEN_ODD);
        fill.reset();
        fill.setColor(color);
        fill.setAntiAlias(false);
        c.save();
        c.translate(x, y);
        c.scale(scale, scale);
        c.drawPath(path, fill);
        c.restore();
    }

    /** 只支持 M/m H/h V/v Z/z（像素图标够用） */
    private static Path parse(String d) {
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
