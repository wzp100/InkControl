package com.inkcontrol.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import com.inkcontrol.i18n.TextCatalog;
import java.io.InputStreamReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** UI locale only: never changes the process locale used by system hooks or persisted feature IDs. */
final class UiLanguage {
    private static volatile TextCatalog catalog;
    private static SharedPreferences preferences(Context context) {
        return context.createDeviceProtectedStorageContext().getSharedPreferences("ui-language", Context.MODE_PRIVATE);
    }
    static Context wrap(Context base) {
        String selected = preferences(base).getString("language", "system");
        if ("system".equals(selected)) return base;
        Configuration config = new Configuration(base.getResources().getConfiguration());
        config.setLocale("zh".equals(selected) ? Locale.SIMPLIFIED_CHINESE : Locale.ENGLISH);
        return base.createConfigurationContext(config);
    }
    static boolean english(Context context) {
        return !"zh".equals(context.getResources().getConfiguration().getLocales().get(0).getLanguage());
    }
    static String text(Context context, CharSequence value) {
        if (value == null) return "";
        if (!english(context)) return value.toString();
        TextCatalog current = catalog;
        if (current == null) synchronized (UiLanguage.class) {
            current = catalog;
            if (current == null) try (InputStreamReader reader = new InputStreamReader(context.getAssets().open("i18n/en.tsv"), StandardCharsets.UTF_8)) {
                catalog = current = new TextCatalog(reader);
            } catch (IOException error) { throw new IllegalStateException("Translation catalog unavailable", error); }
        }
        return current.translate(value.toString(), true);
    }
    static void choose(MainActivity activity) {
        String[] values = {"system", "zh", "en"};
        String selected = preferences(activity).getString("language", "system");
        int checked = java.util.Arrays.asList(values).indexOf(selected);
        new AlertDialog.Builder(activity)
                .setTitle("语言 / Language")
                .setSingleChoiceItems(new String[]{"跟随系统 / System", "简体中文", "English"}, Math.max(0, checked), (dialog, which) -> {
                    if (preferences(activity).edit().putString("language", values[which]).commit()) {
                        // 被注入进程里的提示文字也跟着换语言：通知它们重读配置（语言随配置一并下发）。
                        activity.getContentResolver().notifyChange(android.net.Uri.withAppendedPath(com.inkcontrol.platform.ConfigProvider.URI, "config"), null);
                        dialog.dismiss(); activity.recreate();
                    }
                }).setNegativeButton(android.R.string.cancel, null).show();
    }
    private UiLanguage() { }
}
