package com.inkcontrol.features.motion;

import android.os.SystemClock;
import android.util.Log;

import java.lang.reflect.Method;

/**
 * 通往 HWC（GPU 显示服务 libeinkgpu.so）的波形层动画请求。
 *
 * system_server 写 debug.eink.motion.req = "序号 动画 错开帧数 ox oy 方向 截止时间"，
 * 截止时间是 SystemClock.uptimeMillis（CLOCK_MONOTONIC）。HWC 在截止前的第一次“大面积变化”的
 * A2 更新里，让每个像素晚 S(x,y) 帧起跑（墨水屏刷新分析/实验/native/service_motion.hpp）。
 * debug_prop：system_server 可写、HWC 可读，不需要改 SELinux。
 *
 * system_server 读不到 vendor 下的文件，没法确认 HWC 是否支持；不支持时请求会被忽略，效果等同瞬切。
 * 设置界面用 root 检查库里的标记 EINK_MOTION_V1，决定哪些动画可以选。
 */
final class HwcBridge {
    static final String PROP_REQ = "debug.eink.motion.req";
    static final String PROP_GC = "debug.eink.motion.gc";
    static final String PROP_CAP = "debug.eink.motion.cap";
    static final String PROP_LOCK_CAP = "debug.eink.motion.lockcap";
    static final String LIB = "/vendor/lib64/hw/libeinkgpu.so";
    static final String MARKER = "EINK_MOTION_V2";
    /** 请求的有效期：过渡开始后多久之内出现的大面积更新才算数 */
    private static final long VALID_MS = 1000;

    private static Method sSet;
    private static int sSeq;
    private static volatile long sBoundaryUntil;
    private static String originalGc, writtenGc, writtenRequest;

    static String capabilities() {
        try {
            return (String) Class.forName("android.os.SystemProperties")
                    .getMethod("get", String.class, String.class).invoke(null, PROP_CAP, "");
        } catch (Throwable t) { return ""; }
    }

    /** 空能力表示“还没读到”（后端未下发）而不是“不支持”：照常发请求，HWC 不认识就忽略，等同瞬切。 */
    static boolean supports(String style) {
        String caps = capabilities();
        return caps.isEmpty() || ("," + caps + ",").contains("," + style + ",");
    }
    static boolean supportsLock(String style) {
        try {
            String caps = (String) Class.forName("android.os.SystemProperties")
                    .getMethod("get", String.class, String.class).invoke(null, PROP_LOCK_CAP, "");
            return caps.isEmpty() || ("," + caps + ",").contains("," + style + ",");
        } catch (Throwable t) { return false; }
    }

    static boolean requestLock(String style, int spread, String dir, boolean entering) {
        if (!supportsLock(style)) return false;
        // target=1只允许熄屏阅读bitmap消费；target=2只允许亮屏普通波形消费。
        String value = SystemClock.uptimeMillis() + " " + style + " " + spread
                + " 360 720 " + dir + " " + (SystemClock.uptimeMillis() + 2000) + " 1 " + (entering ? 1 : 2);
        boolean sent = set(PROP_REQ, value);
        if (sent) sBoundaryUntil = SystemClock.uptimeMillis() + 2000;
        return sent;
    }

    static void cancelPending() {
        sBoundaryUntil = 0;
        if (writtenRequest != null && writtenRequest.equals(get(PROP_REQ))) set(PROP_REQ, "");
        writtenRequest = null;
    }

    /**
     * 全刷扫过方向。auto 交给 native 按形状决定：横向从左向右、纵向从上向下。
     * 与「动画擦除方向」互不影响 —— 早先这条策略只写 spread，方向在 native 侧被写死 ltr。
     */
    static String gcsweepDir(String dir) {
        return dir == null || dir.isEmpty() ? "auto" : dir;
    }

    static void configure(Config c) {
        if (Config.GCSWEEP.equals(c.styleFor(Config.GC_CLEAR))) {
            if (originalGc == null) originalGc = get(PROP_GC);
            // 格式 "gcsweep <spread> <dir> <style>"；旧的两段格式仍按 ltr + 线性扫过解析。
            writtenGc = "gcsweep " + c.gcSpread + " " + gcsweepDir(c.gcDir) + " " + c.gcStyle;
            if (!set(PROP_GC, writtenGc)) com.inkcontrol.platform.RuntimeConfig.fail("全刷动画配置未写入");
        } else if (originalGc != null) {
            if (writtenGc.equals(get(PROP_GC))) set(PROP_GC, originalGc);
            originalGc = writtenGc = null;
        }
        if (!c.enabled) cancelPending();
    }

    private static String get(String key) {
        try { return (String) Class.forName("android.os.SystemProperties").getMethod("get", String.class, String.class).invoke(null, key, ""); }
        catch (Throwable error) { return ""; }
    }

    private static boolean set(String key, String value) {
        try {
            if (sSet == null) sSet = Class.forName("android.os.SystemProperties").getMethod("set", String.class, String.class);
            sSet.invoke(null, key, value);
            if (PROP_REQ.equals(key)) writtenRequest = value;
            return true;
        } catch (Throwable t) { Log.e(Hook.TAG, "property write failed: " + key, t); return false; }
    }

    private HwcBridge() {}

    static boolean request(String style, int spread, float ox, float oy, String dir) {
        return request(style, spread, ox, oy, dir, false);
    }

    static boolean request(String style, int spread, float ox, float oy, String dir, boolean dialog) {
        if (SystemClock.uptimeMillis() < sBoundaryUntil) return false;
        if (!supports(style)) return false;
        try {
            if (sSet == null) sSet = Class.forName("android.os.SystemProperties").getMethod("set", String.class, String.class);
            String v = (++sSeq) + " " + style + " " + spread + " " + (int) ox + " " + (int) oy + " " + dir + " "
                    + (SystemClock.uptimeMillis() + VALID_MS) + " " + (dialog ? 1 : 12);
            sSet.invoke(null, PROP_REQ, v);
            writtenRequest = v;
            return true;
        } catch (Throwable t) {
            Log.e(Hook.TAG, "wave request failed", t);
            return false;
        }
    }
}
