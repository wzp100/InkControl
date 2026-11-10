package com.inkcontrol.features.wave;

import android.app.Activity;
import android.app.KeyguardManager;
import android.os.Bundle;
import android.util.Log;

/**
 * 透明占位 Activity：被 WaveService 在移开遮挡后启动，用于调用
 * KeyguardManager.requestDismissKeyguard() 退出非安全锁屏，随后立即结束自身。
 * 对安全锁屏（PIN/图案/密码）此方法只能调出解锁挑战，无法自动越过。
 */
public final class WaveUnlockActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        if (km != null && km.isKeyguardLocked()) {
            try {
                km.requestDismissKeyguard(this, new KeyguardManager.KeyguardDismissCallback() {
                    @Override public void onDismissError() { finish(); }
                    @Override public void onDismissSucceeded() { finish(); }
                    @Override public void onDismissCancelled() { finish(); }
                });
                return;
            } catch (Throwable error) { Log.w(WaveService.TAG, "requestDismissKeyguard failed", error); }
        }
        finish();
    }
}
