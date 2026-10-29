package com.inkcontrol.ui;

import com.inkcontrol.core.DisplayAlgorithms;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 面向普通用户的说明文字：每项功能做什么、开启前后有什么不同、代价、名词解释和选项的白话名称。
 * 数字均来自项目实测报告（墨水屏刷新分析、HWC 排队机制实测、延后取图实测等）。
 */
final class Guide {
    enum Visual { NONE, MOTION_SCENE, MOTION_STEPS, MOTION_ORIGIN, MOTION_DIRECTION, MOTION_SPREAD, HOLD_CURVE, LOCK_STYLE, DESKTOP_THEME, DESKTOP_WIDGET, REFRESH_MODES, BARS }

    static final class Option {
        final String name, detail;
        Option(String name, String detail) { this.name = name; this.detail = detail; }
    }
    static final class Bar {
        final String label; final float value; final String text;
        Bar(String label, float value, String text) { this.label = label; this.value = value; this.text = text; }
    }
    static final class Entry {
        String summary = "", what = "", before = "", after = "", cost = "", note = "";
        Visual visual = Visual.NONE;
        String barsCaption = "";
        final List<Bar> bars = new ArrayList<>();
        final Map<String, Option> options = new LinkedHashMap<>();
        final List<String[]> terms = new ArrayList<>();
        Entry summary(String v) { summary = v; return this; }
        Entry what(String v) { what = v; return this; }
        Entry before(String v) { before = v; return this; }
        Entry after(String v) { after = v; return this; }
        Entry cost(String v) { cost = v; return this; }
        Entry note(String v) { note = v; return this; }
        Entry visual(Visual v) { visual = v; return this; }
        Entry bars(String caption, Bar... values) { visual = Visual.BARS; barsCaption = caption; bars.addAll(Arrays.asList(values)); return this; }
        Entry option(String value, String name, String detail) { options.put(value, new Option(name, detail)); return this; }
        Entry term(String... keys) { for (String key : keys) terms.add(new String[]{key, TERMS.get(key)}); return this; }
    }
    static final class Category {
        final String name, intro;
        final String[][] sections;
        /**
         * 收在子页里、不在分类列表直接出现的功能（「显示信息修改」页里的四项显示开关）。
         * 它们照样算这个分类的开关数，所以单独列出来，分类卡上的「已开启/总数」才准。
         */
        final String[] nested;
        Category(String name, String intro, String[][] sections, String[] nested) {
            this.name = name; this.intro = intro; this.sections = sections; this.nested = nested;
        }
    }

    /** 名词表；功能页引用，首页“名词解释”页列出全部。 */
    static final Map<String, String> TERMS = new LinkedHashMap<>();
    static {
        TERMS.put("刷新方式", "原厂界面有四种模式：清晰（2）、阅读（3，兼容4/5）、快速（6）、极速（9）。快速和极速底层都用 A2，但可分别选择算法；视频是独立场景。选择算法不会改变刷新模式。");
        TERMS.put("快速模式", "原厂模式6，底层使用 A2 黑白刷新，默认 Bayer 网点、不开独立迟滞。与极速模式9分别保存算法选择。");
        TERMS.put("极速模式", "约 0.12 秒刷完。只能显示黑和白，灰色用网点模拟；最快、最跟手，但会留一点残影。（屏幕驱动中的名字是 A2）");
        TERMS.put("阅读模式", "约 0.45 秒刷完。有 16 级灰阶，不闪黑，文字和图片细腻。（GLD16）");
        TERMS.put("清晰全刷", "约 0.9 秒刷完。画面会先闪一下黑再显示，能把残影彻底清掉。（GC16）");
        TERMS.put("残影", "上一张画面没有完全擦干净留下的淡淡痕迹，墨水屏刷新越快越容易出现。");
        TERMS.put("显示后端", "系统里负责把画面转换成墨水屏驱动信号并送上屏的那段程序（硬件合成器 HWC）。");
        TERMS.put("GPU 显示后端", "墨控提供的显示后端：把原厂由 CPU 逐像素做的图像处理换到 GPU 上并行完成。波形动画、触摸响应加速、消除锁屏细线都依赖它。");
        TERMS.put("关键帧", "动画中途停下来显示的一张静止画面。墨水屏不能像普通屏幕那样每秒播放几十帧，所以墨控用 1–3 张中间画面拼出动画，每张都要完整刷新一次。");
        TERMS.put("关键帧动画", "方形展开、关键帧擦除：依次显示几张中间画面实现。不需要 GPU 显示后端，但每多一张就多一次刷新。");
        TERMS.put("波形动画", "墨迹晕开、网点长大、扫描线、百叶窗：在屏幕驱动层让不同位置的像素错开一点时间开始刷新，一次刷新之内完成，所以又快又顺。需要 GPU 显示后端。");
        TERMS.put("扫描帧", "墨水屏驱动一次给整屏送电压的周期，本机约 11.7 毫秒（每秒 85.8 次）。一次极速刷新约需 10 帧。");
        TERMS.put("温度自适应", "墨水屏里的墨水颗粒天冷时移动变慢，同一种刷新要多花几倍时间：24°C 以上极速刷新约 10 帧，3°C 以下要 35 帧。墨控读取电池温度，自动按当前温度取刷完所需的时间。");
        TERMS.put("抖动", "把灰色转换成黑白小点的方法。点排得越均匀，远看越像平滑的灰；排得不好会出现条纹或雪花闪烁。");
        TERMS.put("迟滞", "利用上一帧的黑白结果，让亮度只有小变化的像素保持原状态，减少静止区域闪点。可能增加拖尾。独立开关仅适用于 Bayer、蓝噪声和固定阈值；原厂误差扩散自带历史处理，不由此开关控制。");
        TERMS.put("完整重启", "关机再开机（不是只锁屏）。替换系统文件的功能要在开机过程中才能装载。");
        TERMS.put("磁贴", "下拉快捷设置面板里的一格按钮（Wi-Fi、蓝牙、手电筒等）。装了磁贴的第三方应用会注册一个磁贴服务，系统在「编辑磁贴」里把它列出来，拖到面板上才能用。");
        TERMS.put("作用域", "框架（如 Vector/LSPosed）里给模块勾选的、允许它进入的系统进程。墨控需要：系统框架、系统界面、桌面。");
        TERMS.put("近距离传感器", "屏幕顶部的一个小传感器，检测正前方是否有物体靠近。手或布料遮住它时，手机判断“被覆盖”。");
    }

