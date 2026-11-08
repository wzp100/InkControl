package com.inkcontrol.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;
import com.inkcontrol.features.motion.Geometry;

/**
 * 过渡动画演示。波形类的起跑图与 native/service_motion.hpp 的 buildMotionMap 同一公式（按演示分辨率缩放），
 * 关键帧类直接用 Geometry.stepRects，所以看到的图案就是手机上实际的图案，只是时间放慢。
 * 不播放时显示过程中的一帧作为示例图；点按播放一次，按 100ms 一帧推进，适合墨水屏。
 */
final class MotionDemo extends View {
    static final int W = 120, H = 240;
    private static final int TICK = 100;
    private final Ink ink;
    private String scene = "app_open", style = "kiris", origin = "touch", direction = "auto";
    private int steps = 2, spread = 12;
    private boolean thumbnail;
    private Bitmap from, to, frame;
    /** 画面按 k 倍分辨率绘制，起跑图与裁剪矩形仍在 W×H 逻辑坐标里计算；缩略图用 1 倍省内存。 */
    private final int k;
    private int[] fromPixels, toPixels, outPixels;
    private float[] map;
    private int[][] rects;
    private boolean grow = true;
    private float tapX, tapY;
    private long started = -1;
    private final Paint pixels = new Paint(), paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Runnable tick = () -> { invalidate(); if (started >= 0) postDelayed(this.tick, TICK); };

