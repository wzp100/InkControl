package com.inkcontrol.features.lock;

import com.inkcontrol.core.Configuration;
import android.content.Context;
import android.view.View;

public final class LockPreview {
    /** 预览里底部那一块画通知框（与「显示通知」一致） */
    public static View create(Context context, int style) {
        return create(context, style, null);
    }

    /**
     * @param config 底部那一块画设备状态条还是通知框、设备状态画哪几项，都由它决定。
     *               设置页的预览跑在 App 进程，那里没有挂 RuntimeConfig，所以配置必须由调用方
     *               （UI 层）传进来，否则预览永远按「通知」画，与真实锁屏对不上。
     *               传 null 等于两个开关都关。
     */
    public static View create(Context context, int style, Configuration config) {
        LockCanvas canvas = new LockCanvas(context);
        canvas.set(style, LockData.sample(config));
        return canvas;
    }

    private LockPreview() {}
}
