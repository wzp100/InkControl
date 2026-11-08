package com.inkcontrol.ui;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 首页大功能（分类）的显示顺序。
 * 纯界面偏好：不进入功能配置、不参与后端校验，因此单独放在 SharedPreferences 里。
 * 值为分类名以分号连接的序列；空串表示使用默认顺序。
 */
final class UiCategoryOrder {
    private static SharedPreferences preferences(Context context) {
        return context.createDeviceProtectedStorageContext().getSharedPreferences("ui-category-order", Context.MODE_PRIVATE);
    }
    /** 保存的顺序；空串表示默认顺序。旧版本存过「锁屏主题」，读的时候顺手换成新名字。 */
    static String saved(Context context) {
        String value = preferences(context).getString("order", "");
        // 分类改过名（锁屏主题 → 锁屏显示）：不换掉的话这一项会被当成未知分类丢掉，
        // 用户的排序就白存了。
        if (value.contains("锁屏主题")) {
            value = value.replace("锁屏主题", "锁屏显示");
            save(context, value);
        }
        return value;
    }
    static void save(Context context, String value) {
        preferences(context).edit().putString("order", value).commit();
    }
    private UiCategoryOrder() { }
}
