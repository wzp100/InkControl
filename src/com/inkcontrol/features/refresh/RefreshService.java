package com.inkcontrol.features.refresh;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import com.inkcontrol.core.Configuration;
import com.inkcontrol.platform.AndroidConfigStore;
import com.inkcontrol.platform.ConfigProvider;

/**
 * 统计点击、滚动、按键与阅读界面操作，达到阈值后请求墨控后端刷新墨水屏。
 * 只发出刷新请求；真正的 clear 由 system_server 的 refresh.Hook 记录并执行。
 */
public final class RefreshService extends AccessibilityService {
    static final String TAG = "EinkRefreshService";
    private static final int READ_TEXT_THRESHOLD = 150;
    private static final int READ_RATIO_THRESHOLD = 10;
    private static final String IGNORE_PACKAGE = "com.android.systemui";

    private final Object lock = new Object();
    private HandlerThread thread;
    private Handler handler;
    private AndroidConfigStore store;
    private volatile RefreshConfig config = RefreshConfig.from(new Configuration());
    /** 最近一次窗口切换的前台包名，用于按键等没有包名的按键事件按应用过滤。 */
    private volatile String foreground = "";
    private int count;
    private long lastCountTime;

    private final Runnable refresh = new Runnable() { @Override public void run() { trigger(); } };
    private final Runnable periodic = new Runnable() {
        @Override public void run() {
            RefreshConfig current = config;
            if (!current.enabled || current.period <= 0) return;
            trigger();
            handler.postDelayed(this, current.period * 1000L);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        HandlerThread started = new HandlerThread("EinkRefreshService");
        started.start();
        thread = started;
        handler = new Handler(started.getLooper());
        store = new AndroidConfigStore(getApplicationContext());
    }

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPE_VIEW_CLICKED | AccessibilityEvent.TYPE_VIEW_SCROLLED
                | AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.notificationTimeout = 100;
        info.flags = AccessibilityServiceInfo.DEFAULT | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                | AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
        setServiceInfo(info);
        try {
            getContentResolver().registerContentObserver(ConfigProvider.URI, true, new ContentObserver(new Handler(Looper.getMainLooper())) {
                @Override public void onChange(boolean self, Uri uri) {
                    if (uri == null || uri.getPath() == null) return;
                    if (uri.getPath().isEmpty() || "/config".equals(uri.getPath())) handler.post(RefreshService.this::reload);
                }
            });
        } catch (Throwable error) { Log.w(TAG, "observer registration failed", error); }
        reload();
        Log.i(TAG, "service connected");
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        RefreshConfig current = config;
        if (!current.enabled) return;
        CharSequence name = event.getPackageName();
        String pkg = name == null ? "" : name.toString();
        if (IGNORE_PACKAGE.equals(pkg)) return;
        int type = event.getEventType();
        if (type == AccessibilityEvent.TYPE_VIEW_CLICKED || type == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            if (current.touch && current.allows(pkg)) countAction();
        } else if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (!pkg.isEmpty()) foreground = pkg;
            if (current.reading && current.allows(pkg) && isReadingScreen()) countAction();
        }
    }

    @Override protected boolean onKeyEvent(KeyEvent event) {
        if (event == null || event.getAction() != KeyEvent.ACTION_DOWN) return false;
        int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_HOME || code == KeyEvent.KEYCODE_APP_SWITCH
                || code == KeyEvent.KEYCODE_MENU || code == KeyEvent.KEYCODE_POWER) return false;
        RefreshConfig current = config;
        if (current.enabled && current.key && current.allows(foreground)) countAction();
        return false;
    }

    @Override public void onInterrupt() { }

    @Override public void onDestroy() {
        if (handler != null) handler.removeCallbacksAndMessages(null);
        if (thread != null) thread.quitSafely();
        super.onDestroy();
    }

    private void reload() {
        RefreshConfig next;
        try { next = RefreshConfig.from(store.load()); }
        catch (Throwable error) { Log.w(TAG, "config load failed", error); next = RefreshConfig.from(new Configuration()); }
        config = next;
        synchronized (lock) { count = 0; }
        lastCountTime = 0;
        armPeriodic();
    }

    private void armPeriodic() {
        if (handler == null) return;
        handler.removeCallbacks(periodic);
        RefreshConfig current = config;
        if (current.enabled && current.period > 0) handler.postDelayed(periodic, current.period * 1000L);
    }

    private void countAction() {
        RefreshConfig current = config;
        if (!current.enabled || current.interval <= 0) return;
        long now = SystemClock.elapsedRealtime();
        // 合并间隔内的重复操作只算一次：滑动一次会连续上报多帧，按键也可能连按。
        if (current.debounce > 0 && now - lastCountTime < current.debounce) return;
        lastCountTime = now;
        int value;
        synchronized (lock) { value = ++count; if (value >= current.interval) count = 0; }
        if (value >= current.interval) {
            handler.removeCallbacks(refresh);
            handler.postDelayed(refresh, Math.max(0, current.delay));
        }
    }

    private void trigger() {
        try { getContentResolver().call(ConfigProvider.URI, "requestRefresh", null, null); }
        catch (Throwable error) { Log.w(TAG, "refresh request failed", error); }
    }

    private boolean isReadingScreen() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        int[] measure = new int[]{0, 0};
        collect(root, measure, 0);
        return measure[0] > READ_TEXT_THRESHOLD && measure[0] / Math.max(1, measure[1]) > READ_RATIO_THRESHOLD;
    }

    private static void collect(AccessibilityNodeInfo node, int[] measure, int depth) {
        if (node == null || depth > 40) return;
        CharSequence text = node.getText();
        if (text != null) measure[0] += text.length();
        measure[1]++;
        for (int i = 0; i < node.getChildCount(); i++) collect(node.getChild(i), measure, depth + 1);
    }
}
