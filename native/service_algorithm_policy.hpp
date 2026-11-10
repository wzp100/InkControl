#pragma once
// 纯选择策略：不读取属性、不改变控制器、波形或计数。生产与独立测试共用。
#include <cstring>

namespace einkalg {
enum class Group { None, Clear, Reading, Fast, Extreme, Video };
enum class Algorithm { Default, Stock, Nearest, Bayer16, Bayer, Blue, Threshold };
enum class Hysteresis { Default, On, Off };
struct Context {
    unsigned mode=0,selected=0,internal=0;
    bool reset=false,transition=false,blank=false;
};
inline bool equal(const char*value,const char*expected){return value&&std::strcmp(value,expected)==0;}
inline Group group(const Context&c){
    if(c.reset||c.transition||c.blank)return Group::None;
    const unsigned external=c.mode&255u,selected=c.selected&255u;
    // 视频独立于外部低字节；自动选 A2 和原厂 VIDEO 入口同样优先。
    if((c.mode&0x400u)&&selected==8&&c.internal==8)return Group::Video;
    // 仅接管明确指定的四种外部模式；自动、未定义及其他入口沿旧行为。
    if(external!=2&&external!=3&&external!=4&&external!=5&&external!=6&&external!=9)return Group::None;
    if(external==2&&selected==2&&c.internal==2)return Group::Clear;
    if((external==3||external==4||external==5)&&selected==5&&c.internal==5)return Group::Reading;
    if(selected==8&&c.internal==8){
        if(external==6)return Group::Fast;
        if(external==9)return Group::Extreme;
    }
    return Group::None;
}
inline const char*algorithmProperty(Group g){
    switch(g){
    case Group::Clear:return "debug.eink.alg.clear";
    case Group::Reading:return "debug.eink.alg.reading";
    case Group::Fast:return "debug.eink.alg.fast";
    case Group::Extreme:return "debug.eink.alg.extreme";
    case Group::Video:return "debug.eink.alg.video";
    default:return nullptr;
    }
}
inline const char*hysteresisProperty(Group g){
    switch(g){
    case Group::Fast:return "debug.eink.hyst.fast";
    case Group::Extreme:return "debug.eink.hyst.extreme";
    case Group::Video:return "debug.eink.hyst.video";
    default:return nullptr;
    }
}
inline Algorithm parseAlgorithm(Group g,const char*v){
    if(g==Group::Clear||g==Group::Reading){
        if(equal(v,"stock"))return Algorithm::Stock;
        if(equal(v,"nearest"))return Algorithm::Nearest;
        if(equal(v,"bayer16"))return Algorithm::Bayer16;
    }else if(g==Group::Fast||g==Group::Extreme||g==Group::Video){
        if(equal(v,"bayer"))return Algorithm::Bayer;
        if(equal(v,"blue"))return Algorithm::Blue;
        if(equal(v,"threshold"))return Algorithm::Threshold;
        // 快速/极速也支持「原厂」：该模式像素量化交回原厂 CPU 路径（GPU 二值 shader 无原厂实现）。
        if(equal(v,"stock"))return Algorithm::Stock;
    }
    return Algorithm::Default;
}
inline Hysteresis parseHysteresis(const char*v){
    return equal(v,"on")?Hysteresis::On:equal(v,"off")?Hysteresis::Off:Hysteresis::Default;
}
// 与旧 videoDither 完全相同：缺失或非法值回到 blue。
inline Algorithm legacyVideo(const char*v){
    return equal(v,"stock")?Algorithm::Stock:equal(v,"bayer")?Algorithm::Bayer:Algorithm::Blue;
}
struct Binary {
    Algorithm algorithm=Algorithm::Bayer;
    bool videoTone=false,hysteresis=false;
    bool independentHysteresisSupported()const{return algorithm!=Algorithm::Stock;}
    unsigned shaderAlgorithm()const{
        return algorithm==Algorithm::Blue?1u:algorithm==Algorithm::Threshold?2u:0u;
    }
    bool legacyBayerCheck()const{return algorithm==Algorithm::Bayer&&!videoTone&&!hysteresis;}
};
inline Binary binary(bool video,Algorithm requested,Hysteresis hyst,Algorithm oldVideo){
    Binary r;r.videoTone=video;
    r.algorithm=requested==Algorithm::Default?(video?oldVideo:Algorithm::Bayer):requested;
    // 同类约束在 parseAlgorithm 已执行，这层也防止调用者把灰度选择送入黑白。
    if(r.algorithm!=Algorithm::Stock&&r.algorithm!=Algorithm::Bayer&&r.algorithm!=Algorithm::Blue&&r.algorithm!=Algorithm::Threshold)
        r.algorithm=video?oldVideo:Algorithm::Bayer;
    if(!video&&r.algorithm==Algorithm::Stock)r.algorithm=Algorithm::Bayer;
    r.hysteresis=r.independentHysteresisSupported()&&(hyst==Hysteresis::On||(hyst==Hysteresis::Default&&video));
    return r;
}
// 保留旧灰度分支和锁屏 nearest 的优先级：仅显式 alg 覆盖它们。
enum class Gray { Legacy, Stock16, Nearest16, Bayer16 };
inline Gray gray(Algorithm requested,bool legacyStock16,bool lockNearest){
    if(requested==Algorithm::Stock)return Gray::Stock16;
    if(requested==Algorithm::Nearest)return Gray::Nearest16;
    if(requested==Algorithm::Bayer16)return Gray::Bayer16;
    if(legacyStock16&&lockNearest)return Gray::Nearest16;
    return legacyStock16?Gray::Stock16:Gray::Legacy;
}
} // namespace einkalg
