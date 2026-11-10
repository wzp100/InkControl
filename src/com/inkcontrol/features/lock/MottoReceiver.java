package com.inkcontrol.features.lock;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 应用外部触发「刷新格言」的静态入口。
 *
 * <p>用 shell 或自动化工具发一条广播即可，不必打开墨控：
 * <pre>am broadcast -a com.inkcontrol.action.REFRESH_MOTTO -p com.inkcontrol</pre>
 *
 * <p>{@code -p} 必须带上：Android 8 起静态注册的接收器收不到隐式广播，不带包名会被
 * {@code BroadcastQueue} 拦成 "Background execution not allowed"，命令成功、格言不换。
 *
 * <p>为什么另设一个 action 而不是复用 {@link Hook#ACTION_MOTTO_NEXT}：那条是应用发给
 * SystemUI 的内部通道，只在两个进程之间约定，随时可能改；对外留一个固定的
 * {@link MottoCommand#ACTION} 当契约，外部脚本不会因为内部改名而失效。
 */
public final class MottoReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        MottoCommand.request(context);
    }
}
