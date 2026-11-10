package com.inkcontrol.features.lock;

import android.content.Context;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * system_server：EpdManagerService 送屏前会用 BottomBarController.getBottomBar() 在图底部
 * （y 1365–1419）叠一条电量图标 + “上滑解锁”（亮屏锁屏时右侧还有静音图标）。本模块的锁屏样式
 * 自带这些内容，叠上去会重叠，所以样式 5–8 在锁屏状态下跳过这一层；原版样式、关机图照旧。
 */
final class BottomBarHook {
    private static final String TAG = "EinkLyric: ";
    private static final String CONTROLLER = "com.android.server.BottomBarController";
    private static final int STATUS_LOCK = 1;

    /** -1 = 还没读到；在主线程用 ContentObserver 维护，绘制路径里只读这个字段，不在持锁时访问设置库 */
    private static volatile int sStyle = -1;
    private static boolean sObserving;

    static void hook(ClassLoader cl) {
        XposedHelpers.findAndHookMethod(CONTROLLER, cl, "getBottomBar",
                Bitmap.class, int.class, boolean.class, int.class, boolean.class, String.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            observe(param.thisObject);
                            int status = (Integer) param.args[1];
                            int style = sStyle;
                            if (style < 0) {
                                Context context = (Context) Reflect.get(param.thisObject, "mContext");
                                if (context != null) {
                                    style = Settings.Global.getInt(context.getContentResolver(), LockStyle.KEY, 0);
                                    sStyle = style;
                                }
                            }
                            // 锁屏样式键是当前实时读取的 eink_keyguard_style（5–10 即墨控自定义样式）。
                            // 这里不能用 RuntimeConfig.enabled：system_server 的快照是开机时的旧配置，
                            // 用户后来在 App 开启 lock.style 后不会重新加载，导致误判未启用而叠加原厂图标条。
                            // 样式本身在 5–10 区间就代表已选用墨控锁屏，直接跳过原厂 bottom bar。
                            if (style >= LockStyle.EDITORIAL && style <= LockStyle.EDITORIAL_LAND_R) {
                                XposedBridge.log(TAG + "skip stock bottom bar; status=" + status + " style=" + style);
                                param.setResult(param.args[0]);
                            }
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + "bottom bar hook: " + t);
                        }
                    }
                });
        // 开机创建控制器时就开始监听，第一次锁屏前就读到样式
        XposedHelpers.findAndHookConstructor(CONTROLLER, cl,
                Context.class, "com.android.server.EpdManagerService", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            observe(param.thisObject);
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + "bottom bar init: " + t);
                        }
                    }
                });
        XposedBridge.log(TAG + "hooked BottomBarController");
    }

    private static synchronized void observe(Object controller) {
        if (sObserving) return;
        final Context context = (Context) Reflect.get(controller, "mContext");
        if (context == null) return;
        sObserving = true;
        final Handler main = new Handler(Looper.getMainLooper());
        main.post(new Runnable() {
            @Override
            public void run() {
                try {
                    final Runnable read = new Runnable() {
                        @Override
                        public void run() {
                            sStyle = Settings.Global.getInt(context.getContentResolver(), LockStyle.KEY, 0);
                        }
                    };
                    context.getContentResolver().registerContentObserver(
                            Settings.Global.getUriFor(LockStyle.KEY), false, new ContentObserver(main) {
                                @Override
                                public void onChange(boolean selfChange) {
                                    read.run();
                                }
                            });
                    read.run();
                } catch (Throwable t) {
                    XposedBridge.log(TAG + "bottom bar observe: " + t);
                }
            }
        });
    }
}
