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

/** 面板按钮对应的系统开关动作（无线、蓝牙、铃声、飞行模式、手电筒等）。从 PanelHook 拆出，全部是静态方法。 */
final class PanelActions {
    private PanelActions() { }

    /** 各动作都照原厂磁贴的同一套调用做：同一个进程、同样的权限，行为与下拉面板一致。 */
    static String run(Context context, String action) {
        try {
            // 刷新格言不是开关：让原厂格言往前跳一条。接收方就在本进程（SystemUI 的锁屏 Hook），
            // 它会自己提示换到了哪一条，这里不再重复提示。
            if ("motto".equals(action)) {
                context.sendBroadcast(new Intent(com.inkcontrol.features.lock.Hook.ACTION_MOTTO_NEXT)
                        .setPackage("com.android.systemui"));
                return null;
            }
            if ("wifi".equals(action)) return toggleWifi(context);
            if ("bt".equals(action)) return toggleBluetooth(context);
            if ("ring".equals(action)) return toggleRing(context);
            if ("airplane".equals(action)) return toggleAirplane(context);
            if ("torch".equals(action)) return toggleTorch(context);
            if ("rotate".equals(action)) return toggleRotate(context);
            if ("page".equals(action)) return togglePage(context);
            if ("dnd".equals(action)) return toggleDnd(context);
            if ("saver".equals(action)) return toggleSaver(context);
            if ("float".equals(action)) return toggleFloat(context);
        } catch (Throwable error) {
            XposedBridge.log(PanelHook.TAG + action + " failed: " + error);
            return PanelHook.label(action) + "切换失败";
        }
        return null;
    }

    private static String toggleWifi(Context context) {
        WifiManager manager = (WifiManager) context.getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        if (manager == null) return "WiFi 不可用";
        boolean on = !manager.isWifiEnabled();
        // setWifiEnabled 只是「请求已受理」，真正的状态切换是异步的，所以提示按目标状态写。
        boolean accepted = manager.setWifiEnabled(on);
        return accepted ? (on ? "WiFi 已打开" : "WiFi 已关闭") : "WiFi 切换失败";
    }

