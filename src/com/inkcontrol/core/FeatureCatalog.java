package com.inkcontrol.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import static com.inkcontrol.core.FeatureDefinition.ApplyMode.*;

public final class FeatureCatalog {
    private static final List<FeatureDefinition> FEATURES = new ArrayList<>();
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
            String values = i == 5 ? "gcsweep" : i >= 6 ? "scan,ink,dots,blind" : "kiris,kwipe,ink,dots,scan,blind";
            String labels = i == 5 ? "全刷扫过" : i >= 6 ? "扫描线,墨迹晕开,网点长大,百叶窗" : "方形展开,关键帧擦除,墨迹晕开,网点长大,扫描线,百叶窗";
            // 总开关与全刷扫过不再标「实验」，其余场景保留
            add("motion." + scenes[i], "动画", names[i], "关闭此场景即恢复系统过渡", "system", "motion.master", HOT, i != 5, values, labels);
        }
        add("motion.dim", "动画", "去掉对话框背景变暗", "独立于动画总开关", "system", "", HOT, false, "default", "移除变暗");
        add("motion.steps", "动画", "关键帧数", "关闭此选项时使用两步", "system", "motion.master", HOT, false, "2,3,4", "两步,三步,四步");
        add("motion.hold", "动画", "关键帧停留", "关闭时随温度自动计算", "system", "motion.master", HOT, false, "0,60,100,200,300,500,1000,@curve", "温度自适应,60ms,100ms,200ms,300ms,500ms,1000ms,自定义曲线");
        add("motion.origin", "动画", "展开起点", "关闭时使用触摸位置", "system", "motion.master", HOT, false, "touch,center", "触摸位置,屏幕中心");
        add("motion.direction", "动画", "擦除方向", "关闭时自动选择方向", "system", "motion.master", HOT, false, "auto,ltr,rtl,ttb,btt", "自动,从左向右,从右向左,从上向下,从下向上");
        add("motion.spread", "动画", "波形动画速度", "以扫描帧为单位，关闭时使用12帧", "system", "motion.master", HOT, true, "12,1,6,18,24", "12帧,1帧,6帧,18帧,24帧");
        add("motion.scale", "动画", "应用内小动画", "关闭时恢复原厂倍率", "system", "", HOT, false, "0,0.5,1", "关闭动画,半速时长,原时长");
        add("lock.style", "锁屏", "自定义锁屏", "关闭时恢复有效的原厂样式", "systemui", "", HOT, false, "5,6,7,9,10,8", "编辑排版,瑞士网格,像素,编辑排版-横,编辑排版-横-逆,液态玻璃");
        add("lock.weather", "锁屏", "锁屏天气", "跟随原厂桌面组件，或直接读取系统预装的天气App", "systemui", "lock.style", HOT, false, "default,moji", "原厂桌面组件,天气App");
        // 锁屏底部那一块位置只有一处，两个总开关二选一：「显示通知」或「显示设备状态」。
        // 互斥由界面统一保证（MainActivity.change 里开一个就自动关另一个），
        // 所以配置里正常只会有一个为开；设备状态条的四项由 lock.ext.* 单独控制显示与否。
        add("lock.notify", "锁屏", "显示通知", "锁屏底部显示通知条，来源可选系统通知或微信未读", "systemui", "lock.style", HOT, false, "system,wechat", "系统通知,微信");
        // 「显示设备状态」：auto 跟随样式（编辑排版/横版用图标，瑞士网格用文字），
        // icon/text 强制一种。默认 auto 保持各样式原有的观感。
        add("lock.ext", "锁屏", "显示设备状态", "锁屏底部改显示无线网络、蓝牙、声音模式与飞行模式；开启后自动关闭「显示通知」", "systemui", "lock.style", HOT, false, "auto,icon,text", "跟随样式,图标版,文字版");
        // 「显示信息修改」是个纯页面（@panel）：自己没有开关，进去才改下面那四格。
        // 四个开关仍是正式功能（各自写配置、可单独进详情页），只是不再铺在分类列表里。
        add("lock.ext.edit", "锁屏", "显示信息修改", "选择设备状态里显示哪几项：无线网络、蓝牙、声音模式、飞行模式", "systemui", "lock.ext", HOT, false, "@panel", "修改");
        // 四项显示开关：默认全开（旧配置里没有这几个键也按显示处理），各自关掉的才记住。
        add("lock.ext.wifi", "锁屏", "显示无线网络", "锁屏设备状态里显示无线网络开关情况", "systemui", "lock.ext", HOT, false, "default", "显示无线", true);
        add("lock.ext.bt", "锁屏", "显示蓝牙", "锁屏设备状态里显示蓝牙开关情况", "systemui", "lock.ext", HOT, false, "default", "显示蓝牙", true);
        add("lock.ext.audio", "锁屏", "显示声音模式", "锁屏设备状态里显示静音 / 有声；只读，不改铃声音量", "systemui", "lock.ext", HOT, false, "default", "显示声音", true);
        add("lock.ext.airplane", "锁屏", "显示飞行模式", "锁屏设备状态里显示飞行模式开关情况", "systemui", "lock.ext", HOT, false, "default", "显示飞行", true);
        add("lock.countdown", "锁屏", "锁屏倒数日", "锁屏右侧显示事项倒数，最多三个，事项名可留空", "systemui", "lock.style", HOT, false, "default", "显示倒数日");
        add("lock.countdowns", "锁屏", "倒数日事项", "每组一个事项和日期；日期为空则该组停用", "systemui", "lock.countdown", HOT, false, "@countdowns", "编辑事项");
        add("lock.lyric", "锁屏", "锁屏歌词", "支持现有媒体歌词与 Lyric Getter 广播", "systemui", "", HOT, false, "default", "显示歌词");
        add("lock.statusbar", "锁屏", "状态栏歌词", "停止或关闭后恢复通知图标", "systemui", "", HOT, false, "default", "显示歌词");
        add("lock.first", "锁屏", "锁屏瞬间的刷新方式", "阅读模式曾改善空白，残影需观察", "systemui", "", HOT, true, "3,2", "阅读模式3,清晰全刷2");
        add("lock.update", "锁屏", "走时与通知的刷新方式", "关闭时沿用系统模式", "systemui", "", HOT, true, "3,2", "阅读模式3,清晰全刷2");
        add("lock.lyricmode", "锁屏", "歌词的刷新方式", "关闭时沿用系统模式", "systemui", "lock.lyric", HOT, true, "3,2,9", "阅读模式3,清晰全刷2,极速模式9");
        add("lock.delay", "锁屏", "锁屏画面出现时机", "最低300ms，避免过早送图", "systemui", "", HOT, false, "300,400,500", "300ms,400ms,500ms");
        add("lock.dither", "锁屏", "消除锁屏细线", "使用点量化，字体灰阶需实屏观察", "backend", "display.gpu", HOT, true, "nearest", "点量化");
        // 「体感息屏唤醒」总开关已下线：遮挡息屏 / 移开唤醒各自就是开关，
        // 两个都关时体感服务不监听传感器。无障碍里的「墨控体感」服务仍需启用。
        add("wave.lock", "体感", "遮挡息屏", "近距离传感器被持续遮挡一段时间后锁定并关闭屏幕；需在系统无障碍中启用「墨控体感」服务", "system", "", HOT, false, "default", "遮挡息屏");
        add("wave.wake", "体感", "移开唤醒", "遮挡结束（移出口袋/移开手掌）后点亮屏幕并尝试退出锁屏；需在系统无障碍中启用「墨控体感」服务", "system", "", HOT, false, "default", "移开唤醒");
        add("wave.coverDelay", "体感", "遮挡延时", "遮挡持续多久才息屏，避免误触", "system", "wave.lock", HOT, false, "1000,500,2000,3000", "1秒,500毫秒,2秒,3秒");
        add("desktop.theme", "桌面", "桌面主题", "保留原厂桌面位置与布局", "launcher", "", HOT, false, "glass,paper,ink", "流光玻璃,纸间,墨构");
        add("desktop.icons", "桌面", "图标换肤", "独立恢复原厂图标", "launcher", "desktop.theme", HOT, false, "default", "主题图标");
        add("desktop.font", "桌面", "字体换肤", "独立恢复原厂字体", "launcher", "desktop.theme", HOT, false, "default", "主题字体");
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
        add("experiment.cache", "实验室", "GPU 参数缓存", "提交阶段局部优化，不承诺整机提速", "backend", "display.gpu", HOT, true, "default", "缓存uniform位置");
        add("experiment.video", "实验室", "视频抖动算法", "关闭时使用 GPU 蓝噪声；可改用原厂误差扩散对比", "backend", "display.gpu", HOT, true, "blue,stock", "蓝噪声与迟滞,原厂误差扩散");
        add("refresh.master", "自动刷新", "自动刷新总开关", "按点击、按键或阅读界面判断何时刷新", "system", "", HOT, true, "default", "启用自动刷新");
        add("refresh.touch", "自动刷新", "响应触摸", "点击或滑动累计到设定次数后刷新", "system", "refresh.master", HOT, false, "default", "响应点击");
        add("refresh.key", "自动刷新", "响应按键", "按下音量键或翻页键后刷新", "system", "refresh.master", HOT, false, "default", "响应按键");
        add("refresh.interval", "自动刷新", "触发次数", "累计多少次操作后刷新一次", "system", "refresh.master", HOT, false, "5,10,15,20,30,@number", "5次,10次,15次,20次,30次,自定义");
        add("refresh.debounce", "自动刷新", "合并间隔", "短时间内连续操作只算一次，避免滑动被重复计数", "system", "refresh.master", HOT, false, "1000,500,2000,0,@number", "1秒,500毫秒,2秒,关闭,自定义");
        add("refresh.delay", "自动刷新", "刷新前延时", "操作停止后再等多久刷新，避免打断连续操作", "system", "refresh.master", HOT, false, "0,300,500,1000", "立即,300毫秒,500毫秒,1000毫秒");
        add("refresh.reading", "自动刷新", "识别阅读界面", "检测到整屏文字时自动刷新，改善翻页残影", "system", "refresh.master", HOT, false, "default", "识别阅读界面");
        add("refresh.period", "自动刷新", "周期性刷新", "每隔一段时间刷新一次屏幕", "system", "refresh.master", HOT, false, "60,300,600,1800,@number", "1分钟,5分钟,10分钟,30分钟,自定义");
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
