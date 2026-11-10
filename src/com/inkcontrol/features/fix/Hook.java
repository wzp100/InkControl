package com.inkcontrol.features.fix;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ActivityInfo;
import android.os.Binder;
import android.os.Handler;
import android.util.Log;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.ArrayList;

import com.inkcontrol.platform.RuntimeConfig;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Fixes for defects in Hisense's EpdManagerService (system_server).
 * Original bugfixes retain stock fallback. Permission failures fail closed at the Binder stub.
 */
public class Hook implements IXposedHookLoadPackage {
    static final String TAG = "EinkFix";
    static final String SERVICE = "com.android.server.EpdManagerService";
    static final int NO_FLICKER = 0x400;
    private static boolean sHooked;
    private static int sSkipped;
    private static final Map<Object, RuntimeState> STATES = new WeakHashMap<>();
    /** 最近一次写过闸门的前台包名；null 表示还没写过，保证首次必写一次。 */
    private static String readerPackage;
    private static Method setProperty;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!"android".equals(lpparam.packageName) || sHooked) return;
        sHooked = true;
        RuntimeConfig.listen(Hook::configurationChanged);
        ClassLoader cl = lpparam.classLoader;
        Class<?> service;
        try {
            service = XposedHelpers.findClass(SERVICE, cl);
        } catch (Throwable t) {
            Log.e(TAG, "EpdManagerService not found; nothing to fix", t);
            return;
        }

        // 1. EpdHandler rethrows every RuntimeException on the system_server main
        //    thread (e.g. ConcurrentModificationException while writing the config),
        //    which soft-reboots the whole system. Log and drop instead.
        hook(cl, SERVICE + "$EpdHandler", "handleMessage", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!RuntimeConfig.enabled("fix.exceptions")) return;
                Throwable t = param.getThrowable();
                if (t != null) {
                    Log.e(TAG, "EpdHandler exception suppressed", t);
                    param.setResult(null);
                }
            }
        });

        // 2. handleWriteConfigFile iterates mAppModes under `this`, while binder
        //    threads mutate it under mLock. Run it under mLock.
        hook(service, "handleWriteConfigFile", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!RuntimeConfig.enabled("fix.configlock")) return;
                try {
                    Object lock = XposedHelpers.getObjectField(param.thisObject, "mLock");
                    synchronized (lock) {
                        XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args);
                    }
                } catch (InvocationTargetException e) {
                    Log.e(TAG, "handleWriteConfigFile failed", e.getCause());
                } catch (Throwable t) {
                    Log.e(TAG, "handleWriteConfigFile wrapper failed; config not written this time", t);
                }
                param.setResult(null);
            }
        });

        installPermissions(cl);
        try {
            XposedHelpers.findAndHookConstructor(SERVICE, cl, Context.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                if (!RuntimeConfig.enabled("fix.video")) return;
                    if (param.hasThrowable()) return;
                    try { state(param.thisObject); }
                    catch (Throwable t) { Log.e(TAG, "lifecycle state initialization failed", t); }
                }
            });
        } catch (Throwable t) { Log.e(TAG, "service constructor hook unavailable", t); }
        installActivityLifecycle(cl);
        // Serialize the whole stock resume operation, including its mode changes.
        hook(cl, SERVICE + "$1", "begigResumeActivity", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                // 翻页动画融合：分带揭示只该在「目标阅读器」前台时出现。
                // 这里与 fix.video 无关，所以放在门控之前，关掉修复项也照常更新。
                updateFusionReader((String) param.args[0]);
                if (!RuntimeConfig.enabled("fix.video")) return;
                try {
                    Object service = XposedHelpers.getObjectField(param.thisObject, "this$0");
                    Object lock = XposedHelpers.getObjectField(service, "mLock");
                    synchronized (lock) {
                        RuntimeState state = state(service);
                        state.video.top((String) param.args[0], (String) param.args[1]);
                        XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args);
                        syncReasons(service, state);
                        XposedHelpers.callMethod(service, "dealWithGray2NoFlicker");
                    }
                    param.setResult(null);
                } catch (InvocationTargetException e) {
                    param.setThrowable(e.getCause());
                } catch (Throwable t) {
                    Log.e(TAG, "resume serialization failed", t);
                    param.setThrowable(t);
                }
            }
        });
        hook(cl, SERVICE + "$1", "notifyResumedPackage", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (!RuntimeConfig.enabled("fix.video")) return;
                if (param.hasThrowable()) return;
                try {
                    Object service = XposedHelpers.getObjectField(param.thisObject, "this$0");
                    refreshReasons(service);
                } catch (Throwable t) { Log.e(TAG, "resume package cleanup failed", t); }
            }
        });
        // The stock delayed retry is not a new player; do not run its permanent Activity learning.
        hook(service, "notifyVideoOrCameraOn", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!RuntimeConfig.enabled("fix.video")) return;
                try {
                    Object lock = XposedHelpers.getObjectField(param.thisObject, "mLock");
                    synchronized (lock) {
                        RuntimeState state = state(param.thisObject);
                        String reason = (String) param.args[1];
                        int kind = "VIDEO_ON".equals(reason) ? VideoSessionTracker.VIDEO
                                : "CAMERA_ON".equals(reason) ? VideoSessionTracker.CAMERA : -1;
                        if (kind >= 0) state.video.notify((Integer) param.args[2], kind, ((Integer) param.args[0]) == 1);
                        syncReasons(param.thisObject, state);
                        XposedHelpers.callMethod(param.thisObject, "dealWithGray2NoFlicker");
                        Log.d(TAG, "video lifecycle " + reason + " uid=" + param.args[2] + " " + state.video.summary());
                    }
                } catch (Throwable t) {
                    Log.e(TAG, "video lifecycle failed; stock learning suppressed", t);
                }
                param.setResult(null);
            }
        });
        // Normalise at every consumer too, so stock hardcoded VIDEO_CAMERA_ON cannot re-enable a stale bit.
        hook(service, "dealWithGray2NoFlicker", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if (!RuntimeConfig.enabled("fix.modes")) return;
                Object lock = XposedHelpers.getObjectField(param.thisObject, "mLock");
                synchronized (lock) {
                    if (RuntimeConfig.enabled("fix.video")) syncReasons(param.thisObject, state(param.thisObject));
                    List<?> reasons = (List<?>) XposedHelpers.getObjectField(param.thisObject, "mGray2NoFlickerReason");
                    int mode = XposedHelpers.getIntField(param.thisObject, "mEpdMode");
                    int target = reasons.isEmpty() ? (mode & ~NO_FLICKER) : (mode | NO_FLICKER);
                    if (target != mode) XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args);
                    else sSkipped++;
                    param.setResult(null);
                }
            }
        });
        // services.odex inlines these small private methods into their callers, so
        // the hooks above never fire from there. Deoptimise the callers.
        deoptimize(cl, SERVICE + "$1", "begigResumeActivity");
        deoptimize(cl, SERVICE + "$EpdHandler", "handleMessage");
        deoptimize(cl, SERVICE, "notifyVideoOrCameraOn");
        // Inner classes reach the private methods through synthetic access$NNN
        // accessors, which were compiled with the target inlined as well.
        deoptimize(cl, SERVICE, "access$");
        Log.i(TAG, "EinkFix hooks installed");
    }

    private static final class RuntimeState {
        final VideoSessionTracker video = new VideoSessionTracker();
        List<Object> beforeReasons, writtenReasons;
        final DisplayPermissionPolicy.Identity identity;
        RuntimeState(Object service) {
            Context context = (Context) XposedHelpers.callMethod(service, "getContext");
            final PackageManager pm = context.getPackageManager();
            identity = new DisplayPermissionPolicy.Identity() {
                @Override public boolean platformSigned(int uid) {
                    return pm.checkSignatures(uid, 1000) == PackageManager.SIGNATURE_MATCH;
                }
                @Override public boolean ownsPackage(int uid, String pkg) {
                    if (pkg == null) return false;
                    String[] packages = pm.getPackagesForUid(uid);
                    if (packages == null) return false;
                    for (String name : packages) if (pkg.equals(name)) return true;
                    return false;
                }
                @Override public boolean installedUid(int uid) {
                    String[] packages = pm.getPackagesForUid(uid);
                    return packages != null && packages.length > 0;
                }
                @Override public boolean lyricAuthorized(int uid) {
                    // Pin the existing EinkLyric APK certificate, not a caller-supplied package string.
                    return ownsPackage(uid, "com.inkcontrol") && pm.hasSigningCertificate("com.inkcontrol",
                            hex(com.inkcontrol.platform.BuildInfo.CERT_SHA256),
                            PackageManager.CERT_INPUT_SHA256);
                }
            };
        }
    }

    private static byte[] hex(String value) {
        byte[] bytes = new byte[value.length() / 2];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        return bytes;
    }

    /**
     * 翻页动画融合的白名单闸门。
     *
     * <p>原版翻页动画由常驻端判断「前台应用在不在目标阅读器名单里」，不在名单就不武装。
     * 融合路线没有常驻端，所以改由这里在每次前台切换时比对 {@code pageanim.readers}，
     * 结果写进 {@code debug.inkcontrol.fusion.reader} 给 composer HAL 读。
     *
     * <p>名单为空时按原版语义就是「不触发」，因此空名单写 0 而不是放行。
     * 只在前台包名变化时写一次，避免在 system_server 主线程上反复写属性。
     */
    private static void updateFusionReader(String pkg) {
        String name = pkg == null ? "" : pkg;
        if (name.equals(readerPackage)) return;
        readerPackage = name;
        boolean allowed = false;
        if (!name.isEmpty()) {
            String list = RuntimeConfig.value("pageanim.readers");
            for (String item : list.split(",")) {
                if (item.trim().equals(name)) { allowed = true; break; }
            }
        }
        try {
            if (setProperty == null) setProperty =
                    Class.forName("android.os.SystemProperties").getMethod("set", String.class, String.class);
            setProperty.invoke(null, "debug.inkcontrol.fusion.reader", allowed ? "1" : "0");
        } catch (Throwable t) {
            Log.e(TAG, "fusion reader property failed", t);
        }
    }

    private static RuntimeState state(Object service) {
        synchronized (STATES) {
            RuntimeState state = STATES.get(service);
            if (state == null) { state = new RuntimeState(service); STATES.put(service, state); }
            return state;
        }
    }

    @SuppressWarnings("unchecked")
    private static void syncReasons(Object service, RuntimeState state) {
        state.video.top((String) XposedHelpers.getObjectField(service, "mTopPackage"),
                (String) XposedHelpers.getObjectField(service, "mTopActivity"));
        List<Object> reasons = (List<Object>) XposedHelpers.getObjectField(service, "mGray2NoFlickerReason");
        if (state.writtenReasons == null || !state.writtenReasons.equals(reasons)) state.beforeReasons = new ArrayList<>(reasons);
        for (Iterator<Object> i = reasons.iterator(); i.hasNext();) {
            Object r = i.next();
            if ("VIDEO_ON".equals(r) || "CAMERA_ON".equals(r) || "VIDEO_CAMERA_ON".equals(r)
                    || "CAMERA_VIDEO_AGAIN".equals(r)) i.remove();
        }
        if (state.video.visible(VideoSessionTracker.VIDEO, state.identity)) reasons.add("VIDEO_ON");
        if (state.video.visible(VideoSessionTracker.CAMERA, state.identity)) reasons.add("CAMERA_ON");
        state.writtenReasons = new ArrayList<>(reasons);
    }

    @SuppressWarnings("unchecked")
    private static void configurationChanged() {
        // 名单可能刚改过：把缓存清掉，下一次前台切换时按新名单重算。
        readerPackage = null;
        if (RuntimeConfig.enabled("fix.video")) return;
        synchronized (STATES) {
            for (Map.Entry<Object, RuntimeState> entry : STATES.entrySet()) {
                Object service = entry.getKey(); RuntimeState state = entry.getValue();
                Object lock = XposedHelpers.getObjectField(service, "mLock");
                synchronized (lock) {
                    List<Object> reasons = (List<Object>) XposedHelpers.getObjectField(service, "mGray2NoFlickerReason");
                    if (state.writtenReasons != null && state.writtenReasons.equals(reasons)) {
                        reasons.clear(); reasons.addAll(state.beforeReasons);
                        XposedHelpers.callMethod(service, "dealWithGray2NoFlicker");
                    }
                }
            }
            STATES.clear();
        }
    }

    private static void refreshReasons(Object service) {
        Object lock = XposedHelpers.getObjectField(service, "mLock");
        synchronized (lock) {
            syncReasons(service, state(service));
            XposedHelpers.callMethod(service, "dealWithGray2NoFlicker");
        }
    }

    private static void installPermissions(ClassLoader cl) {
        try {
            Class<?> stub = XposedHelpers.findClass(SERVICE + "$1", cl);
            for (Method method : stub.getDeclaredMethods()) {
                if (!Modifier.isPublic(method.getModifiers()) || Modifier.isStatic(method.getModifiers())
                        || method.isSynthetic() || "onTransact".equals(method.getName())
                        || "asBinder".equals(method.getName())) continue;
                hook(stub, method.getName(), new XC_MethodHook(10000) {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (!RuntimeConfig.enabled("fix.permissions")) return;
                        final int uid = Binder.getCallingUid();
                        final int pid = Binder.getCallingPid();
                        final String name = param.method.getName();
                        if (uid == 0 || uid == 1000
                                || DisplayPermissionPolicy.access(name) == DisplayPermissionPolicy.Access.READ) return;
                        try {
                            Object service = XposedHelpers.getObjectField(param.thisObject, "this$0");
                            RuntimeState state = state(service);
                            if (DisplayPermissionPolicy.access(name) == DisplayPermissionPolicy.Access.TEMP_MODE) {
                                Object lock = XposedHelpers.getObjectField(service, "mLock");
                                synchronized (lock) {
                                    String top = (String) XposedHelpers.getObjectField(service, "mTopPackage");
                                    if (!DisplayPermissionPolicy.allowed(name, uid, top, param.args, state.identity)) {
                                        param.setThrowable(new SecurityException("EinkFix: " + name + " denied for uid=" + uid));
                                    } else {
                                        param.setResult(XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args));
                                    }
                                }
                                return;
                            }
                            String top = (String) XposedHelpers.getObjectField(service, "mTopPackage");
                            if (!DisplayPermissionPolicy.allowed(name, uid, top, param.args, state.identity)) {
                                Log.w(TAG, "deny epd " + name + " uid=" + uid + " pid=" + pid);
                                param.setThrowable(new SecurityException("EinkFix: " + name + " denied for uid=" + uid));
                            }
                        } catch (InvocationTargetException t) {
                            param.setThrowable(t.getCause());
                        } catch (Throwable t) {
                            Log.e(TAG, "epd permission check failed " + name + " uid=" + uid, t);
                            param.setThrowable(new SecurityException("EinkFix: caller validation unavailable"));
                        }
                    }
                });
            }
            deoptimize(cl, SERVICE + "$1", "onTransact");
        } catch (Throwable t) { Log.e(TAG, "permission hooks unavailable", t); }
    }

    private static void installActivityLifecycle(ClassLoader cl) {
        // system_server ActivityRecord gives instance identity without loading this module into every app.
        hook(cl, "com.android.server.wm.ActivityRecord", "setState", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (!RuntimeConfig.enabled("fix.video")) return;
                if (param.hasThrowable()) return;
                try {
                    ActivityInfo info = (ActivityInfo) XposedHelpers.getObjectField(param.thisObject, "info");
                    Object token = XposedHelpers.getObjectField(param.thisObject, "appToken");
                    String newState = String.valueOf(param.args[0]);
                    final boolean destroyed = "DESTROYED".equals(newState);
                    final boolean resumed = "RESUMED".equals(newState);
                    boolean visible = resumed;
                    if (!destroyed && !resumed) {
                        visible = XposedHelpers.getBooleanField(param.thisObject, "visible");
                    }
                    dispatchActivity(token, info.applicationInfo.uid, info.packageName, info.name, visible, destroyed);
                } catch (Throwable t) { Log.e(TAG, "ActivityRecord lifecycle unavailable", t); }
            }
        });
        hook(cl, "com.android.server.wm.ActivityRecord", "takeFromHistory", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (!RuntimeConfig.enabled("fix.video")) return;
                if (param.hasThrowable()) return;
                try {
                    ActivityInfo info = (ActivityInfo) XposedHelpers.getObjectField(param.thisObject, "info");
                    Object token = XposedHelpers.getObjectField(param.thisObject, "appToken");
                    dispatchActivity(token, info.applicationInfo.uid, info.packageName, info.name, false, true);
                } catch (Throwable t) { Log.e(TAG, "ActivityRecord removal unavailable", t); }
            }
        });
        hook(cl, "com.android.server.wm.ActivityRecord", "setVisible", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (!RuntimeConfig.enabled("fix.video")) return;
                if (param.hasThrowable()) return;
                try {
                    ActivityInfo info = (ActivityInfo) XposedHelpers.getObjectField(param.thisObject, "info");
                    Object token = XposedHelpers.getObjectField(param.thisObject, "appToken");
                    dispatchActivity(token, info.applicationInfo.uid, info.packageName, info.name,
                            (Boolean) param.args[0], false);
                } catch (Throwable t) { Log.e(TAG, "ActivityRecord visibility unavailable", t); }
            }
        });
        deoptimize(cl, "com.android.server.wm.ActivityRecord", "setState");
        deoptimize(cl, "com.android.server.wm.ActivityRecord", "setVisible");
    }

    private static void dispatchActivity(final Object token, final int uid, final String pkg,
            final String name, final boolean visible, final boolean destroyed) {
        if (!RuntimeConfig.enabled("fix.video")) return;
        // Never acquire mLock while ATM's global lock is held; post to the EPD handler.
        synchronized (STATES) {
            for (final Object service : STATES.keySet()) {
                Handler handler = (Handler) XposedHelpers.getObjectField(service, "mHandler");
                handler.post(new Runnable() {
                    @Override public void run() {
                        if (!RuntimeConfig.enabled("fix.video")) return;
                        try {
                            Object lock = XposedHelpers.getObjectField(service, "mLock");
                            synchronized (lock) {
                                RuntimeState state = state(service);
                                if (destroyed) state.video.destroyed(token);
                                else state.video.activity(token, uid, pkg, name, visible);
                                refreshReasons(service);
                            }
                        } catch (Throwable t) { Log.e(TAG, "queued Activity lifecycle failed", t); }
                    }
                });
            }
        }
    }

    private static void deoptimize(ClassLoader cl, String className, String method) {
        try {
            java.lang.reflect.Method deopt = XposedBridge.class.getDeclaredMethod("deoptimizeMethod", java.lang.reflect.Member.class);
            int n = 0;
            for (java.lang.reflect.Method m : XposedHelpers.findClass(className, cl).getDeclaredMethods()) {
                if (method.endsWith("$") ? m.getName().startsWith(method) : m.getName().equals(method)) { deopt.invoke(null, m); n++; }
            }
            Log.i(TAG, "deoptimized " + className + "." + method + " x" + n);
        } catch (Throwable t) {
            Log.e(TAG, "deoptimize failed: " + className + "." + method, t);
        }
    }

    private static void hook(ClassLoader cl, String className, String method, XC_MethodHook callback) {
        try {
            hook(XposedHelpers.findClass(className, cl), method, callback);
        } catch (Throwable t) {
            Log.e(TAG, "class not found: " + className, t);
        }
    }

    private static void hook(Class<?> cls, String method, XC_MethodHook callback) {
        try {
            if (XposedBridge.hookAllMethods(cls, method, callback).isEmpty()) Log.e(TAG, "method not found: " + method);
            else Log.i(TAG, "hooked " + cls.getSimpleName() + "." + method);
        } catch (Throwable t) {
            Log.e(TAG, "hook failed: " + method, t);
        }
    }
}
