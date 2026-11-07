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
import com.inkcontrol.core.FeatureCatalog;
import com.inkcontrol.core.FeatureService;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/** 只传配置快照和有身份约束的状态/媒体数据，不暴露配置写入或Root执行。 */
public final class ConfigProvider extends ContentProvider {
    public static final Uri URI = Uri.parse("content://com.inkcontrol.state");
    /** 刷新面板里「点一下开关」的几项：自动刷新、锁屏主题、遮挡息屏、翻页动画。 */
    private static final String[] TOGGLES = {"refresh.master", "lock.style", "wave.lock", "pageanim.master"};
    private AndroidConfigStore store;
    private FeatureService features;
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
    /** 面板里的开关只在应用进程里能做：写的是应用私有目录里的配置，还要 root 让后端应用。 */
    private FeatureService features() {
        if (features == null) {
            RootBridge root = new RootBridge(getContext());
            features = new FeatureService(store, (before, after) -> root.run("apply", after.text()), new RuntimeStatus());
        }
        return features;
    }
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
            if ("toggle".equals(method)) {
                // 刷新面板上「点一下开关」的几项：系统界面没有墨控私有目录的写权限，
                // 也没法直接让后端应用配置，所以把这件事转给应用进程做。
                // self() 是兜底路径：应用被强制停止时 provider 拉不起来，由透明页把同一条
                // 调用从应用自己的进程发过来（见 PanelToggleActivity）。
                require(systemui || self());
                String id = limited(input, "feature", 64);
                if (!Arrays.asList(TOGGLES).contains(id)) throw new IllegalArgumentException("不支持的面板开关");
                Configuration before = store.load();
                // 面板与兜底页都带明确的目标状态；没带的旧调用方仍按「取反」处理。
                // 显式目标值让失败重试保持幂等，不会把开关又翻回去。
                final boolean enable = input.containsKey("enabled") ? input.getBoolean("enabled") : !before.enabled(id);
                final String value = before.value(id);
                // 写盘 + root 应用要一两秒。这条 call 由系统界面发起，配置一改它就得回来重读，
                // 同步等会卡住系统界面主线程，所以放后台线程，先回目标状态。
                new Thread(() -> {
                    try {
                        features().change(id, enable, value);
                        // 配置是在应用进程里写的，但界面拿的是打开页面那一刻的快照。
                        // 写盘自带的 /config 通知是给被注入进程重读用的，这里再单独发一条
                        // 给 App 界面：收到后重读快照并重画，否则开关样式会一直停在旧状态。
                        changed("panel-toggle");
                    } catch (Throwable error) { reportToggleFailure(id, error); }
                }, "inkcontrol-toggle").start();
                Bundle result = new Bundle();
                result.putBoolean("enabled", enable);
                return result;
            }
            throw new IllegalArgumentException("未知接口");
        } catch (RuntimeException error) { throw error; }
        catch (Exception error) { throw new IllegalStateException("配置读取失败，保留原文件", error); }
    }
    /**
     * 面板那条提示是按目标状态写的，真失败了要补一条，别让人以为已经打开。
     * 面板是盖住下半屏的悬浮窗，这条 Toast 用默认位置（底部），面板收起后才看得见。
     */
    private void reportToggleFailure(String id, Throwable error) {
        android.util.Log.w("InkControl", "面板开关失败：" + id, error);
        String name = FeatureCatalog.get(id).title;
        String reason = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        new android.os.Handler(getContext().getMainLooper()).post(() ->
                android.widget.Toast.makeText(getContext(), name + "切换失败：" + reason,
                        android.widget.Toast.LENGTH_LONG).show());
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) { throw new UnsupportedOperationException(); }
    @Override public String getType(Uri uri) { return "application/vnd.inkcontrol.state"; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
