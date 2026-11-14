package com.inkcontrol.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** 不依赖 Android 的不可变配置；文本格式只允许注册的选项，不执行文本。 */
public final class Configuration {
    public final long revision;
    private final Map<String, Boolean> switches;
    private final Map<String, String> options;

    public Configuration() { this(0, new LinkedHashMap<>(), new LinkedHashMap<>()); }
    private Configuration(long revision, Map<String, Boolean> switches, Map<String, String> options) {
        this.revision = revision;
        this.switches = Collections.unmodifiableMap(switches);
        this.options = Collections.unmodifiableMap(options);
    }
    /** 配置里没写过这个键时，取功能自己的 defaultOn（「显示设备状态」的四项显示开关默认开）。 */
    public boolean requested(String id) {
        // 必选参数没有开关：即便配置里残留 0（旧版本／手改），也一律按开启处理。
        if (FeatureCatalog.required(id)) return true;
        FeatureDefinition definition = FeatureCatalog.get(id);
        Boolean stored = switches.get(id);
        return stored == null ? definition.defaultOn : stored;
    }
    public String value(String id) { return options.containsKey(id) ? options.get(id) : FeatureCatalog.get(id).defaultValue(); }
    public boolean enabled(String id) {
        FeatureDefinition definition = FeatureCatalog.get(id);
        return requested(id) && (definition.dependency.isEmpty() || enabled(definition.dependency));
    }
    public Configuration change(String id, boolean enabled, String value) {
        FeatureCatalog.get(id).validate(value);
        Map<String, Boolean> flags = new LinkedHashMap<>(switches);
        Map<String, String> values = new LinkedHashMap<>(options);
        flags.put(id, FeatureCatalog.required(id) || enabled);
        values.put(id, value);
        return new Configuration(revision + 1, flags, values);
    }
    public Configuration disableAll() {
        return new Configuration(revision + 1, new LinkedHashMap<>(), new LinkedHashMap<>(options));
    }
    /** 只把某一分类恢复默认（关闭并取默认值）；其它分类与布局无关的原始设置不动。 */
    public Configuration restoreGroup(String group) {
        Map<String, Boolean> flags = new LinkedHashMap<>(switches);
        Map<String, String> values = new LinkedHashMap<>(options);
        for (FeatureDefinition feature : FeatureCatalog.all()) {
            if (group.equals(feature.group)) {
                flags.put(feature.id, FeatureCatalog.required(feature.id) || feature.defaultOn);
                values.put(feature.id, feature.defaultValue());
            }
        }
        return new Configuration(revision + 1, flags, values);
    }
    public String text() {
        StringBuilder out = new StringBuilder("version=1\nrevision=" + revision + "\n");
        for (FeatureDefinition feature : FeatureCatalog.all()) {
            out.append(feature.id).append('=').append(requested(feature.id) ? '1' : '0').append('\n');
            out.append(feature.id).append(".value=").append(value(feature.id)).append('\n');
        }
        return out.toString();
    }
    public static Configuration parse(String text) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (String line : text.split("\n")) {
            if (line.isEmpty()) continue;
            int split = line.indexOf('=');
            if (split <= 0 || line.indexOf('\r') >= 0) throw new IllegalArgumentException("配置格式错误");
            if (fields.put(line.substring(0, split), line.substring(split + 1)) != null) throw new IllegalArgumentException("配置字段重复");
        }
        if (!"1".equals(fields.remove("version"))) throw new IllegalArgumentException("配置版本不支持");
        String rev = fields.remove("revision");
        if (rev == null || !rev.matches("[0-9]{1,15}")) throw new IllegalArgumentException("配置修订号无效");
        Map<String, Boolean> flags = new LinkedHashMap<>();
        Map<String, String> values = new LinkedHashMap<>();
        // 旧配置的「拓展显示」是三选一（system / wechat / ext）。ext 现在独立成
        // 「显示设备状态」开关，收到旧值时把那个开关补上（新配置里已经有 lock.ext 就以新值为准）。
        boolean legacyExt = false;
        boolean hasExtKey = fields.containsKey("lock.ext");
        for (FeatureDefinition feature : FeatureCatalog.all()) {
            String flag = fields.remove(feature.id), value = fields.remove(feature.id + ".value");
            if (flag != null && !"0".equals(flag) && !"1".equals(flag)) throw new IllegalArgumentException("功能开关无效");
            if (value == null) value = feature.defaultValue();
            if ("refresh.period".equals(feature.id) && "0".equals(value)) {
                value = feature.defaultValue();
                flag = "0";
            }
            if ("lock.notify".equals(feature.id) && "ext".equals(value)) {
                legacyExt = true;
                value = feature.defaultValue();
                flag = "0";
            }
            // 「显示设备状态」原来是单值开关（default），现在分「跟随样式/图标版/文字版」
            if ("lock.ext".equals(feature.id) && "default".equals(value)) value = feature.defaultValue();
            feature.validate(value);
            // 没写过的键不入表：requested() 会退回功能自己的 defaultOn，
            // 这样旧配置升级后「四项显示开关」默认仍是全开。
            // 必选参数例外：无论读写到什么，都固定按开启存，避免关掉后拼不出完整配置。
            if (FeatureCatalog.required(feature.id)) flags.put(feature.id, true);
            else if (flag != null) flags.put(feature.id, "1".equals(flag));
            values.put(feature.id, value);
        }
        if (legacyExt && !hasExtKey) flags.put("lock.ext", true);
        // 旧版残留键已删除：「字体2」（font2.*）并入「字体」（font.*）；
        // 「Google 设置图标修复」（fix.google）、「隐藏锁屏通知」（lock.notifyhide）并入「锁屏通知」、
        // 「体感息屏唤醒」（wave.master）整体下线；「字体换肤」（desktop.font）也下线，
        // 全局「字体」（font.dir）已完全取代它。设置状态的四项显示开关（lock.ext.wifi/bt/audio/airplane）
        // 曾经下线过一阵子，旧配置里可能残留同名的行；它们现在是正式功能，已被上面的循环取走，
        // 这里仍保留匹配以防只写了半行（只有 flag 没有 .value）。
        // 容忍旧配置里的残留键，否则升级后 parse 抛「存在未知配置字段」
        // → 整个界面只剩错误卡、连「模块设置」都进不去。
        // 「截屏便签那个位置」（qs.pinlaunch）已下线：它原来是原厂那一格的四选一，
        // 现在那一格就是动作表里的「截屏便签」，隐藏/换应用/无都由 qs.panel.btnN 表达。
        fields.keySet().removeIf(key -> {
            String name = key.endsWith(".value") ? key.substring(0, key.length() - 6) : key;
            return FeatureCatalog.retired(name)
                    || name.matches("lock\\.ext\\.(wifi|bt|audio|airplane)");
        });
        if (!fields.isEmpty()) throw new IllegalArgumentException("存在未知配置字段");
        return new Configuration(Long.parseLong(rev), flags, values);
    }
}
