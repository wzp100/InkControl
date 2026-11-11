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

/**
 * 纯原生界面，不接触系统设置、Root 或进程内 Hook 状态。
 * 所有服务调用都在进程级单线程中执行，页面只渲染后台生成的快照。
 * 「功能」分三级：分类 → 分类内功能列表 → 单项详情（说明、开启前后对比、演示与选项）。
 */
public final class MainActivity extends Activity {
    static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    static final String FEATURES = "功能", MODULE = "模块设置", INSTALL = "安装", DIAGNOSE = "诊断", ORDER = "功能列表排序", SHORTCUTS = "桌面快捷方式", ABOUT = "关于", TERMS = "__terms";
    /** 启动时直接打开「模块设置」下某一页用的 extra。 */
    static final String MODULE_EXTRA = "module";
    /** E-Ink 刷新面板的「墨控快捷设置」按钮：带 quick=1 进来，直接落到只含三个常用开关的一页。 */
    static final String QUICK_EXTRA = "quick";
    /** 面板长按「自动刷新 / 遮挡息屏」：带分类名进来，落到该大功能的分类页而不是单项详情。 */
    static final String CATEGORY_EXTRA = "category";
    /** 落到大功能页之后要滚到哪一行（面板长按「全刷扫过」用它定位到 02 那一组）。 */
    static final String FOCUS_EXTRA = "focus";
    /** 「快捷设置」页不是 Guide 里的分类（首页不列），只是借分类位渲染一个独立页面。 */
    static final String QUICK = "快捷设置";
    /** 「模块设置」下这些是页面而不是普通选项，进程重建时要保留当前页。 */
    static final List<String> MODULE_PAGES = Arrays.asList(INSTALL, DIAGNOSE, ORDER, SHORTCUTS, ABOUT);
    static final String[] TABS = {FEATURES, MODULE};
    static final int[] TAB_GLYPHS = {Ink.Glyph.FEATURES, Ink.Glyph.INSTALL};
    static final int EXPORT_REQUEST = 100;
    static final int FONT_REQUEST = 101;
    static final int FONT_DIR_REQUEST = 102;
    /** 海信主题字体目录：字体包没有扩展名，靠文件头识别 TTF/OTF。 */
    static final String FONT_DIR_DEFAULT = "/storage/emulated/0/.hmcttheme/com.hmct.theme/";
    /** 「关于」页里的外链；只跳转浏览器，不在应用里做自动更新或下载。 */
    static final String REPO = "https://github.com/wzp100/InkControl";
    static final String RELEASES = REPO + "/releases";
    static final String README_URL = REPO + "/blob/main/README.md";
    static final String LATEST_API = "https://api.github.com/repos/wzp100/InkControl/releases/latest";
    static final String AUTHOR_URL = "https://github.com/liuqianhe";
    static final String UPSTREAM_REPO = "https://github.com/wzp100/InkControl";
    static final String COMMUNITY_NUKU = "http://www.coolapk.com/u/21150263";
    static final String REFRESH_REFERENCE = "https://github.com/ChenXu178/Hisense-E-Ink-auto-refresh";
    /** 上面那个项目的分支，墨控的自动刷新按它改。 */
    static final String REFRESH_FORK = "https://github.com/liuqianhe/Hisense-E-Ink-auto-refreshs";
    /** 体感（遮挡息屏 / 移开唤醒）参考的 wave-up。 */
    static final String WAVEUP_REFERENCE = "https://gitlab.com/juanitobananas/wave-up";
    /**
     * 三个作用域一个回执都没有＝框架根本没把墨控注入任何进程。这时功能页只会顶着一句
     * 「等待运行确认」，看起来像功能写坏了，所以要在状态旁边直说原因。
     */
    static final String NOT_INJECTED = "模块未注入，请到框架管理器启用墨控并勾选三个作用域（系统框架、系统界面、桌面），改完完整重启。";

    ApplicationServices services;
    Ink ink;
    ApplicationServices.Snapshot snapshot;
    String loadError = "";
    String tab = FEATURES, category = "", featureId = "", message = "", output = "";
    /** 「模块设置」内的子页：空=入口页，否则为「安装」/「诊断」。 */
    String modulePage = "";
    boolean busy, messageFailed, outputExpanded;
    /** 正在提交的单项更改；执行期间按目标值显示，结束后以新快照为准。 */
    String pendingId = "", pendingValue = "";
    boolean pendingEnabled;
    /** 「显示信息修改」在紧凑页里是展开式多选，记着这一行是展开还是收起。 */
    boolean lockExtOpen;
    /** 面板开关由 SystemUI 转到应用进程写盘，界面快照不会自己变；靠这条观察者重读。 */
    android.database.ContentObserver panelObserver;
    /** 「关于」页检测更新的结果：留在字段里，页面重建（旋转）后仍看得到上一次结论。 */
    String aboutUpdateText = "", aboutUpdateUrl = "";
    boolean checkingUpdate;
    TextView aboutUpdateView;
    /** 详情页里正在预览、尚未使用的选项；空表示与当前设置相同。 */
    String preview = "";
    /** 字体页的完整列表、过滤词与渲染控件：搜索框只过滤显示，不重新扫描目录。 */
    List<String[]> fontItems = new ArrayList<>();    private String fontProblem = "";
    String fontAppliedMark = "";
    String fontQuery = "";
    /** 字体页当前目录：列表读取失败时「重新加载」按它重扫。 */
    String fontDir = FONT_DIR_DEFAULT;
    TextView fontHintView;
    LinearLayout fontListViews;
    boolean autoplay;
    /** 停留曲线编辑中的草稿。 */
    String holdMode = "";
    int[] holdDraft;
    /** 操作结果提示条的自动消失计时；结果条默认停留 30 秒，可在「模块设置」调整。 */
    final android.os.Handler noticeHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    int noticeSeq;
    /** 缓存「提示时长」偏好，避免每次绘制都同步读一遍存储。 */
    int noticeSeconds = UiNotice.DEFAULT;
    final Map<String, Integer> scrollPositions = new HashMap<>();
    ScrollView scroll;
    LinearLayout body;
    MotionDemo mainDemo;
    /** 预览选项后滚动到演示处，让用户立刻看到效果。 */
    boolean scrollToVisual;
    View visualView;
    /** 从面板/快捷方式进来时，落页后要滚到的那一行（功能 id）。 */
    String scrollToFocus;
    /** 面板按钮刚拖完、还没写进配置的新顺序；有值时按它画，省得等 root 应用那一两秒。 */
    String[] panelOrderPending;
    boolean panelOrderBusy;

