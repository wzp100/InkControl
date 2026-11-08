// 翻页动画「4 块」历史原型（预研用，默认完全不接管传输）。
//
// 现在的正式实现是 native/service_fusion.hpp（波形层分带相位），本文件只留作
// 探索记录，没有被 hookUpdate 的正式路径使用。整体说明见
// docs/翻页动画融合实现.md：墨控 native 侧已经拿到与翻页动画引擎
// 相同的控制结构（hookUpdate 的 ctl）和原厂提交入口（base+0x95dc4），
// 所以门控与 4 块几何都能在源码里闭环；唯一没确认的是 ctl 里矩形字段的偏移，
// 在未上机验证前，本文件默认只记录命中与几何，不改写控制结构、不重复提交。
//
// 属性（全部缺省为关，任何一项没写就走原厂路径）：
//   debug.inkcontrol.pageanim.proto    总开关，1 才进入预研分支
//   debug.inkcontrol.pageanim.observe  1 时每 64 帧采样打印 ctl 字段，仅诊断
//   sys.inkcontrol.pageanim.armed      模拟 resident 的白名单命中 / 已武装
//   sys.inkcontrol.pageanim.submit     1 才打印 4 块提交计划（实验用，默认 0）
//   sys.inkcontrol.pageanim.style      0 横向切四条 / 1 纵向切四条
//   sys.inkcontrol.pageanim.force_gate 1 时跳过 EPD/clean 门控，仅要求 armed + 整屏
//
// 所有 `sys.inkcontrol.pageanim.X` 都可用同名 `debug.inkcontrol.pageanim.X` 代替
// （上机发现 HAL 进程读不到 sys.*，见 flag() 注释）；一般建议直接用 debug.* 前缀。
//
// 2026-11-08 上机补充：EPD_MODE 与 clean 条件是从第三方翻页动画 HWC 逆向来的，
// 在墨控 patched GPU HWC 上语义完全不同（base+0x9c2358 读到的是别的全局，
// ctl+0x270/0x280 在 GPU 波形路径上与翻页动画引擎的约定相反）。
// 因此默认门控只作为「照抄能不能用」的对照；正式实现必须从 ctl 的
// mode/phase（见 service_transitions.hpp::Control）重新定义，不依赖私有 BSS。
#ifndef INKCONTROL_SERVICE_PAGEANIM_HPP
#define INKCONTROL_SERVICE_PAGEANIM_HPP

#include <android/log.h>
#include <cstdarg>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <sys/system_properties.h>

