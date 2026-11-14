package com.inkcontrol.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import com.inkcontrol.core.FeatureCatalog;
import com.inkcontrol.core.FeatureDefinition;
import com.inkcontrol.core.FeatureService;
import com.inkcontrol.core.DisplayAlgorithms;
import com.inkcontrol.features.desktop.DesktopPreview;
import com.inkcontrol.features.lock.LockPreview;
import com.inkcontrol.features.motion.Geometry;
import com.inkcontrol.platform.ApplicationServices;
import com.inkcontrol.platform.BuildInfo;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/** 「字体」页：目录扫描、搜索、应用与恢复原厂字体。从 MainActivity 拆出，状态与通用控件仍通过宿主 {@code a} 访问。 */
final class FontPages {
    private final MainActivity a;
    FontPages(MainActivity host) { a = host; }

    void fontPage(FeatureDefinition f) {
        ApplicationServices.Snapshot s = a.snapshot;
        String saved = s.config.value("font.dir");
        String dir = saved == null || saved.isEmpty() ? MainActivity.FONT_DIR_DEFAULT : saved;
        String applied = s.config.value("font.pick");

        // 页面每次重渲（应用字体、保存目录等操作都会整页重建）搜索框都是新建的、内容为空，
        // 过滤词若不清空，就会出现「框看着是空的、列表却被上一次的词过滤到一行不剩」的假死，
        // 用户无字可删、清不掉，只能以为字体列表坏了。进入/重建页面一律回到「显示全部」。
        a.fontQuery = "";
        a.fontDir = dir;

        a.body.addView(a.widgets.spaced(a.widgets.paragraph("点「应用」写进系统槽位后，替换全局字体。", 14), 4));

        LinearLayout box = a.widgets.column();
        EditText field = new EditText(a);
        field.setSingleLine(true);
        field.setText(saved == null ? "" : saved);
        field.setHint(MainActivity.FONT_DIR_DEFAULT);
        field.setBackground(a.ink.box(Ink.PAPER, Ink.INK, 1.5f, 8));
        field.setPadding(a.ink.dp(10), a.ink.dp(8), a.ink.dp(10), a.ink.dp(8));
        box.addView(a.widgets.spaced(field, 12));
        box.addView(a.widgets.spaced(a.ink.button("保存并刷新列表", Ink.Kind.PRIMARY, v -> saveFontDir("font.dir", field.getText().toString())), 10));

        LinearLayout actions = new LinearLayout(a);
        actions.addView(a.ink.button("选择目录", Ink.Kind.SECONDARY, v -> pickFontDir()), new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams rightParams = new LinearLayout.LayoutParams(0, -2, 1);
        rightParams.leftMargin = a.ink.dp(10);
        actions.addView(a.ink.button("从文件选择", Ink.Kind.SECONDARY, v -> pickFontFile()), rightParams);
        box.addView(a.widgets.spaced(actions, 12));

        box.addView(a.widgets.spaced(a.ink.button("恢复原厂字体", Ink.Kind.SECONDARY, v -> restoreFont()), 10));
        box.addView(a.widgets.spaced(a.widgets.paragraph("字体是全局替换：状态栏、锁屏、桌面与所有应用一起换字。字体缺少中文字形时部分界面会出现方块，点上面的「恢复原厂字体」即可退回系统默认字体。", 12), 8));
        box.addView(a.widgets.spaced(a.ink.text("默认：" + MainActivity.FONT_DIR_DEFAULT, 12, Ink.MEDIUM), 8));
        if (applied != null && !applied.isEmpty())
            box.addView(a.widgets.spaced(a.ink.text("上次应用：" + applied, 13, Ink.BOLD), 12));
        a.body.addView(a.widgets.spaced(box, 16));

        // 搜索框：只过滤下方列表的显示，不重新扫描目录
        EditText search = new EditText(a);
        search.setSingleLine(true);
        search.setHint("搜索字体名…");
        search.setBackground(a.ink.box(Ink.PAPER, Ink.INK, 1.5f, 8));
        search.setPadding(a.ink.dp(10), a.ink.dp(8), a.ink.dp(10), a.ink.dp(8));
        search.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(android.text.Editable s) {
                a.fontQuery = s == null ? "" : s.toString();
                renderFontList();
            }
        });
        a.body.addView(a.widgets.spaced(search, 12));

