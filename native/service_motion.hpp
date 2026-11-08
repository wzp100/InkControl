#pragma once
// Wave-level transitions for EinkMotion (过渡动画模块): inside ONE A2 update every pixel
// starts its full stock waveform S(x,y) scan frames late. Pixels that have not started
// yet, or have already finished, get drive code 0 (no drive), so each pixel is still
// driven exactly once and the last frame stays neutral.
//
// system_server writes debug.eink.motion.req = "seq style spread ox oy dir deadline_ms"
// (deadline on CLOCK_MONOTONIC, same clock as SystemClock.uptimeMillis). The first
// eligible GPU A2 update before the deadline whose changed-pixel count is large enough
// consumes it. The maps follow buildMap() in 墨水屏刷新分析/过渡动画/preview_template.html,
// evaluated on its 360x720 grid and scaled 2x to the 720x1440 portrait screen.
#include <sys/system_properties.h>
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <string>
#include <vector>

// system_server can't read vendor files; the EinkMotion settings UI greps this with root.
__attribute__((used)) static const char motionMarker[]="EINK_MOTION_V2:ink,dots,scan,blind,gcsweep";

__attribute__((used)) static const char lockMotionMarker[]="EINK_LOCK_MOTION_V1:ink,dots,scan,blind";
constexpr int MOTION_MAX_FRAMES=24;
// Ignore small updates (touch feedback, clock) and wait for the real screen change.
constexpr uint32_t MOTION_MIN_DIFF=720u*1440u*12u/100u;
// Above this A2 length (cold panel) the extra frames are not worth it.
constexpr int MOTION_MAX_ACTIVE=16;

static uint32_t frontDiff=UINT32_MAX;   // changed portrait pixels from the GPU front pass; MAX = unknown
static int motionMax=0;                 // >0: the wave being started uses smapHost
static bool motionExtended=false;
static int motionSavedTotal=0;
static void*motionCtl=nullptr;
static std::vector<uint8_t> smapHost;   // drive layout: row r = portrait x (0..719), column c = portrait y (0..1439)

struct MotionReq{unsigned long long seq=0,deadline=0;char style[16]={};char dir[8]={};int spread=0,minPercent=12,target=0;float ox=0,oy=0;bool pending=false;};
static MotionReq motionReq;
static const prop_info*motionProp=nullptr;
static uint32_t motionSerial=0;

static void motionPoll(){
    if(!motionProp)motionProp=__system_property_find("debug.eink.motion.req");
    if(!motionProp)return;
    struct Out{char value[PROP_VALUE_MAX];uint32_t serial;} out{};
    __system_property_read_callback(motionProp,[](void*c,const char*,const char*v,uint32_t serial){
        auto*o=static_cast<Out*>(c);std::strncpy(o->value,v,PROP_VALUE_MAX-1);o->serial=serial;},&out);
    if(out.serial==motionSerial)return;
    motionSerial=out.serial;
    MotionReq r;
    int parsed=std::sscanf(out.value,"%llu %15s %d %f %f %7s %llu %d %d",&r.seq,r.style,&r.spread,&r.ox,&r.oy,r.dir,&r.deadline,&r.minPercent,&r.target);
    motionReq.pending=false; // 空请求、损坏请求也撤销之前未消费的动画。
    if(parsed>=7&&r.spread>=1&&r.spread<=MOTION_MAX_FRAMES&&std::isfinite(r.ox)&&std::isfinite(r.oy)&&
       (r.minPercent==1||r.minPercent==12)&&r.target>=0&&r.target<=2){
        r.pending=true;motionReq=r;
        LOG("MOTION_REQ seq=%llu style=%s spread=%d origin=%.0f,%.0f dir=%s",r.seq,r.style,r.spread,r.ox,r.oy,r.dir);
    }
}

// ---- maps on the preview grid (360x720), same formulas as buildMap()
constexpr int PW=360,PH=720;

