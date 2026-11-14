package com.inkcontrol.ui;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Toast;

import com.inkcontrol.platform.ConfigProvider;

/**
 * 面板开关的兜底入口：透明、不留历史、立刻结束。
 *
 * <p>墨控被「强制停止」后，这台机器的进程管控会禁止 SystemUI 通过 ContentProvider 把应用
 * 进程拉起来（日志里是 {@code unable to launch app ... for provider, it's restricted}），
 * 于是面板点开关只会先报目标状态、再报「切换失败」。显式 startActivity 不受这条限制，
 * 所以 {@code PanelHook} 在 provider 调用失败时启动本页：由应用自己的进程把同一条 toggle
 * 转交给 {@link ConfigProvider}，完成后立即结束。用户不会跳到墨控主界面。
 *
 * <p>目标状态由调用方显式给出，重试不会把开关又翻回去。
 */
public final class PanelToggleActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        final String id = getIntent() == null ? "" : getIntent().getStringExtra("feature");
        final boolean enabled = getIntent() != null && getIntent().getBooleanExtra("enabled", false);
        if (id == null || id.isEmpty()) { finish(); return; }
        new Thread(() -> {
            String failure = "";
            try {
                Bundle input = new Bundle();
                input.putString("feature", id);
                input.putBoolean("enabled", enabled);
                getContentResolver().call(ConfigProvider.URI, "toggle", null, input);
            } catch (Throwable error) {
                failure = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
            }
            final String message = failure;
            runOnUiThread(() -> {
                if (!message.isEmpty()) Toast.makeText(this, UiLanguage.text(this, "切换失败：" + message), Toast.LENGTH_LONG).show();
                finish();
            });
        }, "inkcontrol-panel-retry").start();
    }
}
