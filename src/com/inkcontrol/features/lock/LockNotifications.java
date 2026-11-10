package com.inkcontrol.features.lock;

import android.service.notification.StatusBarNotification;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

final class LockNotifications {
    private static final String TAG = "EinkLyric: ";
    private static final String LISTENER = "com.android.systemui.statusbar.NotificationListener";

    private static volatile Object sListener;
    private static volatile int sLast = -1;
    private static boolean sWarned;

    static void hook(ClassLoader cl) {
        XposedHelpers.findAndHookMethod(LISTENER, cl, "onListenerConnected", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                sListener = param.thisObject;
                sLast = -1;
                sWarned = false;
            }
        });
    }

    static int count() {
        Object listener = sListener;
        if (listener == null) return -1;
        try {
            Object value = listener.getClass().getMethod("getActiveNotifications").invoke(listener);
            if (!(value instanceof StatusBarNotification[])) return sLast;
            int count = 0;
            for (StatusBarNotification notification : (StatusBarNotification[]) value) {
                if (notification != null && notification.isClearable()) count++;
            }
            sLast = count;
            return count;
        } catch (Throwable error) {
            if (!sWarned) {
                sWarned = true;
                XposedBridge.log(TAG + "notification count failed: " + error);
            }
            return sLast;
        }
    }

    private LockNotifications() {}
}
