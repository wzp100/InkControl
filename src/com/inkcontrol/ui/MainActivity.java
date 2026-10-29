package com.inkcontrol.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
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
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 纯原生界面，不接触系统设置、Root 或进程内 Hook 状态。
 * 所有服务调用都在进程级单线程中执行，页面只渲染后台生成的快照。
 * 「功能」分三级：分类 → 分类内功能列表 → 单项详情（说明、开启前后对比、演示与选项）。
 */
public final class MainActivity extends Activity {
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final String FEATURES = "功能", MODULE = "模块设置", INSTALL = "安装", DIAGNOSE = "诊断", ORDER = "大功能排序", TERMS = "__terms";
    private static final String[] TABS = {FEATURES, MODULE};
    private static final int[] TAB_GLYPHS = {Ink.Glyph.FEATURES, Ink.Glyph.INSTALL};
    private static final int EXPORT_REQUEST = 100;
    private static final int FONT_REQUEST = 101;
    private static final int FONT_DIR_REQUEST = 102;
    /** 海信主题字体目录：字体包没有扩展名，靠文件头识别 TTF/OTF。 */
    private static final String FONT_DIR_DEFAULT = "/storage/emulated/0/.hmcttheme/com.hmct.theme/";

    private ApplicationServices services;
    private Ink ink;
    private ApplicationServices.Snapshot snapshot;
    private String loadError = "";
    private String tab = FEATURES, category = "", featureId = "", message = "", output = "";
    /** 「模块设置」内的子页：空=入口页，否则为「安装」/「诊断」。 */
    private String modulePage = "";
    private boolean busy, messageFailed, outputExpanded;
    /** 正在提交的单项更改；执行期间按目标值显示，结束后以新快照为准。 */
    private String pendingId = "", pendingValue = "";
    private boolean pendingEnabled;
    /** 详情页里正在预览、尚未使用的选项；空表示与当前设置相同。 */
    private String preview = "";
    /** 字体页的完整列表、过滤词与渲染控件：搜索框只过滤显示，不重新扫描目录。 */
    private List<String[]> fontItems = new ArrayList<>();
    private String fontProblem = "";
    private String fontAppliedMark = "";
    private String fontQuery = "";
    /** 字体页当前目录：列表读取失败时「重新加载」按它重扫。 */
    private String fontDir = FONT_DIR_DEFAULT;
    private TextView fontHintView;
    private LinearLayout fontListViews;
    private boolean autoplay;
    /** 停留曲线编辑中的草稿。 */
    private String holdMode = "";
    private int[] holdDraft;
    /** 操作结果提示条的自动消失计时；结果条默认停留 30 秒，可在「模块设置」调整。 */
    private final android.os.Handler noticeHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private int noticeSeq;
    /** 缓存「提示时长」偏好，避免每次绘制都同步读一遍存储。 */
    private int noticeSeconds = UiNotice.DEFAULT;
    private final Map<String, Integer> scrollPositions = new HashMap<>();
    private ScrollView scroll;
    private LinearLayout body;
    private MotionDemo mainDemo;
    /** 预览选项后滚动到演示处，让用户立刻看到效果。 */
    private boolean scrollToVisual;
    private View visualView;

    @Override protected void attachBaseContext(android.content.Context base) { super.attachBaseContext(UiLanguage.wrap(base)); }

