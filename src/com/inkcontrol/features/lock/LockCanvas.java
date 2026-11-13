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
    private static final int LAND_W = 720, LAND_H = 360;
    static final int INK = Color.BLACK, PAPER = Color.WHITE;
    /** 次级文字（英文格言等）用的中灰，墨水屏 16 级灰阶下清晰可辨 */
    private static final int GREY = Color.rgb(0x59, 0x59, 0x59);

    static Typeface sSerif, sSerifBold, sSans, sSansMedium, sSansBold, sHeavy;

    int style;
    LockData data;

    // 每次 draw() 时有效
    Canvas c;
    final Paint fill = new Paint();
    final Paint line = new Paint();
    final Path path = new Path();
    final Matrix mat = new Matrix();
    final RectF box = new RectF();
    private final android.graphics.Rect inkRect = new android.graphics.Rect();
    final TextPaint tp = new TextPaint();

    private static final String[] CN = {"〇", "一", "二", "三", "四", "五", "六", "七", "八", "九"};
    static final String[] WEEK_CN = {"日", "一", "二", "三", "四", "五", "六"};
    /** 每个图标在 16 空间里墨迹的实际外框 {左, 上, 右, 下}：用来把墨迹对齐到文字，而不是对齐 16 方框 */
    static final float[][] INK_BOX = {
            {0.10f, 4.70f, 15.90f, 14.55f},   // IC_WIFI（三段弧 + 圆点）
            {4.20f, 2.60f, 11.80f, 13.40f},   // IC_BT
            {1.20f, 1.60f, 14.60f, 14.40f},   // IC_AUDIO（喇叭 + 右侧两道声波）
            {0.50f, 2.00f, 15.50f, 14.20f},   // IC_AIRPLANE（翼展 15 × 机身长 12.2）
    };
    /** 图标的目标墨迹高度：细线图标按 11.5 与文字等重；实心图标视觉重量小，要摸到 12.8～13.2 才不显小 */
    static final float[] INK_H = {11.5f, 11.5f, 12.8f, 13.2f};
    static final int IC_WIFI = 1, IC_BT = 2, IC_AUDIO = 3, IC_AIRPLANE = 4;
    /** 设备状态四项，顺序即界面上的显示顺序 */
    static final int[] EXT_TYPES = {IC_WIFI, IC_BT, IC_AUDIO, IC_AIRPLANE};
    static final String[] EXT_NAMES = {"无线", "蓝牙", "声音", "飞行"};
    static final Map<Character, String[]> GLYPHS = new HashMap<>();
    static final String ICON_CLOUD = "M5 5h4v1h2v1h2v1h1v4H2V9h1V7h2z";
    static final String ICON_NOTE = "M6 2h8v2H6zM6 4h2v6H6zM12 4h2v4h-2zM3 10h5v4H3zM9 8h5v4H9z";
    static final String ICON_QUOTE = "M2 4h5v5H5v3H2zM9 4h5v5h-2v3H9z";
    static final String ICON_BATTERY = "M1 4h12v8H1zM2 5h10v6H2zM13 6h2v4h-2z";
    static final String ICON_NOTIFY = "M6 3h4v1h2v1h1v1h1v5h1v1H2v-1h1V6h1V5h1V4h2z"
            + "M7 13h2v1H7z";
    // 各样式的绘制拆到独立的类里；本类保留画布状态、缩放与旋转、文字与图形的基础方法。
    final LockEditorial editorialStyle = new LockEditorial(this);
    final LockEditorialLand landStyle = new LockEditorialLand(this);
    final LockSwiss swissStyle = new LockSwiss(this);
    final LockPixel pixelStyle = new LockPixel(this);
    final LockGlass glassStyle = new LockGlass(this);
    final LockExtBar extBarView = new LockExtBar(this);

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
            if (style == LockStyle.GLASS) glassStyle.glass();
            else if (style == LockStyle.SWISS) swissStyle.swiss();
            else if (style == LockStyle.PIXEL) pixelStyle.pixel();
            else if (land) landStyle.editorialLand();
            else editorialStyle.editorial();
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
    boolean reverseLand() {
        if (style == LockStyle.EDITORIAL_LAND_R) return true;
        if (style != LockStyle.EDITORIAL_LAND) return false;
        try { return "10".equals(RuntimeConfig.value("lock.style")); }
        catch (RuntimeException ignored) { return false; }
    }

    // ───────────────────────── 基础绘制 ─────────────────────────

    void rect(float x, float y, float w, float h, int color) {
        fill.reset();
        fill.setColor(color);
        fill.setAntiAlias(false);
        c.drawRect(x, y, x + w, y + h, fill);
    }

    void frame(float x, float y, float w, float h, float t) {
        rect(x, y, w, t, INK);
        rect(x, y + h - t, w, t, INK);
        rect(x, y, t, h, INK);
        rect(x + w - t, y, t, h, INK);
    }

    TextPaint font(Typeface face, float size, float spacingEm, int color, boolean aa) {
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
    float text(String s, float x, float top, float maxW) {
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
    float textBase(String s, float x, float baseline, float maxW) {
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

    void textRight(String s, float right, float top, float maxW) {
        if (s == null) return;
        float w = Math.min(tp.measureText(s), maxW);
        text(s, right - w, top, maxW);
    }

    void textCenter(String s, float cx, float top) {
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
    float segFit(String[] parts, float[] sizes, float maxW) {
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
    float segRow(String[] parts, float[] sizes, float scale, float x, float baseline) {
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
    void textCenterBase(String s, float cx, float baseline, float maxW) {
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
    void metricCell(String label, String value, float x, float top, float w, float h) {
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
    void textCenterFit(String s, float cx, float top, float maxW) {
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
    float inkMid(String s) {
        inkRect.setEmpty();
        tp.getTextBounds(s, 0, s.length(), inkRect);
        return (inkRect.top + inkRect.bottom) / 2f;
    }

    /**
     * 一行里的「标签 + 数值」（横版信息带用：竖版按列排，横版只能压成一行）。
     * 标签比数值大一号、同样加粗，缩略图下也能一眼分出字段名与数据；
     * 数值超宽时横向压缩到 right 以内。返回这一段右端的 x。
     */
    float pair(String label, String value, float x, float baseline, float right) {
        font(sSerifBold, 21, 0.12f, INK, true);
        float width = textBase(label, x, baseline, 0);
        float valueX = x + width + 10;
        font(sSerifBold, 18, 0, INK, true);
        return valueX + textBase(value, valueX, baseline, right > valueX ? right - valueX : 0);
    }

    /** 多行段落，lineHeight 为 CSS 式的行高（倍数）。返回高度 */
    float paragraph(String s, float x, float top, float width, float lineHeight, int maxLines) {
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

    String fmt(String pattern) {
        return new SimpleDateFormat(pattern, Locale.US).format(data.now.getTime());
    }

    String hour() {
        return fmt(data.is24h ? "HH" : "hh");
    }

    String time() {
        return hour() + ":" + fmt("mm");
    }

    private static String chineseNumber(int n) {
        if (n < 10) return CN[n];
        if (n == 10) return "十";
        if (n < 20) return "十" + CN[n % 10];
        return CN[n / 10] + "十" + (n % 10 == 0 ? "" : CN[n % 10]);
    }

    String chineseDate() {
        return chineseNumber(data.now.get(Calendar.MONTH) + 1) + "月"
                + chineseNumber(data.now.get(Calendar.DAY_OF_MONTH)) + "日";
    }

    String weekCn() {
        return "星期" + WEEK_CN[data.now.get(Calendar.DAY_OF_WEEK) - 1];
    }

    /** 农历月份日期（数字版，如 9.18）：表头是「公历数字 | 农历数字」并排，写成「九月十八」既长又不齐。 */
    String lunarNumeric() {
        final int[] lunar = LunarCalendar.toLunar(data.now.get(Calendar.YEAR),
                data.now.get(Calendar.MONTH) + 1, data.now.get(Calendar.DAY_OF_MONTH));
        return lunar == null ? "—" : lunar[1] + "." + lunar[2];
    }

    /** 节日/节气优先，其次农历 */
    String almanac() {
        if (data.festival != null) return data.festival;
        return data.lunar;
    }

    static String dash(String s) {
        return s == null || s.isEmpty() ? "—" : s;
    }

    /** 去掉温度单位（瑞士网格里只要数字） */
    static String strip(String temp) {
        return temp == null ? "—" : temp.replace("°F", "").replace("°", "");
    }

    String batteryText() {
        return data.battery < 0 ? "—" : String.valueOf(data.battery);
    }

    String notificationText() {
        return data.notifications < 0 ? "—" : data.notifications > 0
                ? data.notificationLabel + " " + data.notifications + " 条" : "暂无通知";
    }

    boolean hasWeather() {
        return !TextUtils.isEmpty(data.weather) || !TextUtils.isEmpty(data.temperature)
                || !TextUtils.isEmpty(data.high) || !TextUtils.isEmpty(data.low);
    }

    // ───────────────────────── A · 编辑排版 ─────────────────────────

    /** 勾选了「显示」的倒数日组（竖版横版共用；最多 COUNTDOWNS_MAX 组，已在配置层排好序）。 */
    List<FeatureDefinition.CountdownItem> shownCountdowns() {
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
    long daysUntil(FeatureDefinition.CountdownItem item) {
        Calendar target = FeatureDefinition.targetDate(item.date, item.lunar);
        if (target == null) return 0;
        Calendar today = (Calendar) data.now.clone();
        today.set(Calendar.HOUR_OF_DAY, 0);
        today.set(Calendar.MINUTE, 0);
        today.set(Calendar.SECOND, 0);
        today.set(Calendar.MILLISECOND, 0);
        return (target.getTimeInMillis() - today.getTimeInMillis()) / 86400000L;
    }

}
