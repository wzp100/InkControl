// 翻页动画「分块揭示」→ GPU 波形条带相位：融合路线原型（默认完全不接管）。
//
// 目标：让两个引擎不再争 /vendor/lib64/hw/hwcomposer.ums512.so 与 0x92fe4。
// 做法：把「一次整屏拆成 4 块依次揭示」重写为「一次整屏波形的 4 个条带相位」，
// 相位只由墨控自己的 ctl 语义（service_transitions.hpp 的 Control / Segment）推导：
//     localFrame = total - remaining，band = localFrame * bands / (total + 1)。
// 这条路径不重复调用 base+0x95dc4、不需要 ctl 矩形字段偏移、不碰 HWC 二进制，
// 因此不会打乱原厂状态机 / ring 计数，也不与第三方翻页动画 HWC 抢同一份挂载文件。
//
// 真正生效的落点不是「遮蔽索引」，而是墨控 GPU 波形层本来就有的
// WaveRequest::bandDirection / bandFrames：着色器按 row/48 把 720 行分成 15 个带，
// 每带延迟若干帧才驱动波形（bandDirection=1 上→下 / 2 下→上）。
// drive() 把 pageanim.* 语义翻译成这两个参数，由 hookUpdate 在构造波形请求时写入；
// validWave() 会校验约束，异常时原有 ROLL_BEGIN_FALLBACK 自动回原厂路径，不会黑屏。
// 这样翻页动画不需要第二份 HWC、不需要 resident、不需要重启，也不重复调用传输函数。
//
// 属性（全部默认关；上机实测 composer HAL 只读得到 debug.* 前缀）：
//   debug.inkcontrol.fusion          1 才进入融合分支
//   debug.inkcontrol.fusion.drive    1 才真正驱动分带揭示（默认只观测）
//   debug.inkcontrol.fusion.armed    1 才命中（总闸门，由后端按 pageanim.master 下发）
//   debug.inkcontrol.fusion.reader   1 才命中（前台应用在 pageanim.readers 名单里；
//                                    由 system_server 侧随前台切换写，空名单恒为 0）
//   debug.inkcontrol.fusion.observe  1 时每 64 帧采样打印 ctl 字段
//   debug.inkcontrol.fusion.plan     1 时逐帧打印条带相位
//   debug.inkcontrol.fusion.bands    4..32，默认 4
//   debug.inkcontrol.fusion.reverse  1 = 从下往上揭示
//   debug.inkcontrol.fusion.spacing  展开速度，映射成额外的错峰帧数（bandTail）
#ifndef INKCONTROL_SERVICE_FUSION_HPP
#define INKCONTROL_SERVICE_FUSION_HPP

#include <android/log.h>
#include <cstdarg>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <sys/system_properties.h>

