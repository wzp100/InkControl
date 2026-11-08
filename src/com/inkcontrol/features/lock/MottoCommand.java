package com.inkcontrol.features.lock;

import android.content.Context;
import android.content.Intent;

/**
 * 「刷新格言」的唯一出口：让 SystemUI 里的原厂 {@code MottoHelper} 往前跳一条。
 *
 * <p>所有入口都走这里——「锁屏显示」页右上角的小徽标、桌面快捷方式、快捷面板按钮，
 * 以及应用外部的 shell / 自动化调用（见 {@link MottoReceiver} 与 {@code MottoActivity}）。
 *
 * <p>真正干活的是跑在 SystemUI 进程里的 {@link Hook}：它在 {@code MottoHelper} 上找
 * {@code getNextMotto()}，只有那个进程能反射到原厂类，所以应用这边只负责发广播。
 * 广播一律带上 {@code com.android.systemui} 包名，不发给别处，也不会绕回应用自己。
 */
public final class MottoCommand {
    /**
     * 外部入口用的 action：{@code am broadcast -a com.inkcontrol.action.REFRESH_MOTTO}。
     * 由 manifest 里注册的 {@link MottoReceiver} 接住再转给 SystemUI。
     */
    public static final String ACTION = "com.inkcontrol.action.REFRESH_MOTTO";

    /** 请 SystemUI 换下一条格言。调用方进程无所谓：应用内、外部广播、透明页都能调。 */
    public static void request(Context context) {
        context.sendBroadcast(new Intent(Hook.ACTION_MOTTO_NEXT).setPackage("com.android.systemui"));
    }

    private MottoCommand() { }
}
