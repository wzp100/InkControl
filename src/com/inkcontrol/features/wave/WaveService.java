package com.inkcontrol.features.wave;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.KeyguardManager;
import android.database.ContentObserver;
import android.content.Context;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

import com.inkcontrol.core.Configuration;
import com.inkcontrol.platform.AndroidConfigStore;
import com.inkcontrol.platform.ConfigProvider;

/**
 * 体感息屏唤醒：复用 WaveUp 的思路，用近距离（覆盖）传感器在锁屏下自动息屏、
 * 覆盖物消失（移出口袋/移开手掌）时点亮屏幕并尝试退出非安全锁屏。
 * 与自动刷新一样走无障碍服务，因此锁屏动作可以用 performGlobalAction 完成，无需 root。
 *
 * 限制：退出安全锁屏（PIN/图案/密码）无法在无 root、无设备管理员的情况下绕过；
 * 对系统非安全锁屏调用 KeyguardManager.requestDismissKeyguard() 退出，InkControl 自定义
 * 锁屏是否随之退出取决于模块自身，需真机验证。
 */
public final class WaveService extends AccessibilityService implements SensorEventListener {
    static final String TAG = "EinkWaveService";
    private HandlerThread thread;
    private Handler handler;
    private AndroidConfigStore store;
    private SensorManager sensorManager;
    private Sensor proximity;
    private PowerManager powerManager;
    private KeyguardManager keyguardManager;
    private PowerManager.WakeLock wakeLock;

    private volatile WaveConfig config = WaveConfig.from(new Configuration());
    /** 当前是否被遮挡（near）。 */
    private volatile boolean covered = false;
    /** 本次「远」之前是否曾经被遮挡，避免初始远状态误触发唤醒。 */
    private volatile boolean everCovered = false;
    private final Runnable lockTask = new Runnable() { @Override public void run() { maybeLock(); } };

    @Override public void onCreate() {
        super.onCreate();
        HandlerThread started = new HandlerThread("EinkWaveService");
        started.start();
        thread = started;
        handler = new Handler(started.getLooper());
        store = new AndroidConfigStore(getApplicationContext());
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        keyguardManager = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        proximity = sensorManager != null ? sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY) : null;
    }

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.notificationTimeout = 0;
        info.flags = AccessibilityServiceInfo.DEFAULT;
        setServiceInfo(info);
        try {
            getContentResolver().registerContentObserver(ConfigProvider.URI, true, new ContentObserver(new Handler(Looper.getMainLooper())) {
                @Override public void onChange(boolean self, Uri uri) {
                    if (uri == null || uri.getPath() == null) return;
                    if (uri.getPath().isEmpty() || "/config".equals(uri.getPath())) handler.post(WaveService.this::reload);
                }
            });
        } catch (Throwable error) { Log.w(TAG, "observer registration failed", error); }
        reload();
        Log.i(TAG, "wave service connected; proximity=" + (proximity != null));
    }

    private void reload() {
        WaveConfig next;
        try { next = WaveConfig.from(store.load()); }
        catch (Throwable error) { Log.w(TAG, "config load failed", error); next = WaveConfig.from(new Configuration()); }
        config = next;
        handler.removeCallbacks(lockTask);
        if (sensorManager != null) sensorManager.unregisterListener(this);
        if (config.enabled && proximity != null) {
            sensorManager.registerListener(this, proximity, SensorManager.SENSOR_DELAY_NORMAL, handler);
            Log.i(TAG, "wave listening: lock=" + config.lock + " wake=" + config.wake + " delay=" + config.coverDelay);
        } else {
            covered = false; everCovered = false;
            Log.i(TAG, "wave listening disabled");
        }
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }

    @Override public void onInterrupt() { }

    @Override public void onSensorChanged(SensorEvent event) {
        if (event == null || event.sensor == null || event.sensor.getType() != Sensor.TYPE_PROXIMITY || proximity == null) return;
        boolean near = event.values[0] < proximity.getMaximumRange() * 0.5f;
        if (near) {
            if (!covered) {
                covered = true;
                everCovered = true;
                if (config.enabled && config.lock) {
                    handler.removeCallbacks(lockTask);
                    handler.postDelayed(lockTask, Math.max(0, config.coverDelay));
                }
            }
        } else if (covered) {
            covered = false;
            handler.removeCallbacks(lockTask);
            if (config.enabled && config.wake && everCovered) wakeAndUnlock();
            everCovered = false;
        }
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    private void maybeLock() {
        if (!config.enabled || !config.lock || !covered) return;
        if (powerManager.isInteractive() && !keyguardManager.isKeyguardLocked()) {
            performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN);
        }
    }

    private void wakeAndUnlock() {
        if (!powerManager.isInteractive()) acquireWakeLock();
        if (keyguardManager.isKeyguardLocked()) {
            try {
                Intent intent = new Intent(this, WaveUnlockActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            } catch (Throwable error) { Log.w(TAG, "unlock activity launch failed", error); }
        }
    }

    private void acquireWakeLock() {
        try {
            if (wakeLock == null) {
                wakeLock = powerManager.newWakeLock(
                        PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, "InkControl:WaveWake");
                wakeLock.setReferenceCounted(false);
            }
            wakeLock.acquire(2000);
        } catch (Throwable error) { Log.w(TAG, "wake lock acquire failed", error); }
    }

    @Override public void onDestroy() {
        if (handler != null) handler.removeCallbacksAndMessages(null);
        if (sensorManager != null) sensorManager.unregisterListener(this);
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (thread != null) thread.quitSafely();
        super.onDestroy();
    }
}