    interface Work { String run() throws Exception; }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        services = new ApplicationServices(this);
        ink = new Ink(this);
        noticeSeconds = UiNotice.seconds(this);
        if (state != null) {
            tab = state.getString("tab", FEATURES);
            if (!Arrays.asList(TABS).contains(tab)) tab = FEATURES;
            modulePage = state.getString("module", "");
            if (!modulePage.isEmpty() && !modulePage.equals(INSTALL) && !modulePage.equals(DIAGNOSE)) modulePage = "";
            category = state.getString("category", ""); featureId = state.getString("feature", "");
            if (!featureId.isEmpty()) {
                featureId = Guide.destination(featureId);
                category = FeatureCatalog.get(featureId).group;
                // 单项分类（字体）没有分类页：首页点卡片是 navigate("", 单项) 直进功能页，
                // 返回也该直接回大功能列表。进程重建若把 category 恢复成 group=「字体」，
                // 系统返回就会落到一个平时根本不会显示的「字体」分区页，得多按一次返回；
                // 这里对齐首页入口的状态（旧方案多分类时代的遗留，分类砍到只剩一个后暴露）。
                Guide.Category restored = Guide.CATEGORIES.get(category);
                if (restored != null && singleItem(restored) != null) category = "";
            }
        }
        load();
        render();
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("tab", tab); state.putString("category", category); state.putString("feature", featureId);
        state.putString("module", modulePage);
    }
    @Override public void onConfigurationChanged(Configuration configuration) { super.onConfigurationChanged(configuration); render(); }
    @Override protected void onDestroy() { dismissNotice(); super.onDestroy(); }
    @Override public void onBackPressed() {
        if (!featureId.isEmpty()) {
            // 单项分类（字体）没有分类页可回：无论 category 因什么路径被设成它，
            // 系统返回都直接回大功能列表，不落在平时不会显示的「字体」分区页。
            String back = category;
            Guide.Category from = Guide.CATEGORIES.get(back);
            if (from != null && singleItem(from) != null) back = "";
            navigate(back, "");
        }
        else if (!category.isEmpty()) {
            // 实验室 / 名词解释 从「模块设置」进入，返回时回到模块设置入口。
            if (category.equals("实验室") || category.equals(TERMS)) {
                tab = MODULE; category = ""; featureId = ""; modulePage = ""; render();
            } else navigate("", "");
        }
        else if (tab.equals(MODULE) && !modulePage.isEmpty()) { modulePage = ""; render(); }
        else if (!tab.equals(FEATURES)) switchTab(FEATURES);
        else super.onBackPressed();
    }

    /** 首次读取；用户已改过配置时顺带静默检查一次，否则在未授权 Root 的情况下不主动申请。 */
    private void load() {
        busy = true;
        WORKER.execute(() -> {
            ApplicationServices.Snapshot next = null;
            String error = "", text = "";
            try {
                if (services.configuration().revision != 0) text = services.inspect();
                next = services.snapshot();
            } catch (Exception failure) { error = reason(failure); }
            ApplicationServices.Snapshot loaded = next;
            String failure = error, inspected = text;
            runOnUiThread(() -> {
                busy = false;
                if (isFinishing() || isDestroyed()) return;
                snapshot = loaded; loadError = failure; output = inspected;
                render();
            });
        });
    }

    // ───────────────────────── 页面骨架 ─────────────────────────

    private String pageKey() { return tab + "|" + category + "|" + featureId; }

    private void render() {
        if (scroll != null) scrollPositions.put(pageKey(), scroll.getScrollY());
        mainDemo = null; visualView = null;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ink.PAPER);
        root.setFitsSystemWindows(true);
        root.addView(header());
        View bar = messageBar();
        if (bar != null) root.addView(bar);

        scroll = new ScrollView(this);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setScrollbarFadingEnabled(false);
        scroll.setVerticalScrollbarThumbDrawable(ink.solid(2));
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(ink.dp(16), ink.dp(4), ink.dp(16), ink.dp(28));
        scroll.addView(body, new ViewGroup.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(ink.rule(1.5f));
        root.addView(navigation());
        setContentView(root);

        // 墨水屏偶发「点了没画」：点击触发的快速刷新可能吞掉换页后的第一次画面更新，
        // 内容其实已经画好、但要再滑动一下才出现。布局完成后紧接着补一次重绘请求，
        // 让屏幕驱动再收到一帧，进大功能后无需再动一下就能看到新页面。
        View repaint = root;
        repaint.postDelayed(() -> {
            if (!isFinishing() && !isDestroyed()) { repaint.invalidate(); repaint.requestLayout(); }
        }, 80);

        if (snapshot == null && loadError.isEmpty()) body.addView(note("正在读取配置…"));
        else {
            // 配置读取失败也要能进「模块设置」（导出 ZIP、诊断都靠它）：先给错误卡，再照常渲染页面。
            // 此前出错时整页只剩错误卡，「模块设置」/「功能」全都不渲染，用户连安装包都导不出来。
            if (!loadError.isEmpty())
                body.addView(card(column(heading("配置读取失败", 18),
                        paragraph("原文件已保留，未做任何修改。\n" + loadError, 14))));
            if (tab.equals(MODULE)) {
                if (modulePage.equals(INSTALL)) installation();
                else if (modulePage.equals(DIAGNOSE)) diagnostics();
                else if (modulePage.equals(ORDER)) categoryOrderPage();
                else moduleSettings();
            } else if (!loadError.isEmpty()) {
                body.addView(spaced(paragraph("出错了先从这里处理：进「诊断」查看详情，或在「安装」重新导出 Magisk 模块 ZIP。", 14), 12));
                moduleSettings();
            }
            else if (category.equals(TERMS)) glossary();
            else if (!featureId.isEmpty()) featurePage(FeatureCatalog.get(featureId));
            else if (!category.isEmpty()) categoryPage(Guide.CATEGORIES.get(category));
            else home();
        }

        // 在首次绘制前恢复滚动位置；post 会先画顶部再跳回，墨水屏上多一次整屏刷新。
        Integer saved = scrollPositions.get(pageKey());
        View anchor = scrollToVisual ? visualView : null;
        scrollToVisual = false;
        if (anchor != null || saved != null && saved > 0) {
            ScrollView target = scroll;
            int y = saved == null ? 0 : saved;
            target.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
                @Override public boolean onPreDraw() {
                    target.getViewTreeObserver().removeOnPreDrawListener(this);
                    target.scrollTo(0, anchor != null ? Math.max(0, anchor.getTop() - ink.dp(8)) : y);
                    return true;
                }
            });
        }
        if (autoplay && mainDemo != null) { MotionDemo demo = mainDemo; demo.post(demo::play); }
        autoplay = false;
    }

    private View header() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(ink.dp(20), ink.dp(14), ink.dp(16), ink.dp(10));
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        String title, subtitle, back = "";
        boolean isHome = tab.equals(FEATURES) && category.isEmpty() && featureId.isEmpty();
        // 单页分类（字体）会跳过分类页直接进功能页，此时 category 为空，仍算子页
        boolean sub = tab.equals(FEATURES) && !isHome;
        if (sub && category.equals(TERMS)) { title = "名词解释"; subtitle = "界面里出现的专业词是什么意思。"; back = "模块设置"; }
        else if (sub && !featureId.isEmpty()) {
            FeatureDefinition f = FeatureCatalog.get(featureId);
            title = f.title; subtitle = Guide.get(f.id).summary.isEmpty() ? f.description : Guide.get(f.id).summary;
            back = category.isEmpty() ? "功能" : category;
        } else if (sub) { title = category; subtitle = Guide.CATEGORIES.get(category).intro; back = category.equals("实验室") ? "模块设置" : "功能"; }
        else { title = "墨控"; subtitle = ""; }

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        if (!back.isEmpty()) {
            String target = back;
            // 返回按钮放大到 18sp：此前 15sp 在墨水屏上偏小，点按区域不好找。
            TextView backView = ink.text("‹ " + target, 18, Ink.MEDIUM);
            backView.setPadding(0, ink.dp(4), ink.dp(16), ink.dp(10));
            backView.setContentDescription(ink.t("返回 " + target));
            backView.setOnClickListener(v -> onBackPressed());
            titles.addView(backView);
        }
        if (isHome) {
            // 标题「墨控」右侧隔开一段距离显示版本号（原来在副标题里，附带的
            // 「墨水屏增强 · 全部默认关闭」说明已删除——62 项默认关闭写入 README 即可）。
            LinearLayout brand = new LinearLayout(this);
            brand.setGravity(Gravity.CENTER_VERTICAL);
            brand.addView(ink.text(title, 30, Ink.BOLD));
            TextView version = ink.text("v" + BuildInfo.VERSION, 13, Ink.REGULAR);
            version.setPadding(ink.dp(12), ink.dp(2), 0, 0);
            version.setContentDescription(ink.t("版本 " + BuildInfo.VERSION));
            brand.addView(version);
            titles.addView(brand);
        } else {
            titles.addView(ink.text(title, sub ? 26 : 30, Ink.BOLD));
        }
        row.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));

        if (isHome) {
            String label;
            boolean filled = false;
            if (snapshot == null) label = busy ? "读取中" : "未读取";
            else if (!snapshot.checked()) label = snapshot.failure.equals("尚未检查环境") ? "未检查" : "环境异常";
            else if (snapshot.ready()) { label = "● 就绪"; filled = true; }
            else label = "未就绪";
            TextView badge = ink.text(label, 13, Ink.MEDIUM);
            badge.setPadding(ink.dp(12), ink.dp(7), ink.dp(12), ink.dp(7));
            badge.setBackground(filled ? ink.solid(16) : ink.outline(16));
            badge.setTextColor(filled ? Ink.PAPER : Ink.INK);
            badge.setContentDescription(ink.t("环境状态：" + label));
            badge.setOnClickListener(v -> openModule(DIAGNOSE));
            row.addView(badge);
        }
        box.addView(row);

        if (isHome) {
            // 状态概览：合并为一行放在标题正下方，缩小占位（原为大号数字卡片）。
            // 「待重启」「异常」为 0 时不显示，给小屏省出空间。
            View stats = headerStats();
            if (stats != null) box.addView(spaced(stats, 8));
        } else if (!subtitle.isEmpty()) {
            // Keep the brand/title wide enough on 320dp screens in both languages.
            LinearLayout details = new LinearLayout(this);
            details.setGravity(Gravity.CENTER_VERTICAL);
            details.addView(ink.text(subtitle, 13, Ink.REGULAR), new LinearLayout.LayoutParams(0, -2, 1));
            box.addView(spaced(details, 6));
        }
        return box;
    }

    /** 字体条目是页内操作（目录、字体列表、应用），没有开关，也不计入开启数量。 */
    private static boolean isFontNode(String id) { return id.startsWith("font."); }

    /**
     * 没有开关、点进去只做修改的页内操作项：字体（挑字体、应用）与「显示信息修改」
     * （把设备状态的四项显示开关收进一页）。它们不计入「已开启 / 总数」。
     */
    private static boolean isPageNode(String id) { return isFontNode(id) || id.equals("lock.ext.edit"); }

    /** 「显示信息修改」页里的四项显示开关，顺序即锁屏上从左到右的顺序。 */
    private static final String[] EXT_ITEMS = {"lock.ext.wifi", "lock.ext.bt", "lock.ext.audio", "lock.ext.airplane"};

    /** 分类里只有一个「页内操作」项时返回它（字体），调用方直接打开那一页。 */
    private static String singleItem(Guide.Category c) {
        String only = "";
        int count = 0;
        for (String[] section : c.sections) for (int i = 1; i < section.length; i++) { count++; only = section[i]; }
        return count == 1 && isFontNode(only) ? only : null;
    }

    /** 标题下方的单行状态：N 已开启 / 总数，待重启与异常仅在非 0 时出现；异常项可点击跳转。 */
    private View headerStats() {
        if (snapshot == null) return null;
        int on = 0, pending = 0, faults = 0, total = 0;
        String firstFault = "";
        for (FeatureDefinition feature : FeatureCatalog.all()) {
            // 字体与「显示信息修改」都是页内操作，没有开关，不计入「已开启 / 总数」
            if (isPageNode(feature.id)) continue;
            total++;
            String status = status(feature.id);
            if (snapshot.config.requested(feature.id)) on++;
            if (status.equals("待重启")) pending++;
            if (status.startsWith("异常")) {
                faults++;
                if (firstFault.isEmpty()) firstFault = feature.id;
            }
        }
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(statInline(String.valueOf(on), "已开启 / " + total));
        if (pending > 0) row.addView(statInline(String.valueOf(pending), "待重启"));
        if (faults > 0) {
            View fault = statInline(String.valueOf(faults), "异常");
            final String target = firstFault;
            fault.setContentDescription(ink.t("打开异常项"));
            fault.setBackground(ink.pressable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT),
                    ink.box(Ink.PAPER, Ink.INK, 1, 10)));
            fault.setPadding(ink.dp(4), ink.dp(2), ink.dp(4), ink.dp(2));
            fault.setOnClickListener(v -> openFeature(target));
            row.addView(fault);
        }
        return row;
    }

    /** 单个状态项：数字与标签同行，比原 stat() 卡片紧凑。 */
    private View statInline(String number, String label) {
        LinearLayout box = new LinearLayout(this);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(0, 0, ink.dp(20), 0);
        box.addView(ink.text(number, 18, Ink.BOLD));
        TextView text = ink.text(label, 12, Ink.REGULAR);
        text.setPadding(ink.dp(5), 0, 0, 0);
        box.addView(text);
        return box;
    }

    /** 最近一次操作的结果；不再每次弹窗，失败和页面级操作才弹出完整内容。 */
    private View messageBar() {
        if (message.isEmpty() && !busy) return null;
        // 「不显示」时不占用界面空间，直接不画这条提示。
        if (!busy && noticeSeconds <= 0) return null;
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(ink.dp(16), 0, ink.dp(16), ink.dp(8));
        bar.setLayoutParams(params);
        bar.setPadding(ink.dp(14), ink.dp(10), ink.dp(6), ink.dp(10));
        boolean inverted = messageFailed && !busy;
        bar.setBackground(inverted ? ink.solid(10) : ink.outline(10));
        String shown = busy ? "处理中，请稍候…" : (messageFailed ? "✕ " : "✓ ") + message;
        TextView text = ink.text(shown, 14, Ink.REGULAR);
        text.setTextColor(inverted ? Ink.PAPER : Ink.INK);
        text.setMaxLines(2);
        text.setEllipsize(TextUtils.TruncateAt.END);
        text.setContentDescription(ink.t("操作结果"));
        bar.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
        if (!busy) {
            TextView close = ink.text("×", 22, Ink.REGULAR);
            close.setTextColor(inverted ? Ink.PAPER : Ink.INK);
            close.setGravity(Gravity.CENTER);
            close.setContentDescription(ink.t("关闭提示"));
            close.setOnClickListener(v -> { dismissNotice(); message = ""; render(); });
            bar.addView(close, new LinearLayout.LayoutParams(ink.dp(40), ink.dp(36)));
            String full = message;
            bar.setOnClickListener(v -> showText(messageFailed ? "未完成" : "操作结果", full));
        }
        return bar;
    }

    /**
     * 结果提示条到期后自动消失。失败提示必须一直留着由用户手动关闭，
     * 所以失败时不启动计时；「不显示」时同样不计时。
     */
    private void scheduleNoticeDismiss() {
        noticeHandler.removeCallbacksAndMessages(null);
        noticeSeq++;
        if (messageFailed || busy) return;
        int seconds = noticeSeconds;
        if (seconds <= 0) return;
        final int token = noticeSeq;
        noticeHandler.postDelayed(() -> {
            if (token != noticeSeq) return;   // 期间又有了新的操作结果，不清空
            message = "";
            render();
        }, seconds * 1000L);
    }

    /** 立刻清掉待触发的自动消失计时（关闭按钮、页面切换时用）。 */
    private void dismissNotice() {
        noticeHandler.removeCallbacksAndMessages(null);
        noticeSeq++;
    }

    private View navigation() {
        LinearLayout nav = new LinearLayout(this);
        nav.setPadding(ink.dp(8), ink.dp(6), ink.dp(8), ink.dp(6));
        for (int i = 0; i < TABS.length; i++) {
            String title = TABS[i];
            boolean selected = title.equals(tab);
            int color = selected ? Ink.PAPER : Ink.INK;
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER);
            item.setPadding(0, ink.dp(6), 0, ink.dp(6));
            if (selected) item.setBackground(ink.solid(14));
            ImageView icon = new ImageView(this);
            icon.setImageDrawable(new Ink.Glyph(ink, TAB_GLYPHS[i], color));
            item.addView(icon, new LinearLayout.LayoutParams(ink.dp(24), ink.dp(24)));
            TextView label = ink.text(title, 13, selected ? Ink.BOLD : Ink.REGULAR);
            label.setTextColor(color);
            label.setGravity(Gravity.CENTER);
            label.setPadding(0, ink.dp(4), 0, 0);
            item.addView(label);
            item.setContentDescription(ink.t(title + (selected ? "，当前页" : "")));
            item.setOnClickListener(v -> switchTab(title));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ink.dp(58), 1);
            params.setMargins(ink.dp(4), 0, ink.dp(4), 0);
            nav.addView(item, params);
        }
        return nav;
    }

    private void switchTab(String next) {
        if (next.equals(tab)) {
            // 再点一次「功能」回到分类首页；「模块设置」回到它的入口页。
            if (next.equals(FEATURES) && !category.isEmpty()) navigate("", "");
            else if (next.equals(MODULE) && !modulePage.isEmpty()) { modulePage = ""; render(); }
            else if (scroll != null) scroll.scrollTo(0, 0);
            return;
        }
        if (scroll != null) scrollPositions.put(pageKey(), scroll.getScrollY());
        tab = next;
        scroll = null;
        render();
    }

    /** 从功能页跳进「模块设置」的指定子页（安装/诊断）。 */
    private void openModule(String page) {
        modulePage = page;
        if (tab.equals(MODULE)) render();
        else switchTab(MODULE);
    }
    private void navigate(String nextCategory, String nextFeature) {
        if (scroll != null) scrollPositions.put(pageKey(), scroll.getScrollY());
        if (!nextFeature.equals(featureId)) { preview = ""; holdMode = ""; holdDraft = null; }
        // 进入新的下一级页面从顶部开始；返回上一级时保留原位置。
        if (!nextFeature.isEmpty()) scrollPositions.remove(tab + "|" + nextCategory + "|" + nextFeature);
        category = nextCategory; featureId = nextFeature;
        scroll = null;
        render();
    }
    private void openFeature(String id) {
        String destination = Guide.destination(id);
        navigate(FeatureCatalog.get(destination).group, destination);
    }

    // ───────────────────────── 功能：分类首页 ─────────────────────────

    /** 供同包的 UiLanguage / UiNotice 在改完偏好后刷新当前页面。 */
    void refreshUi() {
        noticeSeconds = UiNotice.seconds(this);
        // 改时长本身是一次新动作：把上一条结果清掉，免得旧提示又冒出来。
        if (!busy) { dismissNotice(); message = ""; }
        render();
    }

    private void home() {
        ApplicationServices.Snapshot s = snapshot;
        // 状态概览（已开启/待重启/异常）已合并到标题下方的一行（headerStats）。
        if (!s.ready()) {
            LinearLayout overview = column(spaced(ink.rule(1), 14));
            String hint = s.checked() ? "环境未就绪：开启增强前请按「模块设置 → 安装」完成安装并完整重启。"
                    : s.failure.equals("尚未检查环境") ? "尚未检查环境。开启任何增强前，先到「模块设置 → 安装」完成检查与安装。"
                    : "环境检查未完成：" + s.failure + "。开启增强前请先到「模块设置 → 安装」处理。";
            overview.addView(spaced(paragraph(hint, 14), 12));
            overview.addView(spaced(ink.button("前往安装向导", Ink.Kind.SECONDARY, v -> openModule(INSTALL)), 12));
            body.addView(card(overview));
        }

        for (Guide.Category c : orderedCategories()) {
            if (c.name.equals("实验室")) continue;   // 实验室入口移到「模块设置」
            int total = 0, enabled = 0, bad = 0;
            for (String[] section : c.sections) for (int i = 1; i < section.length; i++) {
                if (isPageNode(section[i])) continue;   // 字体、显示信息修改：页内操作，没有开关
                total++;
                if (s.config.requested(section[i])) enabled++;
                if (status(section[i]).startsWith("异常")) bad++;
            }
            // 收在子页里的开关（设备状态那四项）也要算进来，否则「锁屏」的已开启数会少四格
            for (String id : c.nested) {
                total++;
                if (s.config.requested(id)) enabled++;
                if (status(id).startsWith("异常")) bad++;
            }
            // 整类都是页内操作时（字体），不显示「0/0 已开启」，点卡片直接进那一页
            String single = singleItem(c);
            View.OnClickListener open = v -> {
                if (single != null) navigate("", single); else navigate(c.name, "");
            };
            body.addView(spaced(total == 0 ? categoryCard(c, -1, -1, 0, open)
                    : categoryCard(c, enabled, total, bad, open), 12));
        }

        body.addView(spaced(ink.button("检查当前生效状态", Ink.Kind.PRIMARY, v -> perform("检查生效状态", () -> services.inspect(), false)), 28));
        body.addView(spaced(ink.button("关闭全部增强", Ink.Kind.DANGER, v -> confirm("关闭全部增强", "保留各项选择，并恢复墨控接管的行为。启动期覆盖需完整重启后才完全恢复。",
                () -> perform("关闭全部增强", () -> { services.disableAll(); return "已提交关闭；请完整重启并检查。"; }, true))), 12));
    }

    /**
     * 分类入口卡：把「(已开启/总数)」并进标题行右侧，省掉单独一行的统计文字，
     * 让整张卡更矮；分类简介仍单独一行，异常项数跟在统计后面。
     */
    private View categoryCard(Guide.Category c, int enabled, int total, int bad, View.OnClickListener click) {
        LinearLayout card = new LinearLayout(this);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(ink.dp(18), ink.dp(16), ink.dp(14), ink.dp(16));
        card.setBackground(ink.pressable(ink.outline(14), ink.box(Ink.PAPER, Ink.INK, 3, 14)));
        LinearLayout content = column();
        // 标题行：分类名 + 「| (5/10)已开启」小字并排，不再占用额外的一行高度。
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.addView(ink.text(c.name, 20, Ink.BOLD));
        if (total > 0) {
            TextView count = ink.text("| (" + enabled + "/" + total + ")已开启", 13, enabled > 0 ? Ink.BOLD : Ink.REGULAR);
            count.setPadding(ink.dp(10), 0, 0, 0);
            titleRow.addView(count);
        }
        if (bad > 0) {
            TextView fault = ink.text(bad + " 项异常", 13, Ink.BOLD);
            fault.setPadding(ink.dp(10), 0, 0, 0);
            titleRow.addView(fault);
        }
        content.addView(titleRow);
        content.addView(spaced(paragraph(c.intro, 13), 6));
        card.addView(content, new LinearLayout.LayoutParams(0, -2, 1));
        card.addView(withMargin(ink.text("›", 28, Ink.REGULAR), 12));
        card.setContentDescription(ink.t("分类 " + c.name));
        card.setOnClickListener(click);
        return card;
    }

    private View navCard(String title, String text, String meta, boolean strong, String description, View.OnClickListener click) {
        return navCard(title, text, meta, strong, description, click, true);
    }

    /**
     * {@code framed} 为假时这张卡片自己不画外框：它被放进带 {@code outline(12)} 描边的分组列表里，
     * 再叠一层圆角 14 的框会在四角错开、直边叠成 4px，看着像「方框里套了个扁圆框」。
     * 采用与普通功能行同一套处理：常态透明露出分组描边，按下时加粗框（圆角 12 与外框一致）。
     */
    private View navCard(String title, String text, String meta, boolean strong, String description,
                         View.OnClickListener click, boolean framed) {
        LinearLayout card = new LinearLayout(this);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(ink.dp(18), ink.dp(16), ink.dp(14), ink.dp(16));
        card.setBackground(framed
                ? ink.pressable(ink.outline(14), ink.box(Ink.PAPER, Ink.INK, 3, 14))
                : ink.pressable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT),
                        ink.box(Ink.PAPER, Ink.INK, 2, 12)));
        LinearLayout content = column(ink.text(title, 20, Ink.BOLD), spaced(paragraph(text, 13), 6));
        if (!meta.isEmpty()) content.addView(spaced(ink.text(meta, 13, strong ? Ink.BOLD : Ink.REGULAR), 8));
        card.addView(content, new LinearLayout.LayoutParams(0, -2, 1));
        card.addView(withMargin(ink.text("›", 28, Ink.REGULAR), 12));
        card.setContentDescription(ink.t(description));
        card.setOnClickListener(click);
        return card;
    }

    private void glossary() {
        for (Map.Entry<String, String> term : Guide.TERMS.entrySet()) body.addView(spaced(termView(term.getKey(), term.getValue()), 16));
    }
    private View termView(String term, String text) {
        return column(ink.text(term, 16, Ink.BOLD), spaced(paragraph(text, 14), 4));
    }

    // ───────────────────────── 功能：分类页 ─────────────────────────

    // ───────────────────────── 系统应用卸载 / 还原 ─────────────────────────

    /** 首页分类名；这一页是独立界面，不走功能开关列表（sections 为空，渲染在此特判）。 */
    private static final String SYSAPP_CATEGORY = "系统应用卸载/还原";
    /** 预置的系统应用：{包名, 显示名}，顺序即页面顺序。 */
    private static final String[][] SYS_APPS = {
            {"com.hmct.updater", "系统更新"},
            {"com.tencent.android.location", "腾讯位置服务"},
    };
    /** 包名 → installed / removed / absent；没有条目表示还没读到。 */
    private final Map<String, String> sysAppStates = new java.util.HashMap<>();
    private String sysAppPageKey = "";

    private void sysAppPage() {
        String key = pageKey();
        if (!key.equals(sysAppPageKey)) {
            // 每次重新进入这一页都重读一遍，避免看到离开前的旧状态
            sysAppPageKey = key;
            sysAppStates.clear();
            body.postDelayed(() -> { if (!busy) perform("读取卸载状态", this::readSysAppStates, false); }, 60);
        }
        // 页头已经有分类名与 intro，这里只补一句「卸载到底做了什么」，不再重复标题
        body.addView(card(column(paragraph("「卸载」只把应用从机主账号里移除，系统分区里的原始文件仍然保留，随时可以原样装回；装回后仍是系统应用身份，特权权限不会丢。", 14))));
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setBackground(ink.outline(12));
        for (int i = 0; i < SYS_APPS.length; i++) {
            if (i > 0) list.addView(ink.rule(1));
            list.addView(sysAppRow(SYS_APPS[i][0], SYS_APPS[i][1]));
        }
        body.addView(spaced(list, 18));
        body.addView(spaced(paragraph("「腾讯位置服务」是系统预置的定位组件，卸载后依赖它的应用可能拿不到网络定位；需要时点「还原」即可。", 13), 10));
        body.addView(spaced(ink.button("重新读取状态", Ink.Kind.SECONDARY,
                v -> perform("读取卸载状态", this::readSysAppStates, false)), 14));
    }

    private View sysAppRow(String pkg, String name) {
        String state = sysAppStates.get(pkg);
        boolean installed = "installed".equals(state);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(ink.dp(16), ink.dp(14), ink.dp(12), ink.dp(14));
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.addView(ink.text(name, 17, Ink.MEDIUM));
        content.addView(spaced(paragraph(pkg, 12), 4));
        content.addView(spaced(ink.text("状态：" + sysAppStateLabel(state), 13, installed ? Ink.BOLD : Ink.REGULAR), 5));
        row.addView(content, new LinearLayout.LayoutParams(0, -2, 1));
        TextView action = installed
                ? ink.button("卸载", Ink.Kind.DANGER, v -> confirm("卸载「" + name + "」",
                        "只从机主账号里移除，系统分区里的原始文件不动；以后在任何时候点「还原」都能原样装回。", () ->
                                perform("卸载「" + name + "」", () -> sysAppChange(pkg, name, true), false)))
                : ink.button("还原", Ink.Kind.SECONDARY, v ->
                        perform("还原「" + name + "」", () -> sysAppChange(pkg, name, false), false));
        row.addView(action);
        return row;
    }

    /** 执行卸载或还原，并把新状态记下来；返回值直接作为顶部提示条。 */
    private String sysAppChange(String pkg, String name, boolean remove) throws Exception {
        if (remove) services.sysAppRemove(pkg); else services.sysAppRestore(pkg);
        String state = services.sysAppState(pkg)[0];
        sysAppStates.put(pkg, state);
        return name + "：" + sysAppStateLabel(state);
    }

    /** 一次读完两个包的状态，返回给提示条；perform 成功后会自动重画页面。 */
    private String readSysAppStates() throws Exception {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < SYS_APPS.length; i++) {
            String state;
            try { state = services.sysAppState(SYS_APPS[i][0])[0]; } catch (Exception error) { state = ""; }
            if (!state.isEmpty()) sysAppStates.put(SYS_APPS[i][0], state);
            if (i > 0) out.append('\n');
            out.append(SYS_APPS[i][1]).append('：').append(sysAppStateLabel(state));
        }
        return out.toString();
    }

    private static String sysAppStateLabel(String state) {
        if ("installed".equals(state)) return "已安装";
        if ("removed".equals(state)) return "已卸载";
        if ("absent".equals(state)) return "设备上没有这个应用";
        return "未读取";
    }

    private void categoryPage(Guide.Category c) {
        if (SYSAPP_CATEGORY.equals(c.name)) { sysAppPage(); return; }
        for (String[] section : c.sections) {
            // 分区小标题：原来 14sp 在墨水屏上几乎和正文一样大，层级看不出来。
            // 放大到 21sp（页面标题是 26sp）并保持加粗，形成「页标题 > 分区 > 功能名(17sp) > 说明(13sp)」的层级。
            TextView head = ink.text(section[0], 21, Ink.BOLD);
            head.setPadding(ink.dp(4), ink.dp(22), 0, ink.dp(10));
            body.addView(head);
            LinearLayout list = new LinearLayout(this);
            list.setOrientation(LinearLayout.VERTICAL);
            list.setBackground(ink.outline(12));
            for (int i = 1; i < section.length; i++) {
                if (i > 1) list.addView(ink.rule(1));
                list.addView(featureRow(FeatureCatalog.get(section[i])));
            }
            body.addView(list);
        }
        if (c.name.equals("动画") || c.name.equals("锁屏")) body.addView(spaced(paragraph("点进每一项可以看到动画演示或效果图。", 13), 16));
        if (c.name.equals("桌面")) body.addView(spaced(ink.button("还原默认设置", Ink.Kind.SECONDARY,
                v -> confirm("还原默认设置", "只把桌面主题、图标、字体、壁纸和组件恢复到默认，不改动桌面布局与图标位置。",
                        () -> perform("还原桌面设置", () -> { services.restoreGroup("桌面"); return "已提交还原；请回到桌面确认。"; }, true))), 20));
    }

    private View featureRow(FeatureDefinition feature) { return featureRow(feature, true); }

    /** {@code links} 为假时只留开关、不显示「›」也不可点（用于收在子页里的行）。 */
    private View featureRow(FeatureDefinition feature, boolean links) {
        if (DisplayAlgorithms.algorithmFeature(feature.id)) {
            String scope = DisplayAlgorithms.scope(feature.id);
            String chosen = snapshot.config.requested(feature.id) ? snapshot.config.value(feature.id) : "default";
            return navCard(DisplayAlgorithms.name(scope), Guide.get(feature.id).summary,
                    Guide.optionName(feature, chosen) + " · " + Guide.selectionStatus(!chosen.equals("default"), status(feature.id)), snapshot.config.requested(feature.id),
                    feature.title + "，打开算法与迟滞设置", v -> openFeature(feature.id), false);
        }
        // 字体是页内操作项，不带开关，直接进页面挑字体
        if (isFontNode(feature.id)) {
            String applied = snapshot.config.value("font.pick");
            String saved = snapshot.config.value("font.dir");
            String meta = applied == null || applied.isEmpty() ? "未换过字体" : "上次应用 " + applied;
            if (saved != null && !saved.isEmpty() && !saved.equals(FONT_DIR_DEFAULT)) meta += " · 自定义目录";
            return navCard(feature.title, Guide.get(feature.id).summary, meta, false,
                    feature.title + "，打开字体设置", v -> openFeature(feature.id), false);
        }
        // 「显示信息修改」同样是页内操作项：自己没有开关，进去逐项开关
        if (feature.id.equals("lock.ext.edit")) {
            int shown = 0;
            for (String id : EXT_ITEMS) if (snapshot.config.enabled(id)) shown++;
            String layout = Guide.optionName(FeatureCatalog.get("lock.ext"), snapshot.config.value("lock.ext"));
            String meta = shown == 0 ? "四项都不显示" : shown + " / " + EXT_ITEMS.length + " 项 · " + layout;
            return navCard(feature.title, Guide.get(feature.id).summary, meta, shown > 0,
                    feature.title + "，选择显示哪几项", v -> openFeature(feature.id), false);
        }
        ApplicationServices.Snapshot s = snapshot;
        boolean requested = s.config.requested(feature.id);
        boolean pending = busy && feature.id.equals(pendingId);
        boolean shown = pending ? pendingEnabled : requested;
        boolean child = !feature.dependency.isEmpty();

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(ink.dp(child ? 12 : 16), ink.dp(14), ink.dp(8), ink.dp(14));
        // 常态透明，露出外层列表的描边；按下时加粗框反馈。
        row.setBackground(ink.pressable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT), ink.box(Ink.PAPER, Ink.INK, 2, 12)));
        if (child) {
            View bar = new View(this);
            bar.setBackgroundColor(Ink.INK);
            LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(ink.dp(3), -1);
            barParams.setMargins(0, ink.dp(2), ink.dp(12), ink.dp(2));
            row.addView(bar, barParams);
        }
        String status = pending ? "正在保存" : status(feature.id);
        boolean strong = status.equals("已生效") || status.startsWith("异常") || status.equals("待重启");
        boolean rebootPending = status.equals("待重启");
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.addView(titleLine(feature, 17, rebootPending));
        String summary = Guide.get(feature.id).summary;
        content.addView(spaced(paragraph(summary.isEmpty() ? feature.description : summary, 13), 5));
        if (child && shown && links && !s.config.enabled(feature.dependency))
            content.addView(spaced(ink.text("需先开启「" + FeatureCatalog.get(feature.dependency).title + "」", 13, Ink.MEDIUM), 5));
        String value = valueSummary(feature, pending ? pendingValue : s.config.value(feature.id), shown);
        content.addView(spaced(ink.text(glyph(status) + " " + status + (value.isEmpty() ? "" : " · " + value), 13, strong ? Ink.BOLD : Ink.REGULAR), 7));
        row.addView(content, new LinearLayout.LayoutParams(0, -2, 1));

        CompoundButton toggle = toggle(feature, shown, requested);
        LinearLayout.LayoutParams switchParams = new LinearLayout.LayoutParams(-2, -2);
        switchParams.setMargins(ink.dp(10), 0, 0, 0);
        row.addView(toggle, switchParams);
        if (links) {
            row.addView(withMargin(ink.text("›", 24, Ink.REGULAR), 8));
            row.setContentDescription(ink.t(feature.title + "，打开详情"));
            row.setOnClickListener(v -> openFeature(feature.id));
        } else {
            // 收在子页里的行：只留开关，点行不跳页（避免返回时跳出「收纳」回到分类页）
            row.setPadding(ink.dp(child ? 12 : 16), ink.dp(12), ink.dp(16), ink.dp(12));
            row.setContentDescription(ink.t(feature.title));
        }
        return row;
    }

    /** 列表里显示的当前选择；只有一个选项的功能不显示。 */
    private String valueSummary(FeatureDefinition f, String value, boolean on) {
        if (f.id.equals("motion.hold")) return on ? holdSummary(value) : "温度自适应";
        if (f.id.equals("refresh.apps")) {
            String mode = FeatureDefinition.filterWhitelist(value) ? "白名单" : "黑名单";
            int count = appList(value).size();
            return count == 0 ? mode : mode + " · " + count + " 个应用";
        }
        List<String> real = realValues(f);
        if (real.size() <= 1) return "";
        return Guide.optionName(f, value);
    }
    private static List<String> realValues(FeatureDefinition f) {
        List<String> result = new ArrayList<>(f.values);
        result.remove(FeatureDefinition.CURVE_TOKEN);
        result.remove(FeatureDefinition.NUMBER_TOKEN);
        result.remove(FeatureDefinition.APPS_TOKEN);
        result.remove(FeatureDefinition.PATH_TOKEN);
        result.remove(FeatureDefinition.FONT_TOKEN);
        return result;
    }

    private LinearLayout titleLine(FeatureDefinition feature, float sp, boolean rebootPending) {
        LinearLayout line = new LinearLayout(this);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.addView(ink.text(feature.title, sp, Ink.MEDIUM));
        if (feature.experimental) line.addView(withMargin(ink.tag("实验", false), 8));
        // 只有回执还没追上配置时才标「需重启」；已生效的 REBOOT 功能不再常驻标签
        if (rebootPending) line.addView(withMargin(ink.tag("需重启", false), 6));
        return line;
    }

    /** 不让 CompoundButton 自行翻转：只有提交成功的更改才改变显示状态。 */
    private CompoundButton toggle(FeatureDefinition feature, boolean shown, boolean requested) {
        CompoundButton toggle = new CompoundButton(this) {
            @Override public void toggle() { }
            @Override public CharSequence getAccessibilityClassName() { return Switch.class.getName(); }
        };
        toggle.setButtonDrawable(ink.switchDrawable());
        toggle.setChecked(shown);
        toggle.setContentDescription(ink.t(feature.title));
        toggle.setOnClickListener(v -> {
            boolean next = !requested;
            String value = snapshot.config.value(feature.id);
            if (!begin(feature.id, next, value)) return;
            perform((next ? "开启" : "关闭") + "「" + feature.title + "」", () -> {
                change(feature.id, next, value);
                return "已保存「" + feature.title + "」：" + (next ? "开启" : "关闭");
            }, false);
        });
        return toggle;
    }

    // ───────────────────────── 功能：详情页 ─────────────────────────

    private void featurePage(FeatureDefinition f) {
        if (DisplayAlgorithms.algorithmFeature(f.id)) { displayAlgorithmPage(f); return; }
        // 字体是「页内操作」，没有开关：目录、字体列表、选择器、恢复与刷新全在一页
        if (isFontNode(f.id)) { fontPage(f); return; }
        // 「显示信息修改」：四项显示开关收在一页，本页自己没有开关
        if (f.id.equals("lock.ext.edit")) { extItemsPage(f); return; }
        ApplicationServices.Snapshot s = snapshot;
        Guide.Entry g = Guide.get(f.id);
        boolean requested = s.config.requested(f.id);
        boolean pending = busy && f.id.equals(pendingId);
        boolean shown = pending ? pendingEnabled : requested;
        String current = pending ? pendingValue : s.config.value(f.id);
        String selected = preview.isEmpty() ? current : preview;

        // 1. 状态
        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        String status = pending ? "正在保存" : status(f.id);
        LinearLayout statusTitle = new LinearLayout(this);
        statusTitle.setGravity(Gravity.CENTER_VERTICAL);
        statusTitle.addView(ink.text(shown ? "已开启" : "未开启", 18, Ink.BOLD));
        if (f.experimental) statusTitle.addView(withMargin(ink.tag("实验", false), 8));
        if (f.mode == FeatureDefinition.ApplyMode.REBOOT && status.equals("待重启")) statusTitle.addView(withMargin(ink.tag("需重启", false), 6));
        LinearLayout statusText = column(statusTitle, spaced(ink.text(glyph(status) + " " + status, 14, Ink.REGULAR), 6));
        if (f.mode == FeatureDefinition.ApplyMode.REBOOT) statusText.addView(spaced(paragraph("↻ 更改后需要完整重启才生效。", 13), 4));
        statusRow.addView(statusText, new LinearLayout.LayoutParams(0, -2, 1));
        if (g.visual != Guide.Visual.HOLD_CURVE) statusRow.addView(toggle(f, shown, requested));
        LinearLayout statusCard = column(statusRow);
        if (!f.dependency.isEmpty() && !s.config.enabled(f.dependency)) dependencyHint(statusCard, f.dependency, "这一项属于「" + FeatureCatalog.get(f.dependency).title + "」，要先开启它才会生效。");
        if (FeatureService.needsWaveBackend(f.id, selected) && !s.config.enabled("display.gpu") && !f.dependency.equals("display.gpu"))
            dependencyHint(statusCard, "display.gpu", "所选效果是波形动画，需要先开启「GPU 显示后端」并完整重启。");
        body.addView(card(statusCard));

        // 2. 名单编辑器（模式 + 应用），放在说明之前
        if (f.id.equals("refresh.apps")) appsFilterEditor(f, requested, current);
        // 2b. 倒数日事项编辑器：合并到「锁屏倒数日」页内，数据仍写 lock.countdowns
        if (f.id.equals("lock.countdown")) {
            String stored = busy && "lock.countdowns".equals(pendingId) ? pendingValue : s.config.value("lock.countdowns");
            countdownEditor(FeatureCatalog.get("lock.countdowns"), stored);
        }
        // 2c. 字体页自己处理全部内容（目录 / 列表 / 选择器 / 恢复 / 刷新），这里不再插编辑器
        // 3. 说明
        if (!g.what.isEmpty()) body.addView(spaced(section("它做什么", paragraph(g.what, 15)), 20));
        // 4. 演示 / 示意图
        // 锁屏样式的效果大图例外：那一页真正的「选项」是带缩略图的样式列表，
        // 大图放在列表上面会先占一屏、滚过它才看得到能点的项。按用户要求挪到列表之后（见 5b）。
        View visual = visual(f, g, selected);
        boolean visualDeferred = visual != null && g.visual == Guide.Visual.LOCK_STYLE;
        if (visual != null && !visualDeferred) {
            body.addView(spaced(visual, 16));
            visualView = visual;
            // 预览了别的选项时，在演示下方直接给出“使用”，不必再滚到列表底部。
            if (g.visual != Guide.Visual.HOLD_CURVE && g.visual != Guide.Visual.BARS && realValues(f).size() > 1 && !selected.equals(current))
                body.addView(spaced(useButton(f, requested, selected), 12));
        }
        // 4. 开启前后
        if (!g.before.isEmpty() || !g.after.isEmpty()) body.addView(spaced(beforeAfter(g), 20));
        // 5. 选项
        if (g.visual == Guide.Visual.HOLD_CURVE) holdEditor(f, requested, current);
        else if (realValues(f).size() > 1) options(f, g, requested, current, selected);
        // 5b. 锁屏样式的大预览：挪到选项列表下方，选完往上对一眼效果
        if (visualDeferred) {
            body.addView(spaced(visual, 16));
            visualView = visual;
            if (realValues(f).size() > 1 && !selected.equals(current))
                body.addView(spaced(useButton(f, requested, selected), 12));
        }
        // 5c. 「显示设备状态」页给出前往「显示信息修改」的入口：四项显示开关收在那一页
        if (f.id.equals("lock.ext"))
            body.addView(spaced(ink.button("选择显示哪几项", Ink.Kind.SECONDARY, v -> openFeature("lock.ext.edit")), 14));
        // 6. 注意
        if (!g.cost.isEmpty() || !g.note.isEmpty()) {
            LinearLayout notes = column();
            if (!g.cost.isEmpty()) notes.addView(paragraph(g.cost, 14));
            if (!g.note.isEmpty()) notes.addView(spaced(ink.text("⚠ " + g.note, 14, Ink.MEDIUM), g.cost.isEmpty() ? 0 : 10));
            body.addView(spaced(section("需要知道", notes), 20));
        }
        // 7. 名词
        if (!g.terms.isEmpty()) {
            LinearLayout terms = column();
            for (int i = 0; i < g.terms.size(); i++) terms.addView(spaced(termView(g.terms.get(i)[0], g.terms.get(i)[1]), i == 0 ? 0 : 14));
            body.addView(spaced(section("名词解释", terms), 20));
        }
    }

    /**
     * 「显示信息修改」：把设备状态条的四项显示开关收进一页。
     * 本页自己没有开关（值是 {@code @panel}），改的都是 lock.ext.*。
     */
    private void extItemsPage(FeatureDefinition f) {
        ApplicationServices.Snapshot s = snapshot;
        Guide.Entry g = Guide.get(f.id);
        int shown = 0;
        for (String id : EXT_ITEMS) if (s.config.enabled(id)) shown++;
        String layout = Guide.optionName(FeatureCatalog.get("lock.ext"), s.config.value("lock.ext"));
        LinearLayout state = column(heading(shown + " / " + EXT_ITEMS.length + " 项显示 · " + layout, 18),
                spaced(paragraph(shown == 0 ? "四项都关着，锁屏底部那块位置留空。"
                        : "锁屏底部那块位置按这四项从左到右排，关掉的项不占位，其余各项自动均分宽度。", 14), 6));
        // 总开关关着时四项都画不出来：这页只留提示和前往入口，开关仍可先设好
        if (!s.config.enabled("lock.ext")) dependencyHint(state, "lock.ext", "「显示设备状态」没开时这四项都不显示，开关照常保存。");
        body.addView(card(state));

        if (!g.what.isEmpty()) body.addView(spaced(section("它做什么", paragraph(g.what, 15)), 20));

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setBackground(ink.outline(12));
        for (int i = 0; i < EXT_ITEMS.length; i++) {
            if (i > 0) list.addView(ink.rule(1));
            // 收在页里的行只留开关：点行不跳页，免得返回时跳出「收纳」回到分类列表
            list.addView(featureRow(FeatureCatalog.get(EXT_ITEMS[i]), false));
        }
        body.addView(spaced(section("显示哪几项", list), 16));
        body.addView(spaced(ink.button("画成图标版还是文字版（当前：" + layout + "）", Ink.Kind.SECONDARY,
                v -> openFeature("lock.ext")), 14));

        if (!g.cost.isEmpty()) body.addView(spaced(section("需要知道", paragraph(g.cost, 14)), 20));
    }

    /** 算法页直接选值，保持现状即撤下本页覆盖；不改旧视频、锁屏细线或独立迟滞。 */
    private void displayAlgorithmPage(FeatureDefinition feature) {
        String scope = DisplayAlgorithms.scope(feature.id);
        com.inkcontrol.core.Configuration config = snapshot.config;
        Guide.Entry guide = Guide.get(feature.id);
        boolean pending = busy && pendingId.equals(feature.id);
        boolean requested = pending ? pendingEnabled : config.requested(feature.id);
        String selected = requested ? (pending ? pendingValue : config.value(feature.id)) : "default";
        LinearLayout state = column(heading("算法选择 · " + Guide.optionName(feature, selected), 18),
                spaced(paragraph(pending ? "正在保存" : "算法状态：" + Guide.selectionStatus(!selected.equals("default"), status(feature.id)), 14), 6),
                spaced(paragraph("保持现状时：" + displayBaseline(scope), 14), 8));
        if (!config.enabled("display.gpu")) dependencyHint(state, "display.gpu", "GPU 显示后端未开启，当前沿用系统显示路径；已保存的选择仍会保留。");
        body.addView(card(state));
        body.addView(spaced(paragraph(guide.what, 15), 16));
        LinearLayout options = column();
        for (String value : feature.values) {
            Guide.Option option = guide.options.get(value);
            LinearLayout choice = column(ink.text((value.equals(selected) ? "● " : "○ ") + option.name, 17, Ink.BOLD),
                    spaced(paragraph(option.detail, 14), 6));
            choice.setPadding(ink.dp(16), ink.dp(14), ink.dp(16), ink.dp(14));
            choice.setBackground(ink.box(Ink.PAPER, Ink.INK, value.equals(selected) ? 2.5f : 1, 12));
            choice.setContentDescription(ink.t("算法 " + option.name + (value.equals(selected) ? "，已选择" : "")));
            choice.setOnClickListener(v -> {
                if (!busy && value.equals(selected)) return;
                saveDisplayChoice(feature.id, value, option.name);
            });
            options.addView(spaced(choice, 10));
        }
        body.addView(spaced(section("本模式算法", options), 16));
        if (!DisplayAlgorithms.gray(scope)) displayHysteresis(scope);
        else body.addView(spaced(paragraph("16级灰阶算法没有独立迟滞选项。保持现状会保留锁屏细线处理；明确选择算法会覆盖本模式的灰阶量化。", 14), 18));
        body.addView(spaced(section("需要知道", paragraph(guide.cost, 14)), 20));
        if (scope.equals("video")) body.addView(spaced(paragraph("旧实验室的视频选择继续保存在配置中，本页选「保持现状」即可沿用。新算法选择优先；恢复默认不会清除旧选择。", 14), 12));
    }

    private String displayBaseline(String scope) {
        if (DisplayAlgorithms.gray(scope)) return "沿用原有16级灰阶处理，由原分支决定是否需要抖动；需要抖动且符合锁屏细线条件时，仍沿用「消除锁屏细线」的点量化。该项当前"
                + (snapshot.config.enabled("lock.dither") ? "已开启。" : "未开启。" );
        FeatureDefinition feature = FeatureCatalog.get("display.alg." + scope);
        return Guide.optionName(feature, DisplayAlgorithms.baselineAlgorithm(snapshot.config, scope))
                + (scope.equals("video") && snapshot.config.enabled("experiment.video") ? "（沿用旧视频选择）。" : "。");
    }

    private void displayHysteresis(String scope) {
        com.inkcontrol.core.Configuration config = snapshot.config;
        if (!config.enabled("display.gpu")) {
            body.addView(spaced(section("独立迟滞", paragraph("开启 GPU 显示后端后，可按当前算法设置。视频默认开启，快速和极速默认关闭。", 14)), 20));
            return;
        }
        // 算法提交期间保持原页开关静止，快照更新后才按新算法改变可用性。
        if (!DisplayAlgorithms.supportsHysteresis(config, scope)) {
            body.addView(spaced(section("历史处理", paragraph("原厂误差扩散有内部历史处理，不能使用独立迟滞开关。已保存的独立迟滞选择会保留，切回 Bayer、蓝噪声或固定阈值时继续使用。", 14)), 20));
            return;
        }
        String id = "display.hyst." + scope;
        boolean pending = busy && pendingId.equals(id);
        boolean current = DisplayAlgorithms.hysteresis(config, scope);
        String policy = pending ? pendingValue : DisplayAlgorithms.hysteresisOverride(config, scope);
        boolean shown = pending ? policy.equals("on") || policy.equals("default") && scope.equals("video") : current;
        LinearLayout row = column(ink.text("独立迟滞", 18, Ink.BOLD),
                spaced(paragraph("减少静止区域闪点，可能增加拖尾。与算法选择独立保存。", 14), 6));
        CompoundButton toggle = new CompoundButton(this) {
            @Override public void toggle() { }
            @Override public CharSequence getAccessibilityClassName() { return Switch.class.getName(); }
        };
        toggle.setButtonDrawable(ink.switchDrawable());
        toggle.setChecked(shown);
        toggle.setContentDescription(ink.t(DisplayAlgorithms.name(scope) + "独立迟滞"));
        toggle.setOnClickListener(v -> saveDisplayChoice(id, current ? "off" : "on", current ? "关闭迟滞" : "开启迟滞"));
        row.addView(spaced(toggle, 12));
        row.addView(spaced(paragraph((policy.equals("default") ? "跟随默认：" : "单独设置：") + (shown ? "开启" : "关闭")
                + " · " + (pending ? "正在保存" : "迟滞状态：" + Guide.selectionStatus(!policy.equals("default"), status(id))), 14), 8));
        if (!policy.equals("default")) row.addView(spaced(ink.button("恢复默认（" + (scope.equals("video") ? "开启" : "关闭") + "）", Ink.Kind.SECONDARY,
                v -> saveDisplayChoice(id, "default", "跟随默认迟滞")), 12));
        body.addView(spaced(card(row), 20));
    }

    private void saveDisplayChoice(String id, String value, String label) {
        boolean enabled = !value.equals("default");
        if (!begin(id, enabled, value)) return;
        perform("保存「" + label + "」", () -> {
            change(id, enabled, value);
            return "已保存「" + FeatureCatalog.get(id).title + "」：" + label;
        }, false);
    }

    private void dependencyHint(LinearLayout card, String dependency, String text) {
        card.addView(spaced(ink.rule(1), 14));
        card.addView(spaced(paragraph(text, 14), 12));
        card.addView(spaced(ink.button("前往「" + FeatureCatalog.get(dependency).title + "」", Ink.Kind.SECONDARY, v -> openFeature(dependency)), 10));
    }

    private View section(String title, View content) {
        return column(ink.text(title, 18, Ink.BOLD), spaced(content, 8));
    }

    private View beforeAfter(Guide.Entry g) {
        LinearLayout box = column(ink.text("开启前后", 18, Ink.BOLD));
        if (!g.before.isEmpty()) box.addView(spaced(compare("开启前", g.before, false), 10));
        if (!g.after.isEmpty()) box.addView(spaced(compare("开启后", g.after, true), 8));
        return box;
    }
    private View compare(String label, String text, boolean after) {
        LinearLayout row = new LinearLayout(this);
        row.setPadding(ink.dp(14), ink.dp(12), ink.dp(14), ink.dp(12));
        row.setBackground(ink.box(Ink.PAPER, Ink.INK, after ? 2.5f : 1, 10));
        TextView tag = ink.tag(label, after);
        row.addView(tag);
        TextView content = paragraph(text, 14);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
        params.leftMargin = ink.dp(12);
        row.addView(content, params);
        return row;
    }

    // ── 演示与示意图 ──

    private int width() { return getResources().getDisplayMetrics().widthPixels - ink.dp(32); }

    private View visual(FeatureDefinition f, Guide.Entry g, String value) {
        ApplicationServices.Snapshot s = snapshot;
        switch (g.visual) {
            case MOTION_SCENE: {
                String scene = f.id.substring("motion.".length());
                MotionDemo demo = demo(scene, value, steps(), origin(), direction(), spread());
                return demoBox("效果演示", demo, timing(value));
            }
            case MOTION_STEPS: {
                String style = s.config.value("motion.app_open");
                if (!style.equals("kwipe")) style = "kiris";
                return demoBox("效果演示（打开应用 · " + Guide.optionName(FeatureCatalog.get("motion.app_open"), style) + "）",
                        demo("app_open", style, Integer.parseInt(value), origin(), direction(), spread()), "共 " + (Integer.parseInt(value) - 1) + " 张中间画面。");
            }
            case MOTION_ORIGIN:
                return demoBox("效果演示（方形展开）", demo("app_open", "kiris", 3, value, direction(), spread()), "");
            case MOTION_DIRECTION:
                return demoBox("效果演示（应用内前进 · 关键帧擦除）", demo("in_app_forward", "kwipe", 3, origin(), value, spread()), "");
            case MOTION_SPREAD:
                return demoBox("效果演示（墨迹晕开）", demo("app_open", "ink", steps(), origin(), direction(), Integer.parseInt(value)),
                        "实际全程约 " + seconds(Integer.parseInt(value) * 11.65 + 117) + " 秒。");
            case LOCK_STYLE: {
                int frameWidth = Math.min(ink.dp(240), width());
                View phone = phone(LockPreview.create(this, Integer.parseInt(value), snapshot.config), frameWidth);
                return framed("效果图 · " + Guide.optionName(f, value), phone, "图中为样例数据，实际显示你的时间、天气和音乐。");
            }
            case DESKTOP_THEME: {
                String kind = s.config.value("desktop.widget");
                return framed("效果图 · " + Guide.optionName(f, value), widget(value, kind), "图为主屏组件（" + Guide.optionName(FeatureCatalog.get("desktop.widget"), kind) + "），图标与字体同一风格。");
            }
            case DESKTOP_WIDGET:
                return framed("效果图 · " + Guide.optionName(f, value), widget(s.config.value("desktop.theme"), value), "使用当前选择的主题「" + Guide.optionName(FeatureCatalog.get("desktop.theme"), s.config.value("desktop.theme")) + "」。");
            case REFRESH_MODES: {
                Map<String, String> marks = new HashMap<>();
                if (f.id.equals("display.apps")) { marks.put("gld", "原来"); marks.put("a2", "开启后"); }
                else marks.put(value.equals("9") ? "a2" : value.equals("2") ? "gc" : "gld", "所选");
                return framed("三种刷新方式", new Charts.RefreshModes(this, ink, marks), "条越长越慢；清晰全刷的实心部分表示先闪黑。");
            }
            case BARS:
                return framed(g.barsCaption, new Charts.Bars(this, ink, g.bars), "");
            default:
                return null;
        }
    }

    private MotionDemo demo(String scene, String style, int steps, String origin, String direction, int spread) {
        MotionDemo demo = new MotionDemo(this, ink, false).configure(scene, style, steps, origin, direction, spread);
        mainDemo = demo;
        return demo;
    }
    private View demoBox(String title, MotionDemo demo, String caption) {
        int w = Math.min(ink.dp(220), width() - ink.dp(40));
        FrameLayout frame = new FrameLayout(this);
        frame.setPadding(ink.dp(5), ink.dp(5), ink.dp(5), ink.dp(5));
        frame.setBackground(ink.box(Ink.PAPER, Ink.INK, 2.5f, 18));
        frame.addView(demo, new FrameLayout.LayoutParams(-1, -2));
        LinearLayout box = column(ink.text(title, 18, Ink.BOLD));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(w + ink.dp(10), -2);
        params.gravity = Gravity.CENTER_HORIZONTAL;
        params.topMargin = ink.dp(12);
        box.addView(frame, params);
        box.addView(spaced(paragraph("点按画面播放一次。演示把时间放慢，方便看清过程。" + (caption.isEmpty() ? "" : "\n" + caption), 13), 10));
        return box;
    }
    private View framed(String title, View content, String caption) {
        LinearLayout box = column(ink.text(title, 18, Ink.BOLD));
        box.addView(spaced(content, 12));
        if (!caption.isEmpty()) box.addView(spaced(paragraph(caption, 13), 10));
        return box;
    }
    private View phone(View screen, int frameWidth) {
        FrameLayout frame = new FrameLayout(this);
        frame.setPadding(ink.dp(6), ink.dp(6), ink.dp(6), ink.dp(6));
        frame.setBackground(ink.box(Ink.PAPER, Ink.INK, 2.5f, 22));
        // 屏幕内容按机身内圆角裁切，避免直角露出外框。
        screen.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View view, android.graphics.Outline outline) { outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), ink.px(16)); }
        });
        screen.setClipToOutline(true);
        frame.addView(screen, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(frameWidth, (frameWidth - ink.dp(12)) * 2 + ink.dp(12));
        params.gravity = Gravity.CENTER_HORIZONTAL;
        frame.setLayoutParams(params);
        return frame;
    }
    private View widget(String theme, String kind) {
        FrameLayout frame = new FrameLayout(this);
        frame.setPadding(ink.dp(2), ink.dp(2), ink.dp(2), ink.dp(2));
        frame.setBackground(ink.outline(12));
        View content;
        try { content = DesktopPreview.create(this, theme, kind); }
        catch (RuntimeException error) { content = paragraph("预览素材不可用：" + reason(error), 13); }
        frame.addView(content, new FrameLayout.LayoutParams(-1, -1));
        // 组件设计稿 632×432。
        frame.setLayoutParams(new LinearLayout.LayoutParams(-1, Math.round(width() * 432f / 632f)));
        return frame;
    }

    /** 当前设置下的动画参数；子项未开启时取运行端的默认值。 */
    private int steps() { return snapshot.config.enabled("motion.steps") ? Integer.parseInt(snapshot.config.value("motion.steps")) : 2; }
    private String origin() { return snapshot.config.enabled("motion.origin") ? snapshot.config.value("motion.origin") : "touch"; }
    private String direction() { return snapshot.config.enabled("motion.direction") ? snapshot.config.value("motion.direction") : "auto"; }
    private int spread() { return snapshot.config.enabled("motion.spread") ? Integer.parseInt(snapshot.config.value("motion.spread")) : 12; }
    private String timing(String style) {
        if (style.equals("kiris") || style.equals("kwipe")) {
            int hold = currentHold();
            return "按当前设置，实际约 " + seconds((steps() - 1) * hold + 117) + " 秒（" + (steps() - 1) + " 张中间画面，每张停留 " + hold + " 毫秒）。";
        }
        if (style.equals("gcsweep")) return "与原厂全刷用时相同，约 0.9 秒。";
        return "按当前设置，实际约 " + seconds(spread() * 11.65 + 117) + " 秒，仍在一次刷新内完成。";
    }
    private static String seconds(double ms) { return String.format(Locale.ROOT, "%.2f", ms / 1000.0); }

    // ── 选项卡片 ──

    private void options(FeatureDefinition f, Guide.Entry g, boolean requested, String current, String selected) {
        boolean thumbs = g.visual == Guide.Visual.MOTION_SCENE || g.visual == Guide.Visual.LOCK_STYLE;
        LinearLayout list = column(ink.text(g.visual == Guide.Visual.MOTION_SCENE ? "选择动画" : "选择", 18, Ink.BOLD),
                spaced(paragraph("点按卡片先" + (g.visual == Guide.Visual.NONE || g.visual == Guide.Visual.BARS ? "查看说明" : "预览效果") + "，满意后再点下方按钮使用。", 13), 6));
        for (String value : realValues(f)) {
            Guide.Option o = g.options.get(value);
            String name = Guide.optionName(f, value), detail = o == null ? "" : o.detail;
            boolean isCurrent = value.equals(current), isSelected = value.equals(selected);
            LinearLayout card = new LinearLayout(this);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setPadding(ink.dp(12), ink.dp(12), ink.dp(14), ink.dp(12));
            card.setBackground(ink.box(Ink.PAPER, Ink.INK, isSelected ? 3 : 1.5f, 12));
            if (thumbs) {
                View thumb;
                if (g.visual == Guide.Visual.LOCK_STYLE) thumb = LockPreview.create(this, Integer.parseInt(value), snapshot.config);
                else thumb = new MotionDemo(this, ink, true).configure(f.id.substring(7), value, steps(), origin(), direction(), spread());
                FrameLayout frame = new FrameLayout(this);
                frame.setPadding(ink.dp(2), ink.dp(2), ink.dp(2), ink.dp(2));
                frame.setBackground(ink.box(Ink.PAPER, Ink.INK, 1.5f, 6));
                frame.addView(thumb, new FrameLayout.LayoutParams(-1, g.visual == Guide.Visual.LOCK_STYLE ? ink.dp(116) : -2));
                LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(ink.dp(62), -2);
                tp.rightMargin = ink.dp(14);
                card.addView(frame, tp);
            }
            LinearLayout content = column(ink.text(name, 16, isSelected ? Ink.BOLD : Ink.MEDIUM));
            if (!detail.isEmpty()) content.addView(spaced(paragraph(detail, 13), 4));
            if (FeatureService.needsWaveBackend(f.id, value) && f.id.startsWith("motion."))
                content.addView(spaced(ink.text("需要 GPU 显示后端，并关闭「改用原厂显示算法」", 12, Ink.REGULAR), 4));
            if (isCurrent || isSelected) {
                LinearLayout tags = new LinearLayout(this);
                if (isCurrent) tags.addView(ink.tag(requested ? "✓ 正在使用" : "已选（未开启）", true));
                if (isSelected && !isCurrent) tags.addView(ink.tag("预览中", false));
                content.addView(spaced(tags, 6));
            }
            card.addView(content, new LinearLayout.LayoutParams(0, -2, 1));
            card.setContentDescription(ink.t("选项 " + name + (isCurrent ? "，正在使用" : "") + (isSelected && !isCurrent ? "，预览中" : "")));
            card.setOnClickListener(v -> {
                if (value.equals(selected)) { if (mainDemo != null) mainDemo.play(); return; }
                preview = value.equals(current) ? "" : value;
                autoplay = true; scrollToVisual = true;
                render();
            });
            list.addView(spaced(card, 10));
        }
        if (f.acceptsNumber()) list.addView(spaced(customNumberCard(f, requested, current, selected), 10));
        if (!selected.equals(current) || !requested) list.addView(spaced(useButton(f, requested, selected), 14));
        body.addView(spaced(list, 22));
    }

    /** 手动输入数值的卡片；当前值不在预设档位时高亮显示。 */
    private View customNumberCard(FeatureDefinition f, boolean requested, String current, String selected) {
        boolean previewing = FeatureDefinition.number(selected) != null && !f.values.contains(selected);
        String shown = previewing ? selected : current;
        boolean highlighted = FeatureDefinition.number(shown) != null && !f.values.contains(shown);
        LinearLayout card = new LinearLayout(this);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(ink.dp(12), ink.dp(12), ink.dp(14), ink.dp(12));
        card.setBackground(ink.box(Ink.PAPER, Ink.INK, highlighted ? 3 : 1.5f, 12));
        LinearLayout content = column(ink.text("自定义", 16, highlighted ? Ink.BOLD : Ink.MEDIUM),
                spaced(paragraph(highlighted ? "当前：" + shown + Guide.numberUnit(f) : "点按后手动输入数值。", 13), 4));
        if (highlighted) content.addView(spaced(ink.tag(requested ? "✓ 正在使用" : "已选（未开启）", true), 6));
        card.addView(content, new LinearLayout.LayoutParams(0, -2, 1));
        card.setContentDescription(ink.t("自定义"));
        card.setOnClickListener(v -> customNumberDialog(f, requested, highlighted ? shown : ""));
        return card;
    }

    private void customNumberDialog(FeatureDefinition f, boolean requested, String initial) {
        EditText input = new EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        input.setText(initial);
        input.setHint(ink.t("输入数值"));
        input.setSelection(input.getText().length());
        new AlertDialog.Builder(this)
                .setTitle(ink.t("自定义") + " · " + ink.t(f.title))
                .setView(input)
                .setNegativeButton(ink.t("取消"), null)
                .setPositiveButton(ink.t("使用"), (dialog, which) -> {
                    String value = input.getText().toString().trim();
                    if (FeatureDefinition.number(value) == null) {
                        Toast.makeText(this, ink.t("请输入 1–999 之间的整数"), Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (!begin(f.id, true, value)) return;
                    perform(ink.t("使用"), () -> {
                        change(f.id, true, value);
                        return "已保存「" + f.title + "」：" + value + Guide.numberUnit(f);
                    }, false);
                    preview = "";
                }).show();
    }

    private View useButton(FeatureDefinition f, boolean requested, String selected) {
        String name = Guide.optionName(f, selected);
        String label = requested ? "使用「" + name + "」" : "开启并使用「" + name + "」";
        return ink.button(label, Ink.Kind.PRIMARY, v -> {
            if (!begin(f.id, true, selected)) return;
            perform("使用「" + name + "」", () -> {
                change(f.id, true, selected);
                return "已保存「" + f.title + "」：" + name;
            }, false);
            preview = "";
        });
    }

    // ── 应用名单：黑白名单 ──

    private static List<String> appList(String value) {
        List<String> result = new ArrayList<>();
        String list = FeatureDefinition.filterList(value);
        if (list.isEmpty()) return result;
        for (String part : list.split(",")) if (!part.isEmpty()) result.add(part);
        return result;
    }

    private void appsFilterEditor(FeatureDefinition f, boolean requested, String current) {
        boolean white = FeatureDefinition.filterWhitelist(current);
        List<String> chosen = appList(current);
        LinearLayout box = column(ink.text("名单模式", 18, Ink.BOLD),
                spaced(modeCard(f, requested, current, true, "白名单", "只有名单内应用会触发刷新", white), 10),
                spaced(modeCard(f, requested, current, false, "黑名单", "名单内应用会被跳过，其余照常", !white), 10),
                spaced(ink.text((white ? "白名单" : "黑名单") + " · " + (chosen.isEmpty() ? "尚未选择应用" : "已加入 " + chosen.size() + " 个应用"), 15, Ink.BOLD), 14),
                spaced(ink.button("选择应用", Ink.Kind.PRIMARY, v -> appPickerDialog(f, requested, current)), 10));
        if (!chosen.isEmpty()) box.addView(spaced(ink.button("清空名单", Ink.Kind.SECONDARY, v -> {
            String value = FeatureDefinition.withActiveList(current, "");
            if (!begin(f.id, requested, value)) return;
            perform("清空名单", () -> {
                change(f.id, requested, value);
                return "已清空名单应用";
            }, false);
        }), 10));
        body.addView(spaced(box, 22));
    }

    /** 白/黑名单模式卡片；点按立即写入，保留已选名单。 */
    private View modeCard(FeatureDefinition f, boolean requested, String current, boolean white, String name, String detail, boolean selected) {
        LinearLayout card = new LinearLayout(this);
        card.setPadding(ink.dp(14), ink.dp(14), ink.dp(14), ink.dp(14));
        card.setBackground(ink.box(Ink.PAPER, Ink.INK, selected ? 3 : 1.5f, 12));
        LinearLayout content = column(ink.text(name, 16, selected ? Ink.BOLD : Ink.MEDIUM),
                spaced(paragraph(detail, 13), 4));
        if (selected) content.addView(spaced(ink.tag(requested ? "✓ 正在使用" : "已选（未开启）", true), 6));
        card.addView(content, new LinearLayout.LayoutParams(0, -2, 1));
        card.setContentDescription(ink.t(name));
        card.setOnClickListener(v -> {
            if (selected) return;
            String value = FeatureDefinition.filterMode(current, white);
            if (!begin(f.id, requested, value)) return;
            perform("使用「" + name + "」", () -> {
                change(f.id, requested, value);
                return "已保存「" + f.title + "」：" + name;
            }, false);
        });
        return card;
    }

    /** 应用选择器：列出可启动应用，支持搜索与多选，保存为逗号分隔的包名。 */
    private void appPickerDialog(FeatureDefinition f, boolean requested, String current) {
        LinkedHashMap<String, String> apps = launcherApps();
        List<CheckBox> boxes = new ArrayList<>();
        List<String> packages = new ArrayList<>();
        Set<String> selected = new LinkedHashSet<>(appList(current));
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        for (Map.Entry<String, String> app : apps.entrySet()) {
            CheckBox box = new CheckBox(this);
            box.setText(app.getValue());
            box.setTextSize(15);
            box.setChecked(selected.contains(app.getKey()));
            box.setPadding(ink.dp(10), ink.dp(9), ink.dp(10), ink.dp(9));
            list.addView(box);
            boxes.add(box);
            packages.add(app.getKey());
        }
        if (boxes.isEmpty()) list.addView(paragraph("没有可选择的启动器应用。", 14));
        ScrollView scroller = new ScrollView(this);
        scroller.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroller.addView(list);
        EditText search = new EditText(this);
        search.setHint(ink.t("搜索应用"));
        search.setSingleLine(true);
        search.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) { }
            @Override public void afterTextChanged(android.text.Editable text) {
                String query = text.toString().trim().toLowerCase(Locale.ROOT);
                for (int i = 0; i < boxes.size(); i++) {
                    boolean match = query.isEmpty() || boxes.get(i).getText().toString().toLowerCase(Locale.ROOT).contains(query)
                            || packages.get(i).toLowerCase(Locale.ROOT).contains(query);
                    boxes.get(i).setVisibility(match ? View.VISIBLE : View.GONE);
                }
            }
        });
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(ink.dp(16), ink.dp(4), ink.dp(16), ink.dp(4));
        content.addView(search);
        content.addView(scroller, new LinearLayout.LayoutParams(-1, ink.dp(360)));
        new AlertDialog.Builder(this)
                .setTitle(ink.t("选择应用") + " · " + ink.t(f.title) + " · " + ink.t(FeatureDefinition.filterWhitelist(current) ? "白名单" : "黑名单"))
                .setView(content)
                .setNegativeButton(ink.t("取消"), null)
                .setPositiveButton(ink.t("保存"), (dialog, which) -> {
                    List<String> chosen = new ArrayList<>();
                    for (int i = 0; i < boxes.size(); i++) if (boxes.get(i).isChecked()) chosen.add(packages.get(i));
                    String value = FeatureDefinition.withActiveList(current, String.join(",", chosen));
                    if (!begin(f.id, requested, value)) return;
                    perform(ink.t("保存"), () -> {
                        change(f.id, requested, value);
                        return "已保存「" + f.title + "」：" + chosen.size() + " 个应用";
                    }, false);
                }).show();
    }

    // ── 倒数日事项：最多 3 组「事项;日期」，事项可空，日期为空该组停用 ──
    // target 是数据落盘的 feature（lock.countdowns）；入口页是 lock.countdown，开关只控制显示。

    private void countdownEditor(FeatureDefinition target, String current) {
        final String[] dates = new String[FeatureDefinition.COUNTDOWNS_MAX];
        final boolean[] lunar = new boolean[FeatureDefinition.COUNTDOWNS_MAX];
        final boolean[] shown = new boolean[FeatureDefinition.COUNTDOWNS_MAX];
        // 兼容旧 6 段「事项;日期」值：农历=否、显示=是
        String[] parts = current == null ? new String[0] : current.split(";", -1);
        boolean legacy = parts.length == FeatureDefinition.COUNTDOWNS_MAX * FeatureDefinition.COUNTDOWN_LEGACY_FIELDS;
        boolean modern = parts.length == FeatureDefinition.COUNTDOWNS_MAX * FeatureDefinition.COUNTDOWN_FIELDS;
        int step = legacy ? FeatureDefinition.COUNTDOWN_LEGACY_FIELDS : (modern ? FeatureDefinition.COUNTDOWN_FIELDS : 0);
        LinearLayout box = column(ink.text("倒数日事项", 18, Ink.BOLD),
                spaced(paragraph("最多三个，按目标日先后显示。事项名可留空；日期为空该组停用。开启「农历日期」时按农历年/月/日计算（如选 2027-01-01 即 2027 年春节）；关闭「显示」可暂时隐藏该组。", 13), 12));
        final TextView[] dateButtons = new TextView[FeatureDefinition.COUNTDOWNS_MAX];
        final EditText[] itemFields = new EditText[FeatureDefinition.COUNTDOWNS_MAX];
        for (int i = 0; i < FeatureDefinition.COUNTDOWNS_MAX; i++) {
            final int index = i;
            int base = i * step;
            dates[i] = step > 0 && base + 1 < parts.length ? parts[base + 1].trim() : "";
            lunar[i] = !legacy && step > 0 && base + 2 < parts.length && "1".equals(parts[base + 2].trim());
            shown[i] = legacy || step <= 0 || base + 3 >= parts.length || !"0".equals(parts[base + 3].trim());
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            EditText item = new EditText(this);
            item.setHint(ink.t("事项（可空）"));
            item.setText(step > 0 && base < parts.length ? parts[base].trim() : "");
            item.setSingleLine(true);
            item.setFilters(new android.text.InputFilter[]{
                    new android.text.InputFilter.LengthFilter(FeatureDefinition.COUNTDOWN_ITEM_MAX),
                    (source, start, end, dest, dstart, dend) -> {
                        // 事项名不得含 ; 与 =：会破坏「事项;日期」序列化与 key=value 配置行
                        StringBuilder out = new StringBuilder();
                        for (int k = start; k < end; k++) {
                            char ch = source.charAt(k);
                            if (ch != ';' && ch != '=' && ch != '\n') out.append(ch);
                        }
                        return out.length() == end - start ? null : out.toString();
                    }});
            item.setBackground(ink.box(Ink.PAPER, Ink.INK, 1.5f, 8));
            item.setPadding(ink.dp(10), ink.dp(8), ink.dp(10), ink.dp(8));
            itemFields[i] = item;
            row.addView(item, new LinearLayout.LayoutParams(0, -2, 1));
            TextView date = ink.button(dates[i].isEmpty() ? "选日期" : dates[i] + (lunar[i] ? "（农历）" : ""), Ink.Kind.SECONDARY, v -> {
                Calendar initial = FeatureDefinition.parseDate(dates[index]);
                if (initial == null) initial = Calendar.getInstance();
                new DatePickerDialog(this, (view, year, month, day) -> {
                    dates[index] = String.format(Locale.ROOT, "%04d-%02d-%02d", year, month + 1, day);
                    dateButtons[index].setText(dates[index] + (lunar[index] ? "（农历）" : ""));
                }, initial.get(Calendar.YEAR), initial.get(Calendar.MONTH), initial.get(Calendar.DAY_OF_MONTH)).show();
            });
            dateButtons[i] = date;
            LinearLayout.LayoutParams dateParams = new LinearLayout.LayoutParams(-2, -2);
            dateParams.leftMargin = ink.dp(8);
            row.addView(date, dateParams);
            box.addView(spaced(row, 6));
            // 日期按钮下方：本组独立的「农历日期」「显示」两个开关
            LinearLayout sub = new LinearLayout(this);
            sub.setGravity(Gravity.CENTER_VERTICAL);
            sub.addView(ink.text("农历日期", 13, Ink.MEDIUM));
            final CompoundButton lunarToggle = miniToggle(lunar[i]);
            lunarToggle.setOnClickListener(v -> {
                lunar[index] = lunarToggle.isChecked();
                dateButtons[index].setText(dates[index].isEmpty() ? "选日期" : dates[index] + (lunarToggle.isChecked() ? "（农历）" : ""));
            });
            sub.addView(lunarToggle);
            TextView showLabel = ink.text("显示", 13, Ink.MEDIUM);
            LinearLayout.LayoutParams showParams = new LinearLayout.LayoutParams(-2, -2);
            showParams.leftMargin = ink.dp(18);
            sub.addView(showLabel, showParams);
            final CompoundButton showToggle = miniToggle(shown[i]);
            showToggle.setOnClickListener(v -> shown[index] = showToggle.isChecked());
            sub.addView(showToggle);
            box.addView(spaced(sub, 8));
        }
        box.addView(spaced(ink.button("保存", Ink.Kind.PRIMARY, v -> {
            // 填了事项却没选日期：该组不会显示（日期为空停用），先明确提示再保存
            for (int i = 0; i < FeatureDefinition.COUNTDOWNS_MAX; i++) {
                if (!itemFields[i].getText().toString().trim().isEmpty() && dates[i].isEmpty()) {
                    Toast.makeText(this, ink.t("第 " + (i + 1) + " 组填了事项但没选日期，该组不会显示"), Toast.LENGTH_LONG).show();
                    return;
                }
            }
            StringBuilder value = new StringBuilder();
            for (int i = 0; i < FeatureDefinition.COUNTDOWNS_MAX; i++) {
                if (i > 0) value.append(';');
                value.append(itemFields[i].getText().toString().trim()).append(';').append(dates[i]).append(';')
                        .append(lunar[i] ? '1' : '0').append(';').append(shown[i] ? '1' : '0');
            }
            String text = value.toString();
            if (FeatureDefinition.countdowns(text) == null) {
                // 农历月无此日（选择器按公历选日，30/31 日可能不是合法农历日）
                for (int i = 0; i < FeatureDefinition.COUNTDOWNS_MAX; i++) {
                    if (lunar[i] && !dates[i].isEmpty() && FeatureDefinition.targetDate(dates[i], true) == null) {
                        Toast.makeText(this, ink.t("农历日期「" + dates[i] + "」不存在，请改选其他日期"), Toast.LENGTH_LONG).show();
                        return;
                    }
                }
                Toast.makeText(this, ink.t("日期格式不正确"), Toast.LENGTH_SHORT).show();
                return;
            }
            if (!begin(target.id, true, text)) return;
            perform(ink.t("保存"), () -> {
                change(target.id, true, text);
                return "已保存「" + target.title + "」";
            }, false);
        }), 14));
        body.addView(spaced(box, 16));
    }

    // ── 字体：目录、字体列表、文件选择、恢复与刷新都在同一页 ─────────────────

    private void fontPage(FeatureDefinition f) {
        ApplicationServices.Snapshot s = snapshot;
        String saved = s.config.value("font.dir");
        String dir = saved == null || saved.isEmpty() ? FONT_DIR_DEFAULT : saved;
        String applied = s.config.value("font.pick");

        // 页面每次重渲（应用字体、保存目录等操作都会整页重建）搜索框都是新建的、内容为空，
        // 过滤词若不清空，就会出现「框看着是空的、列表却被上一次的词过滤到一行不剩」的假死，
        // 用户无字可删、清不掉，只能以为字体列表坏了。进入/重建页面一律回到「显示全部」。
        fontQuery = "";
        fontDir = dir;

        body.addView(spaced(paragraph("点「应用」把字体写进系统槽位后，状态栏、通知栏、锁屏、桌面与正在运行的应用会立刻一起换成新字体（全局替换，桌面应用字体也会变），不用重启。", 14), 4));

        LinearLayout box = column();
        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setText(saved == null ? "" : saved);
        field.setHint(FONT_DIR_DEFAULT);
        field.setBackground(ink.box(Ink.PAPER, Ink.INK, 1.5f, 8));
        field.setPadding(ink.dp(10), ink.dp(8), ink.dp(10), ink.dp(8));
        box.addView(spaced(field, 12));
        box.addView(spaced(ink.button("保存并刷新列表", Ink.Kind.PRIMARY, v -> saveFontDir("font.dir", field.getText().toString())), 10));

        LinearLayout actions = new LinearLayout(this);
        actions.addView(ink.button("选择目录", Ink.Kind.SECONDARY, v -> pickFontDir()), new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams rightParams = new LinearLayout.LayoutParams(0, -2, 1);
        rightParams.leftMargin = ink.dp(10);
        actions.addView(ink.button("从文件选择", Ink.Kind.SECONDARY, v -> pickFontFile()), rightParams);
        box.addView(spaced(actions, 12));

        box.addView(spaced(ink.button("恢复原厂字体", Ink.Kind.SECONDARY, v -> restoreFont()), 10));
        box.addView(spaced(paragraph("字体是全局替换：状态栏、锁屏、桌面与所有应用一起换字，不存在「桌面不变」的情况。字体缺少中文字形时部分界面会出现方块，点上面的「恢复原厂字体」即可退回系统默认字体。", 12), 8));
        box.addView(spaced(ink.text("默认：" + FONT_DIR_DEFAULT, 12, Ink.MEDIUM), 8));
        if (applied != null && !applied.isEmpty())
            box.addView(spaced(ink.text("上次应用：" + applied, 13, Ink.BOLD), 12));
        body.addView(spaced(box, 16));

        // 搜索框：只过滤下方列表的显示，不重新扫描目录
        EditText search = new EditText(this);
        search.setSingleLine(true);
        search.setHint("搜索字体名…");
        search.setBackground(ink.box(Ink.PAPER, Ink.INK, 1.5f, 8));
        search.setPadding(ink.dp(10), ink.dp(8), ink.dp(10), ink.dp(8));
        search.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(android.text.Editable s) {
                fontQuery = s == null ? "" : s.toString();
                renderFontList();
            }
        });
        body.addView(spaced(search, 12));

        fontAppliedMark = applied;
        fontHintView = ink.text("正在扫描…", 13, Ink.MEDIUM);
        body.addView(fontHintView);
        fontListViews = column();
        body.addView(spaced(fontListViews, 8));
        loadFonts(dir, applied);
    }

    private void loadFonts(String dir, String applied) {
        WORKER.execute(() -> {
            List<String[]> fonts = new ArrayList<>();
            String failure = "";
            try {
                // 优先走 root 列表：App 没有存储权限时也能读系统字体目录与 TF 卡
                for (String[] font : services.listFonts(dir))
                    fonts.add(new String[]{font[0], font[1], fontSize(Long.parseLong(font[2]))});
            } catch (Exception error) {
                fonts = scanFonts(dir);
                if (fonts.isEmpty()) failure = reason(error);
            }
            List<String[]> found = fonts;
            String problem = failure;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                fontItems = found;
                fontProblem = problem;
                fontAppliedMark = applied;
                renderFontList();
            });
        });
    }

    /** 按搜索词过滤并重画字体列表；过滤词为空时显示全部。 */
    private void renderFontList() {
        if (fontListViews == null || fontHintView == null) return;
        fontListViews.removeAllViews();
        if (!fontProblem.isEmpty()) {
            fontHintView.setText("读取目录失败：" + fontProblem + "\n可以用「从文件选择」直接挑一个字体文件。");
            fontListViews.addView(spaced(reloadFontsButton(), 8));
            return;
        }
        if (fontItems.isEmpty()) {
            fontHintView.setText("这个目录里没找到字体文件。换一个目录，或用「从文件选择」。");
            fontListViews.addView(spaced(reloadFontsButton(), 8));
            return;
        }
        String q = fontQuery == null ? "" : fontQuery.trim().toLowerCase(Locale.ROOT);
        List<String[]> shown = new ArrayList<>();
        for (String[] font : fontItems)
            if (q.isEmpty() || font[1].toLowerCase(Locale.ROOT).contains(q)) shown.add(font);
        if (q.isEmpty()) fontHintView.setText("共 " + fontItems.size() + " 个字体");
        else if (shown.isEmpty()) fontHintView.setText("没有匹配「" + fontQuery.trim() + "」的字体，清空搜索框显示全部");
        else fontHintView.setText("匹配 " + shown.size() + " / 共 " + fontItems.size() + " 个字体");
        for (String[] font : shown) fontListViews.addView(spaced(fontRow(font, fontAppliedMark), 8));
    }

    /** 列表读取失败或目录为空时的重试入口：按当前目录重新走一遍 root 列表 + 兜底扫描。 */
    private View reloadFontsButton() {
        return ink.button("重新加载列表", Ink.Kind.SECONDARY, v -> {
            fontHintView.setText("正在扫描…");
            loadFonts(fontDir, fontAppliedMark);
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
        TextView name = ink.text(font[1], 15, Ink.BOLD);
        name.setSingleLine(false);
        TextView size = ink.text(font[2] + (current ? " · 上次已应用" : ""), 12, Ink.REGULAR);
        // 紧凑「应用」按钮：高度压到与字号相当
        TextView apply = ink.button("应用", Ink.Kind.SECONDARY, v -> applyFontFile(font[0], font[1]));
        apply.setTextSize(13);
        apply.setMinHeight(ink.dp(30));
        apply.setPadding(ink.dp(14), ink.dp(4), ink.dp(14), ink.dp(4));
        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.addView(name);
        textCol.addView(spaced(size, 2));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(textCol, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams button = new LinearLayout.LayoutParams(-2, -2);
        button.leftMargin = ink.dp(8);
        row.addView(apply, button);
        row.setBackground(ink.outline(12));
        row.setPadding(ink.dp(14), ink.dp(10), ink.dp(10), ink.dp(10));
        return row;
    }

    private void saveFontDir(String id, String text) {
        String value = text == null ? "" : text.trim();
        if (!value.isEmpty() && !value.startsWith("/")) {
            Toast.makeText(this, "请填绝对路径，例如 " + FONT_DIR_DEFAULT, Toast.LENGTH_LONG).show();
            return;
        }
        if (FeatureDefinition.path(value) == null) {
            Toast.makeText(this, "路径里不能有等号、分号或换行", Toast.LENGTH_LONG).show();
            return;
        }
        perform(ink.t("保存字体目录"), () -> {
            change(id, true, value);
            return "已保存字体目录：" + (value.isEmpty() ? FONT_DIR_DEFAULT : value);
        }, false);
    }

    private void pickFontDir() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        try { startActivityForResult(intent, FONT_DIR_REQUEST); }
        catch (android.content.ActivityNotFoundException error) { showText("无法选择目录", "系统没有可用的文件夹选择界面。"); }
    }

    private void pickFontFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);
        try { startActivityForResult(intent, FONT_REQUEST); }
        catch (android.content.ActivityNotFoundException error) { showText("无法选择文件", "系统没有可用的文件选择界面。"); }
    }

    /** 文件选择器给的 tree URI 转成文件系统路径；转不出来时返回 null（用「从文件选择」兜底）。 */
    private static String treePath(android.net.Uri uri) {
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
        if (busy) { Toast.makeText(this, ink.t("请等待当前操作完成"), Toast.LENGTH_SHORT).show(); return; }
        perform(ink.t("应用字体"), () -> {
            String result = services.applyFont(path);
            rememberFont(name);
            return result;
        }, true);
    }

    /** 记住最近应用的字体名；写配置失败不影响字体已经写入的事实。 */
    private void rememberFont(String name) {
        if (FeatureDefinition.path(name) == null) return;
        try { change("font.pick", true, name); } catch (Exception ignored) { }
    }

    private void applyFontFromUri(android.net.Uri uri) {
        if (busy) { Toast.makeText(this, ink.t("请等待当前操作完成"), Toast.LENGTH_SHORT).show(); return; }
        perform(ink.t("应用字体"), () -> {
            java.io.File copy = new java.io.File(getCacheDir(), "font-apply.ttf");
            try (java.io.InputStream input = getContentResolver().openInputStream(uri);
                 OutputStream output = new java.io.FileOutputStream(copy)) {
                if (input == null) throw new java.io.IOException("无法读取所选文件");
                byte[] buffer = new byte[65536];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            }
            String result = services.applyFont(copy.getAbsolutePath());
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
        if (busy) { Toast.makeText(this, ink.t("请等待当前操作完成"), Toast.LENGTH_SHORT).show(); return; }
        confirm("恢复原厂字体", "把系统原厂默认字体（Roboto-Medium）重新指向当前槽位——也就是系统「字体样式」里的「默认字体」。写完界面自动刷新（全局界面一起换，包括桌面），不用重启。",
                () -> perform(ink.t("恢复原厂字体"), () -> services.restoreFont(), true));
    }

    /** 编辑器行内小开关：无动画黑白开关图形，状态由调用方读取。
     *  注意：不可重写 toggle() 为空——CompoundButton.performClick() 靠它切换选中态，
     *  置空后点击无反应、OnClickListener 里读到的 isChecked() 永远是旧值。 */
    private CompoundButton miniToggle(boolean checked) {
        CompoundButton toggle = new CompoundButton(this) {
            @Override public CharSequence getAccessibilityClassName() { return Switch.class.getName(); }
        };
        toggle.setButtonDrawable(ink.switchDrawable());
        toggle.setChecked(checked);
        return toggle;
    }

    private LinkedHashMap<String, String> launcherApps() {
        List<String[]> items = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        try {
            android.content.pm.PackageManager manager = getPackageManager();
            List<android.content.pm.ResolveInfo> resolved = manager.queryIntentActivities(
                    new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0);
            for (android.content.pm.ResolveInfo info : resolved) {
                if (info == null || info.activityInfo == null) continue;
                String pkg = info.activityInfo.packageName;
                if (pkg == null || pkg.equals(getPackageName()) || !seen.add(pkg)) continue;
                CharSequence label = info.loadLabel(manager);
                items.add(new String[]{label == null || label.length() == 0 ? pkg : label.toString(), pkg});
            }
        } catch (RuntimeException error) { /* 读不到应用列表时按空名单处理 */ }
        Collections.sort(items, (left, right) -> left[0].compareToIgnoreCase(right[0]));
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        for (String[] item : items) result.put(item[1], item[0]);
        return result;
    }

    // ── 关键帧停留：温度曲线 ──

    private float batteryTemperature() {
        try {
            Intent battery = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            int t = battery == null ? Integer.MIN_VALUE : battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
            return t == Integer.MIN_VALUE ? 30f : t / 10f;
        } catch (RuntimeException error) { return 30f; }
    }
    private String savedHoldMode(boolean requested, String value) {
        if (!requested || value.equals("0")) return "auto";
        int[] curve = FeatureDefinition.curve(value);
        if (curve == null) return "fixed";
        for (int v : curve) if (v != curve[0]) return "curve";
        return "fixed";
    }
    private int[] savedHoldValues(String value) {
        int[] curve = FeatureDefinition.curve(value);
        if (curve != null) return curve;
        int fixed = value.matches("[0-9]+") ? Integer.parseInt(value) : 0;
        if (fixed <= 0) return Charts.autoCurve();
        int[] all = new int[FeatureDefinition.TEMPERATURE_BANDS];
        Arrays.fill(all, fixed);
        return all;
    }
    private String holdSummary(String value) {
        String mode = savedHoldMode(true, value);
        if (mode.equals("auto")) return "温度自适应";
        int[] v = savedHoldValues(value);
        return mode.equals("fixed") ? "统一 " + v[0] + " 毫秒" : "自定义曲线";
    }
    /** 当前温度下实际生效的停留时长；未开启或该段为 0 时用自动值。 */
    private int currentHold() {
        int band = FeatureDefinition.band(batteryTemperature());
        if (!snapshot.config.enabled("motion.hold")) return Geometry.autoHoldForBand(band);
        int v = savedHoldValues(snapshot.config.value("motion.hold"))[band];
        return v > 0 ? v : Geometry.autoHoldForBand(band);
    }

    private void holdEditor(FeatureDefinition f, boolean requested, String current) {
        String saved = savedHoldMode(requested, current);
        if (holdMode.isEmpty()) holdMode = saved;
        if (holdDraft == null) holdDraft = savedHoldValues(current);
        float temperature = batteryTemperature();
        int band = FeatureDefinition.band(temperature);
        int[] auto = Charts.autoCurve();

        LinearLayout box = column(ink.text("怎么决定停留时长", 18, Ink.BOLD));
        LinearLayout modes = new LinearLayout(this);
        String[][] choices = {{"auto", "温度自适应"}, {"fixed", "统一时长"}, {"curve", "自定义曲线"}};
        for (int i = 0; i < choices.length; i++) {
            String mode = choices[i][0];
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ink.dp(42), 1);
            if (i > 0) p.leftMargin = ink.dp(8);
            modes.addView(ink.chip(choices[i][1], mode.equals(holdMode), v -> {
                holdMode = mode;
                if (mode.equals("fixed")) { int v0 = holdDraft[band] > 0 ? holdDraft[band] : auto[band]; Arrays.fill(holdDraft, v0); }
                else if (mode.equals("curve") && saved.equals("auto")) holdDraft = auto.clone();
                render();
            }), p);
        }
        box.addView(spaced(modes, 12));

        String explain;
        int[] shownValues;
        if (holdMode.equals("auto")) { explain = "按电池温度自动取“刚好刷完 + 3 帧余量”。天冷时自动变长，避免画面糊在一起。"; shownValues = auto; }
        else if (holdMode.equals("fixed")) { explain = "所有温度都使用同一个时长。天冷时如果低于虚线，动画可能糊。"; shownValues = holdDraft; }
        else { explain = "上下拖动每根柱子，单独设置每个温度段，10 毫秒一档。虚线是该温度刷完所需的时间。"; shownValues = holdDraft; }
        box.addView(spaced(paragraph(explain, 14), 12));

        int now = holdMode.equals("auto") || shownValues[band] <= 0 ? auto[band] : shownValues[band];
        box.addView(spaced(ink.text(String.format(Locale.ROOT, "当前电池温度 %.1f°C → 每张中间画面停留 %d 毫秒", temperature, now), 14, Ink.BOLD), 12));

        Charts.HoldCurve curve = new Charts.HoldCurve(this, ink, shownValues, band, holdMode.equals("curve"),
                values -> { holdDraft = values; render(); });
        box.addView(spaced(curve, 14));
        box.addView(spaced(paragraph("实心柱：设置的时长　虚线：该温度刷完所需　空心柱：低于所需，可能糊", 12), 6));

        if (holdMode.equals("fixed")) {
            LinearLayout stepper = new LinearLayout(this);
            stepper.setGravity(Gravity.CENTER_VERTICAL);
            stepper.addView(ink.button("−", Ink.Kind.SECONDARY, v -> { Arrays.fill(holdDraft, Math.max(30, holdDraft[0] - 10)); render(); }), new LinearLayout.LayoutParams(ink.dp(64), -2));
            TextView value = ink.text(holdDraft[0] + " 毫秒", 22, Ink.BOLD);
            value.setGravity(Gravity.CENTER);
            stepper.addView(value, new LinearLayout.LayoutParams(0, -2, 1));
            stepper.addView(ink.button("+", Ink.Kind.SECONDARY, v -> { Arrays.fill(holdDraft, Math.min(FeatureDefinition.CURVE_MAX_MS, holdDraft[0] + 10)); render(); }), new LinearLayout.LayoutParams(ink.dp(64), -2));
            box.addView(spaced(stepper, 14));
            FlowLayout presets = new FlowLayout(this, ink.dp(8), ink.dp(8));
            for (int ms : new int[]{100, 150, 200, 300, 500, 1000}) presets.addView(ink.chip(ms + " 毫秒", holdDraft[0] == ms, v -> { Arrays.fill(holdDraft, ms); render(); }));
            box.addView(spaced(presets, 12));
        } else if (holdMode.equals("curve")) {
            LinearLayout actions = new LinearLayout(this);
            actions.addView(ink.button("从自动值开始", Ink.Kind.SECONDARY, v -> { holdDraft = auto.clone(); render(); }), new LinearLayout.LayoutParams(0, -2, 1));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1);
            p.leftMargin = ink.dp(10);
            actions.addView(ink.button("整体加长 20%", Ink.Kind.SECONDARY, v -> {
                for (int i = 0; i < holdDraft.length; i++) holdDraft[i] = Math.min(FeatureDefinition.CURVE_MAX_MS, Math.round(holdDraft[i] * 1.2f / 10) * 10);
                render();
            }), p);
            box.addView(spaced(actions, 14));
        }

        boolean changed = !holdMode.equals(saved) || !holdMode.equals("auto") && !Arrays.equals(holdDraft, savedHoldValues(current));
        if (changed) {
            String mode = holdMode;
            int[] values = holdDraft.clone();
            box.addView(spaced(ink.button("保存", Ink.Kind.PRIMARY, v -> {
                String next = mode.equals("auto") ? current : FeatureDefinition.curveValue(values);
                boolean enable = !mode.equals("auto");
                if (!begin(f.id, enable, next)) return;
                perform("保存关键帧停留", () -> {
                    change(f.id, enable, next);
                    return "已保存「关键帧停留」：" + (mode.equals("auto") ? "温度自适应" : mode.equals("fixed") ? "统一 " + values[0] + " 毫秒" : "自定义曲线");
                }, false);
                holdMode = ""; holdDraft = null;
            }), 16));
        }
        body.addView(spaced(box, 22));
    }

    // ───────────────────────── 模块设置 ─────────────────────────

    /** 「安装」「诊断」的统一入口：跳转卡片 + 语言切换。 */
    private void moduleSettings() {
        body.addView(spaced(navCard("安装", "导出 Magisk 安装包、启用墨控模块并勾选作用域的完整向导。",
                "", false, "打开安装向导", v -> { modulePage = INSTALL; render(); }), 12));
        body.addView(spaced(navCard("诊断", "查看后端模块、配置应用与各项生效状态，排查异常。",
                "", false, "打开诊断", v -> { modulePage = DIAGNOSE; render(); }), 12));
        body.addView(spaced(navCard("大功能排序", "调整首页各大功能的显示顺序，立即生效、随时可恢复默认。",
                "", false, "打开大功能排序", v -> { modulePage = ORDER; render(); }), 12));
        body.addView(spaced(navCard("实验室", Guide.CATEGORIES.get("实验室").intro,
                "", false, "打开实验室", v -> { tab = FEATURES; category = "实验室"; featureId = ""; scroll = null; render(); }), 12));
        body.addView(spaced(navCard("名词解释", "刷新方式、残影、关键帧、波形动画……这些词是什么意思。",
                "", false, "名词解释", v -> navigate(TERMS, "")), 12));

        LinearLayout languageCard = column(ink.text("语言 / Language", 16, Ink.BOLD));
        TextView language = ink.chip("中 / EN", false, v -> UiLanguage.choose(this));
        language.setContentDescription(ink.t("语言 / Language"));
        languageCard.addView(spaced(language, 10));
        languageCard.addView(spaced(paragraph("英文翻译已停止维护：部分界面文字可能仍显示中文。", 13), 8));
        body.addView(spaced(card(languageCard), 12));

        // 操作结果提示条（顶部「已保存…」那行）的停留时长；默认 30 秒自动消失，也可设为不显示。
        LinearLayout noticeCard = column(ink.text("提示时长", 16, Ink.BOLD));
        TextView duration = ink.chip(UiNotice.label(noticeSeconds), false, v -> UiNotice.choose(this));
        duration.setContentDescription(ink.t("提示时长"));
        noticeCard.addView(spaced(duration, 10));
        noticeCard.addView(spaced(paragraph("修改设置后的结果提示会在页面上停留一段时间后自动消失；"
                + "选「不显示」则不再占用页面空间。失败提示不会自动消失。", 13), 8));
        body.addView(spaced(card(noticeCard), 12));
    }

    // ───────────────────────── 大功能排序 ─────────────────────────

    /** 首页大功能的显示顺序：按保存的顺序排列；空表示默认顺序。 */
    private List<Guide.Category> orderedCategories() {
        Map<String, Guide.Category> pool = new LinkedHashMap<>();
        for (Guide.Category c : Guide.CATEGORIES.values()) pool.put(c.name, c);
        List<Guide.Category> result = new ArrayList<>();
        for (String name : UiCategoryOrder.saved(this).split(";", -1)) {
            Guide.Category c = pool.remove(name);
            if (c != null) result.add(c);
        }
        if (pool.isEmpty()) return result;
        // 保存的顺序里没有的分类（通常是升级后新加的）：插到默认顺序里它前一个「已排好」的分类后面，
        // 而不是一律追加到末尾——否则用户排序过一次之后，以后新增的分类永远跑到最后。
        List<String> defaults = new ArrayList<>(Guide.CATEGORIES.keySet());
        for (Guide.Category c : Guide.CATEGORIES.values()) {
            if (!pool.containsKey(c.name)) continue;
            int insert = 0;
            for (int i = defaults.indexOf(c.name) - 1; i >= 0; i--) {
                int pos = indexOfCategory(result, defaults.get(i));
                if (pos >= 0) { insert = pos + 1; break; }
            }
            result.add(insert, c);
        }
        return result;
    }

    private static int indexOfCategory(List<Guide.Category> list, String name) {
        for (int i = 0; i < list.size(); i++) if (list.get(i).name.equals(name)) return i;
        return -1;
    }

    private void categoryOrderPage() {
        body.addView(card(column(heading("大功能排序", 20),
                spaced(paragraph("调整首页各大功能的显示顺序。改动立即生效，只影响排列，不影响任何功能本身；「实验室」在「模块设置」里，不参与排序。", 14), 8))));
        // 编辑列表不含「实验室」：它不在首页显示，顺序由导航位置决定。
        List<Guide.Category> list = new ArrayList<>();
        for (Guide.Category c : orderedCategories()) if (!c.name.equals("实验室")) list.add(c);
        LinearLayout box = column();
        for (int i = 0; i < list.size(); i++) {
            Guide.Category c = list.get(i);
            if (i > 0) box.addView(ink.rule(1));
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView name = ink.text(c.name, 16, Ink.BOLD);
            name.setPadding(ink.dp(14), 0, 0, 0);
            row.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
            if (i > 0) row.addView(ink.button("上移", Ink.Kind.SECONDARY, v -> moveCategory(list.indexOf(c), -1)));
            if (i < list.size() - 1) {
                LinearLayout.LayoutParams down = new LinearLayout.LayoutParams(-2, -2);
                down.leftMargin = ink.dp(8);
                row.addView(ink.button("下移", Ink.Kind.SECONDARY, v -> moveCategory(list.indexOf(c), 1)), down);
            }
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
            rowParams.setMargins(0, ink.dp(10), 0, ink.dp(10));
            box.addView(row, rowParams);
        }
        body.addView(spaced(card(box), 16));
        body.addView(spaced(ink.button("恢复默认顺序", Ink.Kind.SECONDARY, v -> {
            UiCategoryOrder.save(this, "");
            render();
        }), 12));
    }

    /** 交换相邻两项并立即保存；保存的是不含「实验室」的完整顺序。 */
    private void moveCategory(int index, int delta) {
        List<Guide.Category> list = new ArrayList<>();
        for (Guide.Category c : orderedCategories()) if (!c.name.equals("实验室")) list.add(c);
        int target = index + delta;
        if (index < 0 || target < 0 || target >= list.size()) return;
        Collections.swap(list, index, target);
        StringBuilder value = new StringBuilder();
        for (Guide.Category c : list) {
            if (value.length() > 0) value.append(';');
            value.append(c.name);
        }
        UiCategoryOrder.save(this, value.toString());
        render();
    }

    // ───────────────────────── 安装 ─────────────────────────

    private void installation() {
        ApplicationServices.Snapshot s = snapshot;
        // 配置读取失败时 snapshot 可能是 null：安装页必须照样打开（导出 ZIP 就在这里）
        Map<String, String> env = s == null ? Collections.emptyMap() : s.environment;
        body.addView(card(column(
                heading("开始使用", 20),
                spaced(paragraph("适用于 HLTE203T · Android 10 · 固件 L1732.6.01.02，需要 Magisk 与 LSPosed 类框架（如 Vector）。"
                        + "没有 Root 也能浏览功能说明、观看演示和导出安装包。", 14), 8))));

        boolean checked = s != null && s.checked();
        boolean supported = checked && "HLTE203T".equals(env.get("device")) && "29".equals(env.get("sdk"));
        boolean installed = "1".equals(env.get("installed")) && BuildInfo.VERSION.equals(env.get("backend_version"));
        String conflict = env.getOrDefault("conflict", "");
        int hooks = s == null ? 0 : s.hooked.size();

        String checkText = "授予 Root 后读取设备型号、固件和后端状态。";
        if (checked && !supported) checkText += "\n当前设备 " + env.getOrDefault("device", "?") + " 不在适配范围内，增强不会生效。";
        body.addView(step(1, checked, "检查环境", checkText,
                ink.button("检查环境", Ink.Kind.PRIMARY, v -> perform("检查环境", () -> services.inspect(), true))));

        String installText = "导出安装包，在 Magisk →「模块」→「从本地安装」中选择它。";
        if (checked && !installed && !env.getOrDefault("backend_version", "").isEmpty())
            installText += "\n已安装的后端是 " + env.get("backend_version") + "，与本 App 不一致，请重新安装。";
        if (!conflict.isEmpty())
            installText += "\n检测到同类模块 " + conflict + " 仍在启用，它会与墨控争用显示库，请在 Magisk 中停用。";
        body.addView(step(2, installed && conflict.isEmpty(), "安装 Magisk 后端", installText,
                ink.button("导出 Magisk 安装包", Ink.Kind.PRIMARY, v -> exportZip())));

        String hookText = "在框架管理器中启用「墨控」，作用域勾选：系统框架、系统界面、桌面（com.hmct.vision）。";
        if (checked && installed && hooks > 0 && hooks < 3) hookText += "\n目前只有 " + hooks + " / 3 个作用域已加载，请检查勾选后重启。";
        body.addView(step(3, hooks == 3, "启用框架模块", hookText, null));

        body.addView(step(4, s != null && s.ready() && hooks == 3, "重启并开启功能",
                "完成以上步骤后完整重启手机，回到这里重新检查环境，然后到「功能」按需开启。所有增强默认关闭，可随时一键全部关闭。",
                ink.button("前往功能", Ink.Kind.SECONDARY, v -> { category = ""; featureId = ""; switchTab(FEATURES); })));
    }

    private View step(int number, boolean done, String title, String text, View actions) {
        LinearLayout row = new LinearLayout(this);
        row.setPadding(0, ink.dp(20), 0, 0);
        TextView marker = ink.text(done ? "✓" : String.valueOf(number), 15, Ink.BOLD);
        marker.setGravity(Gravity.CENTER);
        marker.setBackground(done ? ink.solid(16) : ink.outline(16));
        marker.setTextColor(done ? Ink.PAPER : Ink.INK);
        marker.setContentDescription(ink.t("第 " + number + " 步" + (done ? "，已完成" : "")));
        LinearLayout.LayoutParams markerParams = new LinearLayout.LayoutParams(ink.dp(32), ink.dp(32));
        markerParams.setMargins(0, 0, ink.dp(14), 0);
        row.addView(marker, markerParams);
        LinearLayout content = column(ink.text(title, 18, Ink.BOLD), spaced(paragraph(text, 14), 8));
        if (actions != null) content.addView(spaced(actions, 12));
        row.addView(content, new LinearLayout.LayoutParams(0, -2, 1));
        return row;
    }

    // ───────────────────────── 诊断 ─────────────────────────

    private void diagnostics() {
        ApplicationServices.Snapshot s = snapshot;
        // 配置读取失败时 snapshot 可能是 null：诊断页必须照样打开
        Map<String, String> env = s == null ? Collections.emptyMap() : s.environment;
        LinearLayout summary = column(heading("运行环境", 20));
        if (s == null || !s.checked()) {
            summary.addView(spaced(paragraph(s == null ? "环境还没读到（配置读取失败）。检查动作依赖 Root；仍可在「安装」导出 ZIP。"
                    : s.failure.equals("尚未检查环境") ? "尚未检查。点击下方按钮会申请 Root，并读取当前进程与后端状态。"
                    : "检查未完成：" + s.failure + "\n仍可浏览说明和导出 ZIP。", 14), 10));
        } else {
            summary.addView(spaced(fact("设备", env.getOrDefault("device", "?") + " · SDK " + env.getOrDefault("sdk", "?")), 12));
            summary.addView(fact("固件", env.getOrDefault("firmware", "?")));
            summary.addView(fact("后端模块", "1".equals(env.get("installed")) ? "已安装 " + env.getOrDefault("backend_version", "") : "未安装或已停用"));
            summary.addView(fact("框架模块", s.hooked.size() + " / 3 个作用域已加载"));
            if (!env.getOrDefault("conflict", "").isEmpty()) summary.addView(fact("冲突模块", env.get("conflict")));
            summary.addView(fact("整体就绪", s.ready() ? "是" : "否"));
            if (!env.getOrDefault("error", "").isEmpty()) summary.addView(fact("后端错误", env.get("error")));
        }
        body.addView(card(summary));
        body.addView(spaced(ink.button("刷新环境与当前进程确认", Ink.Kind.PRIMARY, v -> perform("刷新环境", () -> services.inspect(), false)), 16));
        body.addView(spaced(ink.button("重试应用已保存配置", Ink.Kind.SECONDARY, v -> perform("重试应用配置", () -> services.retry(), true)), 10));

        if (!output.isEmpty()) {
            LinearLayout raw = new LinearLayout(this);
            raw.setOrientation(LinearLayout.VERTICAL);
            LinearLayout head = new LinearLayout(this);
            head.setGravity(Gravity.CENTER_VERTICAL);
            head.addView(ink.text("最近一次完整输出", 16, Ink.BOLD), new LinearLayout.LayoutParams(0, -2, 1));
            head.addView(ink.text(outputExpanded ? "收起 ▾" : "展开 ▸", 14, Ink.MEDIUM));
            head.setOnClickListener(v -> { outputExpanded = !outputExpanded; render(); });
            raw.addView(head);
            if (outputExpanded) {
                TextView text = ink.text(output, 11, Ink.MONO);
                text.setTextIsSelectable(true);
                raw.addView(spaced(text, 12));
            }
            body.addView(spaced(card(raw), 24));
        }
    }
    private View fact(String key, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setPadding(0, ink.dp(6), 0, ink.dp(6));
        row.addView(ink.text(key, 14, Ink.REGULAR), new LinearLayout.LayoutParams(ink.dp(96), -2));
        row.addView(ink.text(value, 14, Ink.MEDIUM), new LinearLayout.LayoutParams(0, -2, 1));
        return row;
    }

    // ───────────────────────── 操作 ─────────────────────────

    /** 配置先保存再由 Root 应用；保存成功但应用失败时如实说明，而不是笼统报“未完成”。 */
    private void change(String id, boolean enabled, String value) throws Exception {
        // 「显示通知」与「显示设备状态」共用锁屏底部那一块位置，界面层强制互斥：
        // 开其中一个就先把另一个关掉。列表开关与详情页按钮都走这里，所以只在这一处保证即可；
        // 关掉另一个时沿用它的现值，免得下次打开又要重选来源。
        if (enabled) {
            String other = id.equals("lock.notify") ? "lock.ext" : id.equals("lock.ext") ? "lock.notify" : "";
            if (!other.isEmpty()) {
                com.inkcontrol.core.Configuration current = services.configuration();
                if (current.requested(other)) services.change(other, false, current.value(other));
            }
        }
        try { services.change(id, enabled, value); }
        catch (Exception error) {
            com.inkcontrol.core.Configuration saved = services.configuration();
            if (saved.requested(id) == enabled && saved.value(id).equals(value))
                throw new IllegalStateException("选择已保存，但未能立即应用：" + reason(error) + "\n完成安装后可在「诊断」重试应用。", error);
            throw error;
        }
    }
    private boolean begin(String id, boolean enabled, String value) {
        if (busy) { Toast.makeText(this, ink.t("请等待当前操作完成"), Toast.LENGTH_SHORT).show(); return false; }
        pendingId = id; pendingEnabled = enabled; pendingValue = value;
        return true;
    }

    private String status(String id) {
        String value = snapshot == null ? null : snapshot.statuses.get(id);
        return value == null ? "未知" : value;
    }
    private static String glyph(String status) {
        if (status.equals("已生效")) return "●";
        if (status.equals("关闭")) return "○";
        if (status.equals("待重启")) return "↻";
        if (status.startsWith("异常")) return "✕";
        if (status.startsWith("依赖")) return "◌";
        if (status.equals("正在保存")) return "◐";
        return "…";
    }

    private void confirm(String title, String text, Runnable action) {
        new AlertDialog.Builder(this).setTitle(ink.t(title)).setMessage(ink.t(text)).setNegativeButton(ink.t("取消"), null).setPositiveButton(ink.t("继续"), (dialog, which) -> action.run()).show();
    }
    private void showText(String title, String text) {
        new AlertDialog.Builder(this).setTitle(ink.t(title)).setMessage(ink.t(text)).setPositiveButton(ink.t("知道了"), null).show();
    }

    /**
     * 后台执行并刷新快照。dialog 为 true 时总是弹出完整结果；否则只在失败时弹出。
     * 返回 false 表示已有操作在执行，本次未提交。onSuccess 在成功收尾后回主线程执行。
     */
    private boolean perform(String label, Work work, boolean dialog) { return perform(label, work, dialog, null); }

    private boolean perform(String label, Work work, boolean dialog, Runnable onSuccess) {
        if (busy) { Toast.makeText(this, ink.t("请等待当前操作完成"), Toast.LENGTH_SHORT).show(); return false; }
        // 新操作开始前撤掉上一条结果的自动消失计时，免得中途把新提示清掉。
        dismissNotice();
        busy = true; message = label; messageFailed = false;
        render();
        WORKER.execute(() -> {
            String result;
            boolean failed = false;
            try {
                result = work.run();
                // inspect 不抛异常，以固定前缀返回未完成。
                failed = result != null && result.startsWith("环境检查未完成");
            } catch (Exception error) { result = reason(error); failed = true; }
            ApplicationServices.Snapshot next = null;
            String error = "";
            try { next = services.snapshot(); } catch (Exception failure) { error = reason(failure); }
            String text = result == null ? "" : result;
            boolean failure = failed;
            ApplicationServices.Snapshot loaded = next;
            String loadFailure = error;
            runOnUiThread(() -> {
                busy = false; pendingId = "";
                if (isFinishing() || isDestroyed()) return;
                if (loaded != null) snapshot = loaded;
                loadError = loadFailure;
                message = failure && !text.startsWith("环境检查未完成") ? "未完成：" + text.trim() : text.isEmpty() ? label + "：完成" : text.trim();
                messageFailed = failure;
                output = text;
                render();
                scheduleNoticeDismiss();
                if (dialog || failure) showText(failure ? "未完成" : "操作结果", text);
                if (!failure && onSuccess != null) onSuccess.run();
            });
        });
        return true;
    }
    private static String reason(Throwable error) {
        String text = error.getMessage();
        return text == null || text.isEmpty() ? error.getClass().getSimpleName() : text;
    }

    private void exportZip() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/zip").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, "inkcontrol_backend-v" + BuildInfo.VERSION + ".zip");
        try { startActivityForResult(intent, EXPORT_REQUEST); }
        catch (android.content.ActivityNotFoundException error) { showText("无法导出", "系统没有可用的文件保存界面。"); }
    }
    @Override public void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == EXPORT_REQUEST && result == RESULT_OK && data != null && data.getData() != null) perform("导出 ZIP", () -> {
            try (OutputStream out = getContentResolver().openOutputStream(data.getData())) { if (out == null) throw new java.io.IOException("无法写入目标文件"); services.exportZip(out); }
            return "ZIP 已校验并导出，请在 Magisk 中从本地安装。";
        }, true);
        if (request == FONT_REQUEST && result == RESULT_OK && data != null && data.getData() != null)
            applyFontFromUri(data.getData());
        if (request == FONT_DIR_REQUEST && result == RESULT_OK && data != null && data.getData() != null) {
            String path = treePath(data.getData());
            if (path == null) showText("无法换成路径", "这个位置没法转换成文件路径。可以改用「从文件选择」直接挑一个字体文件。");
            else saveFontDir("font.dir", path);
        }
    }

    // ───────────────────────── 排版工具 ─────────────────────────

    private TextView heading(String value, float sp) { return ink.text(value, sp, Ink.BOLD); }
    private TextView paragraph(String value, float sp) { return ink.text(value, sp, Ink.REGULAR); }
    private TextView note(String value) { TextView view = paragraph(value, 15); view.setPadding(ink.dp(4), ink.dp(24), 0, 0); return view; }
    private LinearLayout column(View... children) {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        for (View child : children) column.addView(child);
        return column;
    }
    private LinearLayout card(View content) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(ink.outline(14));
        card.setPadding(ink.dp(18), ink.dp(16), ink.dp(18), ink.dp(18));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, ink.dp(8), 0, 0);
        card.setLayoutParams(params);
        card.addView(content);
        return card;
    }
    /** 给视图加上外边距；保留调用方之后传入的 LayoutParams 宽高。 */
    private View spaced(View view, int topDp) {
        ViewGroup.LayoutParams current = view.getLayoutParams();
        LinearLayout.LayoutParams params = current instanceof LinearLayout.LayoutParams ? (LinearLayout.LayoutParams) current
                : new LinearLayout.LayoutParams(current == null ? -1 : current.width, current == null ? -2 : current.height);
        params.topMargin = ink.dp(topDp);
        view.setLayoutParams(params);
        return view;
    }
    private View withMargin(View view, int leftDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
        params.leftMargin = ink.dp(leftDp);
        view.setLayoutParams(params);
        return view;
    }
}
