package com.inkcontrol.ui;

import android.app.Activity;
import android.os.Bundle;

import com.inkcontrol.features.lock.MottoCommand;

/**
 * 「刷新格言」的无界面入口。
 *
 * <p>桌面快捷方式以前戳的是 {@link MainActivity}，点一下会把墨控整个拉起来；换成本页之后
 * 只发一条广播就结束，屏幕上只留 SystemUI 自己那句「已换到下一条格言：…」的提示，
 * 不打断正在用的应用。
 *
 * <p>透明、不留历史、不占任务栈，用法与 {@link PanelToggleActivity} 一致：厂商对
 * {@code startActivity} 不放行限制，所以墨控即使被强制停止也能被这一下叫起来。
 *
 * <p>外部也能直接调：{@code am start -n com.inkcontrol/com.inkcontrol.ui.MottoActivity}。
 */
public final class MottoActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        // 换没换成由 SystemUI 那边 toast 反馈，这里不重复弹一条。
        MottoCommand.request(this);
        finish();
    }
}
