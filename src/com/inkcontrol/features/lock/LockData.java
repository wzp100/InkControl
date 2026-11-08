package com.inkcontrol.features.lock;

import com.inkcontrol.core.Configuration;
import com.inkcontrol.core.FeatureDefinition;
import com.inkcontrol.platform.RuntimeConfig;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.BatteryManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.view.View;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/** 锁屏画面要用的数据。取不到的项保持 null，由绘制端显示“—”或整块不画。 */
final class LockData {
    Calendar now = Calendar.getInstance();
    boolean is24h = true;
    int battery = -1;
    boolean charging;
    /** 天气现象（如“多云”）、当前温度（如“18°”）、今日最高/最低温，没有天气数据时为 null */
    String weather, temperature, high, low;
    /** 农历（如“八月三十”）和节日/节气，取不到为 null */
    String lunar, festival;
    /** 锁屏通知数；锁屏关闭通知显示或暂时取不到时为 -1 */
    int notifications = -1;
    /** 有效倒数日组（{事项, 日期}，事项可能为空串），按日期升序，最多 3 组；未启用或未设置为 null */
    /** 倒数日清单（开关开启时读取；每组事项名可空、日期为空停用、可标记农历与是否显示） */
    List<FeatureDefinition.CountdownItem> countdowns;
    /** 通知来源名称：系统通知为“通知”，兼容微信来源时为“微信” */
    String notificationLabel = "通知";
    /** 「隐藏锁屏通知」开关：开启后所有锁屏样式都不画通知区，但通知仍在正常统计（供回到开关时立即显示） */
    boolean hideNotify;
    /** 「显示设备状态」开着：倒数日下方那一块位置改画设备开关状态，不再画通知条 */
    boolean extMode;
    /** 设备状态画成哪一种：auto（跟随样式）/ icon（图标版）/ text（文字版） */
    String extLayout = "auto";
    /** 设备状态四项的当前值（extMode 为真时才有意义） */
    boolean wifi, bt, airplane, silent;
    /** 四项里哪几项要显示（lock.ext.wifi / .bt / .audio / .airplane，缺省全开） */
    boolean showWifi = true, showBt = true, showAudio = true, showAirplane = true;
    /** 正在播放：歌词（没有歌词时用曲名），否则是格言 */
    boolean playing;
    String text;
    /** 格言的第二段（海信格言是“中文\n英文”），只在没有曲名时显示 */
    String subText;
    String track;
    long position = -1, duration = -1;

    static LockData live(Context c, Object controller) {
        LockData d = new LockData();
        d.is24h = DateFormat.is24HourFormat(c);
        try {
            Intent b = c.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (b != null) {
                int scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                if (scale > 0 && level >= 0) d.battery = Math.min(100, level * 100 / scale);
                int status = b.getIntExtra(BatteryManager.EXTRA_STATUS, 0);
                d.charging = status == BatteryManager.BATTERY_STATUS_CHARGING;
            }
        } catch (Throwable ignored) {
        }
        // 锁屏底部那一块位置只有一处，两个总开关二选一：「显示通知」与「显示设备状态」。
        // 互斥由界面保证，这里再兜一层：万一两个都为开（例如从旧配置 lock.notify.value=ext
        // 迁过来还没重新保存），按设备状态优先，不会两块都画。
        boolean notifyOn = RuntimeConfig.enabled("lock.notify");
        d.extMode = RuntimeConfig.enabled("lock.ext")
                || (notifyOn && "ext".equals(RuntimeConfig.value("lock.notify")));
        boolean notifyVisible = notifyOn && !d.extMode;
        if (notifyVisible && Settings.Global.getInt(c.getContentResolver(), "eink_keyguard_notification_on", 1) != 0) {
            if ("wechat".equals(RuntimeConfig.value("lock.notify"))) {
                d.notifications = Math.max(0, Settings.System.getInt(c.getContentResolver(), "com_hs_wechat_mini", 0));
                d.notificationLabel = "微信";
            } else {
                d.notifications = LockNotifications.count();
            }
        }
        if (d.extMode) {
            d.extLayout = RuntimeConfig.value("lock.ext");
            d.showWifi = RuntimeConfig.enabled("lock.ext.wifi");
            d.showBt = RuntimeConfig.enabled("lock.ext.bt");
            d.showAudio = RuntimeConfig.enabled("lock.ext.audio");
            d.showAirplane = RuntimeConfig.enabled("lock.ext.airplane");
            readDeviceState(d, c);
        }
        // 只是这一处不画，计数照旧；重新打开开关时不用等下一次通知事件
        d.hideNotify = !notifyVisible;
        if (RuntimeConfig.enabled("lock.weather")) readWeather(d, c, controller);
        readLunar(d, controller);
        // 倒数日：仅当「锁屏 → 倒数日」开关开启时读取 App 配置的事项与日期；
        // 开关关闭、或未配置任何事项时不绘制（测试期硬编码样张已移除，编辑器可正常配置）。
        if (RuntimeConfig.enabled("lock.countdown"))
            d.countdowns = FeatureDefinition.countdownList(RuntimeConfig.value("lock.countdowns"));

        String lyric = RuntimeConfig.enabled("lock.lyric") ? Hook.activeLyric() : null;
        if (lyric != null) {
            d.playing = true;
            d.text = lyric;
        }
        readMedia(d, c);
        if (d.text == null) {
            String motto = motto(c, controller);
            if (motto != null) {
                int nl = motto.indexOf('\n');
                if (nl > 0) {
                    d.text = motto.substring(0, nl).trim();
                    String rest = motto.substring(nl + 1).replace('\n', ' ').trim();
                    if (!rest.isEmpty()) d.subText = rest;
                } else {
                    d.text = motto;
                }
            }
        }
        return d;
    }