static const std::vector<float>&inkNoise(){
    static std::vector<float> n;
    if(!n.empty())return n;
    uint32_t s=20261009u;
    auto rnd=[&](){s=s*1103515245u+12345u;return float(s/4294967296.0);};
    auto layer=[&](int cell){
        int gw=(PW+cell-1)/cell+2,gh=(PH+cell-1)/cell+2;
        std::vector<float> g(size_t(gw)*gh);for(auto&v:g)v=rnd();
        std::vector<float> r(size_t(PW)*PH);
        for(int y=0;y<PH;y++)for(int x=0;x<PW;x++){
            float fx=float(x)/cell,fy=float(y)/cell;int ix=int(fx),iy=int(fy);
            float u=fx-ix,v=fy-iy;u=u*u*(3-2*u);v=v*v*(3-2*v);
            float a=g[iy*gw+ix],b=g[iy*gw+ix+1],c=g[(iy+1)*gw+ix],d=g[(iy+1)*gw+ix+1];
            r[y*PW+x]=a+(b-a)*u+(c-a)*v+(a-b-c+d)*u*v;
        }
        return r;
    };
    auto l1=layer(64),l2=layer(24),l3=layer(8);
    n.resize(size_t(PW)*PH);
    for(size_t i=0;i<n.size();i++)n[i]=l1[i]*0.55f+l2[i]*0.3f+l3[i]*0.15f;
    return n;
}

static const float*dotTable(){
    static float t[64];static bool ready=false;
    if(ready)return t;
    struct Cell{int i;double d;};std::vector<Cell> cells;
    for(int y=0;y<8;y++)for(int x=0;x<8;x++)cells.push_back({y*8+x,std::hypot(x-3.5,y-3.5)+0.01*((x*7+y*3)%5)});
    std::stable_sort(cells.begin(),cells.end(),[](const Cell&a,const Cell&b){return a.d<b.d;});
    for(int r=0;r<64;r++)t[cells[r].i]=float(r)/63.f;
    ready=true;return t;
}

// Fill smapHost; returns the largest offset in frames (0 = nothing to do).
static int buildMotionMap(const MotionReq&q){
    const std::string style=q.style,dir=q.dir;
    int kind=style=="ink"?0:style=="dots"?1:(style=="scan"||style=="gcsweep")?2:style=="blind"?3:-1;
    if(kind<0)return 0;
    float sp=std::min(std::max(q.spread,1),MOTION_MAX_FRAMES);
    float ox=std::min(std::max(q.ox/2,0.f),float(PW-1)),oy=std::min(std::max(q.oy/2,0.f),float(PH-1));
    float maxD=0;
    for(auto c:{std::make_pair(0.f,0.f),std::make_pair(float(PW),0.f),std::make_pair(0.f,float(PH)),std::make_pair(float(PW),float(PH))})
        maxD=std::max(maxD,std::hypot(c.first-ox,c.second-oy));
    const float*dots=kind==1?dotTable():nullptr;
    const float*noise=kind==0?inkNoise().data():nullptr;
    smapHost.assign(1440*720,0);
    int maxS=0;
    for(int py=0;py<PH;py++)for(int px=0;px<PW;px++){
        float s=0;
        if(kind==0){
            float d=std::hypot(px-ox,py-oy)/maxD;
            s=std::min(1.f,std::max(0.f,d*0.85f+(noise[py*PW+px]-0.5f)*0.35f+0.08f))*sp;
        }else if(kind==1){
            int cx=px>>3,cy=py>>3;
            s=(dots[(py&7)*8+(px&7)]*0.8f+float((cx*13+cy*7)%5)/5.f*0.2f)*sp;
        }else if(kind==2){
            if(dir=="btt")s=float(PH-1-py)/PH*sp;
            else if(dir=="ltr")s=float(px)/PW*sp;
            else if(dir=="rtl")s=float(PW-1-px)/PW*sp;
            else s=float(py)/PH*sp;
        }else{
            bool horizontal=dir=="ltr"||dir=="rtl";
            int v=horizontal?px:py;
            if(dir=="rtl"||dir=="btt")v=(horizontal?PW:PH)-1-v;
            s=float(v%60)/60.f*sp;
        }
        int v=std::min(MOTION_MAX_FRAMES,int(std::lround(s)));
        maxS=std::max(maxS,v);
        // preview pixel (px,py) covers portrait x=2px..2px+1, y=2py..2py+1
        for(int dx=0;dx<2;dx++){uint8_t*row=&smapHost[size_t(2*px+dx)*1440+2*py];row[0]=row[1]=uint8_t(v);}
    }
    return maxS;
}

