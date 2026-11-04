package com.inkcontrol.platform;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import com.inkcontrol.core.Configuration;
import com.inkcontrol.core.CallerPolicy;
import java.util.HashMap;
import java.util.Map;

/** 只传配置快照和有身份约束的状态/媒体数据，不暴露配置写入或Root执行。 */
public final class ConfigProvider extends ContentProvider {
    public static final Uri URI = Uri.parse("content://com.inkcontrol.state");
    private AndroidConfigStore store;
    private final Map<String, Bundle> reports = new HashMap<>();
    private Bundle weather = new Bundle(), media = new Bundle();
    private long sequence;
    private String command = "";

    @Override public boolean onCreate() {
        store = new AndroidConfigStore(getContext());
        // 通知存活的 Hook 进程重新提交瞬时状态，不持久化媒体或天气副本。
        new android.os.Handler(getContext().getMainLooper()).postDelayed(() -> changed("ready"), 200);
        return true;
    }
    private boolean self() { return Binder.getCallingUid() == Process.myUid(); }
    private int uid(String name) {
        try { return getContext().getPackageManager().getApplicationInfo(name, 0).uid; }
        catch (Exception ignored) { return -1; }
    }
    private static void require(boolean allowed) { if (!allowed) throw new SecurityException("墨控：调用方未获授权"); }
    private void changed(String path) { getContext().getContentResolver().notifyChange(Uri.withAppendedPath(URI, path), null); }
    private static String limited(Bundle input, String key, int length) {
        String value = input.getString(key, "");
        if (value == null) return "";
        return value.substring(0, Math.min(length, value.length()));
    }
    @Override public synchronized Bundle call(String method, String arg, Bundle extras) {
        boolean system = Binder.getCallingUid() == 1000;
        int systemuiUid = uid("com.android.systemui"), launcherUid = uid("com.hmct.vision");
        boolean systemui = Binder.getCallingUid() == systemuiUid, launcher = Binder.getCallingUid() == launcherUid;
        require(CallerPolicy.read(Binder.getCallingUid(), Process.myUid(), systemuiUid, launcherUid));
        Bundle input = extras == null ? new Bundle() : extras;
        try {
            if ("readConfig".equals(method)) {
                Bundle result = new Bundle();
                result.putString("text", store.load().text());
                return result;
            }
            if ("reportStatus".equals(method)) {
                String role = limited(input, "role", 16);
                require(CallerPolicy.report(role, Binder.getCallingUid(), systemuiUid, launcherUid));
                String text = limited(input, "text", 32768);
                Configuration.parse(text);
                Bundle report = new Bundle();
                report.putInt("pid", Binder.getCallingPid());
                report.putString("start", limited(input, "start", 32));
                report.putString("version", limited(input, "version", 64));
                report.putString("text", text);
                report.putString("error", limited(input, "error", 300));
                reports.put(role, report);
                return new Bundle();
            }
            if ("reports".equals(method)) {
                require(self());
                Bundle result = new Bundle();
                for (Map.Entry<String, Bundle> entry : reports.entrySet()) result.putBundle(entry.getKey(), new Bundle(entry.getValue()));
                return result;
            }
            if ("get".equals(method)) {
                Configuration config = store.load();
                Bundle result = new Bundle();
                result.putBoolean("enabled", config.enabled("desktop.theme"));
                result.putBoolean("icons", config.enabled("desktop.icons"));
                result.putBoolean("wallpaper", config.enabled("desktop.wallpaper"));
                result.putBoolean("widget", config.enabled("desktop.widget"));
                result.putString("theme", config.value("desktop.theme"));
                result.putString("kind", config.value("desktop.widget"));
                result.putAll(media);
                result.putAll(weather);
                return result;
            }
            if ("publish-weather".equals(method)) {
                require(launcher);
                Bundle value = new Bundle();
                for (String key : new String[]{"weather", "temperature", "high", "low", "lunar"}) value.putString(key, limited(input, key, 64));
                value.putBoolean("weatherStale", input.getBoolean("weatherStale"));
                weather = value;
                changed("weather");
                return new Bundle();
            }
            if ("publish-media".equals(method)) {
                require(systemui);
                Bundle value = new Bundle();
                for (String key : new String[]{"title", "artist", "mediaPackage"}) value.putString(key, limited(input, key, 256));
                for (String key : new String[]{"playing", "mediaAvailable"}) value.putBoolean(key, input.getBoolean(key));
                for (String key : new String[]{"position", "duration", "positionTime"}) value.putLong(key, input.getLong(key, -1));
                value.putFloat("speed", input.getFloat("speed", 0));
                media = value;
                changed("media");
                return new Bundle();
            }
            if ("control".equals(method)) {
                require(self() || launcher);
                Configuration config = store.load();
                require(config.enabled("desktop.widget") && "music".equals(config.value("desktop.widget")));
                String next = input.getString("command", "");
                if (!next.equals("toggle") && !next.equals("previous") && !next.equals("next")) throw new IllegalArgumentException("无效媒体控制");
                command = next;
                sequence++;
                changed("control");
                return new Bundle();
            }
            if ("read-control".equals(method)) {
                require(systemui);
                Bundle result = new Bundle();
                result.putString("command", command);
                result.putLong("sequence", sequence);
                return result;
            }
            if ("requestRefresh".equals(method)) {
                require(self());
                changed("refresh");
                return new Bundle();
            }
            throw new IllegalArgumentException("未知接口");
        } catch (RuntimeException error) { throw error; }
        catch (Exception error) { throw new IllegalStateException("配置读取失败，保留原文件", error); }
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) { throw new UnsupportedOperationException(); }
    @Override public String getType(Uri uri) { return "application/vnd.inkcontrol.state"; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
