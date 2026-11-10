package com.inkcontrol.features.fix;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.util.Log;

import com.inkcontrol.platform.RuntimeConfig;

/**
 * 修复「冻结安全输入服务后密码框打不开、应用锁被直接放行」。
 *
 * 海信在 system_server 的 InputMethodManagerService.startInputUncheckedLocked() 里，
 * 只要 Settings.Secure.can_use_safe_inputmethod 为 1（未设置时取原厂默认值 1），就把密码输入框的
 * mCurMethodId 硬编码成 com.android.inputmethod.pinyin/.PinyinIME，完全不检查这个组件是否还在。
 * 该包被冻结后 mMethodMap 查不到这个 ID，framework 抛
 * IllegalArgumentException("Unknown id: com.android.inputmethod.pinyin/.PinyinIME")，
 * 异常经 Binder 传回 com.android.settings 主线程：隐藏应用的密码界面崩溃，
 * 应用锁的解锁界面被 Force finishing 后系统直接放行目标应用（fail-open）。
 *
 * 原厂自己留了 can_use_safe_inputmethod 作为这段逻辑的总闸门。开启本功能时墨控把它写成 0，
 * 密码框就保持当前默认输入法；关闭时按「写入所有权」条件恢复，只动墨控自己写过的那个值，
 * 不覆盖用户或其它模块后来改过的设置。切换开关立即生效，不需要重启。
 */
public final class SafeInputEffects {
    private static final String TAG = "InkControl-SafeInput";
    private static final String KEY = "can_use_safe_inputmethod";
    /** 单独一本账，避免与锁屏样式共用账本时被 style 的 clear() 连带清掉。 */
    private static final String LEDGER = "inkcontrol-owned-settings-safeime";
    private static final String BEFORE = "safeime.before";
    private static final int UNSET = -1;
    private static final int DISABLED = 0;

    public static void apply(Context context) {
        // 这里必须自己兜住异常：外层 RuntimeConfig.reload() 一旦抛错会把整份配置重置成全关，
        // 那样为了修一个密码框，反而把其它增强全停了。
        try {
            // 配置尚未读成功时（revision == 0：开机、zygote 软重启、Provider 暂不可用），
            // RuntimeConfig 给的是「全关」快照。若照着它走关闭分支，会把用户已开启的功能
            // 误还原并清空账本，之后要等下一次配置同步才补回来。这段时间保持现状即可。
            if (RuntimeConfig.revision() == 0) return;
            SharedPreferences ledger = context.createDeviceProtectedStorageContext()
                    .getSharedPreferences(LEDGER, Context.MODE_PRIVATE);
            int current = Settings.Secure.getInt(context.getContentResolver(), KEY, UNSET);
            if (RuntimeConfig.enabled("fix.safeime")) {
                if (!ledger.contains(BEFORE)) {
                    // 只在第一次接管时记下原值；接管期间即便有人手改过，也要把开关拉回 0。
                    ledger.edit().putInt(BEFORE, current).commit();
                }
                if (current != DISABLED && !Settings.Secure.putInt(context.getContentResolver(), KEY, DISABLED)) {
                    Log.e(TAG, "can_use_safe_inputmethod=0 写入失败");
                }
                return;
            }
            if (!ledger.contains(BEFORE)) return;
            int before = ledger.getInt(BEFORE, UNSET);
            if (current == DISABLED) {
                boolean ok = before == UNSET
                        ? restoreUnset(context)
                        : Settings.Secure.putInt(context.getContentResolver(), KEY, before);
                if (!ok) Log.e(TAG, "can_use_safe_inputmethod 恢复失败");
            }
            ledger.edit().clear().commit();
        } catch (Throwable error) {
            Log.e(TAG, "安全输入法开关同步失败，保持现状", error);
        }
    }

    /** 原值本来就「未设置」时把键删掉；个别 ROM 的 putString(null) 不删键，就退回原厂默认值 1。 */
    private static boolean restoreUnset(Context context) {
        Settings.Secure.putString(context.getContentResolver(), KEY, null);
        if (Settings.Secure.getInt(context.getContentResolver(), KEY, UNSET) == UNSET) return true;
        return Settings.Secure.putInt(context.getContentResolver(), KEY, 1);
    }

    private SafeInputEffects() {}
}
