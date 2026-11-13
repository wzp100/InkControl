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

/** 「关于」页与手动检测更新。从 MainActivity 拆出，状态与通用控件仍通过宿主 {@code a} 访问。 */
final class AboutPages {
    private final MainActivity a;
    AboutPages(MainActivity host) { a = host; }

    /** 只跳浏览器，不在应用内下载或安装任何东西。 */
    void openLink(String url) {
        try {
            a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception failure) {
            Toast.makeText(a, a.ink.t("没有可以打开链接的应用"), Toast.LENGTH_SHORT).show();
        }
    }

    /** 一条外链：名字 + 一行说明。不放头像，图标也不加背景形状。 */
    View linkRow(String name, String detail, String url) {
        LinearLayout row = a.column(a.ink.text(name, 16, Ink.BOLD), a.spaced(a.paragraph(detail, 13), 4));
        row.setPadding(a.ink.dp(2), a.ink.dp(10), a.ink.dp(2), a.ink.dp(10));
        row.setBackground(a.ink.pressable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT),
                a.ink.box(Ink.PAPER, Ink.INK, 2, 10)));
        row.setContentDescription(a.ink.t(name + "，" + detail + "，打开链接"));
        row.setOnClickListener(v -> openLink(url));
        return row;
    }

    /** 一组外链放在同一张卡里，条目之间用细线分隔。 */
    View aboutSection(String title, View... rows) {
        LinearLayout box = a.column(a.ink.text(title, 16, Ink.BOLD));
        for (int i = 0; i < rows.length; i++) {
            if (i > 0) box.addView(a.spaced(a.ink.rule(1), 10));
            box.addView(a.spaced(rows[i], 10));
        }
        return a.spaced(a.card(box), 12);
    }

    void aboutPage() {
        LinearLayout brand = new LinearLayout(a);
        brand.setOrientation(LinearLayout.VERTICAL);
        brand.setGravity(Gravity.CENTER_HORIZONTAL);
        brand.setPadding(0, a.ink.dp(12), 0, a.ink.dp(6));
        try {
            ImageView icon = new ImageView(a);
            icon.setImageDrawable(a.getPackageManager().getApplicationIcon(a.getPackageName()));
            brand.addView(icon, new LinearLayout.LayoutParams(a.ink.dp(56), a.ink.dp(56)));
        } catch (Exception ignored) { /* 取不到图标就只显示名字 */ }
        TextView name = a.ink.text("墨控", 26, Ink.BOLD);
        name.setGravity(Gravity.CENTER);
        brand.addView(a.spaced(name, 10));
        TextView version = a.ink.text("版本 v" + BuildInfo.VERSION, 13, Ink.REGULAR);
        version.setGravity(Gravity.CENTER);
        brand.addView(a.spaced(version, 6));
        a.body.addView(brand);

        LinearLayout primary = new LinearLayout(a);
        primary.setGravity(Gravity.CENTER_VERTICAL);
        primary.addView(a.ink.button("项目仓库", Ink.Kind.SECONDARY, v -> openLink(MainActivity.REPO)),
                new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams docParams = new LinearLayout.LayoutParams(0, -2, 1);
        docParams.leftMargin = a.ink.dp(10);
        primary.addView(a.ink.button("文档", Ink.Kind.SECONDARY, v -> openLink(MainActivity.README_URL)), docParams);
        a.body.addView(a.spaced(primary, 14));

        a.body.addView(a.spaced(a.card(a.column(a.paragraph(
                "海信 A5 Pro 经典版（HLTE203T / Android 10）的墨水屏增强模块。全部增强默认关闭，按需开启。", 14))), 12));

        // 作者、上游与参考项目分开两张卡：作者卡只留自述与来源，
        // 参考项目单独一张，免得和「谁做的」混在一起。
        // 「版本发布」不再放这里：最下面的「查看更新」里已有「打开发布页」。
        a.body.addView(aboutSection("作者与来源",
                linkRow("liuqianhe", "贡献者 · GitHub 主页", MainActivity.AUTHOR_URL),
                linkRow("wzp100/InkControl", "项目主仓库", MainActivity.UPSTREAM_REPO),
                linkRow("nuku", "翻页动画作者 · 酷安主页", MainActivity.COMMUNITY_NUKU)));

        a.body.addView(aboutSection("参考项目",
                linkRow("ChenXu178/Hisense-E-Ink-auto-refresh", "自动刷新能力的参考项目", MainActivity.REFRESH_REFERENCE),
                linkRow("liuqianhe/Hisense-E-Ink-auto-refreshs", "该项目的分支", MainActivity.REFRESH_FORK),
                linkRow("wave-up", "体感相关的参考项目", MainActivity.WAVEUP_REFERENCE)));

        LinearLayout update = a.column(a.ink.text("查看更新", 16, Ink.BOLD));
        update.addView(a.spaced(a.paragraph("当前版本 v" + BuildInfo.VERSION
                + "。只在点按钮时检测一次，不会自动检查、下载或安装。", 13), 8));
        update.addView(a.spaced(a.ink.button("检测更新", Ink.Kind.SECONDARY, v -> checkForUpdate()), 12));
        a.aboutUpdateView = a.ink.text(a.aboutUpdateText.isEmpty() ? "检测结果会显示在这里。" : a.aboutUpdateText,
                14, Ink.REGULAR);
        if (!a.aboutUpdateUrl.isEmpty()) {
            a.aboutUpdateView.setPaintFlags(a.aboutUpdateView.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
            a.aboutUpdateView.setOnClickListener(v -> openLink(a.aboutUpdateUrl));
            a.aboutUpdateView.setClickable(true);
        }
        update.addView(a.spaced(a.aboutUpdateView, 12));
        update.addView(a.spaced(linkRow("打开发布页", "在浏览器里查看全部版本", MainActivity.RELEASES), 12));
        a.body.addView(a.spaced(a.card(update), 12));
        a.body.addView(a.spaced(a.paragraph("第三方二进制与社区作品的权利归原权利人所有，详见仓库 NOTICE.md。", 13), 12));
    }

    /** 手动检测一次最新版本；结果留在字段里，页面重建后仍然可见。 */
    void checkForUpdate() {
        if (a.checkingUpdate) return;
        a.checkingUpdate = true;
        a.aboutUpdateText = "正在检测最新版本…";
        a.aboutUpdateUrl = "";
        if (a.aboutUpdateView != null) {
            a.aboutUpdateView.setText(a.ink.t(a.aboutUpdateText));
            a.aboutUpdateView.setOnClickListener(null);
            a.aboutUpdateView.setClickable(false);
        }
        MainActivity.WORKER.execute(() -> {
            String result, link = "";
            try {
                HttpURLConnection connection = (HttpURLConnection) new URL(MainActivity.LATEST_API).openConnection();
                connection.setConnectTimeout(6000);
                connection.setReadTimeout(6000);
                connection.setRequestMethod("GET");
                connection.setRequestProperty("User-Agent", "InkControl/" + BuildInfo.VERSION);
                connection.setInstanceFollowRedirects(true);
                try {
                    if (connection.getResponseCode() != 200)
                        throw new java.io.IOException("接口返回 " + connection.getResponseCode());
                    java.io.InputStream input = connection.getInputStream();
                    java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
                    byte[] chunk = new byte[4096];
                    int count;
                    while ((count = input.read(chunk)) > 0) buffer.write(chunk, 0, count);
                    input.close();
                    // 读发布页的订阅源而不是 API：API 的匿名调用按出口地址限流（共用出口时常年 403），
                    // 而且 releases/latest 不含预发布。订阅源两个问题都没有，取其中版本号最高的一个。
                    String feed = new String(buffer.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
                    java.util.regex.Matcher tags = java.util.regex.Pattern
                            .compile("/releases/tag/v([0-9][0-9A-Za-z.\\-]{0,40})").matcher(feed);
                    String latest = "";
                    while (tags.find()) {
                        String version = tags.group(1);
                        if (latest.isEmpty() || com.inkcontrol.core.Versions.compare(version, latest) > 0) latest = version;
                    }
                    String page = latest.isEmpty() ? MainActivity.RELEASES : MainActivity.RELEASES + "/tag/v" + latest;
                    if (latest.isEmpty()) throw new java.io.IOException("发布页没有版本标签");
                    // 只按版本号比大小：线上可能还停在旧版本（本机是提前构建的），
                    // 单纯判断「相等/不等」会把旧发布报成新版本。
                    int newer = com.inkcontrol.core.Versions.compare(latest, BuildInfo.VERSION);
                    if (newer == 0) {
                        result = "已是最新版本 v" + BuildInfo.VERSION;
                    } else if (newer > 0) {
                        result = "发现新版本 v" + latest + "（当前 v" + BuildInfo.VERSION + "），点这里打开发布页";
                        link = page;
                    } else {
                        result = "本机 v" + BuildInfo.VERSION + " 比线上发布的 v" + latest + " 更新（未发布或本地构建）";
                    }
                } finally { connection.disconnect(); }
            } catch (Exception failure) {
                result = "检测失败：网络或接口暂不可用，可点下方打开发布页查看";
            }
            final String text = result, url = link;
            a.runOnUiThread(() -> {
                a.checkingUpdate = false;
                a.aboutUpdateText = text; a.aboutUpdateUrl = url;
                if (a.isFinishing() || a.isDestroyed() || a.aboutUpdateView == null) return;
                a.aboutUpdateView.setText(a.ink.t(text));
                if (url.isEmpty()) {
                    a.aboutUpdateView.setOnClickListener(null);
                    a.aboutUpdateView.setClickable(false);
                } else {
                    a.aboutUpdateView.setPaintFlags(a.aboutUpdateView.getPaintFlags()
                            | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
                    a.aboutUpdateView.setOnClickListener(v -> openLink(url));
                    a.aboutUpdateView.setClickable(true);
                }
            });
        });
    }

    // ───────────────────────── 功能列表排序 ─────────────────────────
}
