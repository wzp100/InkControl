package com.inkcontrol.core;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** 显示选择页共用的配置语义；只解释配置，不代替后端运行回执。 */
public final class DisplayAlgorithms {
    public static final List<String> SCOPES = Collections.unmodifiableList(Arrays.asList("clear", "reading", "fast", "extreme", "video"));

    private static void validateScope(String scope) {
        if (!SCOPES.contains(scope)) throw new IllegalArgumentException("未知显示模式：" + scope);
    }
    public static boolean gray(String scope) { validateScope(scope); return scope.equals("clear") || scope.equals("reading"); }
    public static String name(String scope) {
        validateScope(scope);
        return Arrays.asList("清晰模式", "阅读模式", "快速模式", "极速模式", "视频场景").get(SCOPES.indexOf(scope));
    }
    public static boolean algorithmFeature(String id) { return id.startsWith("display.alg.") && SCOPES.contains(id.substring(12)); }
    public static String scope(String id) {
        if (algorithmFeature(id)) return id.substring(12);
        if (id.startsWith("display.hyst.") && SCOPES.contains(id.substring(13)) && !gray(id.substring(13))) return id.substring(13);
        throw new IllegalArgumentException("不是显示算法或迟滞功能：" + id);
    }
    /** 外部 mode 的低字节决定原厂模式；不能把快速和极速合并成 A2 一项。 */
    public static String scopeForMode(int mode) {
        switch (mode & 255) {
            case 2: return "clear";
            case 3: case 4: case 5: return "reading";
            case 6: return "fast";
            case 9: return "extreme";
            default: return "";
        }
    }
    public static String algorithmProperty(String scope) { validateScope(scope); return "debug.eink.alg." + scope; }
    /** 「改用原厂显示算法」总开关：接管优先级低于该开关，开时五个模式一律回退原厂。 */
    public static boolean stock(Configuration config) { return config.enabled("display.alg.stock"); }
    public static String hysteresisProperty(String scope) {
        if (gray(scope)) throw new IllegalArgumentException("灰阶模式没有独立迟滞");
        return "debug.eink.hyst." + scope;
    }
    public static String algorithmOverride(Configuration config, String scope) {
        validateScope(scope);
        if (stock(config)) return "default";
        String id = "display.alg." + scope;
        return config.enabled(id) ? config.value(id) : "default";
    }
    /** GPU 路径里的默认值；灰阶返回 default，由原分支决定是否需要抖动。 */
    public static String baselineAlgorithm(Configuration config, String scope) {
        validateScope(scope);
        if (gray(scope)) return "default";
        if (!scope.equals("video")) return "bayer";
        return config.enabled("experiment.video") ? config.value("experiment.video") : "blue";
    }
    public static String algorithm(Configuration config, String scope) {
        validateScope(scope);
        // 原厂算法总开关开启时不接管，界面按“原厂”呈现，也不再回落到 GPU 的默认算法
        if (stock(config)) return "stock";
        String value = algorithmOverride(config, scope);
        return value.equals("default") ? baselineAlgorithm(config, scope) : value;
    }
    public static boolean supportsHysteresis(Configuration config, String scope) {
        if (gray(scope) || stock(config)) return false;
        String value = algorithm(config, scope);
        return value.equals("bayer") || value.equals("blue") || value.equals("threshold");
    }
    public static String hysteresisOverride(Configuration config, String scope) {
        hysteresisProperty(scope);
        if (stock(config)) return "default";
        String id = "display.hyst." + scope;
        return config.enabled(id) ? config.value(id) : "default";
    }
    public static boolean hysteresis(Configuration config, String scope) {
        if (!supportsHysteresis(config, scope)) return false;
        String value = hysteresisOverride(config, scope);
        return value.equals("on") || value.equals("default") && scope.equals("video");
    }
    private DisplayAlgorithms() {}
}
