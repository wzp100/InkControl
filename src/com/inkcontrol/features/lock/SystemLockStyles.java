package com.inkcontrol.features.lock;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.inkcontrol.platform.RuntimeConfig;

import java.io.File;
import java.io.FileOutputStream;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 海信息屏画面 = EinkKeyguardController.showPoster() 把 getCurrentStyleView() 画成 720×1440 位图送墨水屏。
 * 样式 5–8 时让 getCurrentStyleView() 返回本模块的 LockCanvas（不挂到任何父布局，手动 measure/layout），
 * 其余流程（息屏延时、每分钟走时、换歌词重绘、刷新模式）全部沿用原版。
 */
final class SystemLockStyles {
    private static final String TAG = "EinkLyric: ";
    private static final String CONTROLLER = "com.eink.keyguard.EinkKeyguardController";
    /** 调试：am broadcast -a com.inkcontrol.features.lock.RENDER [--ei style 5] [--ez sample true] */
    private static final String ACTION_RENDER = "com.inkcontrol.features.lock.RENDER";

    private static LockCanvas sView;
    /** 挂在 mHeadQuarters 里、亮屏解锁那一瞬间真正显示在屏幕上的那份 */
    private static LockCanvas sScreenView;
    private static final String[] STOCK_VIEWS = {"mFWKeyguard", "mVPKeyguard", "mWWKeyguard", "mBTKeyguard"};

