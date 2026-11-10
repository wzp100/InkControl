package com.inkcontrol.features.lock;

import com.inkcontrol.platform.RuntimeConfig;
import android.content.Context;
import android.provider.Settings;

/**
 * 沿用海信系统的锁屏样式键 Settings.Global eink_keyguard_style：
 * 0–4 为原版（壁纸 / 艺术 / 单词 / 诗词 / 大字时钟），5–10 为本模块新增。
 */
final class LockStyle {
    static final String KEY = "eink_keyguard_style";
    static final int EDITORIAL = 5, SWISS = 6, PIXEL = 7, GLASS = 8, EDITORIAL_LAND = 9, EDITORIAL_LAND_R = 10;
    static final String[] NAMES = {"壁纸", "艺术", "单词", "诗词", "大字时钟",
            "编辑排版", "瑞士网格", "1-bit 像素", "液态玻璃", "编辑排版-横", "编辑排版-横-逆"};

    static int read(Context context) {
        return Settings.Global.getInt(context.getContentResolver(), KEY, 0);
    }

    static boolean custom(int style) {
        return RuntimeConfig.enabled("lock.style") && style >= EDITORIAL && style <= EDITORIAL_LAND_R;
    }
}