    /** 系统设置快照列表；null 表示还没读过，读的时候用 backupsLoading 挡重复请求。 */
    List<String[]> backups;
    boolean backupsLoading;
    // 各页面的实现按功能拆到独立的类里；这里只持有它们，状态与通用控件仍在本类。
    final SysAppPages sysAppPages = new SysAppPages(this);
    final PanelPages panelPages = new PanelPages(this);
    final ListEditors listEditors = new ListEditors(this);
    final FontPages fontPages = new FontPages(this);
    final HoldEditor holdEditors = new HoldEditor(this);
    final AboutPages aboutPages = new AboutPages(this);
    final ShortcutPages shortcutPages = new ShortcutPages(this);
    final BackupPages backupPages = new BackupPages(this);

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
            if (!modulePage.isEmpty() && !MODULE_PAGES.contains(modulePage)) modulePage = "";
            category = state.getString("category", ""); featureId = state.getString("feature", "");
            if (!featureId.isEmpty()) {
                featureId = Guide.destination(featureId);
                category = FeatureCatalog.get(featureId).group;
                // 直落功能页的分类（字体、快捷面板修改）没有分类页：首页点卡片是 navigate("", 单项)，
                // 返回也该直接回大功能列表。进程重建若把 category 恢复成 group 名，
                // 系统返回就会落到一个平时根本不会显示的中间分区页，得多按一次返回；
                // 这里对齐首页入口的状态。
                Guide.Category restored = Guide.CATEGORIES.get(category);
                if (restored != null && landingFeature(restored) != null) category = "";
            }
        }
        // 进程重建（旋转、内存回收）时 state 里已经带着上次的位置，不再拿启动 intent 覆盖。
        if (state == null) {
            moduleTarget(getIntent());
            shortcutTarget(getIntent());
            quickTarget(getIntent());
            categoryTarget(getIntent());
        }
        // 面板点一下开关改的是同一份配置，但发起方是系统界面；App 里拿的是打开页面时的
        // 旧快照。只监听面板那条专用通知，App 自己保存时不会触发，避免和提交流程打架。
        panelObserver = new android.database.ContentObserver(noticeHandler) {
            @Override public void onChange(boolean selfChange, android.net.Uri uri) { refreshFromPanel(); }
        };
        getContentResolver().registerContentObserver(
                android.net.Uri.withAppendedPath(com.inkcontrol.platform.ConfigProvider.URI, "panel-toggle"),
                false, panelObserver);
        load();
        render();
        // 长按桌面图标弹出的列表跟着配置走：后台重建一次，内容没变时内部会直接跳过。
        WORKER.execute(() -> UiShortcuts.apply(this));
    }

    /** 直接打开「模块设置」下的某一页（快捷面板里的「去设置」用）。 */
    void moduleTarget(android.content.Intent intent) {
        if (intent == null) return;
        String page = intent.getStringExtra(MODULE_EXTRA);
        if (page == null || !MODULE_PAGES.contains(page)) return;
        tab = MODULE; modulePage = page; category = ""; featureId = "";
    }

    /** 从桌面快捷方式进来：直接落到对应功能页，返回时回到该功能的分类而不是首页。 */
    void shortcutTarget(android.content.Intent intent) {
        if (intent == null || !UiShortcuts.ACTION.equals(intent.getAction())) return;
        String id = intent.getStringExtra(UiShortcuts.EXTRA);
        if (id == null || id.isEmpty()) return;
        // 「刷新格言」是就地动作：发广播让系统界面换下一条，不跳页、也不改当前页面。
        // 新注册的桌面快捷方式已直接指向透明的 MottoActivity，这里只为升级前留下的旧入口兜底。
        if (UiShortcuts.MOTTO.equals(id)) {
            com.inkcontrol.features.lock.MottoCommand.request(this);
            dismissNotice();
            message = "已刷新锁屏格言，锁一次屏就能看到新的";
            messageFailed = false;
            scheduleNoticeDismiss();
            return;
        }
        String target = Guide.destination(id);
        FeatureDefinition definition;
        try { definition = FeatureCatalog.get(target); }
        catch (RuntimeException unknown) { return; }
        tab = FEATURES; modulePage = ""; featureId = target; category = definition.group;
        Guide.Category restored = Guide.CATEGORIES.get(category);
        if (restored != null && landingFeature(restored) != null) category = "";
    }

    /** 从 E-Ink 刷新面板的「墨控快捷设置」按钮进来：落到遮挡息屏 / 锁屏自定义 / 自动刷新那一页。 */
    void quickTarget(android.content.Intent intent) {
        if (intent == null || !"1".equals(intent.getStringExtra(QUICK_EXTRA))) return;
        tab = FEATURES; modulePage = ""; category = QUICK; featureId = "";
    }

    /**
     * 面板长按「自动刷新 / 遮挡息屏」进来：落到对应大功能的分类页，而不是某一项的说明页。
     * 分类名从功能目录取，跟首页卡片用的是同一份名字；只认真实存在的分类，避免落到空页。
     */
    void categoryTarget(android.content.Intent intent) {
        if (intent == null) return;
        String name = intent.getStringExtra(CATEGORY_EXTRA);
        if (name == null || !Guide.CATEGORIES.containsKey(name)) return;
        tab = FEATURES; modulePage = ""; category = name; featureId = "";
        // 面板长按「全刷扫过」这类：落到大功能页后还要滚到指定那一行。
        String focus = intent.getStringExtra(FOCUS_EXTRA);
        scrollToFocus = focus == null || focus.isEmpty() ? null : focus;
    }

    @Override protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent == null) return;
        if (intent.getStringExtra(MODULE_EXTRA) != null) {
            moduleTarget(intent); scroll = null; render(); return;
        }
        if (intent.getStringExtra(QUICK_EXTRA) != null) {
            quickTarget(intent); scroll = null; render(); return;
        }
        if (intent.getStringExtra(CATEGORY_EXTRA) != null) {
            categoryTarget(intent); scroll = null; render(); return;
        }
        if (!UiShortcuts.ACTION.equals(intent.getAction())) return;
        shortcutTarget(intent);
        scroll = null;
        render();
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("tab", tab); state.putString("category", category); state.putString("feature", featureId);
        state.putString("module", modulePage);
    }

    @Override public void onConfigurationChanged(Configuration configuration) { super.onConfigurationChanged(configuration); render(); }
    @Override protected void onDestroy() {
        dismissNotice();
        if (panelObserver != null) getContentResolver().unregisterContentObserver(panelObserver);
        super.onDestroy();
    }
    @Override public void onBackPressed() {
        if (!featureId.isEmpty()) {
            // 直落功能页的分类没有分类页可回：无论 category 因什么路径被设成它，
            // 系统返回都直接回大功能列表，不落在平时不会显示的中间分区页。
            String back = category;
            Guide.Category from = Guide.CATEGORIES.get(back);
            if (from != null && landingFeature(from) != null) back = "";
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
    void load() {
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

    /**
     * 面板点一下开关后，配置已经落盘、后端也已经应用，只有 App 里的 snapshot 还是旧的。
     * 重跑一次 inspect 让运行回执跟上（详情页状态别停在「等待运行确认」），再比对快照，
     * 有变化才重画，免得连续点击时反复重建页面、丢掉滚动位置。
     */
    void refreshFromPanel() {
        if (isFinishing() || isDestroyed()) return;
        if (busy) { noticeHandler.postDelayed(this::refreshFromPanel, 900); return; }
        WORKER.execute(() -> {
            ApplicationServices.Snapshot next;
            try {
                services.inspect();
                next = services.snapshot();
            } catch (Exception ignored) { return; }
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (snapshot != null && snapshot.config.text().equals(next.config.text())
                        && snapshot.statuses.equals(next.statuses)) return;
                snapshot = next;
                render();
            });
        });
    }

    // ───────────────────────── 页面骨架 ─────────────────────────

    String pageKey() { return tab + "|" + category + "|" + featureId; }

    void render() {
        if (scroll != null) scrollPositions.put(pageKey(), scroll.getScrollY());
        mainDemo = null; visualView = null; aboutUpdateView = null;
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
                else if (modulePage.equals(SHORTCUTS)) shortcutPages.shortcutPage();
                else if (modulePage.equals(ABOUT)) aboutPages.aboutPage();
                // 名词解释从「模块设置」点进去时 tab 还是 MODULE，原先把 TAB 分支写在前面，
                // 结果整页仍画成模块设置首页，看起来就是「点了没反应」，切到「功能」才显示。
                else if (category.equals(TERMS)) glossary();
                else moduleSettings();
            } else if (!loadError.isEmpty()) {
                body.addView(spaced(paragraph("出错了先从这里处理：进「诊断」查看详情，或在「安装」重新导出 Magisk 模块 ZIP。", 14), 12));
                moduleSettings();
            }
            else if (category.equals(TERMS)) glossary();
            else if (category.equals(QUICK)) quickPage();
            else if (!featureId.isEmpty()) featurePage(FeatureCatalog.get(featureId));
            else if (!category.isEmpty()) categoryPage(Guide.CATEGORIES.get(category));
            else home();
        }

        // 在首次绘制前恢复滚动位置；post 会先画顶部再跳回，墨水屏上多一次整屏刷新。
        Integer saved = scrollPositions.get(pageKey());
        View anchor = scrollToVisual ? visualView : null;
        scrollToVisual = false;
        // 从面板长按进来时优先滚到指定的那一行（比恢复上次位置更符合来意）。
        View focus = scrollToFocus == null ? null : findTagged(body, scrollToFocus);
        scrollToFocus = null;
        if (anchor != null || focus != null || saved != null && saved > 0) {
            ScrollView target = scroll;
            int y = saved == null ? 0 : saved;
            target.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
                @Override public boolean onPreDraw() {
                    target.getViewTreeObserver().removeOnPreDrawListener(this);
                    int to = focus != null ? offsetInBody(focus) - ink.dp(12)
                            : anchor != null ? anchor.getTop() - ink.dp(8) : y;
                    target.scrollTo(0, Math.max(0, to));
                    return true;
                }
            });
        }
        if (autoplay && mainDemo != null) { MotionDemo demo = mainDemo; demo.post(demo::play); }
        autoplay = false;
    }

    View header() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(ink.dp(20), ink.dp(14), ink.dp(16), ink.dp(10));
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        String title, subtitle, back = "";
        boolean isHome = tab.equals(FEATURES) && category.isEmpty() && featureId.isEmpty();
        // 直落功能页的分类会跳过分类页，此时 category 为空，仍算子页；
        // 名词解释从「模块设置」进入（tab 仍是 MODULE），也按子页画标题与返回。
        boolean sub = (tab.equals(FEATURES) && !isHome) || category.equals(TERMS);
        boolean about = tab.equals(MODULE) && modulePage.equals(ABOUT);
        if (about) { title = "关于"; subtitle = "版本、作者、来源与更新。"; back = "模块设置"; }
        else if (sub && category.equals(TERMS)) { title = "名词解释"; subtitle = "界面里出现的专业词是什么意思。"; back = "模块设置"; }
        else if (sub && !featureId.isEmpty()) {
            FeatureDefinition f = FeatureCatalog.get(featureId);
            title = f.title; subtitle = Guide.get(f.id).summary.isEmpty() ? f.description : Guide.get(f.id).summary;
            back = category.isEmpty() ? "功能" : category;
        } else if (sub) {
            title = category;
            // 「快捷设置」是面板按钮进来的自造页，不在 Guide 分类表里，别在这里取 intro 抛空指针。
            Guide.Category known = Guide.CATEGORIES.get(category);
            subtitle = known != null ? known.intro
                    : "遮挡息屏、锁屏自定义与自动刷新的常用开关。";
            back = category.equals("实验室") ? "模块设置" : "功能";
        }
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
            // 「墨水屏增强 · 全部默认关闭」说明已删除，默认关闭的口径写在 README 与发布说明里）。
            LinearLayout brand = new LinearLayout(this);
            brand.setGravity(Gravity.CENTER_VERTICAL);
            brand.addView(ink.text(title, 30, Ink.BOLD));
            TextView version = ink.text("v" + BuildInfo.VERSION, 13, Ink.REGULAR);
            version.setPadding(ink.dp(12), ink.dp(2), 0, 0);
            version.setContentDescription(ink.t("版本 " + BuildInfo.VERSION));
            brand.addView(version);
            titles.addView(brand);
        } else if (isLockTheme()) {
            // 标题与「刷新格言」并排：徽标若挂在整条标题行（含「‹ 功能」）右侧，
            // 会跟两行整体居中，看起来比标题偏上。
            LinearLayout line = new LinearLayout(this);
            line.setGravity(Gravity.CENTER_VERTICAL);
            line.addView(ink.text(title, 26, Ink.BOLD), new LinearLayout.LayoutParams(0, -2, 1));
            line.addView(mottoBadge());
            titles.addView(line);
        } else {
            titles.addView(ink.text(title, sub || about ? 26 : 30, Ink.BOLD));
        }
        row.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));

        if (isHome) {
            String label;
            boolean filled = false;
            if (snapshot == null) label = busy ? "读取中" : "未读取";
            else if (!snapshot.checked()) label = snapshot.failure.equals("尚未检查环境") ? "未检查" : "环境异常";
            else if (snapshot.ready()) { label = "● 就绪"; filled = true; }
            else label = "未就绪";
            // 右上角一排小按钮：「关于」是入口，「就绪」是环境状态。两者样式一致，
            // 点「就绪」仍然按老规矩进诊断页。
            LinearLayout badges = new LinearLayout(this);
            badges.setGravity(Gravity.CENTER_VERTICAL);
            TextView aboutBadge = ink.text("关于", 13, Ink.MEDIUM);
            aboutBadge.setPadding(ink.dp(12), ink.dp(7), ink.dp(12), ink.dp(7));
            aboutBadge.setBackground(ink.pressable(ink.outline(16), ink.solid(16)));
            aboutBadge.setTextColor(Ink.pressedColors(Ink.INK, Ink.PAPER));
            aboutBadge.setContentDescription(ink.t("打开关于"));
            aboutBadge.setOnClickListener(v -> openModule(ABOUT));
            badges.addView(aboutBadge);
            TextView badge = ink.text(label, 13, Ink.MEDIUM);
            badge.setPadding(ink.dp(12), ink.dp(7), ink.dp(12), ink.dp(7));
            badge.setBackground(filled ? ink.solid(16) : ink.outline(16));
            badge.setTextColor(filled ? Ink.PAPER : Ink.INK);
            badge.setContentDescription(ink.t("环境状态：" + label));
            badge.setOnClickListener(v -> openModule(DIAGNOSE));
            LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-2, -2);
            statusParams.leftMargin = ink.dp(8);
            badges.addView(badge, statusParams);
            row.addView(badges);
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
    static boolean isFontNode(String id) { return id.startsWith("font."); }

    /**
     * 没有开关、点进去只做修改的页内操作项：字体（挑字体、应用）与「显示信息修改」
     * （把设备状态的四项显示开关收进一页）。它们不计入「已开启 / 总数」。
     */
    static boolean isPageNode(String id) { return isFontNode(id) || id.equals("lock.ext.edit"); }

    /** 「显示信息修改」页里的四项显示开关，顺序即锁屏上从左到右的顺序。 */
    static final String[] EXT_ITEMS = {"lock.ext.wifi", "lock.ext.bt", "lock.ext.audio", "lock.ext.airplane"};

    /**
     * 点大功能卡片不经过分类列表、直接落到某个功能页的分类：
     * 字体那一页全是页内操作；「快捷面板修改」原来中间那页只摆一个开关，四格编辑器全收在
     * 「快捷面板按钮」页里，多一层没有内容的列表没有意义。返回值同时让返回跳过这一层。
     */
    static String landingFeature(Guide.Category c) {
        String only = "";
        int count = 0;
        for (String[] section : c.sections) for (int i = 1; i < section.length; i++) { count++; only = section[i]; }
        if (count != 1) return null;
        return isFontNode(only) || only.equals("qs.panel") ? only : null;
    }

    /** 标题下方的单行状态：只留「待重启 / 异常」，不再统计开启数量。 */
    View headerStats() {
        if (snapshot == null) return null;
        int pending = 0, faults = 0;
        String firstFault = "";
        for (FeatureDefinition feature : FeatureCatalog.all()) {
            if (isPageNode(feature.id)) continue;
            String status = status(feature.id);
            if (status.equals("待重启")) pending++;
            if (status.startsWith("异常")) {
                faults++;
                if (firstFault.isEmpty()) firstFault = feature.id;
            }
        }
        if (pending == 0 && faults == 0) return null;
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
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
    View statInline(String number, String label) {
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
    View messageBar() {
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
    void scheduleNoticeDismiss() {
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
    void dismissNotice() {
        noticeHandler.removeCallbacksAndMessages(null);
        noticeSeq++;
    }

    View navigation() {
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

    void switchTab(String next) {
        if (next.equals(tab)) {
            // 再点一次「功能」回到分类首页；「模块设置」回到它的入口页。
            // 「字体」「快捷面板修改」这类没有中间分类页的大功能是直接落到功能页的，
            // 此时 category 为空、只有 featureId，所以两者都要算「不在首页」。
            if (next.equals(FEATURES) && (!category.isEmpty() || !featureId.isEmpty())) navigate("", "");
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
    void openModule(String page) {
        modulePage = page;
        if (tab.equals(MODULE)) render();
        else switchTab(MODULE);
    }
    void navigate(String nextCategory, String nextFeature) {
        if (scroll != null) scrollPositions.put(pageKey(), scroll.getScrollY());
        if (!nextFeature.equals(featureId)) { preview = ""; holdMode = ""; holdDraft = null; }
        // 进入新的下一级页面从顶部开始；返回上一级时保留原位置。
        if (!nextFeature.isEmpty()) scrollPositions.remove(tab + "|" + nextCategory + "|" + nextFeature);
        category = nextCategory; featureId = nextFeature;
        scroll = null;
        render();
    }
    void openFeature(String id) {
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

    void home() {
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
            // 只保留异常提示；开启数量与总数不再统计、也不再展示。
            int bad = 0;
            for (String[] section : c.sections) for (int i = 1; i < section.length; i++) {
                if (isPageNode(section[i])) continue;
                if (status(section[i]).startsWith("异常")) bad++;
            }
            for (String id : c.nested) if (status(id).startsWith("异常")) bad++;
            // 单入口分类（字体、快捷面板修改）跳过中间那层只有一个条目的列表，点卡片直接进详情页
            String landing = landingFeature(c);
            View.OnClickListener open = v -> {
                if (landing != null) navigate("", landing); else navigate(c.name, "");
            };
            body.addView(spaced(categoryCard(c, bad, open), 12));
        }

        body.addView(spaced(ink.button("检查当前生效状态", Ink.Kind.PRIMARY, v -> perform("检查生效状态", () -> services.inspect(), false)), 28));
        body.addView(spaced(ink.button("关闭全部增强", Ink.Kind.DANGER, v -> confirm("关闭全部增强", "保留各项选择，并恢复墨控接管的行为。启动期覆盖需完整重启后才完全恢复。",
                () -> perform("关闭全部增强", () -> { services.disableAll(); return "已提交关闭；请完整重启并检查。"; }, true))), 12));
    }

    /** 分类入口卡：只有分类名、一行简介和异常提示，不再统计「已开启 / 总数」。 */
    View categoryCard(Guide.Category c, int bad, View.OnClickListener click) {
        LinearLayout card = new LinearLayout(this);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(ink.dp(18), ink.dp(16), ink.dp(14), ink.dp(16));
        card.setBackground(ink.pressable(ink.outline(14), ink.box(Ink.PAPER, Ink.INK, 3, 14)));
        LinearLayout content = column();
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.addView(ink.text(c.name, 20, Ink.BOLD));
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

    View navCard(String title, String text, String meta, boolean strong, String description, View.OnClickListener click) {
        return navCard(title, text, meta, strong, description, click, true);
    }

    /**
     * {@code framed} 为假时这张卡片自己不画外框：它被放进带 {@code outline(12)} 描边的分组列表里，
     * 再叠一层圆角 14 的框会在四角错开、直边叠成 4px，看着像「方框里套了个扁圆框」。
     * 采用与普通功能行同一套处理：常态透明露出分组描边，按下时加粗框（圆角 12 与外框一致）。
     */
    View navCard(String title, String text, String meta, boolean strong, String description,
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

    void glossary() {
        for (Map.Entry<String, String> term : Guide.TERMS.entrySet()) body.addView(spaced(termView(term.getKey(), term.getValue()), 16));
    }
    View termView(String term, String text) {
        return column(ink.text(term, 16, Ink.BOLD), spaced(paragraph(text, 14), 4));
    }

    // ───────────────────────── 功能：分类页 ─────────────────────────

    // ───────────────────────── 系统应用卸载 / 还原 ─────────────────────────

    /** 首页分类名；这一页是独立界面，不走功能开关列表（sections 为空，渲染在此特判）。 */
    static final String SYSAPP_CATEGORY = "系统应用卸载/还原";
    /** 预置的系统应用：{包名, 显示名}，顺序即页面顺序。 */
    static final String[][] SYS_APPS = {
            {"com.hmct.updater", "系统更新"},
            {"com.tencent.android.location", "腾讯位置服务"},
            // 只在配置工作资料 / 设备所有者 / 影子应用时才用到；冻结后进程即消失且不会回来。
            // 另外两个常驻进程（com.hmct.tether、com.android.modemnotifier）是 android:persistent 组件，
            // 冻结压不住，运行期卸载还会掀掉 system_server，所以刻意不放进这个列表。
            {"com.android.managedprovisioning", "工作设置"},
    };
    /** 包名 → installed / removed / absent；没有条目表示还没读到。 */
    final Map<String, String> sysAppStates = new java.util.HashMap<>();
    String sysAppPageKey = "";

    void categoryPage(Guide.Category c) {
        if (SYSAPP_CATEGORY.equals(c.name)) { sysAppPages.sysAppPage(); return; }
        // 翻页动画有自己的编号小节与阅读器卡片，其余大功能一律走通用紧凑页。
        if (c.name.equals("翻页动画")) { pageAnimPage(); return; }
        compactCategoryPage(c);
    }

    /**
     * 全局大功能页：分区标题（带编号）＋ 下拉／单行开关，说明文字全部收进详情页。
     * 页内操作、名单编辑、带预览或自定义输入的项留一个「名称 ›」入口，不丢功能。
     */
    void compactCategoryPage(Guide.Category c) {
        // 只有一个分组的分类（体感）不再画「01 行为」这种编号小标题：页头已经说明这一页是什么，
        // 编号只剩一个反而占掉一行。
        boolean single = c.sections.length == 1;
        int sectionNo = 0;
        for (String[] section : c.sections) {
            sectionNo++;
            if (!single) {
                TextView head = ink.text(String.format(Locale.ROOT, "%02d %s", sectionNo, section[0]), 20, Ink.BOLD);
                head.setPadding(ink.dp(4), ink.dp(20), 0, ink.dp(10));
                body.addView(head);
            }
            LinearLayout list = new LinearLayout(this);
            list.setOrientation(LinearLayout.VERTICAL);
            list.setBackground(ink.outline(12));
            int rows = 0;
            for (int i = 1; i < section.length; i++) {
                String id = section[i];
                FeatureDefinition f = FeatureCatalog.get(id);
                // 「显示信息修改」是四项多选，直接在页里展开勾选，不再单开一页来回跳。
                // 「锁屏倒数日」既是开关又有自己的编辑页：右侧给状态与开关，点标题仍可进去编辑。
                View row = id.equals("lock.ext.edit") ? lockExtBlock()
                        : "lock.countdown".equals(id) ? compactToggleRow(id, true)
                        : compactSpecial(id) ? entryRow(f)
                        : realValues(f).size() > 1 ? dropdownRow(f, f.title) : smallToggleRow(id);
                if (rows++ > 0) list.addView(ink.rule(1));
                // 打个 tag：面板长按「全刷扫过」进来时要滚到这一行（见 FOCUS_EXTRA）。
                row.setTag(id);
                list.addView(row);
            }
            body.addView(single ? spaced(list, 14) : list);
        }
        if (c.name.equals("桌面")) body.addView(spaced(ink.button("还原默认设置", Ink.Kind.SECONDARY,
                v -> confirm("还原默认设置", "只把桌面主题、图标、字体、壁纸和组件恢复到默认，不改动桌面布局与图标位置。",
                        () -> perform("还原桌面设置", () -> { services.restoreGroup("桌面"); return "已提交还原；请回到桌面确认。"; }, true))), 20));
    }

    /** 锁屏主题的分类页：标题行右上角挂「刷新格言」，页内不再单列一块。 */
    boolean isLockTheme() {
        return tab.equals(FEATURES) && featureId.isEmpty() && "锁屏显示".equals(category);
    }

    /** 「刷新格言」小徽标：与首页「关于」同一套样式，点一下让原厂格言往前跳一条。 */
    TextView mottoBadge() {
        TextView badge = ink.text("刷新格言", 13, Ink.MEDIUM);
        badge.setPadding(ink.dp(12), ink.dp(7), ink.dp(12), ink.dp(7));
        badge.setBackground(ink.pressable(ink.outline(16), ink.solid(16)));
        badge.setTextColor(Ink.pressedColors(Ink.INK, Ink.PAPER));
        badge.setContentDescription(ink.t("刷新系统的格言"));
        badge.setOnClickListener(v -> com.inkcontrol.features.lock.MottoCommand.request(this));
        return badge;
    }

    /** 这些项有自己的编辑页／预览／名单，紧凑行放不下，给一个入口即可。 */
    static boolean compactSpecial(String id) {
        FeatureDefinition f = FeatureCatalog.get(id);
        return isPageNode(id) || id.equals("sys.backup") || id.equals("lock.countdown")
                || id.equals("lock.style") || id.equals("desktop.theme") || id.equals("desktop.icons")
                || id.equals("desktop.wallpaper") || id.equals("desktop.widget")
                || id.equals("qs.panel") || id.equals("refresh.apps") || id.equals("experiment.cache")
                || f.acceptsPanel() || f.acceptsApps() || f.acceptsPackages()
                || f.acceptsPath() || f.acceptsFont() || f.acceptsCountdowns() || f.acceptsPackage()
                || DisplayAlgorithms.algorithmFeature(id);
    }

    /** 「名称 ›」入口行：点进原详情页，右侧顺带显示当前值。 */
    View entryRow(FeatureDefinition f) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(ink.dp(14), ink.dp(11), ink.dp(12), ink.dp(11));
        row.setBackground(ink.pressable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT),
                ink.box(Ink.PAPER, Ink.INK, 2, 12)));
        row.addView(ink.text(f.title, 16, Ink.MEDIUM), new LinearLayout.LayoutParams(0, -2, 1));
        if (realValues(f).size() > 1)
            row.addView(withMargin(ink.text(Guide.optionName(f, snapshot.config.value(f.id)), 13, Ink.REGULAR), 6));
        row.addView(withMargin(ink.text("›", 24, Ink.REGULAR), 8));
        row.setOnClickListener(v -> openFeature(f.id));
        return row;
    }

    /**
     * 「显示信息修改」：点标题就地展开四项，每项前面一个方框，勾一项存一项。
     * 收起时右侧显示「2 / 4」，跟下拉行一样只占一行，不占版面。
     */
    View lockExtBlock() {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        int on = 0;
        for (String id : EXT_ITEMS) if (snapshot.config.requested(id)) on++;
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(ink.dp(14), ink.dp(13), ink.dp(12), ink.dp(13));
        head.addView(ink.text("显示信息修改", 17, Ink.MEDIUM), new LinearLayout.LayoutParams(0, -2, 1));
        head.addView(ink.text(on + " / " + EXT_ITEMS.length + (lockExtOpen ? " ▴" : " ▾"), 13, Ink.REGULAR));
        head.setContentDescription(ink.t("显示信息修改，已选 " + on + " 项" + (lockExtOpen ? "，已展开" : "，点开可勾选")));
        head.setOnClickListener(v -> { lockExtOpen = !lockExtOpen; render(); });
        block.addView(head);
        if (!lockExtOpen) return block;
        for (String id : EXT_ITEMS) {
            block.addView(ink.rule(1));
            block.addView(lockExtItem(FeatureCatalog.get(id)));
        }
        block.addView(ink.rule(1));
        // 逐项说明与「图标版 / 文字版」还在原来那一页，留个入口，别把那条路堵死。
        TextView more = ink.text("显示方式与说明 ›", 14, Ink.MEDIUM);
        more.setPadding(ink.dp(26), ink.dp(12), ink.dp(12), ink.dp(12));
        more.setOnClickListener(v -> openFeature("lock.ext.edit"));
        block.addView(more);
        return block;
    }

    /** 展开后的一行：方框 + 名称，点一下勾选／取消并立即保存。 */
    View lockExtItem(FeatureDefinition f) {
        boolean on = snapshot.config.requested(f.id);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(ink.dp(26), ink.dp(11), ink.dp(12), ink.dp(11));
        row.setBackground(ink.pressable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT),
                ink.box(Ink.PAPER, Ink.INK, 1.5f, 0)));
        row.addView(checkBox(on));
        row.addView(ink.text(f.title, 16, on ? Ink.MEDIUM : Ink.REGULAR), new LinearLayout.LayoutParams(0, -2, 1));
        row.setContentDescription(ink.t(f.title + (on ? "，已勾选" : "，未勾选")));
        row.setOnClickListener(v -> {
            boolean next = !on;
            String value = snapshot.config.value(f.id);
            if (!begin(f.id, next, value)) return;
            perform((next ? "勾选「" : "取消「") + f.title + "」", () -> {
                change(f.id, next, value);
                return "已保存「" + f.title + "」：" + (next ? "显示" : "不显示");
            }, false);
        });
        return row;
    }

    /** 自绘方框：系统 CheckBox 在灰度墨水屏上靠不住，实心就代表已勾选。 */
    View checkBox(boolean checked) {
        View box = new View(this);
        box.setBackground(checked ? ink.box(Ink.INK, Ink.INK, 2, 3) : ink.box(Ink.PAPER, Ink.INK, 2, 3));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ink.dp(18), ink.dp(18));
        params.setMargins(0, 0, ink.dp(14), 0);
        box.setLayoutParams(params);
        return box;
    }

    /**
     * 「快捷设置」页：E-Ink 刷新面板的「墨控快捷设置」按钮进来。
     *
     * <p>只放三个最常用的入口——遮挡息屏、锁屏主题、自动刷新——开关就地能改，点进去分别是
     * 完整的功能页（延时、版式、触发次数等细项都在那边）。首页的分类列表不列这一页。
     */
    void quickPage() {
        body.addView(card(column(paragraph("常用三项放在一页，开关直接改；点进每一项可以调细节。", 14))));
        String[] ids = {"wave.lock", "lock.style", "refresh.master"};
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setBackground(ink.outline(12));
        for (int i = 0; i < ids.length; i++) {
            if (i > 0) list.addView(ink.rule(1));
            list.addView(featureRow(FeatureCatalog.get(ids[i])));
        }
        body.addView(spaced(list, 18));
        body.addView(spaced(paragraph("遮挡息屏关闭屏幕后要按电源键唤醒；锁屏显示与自动刷新在锁屏、系统界面里生效。", 13), 12));
    }

    /**
     * 「翻页动画」专用页：参数用下拉、操作用单行开关，一屏就能看完。
     * 对齐原插件「02 选择翻页风格」那种紧凑排法，不再铺「它做什么」＋六张选项卡片。
     */
    void pageAnimPage() {
        LinearLayout master = new LinearLayout(this);
        master.setOrientation(LinearLayout.VERTICAL);
        master.setBackground(ink.outline(12));
        master.addView(compactToggleRow("pageanim.master"));
        body.addView(spaced(master, 14));

        body.addView(spaced(ink.text("01 选择翻页风格", 20, Ink.BOLD), 22));
        LinearLayout choices = new LinearLayout(this);
        choices.setOrientation(LinearLayout.VERTICAL);
        choices.setBackground(ink.outline(12));
        String[][] picks = {{"pageanim.style", "翻页风格"}, {"pageanim.spacing", "展开速度"},
                {"pageanim.bands", "分块数量"}};
        for (int i = 0; i < picks.length; i++) {
            if (i > 0) choices.addView(ink.rule(1));
            choices.addView(dropdownRow(FeatureCatalog.get(picks[i][0]), picks[i][1]));
        }
        body.addView(choices);

        body.addView(spaced(ink.text("02 翻页操作", 20, Ink.BOLD), 22));
        LinearLayout ops = new LinearLayout(this);
        ops.setOrientation(LinearLayout.VERTICAL);
        ops.setBackground(ink.outline(12));
        String[] toggles = {"pageanim.reverse", "pageanim.vkey"};
        for (int i = 0; i < toggles.length; i++) {
            if (i > 0) ops.addView(ink.rule(1));
            ops.addView(smallToggleRow(toggles[i]));
        }
        body.addView(ops);

        body.addView(spaced(ink.text("03 目标阅读器", 20, Ink.BOLD), 22));
        FeatureDefinition readers = FeatureCatalog.get("pageanim.readers");
        String current = snapshot.config.value(readers.id);
        List<String> chosen = ListEditors.plainList(current);
        LinearLayout box = column(
                ink.text(chosen.isEmpty() ? "未选择阅读器" : "目标阅读器", 15, Ink.MEDIUM),
                spaced(paragraph(chosen.isEmpty() ? "不选则动画不触发；上下滚动仍走原厂刷新。" : String.join("、", chosen), 13), 5));
        box.addView(spaced(ink.button("选择阅读器", Ink.Kind.SECONDARY,
                v -> listEditors.appPickerDialog(readers, snapshot.config.requested(readers.id), current, true)), 10));
        body.addView(card(box));
    }

    /** 一行「标签 + 当前值 ▾」：点右侧出单选列表，点标签进原详情页（自定义值、预览都在那里）。 */
    View dropdownRow(FeatureDefinition f, String label) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(ink.dp(14), ink.dp(13), ink.dp(12), ink.dp(13));
        TextView title = ink.text(label, 17, Ink.MEDIUM);
        title.setOnClickListener(v -> openFeature(f.id));
        row.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        String value = snapshot.config.value(f.id);
        row.addView(ink.chip(Guide.optionName(f, value) + " ▾", false, v -> showOptionDialog(f, label)));
        return row;
    }

    /**
     * 单选列表：当前选中的那一项上下各压一条 2dp 粗线「双下划线包裹」，
     * 不靠勾选图标或高亮底色（墨水屏上灰度不可靠）。选完即保存，不用预览＋使用两步。
     * 允许手动输入数值的项把「自定义」放在最上面；已经输入过就直接显示「4次(自定义)」。
     */
    void showOptionDialog(FeatureDefinition f, String label) {
        List<String> values = realValues(f);
        // 数值项的预设一律按数值从小到大排（遮挡延时、周期性刷新都是）。目录里的首值
        // 仍是默认档，显示顺序不影响默认值；比如周期性刷新默认 5 分钟，列表仍从 1 分钟起。
        if (f.acceptsNumber())
            Collections.sort(values, (a, b) -> {
                Integer left = FeatureDefinition.number(a), right = FeatureDefinition.number(b);
                return left == null || right == null ? 0 : Integer.compare(left, right);
            });
        String current = snapshot.config.value(f.id);
        boolean customValue = f.acceptsNumber() && FeatureDefinition.number(current) != null && !values.contains(current);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, ink.dp(6), 0, ink.dp(6));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(ink.t(label)).setView(list).create();
        if (f.acceptsNumber()) {
            if (customValue) list.addView(optionMark());
            TextView custom = ink.text(customValue ? Guide.optionName(f, current) : "自定义", 17,
                    customValue ? Ink.BOLD : Ink.REGULAR);
            custom.setPadding(ink.dp(22), ink.dp(11), ink.dp(22), ink.dp(11));
            custom.setMinHeight(ink.dp(44));
            custom.setGravity(Gravity.CENTER_VERTICAL);
            custom.setContentDescription(ink.t("自定义 " + f.title));
            custom.setOnClickListener(v -> {
                dialog.dismiss();
                customNumberDialog(f, snapshot.config.requested(f.id), customValue ? current : "");
            });
            list.addView(custom);
            if (customValue) list.addView(optionMark());
        }
        for (String value : values) {
            boolean selected = value.equals(current);
            if (selected) list.addView(optionMark());
            TextView item = ink.text(Guide.optionName(f, value), 17, selected ? Ink.BOLD : Ink.REGULAR);
            item.setPadding(ink.dp(22), ink.dp(11), ink.dp(22), ink.dp(11));
            item.setMinHeight(ink.dp(44));
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setOnClickListener(v -> { dialog.dismiss(); use(f, value); });
            list.addView(item);
            if (selected) list.addView(optionMark());
        }
        dialog.show();
    }

    /** 选中项上下的那条粗线：2dp 实线，颜色与正文一致。 */
    View optionMark() {
        View line = new View(this);
        line.setBackgroundColor(Ink.INK);
        line.setLayoutParams(new LinearLayout.LayoutParams(-1, ink.dp(2)));
        return line;
    }

    /** 紧凑开关行：只留名字和 Switch，点名字仍可进原详情页。 */
    View smallToggleRow(String id) {
        FeatureDefinition f = FeatureCatalog.get(id);
        boolean requested = snapshot.config.requested(id);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(ink.dp(14), ink.dp(10), ink.dp(12), ink.dp(10));
        TextView title = ink.text(f.title, 16, Ink.MEDIUM);
        title.setOnClickListener(v -> openFeature(id));
        row.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(toggle(f, requested, requested));
        return row;
    }

    View compactToggleRow(String id) { return compactToggleRow(id, false); }

    /**
     * 单行「标题 + 状态 + 开关」：状态（已生效 / 待重启 / 异常）挤到开关左侧，不再单独占一行。
     * 翻页动画总开关这类只有开关、没有参数的项用它，比三行的 {@link #featureRow} 省一半高度。
     * chevron=true 时右侧再加一个「›」，表示点标题还能进详情页（锁屏倒数日这种带编辑页的项）。
     */
    View compactToggleRow(String id, boolean chevron) {
        FeatureDefinition f = FeatureCatalog.get(id);
        boolean requested = snapshot.config.requested(id);
        boolean pending = busy && id.equals(pendingId);
        boolean shown = pending ? pendingEnabled : requested;
        String status = pending ? "正在保存" : status(id);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(ink.dp(14), ink.dp(9), ink.dp(10), ink.dp(9));
        TextView title = ink.text(f.title, 16, Ink.MEDIUM);
        title.setContentDescription(ink.t(f.title + "，打开详情"));
        title.setOnClickListener(v -> openFeature(id));
        row.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        TextView state = ink.text(glyph(status) + " " + status, 12,
                status.equals("已生效") ? Ink.BOLD : Ink.REGULAR);
        LinearLayout.LayoutParams stateParams = new LinearLayout.LayoutParams(-2, -2);
        stateParams.rightMargin = ink.dp(10);
        row.addView(state, stateParams);
        row.addView(toggle(f, shown, requested));
        if (chevron) row.addView(withMargin(ink.text("›", 20, Ink.REGULAR), 6));
        return row;
    }

    View featureRow(FeatureDefinition feature) { return featureRow(feature, true); }

    /** {@code links} 为假时只留开关、不显示「›」也不可点（用于收在子页里的行）。 */
    View featureRow(FeatureDefinition feature, boolean links) {
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
            String meta = shown == 0 ? "四项都不显示" : layout;
            return navCard(feature.title, Guide.get(feature.id).summary, meta, shown > 0,
                    feature.title + "，选择显示哪几项", v -> openFeature(feature.id), false);
        }
        // 「备份系统设置」也是页内操作项：列表里显示有几张快照，不画开关
        if (feature.id.equals("sys.backup")) {
            if (backups == null && !backupsLoading) backupPages.loadBackups();
            String meta;
            if (backups == null) meta = "正在读取…";
            else if (backups.isEmpty()) meta = "还没有快照";
            else meta = backups.size() + " 个快照 · 最近 " + BackupPages.stamp(backups.get(0)[1]);
            return navCard(feature.title, Guide.get(feature.id).summary, meta, backups != null && !backups.isEmpty(),
                    feature.title + "，打开备份", v -> openFeature(feature.id), false);
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
        // 必选参数不是开关：列表里只写当前值，也不画那颗可以拨的 Switch。
        boolean required = FeatureCatalog.required(feature.id);
        String state = required ? Guide.optionName(feature, s.config.value(feature.id))
                : glyph(status) + " " + status + (value.isEmpty() ? "" : " · " + value);
        content.addView(spaced(ink.text(state, 13, required ? Ink.MEDIUM : strong ? Ink.BOLD : Ink.REGULAR), 7));
        row.addView(content, new LinearLayout.LayoutParams(0, -2, 1));

        if (!required) {
            CompoundButton toggle = toggle(feature, shown, requested);
            LinearLayout.LayoutParams switchParams = new LinearLayout.LayoutParams(-2, -2);
            switchParams.setMargins(ink.dp(10), 0, 0, 0);
            row.addView(toggle, switchParams);
        }
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
    String valueSummary(FeatureDefinition f, String value, boolean on) {
        if (f.id.equals("motion.hold")) return on ? holdEditors.holdSummary(value) : "温度自适应";
        if (f.id.equals("refresh.apps")) {
            String mode = FeatureDefinition.filterWhitelist(value) ? "白名单" : "黑名单";
            int count = appList(value).size();
            return count == 0 ? mode : mode + " · " + count + " 个应用";
        }
        if (f.id.equals("pageanim.readers")) {
            int count = ListEditors.plainList(value).size();
            return count == 0 ? "未选择阅读器" : count + " 个阅读器";
        }
        List<String> real = realValues(f);
        if (real.size() <= 1) return "";
        return Guide.optionName(f, value);
    }
    static List<String> realValues(FeatureDefinition f) {
        List<String> result = new ArrayList<>(f.values);
        result.remove(FeatureDefinition.CURVE_TOKEN);
        result.remove(FeatureDefinition.NUMBER_TOKEN);
        result.remove(FeatureDefinition.APPS_TOKEN);
        result.remove(FeatureDefinition.PACKAGES_TOKEN);
        result.remove(FeatureDefinition.PATH_TOKEN);
        result.remove(FeatureDefinition.FONT_TOKEN);
        result.remove(FeatureDefinition.PACKAGE_TOKEN);
        return result;
    }

    LinearLayout titleLine(FeatureDefinition feature, float sp, boolean rebootPending) {
        LinearLayout line = new LinearLayout(this);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.addView(ink.text(feature.title, sp, Ink.MEDIUM));
        if (feature.experimental) line.addView(withMargin(ink.tag("实验", false), 8));
        // 只有回执还没追上配置时才标「需重启」；已生效的 REBOOT 功能不再常驻标签
        if (rebootPending) line.addView(withMargin(ink.tag("需重启", false), 6));
        return line;
    }

    /** 不让 CompoundButton 自行翻转：只有提交成功的更改才改变显示状态。 */
    CompoundButton toggle(FeatureDefinition feature, boolean shown, boolean requested) {
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

    void featurePage(FeatureDefinition f) {
        if (DisplayAlgorithms.algorithmFeature(f.id)) { displayAlgorithmPage(f); return; }
        // 字体是「页内操作」，没有开关：目录、字体列表、选择器、恢复与刷新全在一页
        if (isFontNode(f.id)) { fontPages.fontPage(f); return; }
        // 「显示信息修改」：四项显示开关收在一页，本页自己没有开关
        if (f.id.equals("lock.ext.edit")) { extItemsPage(f); return; }
        // 「备份系统设置」：进去是操作页（拍快照 / 列快照 / 写回），没有开关
        if (f.id.equals("sys.backup")) { backupPages.backupPage(); return; }
        // 「快捷面板修改」：整页只有总开关和按钮顺序，交给专用页
        if (f.id.equals("qs.panel")) { panelPages.quickPanelPage(); return; }
        ApplicationServices.Snapshot s = snapshot;
        Guide.Entry g = Guide.get(f.id);
        boolean requested = s.config.requested(f.id);
        boolean pending = busy && f.id.equals(pendingId);
        boolean shown = pending ? pendingEnabled : requested;
        String current = pending ? pendingValue : s.config.value(f.id);
        String selected = preview.isEmpty() ? current : preview;

        // 1. 状态
        String status = pending ? "正在保存" : status(f.id);
        // 必选参数没有「开启/关闭」这两个状态，标题直接写「当前选择」，右侧放当前值。
        boolean required = FeatureCatalog.required(f.id);
        if ("lock.countdown".equals(f.id)) {
            // 这一页的重点是下面的事项编辑器，状态区压成和「翻页动画总开关」一样的单行紧约条，
            // 别让一张大卡片把编辑器顶到一屏之外。
            LinearLayout bar = new LinearLayout(this);
            bar.setOrientation(LinearLayout.VERTICAL);
            bar.setBackground(ink.outline(12));
            bar.addView(compactToggleRow(f.id));
            if (!f.dependency.isEmpty() && !s.config.enabled(f.dependency))
                dependencyHint(bar, f.dependency, "这一项属于「" + FeatureCatalog.get(f.dependency).title + "」，要先开启它才会生效。");
            body.addView(spaced(bar, 14));
        } else {
        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout statusTitle = new LinearLayout(this);
        statusTitle.setGravity(Gravity.CENTER_VERTICAL);
        statusTitle.addView(ink.text(required ? "当前选择" : shown ? "已开启" : "未开启", 18, Ink.BOLD));
        if (f.experimental) statusTitle.addView(withMargin(ink.tag("实验", false), 8));
        if (f.mode == FeatureDefinition.ApplyMode.REBOOT && status.equals("待重启")) statusTitle.addView(withMargin(ink.tag("需重启", false), 6));
        LinearLayout statusText = column(statusTitle, spaced(ink.text(glyph(status) + " " + status, 14, Ink.REGULAR), 6));
        if (f.mode == FeatureDefinition.ApplyMode.REBOOT) statusText.addView(spaced(paragraph("↻ 更改后需要完整重启才生效。", 13), 4));
        statusRow.addView(statusText, new LinearLayout.LayoutParams(0, -2, 1));
        if (required) statusRow.addView(ink.tag(Guide.optionName(f, current), true));
        else if (g.visual != Guide.Visual.HOLD_CURVE) statusRow.addView(toggle(f, shown, requested));
        LinearLayout statusCard = column(statusRow);
        if (!f.dependency.isEmpty() && !s.config.enabled(f.dependency)) dependencyHint(statusCard, f.dependency, "这一项属于「" + FeatureCatalog.get(f.dependency).title + "」，要先开启它才会生效。");
        if (FeatureService.needsWaveBackend(f.id, selected) && !s.config.enabled("display.gpu") && !f.dependency.equals("display.gpu"))
            dependencyHint(statusCard, "display.gpu", "所选效果是波形动画，需要先开启「GPU 显示后端」并完整重启。");
        // 停在「等待…确认」且三个作用域一个回执都没有：先说明是没注入，别让人以为功能写坏了。
        if (s.hooked.isEmpty() && (status.equals("等待运行确认") || status.equals("等待停用确认"))) {
            statusCard.addView(spaced(ink.rule(1), 14));
            statusCard.addView(spaced(paragraph(NOT_INJECTED, 14), 12));
            statusCard.addView(spaced(ink.button("前往「诊断」", Ink.Kind.SECONDARY, v -> switchTab(DIAGNOSE)), 10));
        }
        body.addView(card(statusCard));
        }

        // 2. 名单编辑器（模式 + 应用），放在说明之前
        if (f.id.equals("refresh.apps")) listEditors.appsFilterEditor(f, requested, current);
        // 2a. 翻页动画的目标阅读器：纯包名逗号列表，没有白/黑名单模式
        if (f.id.equals("pageanim.readers")) listEditors.pageReadersEditor(f, requested, current);
        // 2b. 倒数日事项编辑器：合并到「锁屏倒数日」页内，数据仍写 lock.countdowns
        if (f.id.equals("lock.countdown")) {
            String stored = busy && "lock.countdowns".equals(pendingId) ? pendingValue : s.config.value("lock.countdowns");
            listEditors.countdownEditor(FeatureCatalog.get("lock.countdowns"), stored);
        }
        // 2c. 字体页自己处理全部内容（目录 / 列表 / 选择器 / 恢复 / 刷新），这里不再插编辑器
        // 3. 说明：直接给正文，不再套「它做什么」这种没有信息量的小标题
        if (!g.what.isEmpty()) body.addView(spaced(paragraph(g.what, 15), 20));
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
        if (g.visual == Guide.Visual.HOLD_CURVE) holdEditors.holdEditor(f, requested, current);
        else if (realValues(f).size() > 1) options(f, g, requested, current, selected);
        // 5b. 锁屏样式的大预览：挪到选项列表下方，选完往上对一眼效果。
        //      「使用此主题」跟着标题行走（见 visual()），不再在图片下面重复一颗大按钮。
        if (visualDeferred) {
            body.addView(spaced(visual, 16));
            visualView = visual;
        }
        // 5c. 「显示设备状态」页给出前往「显示信息修改」的入口：四项显示开关收在那一页
        if (f.id.equals("lock.ext"))
            body.addView(spaced(ink.button("选择显示哪几项", Ink.Kind.SECONDARY, v -> openFeature("lock.ext.edit")), 14));
        // 6. 注意
        if (!g.cost.isEmpty() || !g.note.isEmpty()) {
            LinearLayout notes = column();
            if (!g.cost.isEmpty()) notes.addView(paragraph(g.cost, 14));
            if (!g.note.isEmpty()) notes.addView(spaced(ink.text("⚠ " + g.note, 14, Ink.MEDIUM), g.cost.isEmpty() ? 0 : 10));
            // 「锁屏倒数日」这页不铺「需要知道」小标题，注意事项直接跟着编辑器。
            body.addView(spaced(f.id.equals("lock.countdown") ? notes : section("需要知道", notes), 20));
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
    void extItemsPage(FeatureDefinition f) {
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

        if (!g.what.isEmpty()) body.addView(spaced(paragraph(g.what, 15), 20));

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
    void displayAlgorithmPage(FeatureDefinition feature) {
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

    String displayBaseline(String scope) {
        if (DisplayAlgorithms.gray(scope)) return "沿用原有16级灰阶处理，由原分支决定是否需要抖动；需要抖动且符合锁屏细线条件时，仍沿用「消除锁屏细线」的点量化。该项当前"
                + (snapshot.config.enabled("lock.dither") ? "已开启。" : "未开启。" );
        FeatureDefinition feature = FeatureCatalog.get("display.alg." + scope);
        return Guide.optionName(feature, DisplayAlgorithms.baselineAlgorithm(snapshot.config, scope))
                + (scope.equals("video") && snapshot.config.enabled("experiment.video") ? "（沿用旧视频选择）。" : "。");
    }

    void displayHysteresis(String scope) {
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

    void saveDisplayChoice(String id, String value, String label) {
        boolean enabled = !value.equals("default");
        if (!begin(id, enabled, value)) return;
        perform("保存「" + label + "」", () -> {
            change(id, enabled, value);
            return "已保存「" + FeatureCatalog.get(id).title + "」：" + label;
        }, false);
    }

    void dependencyHint(LinearLayout card, String dependency, String text) {
        card.addView(spaced(ink.rule(1), 14));
        card.addView(spaced(paragraph(text, 14), 12));
        card.addView(spaced(ink.button("前往「" + FeatureCatalog.get(dependency).title + "」", Ink.Kind.SECONDARY, v -> openFeature(dependency)), 10));
    }

    View section(String title, View content) {
        return column(ink.text(title, 18, Ink.BOLD), spaced(content, 8));
    }

    View beforeAfter(Guide.Entry g) {
        LinearLayout box = column(ink.text("开启前后", 18, Ink.BOLD));
        if (!g.before.isEmpty()) box.addView(spaced(compare("开启前", g.before, false), 10));
        if (!g.after.isEmpty()) box.addView(spaced(compare("开启后", g.after, true), 8));
        return box;
    }
    View compare(String label, String text, boolean after) {
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

    int width() { return getResources().getDisplayMetrics().widthPixels - ink.dp(32); }

    View visual(FeatureDefinition f, Guide.Entry g, String value) {
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
                // 效果图比一屏还高，选完别的样式要滚到列表底部才看得到「使用」：
                // 把一个同名按钮放到标题行右侧，选完当场就能用。
                View action = value.equals(snapshot.config.value(f.id)) ? null : useChip(f, value);
                return framed("效果图 · " + Guide.optionName(f, value), phone, "图中为样例数据，实际显示你的时间、天气和音乐。", action);
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

    MotionDemo demo(String scene, String style, int steps, String origin, String direction, int spread) {
        // 全刷扫过用自己的方向、速度与形状，别让「动画擦除方向 / 波形动画速度」的取值带进预览。
        if ("gc_clear".equals(scene)) { direction = gcDirection(); spread = gcSpread(); }
        MotionDemo demo = new MotionDemo(this, ink, false).configure(scene, style, steps, origin, direction, spread);
        if ("gc_clear".equals(scene)) demo.variant(gcStyle());
        mainDemo = demo;
        return demo;
    }
    View demoBox(String title, MotionDemo demo, String caption) {
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
    View framed(String title, View content, String caption) { return framed(title, content, caption, null); }
    /** {@code action} 非空时挂在标题行右侧，省得为了一个按钮再滚一屏。 */
    View framed(String title, View content, String caption, View action) {
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(ink.text(title, 18, Ink.BOLD), new LinearLayout.LayoutParams(0, -2, 1));
        if (action != null) head.addView(action);
        LinearLayout box = column(head);
        box.addView(spaced(content, 12));
        if (!caption.isEmpty()) box.addView(spaced(paragraph(caption, 13), 10));
        return box;
    }
    View phone(View screen, int frameWidth) {
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
    View widget(String theme, String kind) {
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
    int steps() { return snapshot.config.enabled("motion.steps") ? Integer.parseInt(snapshot.config.value("motion.steps")) : 2; }
    String origin() { return snapshot.config.enabled("motion.origin") ? snapshot.config.value("motion.origin") : "touch"; }
    String direction() { return snapshot.config.enabled("motion.direction") ? snapshot.config.value("motion.direction") : "auto"; }
    /** 全刷扫过自己的方向；auto 时沿用原厂「从左向右」。 */
    String gcDirection() { return snapshot.config.enabled("motion.gc_direction") ? snapshot.config.value("motion.gc_direction") : "auto"; }
    /** 场景取哪个方向：全刷扫过用独立设置，其余共用「擦除方向」。 */
    String sceneDirection(String id) { return "motion.gc_clear".equals(id) ? gcDirection() : direction(); }
    int spread() { return snapshot.config.enabled("motion.spread") ? Integer.parseInt(snapshot.config.value("motion.spread")) : 12; }
    /** 全刷扫过自己的速度；没单独设置时跟随「波形动画速度」。 */
    int gcSpread() { return snapshot.config.enabled("motion.gc_speed") ? Integer.parseInt(snapshot.config.value("motion.gc_speed")) : spread(); }
    /** 全刷扫过自己的形状；没单独设置时为横向展开。 */
    String gcStyle() { return snapshot.config.enabled("motion.gc_style") ? snapshot.config.value("motion.gc_style") : "horizontal"; }
    String timing(String style) {
        if (style.equals("kiris") || style.equals("kwipe")) {
            int hold = holdEditors.currentHold();
            return "按当前设置，实际约 " + seconds((steps() - 1) * hold + 117) + " 秒（" + (steps() - 1) + " 张中间画面，每张停留 " + hold + " 毫秒）。";
        }
        if (style.equals("gcsweep"))
            return "光带扫过的快慢由「全刷扫过速度」决定；整段仍是一次原厂清晰全刷（约 0.9 秒），去残影效果不变。";
        if (style.equals("none") || style.equals("instant") || style.equals("gc16")) return "保持原厂：画面直接切换，不额外增加刷新。";
        return "按当前设置，实际约 " + seconds(spread() * 11.65 + 117) + " 秒，仍在一次刷新内完成。";
    }
    static String seconds(double ms) { return String.format(Locale.ROOT, "%.2f", ms / 1000.0); }

    // ── 选项卡片 ──

    void options(FeatureDefinition f, Guide.Entry g, boolean requested, String current, String selected) {
        boolean thumbs = g.visual == Guide.Visual.MOTION_SCENE || g.visual == Guide.Visual.LOCK_STYLE;
        // 锁屏样式的效果图比一屏还高，「使用」按钮挪到了标题行右侧，提示要跟着改；
        // 动画场景的按钮仍在列表下方。
        String hint = g.visual == Guide.Visual.LOCK_STYLE ? "点按卡片先预览效果，满意后点标题右侧的「使用此主题」。"
                : g.visual == Guide.Visual.NONE || g.visual == Guide.Visual.BARS ? "点按卡片先查看说明，满意后再点下方按钮使用。"
                : "点按卡片先预览效果，满意后再点下方按钮使用。";
        LinearLayout list = column(ink.text(g.visual == Guide.Visual.MOTION_SCENE ? "选择动画" : "选择", 18, Ink.BOLD),
                spaced(paragraph(hint, 13), 6));
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
                else thumb = new MotionDemo(this, ink, true).configure(f.id.substring(7), value, steps(), origin(), sceneDirection(f.id), spread());
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
    View customNumberCard(FeatureDefinition f, boolean requested, String current, String selected) {
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

    void customNumberDialog(FeatureDefinition f, boolean requested, String initial) {
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
                    boolean enable = enableOnSave(f);
                    if (!begin(f.id, enable || snapshot.config.requested(f.id), value)) return;
                    perform(ink.t("使用"), () -> {
                        saveValue(f, value);
                        return "已保存「" + f.title + "」：" + value + Guide.numberUnit(f);
                    }, false);
                    preview = "";
                }).show();
    }

    View useButton(FeatureDefinition f, boolean requested, String selected) {
        String name = Guide.optionName(f, selected);
        // 依赖没开时按钮只保存，不假装会开启；否则点下去像被拒了。
        String label = !enableOnSave(f) ? "保存「" + name + "」"
                : requested ? "使用「" + name + "」" : "开启并使用「" + name + "」";
        return ink.button(label, Ink.Kind.PRIMARY, v -> use(f, selected));
    }

    /** 标题行右侧那一小颗：「使用此主题」。 */
    View useChip(FeatureDefinition f, String selected) {
        return ink.chip("使用此主题", true, v -> use(f, selected));
    }

    void use(FeatureDefinition f, String selected) {
        String name = Guide.optionName(f, selected);
        // 依赖的总开关没开时只保存取值、不顺手把这一项打开：否则会被「请先开启…」拦住，
        // 用户想先把参数配好、回头再开总开关就做不到（自动刷新的触发次数、体感的遮挡延时都是这种）。
        boolean enable = enableOnSave(f);
        if (!begin(f.id, enable || snapshot.config.requested(f.id), selected)) return;
        perform("使用「" + name + "」", () -> {
            saveValue(f, selected);
            return "已保存「" + f.title + "」：" + name + (enable ? "" : "（开启后生效）");
        }, false);
        preview = "";
    }

    /**
     * 保存一个取值：依赖的总开关开着才顺手打开这一项，否则只落盘、不动开关。
     *
     * <p>「只落盘」走 {@link com.inkcontrol.core.FeatureService#changeValue}，既不触发
     * 「请先开启…」的依赖校验，也不会把原本开着的项关掉。
     */
    void saveValue(FeatureDefinition f, String value) throws Exception {
        if (enableOnSave(f)) change(f.id, true, value);
        else services.changeValue(f.id, value);
    }

    /** 沿用当前开关状态保存取值（名单、按钮格这类「改内容不改开关」的编辑器用）。 */
    void saveValueKeeping(FeatureDefinition f, String value) throws Exception {
        if (snapshot.config.requested(f.id) && enableOnSave(f)) change(f.id, true, value);
        else services.changeValue(f.id, value);
    }

    /** 保存取值时能不能顺手把这一项打开：只取决于依赖的总开关，必选参数/顶层项恒为 true。 */
    boolean enableOnSave(FeatureDefinition f) {
        return f.dependency.isEmpty() || snapshot.config.enabled(f.dependency);
    }

    // ── 应用名单：黑白名单 ──

    static List<String> appList(String value) {
        List<String> result = new ArrayList<>();
        String list = FeatureDefinition.filterList(value);
        if (list.isEmpty()) return result;
        for (String part : list.split(",")) if (!part.isEmpty()) result.add(part);
        return result;
    }

    // ── 快捷面板：按钮顺序、动作与两个开关 ──

    static final String[] PANEL_SLOTS = {"qs.panel.btn1", "qs.panel.btn2", "qs.panel.btn3",
            "qs.panel.btn4", "qs.panel.btn5"};

    /** 编辑器行内小开关：无动画黑白开关图形，状态由调用方读取。
     *  注意：不可重写 toggle() 为空——CompoundButton.performClick() 靠它切换选中态，
     *  置空后点击无反应、OnClickListener 里读到的 isChecked() 永远是旧值。 */
    CompoundButton miniToggle(boolean checked) {
        CompoundButton toggle = new CompoundButton(this) {
            @Override public CharSequence getAccessibilityClassName() { return Switch.class.getName(); }
        };
        toggle.setButtonDrawable(ink.switchDrawable());
        toggle.setChecked(checked);
        return toggle;
    }

    LinkedHashMap<String, String> launcherApps() {
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

    /** 「安装」「诊断」的统一入口：跳转卡片 + 语言切换。 */
    void moduleSettings() {
        body.addView(spaced(navCard("安装", "导出 Magisk 安装包、启用墨控模块并勾选作用域的完整向导。",
                "", false, "打开安装向导", v -> { modulePage = INSTALL; render(); }), 12));
        body.addView(spaced(navCard("诊断", "查看后端模块、配置应用与各项生效状态，排查异常。",
                "", false, "打开诊断", v -> { modulePage = DIAGNOSE; render(); }), 12));
        body.addView(spaced(navCard("功能列表排序", "调整首页各大功能的显示顺序，按住把手上下拖即可，立即生效、随时可恢复默认。",
                "", false, "打开功能列表排序", v -> { modulePage = ORDER; render(); }), 12));
        body.addView(spaced(navCard("桌面快捷方式", "长按桌面上的墨控图标，直接跳到最常用的几个功能。",
                "", false, "选择快捷方式", v -> { modulePage = SHORTCUTS; render(); }), 12));
        body.addView(spaced(navCard("实验室", Guide.CATEGORIES.get("实验室").intro,
                "", false, "打开实验室", v -> { tab = FEATURES; category = "实验室"; featureId = ""; scroll = null; render(); }), 12));
        body.addView(spaced(navCard("名词解释", "刷新方式、残影、关键帧、波形动画……这些词是什么意思。",
                "", false, "名词解释", v -> navigate(TERMS, "")), 12));
        body.addView(spaced(navCard("关于", "版本信息、作者与来源、参考项目，以及手动检测更新。",
                "", false, "打开关于", v -> { modulePage = ABOUT; scroll = null; render(); }), 12));

        LinearLayout languageCard = column(ink.text("语言 / Language", 16, Ink.BOLD));
        TextView language = ink.chip("中 / EN", false, v -> UiLanguage.choose(this));
        language.setContentDescription(ink.t("语言 / Language"));
        languageCard.addView(spaced(language, 10));
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

    // ───────────────────────── 关于 ─────────────────────────

    /** 首页大功能的显示顺序：按保存的顺序排列；空表示默认顺序。 */
    List<Guide.Category> orderedCategories() {
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

    static int indexOfCategory(List<Guide.Category> list, String name) {
        for (int i = 0; i < list.size(); i++) if (list.get(i).name.equals(name)) return i;
        return -1;
    }

    void categoryOrderPage() {
        body.addView(card(column(heading("功能列表排序", 20),
                spaced(paragraph("调整首页各大功能的显示顺序。按住左侧的三条横线上下拖，松手就放到新位置；改动立即生效，只影响排列，不影响任何功能本身；「实验室」在「模块设置」里，不参与排序。", 14), 8))));
        // 编辑列表不含「实验室」：它不在首页显示，顺序由导航位置决定。
        List<Guide.Category> list = new ArrayList<>();
        for (Guide.Category c : orderedCategories()) if (!c.name.equals("实验室")) list.add(c);
        LinearLayout box = DragSort.container(this);
        for (int i = 0; i < list.size(); i++) {
            Guide.Category c = list.get(i);
            // 每一项只占容器的「一格」：上面的细分隔线画在这个包装里，不额外插视图——
            // 拖动落点是按容器子视图的中线算的，多插一个分隔视图就会错位。
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            if (i > 0) item.addView(ink.rule(1));
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(ink.dp(4), ink.dp(4), ink.dp(12), ink.dp(4));
            View handle = DragSort.handle(this, ink);
            row.addView(handle);
            TextView name = ink.text(c.name, 16, Ink.BOLD);
            name.setPadding(ink.dp(8), 0, 0, 0);
            row.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
            item.addView(row);
            DragSort.attach(handle, item, (from, to) -> orderCategories(from, to));
            box.addView(item);
        }
        body.addView(spaced(card(box), 16));
        body.addView(spaced(ink.button("恢复默认顺序", Ink.Kind.SECONDARY, v -> {
            UiCategoryOrder.save(this, "");
            render();
        }), 12));
    }

    /** 把第 {@code from} 项拖到第 {@code to} 位后立即保存；保存的是不含「实验室」的完整顺序。 */
    void orderCategories(int from, int to) {
        List<Guide.Category> list = new ArrayList<>();
        for (Guide.Category c : orderedCategories()) if (!c.name.equals("实验室")) list.add(c);
        if (from < 0 || from >= list.size()) return;
        Guide.Category moved = list.remove(from);
        list.add(Math.max(0, Math.min(to, list.size())), moved);
        StringBuilder value = new StringBuilder();
        for (Guide.Category c : list) {
            if (value.length() > 0) value.append(';');
            value.append(c.name);
        }
        UiCategoryOrder.save(this, value.toString());
        render();
    }

    // ───────────────────────── 安装 ─────────────────────────

    void installation() {
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
        if (checked && installed && hooks == 0) hookText += "\n" + NOT_INJECTED;
        body.addView(step(3, hooks == 3, "启用框架模块", hookText, null));

        body.addView(step(4, s != null && s.ready() && hooks == 3, "重启并开启功能",
                "完成以上步骤后完整重启手机，回到这里重新检查环境，然后到「功能」按需开启。所有增强默认关闭，可随时一键全部关闭。",
                ink.button("前往功能", Ink.Kind.SECONDARY, v -> { category = ""; featureId = ""; switchTab(FEATURES); })));
    }

    View step(int number, boolean done, String title, String text, View actions) {
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

    // ───────────────────────── 桌面快捷方式 ─────────────────────────

    void diagnostics() {
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
            if (s.hooked.isEmpty()) {
                summary.addView(spaced(ink.rule(1), 12));
                summary.addView(spaced(ink.text(NOT_INJECTED, 14, Ink.MEDIUM), 10));
            }
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
    View fact(String key, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setPadding(0, ink.dp(6), 0, ink.dp(6));
        row.addView(ink.text(key, 14, Ink.REGULAR), new LinearLayout.LayoutParams(ink.dp(96), -2));
        row.addView(ink.text(value, 14, Ink.MEDIUM), new LinearLayout.LayoutParams(0, -2, 1));
        return row;
    }

    // ───────────────────────── 操作 ─────────────────────────

    /** 配置先保存再由 Root 应用；保存成功但应用失败时如实说明，而不是笼统报“未完成”。 */
    void change(String id, boolean enabled, String value) throws Exception {
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
    boolean begin(String id, boolean enabled, String value) {
        if (busy) { Toast.makeText(this, ink.t("请等待当前操作完成"), Toast.LENGTH_SHORT).show(); return false; }
        pendingId = id; pendingEnabled = enabled; pendingValue = value;
        return true;
    }

    String status(String id) {
        String value = snapshot == null ? null : snapshot.statuses.get(id);
        return value == null ? "未知" : value;
    }
    static String glyph(String status) {
        if (status.equals("已生效")) return "●";
        if (status.equals("关闭")) return "○";
        if (status.equals("待重启")) return "↻";
        if (status.startsWith("异常")) return "✕";
        if (status.startsWith("依赖")) return "◌";
        if (status.equals("正在保存")) return "◐";
        return "…";
    }

    void confirm(String title, String text, Runnable action) {
        new AlertDialog.Builder(this).setTitle(ink.t(title)).setMessage(ink.t(text)).setNegativeButton(ink.t("取消"), null).setPositiveButton(ink.t("继续"), (dialog, which) -> action.run()).show();
    }
    void showText(String title, String text) {
        new AlertDialog.Builder(this).setTitle(ink.t(title)).setMessage(ink.t(text)).setPositiveButton(ink.t("知道了"), null).show();
    }

    /**
     * 后台执行并刷新快照。dialog 为 true 时总是弹出完整结果；否则只在失败时弹出。
     * 返回 false 表示已有操作在执行，本次未提交。onSuccess 在成功收尾后回主线程执行。
     */
    boolean perform(String label, Work work, boolean dialog) { return perform(label, work, dialog, null); }

    boolean perform(String label, Work work, boolean dialog, Runnable onSuccess) {
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
    static String reason(Throwable error) {
        String text = error.getMessage();
        return text == null || text.isEmpty() ? error.getClass().getSimpleName() : text;
    }

    void exportZip() {
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
            fontPages.applyFontFromUri(data.getData());
        if (request == FONT_DIR_REQUEST && result == RESULT_OK && data != null && data.getData() != null) {
            String path = FontPages.treePath(data.getData());
            if (path == null) showText("无法换成路径", "这个位置没法转换成文件路径。可以改用「从文件选择」直接挑一个字体文件。");
            else fontPages.saveFontDir("font.dir", path);
        }
    }

    // ───────────────────────── 排版工具 ─────────────────────────

    TextView heading(String value, float sp) { return ink.text(value, sp, Ink.BOLD); }
    TextView paragraph(String value, float sp) { return ink.text(value, sp, Ink.REGULAR); }
    TextView note(String value) { TextView view = paragraph(value, 15); view.setPadding(ink.dp(4), ink.dp(24), 0, 0); return view; }
    LinearLayout column(View... children) {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        for (View child : children) column.addView(child);
        return column;
    }
    LinearLayout card(View content) {
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
    View spaced(View view, int topDp) {
        ViewGroup.LayoutParams current = view.getLayoutParams();
        LinearLayout.LayoutParams params = current instanceof LinearLayout.LayoutParams ? (LinearLayout.LayoutParams) current
                : new LinearLayout.LayoutParams(current == null ? -1 : current.width, current == null ? -2 : current.height);
        params.topMargin = ink.dp(topDp);
        view.setLayoutParams(params);
        return view;
    }

    /** 页面里打了 tag 的那一行；紧凑大功能页给每行都打了功能 id，用来定位到指定项。 */
    static View findTagged(View root, String tag) {
        if (root == null || tag == null) return null;
        if (tag.equals(root.getTag())) return root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findTagged(group.getChildAt(i), tag);
                if (found != null) return found;
            }
        }
        return null;
    }

    /** 视图在 body 坐标系里的纵向位置：逐层累加 getTop()，一直加到 body 为止。 */
    int offsetInBody(View view) {
        int y = 0;
        View current = view;
        while (current != null && current != body) {
            y += current.getTop();
            current = current.getParent() instanceof View ? (View) current.getParent() : null;
        }
        return y;
    }
    View withMargin(View view, int leftDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
        params.leftMargin = ink.dp(leftDp);
        view.setLayoutParams(params);
        return view;
    }
}
