// Service hooks use the existing live waveform/state; no captured LUT scanout.
#include <android/log.h>
#include <link.h>
#include <cstring>
#include <cstdint>
#include <ctime>
#include <mutex>
#include <atomic>
#include <unistd.h>
#include <pthread.h>
#include <sys/system_properties.h>

static uintptr_t base;
static bool gpuMode;
static uint64_t configuredMode;
static std::mutex ringMutex;
static uint64_t waveId,modeStart,modeEnd;
static int generated;
// Read-only root observer ABI in the appended RW segment. No property-service
// permission is needed by the vendor HAL. Counters distinguish helper loading,
// GPU preprocessing, GPU scan submission and ordinary CPU fallback waves.
static constexpr uintptr_t TELEMETRY=0x6300100;
static constexpr uint64_t TELEMETRY_MAGIC=0x45494e4b47505503ULL;
enum TelemetryWord { T_MAGIC,T_PIXELS,T_SCAN,T_CPU,T_ERRORS,T_WAVE,T_PATH,T_SCAN_NS,T_READBACK,T_UPLOAD,T_INPUT_GPU,T_INPUT_CPU,T_WORDS };
static void telemetrySet(int word,uint64_t value){if(base)__atomic_store_n(reinterpret_cast<uint64_t*>(base+TELEMETRY)+word,value,__ATOMIC_RELEASE);}
static void telemetryAdd(int word){if(base)__atomic_fetch_add(reinterpret_cast<uint64_t*>(base+TELEMETRY)+word,uint64_t(1),__ATOMIC_RELEASE);}
static void telemetryBytes(int word,size_t n){if(base)__atomic_fetch_add(reinterpret_cast<uint64_t*>(base+TELEMETRY)+word,uint64_t(n),__ATOMIC_RELEASE);}
struct Record {uint64_t wave=0,ready=0,input=0;int frame=0,last=0,pool=-1;};
static Record ringRecords[8],consuming;
static size_t consumingPoolFrame;
static uint64_t now(){timespec t;clock_gettime(CLOCK_MONOTONIC,&t);return uint64_t(t.tv_sec)*1000000000+t.tv_nsec;}
template<class T>static T load(uintptr_t p){T v;std::memcpy(&v,reinterpret_cast<void*>(p),sizeof(v));return v;}
template<class T>static T field(void*p,int offset){return load<T>(reinterpret_cast<uintptr_t>(p)+offset);}
#define LOG(...) __android_log_print(ANDROID_LOG_INFO,"EinkGPU",__VA_ARGS__)
// Per-frame/per-wave tracing is for test builds only (~340 lines/s while scrolling).
#ifndef EINK_VERBOSE
#define EINK_VERBOSE 0
#endif
#define VLOG(...) do{if(EINK_VERBOSE)LOG(__VA_ARGS__);}while(0)
// Program locations remain valid for this one producer context/program lifetime.
// Runtime switches exist only in the profiling build; shipping candidate is fixed.
#ifndef EINK_PERF_AB
#define EINK_PERF_AB 0
#endif
static bool useUniformCache(){
    char value[PROP_VALUE_MAX]={};__system_property_get("debug.inkcontrol.cache",value);
    return value[0]=='1';
}
static uint32_t firstWindowFrames(){
#if EINK_PERF_AB
    char value[PROP_VALUE_MAX]={};__system_property_get("debug.eink.perf.first",value);
    if(value[0]=='2')return 2;if(value[0]=='4')return 4;
    return 8;
#endif
    return 8;
}
static void hookPattern(void*);
static int hookUpdate(void*,void*);
static void hookConsume(int);
static void hookFlip();
static int hookWait();
static int hookMode(void*,void*,int,int,int,int);
static int hookPreprocess(void*,void*,int,int);
static int hookPostprocess(void*);
static void*hookCommit(void*,const void*,size_t);
static void hookTranspose();
static void*hookTake(void*,const void*,size_t);
static int hookCompare(const void*,const void*,size_t);
static void*hookStore(void*,const void*,size_t);
static void hookMixY4(const uint8_t*,const uint8_t*,uint8_t*,int,int);
static void hookMixRegal(const uint8_t*,const uint8_t*,uint8_t*,int,int,void*);
static int hookInlinePattern(void*,void*);
static int hookAutoStats(void*,void*,void*,unsigned,unsigned,int);
static void hookDumpFrameBuffer(void*)noexcept;
static void*hookRawFill(void*,int,size_t);
static void hookMixChange(const uint8_t*,uint8_t*,int,int);
static void hookMixReset(const uint8_t*,uint8_t*,int,int);
static void*hookOldFill(void*,int,size_t);
static int hookBlankPattern(void*);
static void hookBoundClear(void*,unsigned,unsigned);
static void syncCpuBound(void*);
static unsigned prepareStockVideo(void*,unsigned,unsigned,unsigned);
static void syncCpuColorHistory(bool);
static void hookLutPaired(uint8_t*,unsigned,const uint8_t*,unsigned);
static void hookLutPadding(uint8_t*,unsigned,const uint8_t*,unsigned,unsigned);
static void hookLutRegal(uint8_t*,unsigned,const uint8_t*,unsigned,unsigned);
static unsigned gpuLutFor(const uint8_t*,uint32_t);
static int hookRegionPattern(void*);
static int findHwc(dl_phdr_info*info,size_t,void*){
    if(!strstr(info->dlpi_name,"hwcomposer.ums512.so"))return 0;
    base=info->dlpi_addr;auto*table=reinterpret_cast<uintptr_t*>(base+0x6300000);
    for(int i=1;i<T_WORDS;i++)telemetrySet(i,0);
    telemetrySet(T_MAGIC,TELEMETRY_MAGIC);
    gpuMode=table[16]!=0;
    table[0]=reinterpret_cast<uintptr_t>(hookUpdate);table[1]=reinterpret_cast<uintptr_t>(hookPattern);
    table[2]=reinterpret_cast<uintptr_t>(hookConsume);table[3]=reinterpret_cast<uintptr_t>(hookFlip);
    table[4]=reinterpret_cast<uintptr_t>(hookWait);table[5]=reinterpret_cast<uintptr_t>(hookMode);
    table[6]=reinterpret_cast<uintptr_t>(hookPreprocess);table[7]=reinterpret_cast<uintptr_t>(hookPostprocess);
    table[8]=reinterpret_cast<uintptr_t>(hookCommit);
    table[9]=reinterpret_cast<uintptr_t>(hookTranspose);table[10]=reinterpret_cast<uintptr_t>(hookTake);
    table[11]=reinterpret_cast<uintptr_t>(hookCompare);table[12]=reinterpret_cast<uintptr_t>(hookStore);
    table[13]=reinterpret_cast<uintptr_t>(hookMixY4);table[14]=reinterpret_cast<uintptr_t>(hookMixRegal);
    table[15]=reinterpret_cast<uintptr_t>(hookInlinePattern);
    table[17]=reinterpret_cast<uintptr_t>(hookAutoStats);
    table[18]=reinterpret_cast<uintptr_t>(hookDumpFrameBuffer);
    table[19]=reinterpret_cast<uintptr_t>(hookMixChange);
    table[20]=reinterpret_cast<uintptr_t>(hookMixReset);
    table[21]=reinterpret_cast<uintptr_t>(hookOldFill);
    table[22]=reinterpret_cast<uintptr_t>(hookRawFill);
    table[23]=reinterpret_cast<uintptr_t>(hookBlankPattern);
    table[24]=reinterpret_cast<uintptr_t>(hookBoundClear);
    table[25]=reinterpret_cast<uintptr_t>(hookLutPaired);
    table[26]=reinterpret_cast<uintptr_t>(hookLutPadding);
    table[27]=reinterpret_cast<uintptr_t>(hookLutRegal);
    table[28]=reinterpret_cast<uintptr_t>(hookRegionPattern);
    LOG("INIT base=%p gpu=%d",reinterpret_cast<void*>(base),gpuMode);return 1;
}
__attribute__((constructor)) static void initialize(){dl_iterate_phdr(findHwc,nullptr);}

