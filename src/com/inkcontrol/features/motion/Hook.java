package com.inkcontrol.features.motion;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.graphics.Rect;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collection;

import com.inkcontrol.platform.RuntimeConfig;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 海信 A5 墨水屏系统过渡动画（作用域：系统框架 / system_server）。
 *
 * 海信在墨水屏上把窗口和过渡动画倍率强制为 0（WMS.mAnimationsDisabled）。这里不去改倍率，
 * 而是在 AppWindowToken.loadAnimation 返回之后，按场景把动画换成自己构造的阶梯裁剪动画，
 * 时长不受倍率影响。没选动画的场景、对话框、输入法等窗口动画都保持原样（瞬间出现）。
 *
 * 每个钩子都包在 try/catch 里，出错就保留系统原来的结果。
 */
public class Hook implements IXposedHookLoadPackage {
    static final String TAG = "EinkMotion";

    private static final int TRANSIT_ACTIVITY_OPEN = 6, TRANSIT_ACTIVITY_CLOSE = 7;
    private static final int TRANSIT_TASK_OPEN = 8, TRANSIT_TASK_CLOSE = 9;
    private static final int TRANSIT_TASK_TO_FRONT = 10, TRANSIT_TASK_TO_BACK = 11;
    private static final int TRANSIT_WALLPAPER_CLOSE = 12, TRANSIT_WALLPAPER_OPEN = 13;
    private static final int TRANSIT_WALLPAPER_INTRA_OPEN = 14, TRANSIT_WALLPAPER_INTRA_CLOSE = 15;
    private static final int TRANSIT_TRANSLUCENT_ACTIVITY_OPEN = 24, TRANSIT_TRANSLUCENT_ACTIVITY_CLOSE = 25;
    /** 窗口级过渡：WindowManagerPolicy.TRANSIT_ENTER/EXIT/SHOW/HIDE */
    private static final int WTRANSIT_ENTER = 1, WTRANSIT_HIDE = 4, WTRANSIT_SHOW = 3;
    /** AppTransition.NEXT_TRANSIT_TYPE_CUSTOM：应用调用了 overridePendingTransition */
    private static final int NEXT_TRANSIT_TYPE_CUSTOM = 1;
    /** 点击后多久之内的过渡，用点击位置当展开起点 */
    private static final long TOUCH_VALID_MS = 3000;
    /** 融合分带的翻页方向：0 = 正向，1 = 反向（配合时间戳，过期按正向处理）。 */
    static final String PROP_FLIP = "debug.inkcontrol.fusion.flip";

    private static boolean sHooked;
    static volatile Config sCfg = new Config();
    private static Context sContext;
    private static Handler sBg;
    private static Object sWms;
    private static Boolean sOrigDisableTransition;