    static void hook(ClassLoader cl) {
        // 原版 updateVisiblity() 只处理 0–4；样式 5–8 时四套原版视图全保持可见、叠在 mHeadQuarters 里，
        // 解锁亮屏那一下会露出艺术封面。这里在它之后把原版视图藏起来，换上本模块的画面。
        XC_MethodHook sync = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    syncScreenView(param.thisObject, null);
                } catch (Throwable t) {
                    XposedBridge.log(TAG + "screen view sync failed: " + t);
                }
            }
        };
        XposedHelpers.findAndHookMethod(CONTROLLER, cl, "updateVisiblity", sync);
        XposedHelpers.findAndHookMethod(CONTROLLER, cl, "initHeadQuarters", sync);

        XposedHelpers.findAndHookMethod(CONTROLLER, cl, "getCurrentStyleView", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    Object controller = param.thisObject;
                    Object style = Reflect.get(controller, "mCurrentStyle");
                    if (!(style instanceof Integer) || !LockStyle.custom((Integer) style)) return;
                    Context context = (Context) Reflect.get(controller, "mContext");
                    LockData data = LockData.live(context, controller);
                    param.setResult(prepare(context, (Integer) style, data));
                    // 送屏可能来自唤醒引擎的线程；挂在窗口上的视图只能在主线程改
                    final Object c = controller;
                    final LockData d = data;
                    if (Looper.myLooper() == Looper.getMainLooper()) {
                        syncScreenView(c, d);
                    } else {
                        new Handler(Looper.getMainLooper()).post(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    syncScreenView(c, d);
                                } catch (Throwable t) {
                                    XposedBridge.log(TAG + "screen view sync failed: " + t);
                                }
                            }
                        });
                    }
                } catch (Throwable t) {
                    // 出错就让原版画面顶上，不影响锁屏
                    XposedBridge.log(TAG + "custom lock style failed: " + t);
                }
            }
        });
    }

    /** 样式 5–8：藏起原版四套视图，显示 sScreenView（内容与最近一次送屏的图一致）；其它样式：藏起 sScreenView */
    private static void syncScreenView(Object controller, LockData data) {
        // updateVisiblity 也可能由设置观察器的后台线程调用，所有入口统一转到主线程。
        if (Looper.myLooper() != Looper.getMainLooper()) {
            final Object target = controller;
            final LockData snapshot = data;
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    try {
                        syncScreenView(target, snapshot);
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + "screen view sync failed: " + t);
                    }
                }
            });
            return;
        }
        Object parentObj = Reflect.get(controller, "mHeadQuarters");
        if (!(parentObj instanceof ViewGroup)) return;
        ViewGroup parent = (ViewGroup) parentObj;
        Object styleObj = Reflect.get(controller, "mCurrentStyle");
        int style = styleObj instanceof Integer ? (Integer) styleObj : 0;
        boolean custom = LockStyle.custom(style);
        if (!custom) {
            if (sScreenView != null) sScreenView.setVisibility(View.GONE);
            return;
        }
        Context context = (Context) Reflect.get(controller, "mContext");
        if (sScreenView == null) sScreenView = new LockCanvas(context);
        if (sScreenView.getParent() != parent) {
            if (sScreenView.getParent() instanceof ViewGroup) {
                ((ViewGroup) sScreenView.getParent()).removeView(sScreenView);
            }
            parent.addView(sScreenView, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        for (String name : STOCK_VIEWS) {
            Object v = Reflect.get(controller, name);
            if (v instanceof View) ((View) v).setVisibility(View.GONE);
        }
        sScreenView.setVisibility(View.VISIBLE);
        sScreenView.bringToFront();
        sScreenView.set(style, data != null ? data : LockData.live(context, controller));
    }

    private static View prepare(Context context, int style, LockData data) {
        if (sView == null) sView = new LockCanvas(context);
        sView.set(style, data);
        int w = 720, h = 1440;
        sView.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        sView.layout(0, 0, w, h);
        return sView;
    }

    static void capture(Context context, Bitmap bitmap, int mode) {
        if (context == null || bitmap == null) return;
        File f = new File(context.getCacheDir(), "einklock-posted.png");
        try (FileOutputStream out = new FileOutputStream(f)) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            XposedBridge.log(TAG + "posted " + bitmap.getWidth() + "x" + bitmap.getHeight() + " mode " + mode);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "capture failed: " + t);
        }
    }

    /** 在 SystemUI 进程里注册调试出图广播，图存到 SystemUI 的缓存目录 */
    static void registerDebug(final Context context, final Object controller, Handler handler) {
        context.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                if (intent.getBooleanExtra("dumpViews", false)) {
                    Object hq = Reflect.get(controller, "mHeadQuarters");
                    StringBuilder sb = new StringBuilder("headquarters " + (hq == null ? null
                            : ((View) hq).getVisibility() + " " + ((View) hq).getWidth() + "x" + ((View) hq).getHeight()));
                    if (hq instanceof ViewGroup) {
                        ViewGroup g = (ViewGroup) hq;
                        for (int i = 0; i < g.getChildCount(); i++) {
                            View v = g.getChildAt(i);
                            sb.append(" | ").append(v.getClass().getSimpleName()).append(" vis=")
                                    .append(v.getVisibility()).append(' ').append(v.getWidth()).append('x').append(v.getHeight());
                        }
                    }
                    XposedBridge.log(TAG + sb);
                    return;
                }
                if (intent.getBooleanExtra("dumpWeather", false)) {
                    Object view = Reflect.get(Reflect.get(controller, "mFWClockArea"), "mWeather");
                    XposedBridge.log(TAG + "weather view " + (view == null ? null : view.getClass())
                            + " data " + Reflect.get(Reflect.get(view, "mCityData"), "dataMap"));
                    return;
                }
                int style = intent.getIntExtra("style", -1);
                boolean sample = intent.getBooleanExtra("sample", false);
                int[] styles = style >= 0 ? new int[]{style}
                        : new int[]{LockStyle.EDITORIAL, LockStyle.SWISS, LockStyle.PIXEL, LockStyle.GLASS, LockStyle.EDITORIAL_LAND, LockStyle.EDITORIAL_LAND_R};
                for (int s : styles) {
                    try {
                        LockData data = sample ? LockData.sample(RuntimeConfig.snapshot()) : LockData.live(context, controller);
                        Bitmap b = LockCanvas.render(context, s, data, 720, 1440);
                        File f = new File(context.getCacheDir(), "einklock-" + s + ".png");
                        try (FileOutputStream out = new FileOutputStream(f)) {
                            b.compress(Bitmap.CompressFormat.PNG, 100, out);
                        }
                        f.setReadable(true, false);
                        XposedBridge.log(TAG + "rendered " + f);
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + "render " + s + " failed: " + t);
                    }
                }
            }
        }, new IntentFilter(ACTION_RENDER), null, handler);
    }
}
