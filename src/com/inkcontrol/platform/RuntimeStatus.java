package com.inkcontrol.platform;

import android.os.Bundle;
import com.inkcontrol.core.*;
import java.util.LinkedHashMap;
import java.util.Map;

final class RuntimeStatus implements StatusSource {
    final Map<String, String> fields = new LinkedHashMap<>();
    private Bundle reports = new Bundle();
    private Configuration applied, boot;
    private String gpu = "";
    private long expectedRevision;
    String failure = "尚未检查环境";

    void update(String text, Bundle reports, long expectedRevision) {
        this.expectedRevision = expectedRevision;
        fields.clear(); this.reports = reports == null ? new Bundle() : reports;
        applied = section(text, "APPLIED"); boot = section(text, "BOOT");
        int from = text.indexOf("GPU_BEGIN\n"), end = text.indexOf("GPU_END");
        gpu = from < 0 || end < from ? "" : text.substring(from + 10, end);
        String header = text.split("APPLIED_BEGIN", 2)[0];
        for (String line : header.split("\n")) {
            int split = line.indexOf('=');
            if (split > 0) fields.put(line.substring(0, split), line.substring(split + 1));
        }
        failure = "";
    }
    private Configuration section(String text, String name) {
        int from = text.indexOf(name + "_BEGIN\n"), end = text.indexOf(name + "_END");
        if (from < 0 || end < from) return null;
        try { return Configuration.parse(text.substring(from + name.length() + 7, end)); }
        catch (RuntimeException ignored) { return null; }
    }
    /** 当前进程已向墨控提交过版本匹配回执的角色，即框架已在该进程加载墨控。 */
    java.util.Set<String> hooked() {
        java.util.Set<String> result = new java.util.LinkedHashSet<>();
        if (!failure.isEmpty()) return result;
        for (String role : new String[]{"system", "systemui", "launcher"}) {
            Bundle report = reports.getBundle(role);
            if (report == null || !BuildInfo.VERSION.equals(report.getString("version"))) continue;
            String start = report.getString("start", "");
            if (String.valueOf(report.getInt("pid")).equals(fields.get("pid." + role)) && !start.isEmpty() && start.equals(fields.get("start." + role))) result.add(role);
        }
        return result;
    }
    void invalidate(String message) { failure = message; fields.clear(); reports = new Bundle(); applied = boot = null; gpu = ""; }
    @Override public Evidence evidence(FeatureDefinition feature) {
        if (!failure.isEmpty()) return null;
        Configuration value;
        long acknowledged;
        String error = "";
        if (feature.role.equals("backend")) {
            if (!BuildInfo.VERSION.equals(fields.get("backend_version")) || !"1".equals(fields.get("installed"))) return null;
            if (!fields.getOrDefault("boot_id", "!").equals(fields.get("applied_boot"))) return null;
            value = feature.mode == FeatureDefinition.ApplyMode.REBOOT ? boot : applied;
            error = fields.getOrDefault("error", "");
            acknowledged = value == null ? -1 : value.revision;
            // 热更新回执可确认本轮未改变的启动期选项；实际内容必须仍与本次开机一致。
            if (value != null && applied != null && applied.enabled(feature.id) == value.enabled(feature.id) && applied.value(feature.id).equals(value.value(feature.id))) acknowledged = applied.revision;
            if (!error.isEmpty()) return new Evidence(false, "", error, expectedRevision);
            if (value != null && value.enabled(feature.id) && feature.id.equals("display.dynamic") && !"0".equals(fields.get("dynamic_current"))) return new Evidence(false, "", "系统值已被独立修改，保留该修改；重新关闭再开启可接管", acknowledged);
            if (value != null && value.enabled("display.gpu") && (feature.id.equals("display.gpu") || feature.dependency.equals("display.gpu"))) {
                if (!gpu.matches("(?s).*\"helper_loaded\"\\s*:\\s*(true|1).*")) return null;
                if (!gpu.matches("(?s).*\"telemetry_valid\"\\s*:\\s*(true|1).*")) return null;
                if (gpu.matches("(?s).*\"gpu_errors\"\\s*:\\s*[1-9][0-9]*.*")) return new Evidence(false, "", "GPU 记录到执行错误", expectedRevision);
            }
        } else {
            Bundle report = reports.getBundle(feature.role);
            if (report == null || !BuildInfo.VERSION.equals(report.getString("version"))) return null;
            try { value = Configuration.parse(report.getString("text", "")); }
            catch (RuntimeException ignored) { return null; }
            if (!RuntimeIdentity.matches(report.getInt("pid"), report.getString("start", ""), report.getString("version"), value.revision,
                    fields.get("pid." + feature.role), fields.get("start." + feature.role), BuildInfo.VERSION, value.revision)) return null;
            acknowledged = value.revision;
            error = report.getString("error", "");
        }
        return value == null ? null : new Evidence(value.enabled(feature.id), value.value(feature.id), error, acknowledged);
    }
}
