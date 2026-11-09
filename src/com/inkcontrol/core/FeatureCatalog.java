package com.inkcontrol.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import static com.inkcontrol.core.FeatureDefinition.ApplyMode.*;

public final class FeatureCatalog {
    private static final List<FeatureDefinition> FEATURES = new ArrayList<>();
    /**
     * 必选参数：没有开关，配置里恒为开启，界面只能挑一个值。
     * 翻页动画的风格／速度／分块数少任何一个都拼不出完整的 settings.conf，
     * 所以它们不像「反转方向」「音量键翻页」那样可以关掉，只能换值。
     */
    private static final Set<String> REQUIRED = new HashSet<>(Arrays.asList(
            "pageanim.style", "pageanim.spacing", "pageanim.bands"));
    /** 这一项是否没有开关、始终启用。 */
    public static boolean required(String id) { return REQUIRED.contains(id); }
    /**
     * 刷新面板底部四个位置可选的动作（取值, 面板上显示的名字）。
     *
     * <p>前十个就是控制中心那排磁贴；{@code refresh}／{@code lock}／{@code cover}／{@code pageanim}
     * 是墨控自己的四个开关（自动刷新、锁屏主题、遮挡息屏、翻页动画），点一下就地开关（锁屏主题
     * 例外，点它直接进设置页）；{@code quick} 打开墨控的快捷设置页；{@code pin} 就是原厂那个
     * 「截屏便签」，保留它自己的图标、文字与截屏监听器。
     */
    public static final String[][] PANEL_ACTIONS = {
            {"wifi", "WiFi"}, {"bt", "蓝牙"}, {"ring", "铃声"}, {"airplane", "飞行模式"},
            {"torch", "手电筒"}, {"rotate", "自动旋转"}, {"page", "整屏翻页"},
            {"dnd", "免打扰"}, {"saver", "省电模式"}, {"float", "悬浮球"},
            {"refresh", "自动刷新"}, {"lock", "锁屏主题"}, {"cover", "遮挡息屏"},
            {"pageanim", "翻页动画"}, {"gcsweep", "全刷扫过"}, {"motto", "刷新格言"},
            {"quick", "墨控快捷设置"}, {"pin", "截屏便签"}};

    /** 这个位置不显示按钮（选择框里排在最上面的「无」）。 */
    public static final String PANEL_NONE = "none";

    /** 四个位置共用同一套动作，只是默认值不同；{@code defaultValue()} 取第一个取值，所以把默认项排到最前。 */
    private static String panelValues(String first) {
        StringBuilder out = new StringBuilder();
        for (String[] action : panelActions(first)) out.append(out.length() == 0 ? "" : ",").append(action[0]);
        return out.toString();
    }
    private static String panelLabels(String first) {
        StringBuilder out = new StringBuilder();
        for (String[] action : panelActions(first)) out.append(out.length() == 0 ? "" : ",").append(action[1]);
        return out.toString();
    }
    /**
     * 某个位置的完整取值表：默认动作排最前（{@code defaultValue()} 取第一个），其余动作随后，
     * 末尾补上「无」与单个应用包名（{@code @package}，选择框里的 APP）。
     */
    private static String[][] panelActions(String first) {
        String[][] ordered = new String[PANEL_ACTIONS.length + 2][];
        int at = 0;
        for (String[] action : PANEL_ACTIONS) if (action[0].equals(first)) ordered[at++] = action;
        for (String[] action : PANEL_ACTIONS) if (!action[0].equals(first)) ordered[at++] = action;
        ordered[at++] = new String[]{PANEL_NONE, "无"};
        ordered[at] = new String[]{FeatureDefinition.PACKAGE_TOKEN, "某个应用"};
        return ordered;
    }

