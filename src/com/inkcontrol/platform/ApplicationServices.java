package com.inkcontrol.platform;

import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import com.inkcontrol.core.*;
import java.io.*;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** 页面只访问这个应用服务；所有权限、IPC、文件与 Root 细节留在平台层。 */
public final class ApplicationServices {
    private final Context context;
    private final AndroidConfigStore store;
    private final RootBridge root;
    private final RuntimeStatus status = new RuntimeStatus();
    private final FeatureService features;
    public ApplicationServices(Context context) {
        this.context = context.getApplicationContext();
        store = new AndroidConfigStore(context); root = new RootBridge(context);
        features = new FeatureService(store, (before, after) -> {
            // 固定控制器同时校验设备和冲突模块。保存期望后失败仍可明确重试。
            root.run("apply", after.text());
        }, status);
    }
    public Configuration configuration() throws Exception { return features.configuration(); }
    /** 页面一次读取的状态；只在后台单线程调用，避免主线程读盘及与 inspect 并发读写运行状态。 */
    public static final class Snapshot {
        public final Configuration config;
        public final Map<String, String> statuses, environment;
        public final java.util.Set<String> hooked;
        public final String failure;
        Snapshot(Configuration config, Map<String, String> statuses, Map<String, String> environment, java.util.Set<String> hooked, String failure) {
            this.config = config; this.statuses = Collections.unmodifiableMap(statuses);
            this.environment = Collections.unmodifiableMap(environment); this.hooked = Collections.unmodifiableSet(hooked); this.failure = failure;
        }
        public boolean checked() { return !environment.isEmpty(); }
        public boolean ready() { return "1".equals(environment.get("ready")); }
    }
    public Snapshot snapshot() throws Exception {
        Configuration config = configuration();
        Map<String, String> statuses = new LinkedHashMap<>();
        for (FeatureDefinition feature : FeatureCatalog.all()) statuses.put(feature.id, featureStatus(feature.id));
        return new Snapshot(config, statuses, new LinkedHashMap<>(status.fields), status.hooked(), status.failure);
    }
    public String featureStatus(String id) throws Exception {
        String result = features.status(id);
        Configuration config = configuration();
        if (config.enabled(id) && !status.failure.isEmpty()) return "等待检查：" + status.failure;
        return result;
    }
    public void change(String id, boolean enabled, String value) throws Exception {
        if (enabled) {
            inspect();
            if (!status.failure.isEmpty()) throw new IllegalStateException(status.failure);
            if (!"1".equals(status.fields.get("ready"))) throw new IllegalStateException("请先完成安装和完整重启；详见诊断页");
        }
        features.change(id, enabled, value);
        inspect();
    }
    /** 批量改取值（面板换顺序）：一次落盘 + 一次应用，别按格逐个来。 */
    public void changeAll(java.util.LinkedHashMap<String, String> values) throws Exception {
        features.changeAll(values);
        inspect();
    }
    /** 把字体写进系统槽位并立刻全局激活（独立路径 + persist.sys.font* + 微扰 font_scale + 重启 SystemUI）；不需要重启系统框架。 */
    public String applyFont(String source) throws Exception { return root.applyFont(source); }
    /** 恢复原厂字体并立刻全局激活。 */
    public String restoreFont() throws Exception { return root.restoreFont(); }
    /** 列出目录里的字体：{绝对路径, 文件名, 字节数}；root 读取，不受 App 存储权限限制。 */
    public java.util.List<String[]> listFonts(String directory) throws Exception {
        java.util.List<String[]> result = new java.util.ArrayList<>();
        for (String line : root.listFonts(directory).split("\n")) {
            String[] parts = line.split("\t");
            if (parts.length < 3 || parts[0].isEmpty()) continue;
            result.add(new String[]{parts[2], parts[0], parts[1]});
        }
        return result;
    }
    public void disableAll() throws Exception { features.disableAll(); }    public void restoreGroup(String group) throws Exception { features.restoreGroup(group); }

    /** 系统应用在机主账号里的状态：installed / removed / absent，附带安装位置。 */
    public String[] sysAppState(String pkg) throws Exception {
        String state = "absent", path = "";
        for (String line : root.sysAppState(pkg).split("\n")) {
            if (line.startsWith("state=")) state = line.substring(6).trim();
            else if (line.startsWith("path=")) path = line.substring(5).trim();
        }
        return new String[]{state, path};
    }
    /** 从机主账号卸载（分区里的原始 APK 保留，可随时还原）。 */
    public String sysAppRemove(String pkg) throws Exception { return root.sysAppRemove(pkg); }
    /** 还原到机主账号，仍是系统应用身份。 */
    public String sysAppRestore(String pkg) throws Exception { return root.sysAppRestore(pkg); }

