package com.inkcontrol.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;

/**
 * 操作结果提示条（顶部那行「已保存…」）的显示时长。
 * 纯界面偏好：不进入功能配置、不参与后端校验，因此单独放在 SharedPreferences 里。
 */
final class UiNotice {
    /** 可选时长（秒）；0 表示不显示结果提示。 */
    static final int[] OPTIONS = {5, 30, 60, 0};
    static final int DEFAULT = 30;

    private static SharedPreferences preferences(Context context) {
        return context.createDeviceProtectedStorageContext().getSharedPreferences("ui-notice", Context.MODE_PRIVATE);
    }
    /** 结果提示停留的秒数；0 表示不显示。 */
    static int seconds(Context context) {
        return preferences(context).getInt("seconds", DEFAULT);
    }
    static void setSeconds(Context context, int value) {
        preferences(context).edit().putInt("seconds", value).commit();
    }
    /** 当前时长的名称，例如「30 秒」「不显示」。 */
    static String label(int seconds) {
        return seconds <= 0 ? "不显示" : seconds + " 秒";
    }
    static void choose(MainActivity activity) {
        int current = seconds(activity);
        int checked = current == 0 ? 3 : current == 5 ? 0 : current == 60 ? 2 : 1;
        String[] labels = {"5 秒", "30 秒", "60 秒", "不显示"};
        for (int i = 0; i < labels.length; i++) labels[i] = UiLanguage.text(activity, labels[i]);
        new AlertDialog.Builder(activity)
                .setTitle(UiLanguage.text(activity, "提示时长"))
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    setSeconds(activity, OPTIONS[which]);
                    dialog.dismiss();
                    activity.refreshUi();
                })
                .setNegativeButton(android.R.string.cancel, null).show();
    }
    private UiNotice() { }
}
