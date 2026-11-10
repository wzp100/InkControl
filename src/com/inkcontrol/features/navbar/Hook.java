package com.inkcontrol.features.navbar;

import android.animation.ArgbEvaluator;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

import com.inkcontrol.platform.RuntimeConfig;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 三大金刚键漂白：把导航栏的「黑底白图标」改成「白底黑图标」。
 *
 * <p>原厂把颜色写死在代码里，资源覆盖完全无效。反编译 Vision_SystemUI 可见
 * {@code BarTransitions$BarBackgroundDrawable.draw(Canvas)} 里有一段：
 * <pre>
 * if (mTag.equals("BarTransitions.NavigationBarView") &amp;&amp; this$0.mDeviceProvisioned) {
 *     if (!isKeyguardShowing || isCalling) targetColor = Color.BLACK;
 * }
 * </pre>
 * 这段逻辑不读任何资源（RRO 全量染色时状态栏会变、导航栏依旧纯黑），所以只能在画完之后重绘。
 *
 * <p>与最初那版独立模块相比，这里只做三件事的差别（原版会在后台持续刷日志并明显耗电）：
 * <ul>
 * <li><b>不再复算原厂条件</b>。原版每帧重新问一遍 {@code KeyguardMonitor} 与
 *     {@code TelecomManager.isInCall()}，后者是一次跨进程 binder 调用，导航栏每画一帧就多打一次；
 *     现在只读 {@code mColor}（原厂刚画上去的颜色），等于 {@code 0xFF000000} 就说明这一帧被刷黑了。</li>
 * <li><b>反射全部预取</b>。字段/方法在安装钩子时查一次存起来，每帧只有几次字段读。</li>
 * <li><b>出错只记一次</b>。原版任何一个反射异常都会在每一帧调用 {@code XposedBridge.log}，
 *     日志文件与 logcat 一起被刷爆；现在同一个错误只写一行，之后静默跳过。</li>
 * </ul>
 *
 * <p>开关是运行期读的：钩子装好之后，改开关不用重启系统界面，下一帧就按新状态画。
 */
public class Hook implements IXposedHookLoadPackage {

    private static final String TAG = "InkControl-Nav: ";
    private static final String FEATURE = "navbar.white";
    private static final String NAV_TAG = "BarTransitions.NavigationBarView";
    private static final String BACKGROUND = "com.android.systemui.statusbar.phone.BarTransitions$BarBackgroundDrawable";
    private static final String OUTER = "com.android.systemui.statusbar.phone.BarTransitions";
    private static final String ICON = "com.android.systemui.statusbar.policy.KeyButtonDrawable";
    private static final String ICON_STATE = "com.android.systemui.statusbar.policy.KeyButtonDrawable$ShadowDrawableState";
    private static final String KEYGUARD_MONITOR = "com.android.systemui.statusbar.policy.KeyguardMonitor";

    /** 原厂硬编码的纯黑；导航栏每帧画出来的颜色等于它就是「被强制刷黑」。 */
    private static final int BLACK = 0xFF000000;

    private static boolean installed;

    /** 开关；由 {@link RuntimeConfig} 回调刷新，每帧只读这个 volatile。 */
    private static volatile boolean enabled;
    /** 导航栏当前是否为白底（背景钩子每帧写，图标钩子与切换时读）。 */
    private static volatile boolean white = true;

    private static final Paint WHITE_PAINT = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final ColorFilter BLACK_FILTER =
            new PorterDuffColorFilter(Color.BLACK, PorterDuff.Mode.SRC_ATOP);
    private static final ArgbEvaluator EVALUATOR = new ArgbEvaluator();

    /** 已经画过的导航键与它最后一次的亮度；弱引用，键位重建时自动回收。 */
    private static final Map<Object, Float> ICONS = Collections.synchronizedMap(new WeakHashMap<Object, Float>());
    private static final Set<String> REPORTED = Collections.synchronizedSet(new HashSet<String>());
    /** 最近一次画过的导航栏背景，切开关时用它触发一次重画。 */
    private static WeakReference<Object> NAV_BACKGROUND;