    static final Map<String, Category> CATEGORIES = new LinkedHashMap<>();
    static {
        cat("显示", "按清晰、阅读、快速、极速和视频分别选算法，调整触摸响应。", new String[][]{
                {"核心", "display.gpu", "display.alg.stock"},
                {"原厂四模式 · 算法", "display.alg.clear", "display.alg.reading", "display.alg.fast", "display.alg.extreme"},
                {"独立视频场景", "display.alg.video"},
                {"触摸与响应", "display.sampling", "display.idle"}, {"系统刷新设置", "display.dynamic", "display.apps"}});
        cat("锁屏", "锁屏样式、天气、拓展显示、倒数日与锁屏刷新，以及歌词相关设置。", new String[][]{
                {"样式", "lock.style", "lock.weather"},
                {"拓展显示", "lock.notify", "lock.ext", "lock.ext.edit"},
                {"倒数日", "lock.countdown"},
                {"锁屏刷新", "lock.first", "lock.update", "lock.delay", "lock.dither"},
                {"歌词", "lock.lyric", "lock.statusbar", "lock.lyricmode"}},
                new String[]{"lock.ext.wifi", "lock.ext.bt", "lock.ext.audio", "lock.ext.airplane"});
        cat("动画", "打开应用、返回、弹窗和锁屏时的过渡效果，专为墨水屏设计。", new String[][]{
                {"总开关", "motion.master"},
                {"各场景用什么动画", "motion.app_open", "motion.app_close", "motion.in_app_forward", "motion.in_app_back", "motion.dialog", "motion.gc_clear", "motion.lock", "motion.unlock"},
                {"动画节奏", "motion.steps", "motion.hold", "motion.spread", "motion.origin", "motion.direction"},
                {"其他", "motion.dim", "motion.scale"}});
        cat("自动刷新", "点击、按键或阅读时累计操作，达到设定次数后自动刷新，减少墨水屏残影。", new String[][]{
                {"总开关", "refresh.master"}, {"触发方式", "refresh.touch", "refresh.key", "refresh.reading"},
                {"节奏", "refresh.interval", "refresh.debounce", "refresh.delay", "refresh.period"},
                {"应用名单", "refresh.apps"}});
        cat("字体", "把系统字体换成目录里的任意 TTF/OTF，点「应用」立刻全局生效，不用重启。", new String[][]{
                {"字体", "font.dir"}});
        cat("体感", "用手掌或物体遮挡屏幕顶部的近距离传感器，锁屏下自动息屏；移开后唤醒并尝试退出锁屏。需要系统无障碍中启用「墨控体感」服务。", new String[][]{
                {"行为", "wave.lock", "wave.coverDelay", "wave.wake"}});
        cat("桌面", "原厂桌面的主题、图标、字体与组件；不改动图标位置和布局。", new String[][]{
                {"主题", "desktop.theme"}, {"换肤项目", "desktop.icons", "desktop.font", "desktop.wallpaper", "desktop.widget"}});
        // 这一页是独立界面（不走功能开关列表），sections 留空；渲染由 MainActivity 特判。
        cat("系统应用卸载/还原", "把用不到的系统应用从机主账号里卸掉，随时可以还原；系统分区里的原始文件始终保留，还原后仍是系统应用身份。", new String[][]{});
        cat("系统增强", "修复原厂墨水屏服务的几个问题：偶发重启、卡开机、多余刷新；以及放开快捷设置里对第三方磁贴的限制。", new String[][]{
                {"稳定性", "fix.exceptions", "fix.configlock", "guard.config"}, {"刷新行为", "fix.modes", "fix.video"},
                {"安全", "fix.permissions"}, {"控制中心", "qs.extend"}});
        cat("实验室", "仍在验证的优化，效果因场景而异。", new String[][]{{"实验项目", "experiment.cache"}});
    }
    private static void cat(String name, String intro, String[][] sections) {
        CATEGORIES.put(name, new Category(name, intro, sections, new String[0]));
    }
    private static void cat(String name, String intro, String[][] sections, String[] nested) {
        CATEGORIES.put(name, new Category(name, intro, sections, nested));
    }

    private static final Map<String, Entry> ENTRIES = new LinkedHashMap<>();
    static Entry get(String id) { Entry e = ENTRIES.get(id); return e == null ? new Entry() : e; }
    private static Entry put(String id) { Entry e = new Entry(); ENTRIES.put(id, e); return e; }

    /**
     * 没有独立入口、只作为数据或页内操作存在的功能：内容并进了上一页（倒数日事项并进「锁屏倒数日」），
     * 或是那一页里的选择器记录（最近应用的字体、文件选择器记录）。它们不出现在分类小节里。
     */
    static final java.util.Set<String> INTERNAL = new java.util.HashSet<>(Arrays.asList(
            "lock.countdowns", "font.pick", "font.saf"));

    /** 迟滞内嵌在算法页；旧视频入口只跳到新页，配置仍可解析。 */
    static String destination(String id) {
        if (id.equals("experiment.video")) return "display.alg.video";
        if (id.startsWith("display.hyst.")) return "display.alg." + DisplayAlgorithms.scope(id);
        return id;
    }

    /** 未接管选项仍会正常使用默认算法，不能把覆盖开关的“关闭”当成算法关闭。 */
    static String selectionStatus(boolean overridden, String receipt) {
        return !overridden && (receipt.equals("关闭") || receipt.equals("已生效")) ? "沿用当前默认" : receipt;
    }

    /** 选项显示名：优先用白话名称，没有时用目录里的标签。 */
    static String optionName(com.inkcontrol.core.FeatureDefinition f, String value) {
        Option o = get(f.id).options.get(value);
        if (o != null) return o.name;
        if (com.inkcontrol.core.FeatureDefinition.curve(value) != null) return "自定义曲线";
        if (com.inkcontrol.core.FeatureDefinition.number(value) != null) return value + numberUnit(f);
        int i = f.values.indexOf(value);
        return i < 0 ? value : f.labels.get(i);
    }

    /** 自定义数值的单位：合并间隔按毫秒，其余（触发次数）按次。 */
    static String numberUnit(com.inkcontrol.core.FeatureDefinition f) {
        if (f.id.equals("refresh.debounce")) return " 毫秒";
        if (f.id.equals("refresh.period")) return " 分钟";
        return " 次";
    }

