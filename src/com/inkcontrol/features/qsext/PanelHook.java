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

/**
 * 接管 E-Ink 刷新面板（{@code com.hmct.systemui.epd.InkPopWindow}）底部那排按钮。
 *
 * <p>原厂在每次 {@code showPopWindow()} 里按字符串清单 inflate {@code ink_screen_button_item}；
 * 这台设备（新显示模式）的清单只剩「截屏便签」一条，另外三条 clear_mode / smooth_mode / collect
 * 被 {@code mIsSupportNewDisplayMode} 与顶部应用过滤掉，所以底部只有居中一个按钮。
 *
 * <p>所以这里把原厂那个「截屏便签」摘下来当一枚可复用的按钮，再按 {@code qs.panel.btnN} 的取值
 * 摆四个位置：控制中心那套动作（WiFi、蓝牙、铃声、飞行模式、手电筒、自动旋转、整屏翻页、
 * 免打扰、省电模式、悬浮球）、墨控自己的三项开关（自动刷新、锁屏主题、遮挡息屏）、
 * 「墨控快捷设置」、「截屏便签」原厂按钮、以及任意一个应用（包名）或「无」（这一格不显示）。
 * 四个位置都标 {@code two}，与原厂单行同排，面板高度不变（{@code InkButtonsParent} 按每行
 * 个数平分宽度）。
 *
 * <p>长按是「跳去设置」：WiFi、蓝牙跳系统设置页；「自动刷新」「遮挡息屏」跳墨控里对应的
 * 大功能分类页，「锁屏主题」跳样式选择那一页。跳转前一律收起面板（不给开关）。
 *
 * <p>动作都走系统界面本进程的权限，跟下拉磁贴同一套开关；墨控自己的三项要写配置并交给后端
 * 应用，所以经由墨控的 {@code content://com.inkcontrol.state} 的 {@code toggle} 接口，由应用
 * 进程去写（系统界面没有 con.inkcontrol 那个目录的权限）。原厂代码与资源都不改，关掉总开关
 * 下次弹出即恢复原样。
 */