#include "service_motion.hpp"
#include "service_renderer.hpp"
#include "service_lut_runtime.hpp"
#include "service_input.hpp"
#include "service_preprocess.hpp"
#include "service_stock_color.hpp"
#include "service_transitions.hpp"
#include "service_reading_capture.hpp"
#include "service_pageanim.hpp"
#include "service_fusion.hpp"

// 融合分带这次的延长帧数；0 表示本次波形不分带。frame 0 的原厂调用之后
// 按它给 ctl 的 remaining/total 同时加数，让每条带都能跑完整条 LUT。
static uint32_t fusionTail=0;

// The stock mode/state machine remains authoritative. Only its image-sized
// consumers are substituted; their addresses and dimensions are checked here.
static GLuint residentIndices;
static einkwave::IndexLayout residentLayout=einkwave::IndexLayout::PairY4;
static bool rollingProducer=false;
static int rollingWindowPool=-1;
static uint32_t rollingWindowEnd=0;
static einktransition::Phase rollingPhase=einktransition::Phase::Unsupported;
static einktransition::Kernels transitionKernels;
static GLuint transitionMix;
static GLuint blankIndices;
static GLuint residentBound;
static void*boundOwner;
static bool boundCpuDirty;
static void syncCpuBound(void*ctl){
    if(!boundCpuDirty||ctl!=boundOwner)return;
    finishPixels();glBindBuffer(GL_SHADER_STORAGE_BUFFER,residentBound);
    auto*p=glMapBufferRange(GL_SHADER_STORAGE_BUFFER,0,PIXELS,GL_MAP_READ_BIT);require(p,"A2 bound recovery map");
    std::memcpy(field<void*>(ctl,0x2d0),p,PIXELS);require(glUnmapBuffer(GL_SHADER_STORAGE_BUFFER),"A2 bound recovery unmap");
    telemetryBytes(T_READBACK,PIXELS);boundCpuDirty=false;VLOG("A2_BOUND_CPU_BRIDGE bytes=%zu",PIXELS);
}
static void hookBoundClear(void*destination,unsigned width,unsigned height){
    if(preparedGray.ready&&fullPixelWave&&pixelCtl&&width==1440&&height==720&&
       destination==field<void*>(pixelCtl,0x2d0)){
        try{
            if(!residentBound){glGenBuffers(1,&residentBound);glBindBuffer(GL_SHADER_STORAGE_BUFFER,residentBound);
                glBufferData(GL_SHADER_STORAGE_BUFFER,PIXELS,nullptr,GL_DYNAMIC_COPY);}
            transitionKernels.clearA2Bound(residentBound,width,height);
            boundOwner=pixelCtl;boundCpuDirty=true;VLOG("A2_BOUND_GPU_CLEAR wave=%llu bytes=%zu",(unsigned long long)waveId,PIXELS);return;
        }catch(const std::exception&e){telemetryAdd(T_ERRORS);LOG("A2_BOUND_FALLBACK reason=%s",e.what());gpuFailed=true;}
    }
    reinterpret_cast<void(*)(void*,unsigned,unsigned)>(base+0x97da4)(destination,width,height);
    if(pixelCtl&&destination==field<void*>(pixelCtl,0x2d0))boundCpuDirty=false;
}
static bool prepareOtherPixels(void*ctl,int mode,int selected){
    if(!prepareGrayLevels(ctl,mode,selected,pixelRgba))return false;
    residentIndices=mixBuffer;residentLayout=einkwave::IndexLayout::PairY4;return true;
}
static void bridgeResidentPixels(void*ctl){
    if(field<int>(ctl,0x280)!=0&&!preparedGray.ready){syncCpuOld(ctl);fullPixelWave=false;return;}
    GLuint indices=residentIndices;auto layout=residentLayout;
    recoverCpuPixels(ctl);preparedGray={};
    if(transitionMix&&field<int>(ctl,0x278)!=0){
        finishPixels();glBindBuffer(GL_SHADER_STORAGE_BUFFER,transitionMix);
        auto*p=glMapBufferRange(GL_SHADER_STORAGE_BUFFER,0,PIXELS,GL_MAP_READ_BIT);require(p,"transition recovery map");
        std::memcpy(field<void*>(ctl,0x48),p,PIXELS);require(glUnmapBuffer(GL_SHADER_STORAGE_BUFFER),"transition recovery unmap");
        telemetryBytes(T_READBACK,PIXELS);
    }
    if(indices&&layout!=einkwave::IndexLayout::PairY4){
        finishPixels();glBindBuffer(GL_SHADER_STORAGE_BUFFER,indices);
        auto*p=glMapBufferRange(GL_SHADER_STORAGE_BUFFER,0,PIXELS*2,GL_MAP_READ_BIT);require(p,"REGAL recovery map");
        std::memcpy(field<void*>(ctl,0x50),p,PIXELS*2);require(glUnmapBuffer(GL_SHADER_STORAGE_BUFFER),"REGAL recovery unmap");
        telemetryBytes(T_READBACK,PIXELS*2);
    }
}
static bool residentMixArguments(const void*old,const void*next,int w,int h){
    return preparedGray.ready&&preparedGray.wave==waveId&&pixelCtl&&fullPixelWave&&
        old==field<void*>(pixelCtl,0x60)&&next==field<void*>(pixelCtl,0x58)&&w==1440&&h==720;
}
static void hookMixY4(const uint8_t*old,const uint8_t*next,uint8_t*out,int w,int h){
    if(residentMixArguments(old,next,w,h)&&out==field<void*>(pixelCtl,0x40)){
        residentIndices=mixBuffer;residentLayout=einkwave::IndexLayout::PairY4;return;
    }
    if(preparedGray.ready)bridgeResidentPixels(pixelCtl);
    reinterpret_cast<void(*)(const uint8_t*,const uint8_t*,uint8_t*,int,int)>(base+0x9a5a8)(old,next,out,w,h);
}
static void hookMixRegal(const uint8_t*old,const uint8_t*next,uint8_t*out,int w,int h,void*region){
    if(residentMixArguments(old,next,w,h)&&out==field<void*>(pixelCtl,0x50)){
        try{
            auto&b=waveRegalBuffers[pixelCtl];
            if(!b.buffer)glGenBuffers(1,&b.buffer);
            if(!b.fullDestination){upload(b.buffer,out,PIXELS*2,2);telemetryBytes(T_UPLOAD,PIXELS*2);waveFence();b.fullDestination=true;}
            residentIndices=gpuMixRegalForCtl(pixelCtl,oldLevels(),newLevels());
            residentLayout=einkwave::IndexLayout::PixelY8Edge;return;
        }catch(const std::exception&e){telemetryAdd(T_ERRORS);LOG("REGAL_FALLBACK reason=%s",e.what());bridgeResidentPixels(pixelCtl);gpuFailed=true;}
    }else if(preparedGray.ready)bridgeResidentPixels(pixelCtl);
    reinterpret_cast<void(*)(const uint8_t*,const uint8_t*,uint8_t*,int,int,void*)>(base+0x9a140)(old,next,out,w,h,region);
    if(pixelCtl&&out==field<void*>(pixelCtl,0x50))waveRegalBuffers[pixelCtl].fullDestination=false;
}
static int hookInlinePattern(void*,void*ctl){return rollingProducer&&ctl==producerCtl?1:0;}
static int hookBlankPattern(void*ctl){return rollingProducer&&rollingPhase==einktransition::Phase::Blank&&ctl==producerCtl?1:0;}
static int hookRegionPattern(void*ctl){
    if(!rollingProducer||ctl!=producerCtl||rollingPhase!=einktransition::Phase::Normal||
       !rollingWave.request.twoRegions||field<uint32_t>(ctl,0x2c8)!=1)return 0;
    // Retain the exact final metadata writes of the two original inline loops.
    uint32_t rect[4]={1300,field<uint32_t>(ctl,0xbc)-1,0,field<uint32_t>(ctl,0xc0)-1};
    std::memcpy(static_cast<uint8_t*>(ctl)+0x2b8,rect,sizeof(rect));
    VLOG("REGION_GPU_SCAN wave=%llu frame=%u",(unsigned long long)waveId,rollingWave.nextFrame);return 1;
}
static void hookMixChange(const uint8_t*source,uint8_t*destination,int w,int h){
    if(preparedGray.ready&&fullPixelWave&&einktransition::mixChangeCall(einktransition::snapshot(pixelCtl),source,destination,w,h)){
        try{transitionKernels.mixChange(newLevels(),mixBuffer);residentIndices=mixBuffer;
            residentLayout=einkwave::IndexLayout::PairY4;return;}
        catch(const std::exception&e){telemetryAdd(T_ERRORS);LOG("MIX_CHANGE_FALLBACK reason=%s",e.what());bridgeResidentPixels(pixelCtl);gpuFailed=true;}
    }else if(preparedGray.ready)bridgeResidentPixels(pixelCtl);
    reinterpret_cast<void(*)(const uint8_t*,uint8_t*,int,int)>(base+0x9a624)(source,destination,w,h);
}
static void hookMixReset(const uint8_t*source,uint8_t*destination,int w,int h){
    if(preparedGray.ready&&fullPixelWave&&einktransition::mixResetCall(einktransition::snapshot(pixelCtl),source,destination,w,h)){
        try{if(!transitionMix){glGenBuffers(1,&transitionMix);glBindBuffer(GL_SHADER_STORAGE_BUFFER,transitionMix);
                glBufferData(GL_SHADER_STORAGE_BUFFER,PIXELS,nullptr,GL_DYNAMIC_COPY);}
            transitionKernels.mixReset(oldLevels(),transitionMix);return;}
        catch(const std::exception&e){telemetryAdd(T_ERRORS);LOG("MIX_RESET_FALLBACK reason=%s",e.what());bridgeResidentPixels(pixelCtl);gpuFailed=true;}
    }else if(preparedGray.ready)bridgeResidentPixels(pixelCtl);
    reinterpret_cast<void(*)(const uint8_t*,uint8_t*,int,int)>(base+0x9a690)(source,destination,w,h);
}
static void*hookOldFill(void*destination,int value,size_t bytes){
    if(fullPixelWave&&preparedGray.ready&&einktransition::oldFillCall(einktransition::snapshot(pixelCtl),
            einktransition::OldFillCaller,destination,value,bytes)){
        try{transitionKernels.oldFillWhite(oldLevels());oldGpuValid=true;cpuOldDirty=true;shadowValid=false;return destination;}
        catch(const std::exception&e){telemetryAdd(T_ERRORS);LOG("OLD_FILL_FALLBACK reason=%s",e.what());bridgeResidentPixels(pixelCtl);gpuFailed=true;}
    }else if(preparedGray.ready)bridgeResidentPixels(pixelCtl);
    return std::memset(destination,value,bytes);
}
static int hookAutoStats(void*rgba,void*,void*result,unsigned w,unsigned h,int){
    if(!gpuSource||gpuFailed||w!=1440||h!=720)return 0;
    try{auto counts=gpuAutoStats(grayInput);std::memcpy(result,&counts,sizeof(counts));return 1;}
    catch(const std::exception&e){
        telemetryAdd(T_ERRORS);LOG("AUTO_STATS_FALLBACK reason=%s",e.what());gpuFailed=true;
        // The original loop consumes the previous-image argument, not VAR_WORK.
        try{materialiseGpuWork(grayInput,rgba);}
        catch(...){cpuInputWork(grayInput,rgba);}
        return 0;
    }
}