    private static final String SCENE_COMMON = "选「瞬间切换」以外的效果后，这个场景就不再是原厂的直接跳变。波形类效果（墨迹、网点、扫描线、百叶窗）需要先开启 GPU 显示后端，并关闭「改用原厂显示算法」。";
    private static void styles(Entry e, boolean transitions, boolean lock) {
        if (transitions) {
            e.option("kiris", "方形展开", "新画面从你点的位置以方块形状分几步扩开。关键帧动画，不需要 GPU 后端。");
            e.option("kwipe", "关键帧擦除", "新画面像翻页一样，从一侧分几步推过来。关键帧动画，不需要 GPU 后端。");
        }
        e.option("ink", "墨迹晕开", "新画面从点击处像墨水在纸上洇开，边缘自然不规则。波形动画。");
        e.option("dots", "网点长大", "整屏同时冒出小网点，再逐渐长满成新画面，像印刷网点。波形动画。");
        e.option("scan", "扫描线", "新画面像扫描仪一样逐行刷出，方向由“擦除方向”决定。波形动画。");
        e.option("blind", "百叶窗", "屏幕分成若干横条，每条各自从上往下刷新。波形动画。");
        e.term("关键帧动画", "波形动画");
    }

    static {
        for (String scope : DisplayAlgorithms.SCOPES) {
            boolean gray = DisplayAlgorithms.gray(scope);
            String mode = DisplayAlgorithms.name(scope);
            Entry e = put("display.alg." + scope).summary(mode + "，单独保存算法选择")
                    .what("只调整" + mode + "的画面处理，不改变刷新模式。" + (gray ? "仅提供16级灰阶算法。" : "仅提供黑白算法。快速、极速和视频分别保存选择。"))
                    .cost("需要 GPU 显示后端。算法与迟滞均为热更新配置，实际生效以运行回执为准。新算法尚需实屏对照。")
                    .option("default", "保持现状", gray ? "保留原有灰阶路径，也保留「消除锁屏细线」的条件处理。" : scope.equals("video")
                            ? "沿用旧视频选择；没有旧覆盖时是蓝噪声。迟滞单独设置。" : "沿用 Bayer。迟滞单独设置，默认关闭。")
                    .term("刷新方式", "抖动");
            if (gray) {
                e.option("stock", "原厂误差扩散", scope.equals("reading")
                        ? "像素量化严格交回系统原厂 CPU 路径，白底、灰底不再出现规则网点；滑动时系统会自动切到均衡模式，那条 A2 局部刷新路径也跟随这里的选择，所以这一项同时决定滚动、切页时的网点样式。代价是本模式内的波形动画不再生效（全刷扫过走清晰模式，不受影响）。"
                        : "明确使用原厂16级灰阶误差扩散；会覆盖该模式的锁屏细线算法选择。")
                        .option("nearest", "点量化", "就近取16级灰阶，不向相邻像素传播误差。")
                        .option("bayer16", "16级 Bayer", "固定有序网点，保留16级灰阶；纹理需实屏观察。");
            } else {
                e.option("bayer", "Bayer", "固定排列的有序网点，可独立控制迟滞。")
                        .option("blue", "蓝噪声", "分布较均匀的黑白网点，可独立控制迟滞。")
                        .option("threshold", "固定阈值", "直接按亮度取黑或白，不保留灰色网点；可独立控制迟滞。")
                        .term("迟滞");
                if (scope.equals("video")) e.option("stock", "原厂误差扩散", "内部自带历史处理，不支持独立迟滞开关。当前 GPU 实现逐行依赖较多，视频可能较慢。");
                else e.option("stock", "原厂算法", "像素量化交回系统原厂处理，白底、灰底不再出现规则方格；代价是本模式内的波形动画不再生效（全刷扫过走清晰模式，不受影响）。");
                put("display.hyst." + scope).summary(DisplayAlgorithms.name(scope) + "内的独立历史稳定开关")
                        .what("仅对本场景的 Bayer、蓝噪声、固定阈值生效。与算法选择独立保存；切到不适用算法时保留选择并隐藏开关。")
                        .cost("可能增加拖尾；原厂误差扩散自带历史处理，此开关不能启停它。")
                        .option("default", "跟随默认", scope.equals("video") ? "视频默认开启。" : "快速和极速默认关闭。")
                        .option("on", "开启", "利用前一帧减少闪点。")
                        .option("off", "关闭", "逐帧量化，不使用独立迟滞。").term("迟滞");
            }
        }
        put("display.gpu").summary("图像处理交给 GPU，CPU 占用大幅下降").what("墨水屏每次刷新前，要把画面转换成灰度、做抖动、再与刷新波形混合。原厂把这些全放在 CPU 上逐像素计算；开启后改由手机 GPU 并行完成。")
                .before("每次刷新约占用 45 毫秒 CPU 时间，滑动或播放视频时 CPU 负担重。").after("每次刷新的 CPU 处理降到约 3 毫秒。它也是波形动画、触摸响应加速、消除锁屏细线的前提。")
                .cost("会替换系统显示库，开关后需要完整重启。墨控持续监测 GPU 运行状态，一旦出错会自动停用，下次重启即回到原厂显示库。")
                .bars("每次刷新的 CPU 处理时间（实测）", new Bar("原厂", 45.5f, "45 ms"), new Bar("开启后", 3f, "3 ms"))
                .option("default", "启用 GPU 显示后端", "").term("显示后端", "GPU 显示后端", "完整重启");
        put("display.alg.stock").summary("一键退回原厂算法，优先级高于 GPU 后端").what("清晰、阅读、快速、极速、视频五个模式都不再接管量化算法与迟滞，全部交回原厂处理；GPU 显示后端本身仍然加载，触摸响应加速与全刷复用照常可用，但所有像素量化都走原厂路径。")
                .cost("打开时各模式自己保存的算法与迟滞只保留在配置里、不生效，关掉立刻按原设置恢复。适合对照排查「换了算法之后残影变重」这类问题。本开关不需要先开启 GPU 显示后端；但开启期间波形动画（墨迹/网点/扫描线/百叶窗）与「全刷扫过」都无法生效，因为 GPU 的逐像素波形时序会被一并停用。")
                .option("default", "使用原厂算法", "").term("抖动", "迟滞", "显示后端");
        put("display.sampling").summary("触摸后画面更快开始变化，约快 50 毫秒").what("屏幕正在刷新时，显示服务会提前取走下一张画面排队。等真正轮到它上屏，内容已经过时将近 0.1 秒。开启后改为等队列快空时才取最新的画面。")
                .before("从手指触摸到画面开始变化约 98 毫秒。").after("缩短到约 45 毫秒，滑动和点按更跟手。")
                .cost("连续快速滑动时，每秒刷新次数会略少一点（约 5.9 → 5.6 次）。")
                .bars("触摸到画面开始变化的延迟（实测）", new Bar("原厂", 98, "98 ms"), new Bar("开启后", 45, "45 ms"))
                .option("2", "队列剩两帧时取图", "在“及时”与“流畅”之间的平衡点，实测最佳。").term("扫描帧");
        put("display.idle").summary("停顿几秒后再操作，第一下不再慢半拍").what("画面不变时，原厂约 2–3 秒就关掉屏幕驱动电路的电源来省电（墨水屏断电不会掉画面）。可下一次刷新前要先重新上电，多等约 85 毫秒。开启后改成 10 秒没有变化才断电。")
                .before("看几秒内容再翻页，第一下反应明显慢一拍。").after("10 秒内的连续操作都不会遇到这一拍等待。")
                .cost("闲置时驱动电路多通电几秒，耗电略有增加。需要完整重启。")
                .bars("停顿 3 秒后第一次刷新的额外等待（实测）", new Bar("原厂", 85, "85 ms"), new Bar("开启后", 0, "0 ms"))
                .option("10", "空闲 10 秒后断电", "").term("显示后端", "完整重启");
        put("display.dynamic").summary("滑动时不再反复查询刷新模式").what("原厂在你手指每移动一下，都会向墨水屏服务同步查询一次“当前用什么刷新方式”，滑一下就是几十次，主线程要停下来等结果。开启后关掉这个机制（系统设置 dynamic_refresh_mode 设为 0）。")
                .before("在设置里滑动 6 次，触发 75 次同步查询。").after("同样操作 0 次查询，滑动时少一些卡顿。")
                .cost("原厂“滑动时自动切换刷新方式”的行为不再发生。关闭本项时恢复你原来的设置值；如果你期间自己改过这个设置，墨控会保留你的修改。")
                .bars("滑动 6 次触发的同步查询（实测）", new Bar("原厂", 75, "75 次"), new Bar("开启后", 0, "0 次"))
                .option("0", "关闭原厂动态切换", "");
        put("display.apps").summary("把“阅读模式”的应用统一改成极速刷新").what("系统给一部分应用（多为阅读、文档类）预设了阅读模式：灰阶细腻但每次刷新约 0.45 秒。开启后把这些应用改成极速模式。")
                .before("这些应用每次画面变化约 0.45 秒，图片和灰色文字细腻。").after("约 0.12 秒，翻页、滚动明显更跟手；图片变成黑白网点、略有颗粒感。")
                .cost("只改原本是阅读模式的应用；关闭时逐个恢复原值，你自己单独改过的应用不会被动。需要完整重启。")
                .visual(Visual.REFRESH_MODES).option("521", "阅读模式 → 极速模式", "").term("刷新方式", "极速模式", "阅读模式", "残影");

        put("motion.master").summary("原厂没有过渡动画；开启后各场景可分别设置").what("原厂为了墨水屏把窗口动画全部关闭，打开应用、返回时画面直接跳变。开启后，可以在下面每个场景选一种专为墨水屏设计的过渡效果。")
                .before("所有切换都是瞬间跳变，很难看出画面从哪来、到哪去。").after("切换有方向感：从点的地方展开、从一侧推入等。")
                .cost("关键帧动画每多一张中间画面就多一次刷新；波形动画几乎不增加时间。关闭总开关后所有场景恢复原厂，各场景的选择会保留。")
                .option("default", "按场景应用", "").term("关键帧动画", "波形动画");
        String[][] scenes = {{"app_open", "从桌面点开一个应用时。"}, {"app_close", "从应用回到桌面时。"}, {"in_app_forward", "在应用里进入下一页，例如点开一条设置。"},
                {"in_app_back", "在应用里返回上一页。"}, {"dialog", "对话框、弹出菜单出现和收起时。"}, {"lock", "按电源键锁屏时。"}, {"unlock", "唤醒屏幕时。"}};
        for (String[] scene : scenes) {
            Entry e = put("motion." + scene[0]).summary(scene[1].replace("。", "") + "的过渡效果").what(scene[1] + SCENE_COMMON).visual(Visual.MOTION_SCENE)
                    .cost("关闭此场景即恢复原厂的瞬间切换。");
            styles(e, !scene[0].equals("lock") && !scene[0].equals("unlock"), true);
        }
        put("motion.gc_clear").summary("去残影全刷不再整屏同时闪黑").what("系统做“清晰全刷”去残影时，整屏会同时闪一下黑。开启后改成从一侧扫向另一侧，闪黑像一道光带扫过。需要 GPU 显示后端，并关闭「改用原厂显示算法」。")
                .before("整屏同时变黑再变回，比较刺眼。").after("黑色光带从左扫到右，过程柔和，去残影效果相同。")
                .visual(Visual.MOTION_SCENE).option("gcsweep", "全刷扫过", "黑色光带从一侧扫过屏幕。").term("清晰全刷", "残影", "波形动画");
        put("motion.dim").summary("弹窗时背后不再变成满屏颗粒").what("弹出对话框时，系统会把背后的画面调暗。墨水屏显示不了半透明灰，变暗层会被转成满屏颗粒点，弹出和关闭时都要整屏重刷。开启后背后保持原样。")
                .before("每次弹窗，整屏先变成颗粒灰，关闭时再整屏刷回来。").after("只刷新对话框区域，背后不动，更快也更干净。")
                .option("default", "移除背景变暗", "").term("抖动");
        put("motion.steps").summary("关键帧动画用几张中间画面").what("“方形展开”“关键帧擦除”是用几张静止的中间画面拼出来的动画。张数越多越像动画，但每张都要完整刷新一次，整体更慢。只影响这两种关键帧动画。")
                .cost("不开启时使用两步。").visual(Visual.MOTION_STEPS)
                .option("2", "两步（1 张中间画面）", "最快，只在中途停一下。").option("3", "三步（2 张中间画面）", "过程更清楚，略慢。").option("4", "四步（3 张中间画面）", "最像动画，最慢。")
                .term("关键帧", "关键帧动画");
        put("motion.hold").summary("每张中间画面停多久；可按温度自定义曲线").what("关键帧动画里，每张中间画面要停留一会儿再换下一张。停留不能短于墨水屏刷完这张画面所需的时间，否则上一张还没刷完就被覆盖，看到的是糊在一起的画面；停留越长，动画越慢。")
                .cost("不开启时为温度自适应。低于自动值（图中虚线）的温度段，动画可能出现残影或糊成一片。每段最短 30 毫秒、最长 2 秒。")
                .option("0", "温度自适应", "随当前温区选择停留时间。")
                .option("60", "60 毫秒", "统一停留时间。").option("100", "100 毫秒", "统一停留时间。")
                .option("200", "200 毫秒", "统一停留时间。").option("300", "300 毫秒", "统一停留时间。")
                .option("500", "500 毫秒", "统一停留时间。").option("1000", "1000 毫秒", "统一停留时间。")
                .option("@curve", "自定义曲线", "在温度曲线编辑器中逐温区调整。")
                .visual(Visual.HOLD_CURVE).term("关键帧", "温度自适应", "扫描帧");
        put("motion.spread").summary("波形动画的快慢：最早和最晚开始刷新的像素相差多久").what("波形动画让屏幕上不同位置的像素错开一点时间开始刷新。这一项决定错开的总幅度：数值越大，动画越慢、过程越明显；越小越接近瞬间切换。")
                .cost("不开启时为 12 帧。").visual(Visual.MOTION_SPREAD)
                .option("1", "1 帧（约 0.01 秒）", "几乎看不出动画。").option("6", "6 帧（约 0.07 秒）", "轻快。").option("12", "12 帧（约 0.14 秒）", "默认，过程清楚又不拖沓。")
                .option("18", "18 帧（约 0.21 秒）", "舒缓。").option("24", "24 帧（约 0.28 秒）", "最慢，动画感最强。").term("波形动画", "扫描帧");
        put("motion.origin").summary("展开类动画从哪里开始").what("方形展开和墨迹晕开从屏幕的哪个位置开始扩散。").cost("不开启时从触摸位置开始。").visual(Visual.MOTION_ORIGIN)
                .option("touch", "触摸位置", "从你刚点的图标或按钮处展开，最直观。").option("center", "屏幕中心", "总是从正中间展开，更整齐。");
        put("motion.direction").summary("擦除、扫描线、百叶窗从哪一侧开始").what("关键帧擦除、扫描线和百叶窗从屏幕的哪一侧开始。").cost("不开启时为自动。").visual(Visual.MOTION_DIRECTION)
                .option("auto", "自动", "打开和前进从右往左推、关闭和后退从左往右；扫描线从上往下。符合“前进/后退”的直觉。")
                .option("ltr", "从左向右", "").option("rtl", "从右向左", "").option("ttb", "从上向下", "").option("btt", "从下向上", "");
        put("motion.scale").summary("应用自己的小动画：水波纹、回弹、页面内切换").what("应用内部也有很多小动画，例如按钮水波纹、列表回弹、标签页切换。原厂固定为正常速度，在墨水屏上它们会变成一串拖影和多余刷新。")
                .before("点按钮会看到几次灰色水波纹刷新，列表滚到头会回弹几下。").after("可以完全关闭（直接显示结果），或缩短为一半时长。")
                .option("0", "关闭动画", "直接显示动画结束的样子，刷新最少。").option("0.5", "半速时长", "保留动画，但时长减半。").option("1", "原时长", "与原厂相同。");

        put("lock.style").summary("五套专为墨水屏设计的锁屏").what("替换原厂锁屏画面，显示时间、日期、天气和正在播放的音乐。").cost("关闭后恢复你在系统设置里选的原厂锁屏样式。").visual(Visual.LOCK_STYLE)
                .option("5", "编辑排版", "杂志式衬线大字，像一页报纸。").option("6", "瑞士网格", "严格网格与无衬线粗体，信息清晰。")
                .option("7", "像素", "1-bit 像素字体和像素图标，复古掌机风格。").option("8", "液态玻璃", "纸纹波浪背景上浮起玻璃质感卡片。").option("9", "编辑排版-横", "横版构图，横转手机阅读：大号时间加方框，右侧倒数日框，右下电池与通知，左下格言。").option("10", "编辑排版-横-逆", "编辑排版-横 旋转 180°，适合手机反向横转阅读。");
        put("lock.weather").summary("在自定义锁屏上显示天气").what("在锁屏卡片上显示当前天气、温度和图标。原厂锁屏的天气来自桌面时钟/天气小组件，桌面上没放那个小组件时它就没有数据。选择「天气App」后，墨控直接读取系统预装的天气软件（墨迹天气）已经下载的天气，不需要桌面小组件，仍然不额外联网。")
                .cost("「天气App」用的是天气软件最后一次下载的结果；开机后没打开过天气软件、长期不打开它、或把它冻结，都可能取不到，此时自动退回原厂数据。")
                .option("default", "原厂桌面组件", "沿用原厂锁屏天气，数据来自桌面时钟/天气小组件；没有放小组件时通常取不到。")
                .option("moji", "天气App", "直接读取系统预装的天气软件（墨迹天气）已下载的数据，不需要桌面小组件。");
        put("lock.notify").summary("锁屏底部显示通知条").what("锁屏倒数日下方那一块位置只有一处，本页是「显示通知」这个总开关。开启后在来源里二选一：系统通知或微信未读。关闭则整块都不画：编辑排版的「通知 N 条」框、横版的右下通知框、瑞士与液态玻璃右下角的通知数、像素样式的通知数和通知一格都会消失，腾出的位置不会再摆空框。")
                .cost("只影响显示，通知照常统计；关掉再打开马上恢复，不用等新的通知。想在那块位置显示无线、蓝牙等开关情况，请开启「显示设备状态」——两者互斥，开一个会自动关掉另一个。").option("system", "系统通知", "统计可清除的系统通知，不依赖微信。")
                .option("wechat", "微信", "只统计微信未读消息，适合安装了微信的设备。");
        put("lock.ext").summary("锁屏底部改显示设备开关状态").what("同一块位置（倒数日下方、上滑解锁提示上方）改画设备状态：无线网络、蓝牙、声音模式、飞行模式是否开启。要显示哪几项，到「显示信息修改」里逐个开关；关掉的项不再占位，其余各项自动均分宽度。本页选的是画成什么样：跟随样式、图标版还是文字版。")
                .cost("只读显示，不会改动无线、蓝牙、飞行模式或铃声音量。与「显示通知」互斥：开启本项会自动关闭「显示通知」，反之亦然；两项都关时那块位置留空。像素样式是英文标牌（ON / OFF），固定用图标版；瑞士网格本身就是纯文字排版，选「跟随样式」时用文字版。")
                .option("auto", "跟随样式", "编辑排版、横版用图标版，瑞士网格用文字版，与各样式原本的观感一致。")
                .option("icon", "图标版", "左侧一个线描图标、右侧「开 / 关 / 静音 / 有声」。")
                .option("text", "文字版", "「无线|开」这类类型加竖线加状态，两边同字号同基线，没有图标。");
        put("lock.ext.edit").summary("选择设备状态里显示哪几项").what("锁屏底部那块位置宽度固定，项越多每格越窄。这里逐项决定画不画：无线网络、蓝牙、声音模式、飞行模式。关掉的项直接不占位，剩下的自动均分宽度；四项全关时那块位置留空。")
                .cost("四项开关只影响显示，不会改变无线、蓝牙、飞行模式或铃声音量本身。四个都是默认显示：新装或升级后不用先来这里设置；关掉某一项才需要记住。画成图标还是文字在上一页「显示设备状态」里选。");
        put("lock.ext.wifi").summary("设备状态里是否显示无线网络").what("关掉后锁屏设备状态条里不再出现无线这一格，其余各项自动补位。默认显示；只影响这一格，不会关闭无线网络。").option("default", "显示无线", "");
        put("lock.ext.bt").summary("设备状态里是否显示蓝牙").what("关掉后锁屏设备状态条里不再出现蓝牙这一格。默认显示；只影响这一格，不会关闭蓝牙。").option("default", "显示蓝牙", "");
        put("lock.ext.audio").summary("设备状态里是否显示声音模式").what("关掉后锁屏设备状态条里不再出现声音这一格（其余样式里是「静音 / 有声」，像素样式里是喇叭的 ON / OFF）。默认显示；只读铃声音量模式，不会改音量。").option("default", "显示声音", "");
        put("lock.ext.airplane").summary("设备状态里是否显示飞行模式").what("关掉后锁屏设备状态条里不再出现飞行这一格。默认显示；只读飞行模式状态，不会改变它。").option("default", "显示飞行", "");
        put("lock.countdown").summary("一个开关管显示，本页直接编辑事项").what("打开后在锁屏上显示事项倒数日：最多三个，每组一个事项和日期，多个按目标日先后排列。下方就是事项编辑器——事项名可留空；日期为空该组停用。")
                .cost("关闭后锁屏不再显示倒数日，已填的事项和日期仍保留，再打开即恢复。").option("default", "显示倒数日", "");
        put("lock.countdowns").summary("倒数日事项的数据存储位置").what("最多三个倒数日，每组有独立的「农历日期」和「显示」开关：开启农历后按农历年/月/日计算（如选 2027-01-01 即 2027 年春节）；关闭显示可暂时隐藏该组而不删除。编辑入口已并入上一页「锁屏倒数日」，这里是它的数据保存位置，旧配置继续有效。")
                .option("@countdowns", "编辑事项", "每组一个事项和日期，在「锁屏倒数日」页下方编辑。");
        put("lock.lyric").summary("锁屏和息屏画面显示当前一句歌词").what("播放音乐时，在锁屏画面和息屏显示区显示当前这一句歌词，随播放逐句更新。支持在媒体信息里带歌词的播放器，以及 Lyric Getter。")
                .cost("每换一句歌词都要刷新一次锁屏，刷新方式见“歌词的刷新方式”。").option("default", "显示歌词", "");
        put("lock.statusbar").summary("状态栏左侧滚动显示歌词").what("播放音乐时，在状态栏左侧显示当前歌词。停止播放或关闭本项后，通知图标恢复显示。").option("default", "显示歌词", "");
        String modeNote = "三种刷新方式的速度、灰阶和闪黑情况见上图。";
        put("lock.first").summary("锁屏后第一次画锁屏用哪种刷新").what("按电源键锁屏后，第一次画出锁屏画面时使用的刷新方式。" + modeNote)
                .before("不开启时沿用系统决定的方式。").cost("阅读模式更快、不闪黑，但可能留下上一个应用的淡淡残影；清晰全刷会闪一下黑，残影最少。")
                .visual(Visual.REFRESH_MODES)
                .option("3", "阅读模式", "约 0.45 秒、不闪黑。实测能减少锁屏时的空白等待。").option("2", "清晰全刷", "约 0.9 秒、闪一下黑，把上一个画面的残影清干净。")
                .term("刷新方式", "阅读模式", "清晰全刷", "残影");
        put("lock.update").summary("锁屏时每分钟走时、来通知用哪种刷新").what("锁屏期间时钟每分钟变化、来新通知时，局部刷新用的方式。" + modeNote)
                .before("不开启时沿用系统决定的方式。").visual(Visual.REFRESH_MODES)
                .option("3", "阅读模式", "不闪黑，适合每分钟一次的小变化。").option("2", "清晰全刷", "每次都闪黑，最清楚，但一分钟闪一次比较打扰。")
                .term("刷新方式", "阅读模式", "清晰全刷");
        put("lock.lyricmode").summary("歌词每换一句用哪种刷新").what("锁屏歌词每换一句时的刷新方式。" + modeNote).before("不开启时沿用系统决定的方式。").visual(Visual.REFRESH_MODES)
                .option("3", "阅读模式", "不闪黑，文字边缘平滑。").option("2", "清晰全刷", "每句都闪黑，残影最少。").option("9", "极速模式", "最快、最省电，文字边缘略有颗粒和残影。")
                .term("刷新方式", "极速模式", "阅读模式", "清晰全刷", "残影");
        put("lock.delay").summary("按下电源键后多久画锁屏").what("锁屏时屏幕电源要先切换状态。画得太早，屏幕还没准备好，会出现上下半屏不一致的撕裂。")
                .cost("300 毫秒是实测不撕裂的最低值。数值越小锁屏出现得越快。")
                .option("300", "300 毫秒", "最快，实测不撕裂。").option("400", "400 毫秒", "留一点余量。").option("500", "500 毫秒", "最稳妥。");
        put("lock.dither").summary("消除锁屏上偶尔出现的横向细线").what("锁屏画面需要量化成16级灰阶。原厂误差扩散在某些灰度下会排出横向细线。开启后在符合条件的锁屏路径改为就近取16级灰阶。显示分类里的灰阶算法选「保持现状」时继续保留此处理；明确选择其他算法时，以模式算法为准。")
                .before("锁屏背景、卡片阴影处偶尔出现水平细线。").after("细线消失，画面更干净。").cost("灰色渐变的层次会少一些，字体边缘的灰阶需要你实际看一看是否满意。需要 GPU 显示后端。")
                .option("nearest", "点量化", "").term("抖动", "GPU 显示后端");

        put("desktop.theme").summary("三套配套的桌面外观").what("给原厂桌面换一套完整的外观。下面的图标、字体、壁纸、组件可以分别开关；图标位置和桌面布局保持不变。").visual(Visual.DESKTOP_THEME)
                .option("glass", "流光玻璃", "浅色玻璃质感卡片，细线图标。").option("paper", "纸间", "纸张质感与衬线字体，像一本笔记本。").option("ink", "墨构", "黑底反白、高对比，粗线条图标。");
        put("desktop.icons").summary("应用图标换成主题配套的黑白图标").what("把常用应用的图标换成与主题匹配的黑白图标（覆盖 39 个常用应用），在墨水屏上比彩色图标清晰得多。").cost("关闭后立即恢复原厂图标。").option("default", "主题图标", "");
        put("desktop.font").summary("只换桌面图标下方应用名的字体").what("仅对原厂桌面图标下方的应用名生效：纸间主题用系统衬线体、墨构用窄体、流光玻璃用普通黑体，字号与字色也随主题调整。它不加载字体文件、也不改系统全局字体，图标以外任何界面都不受影响。").cost("关闭后恢复原厂字体。想要全局换字体请用「字体」。").option("default", "主题字体", "");
        put("font.dir").summary("换掉系统正在用的字体").what("填字体所在目录（默认是海信主题字体目录，也可以填 TF 卡上的文件夹，或点「选择目录」从文件管理器挑），目录里的 TTF/OTF 会逐个列出来，可用搜索框按名字过滤；墨控按文件头识别，文件不用改扩展名。点某个字体右侧的「应用」，字体文件会以独立路径写进 /data/fonts、把 HmctFont.ttf 与 persist.sys.font* 指过去，并微扰 font_scale 让所有界面立即重建——照海信主题商店做法复刻的全局热切换，状态栏、锁屏、桌面与正在运行的应用立刻一起换字，不用重启。目录读不到时，可用「从文件选择」直接挑一个字体文件。").cost("字体缺少中文字形时部分界面会出现方块，点「恢复原厂字体」即可退回系统默认字体。全局替换，桌面应用字体也会一起换。");
        put("font.pick").summary("记录上次应用的字体").what("只保存最近一次应用成功的字体名，用于在页面上标出「上次已应用」，不单独出现在分类里。");
        put("font.saf").summary("从文件管理器选中的字体记录").what("兼容旧版本：用系统文件选择器挑过字体时会写在这里。功能已经合并到「字体文件」一页，不再单独显示。");
        put("desktop.wallpaper").summary("桌面显示主题底纹").what("在桌面背景显示主题配套的底纹材质。只改变显示，不替换你的壁纸文件。").option("default", "主题材质", "");
        put("desktop.widget").summary("原厂时钟插件位置换成主题组件").what("把原厂时钟插件所在位置换成主题风格的组件。").visual(Visual.DESKTOP_WIDGET)
                .option("clock", "时钟", "大字时间与日期。").option("weather", "天气", "当前天气与温度。").option("music", "音乐", "正在播放的歌曲，可直接控制播放。");

        put("fix.exceptions").summary("防止显示服务出错导致系统界面重启").what("原厂墨水屏服务在处理某些刷新请求出错时，会把错误直接抛给系统，严重时整个系统界面重启（看到开机动画）。开启后拦截这类错误并记录，不让它扩散。")
                .before("偶尔毫无征兆地“软重启”。").after("同类错误只会让那一次刷新失败，系统照常运行。").option("default", "异常保护", "");
        put("fix.configlock").summary("防止刷新配置文件被同时写坏").what("系统保存“每个应用用哪种刷新方式”的配置文件时没有加锁，两处同时写入可能把文件写坏；文件一旦损坏，下次开机会卡住。开启后让写入排队进行。").option("default", "修复写入锁", "");
        put("fix.modes").summary("切换应用时少一次多余刷新").what("切换应用时，原厂会先把上一个应用的刷新方式再下发一遍，然后才下发新应用的，造成一次没有意义的刷新。开启后只在方式真正改变时才下发。")
                .before("切应用时偶尔多刷一下。").after("每次切换只刷必要的次数。").option("default", "模式去重", "");
        put("fix.video").summary("看完视频后不再一直停在防闪烁模式").what("播放视频或打开相机时，系统会把屏幕切到防闪烁模式；原厂退出时有时忘了切回，之后所有应用都停在这个模式。开启后按每个播放会话计数，全部结束才切回。")
                .option("default", "生命周期清理", "");
        put("fix.permissions").summary("只允许应用调整自己的刷新方式").what("原厂墨水屏服务对任何应用开放，任意应用都能改别的应用的刷新方式，甚至改写全局配置。开启后只允许系统和应用自身调整。")
                .cost("个别依赖这个漏洞的第三方墨水屏工具可能失效。").option("default", "调用方校验", "");
        put("guard.config").summary("刷新配置损坏时自动还原，避免卡开机").what("每应用刷新配置文件一旦损坏，原厂会在开机时崩溃、无法进入系统。开启后每次开机前检查该文件，发现损坏就用最近一次正常的备份还原；运行中每 6 小时备份一次。")
                .cost("需要完整重启后开始保护。").option("default", "配置守护", "").term("完整重启");
        put("qs.extend").summary("快捷设置里能选到第三方应用的磁贴").what("原厂在「编辑磁贴」的候选列表里塞了一个开关，只枚举自家磁贴，装有磁贴的第三方应用（VPN、下载、清理等）一个都不列。开启后按原厂自己的枚举流程再补一遍，这些应用的磁贴就会出现在可添加列表里。")
                .before("不开启时：可添加列表只有系统与海信自带磁贴。").after("开启后：再打开一次「编辑磁贴」即可看到第三方应用，长按拖动就能加到面板上。")
                .cost("只改候选列表的枚举，不碰系统安全特性，也不要重启。个别应用自带的磁贴本身有问题时，加到面板上仍会显示成占位图标。").option("default", "列出第三方磁贴", "").term("磁贴");
        put("experiment.cache").summary("显示后端少做几次重复查询").what("GPU 显示后端每帧都要查询 GPU 程序参数的位置，开启后把结果缓存起来。")
                .cost("只是局部优化，日常几乎感觉不到差别。需要 GPU 显示后端。").option("default", "缓存参数位置", "").term("GPU 显示后端");
        put("experiment.video").summary("旧版视频选择，已归入显示分类").what("旧配置继续保留。「显示 → 视频场景算法」选保持现状时沿用这里的选择；新页面明确选择算法时优先使用新选择。")
                .before("不开启时：默认使用蓝噪声与迟滞。").after("选“原厂误差扩散”时：使用与原厂对应的网点算法。当前 GPU 后端也有它的实现，逐行依赖较多，本机视频对照中明显更慢。")
                .cost("需要 GPU 显示后端。原厂算法只建议短时间对比画质。")
                .option("blue", "蓝噪声与迟滞", "推荐，也是不开启时的默认。均匀网点，静止区域不闪。").option("stock", "原厂误差扩散", "用于画质对照，速度取决于实际场景。").term("抖动", "GPU 显示后端");

        put("refresh.master").summary("点击、按键或阅读时自动刷新，减少残影").what("墨水屏在点击、翻页、按键后若长时间不刷新会积累残影。开启后，墨控统计你的操作次数，达到设定次数时触发一次清晰刷新；触发方式、次数、延时和周期可在下面分别调整。")
                .cost("每次触发都会完整刷新一次，画面会短暂闪动，耗电略增。本功能依赖墨控自己的后端，需要安装并完整重启。")
                .option("default", "启用自动刷新", "").term("残影", "清晰全刷", "完整重启");
        put("refresh.touch").summary("点击或滑动后计数").what("监听屏幕上的点击与滑动手势，累计到设定的触发次数后刷新。")
                .option("default", "响应点击", "");
        put("refresh.key").summary("音量键、翻页键计数").what("按下音量键或方向键等实体按键后计数；电源键、返回键和主页键不计入。")
                .option("default", "响应按键", "");
        put("refresh.reading").summary("阅读界面自动刷新").what("识别到整屏以文字为主的界面（如阅读器）时，把翻页也计入刷新，改善翻页残影。")
                .option("default", "识别阅读界面", "");
        put("refresh.interval").summary("累计多少次操作后刷新一次").what("操作次数达到这个数值时触发一次刷新，然后重新计数。")
                .cost("数值越小刷新越频繁，残影越少但闪动越多。")
                .option("5", "5次", "最频繁，残影最少。").option("10", "10次", "默认，平衡。").option("15", "15次", "较省电。").option("20", "20次", "更省电。").option("30", "30次", "最省电。")
                .option(com.inkcontrol.core.FeatureDefinition.NUMBER_TOKEN, "自定义", "手动输入 1–999 之间的次数。");
        put("refresh.debounce").summary("连续操作多久算一次").what("滑动往往一次就上报很多次，按键也可能连按。间隔内的重复操作只算一次，避免比设定的触发次数更早刷新。")
                .cost("间隔越大越不容易被连续滑动误触发，代价是快速连点也可能被合并成一次。")
                .option("1000", "1秒", "推荐：一次滑动大致只算一次。").option("500", "500毫秒", "更灵敏，适合慢速逐次操作。").option("2000", "2秒", "更迟钝，基本按手势次数计。").option("0", "关闭", "不合并，逐次计数。")
                .option(com.inkcontrol.core.FeatureDefinition.NUMBER_TOKEN, "自定义", "手动输入 0–999 毫秒。");
        put("refresh.delay").summary("最后一次操作后多久刷新").what("达到触发次数后，等待这段时间再刷新，避免连续操作时打断你。")
                .option("0", "立即", "达到次数后马上刷新。").option("300", "300毫秒", "稍等片刻。").option("500", "500毫秒", "默认。").option("1000", "1000毫秒", "等操作完全停下。");
        put("refresh.apps").summary("只在指定应用里计入刷新").what("开启后按这里的名单过滤：白名单只统计名单内应用的操作，其它应用不计入；黑名单跳过名单内应用的操作。关闭时对所有应用生效。")
                .cost("只影响点击、滑动、按键和阅读界面的计数；周期性刷新不受名单限制，仍按设定时间执行。")
                .note("白名单与黑名单各自保存，互不影响。名单以包名保存，卸载或停用应用后条目会保留，可再次进入列表取消勾选。")
                .option(com.inkcontrol.core.FeatureDefinition.APPS_TOKEN, "黑白名单", "");
        put("refresh.period").summary("每隔一段时间自动刷新一次").what("无论是否操作，每隔这段时间主动刷新一次屏幕，适合长时间静止显示。")
                .cost("关闭独立开关后不再周期刷新。")
                .option("60", "1分钟", "默认。").option("300", "5分钟", "").option("600", "10分钟", "").option("1800", "30分钟", "最省电。")
                .option(com.inkcontrol.core.FeatureDefinition.NUMBER_TOKEN, "自定义", "手动输入 1–999 之间的分钟数。");

        put("wave.lock").summary("遮挡一段时间后息屏").what("近距离传感器被持续遮挡达到「遮挡延时」设定的时间后，主动锁定并关闭屏幕。适合放进裤袋、盖上衣物时避免误触。需要系统无障碍中启用「墨控体感」服务。").option("default", "遮挡息屏", "");
        put("wave.wake").summary("移开遮挡后唤醒").what("遮挡结束（移出口袋、移开手掌）后点亮屏幕。若未设置安全锁屏，会进一步尝试退出锁屏；若有安全锁屏，则只亮屏。需要系统无障碍中启用「墨控体感」服务。").option("default", "移开唤醒", "");
        put("wave.coverDelay").summary("遮挡持续多久才息屏").what("传感器短暂被碰一下不会立刻息屏，只有持续遮挡超过这个时间才锁定屏幕，避免误触。")
                .cost("数值越小越灵敏，越大越不容易误触。")
                .option("1000", "1 秒", "默认，平衡灵敏与误触。").option("500", "500 毫秒", "更灵敏，适合稳定遮挡场景。")
                .option("2000", "2 秒", "较迟钝，明显减少误触。").option("3000", "3 秒", "最迟钝，仅在明确遮挡时息屏。");
    }

    private Guide() {}
}
