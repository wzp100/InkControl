#include "../../native/service_algorithm_policy.hpp"
#include <cstdlib>
#include <iostream>
#include <string>
#include <vector>
static unsigned checks=0;
#define CHECK(c) do{++checks;if(!(c)){std::cerr<<"line "<<__LINE__<<": " #c "\n";std::abort();}}while(0)
using namespace einkalg;
int main(){
    struct Case {unsigned mode,selected,internal;Group want;};
    // 按实际 stock 映射列举外部入口，防止仅凭 selected=8 混淆快速与极速。
    for(const auto&c:std::vector<Case>{{2,2,2,Group::Clear},{3,5,5,Group::Reading},
        {4,5,5,Group::Reading},{5,5,5,Group::Reading},{6,8,8,Group::Fast},{9,8,8,Group::Extreme},
        {0,8,8,Group::None},{1,1,1,Group::None},{7,8,8,Group::None},{8,8,8,Group::None},
        {255,8,8,Group::None},{6,6,8,Group::None},{9,8,2,Group::None},{2,8,8,Group::None}}){
        for(unsigned flags:{0u,0x100u,0x200u,0x300u}){
            Context s{c.mode|flags,c.selected,c.internal};CHECK(group(s)==c.want);
            s.reset=true;CHECK(group(s)==Group::None);s.reset=false;
            s.transition=true;CHECK(group(s)==Group::None);s.transition=false;
            s.blank=true;CHECK(group(s)==Group::None);
        }
    }
    for(unsigned mode:{0u,2u,3u,4u,5u,6u,7u,8u,9u}){
        CHECK(group({mode|0x600u,8,8})==Group::Video);
        CHECK(group({mode|0x600u,6,8})!=Group::Video);
        CHECK(group({mode|0x600u,8,2})==Group::None);
    }
    CHECK(group({0x400,8,8})==Group::Video);
    CHECK(group({0x608,8,8})==Group::Video);
    CHECK(std::string(algorithmProperty(group({0x206,8,8})))=="debug.eink.alg.fast");
    CHECK(std::string(algorithmProperty(group({0x209,8,8})))=="debug.eink.alg.extreme");
    CHECK(std::string(hysteresisProperty(group({0x609,8,8})))=="debug.eink.hyst.video");
    CHECK(algorithmProperty(Group::None)==nullptr&&hysteresisProperty(Group::Reading)==nullptr);
    // 同一次配置：快速 blue、极速 threshold，视频单独 stock。外部6和9不能共用一项。
    auto configured=[](Context c){
        auto g=group(c);const char*key=algorithmProperty(g);
        const char*value=equal(key,"debug.eink.alg.fast")?"blue":
            equal(key,"debug.eink.alg.extreme")?"threshold":equal(key,"debug.eink.alg.video")?"stock":"default";
        return parseAlgorithm(g,value);
    };
    CHECK(configured({0x206,8,8})==Algorithm::Blue);
    CHECK(configured({0x209,8,8})==Algorithm::Threshold);
    CHECK(configured({0x606,8,8})==Algorithm::Stock);
    CHECK(configured({0x609,8,8})==Algorithm::Stock);
    CHECK(configured({0x200,8,8})==Algorithm::Default);
    for(auto g:{Group::None,Group::Clear,Group::Reading,Group::Fast,Group::Extreme,Group::Video}){
        for(const char*v:{static_cast<const char*>(nullptr),"","default","Default","blue ","on","?"})
            CHECK(parseAlgorithm(g,v)==Algorithm::Default);
        CHECK(parseHysteresis(nullptr)==Hysteresis::Default);
        CHECK(parseHysteresis("ON")==Hysteresis::Default);
        for(const char*v:{"stock","nearest","bayer16","bayer","blue","threshold"}){
            const auto a=parseAlgorithm(g,v);
            if(g==Group::Clear||g==Group::Reading)
                CHECK(a==Algorithm::Default||a==Algorithm::Stock||a==Algorithm::Nearest||a==Algorithm::Bayer16);
            else if(g==Group::Fast||g==Group::Extreme)
                // 快速/极速自 0.3.0 起也可选「原厂」：该模式的像素量化交回原厂 CPU 路径。
                CHECK(a==Algorithm::Default||a==Algorithm::Stock||a==Algorithm::Bayer||a==Algorithm::Blue||a==Algorithm::Threshold);
        }
    }
    CHECK(parseAlgorithm(Group::Clear,"stock")==Algorithm::Stock);
    CHECK(parseAlgorithm(Group::Reading,"nearest")==Algorithm::Nearest);
    CHECK(parseAlgorithm(Group::Reading,"bayer16")==Algorithm::Bayer16);
    CHECK(parseAlgorithm(Group::Fast,"blue")==Algorithm::Blue);
    CHECK(parseAlgorithm(Group::Extreme,"threshold")==Algorithm::Threshold);
    CHECK(parseAlgorithm(Group::Video,"stock")==Algorithm::Stock);
    for(const char*alg:{"","default","garbage"})for(const char*hyst:{"","default","garbage"}){
        auto b=binary(false,parseAlgorithm(Group::Fast,alg),parseHysteresis(hyst),Algorithm::Stock);
        CHECK(b.algorithm==Algorithm::Bayer&&b.legacyBayerCheck()&&!b.hysteresis);
        for(const char*old:{"blue","bayer","stock","", "invalid"}){
            b=binary(true,parseAlgorithm(Group::Video,alg),parseHysteresis(hyst),legacyVideo(old));
            CHECK(b.algorithm==legacyVideo(old)&&b.videoTone);
            CHECK(b.hysteresis==(b.algorithm!=Algorithm::Stock));
        }
        CHECK(gray(parseAlgorithm(Group::Reading,alg),true,true)==Gray::Nearest16);
        CHECK(gray(parseAlgorithm(Group::Reading,alg),true,false)==Gray::Stock16);
        CHECK(gray(parseAlgorithm(Group::Reading,alg),false,true)==Gray::Legacy);
    }
    // 选择和迟滞独立；stock 不能被 generic off/on 破坏内部历史。
    for(auto a:{Algorithm::Bayer,Algorithm::Blue,Algorithm::Threshold}){
        for(bool video:{false,true}){
            CHECK(binary(video,a,Hysteresis::On,Algorithm::Blue).hysteresis);
            CHECK(!binary(video,a,Hysteresis::Off,Algorithm::Blue).hysteresis);
            CHECK(binary(video,a,Hysteresis::Default,Algorithm::Blue).hysteresis==video);
        }
    }
    for(auto h:{Hysteresis::Default,Hysteresis::On,Hysteresis::Off}){
        auto b=binary(true,Algorithm::Stock,h,Algorithm::Blue);
        CHECK(!b.hysteresis&&!b.independentHysteresisSupported());
    }
    for(bool oldStock:{false,true})for(bool lock:{false,true}){
        CHECK(gray(Algorithm::Stock,oldStock,lock)==Gray::Stock16);
        CHECK(gray(Algorithm::Nearest,oldStock,lock)==Gray::Nearest16);
        CHECK(gray(Algorithm::Bayer16,oldStock,lock)==Gray::Bayer16);
    }
    // 逐低字节证明不会向自动/未公开模式泄漏 fast/extreme 属性。
    for(unsigned external=0;external<256;external++){
        auto g=group({external|0x200u,8,8});
        CHECK(g==(external==6?Group::Fast:external==9?Group::Extreme:Group::None));
        Context v{external|0x600u,8,8};CHECK(group(v)==Group::Video);
        v.reset=true;CHECK(group(v)==Group::None);v.reset=false;
        v.transition=true;CHECK(group(v)==Group::None);v.transition=false;
        v.blank=true;CHECK(group(v)==Group::None);
    }
    std::cout<<"algorithm_policy: "<<checks<<" checks passed\n";
}
