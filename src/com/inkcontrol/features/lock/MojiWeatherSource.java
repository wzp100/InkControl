package com.inkcontrol.features.lock;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

import com.inkcontrol.shared.WeatherData;

import java.util.HashMap;
import java.util.Map;

/**
 * 直接读取系统预装天气软件（com.moji.hmct）已经下载好的天气。
 *
 * 原厂锁屏天气并不自己联网：它向桌面时钟/天气小组件（com.hmct.clock.systemwidget 的
 * DataProvider）取数，所以桌面上没放那个小组件时，原厂锁屏也没有天气。
 * 墨迹天气本身对外暴露了两个只读内容提供者（只有 writePermission，读取不设限）：
 *   content://com.moji.hisense.provider.area/hisense/area
 *       城市列表，列 cityId、type；
 *   content://com.moji.hisense.provider.weather/hisense/weather/&lt;cityId&gt;/widget
 *       该城市天气，列为 cityId、cityName、isLocation、isDefault、weatherCode、weatherIcon、
 *       temperature、temperatureUnit、weather（JSON 预报）、updateTime、aqi 等。
 * 字段与桌面小组件、原厂锁屏用的是同一套，所以直接复用 {@link WeatherData#from(Map)} 解析，
 * 不额外联网、也不写任何配置。
 *
 * 注意：天气软件的数据服务要先有进程才响应（开机后从没打开过它、或它被冻结时查不到），
 * 这时一律返回 null，由调用方退回原厂数据；用户打开一次天气软件后即可正常取到。
 */
final class MojiWeatherSource {
    private static final String AREA = "content://com.moji.hisense.provider.area/hisense/area";
    private static final String WEATHER = "content://com.moji.hisense.provider.weather/hisense/weather/%s/widget";
    /** 缓存有效期：天气软件本身十几分钟到一小时才更新一次，不必每次出图都查。 */
    private static final long TTL_MS = 10 * 60 * 1000L;
    /** 上次没取到数据时的重试间隔：天气软件的数据服务要先被拉起来，短一点才能尽快补上。 */
    private static final long RETRY_MS = 30 * 1000L;

    private static volatile Bundle sCache;
    private static volatile long sFetched;
    private static volatile boolean sFetching;

    private MojiWeatherSource() {
    }

    /**
     * 取缓存值；过期或为空时在后台刷新，本次仍返回旧值（可能为 null）。
     * 查询要走跨进程 ContentProvider，不能在出图线程上等，所以永不阻塞。
     */
    static Bundle get(Context context) {
        Bundle value = sCache;
        long age = System.currentTimeMillis() - sFetched;
        if (value == null ? age >= RETRY_MS : age >= TTL_MS) prefetch(context);
        return value;
    }

    /** SystemUI 启动、或用户刚切到「天气App」时先取一次，第一次息屏就能带上天气。 */
    static void prefetch(Context context) {
        if (context == null || sFetching) return;
        sFetching = true;
        final Context app = context.getApplicationContext() == null ? context : context.getApplicationContext();
        Thread worker = new Thread(() -> {
            Bundle data = null;
            try {
                data = fetch(app);
            } catch (Throwable ignored) {
            } finally {
                if (data != null) sCache = data;
                sFetched = System.currentTimeMillis();
                sFetching = false;
            }
        }, "inkcontrol-moji-weather");
        worker.setDaemon(true);
        worker.start();
    }

    /** 城市列表里逐个问，优先当前定位城市，其次默认城市，最后是第一个能取到数据的。 */
    private static Bundle fetch(Context context) {
        Cursor area = null;
        Bundle best = null;
        int bestScore = 0;
        try {
            area = context.getContentResolver().query(Uri.parse(AREA), null, null, null, null);
            if (area == null) return null;
            int column = area.getColumnIndex("cityId");
            if (column < 0) return null;
            while (area.moveToNext()) {
                String city = area.getString(column);
                if (city == null || city.isEmpty()) continue;
                Bundle row = read(context, city);
                if (row == null) continue;
                int score = score(row);
                if (score > bestScore) {
                    best = row;
                    bestScore = score;
                }
                if (bestScore >= 3) break;
            }
        } catch (Throwable ignored) {
        } finally {
            close(area);
        }
        return best;
    }

    private static int score(Bundle row) {
        if ("1".equals(row.getString("isLocation"))) return 3;
        if ("1".equals(row.getString("isDefault"))) return 2;
        return 1;
    }

    private static Bundle read(Context context, String city) {
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver()
                    .query(Uri.parse(String.format(WEATHER, city)), null, null, null, null);
            if (cursor == null || !cursor.moveToFirst()) return null;
            Map<String, Object> raw = new HashMap<>();
            for (String key : new String[]{"weatherCode", "temperature", "temperatureUnit", "weather", "updateTime"})
                raw.put(key, text(cursor, key));
            // 只有城市没有天气时（刚装好还没下载）不当作可用数据
            if (raw.get("temperature") == null && raw.get("weatherCode") == null) return null;
            Bundle data = WeatherData.from(raw);
            data.putString("city", text(cursor, "cityName"));
            data.putString("isLocation", text(cursor, "isLocation"));
            data.putString("isDefault", text(cursor, "isDefault"));
            return data;
        } catch (Throwable ignored) {
            return null;
        } finally {
            close(cursor);
        }
    }

    private static String text(Cursor cursor, String name) {
        int column = cursor.getColumnIndex(name);
        if (column < 0 || cursor.isNull(column)) return null;
        return cursor.getString(column);
    }

    private static void close(Cursor cursor) {
        if (cursor == null) return;
        try {
            cursor.close();
        } catch (Throwable ignored) {
        }
    }
}