    /**
     * 「显示设备状态」要读的四项，全部只读不改。
     * 无线优先用 WifiManager（SystemUI 有 ACCESS_WIFI_STATE），拿不到退回全局设置；
     * 蓝牙优先用 BluetoothAdapter，拿不到同样退回全局设置；飞行模式只有全局设置；
     * 声音模式取铃声模式，只有「静音」算静音，响铃与振动都算非静音。
     */
    private static void readDeviceState(LockData d, Context c) {
        boolean wifiDone = false;
        try {
            android.net.wifi.WifiManager wifi =
                    (android.net.wifi.WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) { d.wifi = wifi.isWifiEnabled(); wifiDone = true; }
        } catch (Throwable ignored) {
        }
        if (!wifiDone) {
            try { d.wifi = Settings.Global.getInt(c.getContentResolver(), Settings.Global.WIFI_ON, 0) != 0; } catch (Throwable ignored) { }
        }
        boolean btDone = false;
        try {
            android.bluetooth.BluetoothAdapter adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter();
            if (adapter != null) { d.bt = adapter.isEnabled(); btDone = true; }
        } catch (Throwable ignored) {
        }
        if (!btDone) {
            try { d.bt = Settings.Global.getInt(c.getContentResolver(), Settings.Global.BLUETOOTH_ON, 0) != 0; } catch (Throwable ignored) { }
        }
        try {
            d.airplane = Settings.Global.getInt(c.getContentResolver(), Settings.Global.AIRPLANE_MODE_ON, 0) != 0;
        } catch (Throwable ignored) {
        }
        try {
            android.media.AudioManager audio = (android.media.AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
            d.silent = audio != null && audio.getRingerMode() == android.media.AudioManager.RINGER_MODE_SILENT;
        } catch (Throwable ignored) {
        }
    }

    /**
     * 两种数据源：默认跟随原厂锁屏的天气控件；选择「天气App」时直接读系统预装的墨迹天气。
     * 天气App 还没取到（首次查询在后台进行）或它本身没有数据时，退回原厂数据，避免整块留空。
     */
    private static void readWeather(LockData d, Context c, Object controller) {
        if ("moji".equals(RuntimeConfig.value("lock.weather"))) {
            android.os.Bundle app = MojiWeatherSource.get(c);
            if (app != null && !TextUtils.isEmpty(app.getString("temperature"))) {
                d.weather = app.getString("weather");
                d.temperature = app.getString("temperature");
                d.high = app.getString("high");
                d.low = app.getString("low");
                return;
            }
        }
        readSystemWeather(d, controller);
    }

    /**
     * 海信锁屏的天气控件在后台读天气库，这里只取它已有的结果（控件隐藏 = 原版判定无数据/过期，跟着不显示）。
     * 国行用的是 MojiWeatherView：mCityData.dataMap 里有 weatherCode（中文天气）、temperature、
     * temperatureUnit、weather（JSON 预报，第 0 天含 highTemp/lowTemp）。
     * 注意这条链路的源头是桌面时钟/天气小组件（com.hmct.clock.systemwidget 的 DataProvider），
     * 桌面上没放小组件时通常没有数据；需要不依赖桌面时可把锁屏天气切到「天气App」。
     */
    private static void readSystemWeather(LockData d, Object controller) {
        try {
            Object view = Reflect.get(Reflect.get(controller, "mFWClockArea"), "mWeather");
            if (!(view instanceof View) || ((View) view).getVisibility() != View.VISIBLE) return;
            Object map = Reflect.get(Reflect.get(view, "mCityData"), "dataMap");
            if (map instanceof java.util.Map) {
                java.util.Map<?, ?> m = (java.util.Map<?, ?>) map;
                android.os.Bundle weather = com.inkcontrol.shared.WeatherData.from(m);
                d.weather = weather.getString("weather"); d.temperature = weather.getString("temperature");
                d.high = weather.getString("high"); d.low = weather.getString("low");
                return;
            }
            Object code = Reflect.get(view, "mWeather");
            Object temp = Reflect.get(view, "mTemperature");
            Object unit = Reflect.get(view, "mTemperatureUnit");
            if (!(code instanceof Integer) || (Integer) code < 0) return;
            Class<?> dict = view.getClass().getClassLoader().loadClass("com.eink.keyguard.weather.WeatherDict");
            Object name = dict.getMethod("getPhenomenon", int.class).invoke(null, code);
            if (name instanceof String && !((String) name).isEmpty()) d.weather = (String) name;
            if (temp instanceof Integer) {
                d.temperature = temp + (unit instanceof Integer && (Integer) unit != 0 ? "°F" : "°");
            }
        } catch (Throwable ignored) {
        }
    }

    private static void readLunar(LockData d, Object controller) {
        try {
            ClassLoader cl = controller.getClass().getClassLoader();
            Object lunar = cl.loadClass("com.eink.keyguard.Lunar")
                    .getConstructor(Calendar.class).newInstance(Calendar.getInstance());
            String s = lunar.toString();
            int i = s.lastIndexOf('年');
            d.lunar = i >= 0 ? s.substring(i + 1) : s;
            Class<?> util = cl.loadClass("com.eink.keyguard.utils.CalendarUtil");
            Object f = util.getMethod("getChinaCalendar")
                    .invoke(util.getConstructor(Calendar.class).newInstance(Calendar.getInstance()));
            if (f instanceof String && !((String) f).trim().isEmpty()) d.festival = ((String) f).trim();
        } catch (Throwable ignored) {
        }
    }

    /** SystemUI 本身有媒体控制权限，直接读正在播放的会话 */
    private static void readMedia(LockData d, Context c) {
        try {
            MediaSessionManager manager = (MediaSessionManager) c.getSystemService(Context.MEDIA_SESSION_SERVICE);
            android.os.Bundle media = com.inkcontrol.shared.MediaSnapshot.read(com.inkcontrol.shared.MediaSnapshot.select(manager.getActiveSessions(null)));
            if (!media.getBoolean("playing")) return;
            String title = media.getString("title", ""), artist = media.getString("artist", "");
            d.track = title + (artist.isEmpty() ? "" : " · " + artist);
            d.position = media.getLong("position", -1);
            if (d.position >= 0 && media.getLong("positionTime") > 0) d.position += (long) ((SystemClock.elapsedRealtime() - media.getLong("positionTime")) * media.getFloat("speed"));
            d.duration = media.getLong("duration", -1); d.playing = true;
            if (d.text == null) d.text = title;
        } catch (Throwable ignored) {
        }
    }

    /** 原版格言（含用户自定义格言），和原版锁屏每次出图取一次的行为一致 */
    private static String motto(Context c, Object controller) {
        // 应用里点过「换一条格言」时先用那一条：原厂 setMotto 读的是它自己记的序号，
        // 不一定立刻反映上来，这里兜 10 分钟，够用户锁一次屏看到。
        String pending = Hook.pendingMotto();
        if (pending != null) return pending;
        try {
            Object helper = Reflect.get(Reflect.get(controller, "mVMUtil"), "mMottoHelper");
            if (helper != null) {
                TextView tv = new TextView(c);
                Reflect.call(helper, "setMotto", new Class<?>[]{Context.class, TextView.class}, c, tv);
                String s = tv.getText().toString().trim();
                if (!s.isEmpty()) return s;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 设置预览和调试出图用的样张数据（通知模式） */
    static LockData sample() {
        return sample(null);
    }

    /**
     * 同上，{@code config} 决定底部那一块画通知框还是设备状态条、设备状态画哪几项。
     * 由调用方传入而不是在这里读配置：设置页的预览跑在 App 进程，那里没有挂 RuntimeConfig，
     * 在这儿调 {@code enabled()} 永远拿到 false，预览会和真实锁屏对不上。
     * 传 null 等价于「两个开关都关」，只画通知。
     */
    static LockData sample(Configuration config) {
        LockData d = new LockData();
        d.now.set(2026, Calendar.OCTOBER, 10, 21, 47, 0);
        d.battery = 76;
        d.weather = "多云";
        d.temperature = "18°";
        d.high = "18°";
        d.low = "12°";
        d.lunar = "九月初一";
        d.countdowns = new ArrayList<>();
        d.countdowns.add(new FeatureDefinition.CountdownItem("考试", "2026-11-27", false, true));
        d.countdowns.add(new FeatureDefinition.CountdownItem("生日", "2026-12-20", false, true));
        d.countdowns.add(new FeatureDefinition.CountdownItem("春节", "2027-01-01", true, true));   // 农历示例（2027 春节=2027-02-06）
        d.notifications = 3;
        d.playing = true;
        d.text = "把今天折好，放进明天的口袋。";
        d.track = "曲名 · 歌手";
        d.position = 132000;
        d.duration = 220000;
        // 那一块位置二选一：开着「显示设备状态」却仍画通知框，会让人以为开关没生效。
        // 四项给一组有开有关的示例值，一眼能看出哪项是「关」；被关掉的显示开关也要跟着少画，
        // 否则预览比真实锁屏多一格。
        if (config != null && config.enabled("lock.ext")) {
            d.extMode = true;
            d.hideNotify = true;
            d.extLayout = config.value("lock.ext");
            d.showWifi = config.requested("lock.ext.wifi");
            d.showBt = config.requested("lock.ext.bt");
            d.showAudio = config.requested("lock.ext.audio");
            d.showAirplane = config.requested("lock.ext.airplane");
            d.wifi = true;
            d.bt = true;
            d.silent = true;
            d.airplane = false;
        }
        return d;
    }
}
