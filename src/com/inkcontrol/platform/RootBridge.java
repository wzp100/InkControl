package com.inkcontrol.platform;

import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import com.inkcontrol.core.Integrity;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** 只运行随 APK 签名分发的固定控制器；用户值通过 stdin 传递。 */
final class RootBridge {
    private final Context context;
    RootBridge(Context context) { this.context = context.createDeviceProtectedStorageContext(); }

    File payload() throws Exception {
        File directory = new File(context.getFilesDir(), "controller-" + BuildInfo.BACKEND_SHA256);
        File marker = new File(directory, "verified");
        if (marker.isFile()) return directory;
        File zip = new File(context.getCacheDir(), "inkcontrol_backend.zip");
        try (InputStream input = context.getAssets().open("inkcontrol_backend.zip")) { Files.copy(input, zip.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
        verify(zip);
        directory.mkdirs();
        try (ZipInputStream input = new ZipInputStream(new FileInputStream(zip))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                if (!(entry.getName().startsWith("lib/") || entry.getName().equals("bin/inkctl")) || entry.isDirectory()) continue;
                File target = new File(directory, entry.getName());
                if (!target.getCanonicalPath().startsWith(directory.getCanonicalPath() + File.separator)) throw new SecurityException("ZIP 路径无效");
                target.getParentFile().mkdirs();
                Files.copy(input, target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Files.write(marker.toPath(), new byte[]{1});
        return directory;
    }
    static void verify(File file) throws Exception {
        try (InputStream input = new FileInputStream(file)) {
            Integrity.verifySha256(input, BuildInfo.BACKEND_SHA256);
        }
    }
    String run(String command, String input) throws Exception {
        if (!command.matches("status|apply|applyfont|fontlist|restorefont|sysapp (state|remove|restore)|sysbackup (list|save|restore|delete)")) throw new IllegalArgumentException("命令未注册");
        String controller = new File(payload(), "bin/inkctl").getCanonicalPath();
        if (!controller.matches("[a-zA-Z0-9_./-]+")) throw new SecurityException("控制器路径无效");
        Process process;
        try { process = new ProcessBuilder("su", "-c", "/system/bin/sh " + controller + " " + command).redirectErrorStream(true).start(); }
        catch (IOException error) { throw new IOException("未找到 Root（su 不可用）", error); }
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try (InputStream stream = process.getInputStream()) {
                byte[] buffer = new byte[4096]; int count;
                while ((count = stream.read(buffer)) != -1) { if (result.size() < 262144) result.write(buffer, 0, count); }
            } catch (IOException ignored) { }
        }, "inkctl-output");
        reader.start();
        try (OutputStream stream = process.getOutputStream()) { stream.write(input.getBytes(StandardCharsets.UTF_8)); }
        if (!process.waitFor(45, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new IOException("Root 授权或操作超时"); }
        reader.join(2000);
        String text = new String(result.toByteArray(), StandardCharsets.UTF_8).trim();
        if (process.exitValue() != 0) throw new IOException(text.isEmpty() ? "Root 未授权或控制器不可用" : text);
        return text;
    }
    /** 应用系统字体：独立路径写入（按内容 hash 命名，避免框架按路径缓存不重载），由后端自动全局激活。 */
    String applyFont(String source) throws Exception {
        if (source == null || !source.startsWith("/") || source.length() > 300
                || source.indexOf('\n') >= 0 || source.indexOf('\r') >= 0)
            throw new IllegalArgumentException("字体路径无效");
        return run("applyfont", source + "\n");
    }
    /** 列出目录里的字体（root 读取，绕过 App 存储权限）；返回「文件名\t字节数\t绝对路径」逐行文本。 */
    String listFonts(String directory) throws Exception {
        if (directory == null || !directory.startsWith("/") || directory.length() > 300
                || directory.indexOf('\n') >= 0 || directory.indexOf('\r') >= 0)
            throw new IllegalArgumentException("字体目录无效");
        return run("fontlist", directory + "\n");
    }
    /** 把原厂字体写回槽位并激活。 */
    String restoreFont() throws Exception { return run("restorefont", ""); }

    /**
     * 系统应用的机主级卸载与还原。包名只允许字母数字点下划线（后端还会再校验一次），
     * 其它用户输入一律进不来，因此不会拼出额外命令。
     */
    private static String packageName(String pkg) {
        if (pkg == null || !pkg.matches("[A-Za-z0-9._]{1,200}")) throw new IllegalArgumentException("包名无效");
        return pkg;
    }
    /** 返回后端输出：「state=installed|removed|absent」，已安装时还带 path=… 行。 */
    String sysAppState(String pkg) throws Exception { return run("sysapp state", packageName(pkg) + "\n"); }
    /** pm uninstall --user 0：只从机主列表移除，分区里的 APK 保留，可随时还原。 */
    String sysAppRemove(String pkg) throws Exception { return run("sysapp remove", packageName(pkg) + "\n"); }
    /** pm install-existing --user 0：把系统里已有的包装回机主，权限身份不变。 */
    String sysAppRestore(String pkg) throws Exception { return run("sysapp restore", packageName(pkg) + "\n"); }

    /**
     * 系统设置快照。编号形如 20261103-213500，只允许字母数字下划线和短横线，
     * 后端还会再校验一次——它会被拼进目录路径，不能让 `../` 混进来。
     */
    private static String snapshotId(String id) {
        if (id == null || !id.matches("[0-9A-Za-z_-]{1,40}")) throw new IllegalArgumentException("快照编号无效");
        return id;
    }
    /** 已有快照：每行「编号\t创建时间\t条数\t占用KB」。 */
    String backupList() throws Exception { return run("sysbackup list", ""); }
    /** 拍一张当前的系统设置快照，返回 id= 与 count= 两行。 */
    String backupSave() throws Exception { return run("sysbackup save", ""); }
    /** 逐条写回某个快照；返回 written=/skipped=/failed=/safety= 四行。 */
    String backupRestore(String id) throws Exception { return run("sysbackup restore", snapshotId(id) + "\n"); }
    String backupDelete(String id) throws Exception { return run("sysbackup delete", snapshotId(id) + "\n"); }
}
