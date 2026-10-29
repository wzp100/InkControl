package com.inkcontrol.platform;

import android.content.Context;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** 框架入口只路由进程和安装功能处理器。 */
public final class HookDispatcher implements IXposedHookLoadPackage {
    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam packageInfo) throws Throwable {
        if (!"android".equals(packageInfo.packageName) && !packageInfo.packageName.equals(packageInfo.processName)) return;
        String name = packageInfo.packageName;
        String role = "android".equals(name) ? "system" : "com.android.systemui".equals(name) ? "systemui"
                : "com.hmct.vision".equals(name) ? "launcher" : "";
        if (role.isEmpty()) return;
        if (!role.equals("system")) {
            XposedHelpers.findAndHookMethod("android.app.Application", packageInfo.classLoader, "attach", Context.class,
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            RuntimeConfig.attach((Context) param.args[0], role);
                        }
                    });
        }
        install(packageInfo, new com.inkcontrol.features.lock.Hook());
        install(packageInfo, new com.inkcontrol.features.desktop.Hook());
        install(packageInfo, new com.inkcontrol.features.qsext.Hook());
        if (role.equals("system")) {
            install(packageInfo, new com.inkcontrol.features.motion.Hook());
            install(packageInfo, new com.inkcontrol.features.fix.Hook());
            install(packageInfo, new com.inkcontrol.features.refresh.Hook());
        }
    }
    private static void install(XC_LoadPackage.LoadPackageParam param, IXposedHookLoadPackage handler) {
        try { handler.handleLoadPackage(param); }
        catch (Throwable error) {
            RuntimeConfig.failInitialization(handler.getClass().getName() + ":" + error.getClass().getSimpleName());
            de.robv.android.xposed.XposedBridge.log(error);
        }
    }
}