public class PanelHook implements IXposedHookLoadPackage {
    private static final String TAG = "InkControl-Panel: ";
    private static final String POP = "com.hmct.systemui.epd.InkPopWindow";
    private static final String PKG = "com.android.systemui";
    private static final String PANEL = "qs.panel";
    private static final String CLOSE = "qs.panel.close";
    /** 原厂「截屏便签」的图标与文字比补上来的按钮大一圈，默认缩到同一档。 */
    private static final String PIN_SIZE = "qs.panel.pinsize";
    private static final String[] SLOTS = {"qs.panel.btn1", "qs.panel.btn2", "qs.panel.btn3", "qs.panel.btn4"};
    private static final ComponentName HOME =
            new ComponentName("com.inkcontrol", "com.inkcontrol.ui.MainActivity");
    /** 墨控里「快捷设置」页：MainActivity 读到这个 extra 直接渲染遮挡息屏 / 锁屏主题 / 自动刷新。 */
    private static final String QUICK_EXTRA = "quick";
    /** 长按落到大功能分类页：MainActivity 用分类名渲染那一页，而不是某一项的说明页。 */
    private static final String CATEGORY_EXTRA = "category";
    /** 墨控自己的三项开关：点一下就地开关，长按进对应的功能页或大功能分类页。 */
    private static final String REFRESH = "refresh.master", LOCK = "lock.style", COVER = "wave.lock";
    /** 「整屏翻页」的原厂设置键（TurnOverPageTile 读写的就是它）。 */
    private static final String TURN_PAGE = "slide_turn_over_page";
    /** 悬浮球（FooView）的显示开关与原厂广播。 */
    private static final String FLOAT_STATE = "fooview_float_icon_state";
    private static final String FLOAT_ACTION = "com.fooview.android.intent.SET_ICON_STATUS";
    private static final ComponentName FLOAT_RECEIVER = new ComponentName(
            "com.fooview.android.fooview.haixin", "com.fooview.android.fooview.fvprocess.FooDaemonReceiver");
    /**
     * 海信的「省电模式」不是 AOSP 的 Battery Saver：状态在 Settings.System 的
     * {@code powermanager_mode_common}，开关由 com.hmct.powermanager 收广播去做。
     * 直接调 PowerManager.setPowerSaveMode 在这台设备上不生效（原厂磁贴也不那么调）。
     */
    private static final String SAVER_STATE = "powermanager_mode_common";
    private static final String SAVER_ON = "com.hmct.powermanager.intent.action.INTO_COMMON_MODE_NEW";
    private static final String SAVER_OFF = "com.hmct.powermanager.intent.action.EXIT_COMMON_MODE_NEW";
    /** 墨控的状态 Provider：只用来让应用进程代写配置（面板自己写不了别人的私有目录）。 */
    private static final Uri STATE = Uri.parse("content://com.inkcontrol.state");

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static boolean sInstalled, sTorchWatched;
    private static volatile boolean sTorch;
    private static volatile String sTorchId;

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!PKG.equals(lpparam.packageName) || sInstalled) return;
        sInstalled = true;
        try {
            XposedHelpers.findAndHookMethod(POP, lpparam.classLoader, "showPopWindow", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.hasThrowable()) return;
                    try {
                        decorate(param.thisObject);
                    } catch (Throwable error) {
                        XposedBridge.log(TAG + "decorate failed: " + android.util.Log.getStackTraceString(error));
                    }
                }
            });
        } catch (Throwable error) {
            XposedBridge.log(TAG + "InkPopWindow not found: " + error);
        }
    }

    private static void decorate(Object window) {
        // 总开关关着就完全按原厂走：不摘按钮、不换顺序、也不缩尺寸。
        if (!RuntimeConfig.enabled(PANEL)) return;
        ViewGroup parent = (ViewGroup) XposedHelpers.getObjectField(window, "mButtonsParent");
        if (parent == null || parent.getChildCount() == 0) return;
        Context context = (Context) XposedHelpers.getObjectField(window, "mContext");
        if (context == null) return;
        int layout = layoutId(context, parent.getClass().getClassLoader());
        if (layout == 0) {
            XposedBridge.log(TAG + "button layout not found");
            return;
        }

        // 原厂那个「截屏便签」先摘下来：它可能被排到别的位次，也可能这一格换成了别的东西。
        View pin = findPinButton(parent, context);
        if (pin != null) parent.removeView(pin);
        watchTorch(context);
        boolean fit = RuntimeConfig.enabled(PIN_SIZE);

        List<View> items = new ArrayList<>();
        for (String slot : SLOTS) {
            String action = action(slot);
            if (FeatureCatalog.PANEL_NONE.equals(action)) continue;   // 这一格不要按钮
            View item;
            if ("pin".equals(action)) {
                // 就是原厂那个按钮本身：图标、文字与原厂截屏监听器一概不动，只按开关调尺寸。
                if (pin == null) continue;
                if (fit) fitPin(context, pin);
                item = pin;
            } else if (isApp(action)) {
                item = appItem(window, context, layout, action);
                if (item == null) continue;
                attach(context, item, v -> openApp(context, window, action));
            } else {
                item = tree(window, context, layout, label(action), iconName(action));
                if (item == null) continue;
                attach(context, item, v -> click(context, window, action));
                attachLong(context, item, v -> { longPress(context, window, action); return true; });
            }
            items.add(item);
        }
        // 顺序就是位置顺序：先清空再按顺序放回去，InkButtonsParent 会在下一轮测量里重新均分宽度。
        parent.removeAllViews();
        for (View item : items) parent.addView(item);
    }

    /** 按位置读动作；取值非法（比如旧配置）时退回该位置自己的默认值。 */
    private static String action(String slot) {
        String value = RuntimeConfig.value(slot);
        if (value == null || value.isEmpty()) return FeatureCatalog.get(slot).defaultValue();
        FeatureDefinition feature = FeatureCatalog.get(slot);
        if (feature.values.contains(value)) return value;
        return isApp(value) ? value : feature.defaultValue();
    }

    /** 既不是内置动作也不是「无」，那就当成应用包名。 */
    private static boolean isApp(String action) {
        if (action.isEmpty() || FeatureCatalog.PANEL_NONE.equals(action) || "pin".equals(action)) return false;
        for (String[] entry : FeatureCatalog.PANEL_ACTIONS) if (entry[0].equals(action)) return false;
        return true;
    }

    // ── 按钮外壳 ──

    /**
     * 按钮模板的布局 id。{@code getIdentifier} 在这台设备的系统界面上查不到这个 layout
     * （返回 0，同名 id/string 都查得到），所以先直接读它自己 dex 里的 {@code R$layout}，
     * 读不到再退回按名字查。
     */
    private static int layoutId(Context context, ClassLoader loader) {
        try {
            Class<?> layout = Class.forName("com.android.systemui.R$layout", false, loader);
            int id = layout.getField("ink_screen_button_item").getInt(null);
            if (id != 0) return id;
        } catch (Throwable ignored) {
            // 退回到按名字查
        }
        return id(context, "ink_screen_button_item");
    }

    /** 原厂「截屏便签」那一个按钮：按文字找，避免依赖内部类编号。 */
    private static View findPinButton(ViewGroup parent, Context context) {
        String label = string(context, "ink_launch_pad_pinscreen");
        if (label == null || label.isEmpty()) return null;
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            TextView summary = child.findViewById(id(context, "summary"));
            if (summary != null && label.contentEquals(summary.getText())) return child;
        }
        return null;
    }

    private static View tree(Object window, Context context, int layout, String label, String iconResource) {
        try {
            LayoutInflater inflater = (LayoutInflater) XposedHelpers.getObjectField(window, "mInflater");
            if (inflater == null) inflater = LayoutInflater.from(context);
            View item = inflater.inflate(layout, null, false);
            if (item == null) {
                XposedBridge.log(TAG + "inflate null for " + label);
                return null;
            }
            setLabel(item, label);
            ImageView icon = item.findViewById(id(context, "icon"));
            if (icon != null && iconResource != null && !iconResource.isEmpty()) {
                int drawable = resource(context, iconResource, "drawable");
                if (drawable != 0) setIcon(context, icon, context.getDrawable(drawable), true);
            }
            item.setTag("two");
            item.setContentDescription(label);
            return item;
        } catch (Throwable error) {
            XposedBridge.log(TAG + "inflate " + label + " failed: " + error);
            return null;
        }
    }

    /** 一格「某个应用」：文字用应用名，图标用它自己的图标，不染色。 */
    private static View appItem(Object window, Context context, int layout, String pkg) {
        View item = tree(window, context, layout, appLabel(context, pkg), null);
        if (item == null) return null;
        ImageView icon = item.findViewById(id(context, "icon"));
        Drawable drawable = appIcon(context, pkg);
        if (icon != null && drawable != null) setIcon(context, icon, drawable, false);
        return item;
    }

    /** 外层整格与里面那条 click_region 都挂同一个监听，整格都能点（原厂只有中间一条能点）。 */
    private static void attach(Context context, View item, View.OnClickListener click) {
        if (item == null) return;
        item.setOnClickListener(click);
        View region = item.findViewById(id(context, "click_region"));
        if (region != null) region.setOnClickListener(click);
    }

    /** 长按同样两级都要挂，否则按在文字上没反应。 */
    private static void attachLong(Context context, View item, View.OnLongClickListener press) {
        if (item == null) return;
        item.setOnLongClickListener(press);
        View region = item.findViewById(id(context, "click_region"));
        if (region != null) region.setOnLongClickListener(press);
    }

    private static void setLabel(View item, String label) {
        TextView summary = item.findViewById(id(item.getContext(), "summary"));
        if (summary == null) return;
        summary.setText(label);
        // 一格只有 85dp 宽，14sp 的「墨控快捷设置」会顶到边，降一档刚好。
        summary.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
    }

    /**
     * 原厂模板里图标是 wrap_content：状态栏那套图标本身是白色、尺寸还不一样（蓝牙那个
     * 96dp 见方，会把下面的文字挤成 0 高），所以这里固定成 28dp 见方；矢量图标再染成黑色，
     * 白底面板上才看得见，四个按钮的图标也对得齐。
     */
    private static void setIcon(Context context, ImageView icon, Drawable drawable, boolean tint) {
        int box = Math.round(28 * context.getResources().getDisplayMetrics().density);
        icon.setLayoutParams(new android.widget.LinearLayout.LayoutParams(box, box));
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        // 注意别写成 setColorFilter(tint ? Color.BLACK : null)：那样会被编成 int 重载并在 tint
        // 为假时拆箱空指针，效果图的按钮就整个装饰失败。
        if (tint) icon.setColorFilter(Color.BLACK);
        else icon.clearColorFilter();
        icon.setImageDrawable(drawable);
    }

    /**
     * 原厂「截屏便签」缩到与补上来的按钮同一档。只改图标尺寸与文字字号，
     * 文字内容、图标和原厂的点击监听器都不碰（那一格该干什么还干什么）。
     */
    private static void fitPin(Context context, View pin) {
        try {
            ImageView icon = pin.findViewById(id(context, "icon"));
            Drawable drawable = icon == null ? null : icon.getDrawable();
            if (icon != null && drawable != null) setIcon(context, icon, drawable, true);
            TextView summary = pin.findViewById(id(context, "summary"));
            if (summary != null) summary.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
        } catch (Throwable error) {
            XposedBridge.log(TAG + "fit pin failed: " + error);
        }
    }

    // ── 点击 ──

    private static void click(Context context, Object window, String action) {
        if ("quick".equals(action)) {
            // 面板是系统层悬浮窗，会盖在墨控上面：先收起来再启动。
            hidePanel(window);
            start(context, new Intent().putExtra(QUICK_EXTRA, "1"));
            return;
        }
        if (feature(action) != null) {
            toggle(context, window, action);
            return;
        }
        String message = run(context, action);
        // 先收面板再提示：提示走系统默认的底部居中位置，面板还开着就会把这条压住。
        if (RuntimeConfig.enabled(CLOSE)) hidePanel(window);
        if (message != null) toast(context, message);
    }

    /**
     * 长按一律「跳去设置」，跳之前直接把面板收起来——面板挡着半屏，跳过去也没用，
     * 所以这一条不给开关。没有对应设置页的动作（铃声、手电筒等）就不响应。
     */
    private static void longPress(Context context, Object window, String action) {
        if ("wifi".equals(action)) { hidePanel(window); openSettings(context, Settings.ACTION_WIFI_SETTINGS); return; }
        if ("bt".equals(action)) { hidePanel(window); openSettings(context, Settings.ACTION_BLUETOOTH_SETTINGS); return; }
        String id = feature(action);
        if (id == null) return;
        hidePanel(window);
        // 「自动刷新」「遮挡息屏」各是一整组开关，长按要落到大功能那一页，别停在总开关 /
        // 遮挡息屏这一项的说明页。分类名取功能目录的 group，和首页卡片用的是同一份名字。
        if ("refresh".equals(action) || "cover".equals(action)) {
            start(context, new Intent().putExtra(CATEGORY_EXTRA, FeatureCatalog.get(id).group));
            return;
        }
        // 锁屏主题的详情就是样式选择器，仍走桌面快捷方式那条通道直达那一页。
        start(context, new Intent(UiShortcuts.ACTION).putExtra(UiShortcuts.EXTRA, id));
    }

    /** 墨控自己的三项开关对应的功能 id；不是这三项返回 null。 */
    private static String feature(String action) {
        if ("refresh".equals(action)) return REFRESH;
        if ("lock".equals(action)) return LOCK;
        if ("cover".equals(action)) return COVER;
        return null;
    }

    /**
     * 墨控自己的开关：写配置 + 后端应用都在应用进程里做（系统界面写不了墨控的私有目录），
     * 这里只把请求递过去。Binder 调用可能要等应用进程冷启动，所以放在后台线程。
     */
    private static void toggle(Context context, Object window, String action) {
        final String id = feature(action);
        final String name = label(action);
        final boolean on = !RuntimeConfig.enabled(id);
        // 跟 WiFi／蓝牙一样按目标状态提示；真失败时应用进程会再补一条提示。
        if (RuntimeConfig.enabled(CLOSE)) hidePanel(window);
        toast(context, name + (on ? "已打开" : "已关闭"));
        new Thread(() -> {
            try {
                Bundle input = new Bundle();
                input.putString("feature", id);
                context.getContentResolver().call(STATE, "toggle", null, input);
            } catch (Throwable error) {
                XposedBridge.log(TAG + "toggle " + id + " failed: " + error);
                MAIN.post(() -> toast(context, name + "切换失败"));
            }
        }, "inkcontrol-toggle").start();
    }

    /** 各动作都照原厂磁贴的同一套调用做：同一个进程、同样的权限，行为与下拉面板一致。 */
    private static String run(Context context, String action) {
        try {
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
            XposedBridge.log(TAG + action + " failed: " + error);
            return label(action) + "切换失败";
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
        boolean on = !sTorch;
        manager.setTorchMode(id, on);     // CameraAccessException 由 run() 统一兜住
        return on ? "手电筒已打开" : "手电筒已关闭";
    }

    /** 找带闪光灯的那个摄像头。 */
    private static String torchId(CameraManager manager) {
        String cached = sTorchId;
        if (cached != null) return cached;
        try {
            for (String id : manager.getCameraIdList()) {
                Boolean flash = manager.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if (Boolean.TRUE.equals(flash)) { sTorchId = id; return id; }
            }
        } catch (Throwable error) {
            XposedBridge.log(TAG + "torch probe failed: " + error);
        }
        return null;
    }

    /** 手电筒的开关状态只有回调知道：注册一次，免得在别处开过之后这边记反。 */
    private static void watchTorch(Context context) {
        if (sTorchWatched) return;
        sTorchWatched = true;
        try {
            CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            if (manager == null) return;
            manager.registerTorchCallback(new CameraManager.TorchCallback() {
                @Override public void onTorchModeChanged(String cameraId, boolean enabled) { sTorch = enabled; }
                @Override public void onTorchModeUnavailable(String cameraId) { sTorch = false; }
            }, MAIN);
        } catch (Throwable error) {
            XposedBridge.log(TAG + "torch watch failed: " + error);
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
        boolean on = Settings.Global.getInt(resolver, TURN_PAGE, 0) == 0;
        Settings.Global.putInt(resolver, TURN_PAGE, on ? 1 : 0);
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
        boolean on = Settings.System.getInt(resolver, SAVER_STATE, 0) != 1;
        context.sendBroadcast(new Intent(on ? SAVER_ON : SAVER_OFF));
        return on ? "省电模式已打开" : "省电模式已关闭";
    }

    /** 悬浮球由 FooView 自己写状态，原厂磁贴也只是发一条广播，这里照抄。 */
    private static String toggleFloat(Context context) {
        // 系统界面这个 Context 本来就是当前用户的，直接按用户读，不碰隐藏的 UserHandle 接口。
        boolean show = Settings.System.getInt(context.getContentResolver(), FLOAT_STATE, 0) == 0;
        Intent intent = new Intent(FLOAT_ACTION).putExtra("fv_icon_visible", show).setComponent(FLOAT_RECEIVER);
        context.sendBroadcast(intent);
        return show ? "悬浮球已显示" : "悬浮球已隐藏";
    }

    // ── 图标与文字 ──

    private static String iconName(String action) {
        if ("wifi".equals(action)) return "ic_wifi_open_signal_4";
        if ("bt".equals(action)) return "ic_qs_bluetooth_on";
        if ("ring".equals(action)) return "ic_volume_ringer";
        if ("airplane".equals(action)) return "ic_qs_airplane";
        if ("torch".equals(action)) return "ic_qs_flashlight";
        if ("rotate".equals(action)) return "ic_qs_autorotate";
        if ("page".equals(action)) return "ic_qs_turn_over_page";
        if ("dnd".equals(action)) return "ic_qs_dnd";
        if ("saver".equals(action)) return "ic_qs_batterysaver";
        if ("float".equals(action)) return "ic_qs_float";
        if ("refresh".equals(action)) return "ic_menu_refresh";
        if ("lock".equals(action)) return "ic_lock_24dp";
        if ("cover".equals(action)) return "ic_qs_eyeprotection";
        if ("quick".equals(action)) return "ink_launch_pad_settings";
        return "";
    }

    private static String label(String action) {
        for (String[] entry : FeatureCatalog.PANEL_ACTIONS) if (entry[0].equals(action)) return entry[1];
        return action;
    }

    private static String appLabel(Context context, String pkg) {
        try {
            android.content.pm.PackageManager manager = context.getPackageManager();
            return manager.getApplicationLabel(manager.getApplicationInfo(pkg, 0)).toString();
        } catch (Throwable error) {
            return pkg;
        }
    }

    /** 应用自己的图标，缩到与原厂面板图标相仿的尺寸（原厂那套是 24dp 见方）。 */
    private static Drawable appIcon(Context context, String pkg) {
        try {
            Drawable source = context.getPackageManager().getApplicationIcon(pkg);
            int size = Math.round(28 * context.getResources().getDisplayMetrics().density);
            Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            source.setBounds(0, 0, size, size);
            source.draw(canvas);
            return new BitmapDrawable(context.getResources(), bitmap);
        } catch (Throwable error) {
            XposedBridge.log(TAG + "app icon " + pkg + " failed: " + error);
            return null;
        }
    }

    // ── 通用 ──

    /** 请求没被受理时别提示「已打开」，那会骗人（典型是权限被系统收走）。 */
    private static void toast(Context context, String text) {
        try {
            Toast toast = Toast.makeText(context, text, Toast.LENGTH_SHORT);
            // 用系统默认位置（底部居中）。这里不再手动 setGravity 抬到顶部：
            // 面板虽是 type=0x7d9、比 Toast 高一层，但点完会先收面板（见 click / toggle），
            // 提示落到底部时面板已经没了，不会被压住。
            toast.show();
        } catch (Throwable error) {
            XposedBridge.log(TAG + "toast failed: " + error);
        }
    }

    /** 打开一格应用：墨控自己走自己的 MainActivity（要 SINGLE_TOP），其余按启动意图跳。 */
    private static void openApp(Context context, Object window, String pkg) {
        hidePanel(window);
        if ("com.inkcontrol".equals(pkg)) {
            start(context, new Intent());
            return;
        }
        try {
            Intent launch = context.getPackageManager().getLaunchIntentForPackage(pkg);
            if (launch == null) { toast(context, "找不到这个应用"); return; }
            context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable error) {
            XposedBridge.log(TAG + "open " + pkg + " failed: " + error);
            toast(context, "打不开这个应用");
        }
    }

    private static void openSettings(Context context, String action) {
        try {
            context.startActivity(new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable error) {
            XposedBridge.log(TAG + "open settings " + action + " failed: " + error);
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
            XposedBridge.log(TAG + "closePopWindow failed, fallback to GONE: " + error);
            try {
                Object really = XposedHelpers.getObjectField(window, "mReallyView");
                if (really instanceof View) ((View) really).setVisibility(View.GONE);
            } catch (Throwable inner) {
                XposedBridge.log(TAG + "hide panel failed: " + inner);
            }
        }
    }

    /**
     * 拉起墨控。{@code SINGLE_TOP} 是必须的：{@code MainActivity} 没声明 launchMode，光带
     * {@code NEW_TASK} 时系统只会把已有任务拉到前台，新 Intent 不会送达，extras 白带。
     */
    private static void start(Context context, Intent intent) {
        try {
            intent.setComponent(HOME);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            context.startActivity(intent);
        } catch (Throwable error) {
            XposedBridge.log(TAG + "start InkControl failed: " + error);
        }
    }

    private static int id(Context context, String name) {
        return resource(context, name, "id");
    }

    private static int resource(Context context, String name, String type) {
        Resources resources = context.getResources();
        return resources.getIdentifier(name, type, PKG);
    }

    private static String string(Context context, String name) {
        int res = resource(context, name, "string");
        return res == 0 ? "" : context.getString(res);
    }
}