// Frame 0 of an eligible GPU A2 wave, before renderWave: decide whether this wave animates.
static void motionDecide(void*ctl,bool gc=false,bool reading=false){
    motionMax=0;
    motionPoll();
    unsigned long long ms=now()/1000000ULL;
    if(motionReq.pending&&ms>motionReq.deadline){motionReq.pending=false;LOG("MOTION_EXPIRED seq=%llu",motionReq.seq);}
    // 大面积应用切换会被原厂提升为 GC16。请求优先于默认全刷策略，
    // 因而不再因实际波形从 A2 变成 GC16 而丢掉整段动画。
    bool targetMatches=reading?motionReq.target==1:motionReq.target!=1;
    // bitmap 没走亮屏 front compare，frontDiff 为 unknown；仅显式首次送图请求绕过这一阈值。
    bool requested=motionReq.pending&&targetMatches&&(reading||motionReq.target==2||(frontDiff!=UINT32_MAX&&
        frontDiff>=720u*1440u*uint32_t(motionReq.minPercent)/100u));
    if(gc&&!requested){
        char policy[PROP_VALUE_MAX]={};__system_property_get("debug.eink.motion.gc",policy);
        int spread=0;char rawDir[8]={};
        // 新格式 "gcsweep <spread> <dir>"；旧格式只有 spread，方向按原厂习惯取 ltr。
        if(std::sscanf(policy,"gcsweep %d %7s",&spread,rawDir)<1||spread<1||spread>MOTION_MAX_FRAMES)return;
        const char*dir="ltr";
        if(rawDir[0]&&(!std::strcmp(rawDir,"rtl")||!std::strcmp(rawDir,"ttb")||!std::strcmp(rawDir,"btt")))dir=rawDir;
        MotionReq sweep;std::strcpy(sweep.style,"gcsweep");std::strncpy(sweep.dir,dir,sizeof(sweep.dir)-1);sweep.spread=spread;
        motionMax=buildMotionMap(sweep);
        LOG("MOTION_GC wave=%llu max=%d active=%d",(unsigned long long)waveId,motionMax,field<int>(ctl,0x160));
        return;
    }
    if(!requested)return;
    int active=field<int>(ctl,0x160);
    if(!gc&&!reading&&active>MOTION_MAX_ACTIVE){motionReq.pending=false;LOG("MOTION_SKIP_COLD seq=%llu active=%d",motionReq.seq,active);return;}
    uint64_t t0=now();
    motionMax=buildMotionMap(motionReq);
    motionReq.pending=false;
    LOG("MOTION wave=%llu seq=%llu style=%s max=%d active=%d diff=%u map_us=%llu",(unsigned long long)waveId,motionReq.seq,
        motionReq.style,motionMax,active,frontDiff,(unsigned long long)(now()-t0)/1000);
    LOG("LOCK_MOTION wave=%llu target=%d reading=%d original_active=%d delay=%d",(unsigned long long)waveId,motionReq.target,reading,active,motionMax);
}

// After the stock code generated frame 0: make its A2 loop run motionMax more frames.
// Frame index = total - remaining, so adding the same amount to both keeps the count going.
static void motionExtend(void*ctl){
    auto*c=static_cast<uint8_t*>(ctl);int remaining,total;
    std::memcpy(&remaining,c+0xc8,4);std::memcpy(&total,c+0x160,4);
    motionSavedTotal=total;remaining+=motionMax;total+=motionMax;
    std::memcpy(c+0xc8,&remaining,4);std::memcpy(c+0x160,&total,4);
    motionCtl=ctl;motionExtended=true;
}

static void motionRestore(){
    if(!motionExtended)return;
    std::memcpy(static_cast<uint8_t*>(motionCtl)+0x160,&motionSavedTotal,4);
    motionExtended=false;motionMax=0;
}

// GPU 渲染失败时撤销延长，避免原厂代码用扩展后的帧号越界访问 LUT。
static void motionAbort(void*ctl,int&frame){
    if(!motionExtended)return;
    int original=motionSavedTotal;
    frame=std::min(frame,original);
    int remaining=original-frame;
    std::memcpy(static_cast<uint8_t*>(ctl)+0xc8,&remaining,4);
    motionRestore();
}
