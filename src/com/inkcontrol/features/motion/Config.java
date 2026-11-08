package com.inkcontrol.features.motion;

import com.inkcontrol.core.Configuration;
import com.inkcontrol.core.FeatureService;

/** 从统一快照生成的动画运行参数，不单独存储配置。 */
public final class Config {
    // 场景
    public static final int APP_OPEN = 0, APP_CLOSE = 1, FORWARD = 2, BACK = 3, GC_CLEAR = 4, DIALOG = 5, LOCK = 6, UNLOCK = 7;
    public static final String[] SCENES = {"app_open", "app_close", "in_app_forward", "in_app_back", "gc_clear", "dialog", "lock", "unlock"};

    // 动画（与预览页 STYLES 的 id 相同）
    public static final String INSTANT = "instant", GC16 = "gc16";
    public static final String KWIPE = "kwipe", KIRIS = "kiris";
    public static final String INK = "ink", DOTS = "dots", SCAN = "scan", BLIND = "blind", GCSWEEP = "gcsweep";

    /** 普通场景可选的动画；全刷场景只有 gc16 / gcsweep */
    public static final String[] TRANSITION_STYLES = {INSTANT, KIRIS, KWIPE, INK, DOTS, SCAN, BLIND};
    public static final String[] GC_STYLES = {GC16, GCSWEEP};
    public static final String[] LOCK_STYLES = {INSTANT, INK, DOTS, SCAN, BLIND};

    public static final String[] DEFAULT_STYLES = {KIRIS, INSTANT, INSTANT, INSTANT, GC16, INSTANT, INSTANT, INSTANT};

    public boolean enabled = false;
    public final String[] styles = DEFAULT_STYLES.clone();
    /** 系统层关键帧数：2 = 一个中间帧（快），3 = 两个中间帧（与预览页相同） */
    public int steps = 2;
    /** 每个关键帧停留多久；0 = 按温度自动（A2 帧数 + 3 帧） */
    public int holdMs = 0;
    /** 按温区自定义的停留时长（毫秒）；非空时优先于 holdMs。 */
    public int[] holdCurve;
    /** 方形展开起点：touch = 刚才点击的位置，center = 屏幕中心 */
    public String origin = "touch";
    /** 擦除方向：auto = 前进/打开从右侧开始，后退/关闭从左侧开始 */
    public String wipeDir = "auto";
    /** 全刷扫过方向：auto = 沿用原厂习惯「从左向右」。与「擦除方向」分开，避免互相牵制。 */
    public String gcDir = "auto";
    /** 去掉对话框背后的变暗层（和动画总开关无关） */
    public boolean noDim = false;
    /**
     * 应用内动画倍率（波纹、页面切换、列表动画等属性动画）。海信在墨水屏上固定为 1.0。
     * -1 = 不改；0 = 关闭（直接跳到结束状态）；0.5 = 减半。和总开关无关。
     */
    public float appAnimScale = -1f;
    /** 波形层：起跑时间错开幅度，单位是扫描帧（11.65ms） */
    public int spread = 12;
    /** 每次保存时更新，用来确认 system_server 已经读到 */
    public long rev;
    /** 在 logcat（标签 EinkMotion）里记录每次过渡 */
    public boolean debug = false;

    public static boolean isSystemStyle(String s) {
        return KWIPE.equals(s) || KIRIS.equals(s);
    }

    public static boolean isPluginStyle(String s) {
        return INK.equals(s) || DOTS.equals(s) || SCAN.equals(s) || BLIND.equals(s) || GCSWEEP.equals(s);
    }

    static boolean allowed(int scene, String s) {
        String[] list = stylesFor(scene);
        for (String x : list) if (x.equals(s)) return true;
        return false;
    }

    public static String[] stylesFor(int scene) {
        return scene == GC_CLEAR ? GC_STYLES : scene == LOCK || scene == UNLOCK ? LOCK_STYLES : TRANSITION_STYLES;
    }

    /** 总开关关闭时一律按原版处理 */
    public String styleFor(int scene) {
        return enabled ? styles[scene] : nativeStyle(scene);
    }

    public static String nativeStyle(int scene) {
        return scene == GC_CLEAR ? GC16 : INSTANT;
    }

    public static Config from(Configuration config) {
        Config result = new Config();
        result.enabled = config.enabled("motion.master");
        for (int i = 0; i < SCENES.length; i++) {
            String id = "motion." + SCENES[i];
            result.styles[i] = config.enabled(id) && (!FeatureService.needsWaveBackend(id, config.value(id)) || config.enabled("display.gpu")) ? config.value(id) : nativeStyle(i);
        }
        result.noDim = config.enabled("motion.dim");
        result.steps = config.enabled("motion.steps") ? Integer.parseInt(config.value("motion.steps")) : 2;
        String hold = config.enabled("motion.hold") ? config.value("motion.hold") : "0";
        result.holdCurve = com.inkcontrol.core.FeatureDefinition.curve(hold);
        result.holdMs = result.holdCurve == null ? Integer.parseInt(hold) : 0;
        result.origin = config.enabled("motion.origin") ? config.value("motion.origin") : "touch";
        result.wipeDir = config.enabled("motion.direction") ? config.value("motion.direction") : "auto";
        result.gcDir = config.enabled("motion.gc_direction") ? config.value("motion.gc_direction") : "auto";
        result.spread = config.enabled("motion.spread") ? Integer.parseInt(config.value("motion.spread")) : 12;
        result.appAnimScale = config.enabled("motion.scale") ? Float.parseFloat(config.value("motion.scale")) : -1;
        result.debug = false;
        result.rev = config.revision;
        return result;
    }

    public String summary() {
        StringBuilder b = new StringBuilder(enabled ? "on" : "off");
        for (int i = 0; i < SCENES.length; i++) b.append(' ').append(SCENES[i]).append('=').append(styles[i]);
        b.append(" steps=").append(steps).append(" hold=").append(holdCurve != null ? "curve" + java.util.Arrays.toString(holdCurve) : holdMs == 0 ? "auto" : holdMs + "ms");
        b.append(" no_dim=").append(noDim).append(" app_anim=").append(appAnimScale < 0 ? "keep" : String.valueOf(appAnimScale));
        return b.toString();
    }
}
