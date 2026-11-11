package com.inkcontrol.platform;

import android.content.Context;
import com.inkcontrol.i18n.TextCatalog;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * 系统界面、桌面等被注入进程里的提示文字翻译。
 *
 * <p>界面语言是墨控自己的偏好（跟随系统 / 中文 / English），被注入进程读不到应用的偏好文件，
 * 所以由 {@link ConfigProvider} 在 {@code readConfig} 里一并给出，{@link RuntimeConfig} 每次重读配置时更新。
 * 翻译表就是应用界面用的那一份 {@code assets/i18n/en.tsv}，从墨控的安装包里读。
 * 任何一步失败都退回中文原文，不影响提示本身。
 */
public final class HookText {
    private static volatile boolean english;
    private static volatile TextCatalog catalog;

    /** 应用界面当前是否显示英文：偏好为 en，或跟随系统且系统语言不是中文。 */
    static boolean english(Context context) {
        try {
            String selected = context.createDeviceProtectedStorageContext()
                    .getSharedPreferences("ui-language", Context.MODE_PRIVATE).getString("language", "system");
            if ("zh".equals(selected)) return false;
            if ("en".equals(selected)) return true;
            return !"zh".equals(context.getResources().getConfiguration().getLocales().get(0).getLanguage());
        } catch (RuntimeException error) { return false; }
    }

    static void language(boolean value) { english = value; }

    /** 按应用界面语言翻译一条提示；中文界面或翻译表不可用时原样返回。 */
    public static String t(Context context, String value) {
        if (!english || value == null || context == null) return value;
        try {
            TextCatalog current = catalog;
            if (current == null) synchronized (HookText.class) {
                current = catalog;
                if (current == null) {
                    Context module = "com.inkcontrol".equals(context.getPackageName()) ? context
                            : context.createPackageContext("com.inkcontrol", 0);
                    try (InputStreamReader reader = new InputStreamReader(module.getAssets().open("i18n/en.tsv"), StandardCharsets.UTF_8)) {
                        catalog = current = new TextCatalog(reader);
                    }
                }
            }
            return current.translate(value, true);
        } catch (Throwable error) { return value; }
    }

    private HookText() { }
}