    private static volatile float sBatteryC = 30f;
    private static volatile float sTouchX = -1, sTouchY = -1;
    private static volatile long sTouchTime;
    /** 按下点，用来判断这一次手势是「翻下一页」还是「翻上一页」；与展开起点分开记。 */
    private static volatile float sDownX = -1, sDownY = -1;
    private static volatile long sDownTime;
    /** 水平位移超过它才算滑动翻页，低于 TAP_SLOP 算点击；两者之间（含竖向拖动）不改变方向。 */
    private static final float SWIPE_MIN = 48f, TAP_SLOP = 24f;
    /** 最近一次写进 fusion.flip 的值，-1 表示还没写过。 */
    private static int sFlip = -1;
    private static long sFlipTime;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!"android".equals(lpparam.packageName) || sHooked) return;
        sHooked = true;
        ClassLoader cl = lpparam.classLoader;

        try {
            XposedHelpers.findAndHookMethod("com.android.server.wm.WindowManagerService", cl, "systemReady",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                init(param.thisObject);
                            } catch (Throwable t) {
                                Log.e(TAG, "init failed", t);
                            }
                        }
                    });
        } catch (Throwable t) {
            Log.e(TAG, "hook systemReady failed", t);
        }

        try {
            XposedBridge.hookAllMethods(XposedHelpers.findClass("com.android.server.wm.AppWindowToken", cl),
                    "loadAnimation", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Animation a = onLoadAnimation(param.thisObject, param.args, (Animation) param.getResult());
                                if (a != null) param.setResult(a);
                            } catch (Throwable t) {
                                Log.e(TAG, "loadAnimation hook failed", t);
                            }
                        }
                    });
        } catch (Throwable t) {
            Log.e(TAG, "hook loadAnimation failed", t);
        }

        try {
            // Dimmer.dim(Transaction, WindowContainer, int relativeLayer, float alpha) 是 dimAbove/dimBelow 的共同入口
            XposedBridge.hookAllMethods(XposedHelpers.findClass("com.android.server.wm.Dimmer", cl),
                    "dim", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (sCfg.noDim) param.setResult(null);
                        }
                    });
        } catch (Throwable t) {
            Log.e(TAG, "hook Dimmer failed", t);
        }
        try {
            // 应用内动画倍率（which=2）：海信在墨水屏上固定返回 1.0，这里按配置改
            XposedBridge.hookAllMethods(XposedHelpers.findClass("com.android.server.wm.WindowManagerService", cl),
                    "animationScalesCheck", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            float s = sCfg.appAnimScale;
                            if (s >= 0 && param.args.length == 1 && Integer.valueOf(2).equals(param.args[0])) param.setResult(s);
                        }
                    });
        } catch (Throwable t) {
            Log.e(TAG, "hook animationScalesCheck failed", t);
        }

        try {
            // 对话框、弹出菜单的窗口级动画
            XposedBridge.hookAllMethods(XposedHelpers.findClass("com.android.server.wm.WindowStateAnimator", cl),
                    "applyAnimationLocked", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                Object r = onWindowAnimation(param.thisObject, (Integer) param.args[0], (Boolean) param.args[1]);
                                if (r != null) param.setResult(r);
                            } catch (Throwable t) {
                                Log.e(TAG, "window animation hook failed", t);
                            }
                        }
                    });
        } catch (Throwable t) {
            Log.e(TAG, "hook WindowStateAnimator failed", t);
        }

        try {
            // 只用来记录：桌面用远程动画时不经过 loadAnimation，这里能看出来
            XposedBridge.hookAllMethods(XposedHelpers.findClass("com.android.server.wm.AppWindowToken", cl),
                    "applyAnimationLocked", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                if (!sCfg.debug) return;
                                Object dc = XposedHelpers.callMethod(param.thisObject, "getDisplayContent");
                                Object at = XposedHelpers.getObjectField(dc, "mAppTransition");
                                if (XposedHelpers.getObjectField(at, "mRemoteAnimationController") != null) {
                                    Log.i(TAG, "transit=" + param.args[1] + " uses remote animation, not replaced");
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    });
        } catch (Throwable t) {
            Log.e(TAG, "hook applyAnimationLocked failed", t);
        }
        LockTransitions.install(cl);
        hookVolumeKeys(cl);
        Log.i(TAG, "hooks installed");
    }

    // ---------------------------------------------------------------- 初始化与配置

    private static void init(Object wms) {
        if (sContext != null) return;
        sWms = wms;
        sContext = (Context) XposedHelpers.getObjectField(wms, "mContext");
        RuntimeConfig.attach(sContext, "system");
        RuntimeConfig.listen(Hook::reload);
        try {
            sOrigDisableTransition = XposedHelpers.getBooleanField(wms, "mDisableTransitionAnimation");
        } catch (Throwable t) {
            sOrigDisableTransition = null;
        }
        HandlerThread ht = new HandlerThread("EinkMotion");
        ht.start();
        sBg = new Handler(ht.getLooper());

        sBg.post(Hook::reload);

        try {
            sContext.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent i) {
                    int t = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
                    if (t != Integer.MIN_VALUE) sBatteryC = t / 10f;
                }
            }, new IntentFilter(Intent.ACTION_BATTERY_CHANGED), null, sBg);
        } catch (Throwable t) {
            Log.e(TAG, "battery receiver failed", t);
        }

        try {
            registerTouchListener(wms);
        } catch (Throwable t) {
            Log.e(TAG, "touch listener failed", t);
        }
        Log.i(TAG, "ready, mDisableTransitionAnimation=" + sOrigDisableTransition);
        RuntimeConfig.markReady();
    }

    private static void reload() {
        try {
            ContentResolver cr = sContext.getContentResolver();
            Config c = Config.from(RuntimeConfig.snapshot());
            sCfg = c;
            Object lock = XposedHelpers.getObjectField(sWms, "mGlobalLock");
            synchronized (lock) { applyDisableTransitionField(c); }
            HwcBridge.configure(c);
            try {
                // 通知所有应用进程更新属性动画倍率
                XposedHelpers.callMethod(sWms, "dispatchNewAnimatorScaleLocked", (Object) null);
            } catch (Throwable t) {
                Log.e(TAG, "dispatch animator scale failed", t);
            }
            int hold = holdMs(c);
            String ack = c.rev + ";" + c.summary() + " hold_now=" + hold + "ms battery=" + sBatteryC
                    + "C pid=" + android.os.Process.myPid() + " probe=" + "inkcontrol"
                    + " wave=" + HwcBridge.capabilities() + " lock_hooks=" + LockTransitions.installed;
            // 运行确认由统一 Provider 保存，避免系统键成为第二配置源。
            Log.i(TAG, "config " + ack);
        } catch (Throwable t) {
            Log.e(TAG, "reload failed", t);
            RuntimeConfig.fail("动画配置应用失败：" + t.getClass().getSimpleName());
        }
    }

    /** 有的机型用资源开关整个禁掉过渡动画；启用了系统层动画时临时放开，关闭时恢复原值 */
    private static void applyDisableTransitionField(Config c) {
        if (sOrigDisableTransition == null || !sOrigDisableTransition) return;
        boolean any = false;
        for (int i = 0; i < Config.SCENES.length; i++) any |= Config.isSystemStyle(c.styleFor(i));
        XposedHelpers.setBooleanField(sWms, "mDisableTransitionAnimation", !any);
    }

    private static int holdMs(Config c) {
        // 曲线某段为 0 表示该温区沿用自动值。
        if (c.holdCurve != null) { int v = c.holdCurve[com.inkcontrol.core.FeatureDefinition.band(sBatteryC)]; return v > 0 ? v : Geometry.autoHoldMs(sBatteryC); }
        return c.holdMs > 0 ? c.holdMs : Geometry.autoHoldMs(sBatteryC);
    }

    /** 记录最近一次按下的位置，作为方形展开的起点（点哪个图标就从哪里展开） */
    private static void registerTouchListener(Object wms) throws Exception {
        ClassLoader cl = wms.getClass().getClassLoader();
        Class<?> itf = Class.forName("android.view.WindowManagerPolicyConstants$PointerEventListener", false, cl);
        Object proxy = Proxy.newProxyInstance(cl, new Class<?>[]{itf}, new InvocationHandler() {
            @Override
            public Object invoke(Object self, Method m, Object[] args) {
                switch (m.getName()) {
                    case "onPointerEvent":
                        if (args != null && args.length > 0 && args[0] instanceof MotionEvent) {
                            MotionEvent e = (MotionEvent) args[0];
                            int action = e.getActionMasked();
                            if (action == MotionEvent.ACTION_DOWN) {
                                sTouchX = e.getRawX();
                                sTouchY = e.getRawY();
                                sTouchTime = SystemClock.uptimeMillis();
                                sDownX = sTouchX;
                                sDownY = sTouchY;
                                sDownTime = sTouchTime;
                            } else if (action == MotionEvent.ACTION_UP) {
                                detectPageTurn(e.getRawX(), e.getRawY());
                            } else if (action == MotionEvent.ACTION_CANCEL) {
                                sDownX = sDownY = -1;
                            }
                        }
                        return null;
                    case "equals":
                        return self == args[0];
                    case "hashCode":
                        return System.identityHashCode(self);
                    case "toString":
                        return "EinkMotionTouch";
                    default:
                        return null;
                }
            }
        });
        Method reg = wms.getClass().getMethod("registerPointerEventListener", itf, int.class);
        reg.invoke(wms, proxy, 0);
    }

    /**
     * 抬手时判断这一次是「翻下一页」还是「翻上一页」，把方向写给融合分带。
     *
     * <p>老的独立翻页动画库自己拦输入，所以滑动/点边会朝对应方向展开；融合没有常驻端，
     * 只能在 system_server 里从同一条指针事件流里取方向：左滑/点右侧＝下一页（反向），
     * 右滑/点左侧＝上一页（正向）。竖向拖动与中间区域不写，保留上一次的值（由 native 侧过期）。
     *
     * <p>方向的定义取自实测：分带坐标的 row 轴是屏幕横向轴，正向（direction=1）是
     * 从左向右推，反向（direction=2）是从右向左推。翻下一页（左滑、点右半屏）时新页
     * 从右往左盖过来才对得上老库的观感，所以「翻下一页」写反向；翻上一页才写正向。
     */
    private static void detectPageTurn(float upX, float upY) {
        if (sDownX < 0) return;
        float dx = upX - sDownX, dy = upY - sDownY;
        long held = SystemClock.uptimeMillis() - sDownTime;
        sDownX = sDownY = -1;
        if (held > 1200) return;
        int flip;
        if (Math.abs(dx) >= SWIPE_MIN && Math.abs(dx) >= Math.abs(dy) * 2f) {
            flip = dx > 0 ? 0 : 1;                       // 手往右＝翻上一页＝正向
        } else if (Math.abs(dx) <= TAP_SLOP && Math.abs(dy) <= TAP_SLOP) {
            flip = upX >= screenWidth() / 2f ? 1 : 0;    // 点右半屏＝翻下一页＝反向
        } else return;
        writeFlip(flip);
    }

    /**
     * 实体音量键翻页的方向。
     *
     * <p>音量键没有「点在哪一边」的信息，融合分带只能沿用上一次写进去的 flip，表现出来就是
     * 「上次在右侧点过，按音量+ 时动画也从右往左」。这里在按键分发前按习惯重写一次：
     * 音量+＝上一页（正向），音量-＝下一页（反向），正好和「点左半屏／右滑＝上一页」一致。
     *
     * <p>「音量键遵从翻页直觉」关掉就不覆盖，回到跟手势走的老行为（用户可能是想那样用）。
     * 两个入口都挂上：音量键在有些机型按队列阶段处理、有些在分发阶段，重复写同一个值会被去重。
     */
    private static void hookVolumeKeys(ClassLoader cl) {
        for (String method : new String[]{"interceptKeyBeforeQueueing", "interceptKeyBeforeDispatching"}) {
            try {
                XposedBridge.hookAllMethods(XposedHelpers.findClass("com.android.server.policy.PhoneWindowManager", cl),
                        method, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                for (Object arg : param.args) {
                                    if (arg instanceof KeyEvent) {
                                        try {
                                            onVolumeKey((KeyEvent) arg);
                                        } catch (Throwable t) {
                                            Log.e(TAG, "volume key handling failed", t);
                                        }
                                        return;
                                    }
                                }
                            }
                        });
            } catch (Throwable t) {
                Log.e(TAG, "hook " + method + " failed", t);
            }
        }
    }

    private static void onVolumeKey(KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN || event.getRepeatCount() > 0) return;
        int flip;
        if (event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_UP) flip = 0;
        else if (event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_DOWN) flip = 1;
        else return;
        try {
            if (!RuntimeConfig.enabled("pageanim.vkey") || !RuntimeConfig.enabled("pageanim.master")) return;
        } catch (Throwable unknown) {
            // 配置还没就绪（开机早期）：这一下不改方向，等下一次按键
            return;
        }
        writeFlip(flip);
        if (sCfg.debug) Log.i(TAG, "volume " + (flip == 0 ? "up -> 上一页" : "down -> 下一页"));
    }

    /** 把翻页方向写给融合分带：0 = 正向（从左向右推），1 = 反向（从右向左推）。 */
    private static void writeFlip(int flip) {
        if (flip == sFlip && SystemClock.uptimeMillis() - sFlipTime < 1000) return;
        sFlip = flip;
        sFlipTime = SystemClock.uptimeMillis();
        try {
            // 值里带时间戳：native 侧 4 秒后按「正向」处理，避免翻页菜单、滚动等
            // 不改变方向的手势沿用上一次的反向。
            Class.forName("android.os.SystemProperties").getMethod("set", String.class, String.class)
                    .invoke(null, PROP_FLIP, flip + " " + sFlipTime);
        } catch (Throwable t) { Log.e(TAG, "fusion flip property failed", t); }
    }

    private static int screenWidth() {
        try {
            int width = sContext.getResources().getDisplayMetrics().widthPixels;
            return width > 0 ? width : 720;
        } catch (Throwable t) { return 720; }
    }

    // ---------------------------------------------------------------- 过渡动画

    /** 返回要替换成的动画；返回 null 表示保留系统原来的结果 */
    private static Animation onLoadAnimation(Object token, Object[] args, Animation original) {
        Config c = sCfg;
        if (!c.enabled) return null;
        int transit = (Integer) args[1];
        boolean enter = (Boolean) args[2];

        Object dc = XposedHelpers.callMethod(token, "getDisplayContent");
        if (dc == null) return null;
        Object at = XposedHelpers.getObjectField(dc, "mAppTransition");
        // 应用自己要求不要动画（overridePendingTransition(0, 0)），尊重它
        if (XposedHelpers.getIntField(at, "mNextAppTransitionType") == NEXT_TRANSIT_TYPE_CUSTOM
                && XposedHelpers.getIntField(at, enter ? "mNextAppTransitionEnter" : "mNextAppTransitionExit") == 0) {
            return null;
        }

        int scene = classify(dc, transit);
        if (scene < 0) return null;
        String style = c.styleFor(scene);
        boolean grow = scene == Config.APP_OPEN || scene == Config.FORWARD;

        Object info = XposedHelpers.callMethod(dc, "getDisplayInfo");
        int w = XposedHelpers.getIntField(info, "logicalWidth");
        int h = XposedHelpers.getIntField(info, "logicalHeight");
        float ox = w / 2f, oy = h / 2f;
        if (grow && "touch".equals(c.origin) && sTouchX >= 0
                && SystemClock.uptimeMillis() - sTouchTime < TOUCH_VALID_MS) {
            ox = Math.max(0, Math.min(w - 1, sTouchX));
            oy = Math.max(0, Math.min(h - 1, sTouchY));
        }
        String dir = c.wipeDir;
        if ("auto".equals(dir)) dir = grow ? "rtl" : "ltr";

        if (Config.isPluginStyle(style)) {
            if (!HwcBridge.supports(style)) return null;
            // 只让 enter 那一侧发请求，同一次过渡不重复；写属性放到后台线程，不占 WMS 的锁
            if (enter) {
                final String st = style, d = "auto".equals(c.wipeDir) ? "ttb" : c.wipeDir;
                final float x = ox, y = oy;
                final int sp = c.spread;
                HwcBridge.request(st, sp, x, y, d);
            }
            if (c.debug && enter) log(transit, enter, scene, style + " -> HWC wave request", 0);
            return null;
        }
        if (!Config.isSystemStyle(style)) {
            if (c.debug && enter) log(transit, enter, scene, style, 0);
            return null;
        }

        int hold = holdMs(c);
        long dur = (long) hold * (c.steps - 1);
        Animation a;
        if (grow == enter) {
            // 展开时裁剪新窗口，收缩时裁剪旧窗口；被裁剪的那个放在上层
            a = new StepClipAnimation(Geometry.stepRects(style, grow, c.steps, w, h, ox, oy, dir), dur);
        } else {
            // 另一个窗口原样停住，直到动画结束
            a = new HoldAnimation(dur);
            a.setZAdjustment(Animation.ZORDER_NORMAL);
        }
        a.initialize(w, h, w, h);
        if (c.debug) log(transit, enter, scene, style + (grow == enter ? " clip" : " hold")
                + " origin=" + (int) ox + "," + (int) oy + " dir=" + dir, dur);
        return a;
    }

    /**
     * 对话框（TYPE_APPLICATION）、弹出菜单（面板、子面板、附着对话框）、系统对话框的出现和消失。
     * 返回 null 交给系统原来的逻辑（海信下就是瞬间出现）；否则返回 applyAnimationLocked 的结果。
     */
    private static Object onWindowAnimation(Object wsa, int transit, boolean entrance) {
        Config c = sCfg;
        String style = c.styleFor(Config.DIALOG);
        if ((!Config.isSystemStyle(style) && !Config.isPluginStyle(style)) || transit < WTRANSIT_ENTER || transit > WTRANSIT_HIDE) return null;
        Object win = XposedHelpers.getObjectField(wsa, "mWin");
        int type = ((WindowManager.LayoutParams) XposedHelpers.getObjectField(win, "mAttrs")).type;
        if (type != 2 && type != 1000 && type != 1002 && type != 1003 && type != 2008) return null;
        if (Boolean.TRUE.equals(XposedHelpers.callMethod(win, "isSelfAnimating"))
                && XposedHelpers.getBooleanField(wsa, "mAnimationIsEntrance") == entrance) {
            return Boolean.TRUE;
        }
        Object token = XposedHelpers.getObjectField(win, "mToken");
        if (!Boolean.TRUE.equals(XposedHelpers.callMethod(token, "okToAnimate"))) return null;
        Rect frame = (Rect) XposedHelpers.callMethod(win, "getFrameLw");
        int w = frame.width(), h = frame.height();
        if (w <= 0 || h <= 0) return null;

        float ox = w / 2f, oy = h / 2f;
        if (entrance && "touch".equals(c.origin) && sTouchX >= 0
                && SystemClock.uptimeMillis() - sTouchTime < TOUCH_VALID_MS) {
            ox = Math.max(0, Math.min(w - 1, sTouchX - frame.left));
            oy = Math.max(0, Math.min(h - 1, sTouchY - frame.top));
        }
        String dir = "auto".equals(c.wipeDir) ? "ttb" : c.wipeDir;
        if (Config.isPluginStyle(style)) {
            HwcBridge.request(style, c.spread, ox + frame.left, oy + frame.top, dir, true);
            return null;
        }
        long dur = (long) holdMs(c) * (c.steps - 1);
        Animation a = new StepClipAnimation(Geometry.stepRects(style, entrance, c.steps, w, h, ox, oy, dir), dur);
        XposedHelpers.callMethod(win, "startAnimation", a);
        XposedHelpers.setBooleanField(win, "mExitAnimNull", false);
        XposedHelpers.setBooleanField(wsa, "mAnimationIsEntrance", entrance);
        if (c.debug) Log.i(TAG, "window type=" + type + " transit=" + transit + " -> " + style
                + (entrance ? " show" : " hide") + " " + w + "x" + h + " " + dur + "ms");
        return XposedHelpers.callMethod(win, "isAnimating");
    }

    /** 根据桌面在打开还是关闭来区分“打开应用 / 返回桌面”，其余按过渡类型 */
    private static int classify(Object dc, int transit) {
        switch (transit) {
            case TRANSIT_ACTIVITY_OPEN: case TRANSIT_ACTIVITY_CLOSE:
            case TRANSIT_TASK_OPEN: case TRANSIT_TASK_CLOSE:
            case TRANSIT_TASK_TO_FRONT: case TRANSIT_TASK_TO_BACK:
            case TRANSIT_WALLPAPER_CLOSE: case TRANSIT_WALLPAPER_OPEN:
            case TRANSIT_WALLPAPER_INTRA_OPEN: case TRANSIT_WALLPAPER_INTRA_CLOSE:
            case TRANSIT_TRANSLUCENT_ACTIVITY_OPEN: case TRANSIT_TRANSLUCENT_ACTIVITY_CLOSE:
                break;
            default:
                return -1; // 锁屏、重建、分屏等保持原样
        }
        boolean homeOpening = hasHome(XposedHelpers.getObjectField(dc, "mOpeningApps"));
        boolean homeClosing = hasHome(XposedHelpers.getObjectField(dc, "mClosingApps"));
        if (homeClosing && !homeOpening) return Config.APP_OPEN;
        if (homeOpening && !homeClosing) return Config.APP_CLOSE;
        switch (transit) {
            case TRANSIT_TASK_OPEN: case TRANSIT_TASK_TO_FRONT: case TRANSIT_WALLPAPER_CLOSE:
                return Config.APP_OPEN;
            case TRANSIT_TASK_CLOSE: case TRANSIT_TASK_TO_BACK: case TRANSIT_WALLPAPER_OPEN:
                return Config.APP_CLOSE;
            case TRANSIT_ACTIVITY_OPEN: case TRANSIT_WALLPAPER_INTRA_OPEN: case TRANSIT_TRANSLUCENT_ACTIVITY_OPEN:
                return Config.FORWARD;
            default:
                return Config.BACK;
        }
    }

    private static boolean hasHome(Object apps) {
        if (!(apps instanceof Collection)) return false;
        for (Object t : (Collection<?>) apps) {
            if (Boolean.TRUE.equals(XposedHelpers.callMethod(t, "isActivityTypeHome"))) return true;
        }
        return false;
    }

    private static void log(int transit, boolean enter, int scene, String what, long dur) {
        Log.i(TAG, "transit=" + transit + " enter=" + enter + " scene=" + Config.SCENES[scene]
                + " -> " + what + (dur > 0 ? " " + dur + "ms" : ""));
    }
}
