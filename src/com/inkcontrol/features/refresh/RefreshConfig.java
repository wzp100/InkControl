package com.inkcontrol.features.refresh;

import com.inkcontrol.core.Configuration;
import com.inkcontrol.core.FeatureDefinition;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** 自动刷新的运行时快照；只解释配置，不执行。 */
final class RefreshConfig {
    final boolean enabled, touch, key, reading;
    /** 是否按应用名单过滤事件计数，以及白名单（true）或黑名单（false）。 */
    final boolean scoped, whitelist;
    final int interval, debounce, delay, period;
    final Set<String> apps;
    private RefreshConfig(boolean enabled, boolean touch, boolean key, boolean reading, boolean scoped, boolean whitelist,
            int interval, int debounce, int delay, int period, Set<String> apps) {
        this.enabled = enabled; this.touch = touch; this.key = key; this.reading = reading;
        this.scoped = scoped; this.whitelist = whitelist;
        this.interval = interval; this.debounce = debounce; this.delay = delay; this.period = period;
        this.apps = apps;
    }
    static RefreshConfig from(Configuration config) {
        String filter = config.value("refresh.apps");
        Set<String> apps = new LinkedHashSet<>();
        String list = FeatureDefinition.filterList(filter);
        if (!list.isEmpty()) for (String part : list.split(",")) if (!part.isEmpty()) apps.add(part);
        return new RefreshConfig(
                config.enabled("refresh.master"),
                config.enabled("refresh.touch"),
                config.enabled("refresh.key"),
                config.enabled("refresh.reading"),
                config.enabled("refresh.apps"),
                FeatureDefinition.filterWhitelist(filter),
                number(config.value("refresh.interval"), 10),
                number(config.value("refresh.debounce"), 1000),
                number(config.value("refresh.delay"), 500),
                // 周期性刷新必须同时看独立开关：关闭时 period=0，服务据此撤销定时刷新
                config.enabled("refresh.period") ? number(config.value("refresh.period"), 300) : 0,
                Collections.unmodifiableSet(apps));
    }
    /** 事件计数是否计入该前台应用：未启用过滤时一律计入，白名单只放行名单内应用，黑名单相反。 */
    boolean allows(String packageName) {
        if (!scoped || packageName == null || packageName.isEmpty()) return true;
        return whitelist == apps.contains(packageName);
    }
    private static int number(String value, int fallback) {
        try { return Integer.parseInt(value); } catch (RuntimeException error) { return fallback; }
    }
}
