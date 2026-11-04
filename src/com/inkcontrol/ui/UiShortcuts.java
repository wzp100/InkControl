package com.inkcontrol.ui;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.Icon;
import android.util.Log;

import com.inkcontrol.core.FeatureCatalog;
import com.inkcontrol.core.FeatureDefinition;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 长按桌面图标弹出的快捷方式。
 *
 * <p>纯界面偏好：不进功能配置、不参与后端校验，因此和「大功能排序」一样放在 SharedPreferences 里。
 * 存的是功能 id 的有序列表（逗号连接），空表示还没配置过，按 {@link #DEFAULTS} 处理。
 *
 * <p>每一项注册成一条独立的动态快捷方式（{@code setDynamicShortcuts}），桌面长按后直接列出来，
 * 点哪个进哪个功能页，不再中转。海信桌面这一列最多排 4 个，所以 {@link #MAX} 也是 4。
 *
 * <p>标签只留两三个字：桌面按格排版，字多了会变成「遮挡息…」。
 */
final class UiShortcuts {
    /** 桌面长按最多放几个：海信桌面自己就截到 4 个。 */
    static final int MAX = 4;
    /** 还没配置过时给的四个：遮挡息屏、自定义锁屏、自动刷新、字体。 */
    static final String[] DEFAULTS = {"wave.lock", "lock.style", "refresh.master", "font.dir"};
    /** 快捷方式点击时的 action，保证和其它入口（LAUNCHER）区分开。 */
    static final String ACTION = "com.inkcontrol.SHORTCUT";
    /** 传功能 id 的 extra。 */
    static final String EXTRA = "feature";
    /**
     * 结构版本。改了「桌面挂几条」这种结构就加一，否则 applied 里记的签名和以前一样，
     * 升级后不会重写，桌面还停在旧的那几项上。
     */
    private static final String ENTRY_REVISION = "3";
    private static final String TAG = "InkControl-Shortcut";

    /**
     * 桌面那一行只有两三字的位置，标题多半是四五个字，直接放会变成省略号。常用项给个短名。
     * 没列到的退回「标题前三个字」。
     */
    private static final String[][] SHORT = {
            {"wave.lock", "遮屏"}, {"wave.wake", "唤醒"},
            {"lock.style", "锁屏"}, {"lock.weather", "天气"}, {"lock.notify", "通知"},
            {"lock.ext", "状态"}, {"lock.lyric", "歌词"}, {"lock.countdown", "倒数"},
            {"refresh.master", "刷新"}, {"refresh.reading", "阅读"}, {"refresh.apps", "黑白名单"},
            {"desktop.theme", "主题"}, {"desktop.icons", "图标"}, {"desktop.wallpaper", "壁纸"},
            {"display.alg.stock", "原厂"}, {"display.apps", "极速"},
            {"font.dir", "字体"}, {"qs.extend", "磁贴"}, {"navbar.white", "导航"},
            {"sys.backup", "备份"}, {"fix.modes", "少刷"}, {"fix.video", "视频"}, {"fix.safeime", "密码"},
            {"motion.master", "动画"}, {"experiment.video", "视频抖动"},
    };

    private static SharedPreferences preferences(Context context) {
        return context.createDeviceProtectedStorageContext().getSharedPreferences("ui-shortcuts", Context.MODE_PRIVATE);
    }

    /** 已配置的快捷方式功能 id；没配置过返回默认的四个。 */
    static List<String> saved(Context context) {
        SharedPreferences preferences = preferences(context);
        String value = preferences.getString("list", null);
        if (value == null) return new ArrayList<>(Arrays.asList(DEFAULTS));
        List<String> result = new ArrayList<>();
        for (String id : value.split(",", -1)) {
            if (!id.isEmpty() && valid(id) && !result.contains(id) && result.size() < MAX) result.add(id);
        }
        return result;
    }

    /** 保存并立即重建快捷方式；返回是否真的写进去了（用来提示用户）。 */
    static boolean save(Context context, List<String> ids) {
        List<String> clean = new ArrayList<>();
        for (String id : ids) {
            if (valid(id) && !clean.contains(id) && clean.size() < MAX) clean.add(id);
        }
        preferences(context).edit().putString("list", String.join(",", clean)).commit();
        return apply(context);
    }

    /** 恢复默认四个。 */
    static boolean reset(Context context) {
        preferences(context).edit().remove("list").commit();
        return apply(context);
    }

    /** 某个功能能不能当快捷方式：必须是一个真实的功能项（不是纯页面）。 */
    static boolean valid(String id) {
        try {
            FeatureDefinition definition = FeatureCatalog.get(Guide.destination(id));
            return !definition.acceptsPanel();
        } catch (RuntimeException unknown) {
            return false;
        }
    }

    /** 桌面那一行显示的两三字标签。 */
    static String shortName(FeatureDefinition feature) {
        for (String[] pair : SHORT) if (pair[0].equals(feature.id)) return pair[1];
        String title = feature.title.trim();
        return title.length() <= 3 ? title : title.substring(0, 3);
    }

    /**
     * 把每个功能都注册成一条桌面快捷方式。
     *
     * <p>只在内容变化时真正下发：{@code setDynamicShortcuts} 有速率限制（半小时内约 10 次），
     * 每次打开 App 都无条件调一次会很快撞上，表现为「改完没反应」。
     */
    static boolean apply(Context context) {
        ShortcutManager manager = (ShortcutManager) context.getSystemService(Context.SHORTCUT_SERVICE);
        if (manager == null) return false;
        List<String> ids = saved(context);
        String signature = ENTRY_REVISION + "|" + String.join(",", ids);
        if (signature.equals(preferences(context).getString("applied", ""))) return true;
        if (manager.isRateLimitingActive()) {
            // 撞上限时不写 applied，下次打开 App 再补
            Log.w(TAG, "桌面拒绝更新快捷方式（触发速率限制），稍后重试");
            return false;
        }
        List<ShortcutInfo> infos = new ArrayList<>(ids.size());
        for (int index = 0; index < ids.size(); index++) {
            String id = ids.get(index);
            FeatureDefinition feature;
            try { feature = FeatureCatalog.get(Guide.destination(id)); }
            catch (RuntimeException unknown) { continue; }
            String name = shortName(feature);
            infos.add(new ShortcutInfo.Builder(context, "feature:" + id)
                    .setShortLabel(name)
                    .setLongLabel(feature.title)
                    // 桌面按 rank 排序，但这套桌面是倒着排的（实测 rank 0 落到最下面），
                    // 所以把序号反过来给，界面里看到的顺序才和配置顺序一致。
                    .setRank(MAX - 1 - index)
                    .setIcon(Icon.createWithBitmap(bitmap(name)))
                    .setIntent(new Intent(ACTION)
                            .setClass(context, MainActivity.class)
                            .putExtra(EXTRA, id)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP))
                    .build());
        }
        if (infos.isEmpty()) return false;
        try {
            boolean ok = manager.setDynamicShortcuts(infos);
            if (ok) preferences(context).edit().putString("applied", signature).commit();
            return ok;
        } catch (RuntimeException failure) {
            // 超上限 / 限速 / 桌面不支持：只留一行，不影响界面
            Log.w(TAG, "桌面不接受快捷方式：" + failure.getClass().getSimpleName() + " " + failure.getMessage());
            return false;
        }
    }

    /**
     * 快捷方式图标：白底黑圈的圆，中间两三个字。
     *
     * <p>不用应用图标：四条排在一起全是同一个齿轮，只能靠下面那行小字分辨，墨水屏上更难分。
     * 自己画也省掉一份位图资源，颜色只有黑白两色，抖动不会带出脏点。
     */
    private static Bitmap bitmap(String label) {
        int size = 192;
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        float center = size / 2f;
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(0xFFFFFFFF);
        canvas.drawCircle(center, center, center - 4, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(9);
        paint.setColor(0xFF000000);
        canvas.drawCircle(center, center, center - 10, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFF000000);
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(label.length() >= 3 ? 52 : 68);
        Paint.FontMetrics metrics = paint.getFontMetrics();
        // 按字形实际高度居中：直接用 size/2 会偏上（中文字形重心靠下）
        canvas.drawText(label, center, center - (metrics.ascent + metrics.descent) / 2f, paint);
        return bitmap;
    }

    /** 把列表去重并截到上限，供界面勾选时复用。 */
    static List<String> normalize(List<String> ids) {
        return new ArrayList<>(new LinkedHashSet<>(ids.size() > MAX ? ids.subList(0, MAX) : ids));
    }

    private UiShortcuts() { }
}
