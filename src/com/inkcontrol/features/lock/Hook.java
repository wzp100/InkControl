package com.inkcontrol.features.lock;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.widget.TextView;

import java.lang.reflect.Method;

import cn.lyric.getter.api.data.LyricData;
import com.inkcontrol.platform.RuntimeConfig;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 海信 A5 墨水屏：息屏画面“格言”区和状态栏显示当前歌词，并缩短锁屏出图时间。
 * 歌词来源：系统媒体会话里的 "lyricInfo"（网易云的流体云出口，见 MediaLyric），
 * 以及 Lyric Getter 模块发出的 "Lyric_Data" 广播。
 */
public class Hook implements IXposedHookLoadPackage {
    private static final String TAG = "EinkLyric: ";
    private static final String CONTROLLER = "com.eink.keyguard.EinkKeyguardController";
    private static final String MOTTO_HELPER = "com.hmct.motto.MottoHelper";
    private static final String UTIL = "com.eink.keyguard.Util";

    /** 调参开关（settings put global ...），-1 表示保持系统原样 */
    private static final String KEY_DELAY = "einklyric_delay_ms";
    private static final String KEY_FIRST_MODE = "einklyric_first_mode";
    private static final String KEY_UPDATE_MODE = "einklyric_update_mode";
    private static final String KEY_LYRIC_MODE = "einklyric_lyric_mode";
    private static final String KEY_STATUSBAR = "einklyric_statusbar";

    /** 两次墨水屏重绘的最小间隔，避免一句接一句时连续闪屏 */
    private static final long MIN_REFRESH_INTERVAL_MS = 1500;
    /** 超过这么久没收到新歌词就当作已停止，恢复原格言 */
    private static final long LYRIC_STALE_MS = 3 * 60 * 1000;

    private static boolean sHooked;
    private static Object sController;
    private static Method sRefreshMethod;
    private static Context sContext;
    private static Handler sHandler;
    private static ClassLoader sLoader;

    /** 应用里点「换一条格言」时发出的广播；本 Hook 收到后让原厂的 MottoHelper 跳到下一条。 */
    public static final String ACTION_MOTTO_NEXT = "com.inkcontrol.action.LOCK_MOTTO_NEXT";

    /**
     * 手动换来的那一条格言。原厂 {@code setMotto} 是按它自己记的序号出图，
     * {@code getNextMotto()} 不一定立刻反映到下一次出图上，所以这里先兜住返回值，
     * 由 {@link LockData#motto} 优先使用；锁屏画过一次之后再交回原厂。
     */
    private static volatile String sNextMotto;
    private static volatile long sNextMottoAt;
    private static final long MOTTO_HOLD_MS = 10 * 60 * 1000;

    private static volatile String sLyric;
    private static volatile long sLyricTime;
    private static long sLastRefresh;
    private static boolean sRefreshPending;
    private static boolean sInLyricRefresh;
    private static boolean sHadVisual;

