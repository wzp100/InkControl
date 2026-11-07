import com.inkcontrol.core.*;
import java.util.*;

public final class CoreTest {
    static int checks;
    static void check(boolean value) { checks++; if (!value) throw new AssertionError("check " + checks); }
    interface Action { void run() throws Exception; }
    static void reject(Action action) throws Exception {
        try { action.run(); } catch (IllegalArgumentException | IllegalStateException | SecurityException expected) { checks++; return; }
        throw new AssertionError("expected rejection");
    }
    static final class Store implements ConfigStore {
        Configuration value = new Configuration();
        public Configuration load() { return value; }
        public void save(Configuration value) { this.value = value; }
    }
    static void displayAlgorithms() throws Exception {
        Configuration empty = new Configuration();
        Configuration gpu = empty.change("display.gpu", true, "default");
        check(DisplayAlgorithms.scopeForMode(2).equals("clear"));
        for (int mode : new int[]{3, 4, 5, 515, 516, 517}) check(DisplayAlgorithms.scopeForMode(mode).equals("reading"));
        check(DisplayAlgorithms.scopeForMode(518).equals("fast"));
        check(DisplayAlgorithms.scopeForMode(521).equals("extreme"));
        check(DisplayAlgorithms.scopeForMode(7).isEmpty());
        reject(() -> DisplayAlgorithms.hysteresisProperty("reading"));
        reject(() -> DisplayAlgorithms.algorithmProperty("unknown"));
        // 「改用原厂显示算法」总开关：逐模式算法/迟滞仍各自保存，但总开关打开时一律回退原厂；
        // 优先级高于 GPU 显示后端，不依赖它即可开启
        FeatureDefinition stockFeature = FeatureCatalog.get("display.alg.stock");
        check(stockFeature.role.equals("backend") && stockFeature.dependency.isEmpty());
        check(stockFeature.mode == FeatureDefinition.ApplyMode.HOT && stockFeature.defaultValue().equals("default"));
        Configuration tuned = gpu.change("display.alg.fast", true, "blue").change("display.hyst.fast", true, "on")
                .change("display.alg.video", true, "bayer");
        check(DisplayAlgorithms.algorithm(tuned, "fast").equals("blue") && DisplayAlgorithms.hysteresis(tuned, "fast"));
        Configuration stockOnly = tuned.change("display.alg.stock", true, "default");
        check(DisplayAlgorithms.stock(stockOnly) && DisplayAlgorithms.algorithm(stockOnly, "fast").equals("stock"));
        check(DisplayAlgorithms.algorithmOverride(stockOnly, "video").equals("default"));
        check(!DisplayAlgorithms.supportsHysteresis(stockOnly, "fast") && !DisplayAlgorithms.hysteresis(stockOnly, "fast"));
        check(DisplayAlgorithms.hysteresisOverride(stockOnly, "fast").equals("default"));
        // 总开关独立于 GPU 后端：不开 GPU 也能开启并生效
        check(DisplayAlgorithms.stock(empty.change("display.alg.stock", true, "default")));
        // 锁屏底部那一块位置的两个总开关：「显示通知」与「显示设备状态」，都挂在锁屏样式下
        FeatureDefinition notify = FeatureCatalog.get("lock.notify");
        check(notify.role.equals("systemui") && notify.dependency.equals("lock.style"));
        check(notify.values.equals(Arrays.asList("system", "wechat")));
        check(!new Configuration().enabled("lock.notify"));
        // 设备状态总开关本身没有选项，画图标版还是文字版由 auto/icon/text 决定；默认 off
        FeatureDefinition ext = FeatureCatalog.get("lock.ext");
        check(ext.role.equals("systemui") && ext.dependency.equals("lock.style"));
        check(ext.values.equals(Arrays.asList("auto", "icon", "text")) && !new Configuration().enabled("lock.ext"));
        check(!ext.defaultOn && ext.defaultValue().equals("auto"));
        // 四项显示开关挂在总开关下，配置里没写过时按「显示」处理（首次开启全开）
        String[] kinds = {"lock.ext.wifi", "lock.ext.bt", "lock.ext.audio", "lock.ext.airplane"};
        Configuration fresh = new Configuration();
        for (String id : kinds) {
            FeatureDefinition child = FeatureCatalog.get(id);
            check(child.dependency.equals("lock.ext") && child.defaultOn && child.values.equals(Arrays.asList("default")));
            check(fresh.requested(id) && !fresh.enabled(id));      // 总开关没开时仍不算生效
            check(!fresh.change(id, false, "default").requested(id));
            check(fresh.change(id, false, "default").change(id, true, "default").requested(id));
            Configuration off = fresh.change(id, false, "default");
            check(off.text().contains(id + "=0\n") && Configuration.parse(off.text()).requested(id) == false);
        }
        // 「显示信息修改」是纯页面项（@panel）：自己没有开关，也不写取值
        FeatureDefinition edit = FeatureCatalog.get("lock.ext.edit");
        check(edit.dependency.equals("lock.ext") && edit.values.equals(Arrays.asList("@panel")));
        check(edit.defaultValue().isEmpty() && edit.acceptsPanel());
        check(fresh.value("lock.ext.edit").isEmpty() && !fresh.requested("lock.ext.edit"));
        check(fresh.change("lock.ext.edit", false, "").text().contains("lock.ext.edit.value=\n"));
        check(Configuration.parse(fresh.text()).value("lock.ext.edit").isEmpty());
        reject(() -> fresh.change("lock.ext.edit", false, "@panel"));
        for (String scope : DisplayAlgorithms.SCOPES) {
            String id = "display.alg." + scope;
            FeatureDefinition f = FeatureCatalog.get(id);
            check(f.role.equals("backend") && f.mode == FeatureDefinition.ApplyMode.HOT && f.dependency.equals("display.gpu"));
            check(f.defaultValue().equals("default"));
            check(DisplayAlgorithms.algorithmFeature(id) && DisplayAlgorithms.scope(id).equals(scope));
            check(DisplayAlgorithms.algorithmProperty(scope).equals("debug.eink.alg." + scope));
            List<String> allowed = DisplayAlgorithms.gray(scope) ? Arrays.asList("default", "stock", "nearest", "bayer16")
                    : scope.equals("video") ? Arrays.asList("default", "blue", "bayer", "stock", "threshold") : Arrays.asList("default", "stock", "bayer", "blue", "threshold");
            check(f.values.equals(allowed));
            for (String value : Arrays.asList("default", "stock", "nearest", "bayer16", "bayer", "blue", "threshold", "on"))
                if (!allowed.contains(value)) reject(() -> gpu.change(id, true, value));
            Store store = new Store(); int[] applied = {0};
            FeatureService service = new FeatureService(store, (before, after) -> applied[0]++, feature -> null);
            reject(() -> service.change(id, true, allowed.get(1)));
            check(store.value.revision == 0 && applied[0] == 0);
            store.value = gpu;
            service.change(id, true, allowed.get(1));
            check(store.value.enabled(id) && applied[0] == 1);
            check(service.status(id).equals("待重启"));
            Map<String, StatusSource.Evidence> receipts = new HashMap<>();
            receipts.put("display.gpu", new StatusSource.Evidence(true, "default", "", store.value.revision));
            FeatureService observed = new FeatureService(store, (before, after) -> {}, feature -> receipts.get(feature.id));
            check(observed.status(id).equals("等待运行确认"));
            receipts.put(id, new StatusSource.Evidence(true, allowed.get(1), "", store.value.revision));
            check(observed.status(id).equals("已生效"));
            receipts.put(id, new StatusSource.Evidence(true, "default", "", store.value.revision));
            check(!observed.status(id).equals("已生效"));
            for (String value : allowed) {
                Configuration selected = gpu.change(id, true, value);
                check(DisplayAlgorithms.algorithmOverride(Configuration.parse(selected.text()), scope).equals(value));
                check(DisplayAlgorithms.algorithmOverride(selected.change(id, false, value), scope).equals("default"));
                check(DisplayAlgorithms.algorithmOverride(selected.change("display.gpu", false, "default"), scope).equals("default"));
            }
            if (DisplayAlgorithms.gray(scope)) {
                reject(() -> FeatureCatalog.get("display.hyst." + scope));
                check(!DisplayAlgorithms.supportsHysteresis(gpu, scope));
                continue;
            }
            String hyst = "display.hyst." + scope;
            FeatureDefinition h = FeatureCatalog.get(hyst);
            check(h.values.equals(Arrays.asList("default", "on", "off")));
            check(h.mode == FeatureDefinition.ApplyMode.HOT && h.role.equals("backend") && h.dependency.equals("display.gpu"));
            check(DisplayAlgorithms.scope(hyst).equals(scope));
            check(DisplayAlgorithms.hysteresisProperty(scope).equals("debug.eink.hyst." + scope));
            check(DisplayAlgorithms.hysteresis(gpu, scope) == scope.equals("video"));
            reject(() -> service.change(hyst, true, "blue"));
            store.value = empty;
            reject(() -> service.change(hyst, true, "on"));
            store.value = gpu;
            service.change(hyst, true, "on");
            check(store.value.enabled(hyst));
            for (String alg : allowed) for (String policy : h.values) {
                Configuration selected = gpu.change(id, true, alg).change(hyst, true, policy);
                check(Configuration.parse(selected.text()).text().equals(selected.text()));
                boolean supports = !alg.equals("stock");
                check(DisplayAlgorithms.supportsHysteresis(selected, scope) == supports);
                check(DisplayAlgorithms.hysteresis(selected, scope) == (supports && (policy.equals("on") || policy.equals("default") && scope.equals("video"))));
            }
        }
        Configuration legacy = Configuration.parse("version=1\nrevision=27\ndisplay.gpu=1\nexperiment.video=1\nexperiment.video.value=stock\nlock.dither=1\nlock.dither.value=nearest\n");
        for (String scope : DisplayAlgorithms.SCOPES) check(!legacy.requested("display.alg." + scope));
        check(legacy.value("display.alg.video").equals("default"));
        check(DisplayAlgorithms.algorithm(legacy, "video").equals("stock"));
        check(!DisplayAlgorithms.supportsHysteresis(legacy, "video"));
        Configuration video = legacy.change("display.alg.video", true, "bayer").change("display.hyst.video", true, "off");
        check(DisplayAlgorithms.algorithm(video, "video").equals("bayer") && !DisplayAlgorithms.hysteresis(video, "video"));
        check(video.value("experiment.video").equals("stock") && video.enabled("lock.dither"));
        Configuration back = video.change("display.alg.video", true, "default");
        check(DisplayAlgorithms.algorithm(back, "video").equals("stock") && back.enabled("display.hyst.video"));
        Configuration stock = video.change("display.alg.video", true, "stock").change("display.hyst.video", true, "on");
        check(!DisplayAlgorithms.hysteresis(stock, "video"));
        check(DisplayAlgorithms.hysteresis(stock.change("display.alg.video", true, "blue"), "video"));
        check(stock.requested("display.hyst.video") && stock.value("display.hyst.video").equals("on"));
        Configuration separate = gpu.change("display.alg.fast", true, "blue").change("display.hyst.fast", true, "on");
        check(DisplayAlgorithms.algorithm(separate, "extreme").equals("bayer"));
        check(!DisplayAlgorithms.hysteresis(separate, "extreme"));
        check(DisplayAlgorithms.algorithm(separate, "video").equals("blue"));
        check(DisplayAlgorithms.hysteresis(separate, "video"));
        check(DisplayAlgorithms.algorithm(separate.disableAll(), "fast").equals("bayer"));
        check(separate.disableAll().value("display.alg.fast").equals("blue"));
        for (String scope : Arrays.asList("clear", "reading")) {
            Configuration gray = legacy.change("display.alg." + scope, true, "default");
            check(gray.enabled("lock.dither") && gray.value("lock.dither").equals("nearest"));
            check(DisplayAlgorithms.algorithm(gray, scope).equals("default"));
        }
        // 快速/极速现在支持「原厂」(stock)；灰度专属的点量化仍应被拒绝
        check(Configuration.parse(gpu.text().replace("display.alg.fast.value=default", "display.alg.fast.value=stock")).value("display.alg.fast").equals("stock"));
        reject(() -> Configuration.parse(gpu.text().replace("display.alg.fast.value=default", "display.alg.fast.value=nearest")));
        reject(() -> Configuration.parse(gpu.text() + "display.alg.fast.value=blue\n"));
        reject(() -> Configuration.parse(gpu.text() + "display.hyst.clear=1\n"));
    }
    public static void main(String[] args) throws Exception {
        displayAlgorithms();
        Configuration config = new Configuration(); Set<String> ids = new HashSet<>();
        for (FeatureDefinition feature : FeatureCatalog.all()) {
            check(ids.add(feature.id)); check(!config.enabled(feature.id));
            // 必选参数没有开关，新配置里也按开启处理
            check(config.requested(feature.id) == (feature.defaultOn || FeatureCatalog.required(feature.id)));
            for (String value : feature.values) { if (value.equals(FeatureDefinition.CURVE_TOKEN) || value.equals(FeatureDefinition.NUMBER_TOKEN) || value.equals(FeatureDefinition.APPS_TOKEN) || value.equals(FeatureDefinition.PACKAGES_TOKEN) || value.equals(FeatureDefinition.COUNTDOWNS_TOKEN) || value.equals(FeatureDefinition.PATH_TOKEN) || value.equals(FeatureDefinition.FONT_TOKEN) || value.equals(FeatureDefinition.PANEL_TOKEN) || value.equals(FeatureDefinition.PACKAGE_TOKEN)) { reject(() -> feature.validate(value)); continue; } feature.validate(value); check(Configuration.parse(config.change(feature.id, true, value).text()).value(feature.id).equals(value)); }
            reject(() -> feature.validate("$(id)"));
        }
        check(Configuration.parse(config.text()).text().equals(config.text()));
        // 翻页动画的三项必选参数：没有开关，旧配置里写着 0 也按开启读回来，写出去恒为 1。
        for (String id : new String[]{"pageanim.style", "pageanim.spacing", "pageanim.bands"}) {
            check(FeatureCatalog.required(id));
            check(config.requested(id) && config.text().contains(id + "=1\n"));
            check(config.change(id, false, config.value(id)).requested(id));
            check(config.change(id, false, config.value(id)).text().contains(id + "=1\n"));
            Configuration legacy = Configuration.parse(config.text().replace(id + "=1\n", id + "=0\n"));
            check(legacy.requested(id) && legacy.text().contains(id + "=1\n"));
        }
        // 「目标阅读器」是纯包名逗号列表（不是白／黑名单三段值），出厂不内置任何应用。
        FeatureDefinition readers = FeatureCatalog.get("pageanim.readers");
        check(readers.acceptsPackages() && readers.defaultValue().isEmpty());
        readers.validate("io.legado.app.release");
        readers.validate("io.legado.app.release,com.example.reader");
        readers.validate("");
        reject(() -> readers.validate("@packages"));
        reject(() -> readers.validate("$(id)"));
        reject(() -> readers.validate("com..a"));
        // 「冻结安全输入服务后能输密码」归在「系统增强」，由 SystemUI 写系统设置，默认关闭。
        FeatureDefinition safeime = FeatureCatalog.get("fix.safeime");
        check(safeime.group.equals("系统增强") && safeime.role.equals("systemui")
                && safeime.mode == FeatureDefinition.ApplyMode.HOT && !safeime.defaultOn
                && safeime.values.size() == 1 && safeime.values.get(0).equals("default"));
        // 四个位置各存一个动作，默认顺序 WiFi / 蓝牙 / 墨控快捷设置 / 截屏便签。
        // 取值＝动作表 + 末尾的「无」和单个应用包名（@package）。
        String[] slotDefaults = {"wifi", "bt", "quick", "pin"};
        for (int slot = 1; slot <= 4; slot++) {
            FeatureDefinition button = FeatureCatalog.get("qs.panel.btn" + slot);
            check(button.group.equals("快捷面板修改") && button.dependency.equals("qs.panel")
                    && button.mode == FeatureDefinition.ApplyMode.HOT
                    && button.values.size() == FeatureCatalog.PANEL_ACTIONS.length + 2
                    && button.values.get(0).equals(slotDefaults[slot - 1])
                    && button.defaultValue().equals(slotDefaults[slot - 1])
                    && button.values.contains(FeatureCatalog.PANEL_NONE)
                    && button.values.contains(FeatureDefinition.PACKAGE_TOKEN));
            for (String[] action : FeatureCatalog.PANEL_ACTIONS)
                check(button.values.contains(action[0]));
            button.validate("com.tencent.mm");        // 换成某个应用按包名存，走 @package 通道
            button.validate(FeatureCatalog.PANEL_NONE);
        }
        // 面板上还能直接开关墨控自己的三项：自动刷新、锁屏主题、遮挡息屏。
        for (String[] action : new String[][]{{"refresh", "自动刷新", "refresh.master"},
                {"lock", "锁屏主题", "lock.style"}, {"cover", "遮挡息屏", "wave.lock"}}) {
            boolean found = false;
            for (String[] entry : FeatureCatalog.PANEL_ACTIONS)
                if (entry[0].equals(action[0])) { found = entry[1].equals(action[1]); break; }
            check(found && FeatureCatalog.get(action[2]) != null);
        }
        // 「快捷面板按钮」同属「快捷面板修改」，面板弹出时由 SystemUI 现加按钮，默认关闭。
        FeatureDefinition panel = FeatureCatalog.get("qs.panel");
        check(panel.group.equals("快捷面板修改") && panel.role.equals("systemui")
                && panel.mode == FeatureDefinition.ApplyMode.HOT && !panel.defaultOn
                && panel.values.size() == 1 && panel.values.get(0).equals("default"));
        // 「点完收起面板」挂在「快捷面板按钮」下面：没有按钮就没有可收的面板，默认关闭。
        FeatureDefinition panelClose = FeatureCatalog.get("qs.panel.close");
        check(panelClose.group.equals("快捷面板修改") && panelClose.role.equals("systemui")
                && panelClose.dependency.equals("qs.panel")
                && panelClose.mode == FeatureDefinition.ApplyMode.HOT && !panelClose.defaultOn
                && panelClose.values.size() == 1 && panelClose.values.get(0).equals("default"));
        // 「截屏便签对齐尺寸」默认开：原厂那一格不缩的话会比补上来的四个大一圈。
        FeatureDefinition pinSize = FeatureCatalog.get("qs.panel.pinsize");
        check(pinSize.group.equals("快捷面板修改") && pinSize.role.equals("systemui")
                && pinSize.dependency.equals("qs.panel")
                && pinSize.mode == FeatureDefinition.ApplyMode.HOT && pinSize.defaultOn
                && pinSize.values.size() == 1 && pinSize.values.get(0).equals("default"));
        // 已下线功能的旧配置残留键被容忍并丢弃：字体2、Google 图标、隐藏锁屏通知、体感息屏唤醒、字体换肤
        Configuration tolerated = Configuration.parse(config.text()
                + "font2.pick=1\nfont2.pick.value=x\nfix.google=1\nfix.google.value=default\n"
                + "lock.notifyhide=1\nlock.notifyhide.value=default\nwave.master=1\nwave.master.value=default\n"
                + "desktop.font=1\ndesktop.font.value=default\n"
                + "qs.pinlaunch=1\nqs.pinlaunch.value=hide\n");
        check(tolerated.revision == 0 && !tolerated.requested("motion.lock") && !tolerated.requested("wave.lock"));
        // 旧配置的「拓展显示 = 设备状态」迁到独立的 lock.ext：通知关掉、设备状态打开；
        // 四项显示开关是正式功能，旧配置里的同名行照旧读入（这里是「开」）。
        Configuration migrated = Configuration.parse("version=1\nrevision=3\n"
                + "lock.notify=1\nlock.notify.value=ext\n"
                + "lock.ext.wifi=1\nlock.ext.wifi.value=default\n"
                + "lock.ext.bt=1\nlock.ext.bt.value=default\n");
        check(migrated.requested("lock.ext") && !migrated.requested("lock.notify"));
        check(migrated.requested("lock.ext.wifi") && migrated.requested("lock.ext.bt"));
        check(migrated.requested("lock.ext.audio") && migrated.requested("lock.ext.airplane"));   // 缺省按显示
        check(migrated.value("lock.ext").equals("auto"));                                        // 单值时代的 default 迁成 auto
        check(migrated.value("lock.notify").equals("system"));
        // 旧配置里的 lock.ext.value=default（没有 auto/icon/text 三选一之前）不能把整个配置打回错误卡
        check(Configuration.parse("version=1\nrevision=1\nlock.ext=1\nlock.ext.value=default\n").value("lock.ext").equals("auto"));
        FeatureDefinition hold = FeatureCatalog.get("motion.hold");
        String curve = FeatureDefinition.curveValue(new int[]{420, 400, 360, 320, 280, 230, 210, 175, 150});
        hold.validate(curve); check(Configuration.parse(config.change("motion.hold", true, curve).text()).value("motion.hold").equals(curve));
        check(FeatureDefinition.curve(curve)[8] == 150); check(FeatureDefinition.band(2) == 0); check(FeatureDefinition.band(23.9) == 7); check(FeatureDefinition.band(24) == 8);
        for (String bad : new String[]{"curve:", "curve:1,2,3", "curve:1,2,3,4,5,6,7,8,9,10", "curve:1,2,3,4,5,6,7,8,2001", "curve:1,2,3,4,5,6,7,8,-1", "curve:1,2,3,4,5,6,7,8,9;id", "curve:1,2,3,4,5,6,7,8,9\n", "curve:1, 2,3,4,5,6,7,8,9"})
            reject(() -> hold.validate(bad));
        reject(() -> FeatureCatalog.get("motion.steps").validate(curve));
        // 字体目录/文件名：允许绝对路径与中文文件名，拒绝 shell 元字符
        FeatureDefinition fontDir = FeatureCatalog.get("font.dir");
        check(fontDir.acceptsPath() && !fontDir.acceptsFont());
        fontDir.validate("/storage/emulated/0/.hmcttheme/com.hmct.theme/");
        FeatureDefinition fontPick = FeatureCatalog.get("font.pick");
        check(fontPick.acceptsFont() && !fontPick.acceptsPath());
        fontPick.validate("霞鹜文楷等宽 屏幕阅读版");
        reject(() -> fontDir.validate("/storage/$(id)/"));
        reject(() -> fontPick.validate("a;b"));
        FeatureDefinition interval = FeatureCatalog.get("refresh.interval");
        check(interval.acceptsNumber() && !interval.acceptsCurve() && FeatureDefinition.number("3") == 3);
        interval.validate("3"); check(Configuration.parse(config.change("refresh.interval", true, "3").text()).value("refresh.interval").equals("3"));
        Configuration legacyPeriod = Configuration.parse(config.change("refresh.period", true, "300").text().replace("refresh.period.value=300", "refresh.period.value=0"));
        check(!legacyPeriod.requested("refresh.period") && legacyPeriod.value("refresh.period").equals("300"));   // 旧值 0 → 默认档（5 分钟）
        for (String bad : new String[]{"0", "1000", "abc", "-1", "@number", "", "3.5", " 3", "@curve"}) reject(() -> interval.validate(bad));
        // 倒数日清单：新格式 12 段「事项;日期;农历;显示」×3；兼容旧 6 段「事项;日期」×3
        FeatureDefinition countdowns = FeatureCatalog.get("lock.countdowns");
        check(countdowns.acceptsCountdowns() && countdowns.defaultValue().isEmpty());
        countdowns.validate(""); check(FeatureDefinition.countdowns("").isEmpty());
        // 旧格式兼容：农历=否、显示=是
        String two = "考试;2026-11-27;;;生日;2026-12-20";   // 组1 考试、组2 全空停用、组3 生日
        countdowns.validate(two);
        List<FeatureDefinition.CountdownItem> legacy = FeatureDefinition.countdownList(two);
        check(legacy.size() == 2 && legacy.get(0).item.equals("考试") && legacy.get(0).date.equals("2026-11-27"));
        check(!legacy.get(0).lunar && legacy.get(0).shown && legacy.get(1).item.equals("生日"));
        check(FeatureDefinition.countdownList(";2026-11-27;;;;").size() == 1);   // 事项为空只用日期
        check(FeatureDefinition.countdownList(";2026-11-27;;;;2027-01-01").size() == 2);   // 按日期升序
        // 新格式：组1 考试（公历/显示）、组2 空（事项日期空、标志位默认）、组3 春节（农历/显示）
        String modern = String.join(";", "考试", "2026-11-27", "0", "1", "", "", "0", "1", "春节", "2027-01-01", "1", "1");
        countdowns.validate(modern);
        List<FeatureDefinition.CountdownItem> items = FeatureDefinition.countdownList(modern);
        check(items.size() == 2);
        check(items.get(0).item.equals("考试") && !items.get(0).lunar);       // 2026-11-27 早于春节换算日
        check(items.get(1).item.equals("春节") && items.get(1).lunar && items.get(1).shown);
        // 「显示」关闭的组仍入清单（渲染层过滤），由 shown 标记
        String hidden = String.join(";", "考试", "2026-11-27", "0", "1", "生日", "2026-12-20", "0", "0", "", "", "0", "1");
        countdowns.validate(hidden);
        check(FeatureDefinition.countdownList(hidden).size() == 2 && !FeatureDefinition.countdownList(hidden).get(1).shown);
        // 农历换算锚点：设备实证 2026-10-24=九月十五；2027 春节=2027-02-06；2026 春节=2026-02-17
        int[] anchor = LunarCalendar.toLunar(2026, 10, 24);
        check(anchor[0] == 2026 && anchor[1] == 9 && anchor[2] == 15);
        Calendar spring = FeatureDefinition.targetDate("2027-01-01", true);
        check(spring != null && spring.get(Calendar.YEAR) == 2027 && spring.get(Calendar.MONTH) == Calendar.FEBRUARY && spring.get(Calendar.DAY_OF_MONTH) == 6);
        Calendar newYear = FeatureDefinition.targetDate("2026-01-01", true);
        check(newYear != null && newYear.get(Calendar.MONTH) == Calendar.FEBRUARY && newYear.get(Calendar.DAY_OF_MONTH) == 17);
        check(FeatureDefinition.targetDate("2026-09-15", true) != null);      // 合法农历日
        check(FeatureDefinition.targetDate("2026-02-29", true) != null);      // 2026 农历二月小月 29 天，合法
        check(FeatureDefinition.targetDate("2026-02-30", true) == null);      // 农历月无 30 日以上
        check(FeatureDefinition.targetDate("2027-13-01", true) == null && FeatureDefinition.targetDate("1899-01-01", true) == null);
        check(FeatureDefinition.targetDate("2026-11-27", false) != null && FeatureDefinition.targetDate("2026-02-30", false) == null);
        check(FeatureDefinition.parseDate("2026-11-27") != null && FeatureDefinition.parseDate("2028-02-29") != null);
        check(FeatureDefinition.parseDate("2026-02-29") == null && FeatureDefinition.parseDate("2026-13-01") == null && FeatureDefinition.parseDate("2026-11-31") == null);
        for (String bad : new String[]{"a;2026-11-27", ";;2026-11-27;;2027-01-01", ";2026-13-01;;;;", ";2026-02-30;;;;", ";not-a-date;;;;", "事项名特别长超过十二个字符;2026-11-27;;;;", "a=b;2026-11-27;;;;", "a;2026-11-27;;;b;2026-12-20;c;2027-01-01",
                String.join(";", "a", "2026-11-27", "2", "1", "", "", "", "", "", "", "", ""),          // 农历标志非 0/1
                String.join(";", "a", "2026-02-30", "1", "1", "", "", "", "", "", "", "", ""),          // 农历月无 30 日
                String.join(";", "a", "2026-11-27", "0", "1", "", "", "", "", "", "", ""),              // 只有 11 段
                String.join(";", "a", "2026-11-27", "0", "1", "", "", "", "", "", "", "", "", "")})     // 13 段
            reject(() -> countdowns.validate(bad));
        FeatureDefinition appFilter = FeatureCatalog.get("refresh.apps");
        check(appFilter.acceptsApps() && !appFilter.acceptsNumber() && !appFilter.acceptsCurve() && appFilter.defaultValue().isEmpty());
        check(FeatureDefinition.apps("").isEmpty()); check(FeatureDefinition.apps("com.tencent.mm").equals("com.tencent.mm"));
        check(FeatureDefinition.apps("com.a,android,com.b_c.d1") != null);
        check(FeatureDefinition.appFilter("") != null && FeatureDefinition.appFilter("whitelist") != null && FeatureDefinition.appFilter("blacklist") != null);
        check(FeatureDefinition.appFilter("whitelist:com.tencent.mm,android") != null && FeatureDefinition.appFilter("blacklist:") != null);
        check(FeatureDefinition.appFilter("whitelist;;") != null && FeatureDefinition.appFilter("blacklist;com.a,android;com.b_c.d1") != null);
        check(FeatureDefinition.filterWhitelist("") && FeatureDefinition.filterWhitelist("whitelist;com.a;com.b") && !FeatureDefinition.filterWhitelist("blacklist;com.a;com.b"));
        check(FeatureDefinition.filterWhiteList("blacklist;com.a;com.b").equals("com.a") && FeatureDefinition.filterBlackList("blacklist;com.a;com.b").equals("com.b"));
        check(FeatureDefinition.filterList("blacklist;com.a,com.b;com.c").equals("com.c") && FeatureDefinition.filterList("").isEmpty());
        check(FeatureDefinition.filterWhiteList("whitelist:com.a").equals("com.a") && FeatureDefinition.filterBlackList("whitelist:com.a").isEmpty());
        check(FeatureDefinition.filterList("blacklist:com.a,com.b").equals("com.a,com.b"));
        check(FeatureDefinition.filterValue(false, "com.a", "com.b").equals("blacklist;com.a;com.b"));
        check(FeatureDefinition.filterMode("blacklist;com.a;com.b", true).equals("whitelist;com.a;com.b"));
        check(FeatureDefinition.withActiveList("whitelist;com.a;com.b", "com.c").equals("whitelist;com.c;com.b"));
        check(FeatureDefinition.withActiveList("blacklist;com.a;com.b", "").equals("blacklist;com.a;"));
        for (String bad : new String[]{"com.a,", ",com.a", "com a", "com;id", "@apps", "com.a/com.b", "$(id)", "com.a\ncom.b", "com..a", ".com.a", "com.a.", "green:com.a", "whitelist:com..a", "whitelist:com.a,", "whitelist;com.a", "whitelist;com.a;com.b;com.c", "green;com.a;com.b", "whitelist;com..a;", "whitelist;;com.a,"})
            reject(() -> appFilter.validate(bad));
        check(Configuration.parse(config.change("refresh.apps", true, "whitelist;com.tencent.mm,android;").text()).value("refresh.apps").equals("whitelist;com.tencent.mm,android;"));
        reject(() -> FeatureCatalog.get("motion.hold").validate("3"));
        reject(() -> Configuration.parse(config.text() + "alien=1\n"));
        reject(() -> Configuration.parse(config.text() + "version=1\n"));
        reject(() -> Configuration.parse(config.text().replace("revision=0", "revision=-1")));
        reject(() -> Configuration.parse(config.text().replace("display.gpu=0", "display.gpu=x")));
        reject(() -> Configuration.parse(config.text().replace("version=1", "version=2")));
        reject(() -> Configuration.parse(config.text().replace("\n", "\r\n")));
        Store store = new Store(); final int[] applied = {0};
        final StatusSource.Evidence[] evidence = {null};
        FeatureService service = new FeatureService(store, (before, after) -> { check(after.revision == before.revision + 1 || after == before); applied[0]++; }, feature -> evidence[0]);
        reject(() -> service.change("desktop.icons", true, "default")); check(store.value.revision == 0);
        reject(() -> service.change("refresh.touch", true, "default"));   // 依赖的总开关未开启
        service.change("desktop.theme", true, "paper"); check(store.value.revision == 1);
        service.change("desktop.icons", true, "default"); service.change("desktop.theme", false, "ink");
        check(store.value.requested("desktop.icons")); check(!store.value.enabled("desktop.icons")); check(service.status("desktop.icons").equals("依赖未满足"));
        check(store.value.value("desktop.theme").equals("ink"));
        service.change("desktop.theme", true, "ink"); check(store.value.enabled("desktop.icons"));
        service.change("display.gpu", true, "default"); check(service.status("display.gpu").equals("待重启"));
        check(service.status("desktop.theme").equals("等待运行确认"));
        evidence[0] = new StatusSource.Evidence(true, "ink", "", store.value.revision); check(service.status("desktop.theme").equals("已生效"));
        evidence[0] = new StatusSource.Evidence(true, "ink", "", store.value.revision - 1); check(!service.status("desktop.theme").equals("已生效"));
        evidence[0] = new StatusSource.Evidence(true, "paper", "", store.value.revision); check(!service.status("desktop.theme").equals("已生效"));
        evidence[0] = new StatusSource.Evidence(true, "ink", "测试异常", store.value.revision); check(service.status("desktop.theme").startsWith("异常"));
        evidence[0] = null; service.disableAll();
        evidence[0] = new StatusSource.Evidence(true, "ink", "", store.value.revision - 1); check(service.status("desktop.theme").equals("等待停用确认")); evidence[0] = null;
        for (FeatureDefinition feature : FeatureCatalog.all()) check(!store.value.enabled(feature.id));
        check(store.value.value("desktop.theme").equals("ink")); check(applied[0] == 6);
        FeatureService failing = new FeatureService(store, (before, after) -> { throw new java.io.IOException("Root拒绝"); }, f -> null);
        try { failing.change("lock.style", true, "8"); throw new AssertionError(); } catch (java.io.IOException expected) { checks++; }
        check(store.value.enabled("lock.style")); check(failing.status("lock.style").equals("等待运行确认"));
        check(RuntimeIdentity.matches(123, "456", "rc1", 4, "123", "456", "rc1", 4));
        check(!RuntimeIdentity.matches(123, "455", "rc1", 4, "123", "456", "rc1", 4));
        check(!RuntimeIdentity.matches(122, "456", "rc1", 4, "123", "456", "rc1", 4));
        check(!RuntimeIdentity.matches(123, "456", "rc0", 4, "123", "456", "rc1", 4));
        check(!RuntimeIdentity.matches(123, "456", "rc1", 3, "123", "456", "rc1", 4));
        check(!RuntimeIdentity.matches(123, "", "rc1", 4, "123", "", "rc1", 4));
        check(ConditionalRestore.owns("8", "8")); check(!ConditionalRestore.owns("2", "8")); check(!ConditionalRestore.owns("8", null));
        for (int style = -2; style <= 10; style++) check(ConditionalRestore.stockLockStyle(style) == (style >= 0 && style <= 4 ? style : 0));
        // 自定义锁屏样式 choices 含新增的「编辑排版-横-逆」（10），且值与名称数量对齐。
        FeatureDefinition lockStyle = FeatureCatalog.get("lock.style");
        check(lockStyle.values.contains("10"));
        check(lockStyle.values.size() == lockStyle.labels.size());
        check(lockStyle.labels.get(lockStyle.values.indexOf("10")).equals("编辑排版-横-逆"));
        // 锁屏天气：默认沿用原厂（桌面小组件）数据，另可切换为直接读系统预装的天气App
        FeatureDefinition lockWeather = FeatureCatalog.get("lock.weather");
        check(lockWeather.values.equals(Arrays.asList("default", "moji")));
        check(lockWeather.labels.get(lockWeather.values.indexOf("moji")).equals("天气App"));
        check(CallerPolicy.read(10100, 10100, 10139, 10063)); check(CallerPolicy.read(1000, 10100, 10139, 10063));
        check(CallerPolicy.read(10139, 10100, 10139, 10063)); check(CallerPolicy.read(10063, 10100, 10139, 10063));
        check(!CallerPolicy.read(10199, 10100, 10139, 10063)); check(!CallerPolicy.read(0, 10100, -1, -1));
        check(CallerPolicy.report("system", 1000, 10139, 10063)); check(!CallerPolicy.report("system", 10100, 10139, 10063));
        check(CallerPolicy.report("launcher", 10063, 10139, 10063)); check(!CallerPolicy.report("launcher", 10139, 10139, 10063));
        check(!CallerPolicy.report("evil", 1000, 10139, 10063)); check(!CallerPolicy.report("systemui", 10139, -1, 10063));
        String sha = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
        Integrity.verifySha256(new java.io.ByteArrayInputStream(new byte[]{97,98,99}), sha); checks++;
        reject(() -> Integrity.verifySha256(new java.io.ByteArrayInputStream(new byte[]{97,98,100}), sha));
        reject(() -> Integrity.verifySha256(new java.io.ByteArrayInputStream(new byte[0]), "invalid"));
        System.out.println("core checks passed: " + checks);
    }
}
