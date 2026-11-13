package com.inkcontrol.features.qsext;

import android.app.NotificationManager;
import android.bluetooth.BluetoothAdapter;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import com.inkcontrol.core.FeatureCatalog;
import com.inkcontrol.core.FeatureDefinition;
import com.inkcontrol.platform.RuntimeConfig;
import com.inkcontrol.ui.UiShortcuts;
import java.util.ArrayList;
import java.util.List;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** 面板按钮的跳转：打开应用、进入墨控设置页、收起面板。从 PanelHook 拆出，全部是静态方法。 */
final class PanelLaunch {
    private PanelLaunch() { }

    /** 打开一格应用：墨控自己走自己的 MainActivity（要 SINGLE_TOP），其余按启动意图跳。 */
    static void openApp(Context context, Object window, String pkg) {
        hidePanel(window);
        if ("com.inkcontrol".equals(pkg)) {
            start(context, new Intent());
            return;
        }
        try {
            Intent launch = context.getPackageManager().getLaunchIntentForPackage(pkg);
            if (launch == null) { PanelHook.toast(context, "找不到这个应用"); return; }
            context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable error) {
            XposedBridge.log(PanelHook.TAG + "open " + pkg + " failed: " + error);
            PanelHook.toast(context, "打不开这个应用");
        }
    }

    static void openSettings(Context context, String action) {
        try {
            context.startActivity(new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable error) {
            XposedBridge.log(PanelHook.TAG + "open settings " + action + " failed: " + error);
        }
    }

    /**
     * 面板是 type=0x7d9 的系统悬浮窗，不收起会一直盖在启动的应用上面。
     *
     * <p>必须走它自己的 {@code closePopWindow()}，不能只把 {@code mReallyView} 设成 GONE：
     * {@code isShowing()} 判的就是 {@code mPopWindow != null}，只藏视图的话内部仍认为「开着」，
     * 下次唤起会被当成「关闭」吞掉一次。{@code closePopWindow()} 会 removeView 并置空，
     * 下次 {@code showPopWindow()} 重新 inflate，按钮也重新装饰一遍。
     */
    static void hidePanel(Object window) {
        try {
            XposedHelpers.callMethod(window, "closePopWindow");
        } catch (Throwable error) {
            XposedBridge.log(PanelHook.TAG + "closePopWindow failed, fallback to GONE: " + error);
            try {
                Object really = XposedHelpers.getObjectField(window, "mReallyView");
                if (really instanceof View) ((View) really).setVisibility(View.GONE);
            } catch (Throwable inner) {
                XposedBridge.log(PanelHook.TAG + "hide panel failed: " + inner);
            }
        }
    }

    /**
     * 拉起墨控。{@code SINGLE_TOP} 是必须的：{@code MainActivity} 没声明 launchMode，光带
     * {@code NEW_TASK} 时系统只会把已有任务拉到前台，新 Intent 不会送达，extras 白带。
     */
    static void start(Context context, Intent intent) {
        try {
            intent.setComponent(PanelHook.HOME);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            context.startActivity(intent);
        } catch (Throwable error) {
            XposedBridge.log(PanelHook.TAG + "start InkControl failed: " + error);
        }
    }
}
