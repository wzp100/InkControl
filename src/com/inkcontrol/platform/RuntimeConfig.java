package com.inkcontrol.platform;

import android.content.Context;
import android.database.ContentObserver;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import com.inkcontrol.core.Configuration;
import com.inkcontrol.core.FeatureService;
import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;

/** 每个被注入进程持有不可变快照；Provider 暂不可用时使用全关闭状态。 */
public final class RuntimeConfig {
    private static volatile Configuration snapshot = new Configuration();
    private static Context context;
    private static String role, failure = "";
    private static String initializationFailure = "";
    private static final List<Runnable> listeners = new ArrayList<>();
    private static Handler MAIN;
    private static boolean ready;
    private static int attempts;
    private RuntimeConfig() {}

    public static boolean enabled(String id) {
        Configuration config = snapshot;
        return config.enabled(id) && (!FeatureService.needsWaveBackend(id, config.value(id)) || config.enabled("display.gpu"));
    }
    public static String value(String id) { return snapshot.value(id); }
    public static long revision() { return snapshot.revision; }
    public static Configuration snapshot() { return snapshot; }
    public static synchronized void listen(Runnable callback) { listeners.add(callback); }
    public static void fail(String message) {
        failure = message;
        if (MAIN != null) MAIN.post(RuntimeConfig::report);
    }
    public static void failInitialization(String message) {
        initializationFailure = message;
        fail(message);
    }
    public static void markReady() { ready = true; if (MAIN != null) MAIN.post(RuntimeConfig::reload); }
    public static synchronized void attach(Context target, String processRole) {
        if (context != null) return;
        context = target;
        role = processRole;
        MAIN = new Handler(target.getMainLooper());
        target.getContentResolver().registerContentObserver(ConfigProvider.URI, true, new ContentObserver(MAIN) {
            @Override public void onChange(boolean self, android.net.Uri uri) {
                if (uri == null || uri.getPath() == null || uri.getPath().isEmpty() || "/config".equals(uri.getPath()) || "/ready".equals(uri.getPath())) reload();
            }
        });
        MAIN.post(RuntimeConfig::reload);
    }
    private static void reload() {
        try {
            String ready = (String) Class.forName("android.os.SystemProperties").getMethod("get", String.class, String.class).invoke(null, "sys.inkcontrol.ready", "0");
            if (!"1".equals(ready)) {
                snapshot = new Configuration();
                throw new IllegalStateException("后端安装或启动检查尚未通过");
            }
            Bundle result = context.getContentResolver().call(ConfigProvider.URI, "readConfig", null, null);
            if (result == null) throw new IllegalStateException("Provider未就绪");
            snapshot = Configuration.parse(result.getString("text", ""));
            HookText.language(result.getBoolean("english", false));
            attempts = 0;
            failure = initializationFailure;
            if ("systemui".equals(role)) SettingsEffects.apply(context);
            List<Runnable> callbacks;
            synchronized (RuntimeConfig.class) { callbacks = new ArrayList<>(listeners); }
            for (Runnable callback : callbacks) {
                try { callback.run(); }
                catch (Throwable error) { failure = error.getClass().getSimpleName() + ":" + error.getMessage(); }
            }
            report();
        } catch (Throwable error) {
            snapshot = new Configuration();
            try {
                if ("systemui".equals(role)) SettingsEffects.apply(context);
                List<Runnable> callbacks;
                synchronized (RuntimeConfig.class) { callbacks = new ArrayList<>(listeners); }
                for (Runnable callback : callbacks) callback.run();
            } catch (Throwable restoring) { failure = "恢复待检查：" + restoring.getClass().getSimpleName(); }
            android.util.Log.w("InkControl", "配置同步稍后重试", error);
            if (++attempts <= 120) MAIN.postDelayed(RuntimeConfig::reload, 3000);
        }
    }
    private static void report() {
        if (context == null || !ready) return;
        try {
            Bundle report = new Bundle();
            report.putString("role", role); report.putString("text", snapshot.text());
            report.putString("start", processStart()); report.putString("version", BuildInfo.VERSION);
            report.putString("error", failure);
            context.getContentResolver().call(ConfigProvider.URI, "reportStatus", null, report);
        } catch (Throwable error) { android.util.Log.w("InkControl", "运行回执稍后重试", error); }
    }
    private static String processStart() {
        try (BufferedReader input = new BufferedReader(new FileReader("/proc/self/stat"))) {
            String line = input.readLine();
            return line.substring(line.lastIndexOf(')') + 2).split(" ")[19];
        } catch (Exception error) { return ""; }
    }
}