    /** 已有系统设置快照：{编号, 创建时间(秒), 条数, 占用KB}，最新的排在最前面。 */
    public java.util.List<String[]> backupSnapshots() throws Exception {
        java.util.List<String[]> result = new java.util.ArrayList<>();
        for (String line : root.backupList().split("\n")) {
            String[] parts = line.split("\t");
            if (parts.length < 4 || parts[0].isEmpty()) continue;
            result.add(new String[]{parts[0].trim(), parts[1].trim(), parts[2].trim(), parts[3].trim()});
        }
        // 后端按目录名升序输出，界面上要「最近的在最上面」，所以按创建时间倒序再排一次；
        // 时间戳读不出来（老快照或手改过）就退回用编号比，编号本身就是 yyyyMMdd-HHmmss。
        result.sort((left, right) -> {
            long a = seconds(left[1]), b = seconds(right[1]);
            return a == b ? right[0].compareTo(left[0]) : Long.compare(b, a);
        });
        return result;
    }
    private static long seconds(String text) {
        try { return Long.parseLong(text); } catch (NumberFormatException invalid) { return 0L; }
    }
    /** 拍一张快照；返回 {编号, 条数}。 */
    public String[] backupSave() throws Exception {
        String id = "", count = "0";
        for (String line : root.backupSave().split("\n")) {
            if (line.startsWith("id=")) id = line.substring(3).trim();
            else if (line.startsWith("count=")) count = line.substring(6).trim();
        }
        return new String[]{id, count};
    }
    /** 逐条写回；返回 {写入数, 跳过数, 失败数, 事前快照编号}。 */
    public String[] backupRestore(String id) throws Exception {
        String written = "0", skipped = "0", failed = "0", safety = "";
        for (String line : root.backupRestore(id).split("\n")) {
            if (line.startsWith("written=")) written = line.substring(8).trim();
            else if (line.startsWith("skipped=")) skipped = line.substring(8).trim();
            else if (line.startsWith("failed=")) failed = line.substring(7).trim();
            else if (line.startsWith("safety=")) safety = line.substring(7).trim();
        }
        return new String[]{written, skipped, failed, safety};
    }
    public String backupDelete(String id) throws Exception { return root.backupDelete(id); }
    public String retry() throws Exception { features.retryApply(); return inspect(); }
    public String inspect() {
        try {
            String text = root.run("status", "");
            long revision = configuration().revision;
            status.update(text, reports(), revision);
            // 应用刚起来时钩子还在重连，「0 个作用域」多半只是回执没到；等两轮再判，
            // 仍为空才认定没注入，否则会误报「模块未注入」。
            for (int attempt = 0; attempt < 2 && status.failure.isEmpty() && status.hooked().isEmpty(); attempt++) {
                try { Thread.sleep(900); } catch (InterruptedException stop) { Thread.currentThread().interrupt(); break; }
                status.update(text, reports(), revision);
            }
            return "本机：" + Build.MODEL + " / Android " + Build.VERSION.RELEASE + "\n" + text;
        } catch (Exception error) { status.invalidate(error.getMessage()); return "环境检查未完成：" + error.getMessage() + "\n仍可预览和导出 ZIP。"; }
    }
    private Bundle reports() {
        Bundle value = context.getContentResolver().call(ConfigProvider.URI, "reports", null, null);
        return value == null ? new Bundle() : value;
    }
    public void exportZip(OutputStream output) throws Exception {
        File directory = root.payload();
        File zip = new File(context.createDeviceProtectedStorageContext().getCacheDir(), "inkcontrol_backend.zip");
        // 命中已解压控制器时缓存 ZIP 仍可能被系统清理，重新复制并校验。
        try (InputStream input = context.getAssets().open("inkcontrol_backend.zip"); OutputStream cache = new FileOutputStream(zip)) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) != -1) cache.write(buffer, 0, count);
        }
        RootBridge.verify(zip);
        try (InputStream input = new FileInputStream(zip)) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        }
    }
}