        a.fontAppliedMark = applied;
        a.fontHintView = a.ink.text("正在扫描…", 13, Ink.MEDIUM);
        a.body.addView(a.fontHintView);
        a.fontListViews = a.widgets.column();
        a.body.addView(a.widgets.spaced(a.fontListViews, 8));
        loadFonts(dir, applied);
    }

    private void loadFonts(String dir, String applied) {
        MainActivity.WORKER.execute(() -> {
            List<String[]> fonts = new ArrayList<>();
            String failure = "";
            try {
                // 优先走 root 列表：App 没有存储权限时也能读系统字体目录与 TF 卡
                for (String[] font : a.services.listFonts(dir))
                    fonts.add(new String[]{font[0], font[1], fontSize(Long.parseLong(font[2]))});
            } catch (Exception error) {
                fonts = scanFonts(dir);
                if (fonts.isEmpty()) failure = MainActivity.reason(error);
            }
            List<String[]> found = fonts;
            String problem = failure;
            a.runOnUiThread(() -> {
                if (a.isFinishing() || a.isDestroyed()) return;
                a.fontItems = found;
                a.fontProblem = problem;
                a.fontAppliedMark = applied;
                renderFontList();
            });
        });
    }

    /** 按搜索词过滤并重画字体列表；过滤词为空时显示全部。 */
    private void renderFontList() {
        if (a.fontListViews == null || a.fontHintView == null) return;
        a.fontListViews.removeAllViews();
        if (!a.fontProblem.isEmpty()) {
            a.fontHintView.setText("读取目录失败：" + a.fontProblem + "\n可以用「从文件选择」直接挑一个字体文件。");
            a.fontListViews.addView(a.widgets.spaced(reloadFontsButton(), 8));
            return;
        }
        if (a.fontItems.isEmpty()) {
            a.fontHintView.setText("这个目录里没找到字体文件。换一个目录，或用「从文件选择」。");
            a.fontListViews.addView(a.widgets.spaced(reloadFontsButton(), 8));
            return;
        }
        String q = a.fontQuery == null ? "" : a.fontQuery.trim().toLowerCase(Locale.ROOT);
        List<String[]> shown = new ArrayList<>();
        for (String[] font : a.fontItems)
            if (q.isEmpty() || font[1].toLowerCase(Locale.ROOT).contains(q)) shown.add(font);
        if (q.isEmpty()) a.fontHintView.setText("共 " + a.fontItems.size() + " 个字体");
        else if (shown.isEmpty()) a.fontHintView.setText("没有匹配「" + a.fontQuery.trim() + "」的字体，清空搜索框显示全部");
        else a.fontHintView.setText("匹配 " + shown.size() + " / 共 " + a.fontItems.size() + " 个字体");
        for (String[] font : shown) a.fontListViews.addView(a.widgets.spaced(fontRow(font, a.fontAppliedMark), 8));
    }

    /** 列表读取失败或目录为空时的重试入口：按当前目录重新走一遍 root 列表 + 兜底扫描。 */
    private View reloadFontsButton() {
        return a.ink.button("重新加载列表", Ink.Kind.SECONDARY, v -> {
            a.fontHintView.setText("正在扫描…");
            loadFonts(a.fontDir, a.fontAppliedMark);
        });
    }

    /** 扫描目录里的 TTF/OTF（含无扩展名的主题字体包），返回 {绝对路径, 文件名, 大小文本}。 */
    private static List<String[]> scanFonts(String dir) {
        List<String[]> result = new ArrayList<>();
        java.io.File[] files = new java.io.File(dir).listFiles();
        if (files == null) return result;
        Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        for (java.io.File file : files) {
            if (!file.isFile() || file.length() < 65536) continue;
            try (java.io.FileInputStream input = new java.io.FileInputStream(file)) {
                byte[] head = new byte[4];
                if (input.read(head) != 4) continue;
                String tag = String.format(Locale.ROOT, "%02x%02x%02x%02x",
                        head[0] & 0xff, head[1] & 0xff, head[2] & 0xff, head[3] & 0xff);
                if (!tag.equals("00010000") && !tag.equals("4f54544f")
                        && !tag.equals("74727565") && !tag.equals("74746366")) continue;
            } catch (Exception ignored) { continue; }
            result.add(new String[]{file.getAbsolutePath(), file.getName(), fontSize(file.length())});
        }
        return result;
    }

    private static String fontSize(long bytes) {
        return bytes >= 1048576
                ? String.format(Locale.ROOT, "%.1f MB", bytes / 1048576f) : (bytes / 1024) + " KB";
    }

    /** 一个字体一个卡片：左侧名字+大小，「应用」按钮独立一列、在整个卡片内垂直居中。 */
    private View fontRow(String[] font, String applied) {
        boolean current = font[1].equals(applied);
        TextView name = a.ink.text(font[1], 15, Ink.BOLD);
        name.setSingleLine(false);
        TextView size = a.ink.text(font[2] + (current ? " · 上次已应用" : ""), 12, Ink.REGULAR);
        // 紧凑「应用」按钮：高度压到与字号相当
        TextView apply = a.ink.button("应用", Ink.Kind.SECONDARY, v -> applyFontFile(font[0], font[1]));
        apply.setTextSize(13);
        apply.setMinHeight(a.ink.dp(30));
        apply.setPadding(a.ink.dp(14), a.ink.dp(4), a.ink.dp(14), a.ink.dp(4));
        LinearLayout textCol = new LinearLayout(a);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.addView(name);
        textCol.addView(a.widgets.spaced(size, 2));
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(textCol, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams button = new LinearLayout.LayoutParams(-2, -2);
        button.leftMargin = a.ink.dp(8);
        row.addView(apply, button);
        row.setBackground(a.ink.outline(12));
        row.setPadding(a.ink.dp(14), a.ink.dp(10), a.ink.dp(10), a.ink.dp(10));
        return row;
    }

    void saveFontDir(String id, String text) {
        String value = text == null ? "" : text.trim();
        if (!value.isEmpty() && !value.startsWith("/")) {
            Toast.makeText(a, "请填绝对路径，例如 " + MainActivity.FONT_DIR_DEFAULT, Toast.LENGTH_LONG).show();
            return;
        }
        if (FeatureDefinition.path(value) == null) {
            Toast.makeText(a, "路径里不能有等号、分号或换行", Toast.LENGTH_LONG).show();
            return;
        }
        a.perform(a.ink.t("保存字体目录"), () -> {
            a.change(id, true, value);
            return "已保存字体目录：" + (value.isEmpty() ? MainActivity.FONT_DIR_DEFAULT : value);
        }, false);
    }

    private void pickFontDir() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        try { a.startActivityForResult(intent, MainActivity.FONT_DIR_REQUEST); }
        catch (android.content.ActivityNotFoundException error) { a.showText("无法选择目录", "系统没有可用的文件夹选择界面。"); }
    }

    private void pickFontFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);
        try { a.startActivityForResult(intent, MainActivity.FONT_REQUEST); }
        catch (android.content.ActivityNotFoundException error) { a.showText("无法选择文件", "系统没有可用的文件选择界面。"); }
    }

    /** 文件选择器给的 tree URI 转成文件系统路径；转不出来时返回 null（用「从文件选择」兜底）。 */
    static String treePath(android.net.Uri uri) {
        try {
            String id = android.provider.DocumentsContract.getTreeDocumentId(uri);
            int colon = id.indexOf(':');
            if (colon < 0) return null;
            String volume = id.substring(0, colon);
            String rest = id.substring(colon + 1);
            String base;
            if (volume.equalsIgnoreCase("primary")) base = "/storage/emulated/0";
            else if (volume.matches("(?i)[0-9a-f]{4}-[0-9a-f]{4}")) base = "/storage/" + volume;
            else return null;
            return rest.isEmpty() ? base : base + "/" + rest;
        } catch (Exception error) { return null; }
    }

    private void applyFontFile(String path, String name) {
        if (a.busy) { Toast.makeText(a, a.ink.t("请等待当前操作完成"), Toast.LENGTH_SHORT).show(); return; }
        a.perform(a.ink.t("应用字体"), () -> {
            String result = a.services.applyFont(path);
            rememberFont(name);
            return result;
        }, true);
    }

    /** 记住最近应用的字体名；写配置失败不影响字体已经写入的事实。 */
    private void rememberFont(String name) {
        if (FeatureDefinition.path(name) == null) return;
        try { a.change("font.pick", true, name); } catch (Exception ignored) { }
    }

    void applyFontFromUri(android.net.Uri uri) {
        if (a.busy) { Toast.makeText(a, a.ink.t("请等待当前操作完成"), Toast.LENGTH_SHORT).show(); return; }
        a.perform(a.ink.t("应用字体"), () -> {
            java.io.File copy = new java.io.File(a.getCacheDir(), "font-apply.ttf");
            try (java.io.InputStream input = a.getContentResolver().openInputStream(uri);
                 OutputStream output = new java.io.FileOutputStream(copy)) {
                if (input == null) throw new java.io.IOException("无法读取所选文件");
                byte[] buffer = new byte[65536];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            }
            String result = a.services.applyFont(copy.getAbsolutePath());
            rememberFont(fontName(uri));
            return result;
        }, true);
    }

    private static String fontName(android.net.Uri uri) {
        String name = uri.getLastPathSegment();
        if (name == null) return "";
        int slash = name.lastIndexOf('/');
        return slash < 0 ? name : name.substring(slash + 1);
    }

    private void restoreFont() {
        if (a.busy) { Toast.makeText(a, a.ink.t("请等待当前操作完成"), Toast.LENGTH_SHORT).show(); return; }
        a.confirm("恢复原厂字体", "把系统原厂默认字体（Roboto-Medium）重新指向当前槽位——也就是系统「字体样式」里的「默认字体」。写完界面自动刷新（全局界面一起换，包括桌面），不用重启。",
                () -> a.perform(a.ink.t("恢复原厂字体"), () -> a.services.restoreFont(), true));
    }
}