namespace einkpageanim {

// hookUpdate 的 else 分支调用的就是这个地址：原厂 Update_Display_Image 提交入口。
// 也就是说 native 侧不需要再改 HWC 二进制就能拿到可调用的传输函数。
static constexpr uintptr_t STOCK_SUBMIT = 0x95dc4;
// HWC BSS 里的「当前 EPD 更新方式」，翻页动画引擎的波形白名单读同一处。
static constexpr uintptr_t EPD_MODE = 0x9c2358;
static constexpr int BANDS = 4, PANEL_W = 720, PANEL_H = 1440;
// 控制结构里矩形字段的偏移尚未逆向确认。0 = 不写矩形（安全模式）；
// 上机确认后改成实际偏移即可让 4 块提交带上各自的条带。
#ifndef PAGEANIM_RECT_OFFSET
#define PAGEANIM_RECT_OFFSET 0
#endif

template<class T> inline T paLoad(uintptr_t p) {
    T v;
    std::memcpy(&v, reinterpret_cast<const void*>(p), sizeof(v));
    return v;
}
template<class T> inline T paField(void* p, int offset) {
    return paLoad<T>(reinterpret_cast<uintptr_t>(p) + offset);
}
inline bool flag(const char* key) {
    char value[PROP_VALUE_MAX] = {};
    __system_property_get(key, value);
    if (value[0] == '1') return true;
    // 2026-11-08 上机实测：composer HAL 进程读得到 debug.inkcontrol.pageanim.*，
    // 却读不到同名 sys.* 属性（getprop 能看到，__system_property_get 拿到空值）。
    // 因此 sys.inkcontrol.pageanim.X 一律再试一次 debug.inkcontrol.pageanim.X。
    static const char sysPrefix[] = "sys.";
    if (std::strncmp(key, sysPrefix, sizeof(sysPrefix) - 1) == 0) {
        char alt[96] = {};
        std::snprintf(alt, sizeof(alt), "debug.%s", key + sizeof(sysPrefix) - 1);
        char altValue[PROP_VALUE_MAX] = {};
        __system_property_get(alt, altValue);
        return altValue[0] == '1';
    }
    return false;
}
inline void log(const char* fmt, ...) {
    va_list args;
    va_start(args, fmt);
    __android_log_vprint(ANDROID_LOG_INFO, "EinkPageAnim", fmt, args);
    va_end(args);
}

struct Frame {
    int width, height, mode, frames, epd;
    bool full, gc16, clean, armed;
};

// 门控条件照抄翻页动画引擎 0x6800d94：整屏 720x1440、GC16 一族波形、
// 没有局部矩形/滚动偏移，再叠加 resident 的「已武装」标记。
inline Frame inspect(void* ctl, uintptr_t base) {
    Frame f{};
    f.width = paField<int>(ctl, 0xc0);
    f.height = paField<int>(ctl, 0xbc);
    f.mode = paField<uint8_t>(ctl, 0x15c);
    f.frames = paField<int>(ctl, 0x160);
    f.epd = paLoad<int>(base + EPD_MODE);
    f.full = f.width == PANEL_W && f.height == PANEL_H;
    f.gc16 = f.epd == 0x201 || f.epd == 0x203 || f.epd == 0x603;
    f.clean = paField<int>(ctl, 0x270) == 1 && paField<int>(ctl, 0x278) == 0
              && paField<int>(ctl, 0x280) == 0;
    f.armed = flag("sys.inkcontrol.pageanim.armed");
    return f;
}

struct Block { int x, y, w, h; };
inline Block block(int index, int style) {
    if (style == 1) {
        int w = PANEL_W / BANDS;
        return {index * w, 0, w, PANEL_H};
    }
    int h = PANEL_H / BANDS;
    return {0, index * h, PANEL_W, h};
}

inline void writeRect(unsigned char* ctl, const Block& b) {
#if PAGEANIM_RECT_OFFSET > 0
    int* rect = reinterpret_cast<int*>(ctl + PAGEANIM_RECT_OFFSET);
    rect[0] = b.x; rect[1] = b.y; rect[2] = b.w; rect[3] = b.h;
#else
    (void)ctl; (void)b;
#endif
}

// 真正接管的骨架：原厂提交入口在 native 侧可直达（无需改 HWC 二进制），
// 但「把整屏拆成 4 块」需要改写 ctl 的矩形字段，而该偏移尚未上机确认；
// 原厂状态机 / ring 计数也按「一次整屏」设计，重复提交会让它们错乱。
// 因此本原型在 submit=1 时只打印将要执行的 4 次调用，不真的调用 stock。
inline bool submitBlocks(void* entry, void* ctl, uintptr_t base, int style) {
    using Submit = int(*)(void*, void*);
    Submit stock = reinterpret_cast<Submit>(base + STOCK_SUBMIT);
    alignas(16) unsigned char scratch[64] = {};
    for (int i = 0; i < BANDS; i++) {
        Block b = block(i, style);
        writeRect(scratch, b);   // 演示矩形写入；RECT_OFFSET=0 时是空操作
        log("PAGEANIM_SUBMIT_PLAN i=%d x=%d y=%d w=%d h=%d entry=%p ctl=%p stock=%p",
            i, b.x, b.y, b.w, b.h, entry, ctl, reinterpret_cast<void*>(stock));
    }
    return false;   // 未接管：调用方照常走 GPU 波形流水线
}

/** 返回 true 表示这一帧已被接管，调用方应直接返回，不要再跑 GPU 波形流水线。 */
inline bool probe(void* entry, void* ctl, uintptr_t base) {
    if (!flag("debug.inkcontrol.pageanim.proto")) return false;
    Frame f = inspect(ctl, base);
    // 每 64 帧采样一次，用来确认不同 HWC 上 ctl 字段的真实取值分布。
    // 只在 observe 打开时输出，默认完全静默，不会污染正常日志。
    static unsigned observed = 0;
    if (flag("debug.inkcontrol.pageanim.observe") && (observed++ % 64) == 0) {
        log("PAGEANIM_OBS w=%d h=%d mode=%d frames=%d epd=0x%x full=%d gc16=%d clean=%d armed=%d",
            f.width, f.height, f.mode, f.frames, f.epd,
            f.full ? 1 : 0, f.gc16 ? 1 : 0, f.clean ? 1 : 0, f.armed ? 1 : 0);
    }
    const bool forceGate = flag("sys.inkcontrol.pageanim.force_gate");
    if (!f.armed || !f.full) return false;
    if (!forceGate && (!f.gc16 || !f.clean)) return false;
    char styleValue[PROP_VALUE_MAX] = {};
    __system_property_get("sys.inkcontrol.pageanim.style", styleValue);
    int style = styleValue[0] == '1' ? 1 : 0;
    log("PAGEANIM_HIT w=%d h=%d mode=%d epd=0x%x frames=%d style=%d bands=%d gate=%s",
        f.width, f.height, f.mode, f.epd, f.frames, style, BANDS,
        forceGate ? "force" : "stock");
    for (int i = 0; i < BANDS; i++) {
        Block b = block(i, style);
        log("PAGEANIM_BLOCK i=%d x=%d y=%d w=%d h=%d", i, b.x, b.y, b.w, b.h);
    }
    if (!flag("sys.inkcontrol.pageanim.submit")) return false;   // 默认：只观测
    return submitBlocks(entry, ctl, base, style);
}

}  // namespace einkpageanim
#endif  // INKCONTROL_SERVICE_PAGEANIM_HPP
