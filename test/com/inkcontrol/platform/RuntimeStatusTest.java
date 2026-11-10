package com.inkcontrol.platform;
import android.os.Bundle;
import com.inkcontrol.core.*;

public final class RuntimeStatusTest {
    static int checks;
    static void check(boolean value) { checks++; if (!value) throw new AssertionError("status " + checks); }
    static String header = "protocol=1\ninstalled=1\nbackend_version=test-version\nboot_id=abc\napplied_boot=abc\npid.launcher=123\nstart.launcher=456\n";
    static Configuration off = new Configuration(), enabled = off.change("desktop.theme", true, "paper");
    static Bundle reports(int pid, String start, String version, Configuration config) {
        Bundle report = new Bundle(); report.putInt("pid", pid); report.putString("start", start); report.putString("version", version);
        report.putString("text", config.text()); report.putString("error", "");
        Bundle reports = new Bundle(); reports.putBundle("launcher", report); return reports;
    }
    static String state(Configuration applied, Configuration boot, String gpu) {
        return header + "APPLIED_BEGIN\n" + applied.text() + "APPLIED_END\nBOOT_BEGIN\n" + boot.text() + "BOOT_END\nGPU_BEGIN\n" + gpu + "\nGPU_END\n";
    }
    public static void main(String[] args) {
        RuntimeStatus status = new RuntimeStatus(); FeatureDefinition theme = FeatureCatalog.get("desktop.theme");
        status.update(state(off, off, ""), reports(123,"456",BuildInfo.VERSION,enabled), enabled.revision); check(status.evidence(theme).enabled);
        status.update(state(off, off, ""), reports(122,"456",BuildInfo.VERSION,enabled), enabled.revision); check(status.evidence(theme)==null);
        status.update(state(off, off, ""), reports(123,"455",BuildInfo.VERSION,enabled), enabled.revision); check(status.evidence(theme)==null);
        status.update(state(off, off, ""), reports(123,"456","old",enabled), enabled.revision); check(status.evidence(theme)==null);
        status.update(state(off, off, ""), reports(123,"456",BuildInfo.VERSION,off), enabled.revision); check(status.evidence(theme).revision != enabled.revision);
        status.update(state(off, off, ""), new Bundle(), enabled.revision); check(status.evidence(theme)==null);
        Configuration gpu = off.change("display.gpu", true, "default"); FeatureDefinition backend = FeatureCatalog.get("display.gpu");
        status.update(state(gpu,gpu,"{\"helper_loaded\":true,\"telemetry_valid\":true,\"gpu_errors\":0}"),new Bundle(),gpu.revision); check(status.evidence(backend).enabled);
        status.update(state(gpu,gpu,"{\"helper_loaded\":false,\"telemetry_valid\":true}"),new Bundle(),gpu.revision); check(status.evidence(backend)==null);
        status.update(state(gpu,gpu,"{\"helper_loaded\":true,\"telemetry_valid\":false}"),new Bundle(),gpu.revision); check(status.evidence(backend)==null);
        status.update(state(gpu,gpu,"{\"helper_loaded\":true,\"telemetry_valid\":true,\"gpu_errors\":3}"),new Bundle(),gpu.revision); check(!status.evidence(backend).error.isEmpty());
        status.update(state(gpu,gpu,"").replace("backend_version=test-version","backend_version=wrong"),new Bundle(),gpu.revision); check(status.evidence(backend)==null);
        status.update(state(gpu,gpu,"").replace("applied_boot=abc","applied_boot=old"),new Bundle(),gpu.revision); check(status.evidence(backend)==null);
        status.invalidate("Root拒绝"); check(status.evidence(theme)==null); check(status.evidence(backend)==null);
        System.out.println("runtime status checks passed: " + checks);
    }
}