    private static Field tagField, colorField, frameField;
    private static Field outerField, keyguardField, provisionedField;
    private static Method isShowingMethod, isSecureMethod;
    private static Field stateField, lightField, darkField;

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam packageInfo) {
        if (!"com.android.systemui".equals(packageInfo.packageName) || installed) return;
        installed = true;
        WHITE_PAINT.setStyle(Paint.Style.FILL);
        WHITE_PAINT.setColor(Color.WHITE);
        if (!prepare(packageInfo.classLoader)) return;
        installBackground(packageInfo.classLoader);
        installIcons(packageInfo.classLoader);
        RuntimeConfig.listen(new Runnable() {
            @Override public void run() { refresh(); }
        });
    }

    /** 预取所有反射句柄；缺任何一个就整体放弃，绝不留「每帧抛一次异常」的路径。 */
    private static boolean prepare(ClassLoader loader) {
        try {
            Class<?> background = XposedHelpers.findClass(BACKGROUND, loader);
            tagField = field(background, "mTag");
            colorField = field(background, "mColor");
            frameField = field(background, "mFrame");
            outerField = field(background, "this$0");
            Class<?> outer = XposedHelpers.findClass(OUTER, loader);
            keyguardField = field(outer, "mKeyguardMonitor");
            provisionedField = field(outer, "mDeviceProvisioned");
            Class<?> monitor = XposedHelpers.findClass(KEYGUARD_MONITOR, loader);
            isShowingMethod = monitor.getMethod("isShowing");
            isSecureMethod = monitor.getMethod("isSecure");
            Class<?> state = XposedHelpers.findClass(ICON_STATE, loader);
            stateField = field(XposedHelpers.findClass(ICON, loader), "mState");
            lightField = field(state, "mLightColor");
            darkField = field(state, "mDarkColor");
            return true;
        } catch (Throwable error) {
            report("系统界面结构不符，已跳过", error);
            return false;
        }
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        Field found = type.getDeclaredField(name);
        found.setAccessible(true);
        return found;
    }

    private static void installBackground(ClassLoader loader) {
        try {
            XposedHelpers.findAndHookMethod(BACKGROUND, loader, "draw", Canvas.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    // 关着的时候也要认一遍这个对象：开关拨开的瞬间要靠它触发一次重画，
                    // 否则导航栏没有理由自己 invalidate，屏幕上的黑条会一直留着。
                    try { repaint(param); }
                    catch (Throwable error) { report("背景重绘失败，已停用", error); }
                }
            });
        } catch (Throwable error) {
            report("导航栏背景钩子不可用", error);
        }
    }

    /** 原厂这一帧画的是纯黑、且不在锁屏上 —— 就在同一块画布上覆盖成白色。 */
    private static void repaint(XC_MethodHook.MethodHookParam param) throws Exception {
        Object drawable = param.thisObject;
        if (!NAV_TAG.equals(tagField.get(drawable))) return;
        remember(drawable);
        if (!enabled) return;
        int color = ((Integer) colorField.get(drawable)).intValue();
        if (color != BLACK || keyguardShowing(drawable)) {
            setWhite(false);
            return;
        }
        setWhite(true);
        Canvas canvas = (Canvas) param.args[0];
        Object frame = frameField.get(drawable);
        if (frame instanceof Rect) canvas.drawRect((Rect) frame, WHITE_PAINT);
        else canvas.drawPaint(WHITE_PAINT);
    }

    /** 记下导航栏背景对象；只有换成另一个对象时才重新分配弱引用（每帧都会走这里）。 */
    private static void remember(Object drawable) {
        WeakReference<Object> reference = NAV_BACKGROUND;
        if (reference != null && reference.get() == drawable) return;
        NAV_BACKGROUND = new WeakReference<Object>(drawable);
    }

    /**
     * 开关切换后主动让导航栏重画一次。
     *
     * <p>导航栏是一块独立窗口，背景只有它自己 invalidate 时才会重画；改完配置那一刻它通常
     * 没有理由重画（图标没变、模式没变），于是屏幕上的黑条会一直留着，看起来像「没生效」。
     * 记住最后一次画过的那个背景对象，切开关时叫它自废一次即可。
     */
    private static void invalidate() {
        WeakReference<Object> reference = NAV_BACKGROUND;
        Object drawable = reference == null ? null : reference.get();
        if (!(drawable instanceof Drawable)) return;
        try { ((Drawable) drawable).invalidateSelf(); }
        catch (Throwable error) { report("导航栏重画失败", error); }
    }

    /**
     * 复刻原厂那半个例外：锁屏且非通话时原厂本来就不刷黑，那边保持原样。
     * 只问 KeyguardMonitor（进程内的两个布尔），不问 TelecomManager —— 后者是跨进程调用，
     * 原版每帧多打一次，是耗电的主要来源；锁屏时两种情形原厂都是黑底，跳过重绘即可等价。
     */
    private static boolean keyguardShowing(Object drawable) throws Exception {
        Object outer = outerField.get(drawable);
        if (outer == null) return false;
        Object monitor = keyguardField.get(outer);
        if (monitor == null) return false;
        if (!((Boolean) provisionedField.get(outer)).booleanValue()) return true;
        boolean showing = ((Boolean) isShowingMethod.invoke(monitor)).booleanValue();
        boolean secure = ((Boolean) isSecureMethod.invoke(monitor)).booleanValue();
        return showing && secure;
    }

    private static void installIcons(ClassLoader loader) {
        try {
            XposedHelpers.findAndHookMethod(ICON, loader, "setDarkIntensity", float.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    Object icon = param.thisObject;
                    ICONS.put(icon, (Float) param.args[0]);
                    // 原厂刚按「亮度」算好滤镜；白底时直接盖成纯黑，非白底就保留原厂结果。
                    if (!enabled || !white) return;
                    try { paint(icon, true); }
                    catch (Throwable error) { report("导航键取色失败", error); }
                }
            });
        } catch (Throwable error) {
            report("导航键钩子不可用", error);
        }
    }

    private static void setWhite(boolean value) {
        if (white == value) return;
        white = value;
        sync();
    }

    /** 把当前结论刷到所有见过的导航键上（只在白/黑切换或开关变化时调用，不是每帧）。 */
    private static void sync() {
        try {
            Object[] icons;
            synchronized (ICONS) { icons = ICONS.keySet().toArray(); }
            for (Object icon : icons) {
                if (!(icon instanceof Drawable)) continue;
                Object drawable = icon;
                Float intensity = ICONS.get(drawable);
                paint(drawable, enabled && white, intensity == null ? 1f : intensity.floatValue());
            }
        } catch (Throwable error) {
            report("导航键联动失败", error);
        }
    }

    private static void paint(Object drawable, boolean black) throws Exception {
        paint(drawable, black, null);
    }

    /** black=true 用纯黑滤镜；否则按原厂公式从亮度与浅/深两色重新算一遍。 */
    private static void paint(Object drawable, boolean black, Float intensity) throws Exception {
        Drawable target = (Drawable) drawable;
        if (black) {
            target.setColorFilter(BLACK_FILTER);
            return;
        }
        if (intensity == null) return;
        Object state = stateField.get(drawable);
        if (state == null) return;
        int light = lightField.getInt(state), dark = darkField.getInt(state);
        Object color = EVALUATOR.evaluate(intensity.floatValue(), Integer.valueOf(light), Integer.valueOf(dark));
        target.setColorFilter(new PorterDuffColorFilter(((Integer) color).intValue(), PorterDuff.Mode.SRC_ATOP));
    }

    /** 配置变化：开关一动，导航栏与已画过的键立刻跟上，不需要重启系统界面。 */
    private static void refresh() {
        boolean now = RuntimeConfig.enabled(FEATURE);
        if (now == enabled) return;
        enabled = now;
        white = now;
        sync();
        invalidate();
    }

    private static void report(String what, Throwable error) {
        if (!REPORTED.add(what)) return;
        XposedBridge.log(TAG + what + "：" + error);
    }
}
