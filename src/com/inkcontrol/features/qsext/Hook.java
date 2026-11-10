package com.inkcontrol.features.qsext;

import com.inkcontrol.platform.RuntimeConfig;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 控制中心拓展：让「编辑磁贴」的候选列表带上第三方应用的磁贴。
 *
 * <p>原厂在 {@code TileQueryHelper.queryTiles} 里只做了两件事：
 * {@code addCurrentAndStockTiles(host)}，然后
 * <pre>
 * if ("true".equals(SystemProperties.getStable("stable.sys.runtimepregrant", "false")))
 *     addPackageTiles(host);      // 只有这里才枚举第三方 ACTION_QS_TILE
 * </pre>
 * 而 {@code getStable("stable.sys.runtimepregrant")} 被海信改写成读 {@code ro.hx.test}，
 * 那个属性同时是安全特性总开关
 * （{@code SafeFeature.ENABLE = persist.sys.hmct.safe.enable && !ro.hx.test}），
 * 置真会把锁屏键盘等一整套定制退化成 AOSP 原生样式 —— 所以属性这条路不能走。
 *
 * <p>这里改为在 {@code queryTiles} 执行完之后补调一次原厂自己的私有方法
 * {@code addPackageTiles(QSTileHost)}：它内部 post 到 {@code mBgHandler} 做查询，
 * 每个组件走 {@code addTile}（{@code mSpecs} 去重），最后 {@code notifyTilesChanged}
 * 把完整列表推给编辑页。只影响候选列表的枚举，不碰任何系统属性。
 *
 * <p>开关每次打开编辑页都会重新判断，所以改了开关不用重启，重开一次编辑页即可。
 */
public class Hook implements IXposedHookLoadPackage {
    private static final String TAG = "InkControl-QS: ";
    private static final String HELPER = "com.android.systemui.qs.customize.TileQueryHelper";
    private static final String HOST = "com.android.systemui.qs.QSTileHost";
    private static final String FEATURE = "qs.extend";

    private static boolean sInstalled;

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!"com.android.systemui".equals(lpparam.packageName)) return;
        if (sInstalled) return;
        sInstalled = true;
        try {
            Class<?> host = lpparam.classLoader.loadClass(HOST);
            XposedHelpers.findAndHookMethod(HELPER, lpparam.classLoader, "queryTiles", host, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!RuntimeConfig.enabled(FEATURE)) return;
                    try {
                        // addPackageTiles 只是把查询 post 到后台线程，本身很快返回；
                        // 列表由它自己调 notifyTilesChanged 刷新，不用再补通知。
                        XposedHelpers.callMethod(param.thisObject, "addPackageTiles", param.args[0]);
                    } catch (Throwable error) {
                        XposedBridge.log(TAG + "addPackageTiles failed: " + error);
                    }
                }
            });
        } catch (Throwable error) {
            XposedBridge.log(TAG + "hook unavailable: " + error);
        }
    }
}
