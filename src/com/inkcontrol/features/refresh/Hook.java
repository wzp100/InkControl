package com.inkcontrol.features.refresh;

import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;

import java.lang.reflect.Method;

import com.inkcontrol.platform.ConfigProvider;
import com.inkcontrol.platform.RuntimeConfig;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * system_server 侧的自动刷新执行端：App 发出刷新信号后，调用原厂墨水屏的清晰刷新入口
 * （EpdManager.forceClear）。候选方法名逐个尝试，未命中时记录服务上的方法名，便于按固件确认。
 */
public class Hook implements IXposedHookLoadPackage {
    static final String TAG = "EinkRefresh";
    private static final String SERVICE = "com.android.server.EpdManagerService";
    private static final String[] CANDIDATES = {"forceClear", "forceFullRefresh", "forceEpdRefresh", "clearScreen", "forceRefresh"};
    private static boolean sInstalled;
    private static volatile Object sService;
    private static volatile Context sContext;
    private static volatile Handler sBackground;
    private static boolean sObserving;

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!"android".equals(lpparam.packageName) || sInstalled) return;
        sInstalled = true;
        RuntimeConfig.listen(Hook::install);
        try { sContext = systemContext(); } catch (Throwable t) { Log.e(TAG, "system context unavailable", t); }
        try {
            XposedHelpers.findAndHookConstructor(SERVICE, lpparam.classLoader, Context.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    sService = param.thisObject;
                    if (sContext == null) {
                        try { sContext = (Context) XposedHelpers.callMethod(param.thisObject, "getContext"); } catch (Throwable ignored) { }
                    }
                    install();
                }
            });
        } catch (Throwable t) { Log.e(TAG, "epd service hook unavailable", t); }
        install();
        Log.i(TAG, "refresh hooks installed");
    }

    private static Context systemContext() throws Exception {
        Class<?> thread = Class.forName("android.app.ActivityThread");
        Object current = thread.getMethod("currentActivityThread").invoke(null);
        if (current == null) return null;
        return (Context) thread.getMethod("getSystemContext").invoke(current);
    }

    private static synchronized void install() {
        if (sObserving || sContext == null) return;
        try {
            HandlerThread background = new HandlerThread("EinkRefresh");
            background.start();
            sBackground = new Handler(background.getLooper());
            sContext.getContentResolver().registerContentObserver(Uri.withAppendedPath(ConfigProvider.URI, "refresh"), false,
                    new ContentObserver(new Handler(Looper.getMainLooper())) {
                        @Override public void onChange(boolean self) { if (sBackground != null) sBackground.post(Hook::request); }
                    });
            sObserving = true;
            Log.i(TAG, "refresh signal observer registered");
        } catch (Throwable t) { Log.e(TAG, "observer registration failed", t); }
    }

    private static void request() {
        if (!RuntimeConfig.enabled("refresh.master")) return;
        try {
            Object target = sService;
            Method method = target == null ? null : candidate(target.getClass());
            if (method == null) {
                Object binder = epdBinder();
                if (binder != null) { target = binder; method = candidate(binder.getClass()); }
            }
            if (method == null) {
                Log.w(TAG, "no refresh entry; service=" + describe(sService) + " epd=" + describe(epdBinder()));
                return;
            }
            method.invoke(target);
            Log.i(TAG, "forced e-ink refresh via " + target.getClass().getName() + "." + method.getName());
        } catch (Throwable t) { Log.e(TAG, "forced refresh failed", t); }
    }

    private static Object epdBinder() {
        try { return Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, "epd"); }
        catch (Throwable t) { return null; }
    }

    private static Method candidate(Class<?> type) {
        for (String name : CANDIDATES) {
            try {
                Method method = type.getMethod(name);
                if (method.getParameterTypes().length == 0) return method;
            } catch (Throwable ignored) { }
        }
        return null;
    }

    private static String describe(Object target) {
        if (target == null) return "null";
        StringBuilder out = new StringBuilder(target.getClass().getName());
        for (Method method : target.getClass().getMethods()) {
            if (method.getParameterTypes().length == 0 && method.getDeclaringClass() != Object.class) out.append(' ').append(method.getName());
        }
        return out.toString();
    }
}
