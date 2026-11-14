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
 * 摆最多五个位置：控制中心那套动作（WiFi、蓝牙、铃声、飞行模式、手电筒、自动旋转、整屏翻页、
 * 免打扰、省电模式、悬浮球）、墨控自己的开关（自动刷新、锁屏主题、遮挡息屏、翻页动画、全刷扫过）、
 * 「刷新格言」、「墨控快捷设置」、「截屏便签」原厂按钮、以及任意一个应用（包名）或「无」（这一格
 * 不显示）。五个位置都标 {@code two}，与原厂单行同排，面板高度不变（{@code InkButtonsParent} 按
 * 每行个数平分宽度）。
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
    static final String TAG = "InkControl-Panel: ";
    private static final String POP = "com.hmct.systemui.epd.InkPopWindow";
    private static final String PKG = "com.android.systemui";
    private static final String PANEL = "qs.panel";
    private static final String CLOSE = "qs.panel.close";
    /** 原厂「截屏便签」的图标与文字比补上来的按钮大一圈，默认缩到同一档。 */
    private static final String PIN_SIZE = "qs.panel.pinsize";
    private static final String[] SLOTS = {"qs.panel.btn1", "qs.panel.btn2", "qs.panel.btn3",
            "qs.panel.btn4", "qs.panel.btn5"};
    static final ComponentName HOME =
            new ComponentName("com.inkcontrol", "com.inkcontrol.ui.MainActivity");
    /** 墨控里「快捷设置」页：MainActivity 读到这个 extra 直接渲染遮挡息屏 / 锁屏主题 / 自动刷新。 */
    private static final String QUICK_EXTRA = "quick";
    /** 长按落到大功能分类页：MainActivity 用分类名渲染那一页，而不是某一项的说明页。 */
    private static final String CATEGORY_EXTRA = "category";
    /** 落到某个大功能页之后还要滚到哪一项（面板长按「全刷扫过」用）。 */
    private static final String FOCUS_EXTRA = "focus";
    /** 墨控自己的三项开关：点一下就地开关，长按进对应的功能页或大功能分类页。 */
    private static final String REFRESH = "refresh.master", LOCK = "lock.style", COVER = "wave.lock",
            PAGEANIM = "pageanim.master", GCSWEEP = "motion.gc_clear";
    /** 「整屏翻页」的原厂设置键（TurnOverPageTile 读写的就是它）。 */
    static final String TURN_PAGE = "slide_turn_over_page";
    /** 悬浮球（FooView）的显示开关与原厂广播。 */
    static final String FLOAT_STATE = "fooview_float_icon_state";
    static final String FLOAT_ACTION = "com.fooview.android.intent.SET_ICON_STATUS";
    static final ComponentName FLOAT_RECEIVER = new ComponentName(
            "com.fooview.android.fooview.haixin", "com.fooview.android.fooview.fvprocess.FooDaemonReceiver");
    /**
     * 海信的「省电模式」不是 AOSP 的 Battery Saver：状态在 Settings.System 的
     * {@code powermanager_mode_common}，开关由 com.hmct.powermanager 收广播去做。
     * 直接调 PowerManager.setPowerSaveMode 在这台设备上不生效（原厂磁贴也不那么调）。
     */
    static final String SAVER_STATE = "powermanager_mode_common";
    static final String SAVER_ON = "com.hmct.powermanager.intent.action.INTO_COMMON_MODE_NEW";
    static final String SAVER_OFF = "com.hmct.powermanager.intent.action.EXIT_COMMON_MODE_NEW";
    /** 墨控的状态 Provider：只用来让应用进程代写配置（面板自己写不了别人的私有目录）。 */
    private static final Uri STATE = Uri.parse("content://com.inkcontrol.state");

    static final Handler MAIN = new Handler(Looper.getMainLooper());
    static boolean sInstalled, sTorchWatched;
    static volatile boolean sTorch;
    static volatile String sTorchId;

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
        PanelActions.watchTorch(context);
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
                attach(context, item, v -> PanelLaunch.openApp(context, window, action));
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
        fitCells(context, items);
    }

    /**
     * 并排超过四个时每格会窄于 85dp（容器固定 680px：四格 170px、五格 136px），而模板里的
     * {@code click_region} 写死 100dp、文字 12sp，「墨控快捷设置」这种六字动作会被裁掉两头。
     * 所以格子多于四个时把 {@code click_region} 撑满整格，再把文字一档档缩到放得下（最小 9sp）。
     *
     * <p>只改宽、不动原有的 LayoutParams：模板里 {@code click_region} 带 16dp 上边距，
     * 换成新的 LayoutParams 会把边距一起丢掉，五个按钮的图标就会顶到格子最上面。
     */
    private static void fitCells(Context context, List<View> items) {
        if (items.size() <= 4) return;
        float density = context.getResources().getDisplayMetrics().density;
        // 容器宽度＝屏宽 − 两侧各 10dp 外边距（见 ink_screen_pop_window 里那层 LinearLayout）。
        int cell = Math.max(1, Math.round((context.getResources().getDisplayMetrics().widthPixels - 20 * density) / items.size()));
        float room = cell - 4 * density;
        for (View item : items) {
            try {
                View region = item.findViewById(id(context, "click_region"));
                ViewGroup.LayoutParams params = region == null ? null : region.getLayoutParams();
                if (params != null) {
                    params.width = ViewGroup.LayoutParams.MATCH_PARENT;
                    region.setLayoutParams(params);
                }
                TextView summary = item.findViewById(id(context, "summary"));
                if (summary == null || summary.getText() == null) continue;
                String text = summary.getText().toString();
                for (int sp = 12; sp > 9; sp--) {
                    summary.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, sp);
                    if (summary.getPaint().measureText(text) <= room) break;
                }
            } catch (Throwable error) {
                XposedBridge.log(TAG + "fit cell failed: " + error);
            }
        }
    }

    /** 按位置读动作；取值非法（比如旧配置）时退回该位置自己的默认值。 */
    static String action(String slot) {
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
     * 白底面板上才看得见，几个按钮的图标也对得齐。
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
            PanelLaunch.hidePanel(window);
            PanelLaunch.start(context, new Intent().putExtra(QUICK_EXTRA, "1"));
            return;
        }
        if (feature(action) != null) {
            // 锁屏主题不是「点一下就好」的开关：和长按一样直接进「锁屏自定义」那一页挑样式，
            // 免得在面板上误触就把整套锁屏关掉。另两项（自动刷新、遮挡息屏）仍就地开关。
            if ("lock".equals(action)) { longPress(context, window, action); return; }
            toggle(context, window, action);
            return;
        }
        String message = PanelActions.run(context, action);
        // 先收面板再提示：提示走系统默认的底部居中位置，面板还开着就会把这条压住。
        if (RuntimeConfig.enabled(CLOSE)) PanelLaunch.hidePanel(window);
        if (message != null) toast(context, message);
    }

    /**
     * 长按一律「跳去设置」，跳之前直接把面板收起来——面板挡着半屏，跳过去也没用，
     * 所以这一条不给开关。没有对应设置页的动作（铃声、手电筒等）就不响应。
     */
    private static void longPress(Context context, Object window, String action) {
        if ("wifi".equals(action)) { PanelLaunch.hidePanel(window); PanelLaunch.openSettings(context, Settings.ACTION_WIFI_SETTINGS); return; }
        if ("bt".equals(action)) { PanelLaunch.hidePanel(window); PanelLaunch.openSettings(context, Settings.ACTION_BLUETOOTH_SETTINGS); return; }
        // 刷新格言没有对应功能项，长按直接落到「锁屏显示」那一页（格言徽标就在那儿）。
        if ("motto".equals(action)) {
            PanelLaunch.hidePanel(window);
            PanelLaunch.start(context, new Intent().putExtra(CATEGORY_EXTRA, FeatureCatalog.get(LOCK).group));
            return;
        }
        String id = feature(action);
        if (id == null) return;
        PanelLaunch.hidePanel(window);
        // 「自动刷新」「遮挡息屏」「翻页动画」「全刷扫过」各是一整组开关，长按要落到大功能那一页，别停在
        // 总开关 / 遮挡息屏这一项的说明页。分类名取功能目录的 group，和首页卡片用的是同一份名字。
        if ("refresh".equals(action) || "cover".equals(action) || "pageanim".equals(action)
                || "gcsweep".equals(action)) {
            // 全刷扫过是「动画」里的一个小节：多带一个 focus，落到那一页后滚到它所在的分组。
            Intent intent = new Intent().putExtra(CATEGORY_EXTRA, FeatureCatalog.get(id).group);
            if ("gcsweep".equals(action)) intent.putExtra(FOCUS_EXTRA, GCSWEEP);
            PanelLaunch.start(context, intent);
            return;
        }
        // 锁屏主题的详情就是样式选择器，仍走桌面快捷方式那条通道直达那一页。
        PanelLaunch.start(context, new Intent(UiShortcuts.ACTION).putExtra(UiShortcuts.EXTRA, id));
    }

    /** 墨控自己的开关对应的功能 id；不是这几项返回 null。 */
    private static String feature(String action) {
        if ("refresh".equals(action)) return REFRESH;
        if ("lock".equals(action)) return LOCK;
        if ("cover".equals(action)) return COVER;
        if ("pageanim".equals(action)) return PAGEANIM;
        if ("gcsweep".equals(action)) return GCSWEEP;
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
        if (RuntimeConfig.enabled(CLOSE)) PanelLaunch.hidePanel(window);
        toast(context, name + (on ? "已打开" : "已关闭"));
        new Thread(() -> {
            try {
                Bundle input = new Bundle();
                input.putString("feature", id);
                input.putBoolean("enabled", on);
                context.getContentResolver().call(STATE, "toggle", null, input);
            } catch (Throwable error) {
                // 墨控被「强制停止」后，这台机器会禁止 SystemUI 通过 provider 把应用进程
                // 拉起来（Unknown authority / ... it's restricted）。显式 startActivity 不受
                // 这条限制：启动一个透明页把同一条 toggle 交给应用自己完成，不打断当前界面。
                XposedBridge.log(TAG + "toggle " + id + " provider unavailable: " + error);
                Intent fallback = new Intent();
                fallback.setComponent(new ComponentName("com.inkcontrol", "com.inkcontrol.ui.PanelToggleActivity"));
                fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
                fallback.putExtra("feature", id);
                fallback.putExtra("enabled", on);
                try { context.startActivity(fallback); }
                catch (Throwable second) {
                    XposedBridge.log(TAG + "toggle " + id + " fallback failed: " + second);
                    MAIN.post(() -> toast(context, name + "切换失败"));
                }
            }
        }, "inkcontrol-toggle").start();
    }

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
        // 翻页动画沿用「整屏翻页」那个折角纸页图标：系统界面里没有更贴切的现成资源。
        if ("pageanim".equals(action)) return "ic_qs_turn_over_page";
        // 全刷扫过是「整屏闪一下去残影」，和自动刷新共用刷新图标。
        if ("gcsweep".equals(action)) return "ic_menu_refresh";
        // 刷新格言也是「换一下再看」，沿用同一个刷新图标。
        if ("motto".equals(action)) return "ic_menu_refresh";
        if ("quick".equals(action)) return "ink_launch_pad_settings";
        return "";
    }

    static String label(String action) {
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
    static void toast(Context context, String text) {
        try {
            Toast toast = Toast.makeText(context, com.inkcontrol.platform.HookText.t(context, text), Toast.LENGTH_SHORT);
            // 用系统默认位置（底部居中）。这里不再手动 setGravity 抬到顶部：
            // 面板虽是 type=0x7d9、比 Toast 高一层，但点完会先收面板（见 click / toggle），
            // 提示落到底部时面板已经没了，不会被压住。
            toast.show();
        } catch (Throwable error) {
            XposedBridge.log(TAG + "toast failed: " + error);
        }
    }

    static int id(Context context, String name) {
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
