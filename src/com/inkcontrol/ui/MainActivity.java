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
    private static final String MODULE_EXTRA = "module";
    /** E-Ink 刷新面板的「墨控快捷设置」按钮：带 quick=1 进来，直接落到只含三个常用开关的一页。 */
    private static final String QUICK_EXTRA = "quick";
    /** 面板长按「自动刷新 / 遮挡息屏」：带分类名进来，落到该大功能的分类页而不是单项详情。 */
    private static final String CATEGORY_EXTRA = "category";
    /** 落到大功能页之后要滚到哪一行（面板长按「全刷扫过」用它定位到 02 那一组）。 */
    private static final String FOCUS_EXTRA = "focus";
    /** 「快捷设置」页不是 Guide 里的分类（首页不列），只是借分类位渲染一个独立页面。 */
    private static final String QUICK = "快捷设置";
    /** 「模块设置」下这些是页面而不是普通选项，进程重建时要保留当前页。 */
    static final List<String> MODULE_PAGES = Arrays.asList(INSTALL, DIAGNOSE, ORDER, SHORTCUTS, ABOUT);
    private static final String[] TABS = {FEATURES, MODULE};
    private static final int[] TAB_GLYPHS = {Ink.Glyph.FEATURES, Ink.Glyph.INSTALL};
    private static final int EXPORT_REQUEST = 100;
    static final int FONT_REQUEST = 101;
    static final int FONT_DIR_REQUEST = 102;
    /** 海信主题字体目录：字体包没有扩展名，靠文件头识别 TTF/OTF。 */
    static final String FONT_DIR_DEFAULT = "/storage/emulated/0/.hmcttheme/com.hmct.theme/";
    /** 「关于」页里的外链；只跳转浏览器，不在应用里做自动更新或下载。 */
    static final String REPO = "https://github.com/wzp100/InkControl";
    static final String RELEASES = REPO + "/releases";
    static final String README_URL = REPO + "/blob/main/README.md";
    static final String LATEST_API = RELEASES + ".atom";
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
    private String loadError = "";
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
    private android.database.ContentObserver panelObserver;
    /** 「关于」页检测更新的结果：留在字段里，页面重建（旋转）后仍看得到上一次结论。 */
    String aboutUpdateText = "", aboutUpdateUrl = "";
    boolean checkingUpdate;
    TextView aboutUpdateView;
    /** 详情页里正在预览、尚未使用的选项；空表示与当前设置相同。 */
    String preview = "";
    /** 字体页的完整列表、过滤词与渲染控件：搜索框只过滤显示，不重新扫描目录。 */
    List<String[]> fontItems = new ArrayList<>();
    String fontProblem = "";
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
    private final android.os.Handler noticeHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private int noticeSeq;
    /** 缓存「提示时长」偏好，避免每次绘制都同步读一遍存储。 */
    int noticeSeconds = UiNotice.DEFAULT;
    private final Map<String, Integer> scrollPositions = new HashMap<>();
    ScrollView scroll;
    LinearLayout body;
    MotionDemo mainDemo;
    /** 预览选项后滚动到演示处，让用户立刻看到效果。 */
    boolean scrollToVisual;
    View visualView;
    /** 从面板/快捷方式进来时，落页后要滚到的那一行（功能 id）。 */
    private String scrollToFocus;
    /** 面板按钮刚拖完、还没写进配置的新顺序；有值时按它画，省得等 root 应用那一两秒。 */
    String[] panelOrderPending;
    boolean panelOrderBusy;

    /** 系统设置快照列表；null 表示还没读过，读的时候用 backupsLoading 挡重复请求。 */
    List<String[]> backups;
    boolean backupsLoading;
    // 各页面的实现按功能拆到独立的类里；这里只持有它们，导航、状态与保存流程仍在本类。
    final SysAppPages sysAppPages = new SysAppPages(this);
    final PanelPages panelPages = new PanelPages(this);
    final ListEditors listEditors = new ListEditors(this);
    final FontPages fontPages = new FontPages(this);
    final HoldEditor holdEditors = new HoldEditor(this);
    final AboutPages aboutPages = new AboutPages(this);
    final ShortcutPages shortcutPages = new ShortcutPages(this);
    final BackupPages backupPages = new BackupPages(this);

    /** 「显示信息修改」页里的四项显示开关，顺序即锁屏上从左到右的顺序。 */
    static final String[] EXT_ITEMS = {"lock.ext.wifi", "lock.ext.bt", "lock.ext.audio", "lock.ext.airplane"};
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
    static final String[] PANEL_SLOTS = {"qs.panel.btn1", "qs.panel.btn2", "qs.panel.btn3",
            "qs.panel.btn4", "qs.panel.btn5"};
    final Widgets widgets = new Widgets(this);
    final CategoryPages categoryPages = new CategoryPages(this);
    final FeatureRows featureRows = new FeatureRows(this);
    final FeaturePage featurePageView = new FeaturePage(this);
    final LockPages lockPages = new LockPages(this);
    final QuickPages quickPages = new QuickPages(this);
    final DisplayPages displayPages = new DisplayPages(this);
    final PreviewViews previews = new PreviewViews(this);
    final OptionViews optionViews = new OptionViews(this);
    final ModulePages modulePages = new ModulePages(this);

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
                if (restored != null && CategoryPages.landingFeature(restored) != null) category = "";
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
    private void moduleTarget(android.content.Intent intent) {
        if (intent == null) return;
        String page = intent.getStringExtra(MODULE_EXTRA);
        if (page == null || !MODULE_PAGES.contains(page)) return;
        tab = MODULE; modulePage = page; category = ""; featureId = "";
    }

    /** 从桌面快捷方式进来：直接落到对应功能页，返回时回到该功能的分类而不是首页。 */
    private void shortcutTarget(android.content.Intent intent) {
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
        if (restored != null && CategoryPages.landingFeature(restored) != null) category = "";
    }

    /** 从 E-Ink 刷新面板的「墨控快捷设置」按钮进来：落到遮挡息屏 / 锁屏自定义 / 自动刷新那一页。 */
    private void quickTarget(android.content.Intent intent) {
        if (intent == null || !"1".equals(intent.getStringExtra(QUICK_EXTRA))) return;
        tab = FEATURES; modulePage = ""; category = QUICK; featureId = "";
    }

    /**
     * 面板长按「自动刷新 / 遮挡息屏」进来：落到对应大功能的分类页，而不是某一项的说明页。
     * 分类名从功能目录取，跟首页卡片用的是同一份名字；只认真实存在的分类，避免落到空页。
     */
    private void categoryTarget(android.content.Intent intent) {
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
            if (from != null && CategoryPages.landingFeature(from) != null) back = "";
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

    /**
     * 面板点一下开关后，配置已经落盘、后端也已经应用，只有 App 里的 snapshot 还是旧的。
     * 重跑一次 inspect 让运行回执跟上（详情页状态别停在「等待运行确认」），再比对快照，
     * 有变化才重画，免得连续点击时反复重建页面、丢掉滚动位置。
     */
    private void refreshFromPanel() {
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

        if (snapshot == null && loadError.isEmpty()) body.addView(widgets.note("正在读取配置…"));
        else {
            // 配置读取失败也要能进「模块设置」（导出 ZIP、诊断都靠它）：先给错误卡，再照常渲染页面。
            // 此前出错时整页只剩错误卡，「模块设置」/「功能」全都不渲染，用户连安装包都导不出来。
            if (!loadError.isEmpty())
                body.addView(widgets.card(widgets.column(widgets.heading("配置读取失败", 18),
                        widgets.paragraph("原文件已保留，未做任何修改。\n" + loadError, 14))));
            if (tab.equals(MODULE)) {
                if (modulePage.equals(INSTALL)) modulePages.installation();
                else if (modulePage.equals(DIAGNOSE)) modulePages.diagnostics();
                else if (modulePage.equals(ORDER)) modulePages.categoryOrderPage();
                else if (modulePage.equals(SHORTCUTS)) shortcutPages.shortcutPage();
                else if (modulePage.equals(ABOUT)) aboutPages.aboutPage();
                // 名词解释从「模块设置」点进去时 tab 还是 MODULE，原先把 TAB 分支写在前面，
                // 结果整页仍画成模块设置首页，看起来就是「点了没反应」，切到「功能」才显示。
                else if (category.equals(TERMS)) categoryPages.glossary();
                else modulePages.moduleSettings();
            } else if (!loadError.isEmpty()) {
                body.addView(widgets.spaced(widgets.paragraph("出错了先从这里处理：进「诊断」查看详情，或在「安装」重新导出 Magisk 模块 ZIP。", 14), 12));
                modulePages.moduleSettings();
            }
            else if (category.equals(TERMS)) categoryPages.glossary();
            else if (category.equals(QUICK)) quickPages.quickPage();
            else if (!featureId.isEmpty()) featurePageView.featurePage(FeatureCatalog.get(featureId));
            else if (!category.isEmpty()) categoryPages.categoryPage(Guide.CATEGORIES.get(category));
            else categoryPages.home();
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
        } else if (lockPages.isLockTheme()) {
            // 标题与「刷新格言」并排：徽标若挂在整条标题行（含「‹ 功能」）右侧，
            // 会跟两行整体居中，看起来比标题偏上。
            LinearLayout line = new LinearLayout(this);
            line.setGravity(Gravity.CENTER_VERTICAL);
            line.addView(ink.text(title, 26, Ink.BOLD), new LinearLayout.LayoutParams(0, -2, 1));
            line.addView(lockPages.mottoBadge());
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
            if (stats != null) box.addView(widgets.spaced(stats, 8));
        } else if (!subtitle.isEmpty()) {
            // Keep the brand/title wide enough on 320dp screens in both languages.
            LinearLayout details = new LinearLayout(this);
            details.setGravity(Gravity.CENTER_VERTICAL);
            details.addView(ink.text(subtitle, 13, Ink.REGULAR), new LinearLayout.LayoutParams(0, -2, 1));
            box.addView(widgets.spaced(details, 6));
        }
        return box;
    }

    /** 标题下方的单行状态：只留「待重启 / 异常」，不再统计开启数量。 */
    private View headerStats() {
        if (snapshot == null) return null;
        int pending = 0, faults = 0;
        String firstFault = "";
        for (FeatureDefinition feature : FeatureCatalog.all()) {
            if (CategoryPages.isPageNode(feature.id)) continue;
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

    static List<String> appList(String value) {
        List<String> result = new ArrayList<>();
        String list = FeatureDefinition.filterList(value);
        if (list.isEmpty()) return result;
        for (String part : list.split(",")) if (!part.isEmpty()) result.add(part);
        return result;
    }

    // ── 快捷面板：按钮顺序、动作与两个开关 ──


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

    /** 页面里打了 tag 的那一行；紧凑大功能页给每行都打了功能 id，用来定位到指定项。 */
    private static View findTagged(View root, String tag) {
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
    private int offsetInBody(View view) {
        int y = 0;
        View current = view;
        while (current != null && current != body) {
            y += current.getTop();
            current = current.getParent() instanceof View ? (View) current.getParent() : null;
        }
        return y;
    }
}