    MotionDemo(Context context, Ink ink, boolean thumbnail) {
        super(context);
        this.ink = ink; this.thumbnail = thumbnail; k = thumbnail ? 1 : 4;
        pixels.setFilterBitmap(true);
        // 按圆角裁切，避免直角露出外框。
        setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View view, android.graphics.Outline outline) { outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), ink.px(thumbnail ? 4 : 13)); }
        });
        setClipToOutline(true);
        if (!thumbnail) { setClickable(true); setOnClickListener(v -> play()); this.setContentDescription(ink.t("播放动画演示")); }
    }

    MotionDemo configure(String scene, String style, int steps, String origin, String direction, int spread) {
        this.scene = scene; this.style = style; this.steps = steps; this.origin = origin; this.direction = direction; this.spread = Math.max(1, spread);
        build();
        invalidate();
        return this;
    }

    void play() {
        if (isInstant()) return;
        started = System.currentTimeMillis();
        removeCallbacks(tick);
        post(tick);
    }

    @Override protected void onDetachedFromWindow() { super.onDetachedFromWindow(); removeCallbacks(tick); started = -1; }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        setMeasuredDimension(width, width * 2);
    }

    private boolean isInstant() { return "instant".equals(style) || "gc16".equals(style); }
    private boolean wave() { return "ink".equals(style) || "dots".equals(style) || "scan".equals(style) || "blind".equals(style) || "gcsweep".equals(style); }

    /** 演示时长：波形按错开幅度缩放，关键帧按步数；均已放慢以便看清。 */
    private long duration() {
        if (wave()) return "gcsweep".equals(style) ? 2000 : Math.max(500, Math.min(3200, 1600L * spread / 12));
        return 700L * steps;
    }

    // ───────────── 画面与起跑图 ─────────────

    private void build() {
        grow = !(scene.equals("app_close") || scene.equals("in_app_back"));
        boolean center = "center".equals(origin);
        tapX = center ? W / 2f : W * 0.69f; tapY = center ? H / 2f : H * 0.43f;
        if (scene.equals("app_close") || scene.equals("in_app_back")) { tapX = center ? W / 2f : W * 0.1f; tapY = center ? H / 2f : H * 0.06f; }
        from = picture(fromScreen()); to = picture(toScreen());
        int rw = W * k, rh = H * k;
        frame = Bitmap.createBitmap(rw, rh, Bitmap.Config.ARGB_8888);
        fromPixels = new int[rw * rh]; toPixels = new int[rw * rh]; outPixels = new int[rw * rh];
        from.getPixels(fromPixels, 0, rw, 0, 0, rw, rh); to.getPixels(toPixels, 0, rw, 0, 0, rw, rh);
        map = null; rects = null;
        if (wave()) map = startMap();
        else if ("kiris".equals(style) || "kwipe".equals(style)) {
            String dir = "auto".equals(direction) ? (grow ? "rtl" : "ltr") : direction;
            rects = Geometry.stepRects(style, grow, steps, W, H, tapX, tapY, dir);
        }
    }

    private String fromScreen() {
        switch (scene) {
            case "app_open": case "unlock": return scene.equals("unlock") ? "lock" : "home";
            case "in_app_back": return "page";
            case "gc_clear": return "ghost";
            default: return "app";
        }
    }
    private String toScreen() {
        switch (scene) {
            case "app_close": case "unlock": return "home";
            case "in_app_forward": return "page";
            case "dialog": return "dialog";
            case "lock": return "lock";
            default: return "app";
        }
    }

    private float[] startMap() {
        float[] s = new float[W * H];
        // 全刷扫过走自己的方向配置；其它样式在 auto 时按「从上向下」演示。
        String dir = "gcsweep".equals(style)
                ? ("auto".equals(direction) ? "ltr" : direction)
                : ("auto".equals(direction) ? "ttb" : direction);
        float maxD = 0;
        for (float[] c : new float[][]{{0, 0}, {W, 0}, {0, H}, {W, H}}) maxD = Math.max(maxD, (float) Math.hypot(c[0] - tapX, c[1] - tapY));
        float[] noise = "ink".equals(style) ? inkNoise() : null;
        float[] dots = "dots".equals(style) ? dotTable() : null;
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            float v;
            if (noise != null) {
                float d = (float) Math.hypot(x - tapX, y - tapY) / maxD;
                v = Math.min(1f, Math.max(0f, d * 0.85f + (noise[y * W + x] - 0.5f) * 0.35f + 0.08f));
            } else if (dots != null) {
                int cx = x >> 2, cy = y >> 2;
                v = dots[(y & 3) * 4 + (x & 3)] * 0.8f + ((cx * 13 + cy * 7) % 5) / 5f * 0.2f;
            } else if ("blind".equals(style)) {
                boolean horizontal = dir.equals("ltr") || dir.equals("rtl");
                int p = horizontal ? x : y, len = horizontal ? W : H;
                if (dir.equals("rtl") || dir.equals("btt")) p = len - 1 - p;
                int band = Math.max(4, len / 12);
                v = (p % band) / (float) band;
            } else {
                v = dir.equals("btt") ? (H - 1 - y) / (float) H : dir.equals("ltr") ? x / (float) W : dir.equals("rtl") ? (W - 1 - x) / (float) W : y / (float) H;
            }
            s[y * W + x] = v;
        }
        return s;
    }

    /** 与原生实现相同的三层值噪声，格子按 360→120 的比例缩小。 */
    private static float[] inkNoise() {
        final long[] seed = {20261009L};
        java.util.function.IntFunction<float[]> layer = cell -> {
            int gw = (W + cell - 1) / cell + 2, gh = (H + cell - 1) / cell + 2;
            float[] g = new float[gw * gh];
            for (int i = 0; i < g.length; i++) { seed[0] = (seed[0] * 1103515245L + 12345L) & 0xffffffffL; g[i] = (float) (seed[0] / 4294967296.0); }
            float[] r = new float[W * H];
            for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
                float fx = x / (float) cell, fy = y / (float) cell; int ix = (int) fx, iy = (int) fy;
                float u = fx - ix, v = fy - iy; u = u * u * (3 - 2 * u); v = v * v * (3 - 2 * v);
                float a = g[iy * gw + ix], b = g[iy * gw + ix + 1], c = g[(iy + 1) * gw + ix], d = g[(iy + 1) * gw + ix + 1];
                r[y * W + x] = a + (b - a) * u + (c - a) * v + (a - b - c + d) * u * v;
            }
            return r;
        };
        float[] l1 = layer.apply(21), l2 = layer.apply(8), l3 = layer.apply(3), n = new float[W * H];
        for (int i = 0; i < n.length; i++) n[i] = l1[i] * 0.55f + l2[i] * 0.3f + l3[i] * 0.15f;
        return n;
    }
    private static float[] dotTable() {
        Integer[] order = new Integer[16];
        for (int i = 0; i < 16; i++) order[i] = i;
        java.util.Arrays.sort(order, (a, b) -> Double.compare(Math.hypot(a % 4 - 1.5, a / 4 - 1.5) + 0.01 * a, Math.hypot(b % 4 - 1.5, b / 4 - 1.5) + 0.01 * b));
        float[] t = new float[16];
        for (int r = 0; r < 16; r++) t[order[r]] = r / 15f;
        return t;
    }

    // ───────────── 示意画面（纯黑白） ─────────────

    private Bitmap picture(String kind) {
        Bitmap b = Bitmap.createBitmap(W * k, H * k, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(Color.WHITE);
        c.scale(k, k);
        Paint fill = new Paint(); fill.setColor(Color.BLACK);
        Paint stroke = new Paint(); stroke.setColor(Color.BLACK); stroke.setStyle(Paint.Style.STROKE); stroke.setStrokeWidth(1.5f);
        Paint text = new Paint(); text.setColor(Color.BLACK); text.setTypeface(Typeface.DEFAULT_BOLD);
        c.drawRect(6, 5, 30, 7, fill); c.drawRect(96, 5, 114, 7, fill); // 状态栏
        switch (kind) {
            case "home":
                text.setTextSize(30); c.drawText(ink.t("9:41"), 10, 46, text);
                text.setTextSize(9); c.drawText(ink.t("10月16日 星期四"), 11, 60, text);
                for (int r = 0; r < 4; r++) for (int col = 0; col < 4; col++) {
                    float x = 10 + col * 27, y = 80 + r * 34;
                    RectF icon = new RectF(x, y, x + 19, y + 19);
                    if ((r * 4 + col) % 3 == 0) c.drawRoundRect(icon, 5, 5, fill); else c.drawRoundRect(icon, 5, 5, stroke);
                    c.drawRect(x + 3, y + 23, x + 16, y + 25, fill);
                }
                c.drawLine(8, 218, 112, 218, stroke);
                for (int i = 0; i < 4; i++) c.drawRoundRect(new RectF(14 + i * 26, 223, 30 + i * 26, 236), 4, 4, stroke);
                break;
            case "app": case "dialog": case "ghost":
                c.drawRect(0, 12, W, 32, fill);
                text.setColor(Color.WHITE); text.setTextSize(10); c.drawText(ink.t("‹  设置"), 8, 26, text); text.setColor(Color.BLACK);
                for (int i = 0; i < 7; i++) {
                    float y = 44 + i * 26;
                    c.drawRoundRect(new RectF(8, y, 22, y + 14), 3, 3, i % 2 == 0 ? fill : stroke);
                    c.drawRect(30, y + 2, 30 + 40 + (i * 13) % 40, y + 5, fill);
                    c.drawRect(30, y + 9, 30 + 25 + (i * 7) % 30, y + 10, fill);
                    c.drawLine(8, y + 20, 112, y + 20, stroke);
                }
                if (kind.equals("ghost")) for (int y = 20; y < H; y += 7) for (int x = (y * 3) % 11; x < W; x += 11) c.drawPoint(x, y, fill);
                if (kind.equals("dialog")) {
                    RectF box = new RectF(14, 82, 106, 162);
                    Paint white = new Paint(); white.setColor(Color.WHITE); c.drawRoundRect(box, 8, 8, white);
                    Paint thick = new Paint(stroke); thick.setStrokeWidth(2.5f); c.drawRoundRect(box, 8, 8, thick);
                    text.setTextSize(10); c.drawText(ink.t("确认操作？"), 24, 102, text);
                    c.drawRect(24, 112, 96, 114, fill); c.drawRect(24, 120, 80, 122, fill);
                    c.drawRoundRect(new RectF(62, 136, 98, 152), 6, 6, fill);
                }
                break;
            case "page":
                c.drawLine(0, 32, W, 32, stroke);
                text.setTextSize(10); c.drawText(ink.t("‹  显示"), 8, 26, text);
                c.drawRect(8, 42, 112, 104, stroke);
                for (int y = 48; y < 100; y += 4) for (int x = 12 + (y % 8); x < 108; x += 8) c.drawCircle(x, y, 1 + (x + y) % 3 * 0.5f, fill);
                for (int i = 0; i < 9; i++) c.drawRect(8, 116 + i * 12, 8 + 100 - (i % 3) * 20, 119 + i * 12, fill);
                break;
            case "lock":
                text.setTextSize(40); text.setTextAlign(Paint.Align.CENTER); c.drawText(ink.t("21:47"), W / 2f, 82, text);
                text.setTextSize(10); c.drawText(ink.t("10月16日 星期四"), W / 2f, 100, text);
                c.drawRoundRect(new RectF(14, 130, 106, 180), 10, 10, stroke);
                c.drawRect(24, 144, 70, 147, fill); c.drawRect(24, 156, 90, 158, fill); c.drawRect(24, 166, 60, 168, fill);
                c.drawRect(44, 222, 76, 224, fill);
                break;
        }
        return b;
    }

    // ───────────── 绘制 ─────────────

    /** 进度 p∈[0,1] 时的画面；p<0 为开始前，p>1 为结束后。 */
    private void compose(float p) {
        int rw = W * k, rh = H * k;
        int[] a = fromPixels, b = toPixels, out = outPixels;
        if (p <= 0) System.arraycopy(a, 0, out, 0, out.length);
        else if (p >= 1 || isInstant()) System.arraycopy(b, 0, out, 0, out.length);
        else if (map != null) {
            boolean sweep = "gcsweep".equals(style);
            float t = sweep ? p * 1.25f : p;
            for (int y = 0; y < rh; y++) {
                int row = (y / k) * W;
                for (int x = 0; x < rw; x++) {
                    int i = y * rw + x; float s = map[row + x / k];
                    if (sweep) out[i] = t < s ? a[i] : t < s + 0.25f ? Color.BLACK : b[i];
                    else out[i] = s <= t ? b[i] : a[i];
                }
            }
        } else if (rects != null) {
            int step = Math.min(steps - 1, (int) (p * steps));
            int[] r = rects[step];
            int[] inside = grow ? b : a, outside = grow ? a : b;
            for (int y = 0; y < rh; y++) for (int x = 0; x < rw; x++) {
                int i = y * rw + x, lx = x / k, ly = y / k;
                out[i] = lx >= r[0] && lx < r[2] && ly >= r[1] && ly < r[3] ? inside[i] : outside[i];
            }
        }
        frame.setPixels(out, 0, rw, 0, 0, rw, rh);
    }

    @Override protected void onDraw(Canvas canvas) {
        long total = duration(), lead = 600, tail = 900;
        boolean playing = started >= 0;
        float p;
        if (playing) {
            long t = System.currentTimeMillis() - started;
            if (t > lead + total + tail) { started = -1; p = isInstant() ? 1 : 0.5f; playing = false; }
            else p = (t - lead) / (float) total;
        } else p = isInstant() ? 1 : rects != null ? 0.5f / steps + 0.001f : 0.5f;
        compose(p);
        Rect dst = new Rect(0, 0, getWidth(), getHeight());
        canvas.drawBitmap(frame, null, dst, pixels);
        float sx = getWidth() / (float) W;
        // 触摸起点
        if ((playing && p <= 0.05f) || (!playing && !thumbnail && ("kiris".equals(style) || "ink".equals(style)))) {
            paint.setStyle(Paint.Style.STROKE); paint.setColor(Color.BLACK); paint.setStrokeWidth(ink.px(2));
            canvas.drawCircle(tapX * sx, tapY * sx, ink.px(thumbnail ? 6 : 12), paint);
        }
        if (thumbnail) return;
        String label = playing ? "演示已放慢" : isInstant() ? "原厂：瞬间切换" : "▶ 点按播放";
        paint.setStyle(Paint.Style.FILL); paint.setTextSize(ink.px(13)); paint.setTypeface(Ink.MEDIUM);
        label = ink.t(label);
        float w = paint.measureText(label) + ink.px(20), h = ink.px(28);
        RectF pill = new RectF((getWidth() - w) / 2, getHeight() - h - ink.px(10), (getWidth() + w) / 2, getHeight() - ink.px(10));
        paint.setColor(Color.BLACK); canvas.drawRoundRect(pill, h / 2, h / 2, paint);
        paint.setColor(Color.WHITE); paint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(ink.t(label), pill.centerX(), pill.centerY() + ink.px(4.5f), paint);
        paint.setTextAlign(Paint.Align.LEFT);
    }
}