namespace einkfusion {

// 屏幕是 720×1440；按 y 切条带，每条 720 × (1440/bands)。
static constexpr int PANEL_W = 720, PANEL_H = 1440, DEFAULT_BANDS = 4;
// 只用于日志中说明「原厂传输入口仍然可达且本路线不需要它」。
static constexpr uintptr_t STOCK_SUBMIT = 0x95dc4;

inline bool flag(const char* key) {
    char value[PROP_VALUE_MAX] = {};
    __system_property_get(key, value);
    return value[0] == '1';
}
inline void log(const char* fmt, ...) {
    va_list args;
    va_start(args, fmt);
    __android_log_vprint(ANDROID_LOG_INFO, "EinkFusion", fmt, args);
    va_end(args);
}
template<class T> inline T field(void* p, int off) {
    T v{};
    std::memcpy(&v, static_cast<const unsigned char*>(p) + off, sizeof(T));
    return v;
}

struct Frame {
    int width, height, mode, total, remaining, localFrame;
    int region, blank, reset, transition;
    bool full, waveFamily, nonLocal, armed, reader;
};

// 字段偏移全部来自 service_transitions.hpp::snapshot，不再引用第三方 HWC 的私有 BSS。
inline Frame inspect(void* ctl) {
    Frame f{};
    f.width = field<int>(ctl, 0xbc);          // 1440
    f.height = field<int>(ctl, 0xc0);         // 720
    f.mode = field<uint8_t>(ctl, 0x15c);
    f.total = field<int>(ctl, 0x160);
    f.remaining = field<int>(ctl, 0xc8);
    f.localFrame = f.total - f.remaining;
    f.region = field<int>(ctl, 0x2c8);
    f.blank = field<int>(ctl, 0x280);
    f.reset = field<int>(ctl, 0x270);
    f.transition = field<int>(ctl, 0x278);
    f.full = f.width == PANEL_H && f.height == PANEL_W;
    // segment() 里只有这几种 mode 会生成 GPU 波形（Normal 分支）。
    f.waveFamily = f.mode == 1 || f.mode == 2 || f.mode == 5 || f.mode == 6 || f.mode == 8;
    f.nonLocal = f.region == 0 && f.transition == 0;
    f.armed = flag("debug.inkcontrol.fusion.armed");
    // 原版只看「目标阅读器」名单内的前台应用；融合没有常驻端，改由 system_server 侧
    // 比对名单后写这条属性。名单为空时原版就是不触发，所以这里也按不触发处理。
    f.reader = flag("debug.inkcontrol.fusion.reader");
    return f;
}

inline int bands() {
    char value[PROP_VALUE_MAX] = {};
    __system_property_get("debug.inkcontrol.fusion.bands", value);
    int n = std::atoi(value);
    return n >= 4 && n <= 32 ? n : DEFAULT_BANDS;
}

struct Band { int index, y, h, firstFrame, lastFrame; };
inline Band band(int i, int n, int total) {
    int h = PANEL_H / n;
    int first = int(int64_t(total + 1) * i / n);
    int last = int(int64_t(total + 1) * (i + 1) / n) - 1;
    return {i, i * h, h, first, last};
}
inline int bandForFrame(int localFrame, int total, int n) {
    if (total <= 0) return 0;
    int i = int(int64_t(localFrame) * n / (total + 1));
    return i < 0 ? 0 : (i >= n ? n - 1 : i);
}

/** 只观测：恒返回 false，调用方继续跑 GPU 波形流水线。 */
inline bool probe(void* entry, void* ctl, uintptr_t base) {
    if (!flag("debug.inkcontrol.fusion")) return false;
    Frame f = inspect(ctl);
    static unsigned seen = 0;
    if (flag("debug.inkcontrol.fusion.observe") && (seen++ % 64) == 0) {
        log("FUSION_OBS w=%d h=%d mode=%d total=%d frame=%d region=%d blank=%d reset=%d trans=%d "
            "full=%d wave=%d nonlocal=%d armed=%d reader=%d",
            f.width, f.height, f.mode, f.total, f.localFrame, f.region, f.blank, f.reset,
            f.transition, f.full ? 1 : 0, f.waveFamily ? 1 : 0, f.nonLocal ? 1 : 0,
            f.armed ? 1 : 0, f.reader ? 1 : 0);
    }
    const int n = bands();
    if (!f.armed || !f.reader || !f.full || !f.waveFamily || !f.nonLocal || f.total < n) return false;
    if (f.localFrame == 0) {
        log("FUSION_HIT mode=%d total=%d bands=%d stock=%p (unused)",
            f.mode, f.total, n, reinterpret_cast<void*>(base + STOCK_SUBMIT));
        for (int i = 0; i < n; i++) {
            Band b = band(i, n, f.total);
            log("FUSION_BAND i=%d y=%d h=%d frames=%d..%d",
                b.index, b.y, b.h, b.firstFrame, b.lastFrame);
        }
    }
    if (flag("debug.inkcontrol.fusion.plan")) {
        int k = bandForFrame(f.localFrame, f.total, n);
        log("FUSION_PHASE frame=%d/%d band=%d entry=%p ctl=%p gpu=continue",
            f.localFrame, f.total, k, entry, ctl);
    }
    return false;
}

struct Drive {
    bool enabled = false;
    uint32_t direction = 1;   // 1 = 上→下，2 = 下→上
    uint32_t frames = 2;      // 兼容 validWave 的 bandFrames < activeFrames 约束
    uint32_t rows = 48;       // 每带行数：720 / pageanim.bands
    uint32_t tail = 0;        // 为错峰额外延长的总帧数（总帧数 = active + tail + 1）
    uint32_t style = 0;       // pageanim.style：0 横 / 1 纵 / 2 斜 / 3 反斜 / 4 环绕 / 5 中心
};

// 把翻页动画配置翻译成 GPU 波形请求的分带参数。只在整屏、GPU 波形族、
// 非区域叠加、armed 命中且帧数足够时返回 enabled。
inline Drive drive(int activeFrames, void* ctl) {
    Drive d{};
    if (!flag("debug.inkcontrol.fusion") || !flag("debug.inkcontrol.fusion.drive")) return d;
    Frame f = inspect(ctl);
    // 分带由着色器按 row/48 固定划 15 带，这里只要求波形帧数够分成几段，
    // 不按 pageanim.bands 过滤，否则 total=9 一类的短波形会被误排除。
    if (!f.armed || !f.reader || !f.full || !f.waveFamily || !f.nonLocal || f.total < 4) return d;
    if (activeFrames <= 1) return d;
    char reverse[PROP_VALUE_MAX] = {};
    __system_property_get("debug.inkcontrol.fusion.reverse", reverse);
    d.direction = reverse[0] == '1' ? 2u : 1u;
    // 每条带一旦启动必须一直驱动到波形结束：着色器在 !drive 时直接输出 templ
    // （控制模板里的旧像素），bandFrames 太短会让末帧残留大量未驱动区域。
    // 刷新键的面板级 clear ghosting 不产生新合成帧，会把这个中间态重刷定格成黑屏。
    // validWave 要求 bandFrames < activeFrames，取 activeFrames-1 是唯一安全值；
    // 想调节展开速度需把着色器里固定的 row/48 改成 uniform，不能用 bandFrames 代替。
    d.frames = uint32_t(activeFrames - 1);
    // pageanim.bands：4..32 块 → 每带 180..22 行。着色器按 row/bandRows 划带，
    // 所以这里传的是「行数」而不是「块数」。
    char bands[PROP_VALUE_MAX] = {};
    __system_property_get("debug.inkcontrol.fusion.bands", bands);
    int n = std::atoi(bands);
    if (n < 4 || n > 32) n = 12;
    d.rows = uint32_t(720 / n);
    if (d.rows < 4) d.rows = 4;
    // pageanim.spacing：6..40，越小展开越快。映射成波形前 8%..50% 用于错峰，
    // 上限锁在 50%，保证错峰结束后仍有足够帧数让所有带走完 LUT 并收敛。
    char spacing[PROP_VALUE_MAX] = {};
    __system_property_get("debug.inkcontrol.fusion.spacing", spacing);
    int s = std::atoi(spacing);
    if (s < 6 || s > 40) s = 12;
    // spacing 越大 → 错峰越长、动画越慢。基础长度取「分带数 × 3/4」，
    // 保证相邻带之间大约错开 1 帧；再按 spacing/12 缩放，夹在 4..16 帧
    // （16 与 motion 延长同一量级，属已验证范围）。
    d.tail = uint32_t(n * 3 / 4);
    if (d.tail < 4) d.tail = 4;
    d.tail = d.tail * uint32_t(s) / 12u;
    if (d.tail < 4) d.tail = 4;
    if (d.tail > 16) d.tail = 16;
    uint32_t room = uint32_t(activeFrames) + 1u < 159u ? 158u - uint32_t(activeFrames) : 0u;
    if (d.tail > room) d.tail = room;
    char style[PROP_VALUE_MAX] = {};
    __system_property_get("debug.inkcontrol.fusion.style", style);
    int t = std::atoi(style);
    d.style = (t >= 0 && t <= 5) ? uint32_t(t) : 0u;
    d.enabled = true;
    return d;
}

// ── 延长原厂 A2 循环，给分带错峰腾出帧数 ──────────────────────────────
// 只错峰不延长，后启动的带会被截掉 LUT 尾部（收敛帧）：像素停在中间灰阶，
// 旧页就留下浅灰残影，也就是「刷新不干净」。这里在 frame 0 的原厂调用之后
// 给 ctl 的 remaining/total 同时加 tail，帧号 = total - remaining 不变，
// 但循环会多跑 tail 帧，让每条带都能从 LUT 第 0 帧走满 activeFrames 帧。
struct TailState {
    void* ctl = nullptr;
    int savedTotal = 0;
    bool active = false;
};

inline TailState& tailState() {
    static TailState state;
    return state;
}

inline void extendTail(void* ctl, uint32_t tail) {
    if (!tail || !ctl) return;
    auto* c = static_cast<unsigned char*>(ctl);
    int remaining = 0, total = 0;
    std::memcpy(&remaining, c + 0xc8, 4);
    std::memcpy(&total, c + 0x160, 4);
    TailState& s = tailState();
    s.ctl = ctl;
    s.savedTotal = total;
    s.active = true;
    remaining += int(tail);
    total += int(tail);
    std::memcpy(c + 0xc8, &remaining, 4);
    std::memcpy(c + 0x160, &total, 4);
}

inline void restoreTail() {
    TailState& s = tailState();
    if (!s.active) return;
    std::memcpy(static_cast<unsigned char*>(s.ctl) + 0x160, &s.savedTotal, 4);
    s.active = false;
}

inline bool tailActive() { return tailState().active; }

}  // namespace einkfusion
#endif  // INKCONTROL_SERVICE_FUSION_HPP