    static {
        add("display.gpu", "显示", "GPU 显示后端", "底层库变更，完整重启后生效", "backend", "", REBOOT, true, "default", "启用显示后端");
        for (String scope : DisplayAlgorithms.SCOPES) {
            boolean gray = DisplayAlgorithms.gray(scope);
            String values = gray ? "default,stock,nearest,bayer16" : scope.equals("video")
                    ? "default,blue,bayer,stock,threshold" : "default,stock,bayer,blue,threshold";
            String labels = gray ? "保持现状,原厂误差扩散,点量化,16级 Bayer" : scope.equals("video")
                    ? "保持现状,蓝噪声,Bayer,原厂误差扩散,固定阈值" : "保持现状,原厂算法,Bayer,蓝噪声,固定阈值";
            add("display.alg." + scope, "显示", DisplayAlgorithms.name(scope) + "算法", "只调整本模式的量化算法，不切换刷新模式", "backend", "display.gpu", HOT, true, values, labels);
            if (!gray) add("display.hyst." + scope, "显示", DisplayAlgorithms.name(scope) + "迟滞", "独立控制 Bayer、蓝噪声和固定阈值的历史稳定；原厂算法不适用", "backend", "display.gpu", HOT, true,
                    "default,on,off", "跟随默认,开启,关闭");
        }
        add("display.alg.stock", "显示", "改用原厂显示算法", "优先级高于 GPU 显示后端：开启后所有模式的像素量化全部交回原厂，GPU 只保留触摸响应与全刷复用", "backend", "", HOT, true, "default", "原厂算法");
        add("display.sampling", "显示", "触摸响应加速", "队列余量降至两张再采样", "backend", "display.gpu", HOT, false, "2", "余量两张");
        add("display.idle", "显示", "停顿后首次响应加速", "关闭时恢复原厂空闲断电代码", "backend", "display.gpu", REBOOT, false, "10", "空闲十秒");
        add("display.dynamic", "显示", "滑动时减少系统查询", "关闭重复触摸查询，关闭功能时条件恢复", "backend", "", HOT, false, "0", "关闭原厂动态切换");
        add("display.apps", "显示", "阅读类应用改用极速刷新", "阅读模式515调整为521；启动前应用，停用时字段级恢复", "backend", "", REBOOT, false, "521", "515 → 521");
        add("motion.master", "动画", "过渡动画总开关", "各场景仍可单独选择", "system", "", HOT, false, "default", "按场景应用");
        String[] scenes = {"app_open", "app_close", "in_app_forward", "in_app_back", "dialog", "gc_clear", "lock", "unlock"};
        String[] names = {"打开应用", "返回桌面", "应用内前进", "应用内后退", "对话框与菜单", "全刷扫过", "进入锁屏", "唤醒过渡"};
        for (int i = 0; i < scenes.length; i++) {
            // 「无」排在首位：默认取值就是它，等同于保持原厂的瞬间切换，
            // 因此各场景既能选效果，也能明确回到原厂（旧版这里是开关）。
            String values = i == 5 ? "gcsweep" : i >= 6 ? "none,scan,ink,dots,blind" : "none,kiris,kwipe,ink,dots,scan,blind";
            String labels = i == 5 ? "全刷扫过" : i >= 6 ? "无,扫描线,墨迹晕开,网点长大,百叶窗" : "无,方形展开,关键帧擦除,墨迹晕开,网点长大,扫描线,百叶窗";
            // 总开关与全刷扫过不再标「实验」，其余场景保留
            add("motion." + scenes[i], "动画", names[i], "选「无」保持原厂瞬间切换；关闭此场景同样恢复原厂", "system", "motion.master", HOT, i != 5, values, labels);
        }
        add("motion.dim", "动画", "去掉对话框背景变暗", "独立于动画总开关", "system", "", HOT, false, "default", "移除变暗");
        add("motion.steps", "动画", "关键帧数", "关闭此选项时使用两步", "system", "motion.master", HOT, false, "2,3,4", "两步,三步,四步");
        add("motion.hold", "动画", "关键帧停留", "关闭时随温度自动计算", "system", "motion.master", HOT, false, "0,60,100,200,300,500,1000,@curve", "温度自适应,60ms,100ms,200ms,300ms,500ms,1000ms,自定义曲线");
        add("motion.origin", "动画", "展开起点", "关闭时使用触摸位置", "system", "motion.master", HOT, false, "touch,center", "触摸位置,屏幕中心");
        add("motion.direction", "动画", "动画擦除方向", "关键帧擦除、扫描线、百叶窗从哪一侧开始；关闭时自动选择", "system", "motion.master", HOT, false, "auto,ltr,rtl,ttb,btt", "自动,从左向右,从右向左,从上向下,从下向上");
        add("motion.gc_direction", "动画", "全刷扫过方向", "全刷扫过从哪一侧开始；自动＝横向从左向右、纵向从上向下", "system", "motion.gc_clear", HOT, false, "auto,ltr,rtl,ttb,btt", "自动,从左向右,从右向左,从上向下,从下向上");
        // 全刷扫过自己的形状：按翻页动画那套「分块展开」的写法给全刷也用一遍。
        // 只改延迟图（谁先开始刷新），不改波形本身，所以去残影效果不变。
        add("motion.gc_style", "动画", "全刷扫过风格", "光带扫过的形状；斜向与环绕按屏幕几何均匀展开，不使用方向设置", "system", "motion.gc_clear", HOT, false,
                "horizontal,vertical,diag,antidiag,ring,center", "横向展开,纵向展开,斜向分块,反斜向分块,环绕分块,中心展开");
        // 全刷扫过自己的速度：光带扫过整屏用多少帧。帧数越少扫得越快、越接近整屏同时刷新；
        // 与「波形动画速度」分开，改别的动画快慢不会连带影响去残影这一步。
        // 依赖挂在总开关上（和「波形动画速度」一致），全刷扫过没开时也能先设好档位。
        add("motion.gc_speed", "动画", "全刷扫过速度", "光带扫过整屏的时长；帧数越少越快，1 帧几乎整屏同时刷新", "system", "motion.master", HOT, false, "1,4,8,12,18", "极快（1 帧）,快（4 帧）,适中（8 帧）,原厂节奏（12 帧）,慢（18 帧）");
        add("motion.spread", "动画", "波形动画速度", "以扫描帧为单位，关闭时使用12帧", "system", "motion.master", HOT, true, "12,1,6,18,24", "12帧,1帧,6帧,18帧,24帧");
        add("motion.scale", "动画", "应用内小动画", "关闭时恢复原厂倍率", "system", "", HOT, false, "0,0.5,1", "关闭动画,半速时长,原时长");
        add("lock.style", "锁屏显示", "锁屏主题", "关闭时恢复有效的原厂样式", "systemui", "", HOT, false, "5,6,7,9,10,8", "编辑排版,瑞士网格,像素,编辑排版-横,编辑排版-横-逆,液态玻璃");
        add("lock.weather", "锁屏显示", "锁屏天气", "跟随原厂桌面组件，或直接读取系统预装的天气App", "systemui", "lock.style", HOT, false, "default,moji", "原厂桌面组件,天气App");
        // 锁屏底部那一块位置只有一处，两个总开关二选一：「显示通知」或「显示设备状态」。
        // 互斥由界面统一保证（MainActivity.change 里开一个就自动关另一个），
        // 所以配置里正常只会有一个为开；设备状态条的四项由 lock.ext.* 单独控制显示与否。
        add("lock.notify", "锁屏显示", "显示通知", "锁屏底部显示通知条，来源可选系统通知或微信未读；也可以选「不显示」先留空", "systemui", "lock.style", HOT, false, "system,wechat,none", "系统通知,微信,不显示");
        // 「显示设备状态」：auto 跟随样式（编辑排版/横版用图标，瑞士网格用文字），
        // icon/text 强制一种。默认 auto 保持各样式原有的观感。
        add("lock.ext", "锁屏显示", "显示设备状态", "锁屏底部改显示无线网络、蓝牙、声音模式与飞行模式；开启后自动关闭「显示通知」", "systemui", "lock.style", HOT, false, "auto,icon,text", "跟随样式,图标版,文字版");
        // 「显示信息修改」是个纯页面（@panel）：自己没有开关，进去才改下面那四格。
        // 四个开关仍是正式功能（各自写配置、可单独进详情页），只是不再铺在分类列表里。
        add("lock.ext.edit", "锁屏显示", "显示信息修改", "选择设备状态里显示哪几项：无线网络、蓝牙、声音模式、飞行模式", "systemui", "lock.ext", HOT, false, "@panel", "修改");
        // 四项显示开关：默认全开（旧配置里没有这几个键也按显示处理），各自关掉的才记住。
        add("lock.ext.wifi", "锁屏显示", "显示无线网络", "锁屏设备状态里显示无线网络开关情况", "systemui", "lock.ext", HOT, false, "default", "显示无线", true);
        add("lock.ext.bt", "锁屏显示", "显示蓝牙", "锁屏设备状态里显示蓝牙开关情况", "systemui", "lock.ext", HOT, false, "default", "显示蓝牙", true);
        add("lock.ext.audio", "锁屏显示", "显示声音模式", "锁屏设备状态里显示静音 / 有声；只读，不改铃声音量", "systemui", "lock.ext", HOT, false, "default", "显示声音", true);
        add("lock.ext.airplane", "锁屏显示", "显示飞行模式", "锁屏设备状态里显示飞行模式开关情况", "systemui", "lock.ext", HOT, false, "default", "显示飞行", true);
        add("lock.countdown", "锁屏显示", "锁屏倒数日", "锁屏右侧显示事项倒数，最多三个，事项名可留空", "systemui", "lock.style", HOT, false, "default", "显示倒数日");
        add("lock.countdowns", "锁屏显示", "倒数日事项", "每组一个事项和日期；日期为空则该组停用", "systemui", "lock.countdown", HOT, false, "@countdowns", "编辑事项");
        add("lock.lyric", "锁屏显示", "锁屏歌词", "支持现有媒体歌词与 Lyric Getter 广播", "systemui", "", HOT, false, "default", "显示歌词");
        add("lock.statusbar", "锁屏显示", "状态栏歌词", "停止或关闭后恢复通知图标", "systemui", "", HOT, false, "default", "显示歌词");
        add("lock.first", "锁屏显示", "锁屏瞬间的刷新方式", "阅读模式曾改善空白，残影需观察", "systemui", "", HOT, true, "3,2", "阅读模式3,清晰全刷2");
        add("lock.update", "锁屏显示", "走时与通知的刷新方式", "关闭时沿用系统模式", "systemui", "", HOT, true, "3,2", "阅读模式3,清晰全刷2");
        add("lock.lyricmode", "锁屏显示", "歌词的刷新方式", "关闭时沿用系统模式", "systemui", "lock.lyric", HOT, true, "3,2,9", "阅读模式3,清晰全刷2,极速模式9");
        add("lock.delay", "锁屏显示", "锁屏画面出现时机", "最低300ms，避免过早送图", "systemui", "", HOT, false, "300,400,500", "300ms,400ms,500ms");
        add("lock.dither", "锁屏显示", "消除锁屏细线", "使用点量化，字体灰阶需实屏观察", "backend", "display.gpu", HOT, true, "nearest", "点量化");
        // 翻页动画：GPU 后端开启时不切换 HWC，配置实时下发给 libeinkgpu 的分带波形分支，
        // 两个开关可同时启用、不用重启，分带揭示与 GPU 波形在同一条流水线上生效。
        // 只有 GPU 后端关闭时才挂第三方专用 HWC，那种模式仍是 REBOOT、与其它显示功能互斥。
        add("pageanim.master", "翻页动画", "翻页动画总开关", "GPU 显示后端开启时两者同时生效、实时生效，按「目标阅读器」名单触发；未开 GPU 后端时改挂专用显示库，需完整重启", "backend", "", HOT, false, "default", "启用翻页动画");
        add("pageanim.style", "翻页动画", "翻页风格", "新一页按哪种方式分块、分阶段展开", "backend", "pageanim.master", HOT, false,
                "horizontal,vertical,diag,antidiag,ring,center", "横向展开,纵向展开,斜向分块,反斜向分块,环绕分块,中心展开");
        add("pageanim.bands", "翻页动画", "分块数量", "分块越多，展开越细", "backend", "pageanim.master", HOT, false,
                "12,4,8,16,24,32", "12 块,4 块,8 块,16 块,24 块,32 块");
        add("pageanim.spacing", "翻页动画", "展开速度", "阶段越多展开越明显，也越慢", "backend", "pageanim.master", HOT, false,
                "6,8,12,16,24,40", "6 阶段（更快）,8 阶段,12 阶段（均衡）,16 阶段,24 阶段,40 阶段（最明显）");
        add("pageanim.reverse", "翻页动画", "反转动画方向", "把展开方向掉个头", "backend", "pageanim.master", HOT, false, "default", "反转方向");
        add("pageanim.touch", "翻页动画", "点击与横向滑动翻页", "点击与左右滑动当作翻页手势", "backend", "pageanim.master", HOT, false, "default", "点击滑动");
        add("pageanim.keys", "翻页动画", "实体音量键翻页", "音量键当作翻页键", "backend", "pageanim.master", HOT, false, "default", "音量键翻页");
        // 音量键没有「点在哪一边」的信息，融合分带只能沿用上一次点击/滑动的方向，
        // 表现为「上次点右边，按音量+ 也从右往左」。这一项按习惯覆盖方向，默认开着。
        add("pageanim.vkey", "翻页动画", "音量键遵从翻页直觉", "音量+＝上一页、音量-＝下一页，不沿用上一次点击的方向", "backend", "pageanim.master", HOT, false, "default", "遵从翻页直觉", true);
        add("pageanim.debounce", "翻页动画", "连续操作保护", "翻页连得过快时跳过新动画，翻页本身照常", "backend", "pageanim.master", HOT, false,
                "100,250,500,1000", "100 毫秒,250 毫秒,500 毫秒,1 秒");
        add("pageanim.readers", "翻页动画", "目标阅读器", "只对名单里的阅读器生效；不选则动画不触发", "backend", "pageanim.master", HOT, false, "@packages", "选择阅读器");
        // 「体感息屏唤醒」总开关已下线：遮挡息屏 / 移开唤醒各自就是开关，
        // 两个都关时体感服务不监听传感器。无障碍里的「墨控体感」服务由后端按这两个开关自动增删。
        add("wave.lock", "体感", "遮挡息屏", "近距离传感器被持续遮挡一段时间后锁定并关闭屏幕；开关打开时自动启用「墨控体感」服务", "system", "", HOT, false, "default", "遮挡息屏");
        add("wave.wake", "体感", "移开唤醒", "遮挡结束（移出口袋/移开手掌）后点亮屏幕并尝试退出锁屏；开关打开时自动启用「墨控体感」服务", "system", "", HOT, false, "default", "移开唤醒");
        // 自定义（@number）排在最后，让首值仍是 1000＝默认 1 秒；界面里把「自定义」提到列表最上面。
        add("wave.coverDelay", "体感", "遮挡延时", "遮挡持续多久才息屏，避免误触", "system", "wave.lock", HOT, false, "1000,500,2000,3000,@number", "1秒,0.5秒,2秒,3秒,自定义");
        add("desktop.theme", "桌面", "桌面主题", "保留原厂桌面位置与布局", "launcher", "", HOT, false, "glass,paper,ink", "流光玻璃,纸间,墨构");
        add("desktop.icons", "桌面", "图标换肤", "独立恢复原厂图标", "launcher", "desktop.theme", HOT, false, "default", "主题图标");
        add("desktop.wallpaper", "桌面", "壁纸材质", "仅覆盖显示，不改原厂壁纸文件", "launcher", "desktop.theme", HOT, false, "default", "主题材质");
        add("desktop.widget", "桌面", "主屏组件换肤", "复用原厂时钟插件的位置", "launcher", "desktop.theme", HOT, false, "clock,weather,music", "时钟,天气,音乐");
        add("font.dir", "字体", "字体", "把系统字体换成目录里的任意 TTF/OTF：点应用立刻全局生效（状态栏、锁屏、桌面与所有应用一起换，不用重启）", "backend", "", HOT, false, "@path", "字体目录");
        add("font.pick", "字体", "最近应用的字体", "只记录上次应用成功的字体名，用于在页面标出「上次已应用」", "backend", "", HOT, false, "@font", "字体名");
        add("font.saf", "字体", "文件选择器记录", "旧版从文件管理器选字体的记录，功能已并入「字体文件」页", "backend", "", HOT, false, "@font", "字体名");
        add("fix.exceptions", "系统增强", "防止显示服务引发重启", "恢复时回到原厂处理逻辑", "system", "", HOT, false, "default", "异常保护");
        add("fix.configlock", "系统增强", "防止刷新配置被写坏", "串行化原厂配置写入", "system", "", HOT, false, "default", "修复写入锁");
        add("fix.modes", "系统增强", "切换应用少刷一次", "模式实际改变时正常下发", "system", "", HOT, false, "default", "模式去重");
        add("fix.video", "系统增强", "看完视频恢复正常刷新", "生命周期计数候选，待整合后实机验证", "system", "", HOT, true, "default", "生命周期清理");
        add("fix.permissions", "系统增强", "限制其他应用改刷新方式", "按UID及证书校验调用方，候选", "system", "", HOT, true, "default", "调用方校验");
        add("guard.config", "系统增强", "刷新配置损坏自动还原", "启动前检查，备份保留供恢复", "backend", "", REBOOT, false, "default", "配置守护");
        // 海信用 ro.hx.test 当闸门，只有它为真才枚举第三方 TileService；而 ro.hx.test 同时是
        // 安全特性总开关（SafeFeature.ENABLE = safe.enable && !ro.hx.test），不能碰。
        // 这里改在 SystemUI 里补一次原厂私有的 addPackageTiles，只影响「编辑磁贴」的候选列表。
        add("qs.extend", "系统增强", "控制中心拓展", "快捷设置的可添加磁贴里列出装有磁贴的第三方应用，打开编辑页即生效", "systemui", "", HOT, false, "default", "列出第三方磁贴");
        // 原厂把导航栏背景写死在 BarTransitions 里（资源覆盖无效），只能在画完之后重绘；
        // 开关是运行期读的，改完下一帧就变，不用重启系统界面。
        add("navbar.white", "系统增强", "三大金刚键漂白", "把底部导航栏的黑底白键改成白底黑键，切换开关立即生效", "systemui", "", HOT, false, "default", "白底黑键");
        // 纯页面项：进去是备份/恢复的操作页，自己没有开关。
        // 只搬 settings 三张表并逐条回放，不覆盖 /data/data、不碰 packages.xml（整包盖回去会让「设置」启动即闪退）。
        add("sys.backup", "系统增强", "备份系统设置", "把系统设置存成快照（息屏与锁屏时长、音量、输入法、字体名、护眼等），恢复出厂后逐条写回", "backend", "", HOT, false, "@panel", "打开备份");
        // 海信把「安全输入服务」硬编码进 system_server 的 InputMethodManagerService：密码框一律把
        // mCurMethodId 改成 com.android.inputmethod.pinyin/.PinyinIME，完全不检查这个包是否还在。
        // 冻结该输入服务后 framework 抛 IllegalArgumentException("Unknown id: ...")，设置的密码界面
        // 直接崩溃；应用锁的解锁界面被强杀后系统直接放行目标应用（fail-open，锁形同虚设）。
        // 这里由 SystemUI 接管原厂开关 Settings.Secure.can_use_safe_inputmethod：开启写 0，关闭时条件恢复。
        add("fix.safeime", "系统增强", "冻结安全输入服务后能输密码", "密码框改用当前输入法，不再因原厂强制切换导致设置崩溃、隐藏应用进不去、应用锁被直接放行；关闭开关时还原原值", "systemui", "", HOT, false, "default", "恢复密码输入");
        // 同一个面板的按钮区：原厂在这台设备上只会生成「截屏便签」一个按钮（另外三个属于
        // 老显示模式），空着一大片。这里在每次弹出后按原厂按钮模板补几个自用按钮。
        add("qs.panel", "快捷面板修改", "快捷面板按钮", "刷新面板底部补上自己的按钮，四个位置的动作在下面选", "systemui", "", HOT, false, "default", "加入按钮");
        // 四个位置各存一个动作；位置顺序就是按钮顺序，所以「排序」等于把两格的动作对调。
        add("qs.panel.btn1", "快捷面板修改", "第 1 个按钮", "面板底部从左数第一个按钮的动作", "systemui", "qs.panel", HOT, false, panelValues("wifi"), panelLabels("wifi"));
        add("qs.panel.btn2", "快捷面板修改", "第 2 个按钮", "面板底部从左数第二个按钮的动作", "systemui", "qs.panel", HOT, false, panelValues("bt"), panelLabels("bt"));
        add("qs.panel.btn3", "快捷面板修改", "第 3 个按钮", "面板底部从左数第三个按钮的动作", "systemui", "qs.panel", HOT, false, panelValues("quick"), panelLabels("quick"));
        add("qs.panel.btn4", "快捷面板修改", "第 4 个按钮", "面板底部从左数第四个按钮的动作", "systemui", "qs.panel", HOT, false, panelValues("pin"), panelLabels("pin"));
        // 面板是盖住屏幕下半部分的系统悬浮窗，点完按钮不会自己收起，会一直挡住后面的界面。
        add("qs.panel.close", "快捷面板修改", "点完收起面板", "点完按钮后自动收起面板，不再挡住界面", "systemui", "qs.panel", HOT, false, "default", "点完收起");
        // 原厂那个「截屏便签」的图标和文字都比补上来的按钮大一圈（原厂按整块面板的尺寸画，
        // 补上的四个按 28dp 图标 / 12sp 文字），默认缩到同一档，四个按钮才齐平。
        add("qs.panel.pinsize", "快捷面板修改", "截屏便签对齐尺寸", "把原厂「截屏便签」的图标和文字缩到和补上的按钮一样大", "systemui", "qs.panel", HOT, false, "default", "统一尺寸", true);
        add("experiment.cache", "实验室", "GPU 参数缓存", "提交阶段局部优化，不承诺整机提速", "backend", "display.gpu", HOT, true, "default", "缓存uniform位置");
        add("experiment.video", "实验室", "视频抖动算法", "关闭时使用 GPU 蓝噪声；可改用原厂误差扩散对比", "backend", "display.gpu", HOT, true, "blue,stock", "蓝噪声与迟滞,原厂误差扩散");
        add("refresh.master", "自动刷新", "自动刷新总开关", "按点击、按键或阅读界面判断何时刷新", "system", "", HOT, true, "default", "启用自动刷新");
        add("refresh.touch", "自动刷新", "响应触摸", "点击或滑动累计到设定次数后刷新", "system", "refresh.master", HOT, false, "default", "响应点击");
        add("refresh.key", "自动刷新", "响应按键", "按下音量键或翻页键后刷新", "system", "refresh.master", HOT, false, "default", "响应按键");
        add("refresh.interval", "自动刷新", "触发次数", "累计多少次操作后刷新一次", "system", "refresh.master", HOT, false, "5,10,15,20,30,@number", "5次,10次,15次,20次,30次,自定义");
        add("refresh.debounce", "自动刷新", "合并间隔", "短时间内连续操作只算一次，避免滑动被重复计数", "system", "refresh.master", HOT, false, "1000,500,2000,0,@number", "1秒,500毫秒,2秒,关闭,自定义");
        add("refresh.delay", "自动刷新", "刷新前延时", "操作停止后再等多久刷新，避免打断连续操作", "system", "refresh.master", HOT, false, "0,300,500,1000", "立即,300毫秒,500毫秒,1000毫秒");
        add("refresh.reading", "自动刷新", "识别阅读界面", "检测到整屏文字时自动刷新，改善翻页残影", "system", "refresh.master", HOT, false, "default", "识别阅读界面");
        // 首值就是出厂默认值：周期性刷新默认 5 分钟。预设仍按数值升序显示（见 showOptionDialog）。
        add("refresh.period", "自动刷新", "周期性刷新", "每隔一段时间刷新一次屏幕", "system", "refresh.master", HOT, false, "300,60,600,1800,@number", "5分钟,1分钟,10分钟,30分钟,自定义");
        add("refresh.apps", "自动刷新", "应用黑白名单", "白名单只统计名单内应用，黑名单跳过名单内应用", "system", "", HOT, false, "@apps", "黑白名单");
    }

    private static void add(String id, String group, String title, String description, String role,
            String dependency, FeatureDefinition.ApplyMode mode, boolean experimental, String values, String labels) {
        add(id, group, title, description, role, dependency, mode, experimental, values, labels, false);
    }

    /** 同上；{@code defaultOn} 为真时配置里没有这个键就按开启处理（「显示设备状态」的四项显示开关）。 */
    private static void add(String id, String group, String title, String description, String role,
            String dependency, FeatureDefinition.ApplyMode mode, boolean experimental, String values, String labels,
            boolean defaultOn) {
        FEATURES.add(new FeatureDefinition(id, group, title, description, role, dependency, mode,
                experimental, values.split(","), labels.split(","), defaultOn));
    }
    public static List<FeatureDefinition> all() { return Collections.unmodifiableList(FEATURES); }
    public static FeatureDefinition get(String id) {
        for (FeatureDefinition definition : FEATURES) if (definition.id.equals(id)) return definition;
        throw new IllegalArgumentException("未知功能：" + id);
    }
    private FeatureCatalog() {}
}