    private static int getSetting(String key, int def) {
        // 调试开关不在功能配置里，直接读 Settings.Global（settings put global einklyric_debug_capture 1）
        if ("einklyric_debug_capture".equals(key)) {
            return sContext == null ? def : Settings.Global.getInt(sContext.getContentResolver(), key, def);
        }
        return LockSettings.read(key, def);
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if ("android".equals(lpparam.packageName) && "android".equals(lpparam.processName)) {
            try {
                BottomBarHook.hook(lpparam.classLoader);
            } catch (Throwable t) {
                XposedBridge.log(TAG + "system_server hook failed: " + t);
            }
            return;
        }
        if (!"com.android.systemui".equals(lpparam.packageName)
                || !"com.android.systemui".equals(lpparam.processName)) return;
        ClassLoader cl = lpparam.classLoader;
        // SystemUI 进程里还会加载别的 classloader（插件等），只挂第一个带锁屏代码的
        if (sHooked) return;
        try {
            cl.loadClass(CONTROLLER);
        } catch (ClassNotFoundException e) {
            return;
        }
        sHooked = true;
        sLoader = cl;

        XposedHelpers.findAndHookMethod(MOTTO_HELPER, cl, "setMotto",
                Context.class, TextView.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        String lyric = RuntimeConfig.enabled("lock.lyric") ? activeLyric() : null;
                        if (lyric != null) {
                            ((TextView) param.args[1]).setText(lyric);
                            param.setResult(null);
                        }
                    }
                });

        // 息屏后海信固定等 400/500ms 才画锁屏图。屏幕断电约需 0.5s，
        // 早于断电完成送图会出现半屏撕裂，所以默认 300ms（从息屏通知算起）
        XposedHelpers.findAndHookMethod(CONTROLLER, cl, "getSecondPostDelayTime",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        int delay = getSetting(KEY_DELAY, 300);
                        if (delay >= 0) param.setResult(delay);
                    }
                });

        // 首次清晰刷新与走时/通知更新分别配置，默认沿用原厂 2/3 策略。
        XposedHelpers.findAndHookMethod(UTIL, cl, "poster",
                Bitmap.class, int.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        int mode = (Integer) param.args[1];
                        int override = sInLyricRefresh ? getSetting(KEY_LYRIC_MODE, -1)
                                : mode == 2 ? getSetting(KEY_FIRST_MODE, 2)
                                : mode == 3 ? getSetting(KEY_UPDATE_MODE, 3) : -1;
                        if (override >= 0) param.args[1] = override;
                        // 调试：settings put global einklyric_debug_capture 1 → 保存实际送屏的图
                        if (getSetting("einklyric_debug_capture", 0) == 1) {
                            SystemLockStyles.capture(sContext, (Bitmap) param.args[0], (Integer) param.args[1]);
                        }
                    }
                });

        XposedHelpers.findAndHookConstructor(CONTROLLER, cl,
                Context.class, XposedHelpers.findClass(CONTROLLER + "$Callback", cl),
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        init(param.thisObject, (Context) param.args[0]);
                    }
                });
        try {
            StatusBarLyric.hook(cl);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "status bar hook failed: " + t);
        }
        try {
            SystemLockStyles.hook(cl);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "lock style hook failed: " + t);
        }
        try {
            LockNotifications.hook(cl);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "notification hook failed: " + t);
        }
        XposedBridge.log(TAG + "hooked SystemUI");
    }

    static boolean statusBarEnabled() {
        return RuntimeConfig.enabled("lock.statusbar");
    }

    private static void init(Object controller, Context context) {
        sController = controller;
        sContext = context;
        RuntimeConfig.listen(() -> {
            StatusBarLyric.update(sLyric);
            boolean active = RuntimeConfig.enabled("lock.lyric") || RuntimeConfig.enabled("lock.style");
            requestRefresh(sHadVisual && !active);
            sHadVisual = active;
            // 切到「天气App」后立即取一次，下一次出图就有数据
            if (appWeather()) MojiWeatherSource.prefetch(sContext);
        });
        sHandler = new Handler(Looper.getMainLooper());
        sRefreshMethod = findRefreshMethod(controller.getClass());
        if (sRefreshMethod == null) {
            XposedBridge.log(TAG + "refresh method not found, lyric only shows on minute tick");
        }

        final ClassLoader moduleLoader = Hook.class.getClassLoader();
        context.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                try {
                    // 调试：am broadcast -a Lyric_Data --es einklyric_test "歌词"（空串表示停止）
                    String test = intent.getStringExtra("einklyric_test");
                    if (test != null) {
                        LyricData d = LyricData.of(test.isEmpty()
                                ? LyricData.TYPE_STOP : LyricData.TYPE_UPDATE, test);
                        onLyric(d);
                        return;
                    }
                    intent.setExtrasClassLoader(moduleLoader);
                    Parcelable p = intent.getParcelableExtra("Data");
                    if (!(p instanceof LyricData)) return;
                    onLyric((LyricData) p);
                } catch (Throwable t) {
                    XposedBridge.log(TAG + "bad lyric broadcast: " + t);
                }
            }
        }, new IntentFilter("Lyric_Data"), null, sHandler);
        // 应用「锁屏主题」里点「换一条格言」→ 这里替用户手动把原厂格言往前跳一条。
        context.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) { nextMotto(c); }
        }, new IntentFilter(ACTION_MOTTO_NEXT), null, sHandler);
        if (com.inkcontrol.platform.BuildInfo.DEBUG) SystemLockStyles.registerDebug(context, controller, sHandler);
        RuntimeConfig.markReady();
        MediaLyric.start(context, sHandler);
        if (appWeather()) MojiWeatherSource.prefetch(context);
        XposedBridge.log(TAG + "receiver registered");
    }

    /**
     * 让原厂格言换到下一条。{@code MottoHelper.getNextMotto()} 返回的正文会先兜在这里，
     * 锁屏下次出图优先用它；原厂自己记的序号一般也会跟着往前走。
     */
    private static void nextMotto(Context context) {
        String next = null;
        try {
            Class<?> helper = Class.forName(MOTTO_HELPER, false, sLoader != null ? sLoader : context.getClassLoader());
            Object instance = helper.getConstructor(Context.class).newInstance(context);
            Object value = callNextMotto(helper, instance);
            if (value instanceof String) next = ((String) value).trim();
        } catch (Throwable t) {
            XposedBridge.log(TAG + "next motto failed: " + t);
        }
        if (next == null || next.isEmpty()) { toast("格言没换成：系统没有给出下一条"); return; }
        sNextMotto = next;
        sNextMottoAt = SystemClock.uptimeMillis();
        int nl = next.indexOf('\n');
        toast("已换到下一条格言：" + (nl > 0 ? next.substring(0, nl).trim() : next));
    }

    /**
     * 原厂 {@code getNextMotto} 的签名在不同固件上不一样（无参 / 带 Context / 只返回资源 id），
     * 逐个试一遍；都对不上就把实际方法名记进日志，便于按固件补一条。
     */
    private static Object callNextMotto(Class<?> helper, Object instance) throws Exception {
        try {
            return helper.getMethod("getNextMotto").invoke(instance);
        } catch (Throwable first) {
            XposedBridge.log(TAG + "motto getMethod: " + first);
            // 原厂类不一定对当前进程可见（非 public 成员），直接声明查找并放开访问再来一次。
            try {
                Method method = helper.getDeclaredMethod("getNextMotto");
                method.setAccessible(true);
                return method.invoke(instance);
            } catch (Throwable second) {
                XposedBridge.log(TAG + "motto getDeclaredMethod: " + second);
            }
        }
        try {
            return helper.getMethod("getNextMotto", Context.class).invoke(instance, sContext);
        } catch (Throwable ignored) { }
        StringBuilder names = new StringBuilder();
        for (Method method : helper.getDeclaredMethods()) {
            if (method.getParameterTypes().length > 1) continue;
            if (names.length() > 0) names.append(", ");
            names.append(method.getName()).append('/').append(method.getParameterTypes().length);
        }
        XposedBridge.log(TAG + "motto methods: " + names);
        return null;
    }

    /** 手动换来的格言：只兜 10 分钟，够用户锁一次屏看到，之后交回原厂。 */
    static String pendingMotto() {
        if (sNextMotto == null || SystemClock.uptimeMillis() - sNextMottoAt > MOTTO_HOLD_MS) return null;
        return sNextMotto;
    }

    private static void toast(String text) {
        if (sHandler == null) return;
        sHandler.post(() -> {
            try {
                android.widget.Toast.makeText(sContext, text, android.widget.Toast.LENGTH_SHORT).show();
            } catch (Throwable t) { XposedBridge.log(TAG + "motto toast failed: " + t); }
        });
    }

    /** 锁屏天气选了「天气App」＝直接读系统预装的墨迹天气，而不是原厂桌面小组件那条链路。 */
    private static boolean appWeather() {        return RuntimeConfig.enabled("lock.weather") && "moji".equals(RuntimeConfig.value("lock.weather"));
    }

    private static Method findRefreshMethod(Class<?> c) {
        for (String name : new String[]{"refreshInstant",
                "lambda$newNotificationEnd$1$EinkKeyguardController"}) {
            try {
                Method m = c.getDeclaredMethod(name);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {
            }
        }
        return null;
    }

    static void onLyric(LyricData data) {
        String text = data.lyric == null ? "" : data.lyric.trim();
        String old = sLyric;
        if (data.type == LyricData.TYPE_STOP || text.isEmpty()) {
            if (old == null) return;
            sLyric = null;
        } else {
            sLyricTime = SystemClock.elapsedRealtime();
            sHandler.removeCallbacks(sStaleCheck);
            sHandler.postDelayed(sStaleCheck, LYRIC_STALE_MS + 1000);
            if (text.equals(old)) return;
            sLyric = text;
        }
        StatusBarLyric.update(sLyric);
        requestRefresh();
    }

    /** 太久没有新歌词（例如暂停后没发 STOP）时，把状态栏和息屏画面都恢复原样 */
    private static final Runnable sStaleCheck = new Runnable() {
        @Override
        public void run() {
            if (activeLyric() == null) {
                StatusBarLyric.update(null);
                requestRefresh();
            }
        }
    };

    static String activeLyric() {
        String lyric = sLyric;
        if (lyric == null) return null;
        if (SystemClock.elapsedRealtime() - sLyricTime > LYRIC_STALE_MS) {
            sLyric = null;
            return null;
        }
        return lyric;
    }

    /** 只在息屏时重绘；亮屏时息屏画面不可见，下次息屏会自动用最新歌词。 */
    private static void requestRefresh() {
        requestRefresh(false);
    }
    private static void requestRefresh(boolean restoring) {
        if (!restoring && !RuntimeConfig.enabled("lock.lyric") && !RuntimeConfig.enabled("lock.style")) return;
        if (sRefreshMethod == null || sRefreshPending) return;
        long wait = sLastRefresh + MIN_REFRESH_INTERVAL_MS - SystemClock.elapsedRealtime();
        sRefreshPending = true;
        sHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                sRefreshPending = false;
                PowerManager pm = (PowerManager) sContext.getSystemService(Context.POWER_SERVICE);
                if (pm.isInteractive()) return;
                sLastRefresh = SystemClock.elapsedRealtime();
                sInLyricRefresh = true;
                try {
                    sRefreshMethod.invoke(sController);
                } catch (Throwable t) {
                    XposedBridge.log(TAG + "refresh failed: " + t);
                } finally {
                    sInLyricRefresh = false;
                }
            }
        }, Math.max(0, wait));
    }
}