    private static String toggleBluetooth(Context context) {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) return "蓝牙不可用";
        boolean on = !adapter.isEnabled();
        boolean accepted = on ? adapter.enable() : adapter.disable();
        return accepted ? (on ? "蓝牙已打开" : "蓝牙已关闭") : "蓝牙切换失败";
    }

    /** 响铃 → 振动 → 静音。原厂磁贴用的是隐藏的 setRingerModeInternal，这里优先跟它一致。 */
    private static String toggleRing(Context context) {
        AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audio == null) return "铃声不可用";
        int mode = audio.getRingerMode();
        int next = mode == AudioManager.RINGER_MODE_NORMAL ? AudioManager.RINGER_MODE_VIBRATE
                : mode == AudioManager.RINGER_MODE_VIBRATE ? AudioManager.RINGER_MODE_SILENT
                : AudioManager.RINGER_MODE_NORMAL;
        try {
            XposedHelpers.callMethod(audio, "setRingerModeInternal", next);
        } catch (Throwable ignored) {
            audio.setRingerMode(next);
        }
        return next == AudioManager.RINGER_MODE_SILENT ? "铃声：静音"
                : next == AudioManager.RINGER_MODE_VIBRATE ? "铃声：振动" : "铃声：响铃";
    }

    private static String toggleAirplane(Context context) {
        ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null) return "飞行模式不可用";
        boolean on = Settings.Global.getInt(context.getContentResolver(),
                Settings.Global.AIRPLANE_MODE_ON, 0) == 0;
        // 原厂磁贴就调这一个：它自己会写设置、广播并切换射频。
        XposedHelpers.callMethod(manager, "setAirplaneMode", on);
        return on ? "飞行模式已打开" : "飞行模式已关闭";
    }

    private static String toggleTorch(Context context) throws Exception {
        CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        if (manager == null) return "手电筒不可用";
        String id = torchId(manager);
        if (id == null) return "这台设备没有闪光灯";
        boolean on = !PanelHook.sTorch;
        manager.setTorchMode(id, on);     // CameraAccessException 由 run() 统一兜住
        return on ? "手电筒已打开" : "手电筒已关闭";
    }

    /** 找带闪光灯的那个摄像头。 */
    private static String torchId(CameraManager manager) {
        String cached = PanelHook.sTorchId;
        if (cached != null) return cached;
        try {
            for (String id : manager.getCameraIdList()) {
                Boolean flash = manager.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if (Boolean.TRUE.equals(flash)) { PanelHook.sTorchId = id; return id; }
            }
        } catch (Throwable error) {
            XposedBridge.log(PanelHook.TAG + "torch probe failed: " + error);
        }
        return null;
    }

    /** 手电筒的开关状态只有回调知道：注册一次，免得在别处开过之后这边记反。 */
    static void watchTorch(Context context) {
        if (PanelHook.sTorchWatched) return;
        PanelHook.sTorchWatched = true;
        try {
            CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            if (manager == null) return;
            manager.registerTorchCallback(new CameraManager.TorchCallback() {
                @Override public void onTorchModeChanged(String cameraId, boolean enabled) { PanelHook.sTorch = enabled; }
                @Override public void onTorchModeUnavailable(String cameraId) { PanelHook.sTorch = false; }
            }, PanelHook.MAIN);
        } catch (Throwable error) {
            XposedBridge.log(PanelHook.TAG + "torch watch failed: " + error);
        }
    }

    private static String toggleRotate(Context context) {
        ContentResolver resolver = context.getContentResolver();
        boolean on = Settings.System.getInt(resolver, Settings.System.ACCELEROMETER_ROTATION, 0) == 0;
        Settings.System.putInt(resolver, Settings.System.ACCELEROMETER_ROTATION, on ? 1 : 0);
        return on ? "自动旋转已打开" : "自动旋转已关闭";
    }

    private static String togglePage(Context context) {
        ContentResolver resolver = context.getContentResolver();
        boolean on = Settings.Global.getInt(resolver, PanelHook.TURN_PAGE, 0) == 0;
        Settings.Global.putInt(resolver, PanelHook.TURN_PAGE, on ? 1 : 0);
        return on ? "整屏翻页已打开" : "整屏翻页已关闭";
    }

    private static String toggleDnd(Context context) {
        NotificationManager manager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return "免打扰不可用";
        boolean on = manager.getCurrentInterruptionFilter() == NotificationManager.INTERRUPTION_FILTER_ALL;
        manager.setInterruptionFilter(on ? NotificationManager.INTERRUPTION_FILTER_PRIORITY
                : NotificationManager.INTERRUPTION_FILTER_ALL);
        return on ? "免打扰已打开" : "免打扰已关闭";
    }

    /**
     * 海信的省电模式是自家的「普通模式」：状态记在 Settings.System 的
     * {@code powermanager_mode_common}，由 com.hmct.powermanager 收广播切换。
     * 这里之前调的是 AOSP 的 PowerManager.setPowerSaveMode，这台设备没有那条路径，
     * 每次都抛异常，所以一直提示「省电模式切换失败」。
     */
    private static String toggleSaver(Context context) {
        ContentResolver resolver = context.getContentResolver();
        boolean on = Settings.System.getInt(resolver, PanelHook.SAVER_STATE, 0) != 1;
        context.sendBroadcast(new Intent(on ? PanelHook.SAVER_ON : PanelHook.SAVER_OFF));
        return on ? "省电模式已打开" : "省电模式已关闭";
    }

    /** 悬浮球由 FooView 自己写状态，原厂磁贴也只是发一条广播，这里照抄。 */
    private static String toggleFloat(Context context) {
        // 系统界面这个 Context 本来就是当前用户的，直接按用户读，不碰隐藏的 UserHandle 接口。
        boolean show = Settings.System.getInt(context.getContentResolver(), PanelHook.FLOAT_STATE, 0) == 0;
        Intent intent = new Intent(PanelHook.FLOAT_ACTION).putExtra("fv_icon_visible", show).setComponent(PanelHook.FLOAT_RECEIVER);
        context.sendBroadcast(intent);
        return show ? "悬浮球已显示" : "悬浮球已隐藏";
    }

    // ── 图标与文字 ──
}