// The stock producer samples the next image as soon as the previous update's
// last frame is queued, while up to 7 drive frames (~82ms) still wait ahead of
// it. Wait (on the stock ring mutex/cond the send thread signals per frame)
// until at most EINK_SAMPLE_QUEUE frames remain, so a fresher image is taken
// and reaches the panel sooner. Waveforms and the ring itself are unchanged.
#ifndef EINK_SAMPLE_QUEUE
#define EINK_SAMPLE_QUEUE 2
#endif
static void waitForRingDrain(){
    char sampling[PROP_VALUE_MAX]={};__system_property_get("debug.inkcontrol.sampling",sampling);
    if(sampling[0]!='1'||EINK_SAMPLE_QUEUE>=7)return;
    auto*ring=load<int*>(base+0xa0000);auto*mutex=load<pthread_mutex_t*>(base+0xa0010);auto*cond=load<pthread_cond_t*>(base+0xa0028);
    auto queued=[&](){return (__atomic_load_n(&ring[1],__ATOMIC_ACQUIRE)-__atomic_load_n(&ring[0],__ATOMIC_ACQUIRE))&7;};
    uint64_t begin=now();int before=queued();if(before<=EINK_SAMPLE_QUEUE)return;
    pthread_mutex_lock(mutex);
    while(queued()>EINK_SAMPLE_QUEUE&&now()-begin<300000000ULL){
        timespec ts;clock_gettime(CLOCK_REALTIME,&ts);ts.tv_nsec+=20000000;if(ts.tv_nsec>=1000000000){ts.tv_sec++;ts.tv_nsec-=1000000000;}
        pthread_cond_timedwait(cond,mutex,&ts);
    }
    pthread_mutex_unlock(mutex);
    VLOG("QUEUE_WAIT before=%d after=%d us=%llu",before,queued(),(unsigned long long)(now()-begin)/1000);
}
static int hookWait(){redirectInputs();waitForRingDrain();return reinterpret_cast<int(*)()>(base+0x93bb4)();}
static int hookMode(void*a,void*b,int c,int d,int e,int f){
    configuredMode=load<uint64_t>(base+0x6300080);gpuMode=configuredMode!=0;
    fullPixelWave=false;residentIndices=0;residentLayout=einkwave::IndexLayout::PairY4;
    rollingProducer=false;rollingWindowPool=-1;rollingWindowEnd=0;rollingPhase=einktransition::Phase::Unsupported;nonA2Preprocess=prepareOtherPixels;
    motionRestore();
    modeStart=now();++waveId;generated=0;producerPool=-1;eligible=false;
    beginDecision();
    int n=reinterpret_cast<int(*)(void*,void*,int,int,int,int)>(base+0x9594c)(a,b,c,d,e,f);
    endDecision();
    readingFinishInput(f);
    telemetrySet(T_WAVE,waveId);telemetrySet(T_PATH,(fullPixelWave?1ULL:0ULL)|(gpuSource?2ULL:0ULL));
    if(fullPixelWave)telemetryAdd(T_PIXELS);else telemetryAdd(T_CPU);
    modeEnd=now();VLOG("MODE wave=%llu begin=%llu end=%llu n=%d mode=%x temp=%d gpu=%d",(unsigned long long)waveId,
        (unsigned long long)modeStart,(unsigned long long)modeEnd,n,f,c,gpuMode);
    VLOG("PATH wave=%llu configured=%llu full=%d",(unsigned long long)waveId,(unsigned long long)configuredMode,fullPixelWave);return n;
}
static void hookPattern(void*p){
    VLOG("PATTERN wave=%llu f=%u w=%u h=%u bands=%d total=%d mix=%p lut=%p",(unsigned long long)waveId,
        field<uint32_t>(p,0x20),field<uint32_t>(p,0x18),field<uint32_t>(p,0x1c),load<int>(base+0x4e46320),
        load<int>(base+0x4e46324),field<void*>(p,0),field<void*>(p,8));
    if(rollingProducer&&field<int>(p,0x18)==1440&&field<int>(p,0x1c)==720)return;
    if(eligible&&field<int>(p,0x18)==1440&&field<int>(p,0x1c)==720&&load<int>(base+0x4e46320)==0){
        try{
            if(producerPool<0&&field<int>(p,0x20)==0)renderWave(p);
            if(producerPool>=0)return;
        }catch(const std::exception&e){telemetryAdd(T_ERRORS);LOG("GPU_ERROR wave=%llu reason=%s",(unsigned long long)waveId,e.what());recoverCpuPixels(producerCtl);gpuFailed=true;eligible=false;producerPool=-1;}
    }
    reinterpret_cast<void(*)(void*)>(base+0x97540)(p);
}
static int hookUpdate(void*entry,void*ctl){
    // 翻页动画 4 块原型：属性没打开时只读一个 prop 就返回 false，原流程零变化。
    if(einkpageanim::probe(entry,ctl,base))return 0;
    // 融合路线：把分块揭示重写成 GPU 波形的条带相位，默认关闭、恒不接管。
    (void)einkfusion::probe(entry,ctl,base);
    uint64_t start=now();int ordinal=generated++;
    auto control=einktransition::snapshot(ctl);auto segment=einktransition::segment(control);
    int frame=segment.valid?int(segment.localFrame):ordinal;
    int slot=(reinterpret_cast<uintptr_t>(entry)-load<uintptr_t>(base+0x9ffb0))/16;
    int remaining=field<int>(ctl,0xc8),total=field<int>(ctl,0x160);
    producerCtl=ctl;producerSlot=slot;
    eligible=false;
    bool segmentBegin=segment.valid&&segment.localFrame==0&&rollingPhase!=segment.phase;
    if(segmentBegin){
        uint8_t mode=field<uint8_t>(ctl,0x15c);
        rollingPhase=segment.phase;rollingWindowPool=-1;rollingWindowEnd=0;producerPool=-1;
        rollingProducer=gpuMode&&!gpuFailed&&fullPixelWave&&
            (segment.total>0||segment.phase==einktransition::Phase::Blank)&&segment.total<=einkwave::MaxLiveFrames&&
            (segment.phase!=einktransition::Phase::Normal||mode==1||mode==2||mode==5||mode==6||mode==8)&&
            load<int>(base+0x4e46320)==0;
        if(rollingProducer){
            try{
                if(!residentIndices)residentIndices=mixBuffer;
                GpuWaveRequest request{};
                bool blank=segment.phase==einktransition::Phase::Blank;
                static const uint32_t zero=0;
                if(blank&&!blankIndices){glGenBuffers(1,&blankIndices);upload(blankIndices,&zero,4,0);}
                request.gpuIndices=segment.phase==einktransition::Phase::Transition?transitionMix:residentIndices;
                request.layout=segment.phase==einktransition::Phase::Normal?residentLayout:einkwave::IndexLayout::PairY4;
                if(segment.regionOverlay){
                    request.layout=einkwave::IndexLayout::PixelY8;request.maskRegion=true;request.overlay12=true;request.twoRegions=true;
                    request.region={140,200,350,560};request.secondRegion={1300,0,1439,719};
                }
                request.indexBytes=request.layout==einkwave::IndexLayout::PairY4?PIXELS:PIXELS*2;
                request.liveLut=field<const uint8_t*>(ctl,segment.lutOffset);request.lutBytes=size_t(segment.total)*65536;
                if(blank){request.gpuIndices=blankIndices;request.layout=einkwave::IndexLayout::PairY4;
                    request.indexBytes=4;request.liveLut=reinterpret_cast<const uint8_t*>(&zero);request.lutBytes=4;request.neutralOnly=true;}
                require(request.gpuIndices&&request.liveLut,"phase GPU mix/live LUT unavailable");
                if(!blank){const uint8_t*terminal=request.liveLut+size_t(segment.total)*65536;
                    require(std::all_of(terminal,terminal+65536,[](uint8_t v){return v==0;}),"phase terminal LUT not neutral");}
                request.controlTemplate=field<const uint32_t*>(ctl,0x98);request.templateBytes=einkwave::FrameBytes;
                request.activeFrames=blank?0:segment.total;request.totalFrames=segment.total+1;
                if(!blank)request.gpuLut=gpuLutFor(request.liveLut,request.activeFrames);
                // 新滚动流水线也接入起跑帧图。只延长普通全屏 PairY4 波形，
                // reset/blank/模式过渡和区域叠加仍保留原厂时序。
                bool lockReading=readingBitmap&&(readingRequested&255)==3&&(mode==2||mode==5)&&
                    (request.layout==einkwave::IndexLayout::PairY4||request.layout==einkwave::IndexLayout::PixelY8Edge);
                motionPoll();
                if(motionReq.pending&&motionReq.target>0)LOG("LOCK_MOTION_GATE mode=%u layout=%u bitmap=%d captured_bitmap=%d request=%x phase=%u target=%d",mode,unsigned(request.layout),load<uint8_t>(base+0x234209),readingBitmap,readingRequested,unsigned(segment.phase),motionReq.target);
                if(segment.phase==einktransition::Phase::Normal&&!segment.regionOverlay&&
                   ((request.layout==einkwave::IndexLayout::PairY4&&(mode==2||mode==8))||lockReading)){
                    motionDecide(ctl,mode==2,lockReading);
                    if(motionMax>0){request.cpuStartMap=smapHost.data();request.startMapBytes=smapHost.size();
                        request.maxDelay=uint32_t(motionMax);request.totalFrames+=uint32_t(motionMax);}
                }else motionMax=0;
                // 融合：翻页动画的分阶段揭示改由 GPU 波形层原生 bandDirection 驱动。
                // motionMax>0 表示这一帧已经被「动画」里的某个过渡接管（全刷扫过 / 擦除 /
                // 扫描线 / 百叶窗）。两者都靠波形时序表达，叠加会互相覆盖：分带的几何
                // 会把扫过方向盖成斜向。所以显式动画优先，融合让位。
                auto fd=einkfusion::drive(int(request.activeFrames),ctl);
                if(fd.enabled&&motionMax==0&&!blank&&!segment.regionOverlay&&
                   segment.phase==einktransition::Phase::Normal){
                    // 分带必须「迟启动 + 总帧数等量延长」：总帧数 = active + tail + 1，
                    // 并在 frame 0 的原厂调用之后把 ctl 的 remaining/total 同时加 tail，
                    // 让每条带都从 LUT 第 0 帧走满 activeFrames 帧。只错峰不延长会把
                    // 后启动的带截尾，像素停在中间灰阶 → 旧页浅灰残影（刷新不干净）。
                    motionMax=0;
                    request.cpuStartMap=nullptr;request.gpuStartMap=0;request.startMapBytes=0;
                    request.maxDelay=0;
                    request.totalFrames=request.activeFrames+fd.tail+1;
                    request.bandDirection=fd.direction;request.bandFrames=fd.frames;
                    request.bandRows=fd.rows;request.bandTail=fd.tail;request.bandStyle=fd.style;
                    fusionTail=fd.tail;
                    LOG("FUSION_DRIVE wave=%llu dir=%u style=%u rows=%u tail=%u frames=%u active=%u total=%u",
                        (unsigned long long)waveId,fd.direction,fd.style,fd.rows,fd.tail,fd.frames,
                        request.activeFrames,request.totalFrames);
                }else fusionTail=0;
                request.wave=waveId;request.gpuIndexValidated=true;
                gpuBeginWave(request);waveformFrames=int(request.totalFrames);
            }catch(const std::exception&e){telemetryAdd(T_ERRORS);LOG("ROLL_BEGIN_FALLBACK reason=%s",e.what());
                motionMax=0;bridgeResidentPixels(ctl);gpuCancelRollingWave();rollingProducer=false;gpuFailed=true;}
        }
        if(fullPixelWave&&!rollingProducer)bridgeResidentPixels(ctl);
    }
    if(rollingProducer&&uint32_t(frame)>=rollingWindowEnd){
        try{rollingWindowPool=gpuRenderWaveWindow(uint32_t(frame),std::min(frame==0?firstWindowFrames():8u,uint32_t(waveformFrames-frame)));
            rollingWindowEnd=uint32_t(frame)+pools[rollingWindowPool].count;producerPool=rollingWindowPool;
            try{readingCaptureWindow(rollingWindowPool);}catch(const std::exception&e){LOG("READING_CAPTURE_ERROR reason=%s",e.what());readingDone=true;}
        }catch(const std::exception&e){telemetryAdd(T_ERRORS);LOG("ROLL_WINDOW_FALLBACK reason=%s",e.what());
            motionAbort(ctl,frame);
            // 撤销分带延长，避免原厂用扩展后的帧号越界访问 LUT。
            einkfusion::restoreTail();fusionTail=0;control=einktransition::snapshot(ctl);segment=einktransition::segment(control);
            bridgeResidentPixels(ctl);gpuCancelRollingWave();rollingProducer=false;producerPool=-1;gpuFailed=true;}
    }
    if(frame==0&&configuredMode==1)eligible=gpuWaveEligible(ctl);
    if(frame==0&&!rollingProducer){if(eligible)motionDecide(ctl);else motionMax=0;}
    if(frame==0)VLOG("CANDIDATE wave=%llu mode=%d special=%d transition=%d blank=%d remain=%d total=%d eligible=%d",
        (unsigned long long)waveId,field<uint8_t>(ctl,0x15c),field<int>(ctl,0x270),field<int>(ctl,0x278),field<int>(ctl,0x280),remaining,total,eligible);
    int result;
    if(rollingProducer||(eligible&&producerPool>=0))result=reinterpret_cast<int(*)(void*,void*)>(base+0x97668)(field<void*>(entry,0),ctl);
    else result=reinterpret_cast<int(*)(void*,void*)>(base+0x95dc4)(entry,ctl);
    // Observe the real stock update before any optional motion extension. Its
    // counters and terminal return stay authoritative for each GPU segment.
    if(rollingProducer){
        bool valid=einktransition::verifyStep(control,einktransition::snapshot(ctl),result);
        if(!valid){telemetryAdd(T_ERRORS);LOG("CONTROL_VERIFY_FAIL wave=%llu phase=%u frame=%d result=%d",
            (unsigned long long)waveId,unsigned(segment.phase),frame,result);}
        else if(segment.terminal())VLOG("CONTROL_VERIFY_END wave=%llu phase=%u frames=%u pass=1",
            (unsigned long long)waveId,unsigned(segment.phase),segment.total+1);
    }
    if(frame==0&&motionMax>0){if((rollingProducer||eligible)&&producerPool>=0)motionExtend(ctl);else motionMax=0;}
    // 分带延长：必须在本帧的原厂调用之后做，帧号 = total - remaining 保持不变，
    // 下一帧起循环会多跑 fusionTail 帧，每条带才来得及走完 LUT 尾部收敛帧。
    if(frame==0&&fusionTail>0){
        if((rollingProducer||eligible)&&producerPool>=0)einkfusion::extendTail(ctl,fusionTail);
        else fusionTail=0;
    }
    // Publish the frame before the stock code stores the ring tail (plain store).
    std::atomic_thread_fence(std::memory_order_release);
    uint64_t end=now();
    int pool=(rollingProducer||eligible)&&producerPool>=0&&frame<waveformFrames?producerPool:-1;
    {std::lock_guard<std::mutex>l(ringMutex);ringRecords[slot]={waveId,end,modeStart,frame,!result,pool};if(pool>=0)++pools[pool].pending;}
    if(rollingProducer&&(uint32_t(frame+1)==rollingWindowEnd||!result)){
        gpuReleaseWaveWindow(rollingWindowPool);
        if(!result&&uint32_t(frame+1)!=rollingWindowEnd)gpuCancelRollingWave();
    }
    if(rollingProducer&&segment.terminal())rollingProducer=false;
    if(motionExtended&&!result)motionRestore();
    if(einkfusion::tailActive()&&!result)einkfusion::restoreTail();
    VLOG("GEN wave=%llu f=%d slot=%d begin=%llu ready=%llu remain=%d total=%d mode=%d special=%d result=%d",
        (unsigned long long)waveId,frame,slot,(unsigned long long)start,(unsigned long long)end,
        remaining,total,field<uint8_t>(ctl,0x15c),field<int>(ctl,0x270),result);return result;
}
static void hookConsume(int slot){
    // Pairs with the producer's release: the ring index was read without a lock.
    std::atomic_thread_fence(std::memory_order_acquire);
    uint64_t start=now();
    {std::lock_guard<std::mutex>l(ringMutex);consuming=ringRecords[slot];
        if(consuming.pool>=0)consumingPoolFrame=gpuWavePoolFrame(consuming.pool,consuming.wave,uint32_t(consuming.frame));}
    auto*surface=reinterpret_cast<void*(*)()>(base+0x94714)();
    auto*state=load<uint8_t*>(base+0x234300);auto**surfaces=reinterpret_cast<uint8_t**>(state+0x28);
    if(!originalFb[0])for(int i=0;i<2;i++)originalFb[i]=field<uint32_t>(surfaces[i],0x18);
    if(consuming.pool>=0&&!scanFallback){
        uint32_t fb=pools[consuming.pool].frames[consumingPoolFrame]->fb;
        std::memcpy(static_cast<uint8_t*>(surface)+0x18,&fb,4);
    }else{
        for(int i=0;i<2;i++)std::memcpy(surfaces[i]+0x18,&originalFb[i],4);
        auto*dst=field<uint32_t*>(surface,0x10);
        if(consuming.pool>=0){auto snapshot=pools[consuming.pool].frames[consumingPoolFrame]->snapshot();std::memcpy(dst,snapshot.data(),snapshot.size());}
        else{auto*src=load<uint32_t*>(load<uintptr_t>(base+0x9ffb0)+slot*16);
            for(int i=0;i<384*725;i++){uint32_t v=src[i];dst[i]=(v&0xff00ff00)|((v&255)<<16)|((v>>16)&255);}}
    }
    VLOG("COPY wave=%llu f=%d begin=%llu end=%llu queued=%llu",(unsigned long long)consuming.wave,consuming.frame,
        (unsigned long long)start,(unsigned long long)now(),(unsigned long long)consuming.ready);
}
static void hookFlip(){
    uint64_t submit=now();auto*state=load<uint8_t*>(base+0x234300);auto**surfaces=reinterpret_cast<uint8_t**>(state+0x28);
    auto*back=surfaces[field<int>(state,0x38)];
    auto flip=reinterpret_cast<void*(*)(void*)>(base+0x94c84);auto*next=flip(state);
    if(!next&&consuming.pool>=0){
        LOG("SCAN_FALLBACK wave=%llu f=%d errno=%d",(unsigned long long)consuming.wave,consuming.frame,errno);
        telemetryAdd(T_ERRORS);
        gpuFailed=true;scanFallback=true;
        for(int i=0;i<2;i++)std::memcpy(surfaces[i]+0x18,&originalFb[i],4);
        auto snapshot=pools[consuming.pool].frames[consumingPoolFrame]->snapshot();std::memcpy(field<void*>(back,0x10),snapshot.data(),snapshot.size());
        next=flip(state);
    }
    if(!next){LOG("FATAL_FLIP wave=%llu errno=%d",(unsigned long long)consuming.wave,errno);
        reinterpret_cast<void(*)(int)>(base+0x946fc)(1);_exit(1);}
    std::memcpy(reinterpret_cast<void*>(base+0x2342f8),&next,sizeof(next));uint64_t done=now();
    if(consuming.pool>=0&&!scanFallback){telemetryAdd(T_SCAN);telemetrySet(T_SCAN_NS,done);}
    {std::lock_guard<std::mutex>l(ringMutex);activePool=scanFallback?-1:consuming.pool;if(consuming.pool>=0)--pools[consuming.pool].pending;}
    poolCv.notify_one();
    VLOG("FLIP wave=%llu f=%d last=%d submit=%llu done=%llu input=%llu ready=%llu pool=%d",
        (unsigned long long)consuming.wave,consuming.frame,consuming.last,(unsigned long long)submit,
        (unsigned long long)done,(unsigned long long)consuming.input,(unsigned long long)consuming.ready,consuming.pool);
}
