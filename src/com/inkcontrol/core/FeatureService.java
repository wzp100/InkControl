package com.inkcontrol.core;

public final class FeatureService {
    private final ConfigStore store;
    private final FeatureExecutor executor;
    private final StatusSource source;
    public FeatureService(ConfigStore store, FeatureExecutor executor, StatusSource source) {
        this.store = store;
        this.executor = executor;
        this.source = source;
    }
    public Configuration configuration() throws Exception { return store.load(); }
    public synchronized void change(String id, boolean enabled, String value) throws Exception {
        Configuration before = store.load();
        FeatureDefinition feature = FeatureCatalog.get(id);
        feature.validate(value);
        if (enabled && !feature.dependency.isEmpty() && !before.enabled(feature.dependency)) {
            throw new IllegalStateException("请先开启：" + FeatureCatalog.get(feature.dependency).title);
        }
        if (enabled && needsWaveBackend(id, value) && !before.enabled("display.gpu")) {
            throw new IllegalStateException("该效果需要先开启 GPU 显示后端并完成安装重启");
        }
        Configuration after = before.change(id, enabled, value);
        store.save(after);
        executor.apply(before, after);
    }
    /**
     * 一次改多组取值（拖动换顺序用）：只落盘一次、只跑一次应用。
     * 逐条 {@link #change} 时每格都要重新应用一遍，四格排一次要等十几秒。
     */
    public synchronized void changeAll(java.util.LinkedHashMap<String, String> values) throws Exception {
        if (values.isEmpty()) return;
        Configuration before = store.load(), after = before;
        for (java.util.Map.Entry<String, String> entry : values.entrySet()) {
            FeatureCatalog.get(entry.getKey()).validate(entry.getValue());
            after = after.change(entry.getKey(), true, entry.getValue());
        }
        store.save(after);
        executor.apply(before, after);
    }
    public synchronized void disableAll() throws Exception {
        Configuration before = store.load(), after = before.disableAll();
        store.save(after);
        executor.apply(before, after);
    }
    public synchronized void restoreGroup(String group) throws Exception {
        Configuration before = store.load(), after = before.restoreGroup(group);
        store.save(after);
        executor.apply(before, after);
    }
    public void retryApply() throws Exception { Configuration value = store.load(); executor.apply(value, value); }
    public String status(String id) throws Exception {
        Configuration config = store.load();
        FeatureDefinition feature = FeatureCatalog.get(id);
        boolean wanted = config.enabled(id);
        if (config.requested(id) && !wanted) return "依赖未满足";
        if (wanted && needsWaveBackend(id, config.value(id)) && !config.enabled("display.gpu")) return "依赖未满足";
        // 波形动画与全刷扫过都靠 GPU 逐像素波形时序实现；「改用原厂显示算法」要求像素量化全部交回原厂
        // CPU 路径，GPU 波形管线随之停用，这两类效果不可能同时生效，必须如实标出来。
        if (wanted && needsWaveBackend(id, config.value(id)) && config.enabled("display.alg.stock")) return "依赖未满足";
        String runtimeDependency = needsWaveBackend(id, config.value(id)) ? "display.gpu" : feature.dependency;
        if (wanted && !runtimeDependency.isEmpty()) {
            FeatureDefinition dependency = FeatureCatalog.get(runtimeDependency);
            StatusSource.Evidence actual = source.evidence(dependency);
            if (actual == null || !actual.enabled || !actual.error.isEmpty() || actual.revision != config.revision || !actual.value.equals(config.value(runtimeDependency))) {
                return dependency.mode == FeatureDefinition.ApplyMode.REBOOT ? "待重启" : "依赖未满足";
            }
        }
        StatusSource.Evidence evidence = source.evidence(feature);
        boolean current = evidence != null && evidence.revision == config.revision;
        if (current && !evidence.error.isEmpty()) return "异常：" + evidence.error;
        if (current && evidence.enabled == wanted && (!wanted || config.value(id).equals(evidence.value))) return wanted ? "已生效" : "关闭";
        if (feature.mode == FeatureDefinition.ApplyMode.REBOOT && (wanted || evidence != null && evidence.enabled)) return "待重启";
        return wanted ? "等待运行确认" : evidence != null && evidence.enabled ? "等待停用确认" : "关闭";
    }
    public static boolean needsWaveBackend(String id, String value) {
        // 「无」等于原厂瞬间切换，不需要 GPU 波形管线，也不该因 GPU 关闭被判「依赖未满足」。
        if ("none".equals(value)) return false;
        return id.startsWith("motion.") && (id.equals("motion.lock") || id.equals("motion.unlock")
                || id.equals("motion.gc_clear") || value.equals("ink") || value.equals("dots") || value.equals("scan") || value.equals("blind"));
    }
}
