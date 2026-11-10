#pragma once
// All image-sized pixel transforms remain on GPU. Only the 256-byte tone
// parameter table is built by the original service function on the CPU.
#include <cstdlib>
#include <cmath>
#include <algorithm>
#include "service_bluenoise.hpp"
#include "service_algorithm_policy.hpp"
#include "service_binary_shader.hpp"
static GLuint preprocessProgram,rgbaBuffer,toneBuffer,noiseBuffer,levelBuffers[2];
static GLint uAlgorithmLoc=-1,uVideoToneLoc=-1,uCountLoc=-1,uWidthLoc=-1,uGammaLoc=-1,uBlackLoc=-1,uHystLoc=-1;
// Video waves (mode bit 0x400) used the stock CPU DitherColorVideoStyle: error diffusion
// at threshold 127 that crushes shadows to solid black and reshuffles ~5-9% of pixels per
// frame in motion. Here they get blue noise + a highlight-preserving shadow lift with a
// black point + hysteresis on the previous black/white state (see 墨水屏刷新分析/视频抖动/).
// Tunable live: debug.eink.video.dither = blue (default) | bayer | stock
// (exact error diffusion on GPU when prepareGrayLevels admits the wave;
// unsupported waves retain the original CPU consumer),
// debug.eink.video.gamma (0.6), .black (20, on the 0..255 tone-mapped value), .hyst (12).
static float propFloat(const char*key,float fallback){
    char v[PROP_VALUE_MAX]={};if(__system_property_get(key,v)<=0)return fallback;
    char*end=nullptr;float f=std::strtof(v,&end);return end!=v&&*end=='\0'&&std::isfinite(f)?f:fallback;
}
static unsigned videoDither(){
    char v[PROP_VALUE_MAX]={};int n=__system_property_get("debug.eink.video.dither",v);
    static char last[PROP_VALUE_MAX]={1};
    if(std::strcmp(last,v)){std::strcpy(last,v);LOG("VIDEO_DITHER value='%s' n=%d",v,n);}
    return !std::strcmp(v,"stock")?0u:!std::strcmp(v,"bayer")?2u:1u;
}
// 「改用原厂显示算法」总开关（后端下发 debug.eink.alg.stock=1）。
// 优先级高于 GPU 后端的一切算法接管：A2/DU 二值候选与灰阶（含 stock16）全部让位，
// 像素量化走原厂 CPU 路径；全刷复用、取帧与触摸响应不受影响。
static bool stockOverride(){
    char v[PROP_VALUE_MAX]={};__system_property_get("debug.eink.alg.stock",v);
    return v[0]=='1'&&v[1]=='\0';
}
struct PixelPolicy {
    einkalg::Group group=einkalg::Group::None;
    einkalg::Algorithm algorithm=einkalg::Algorithm::Default;
    einkalg::Binary binary{};
};
// 每波只读一次选择属性；候选 BW 路径和 stockVideo 回退使用同一个快照。
static PixelPolicy pixelPolicy;
// 快速/极速模式选了「原厂」：本波像素量化交回原厂 CPU 路径（GPU 二值 shader 没有原厂实现）。
// 只影响该模式；清晰模式的 GC16 波形（全刷扫过）不受影响。
static bool modeStock=false,modeStockLogged=false;
// 外部模式号 → 这个模式是否选了「原厂」。必须按 **外部模式** 判断：
// Group::None（自动选 A2、过渡、空白等五个命名组之外的波）同样会被自研 shader 接管，
// 只看命名组会漏掉它们，浅灰底上照样出方格。
static bool modeStockFor(int mode){
    if(mode&0x400)return false;                       // 视频场景保持自有语义
    unsigned ext=unsigned(mode)&255u;
    // ext==1 是「A2 局部刷新」入口（切页、滚动主要走它），不归属五个命名模式。
    // 滑动时系统会自动切到「均衡模式」（阅读），实测这条路径就落在 ext==1，
    // 所以它跟随阅读模式的选择，不能错绑到快速。
    const char*key=(ext==1||ext==3||ext==4||ext==5)?"debug.eink.alg.reading":
        ext==6?"debug.eink.alg.fast":ext==9?"debug.eink.alg.extreme":nullptr;
    if(!key)return false;
    char v[PROP_VALUE_MAX]={};__system_property_get(key,v);
    return std::strcmp(v,"stock")==0;
}
static PixelPolicy readPixelPolicy(void*ctl,int mode,int selected){
    PixelPolicy p;
    p.group=einkalg::group({unsigned(mode),unsigned(selected),field<uint8_t>(ctl,0x15c),
        field<int>(ctl,0x270)!=0,field<int>(ctl,0x278)!=0,field<int>(ctl,0x280)!=0});
    char alg[PROP_VALUE_MAX]={},hyst[PROP_VALUE_MAX]={};
    if(const char*key=einkalg::algorithmProperty(p.group))__system_property_get(key,alg);
    if(const char*key=einkalg::hysteresisProperty(p.group))__system_property_get(key,hyst);
    p.algorithm=einkalg::parseAlgorithm(p.group,alg);
    const bool video=(unsigned(selected)&255u)==8u&&(mode&0x400);
    const unsigned old=video?videoDither():0u;
    p.binary=einkalg::binary(video,p.algorithm,einkalg::parseHysteresis(hyst),
        old==0?einkalg::Algorithm::Stock:old==2?einkalg::Algorithm::Bayer:einkalg::Algorithm::Blue);
    return p;
}
// Debug-gated entries for the real four-level DU4 and REGAL-family waveform
// experiments. Each property accepts exactly "1" so stray values cannot
// silently reconfigure a production wave class.
static bool du4Enabled(){char v[PROP_VALUE_MAX]={};__system_property_get("debug.eink.wave.du4",v);return v[0]=='1'&&v[1]=='\0';}
static bool gl16Enabled(){char v[PROP_VALUE_MAX]={};__system_property_get("debug.eink.wave.gl16",v);return v[0]=='1'&&v[1]=='\0';}
static bool glr16Enabled(){char v[PROP_VALUE_MAX]={};__system_property_get("debug.eink.wave.glr16",v);return v[0]=='1'&&v[1]=='\0';}
static bool cfaEnabled(){char v[PROP_VALUE_MAX]={};__system_property_get("debug.eink.wave.cfa",v);return v[0]=='1'&&v[1]=='\0';}
// Old/new level states ping-pong: committing a wave swaps the index instead of
// a GPU buffer copy. The PowerVR driver did not order glCopyBufferSubData before
// the next compute read, so the next wave mixed against a stale old state and
// the panel stayed one image behind.
static int oldIndex=0;
static GLuint oldLevels(){return levelBuffers[oldIndex];}
static GLuint newLevels(){return levelBuffers[oldIndex^1];}
static bool oldGpuValid=false,cpuOldDirty=false,preValidated=false;
static void*pixelCtl;
static void*pixelRgba;
static constexpr size_t PIXELS=1440*720;
// Debug only: CPU shadow of the expected old-level state across GPU waves.
static Bytes shadowOld,pendingNext;static bool shadowValid=false;
static void finishPixels(){
    auto create=reinterpret_cast<PFNEGLCREATESYNCKHRPROC>(eglGetProcAddress("eglCreateSyncKHR"));
    auto wait=reinterpret_cast<PFNEGLCLIENTWAITSYNCKHRPROC>(eglGetProcAddress("eglClientWaitSyncKHR"));
    auto destroy=reinterpret_cast<PFNEGLDESTROYSYNCKHRPROC>(eglGetProcAddress("eglDestroySyncKHR"));
    require(create&&wait&&destroy,"pixel fence API");auto f=create(gpuDisplay,EGL_SYNC_FENCE_KHR,nullptr);
    require(f!=EGL_NO_SYNC_KHR,"pixel fence create");glFlush();auto r=wait(gpuDisplay,f,0,1000000000ULL);destroy(gpuDisplay,f);
    require(r==EGL_CONDITION_SATISFIED_KHR,"pixel GPU timeout");Gpu::check("pixel completion");
}
static Bytes readGpu(GLuint buffer){
    glBindBuffer(GL_SHADER_STORAGE_BUFFER,buffer);auto*p=glMapBufferRange(GL_SHADER_STORAGE_BUFFER,0,PIXELS,GL_MAP_READ_BIT);
    require(p,"state map");Bytes result(PIXELS);std::memcpy(result.data(),p,PIXELS);
    require(glUnmapBuffer(GL_SHADER_STORAGE_BUFFER),"state unmap");telemetryBytes(T_READBACK,PIXELS);return result;
}
static void syncCpuOld(void*ctl){
    syncCpuBound(ctl);
    if(!cpuOldDirty)return;
    finishPixels();auto bytes=readGpu(oldLevels());std::memcpy(field<void*>(ctl,0x60),bytes.data(),PIXELS);
    cpuOldDirty=false;VLOG("STATE_SYNC wave=%llu bytes=%zu",(unsigned long long)waveId,PIXELS);
}
static void setupPixels(){
    if(preprocessProgram)return;
    const char*source=einkalg::binaryShader();
    GLuint s=compile(GL_COMPUTE_SHADER,source);preprocessProgram=glCreateProgram();glAttachShader(preprocessProgram,s);glLinkProgram(preprocessProgram);
    GLint ok=0;glGetProgramiv(preprocessProgram,GL_LINK_STATUS,&ok);require(ok,"pixel program link");glDeleteShader(s);
    uAlgorithmLoc=glGetUniformLocation(preprocessProgram,"uAlgorithm");uVideoToneLoc=glGetUniformLocation(preprocessProgram,"uVideoTone");
    uCountLoc=glGetUniformLocation(preprocessProgram,"count");uWidthLoc=glGetUniformLocation(preprocessProgram,"width");
    uGammaLoc=glGetUniformLocation(preprocessProgram,"uGamma0");
    uBlackLoc=glGetUniformLocation(preprocessProgram,"uBlack");uHystLoc=glGetUniformLocation(preprocessProgram,"uHyst");
    require(uAlgorithmLoc>=0&&uVideoToneLoc>=0&&uCountLoc>=0&&uWidthLoc>=0&&uGammaLoc>=0&&uBlackLoc>=0&&uHystLoc>=0,"binary uniforms");
    glGenBuffers(1,&rgbaBuffer);glGenBuffers(1,&toneBuffer);glGenBuffers(1,&noiseBuffer);glGenBuffers(2,levelBuffers);
    {uint32_t noise[4096];for(int i=0;i<4096;i++)noise[i]=kBlueNoise64[i];
     glBindBuffer(GL_SHADER_STORAGE_BUFFER,noiseBuffer);glBufferData(GL_SHADER_STORAGE_BUFFER,sizeof(noise),noise,GL_STATIC_DRAW);}
    for(GLuint b:levelBuffers){glBindBuffer(GL_SHADER_STORAGE_BUFFER,b);glBufferData(GL_SHADER_STORAGE_BUFFER,PIXELS,nullptr,GL_DYNAMIC_COPY);}
    glBindBuffer(GL_SHADER_STORAGE_BUFFER,mixBuffer);glBufferData(GL_SHADER_STORAGE_BUFFER,PIXELS,nullptr,GL_DYNAMIC_COPY);
}
// GPU-candidate wave whose image came from the CPU path (bitmap mode, fallback).
static void grayFromRgba(void*rgba){
    upload(rgbaBuffer,rgba,PIXELS*4,3);
    telemetryBytes(T_UPLOAD,PIXELS*4);
    telemetryAdd(T_INPUT_CPU);
    VLOG("CPU_RGBA_GPU_INPUT wave=%llu bytes=%zu",(unsigned long long)waveId,PIXELS*4);
    glBindBufferBase(GL_SHADER_STORAGE_BUFFER,7,grayBuffer);glUseProgram(rgbaGrayProgram);
    glDispatchCompute((PIXELS/4+127)/128,1,1);glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
}
// Debug-gated CFA color-front gray (stock 0x9a718 never had a caller). Same
// upload-once contract as grayFromRgba; only the gray kernel differs.
static GLuint cfaProgram;
static void cfaGray(void*rgba){
    if(!cfaProgram)cfaProgram=EinkImageOps::link(EinkImageOps::cfaShader());
    upload(rgbaBuffer,rgba,PIXELS*4,3);
    telemetryBytes(T_UPLOAD,PIXELS*4);
    VLOG("CFA_UPLOAD wave=%llu bytes=%zu",(unsigned long long)waveId,PIXELS*4);
    glBindBufferBase(GL_SHADER_STORAGE_BUFFER,3,rgbaBuffer);glBindBufferBase(GL_SHADER_STORAGE_BUFFER,7,grayBuffer);
    glUseProgram(cfaProgram);
    EinkImageOps::uniform(cfaProgram,"width",1440u);EinkImageOps::uniform(cfaProgram,"count",unsigned(PIXELS));
    glDispatchCompute((PIXELS/4+127)/128,1,1);
    glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT|GL_BUFFER_UPDATE_BARRIER_BIT);
}
// CPU wave fed by the GPU front end: hand the stock code its Y8 image.
static int supplyCpuGray(void*rgba,void*ctl,int mode=0){
    syncCpuBound(ctl);
    if(mode&0x400)syncCpuColorHistory(true);
    if(!gpuSource)return 0;
    try{
        if(field<int>(ctl,0xbc)!=1440||field<int>(ctl,0xc0)!=720)throw std::runtime_error("unexpected size");
        // The stock video dither also reads RGBA (x27), not just ctl+0x268.
        // A special/transition wave must have valid CPU work even after GPU input.
        if(mode&0x400)materialiseGpuWork(grayInput,rgba);
        finishPixels();auto gray=readGpu(grayBuffer);std::memcpy(field<void*>(ctl,0x268),gray.data(),PIXELS);
        VLOG("CPU_GRAY wave=%llu",(unsigned long long)waveId);return 2;
    }catch(const std::exception&e){
        // Materialise the landscape RGBA the stock grayscale loop expects.
        telemetryAdd(T_ERRORS);LOG("CPU_GRAY_FALLBACK wave=%llu reason=%s",(unsigned long long)waveId,e.what());
        cpuInputWork(grayInput,rgba);prevArrayStale=false;return 0;
    }
}
static void recoverCpuPixels(void*ctl){
    try{
        syncCpuOld(ctl);
        if(fullPixelWave){
            finishPixels();auto next=readGpu(newLevels()),mixed=readGpu(mixBuffer);
            std::memcpy(field<void*>(ctl,0x58),next.data(),PIXELS);std::memcpy(field<void*>(ctl,0x40),mixed.data(),PIXELS);
        }
        fullPixelWave=false;oldGpuValid=false;
    }catch(const std::exception&e){LOG("FATAL_STATE reason=%s",e.what());reinterpret_cast<void(*)(int)>(base+0x946fc)(1);_exit(1);}
}
// Integration contract for the main mix hooks. A host callback may arm its
// waveform/mix implementation here BEFORE the 1MB CPU bridge executes. Return
// true only after consuming these buffers and taking responsibility for state
// commit and all stock branches that read ctl+0x58. The postprocess return 2
// requires the main trampoline to skip only quantization (jump to 0x995d4).
// Default nullptr keeps stock paths. DO NOT arm on an older return-1-only patch.
static bool (*nonA2Preprocess)(void*ctl,int mode,int selected)=nullptr;
struct GrayLevelView {
    GLuint old=0,next=0,mixed=0,gray=0;unsigned width=1440,height=720;
    uint64_t wave=0;bool ready=false;
};
static GrayLevelView preparedGray;
static GLuint grayLevelsProgram;
static GLuint stock16InitProgram,stock16PrefixProgram,stock16Ring,stock16Gray,stock16Proof;
static void prepareStock16(){
    if(stock16InitProgram)return;
    stock16InitProgram=EinkImageOps::link(EinkImageOps::stock16RingInitShader());
    auto source=EinkImageOps::stock16PrefixCacheShader(1440);
    stock16PrefixProgram=EinkImageOps::link(source.c_str());
    glGenBuffers(1,&stock16Ring);glBindBuffer(GL_SHADER_STORAGE_BUFFER,stock16Ring);
    glBufferData(GL_SHADER_STORAGE_BUFFER,1440*4*4,nullptr,GL_DYNAMIC_COPY);
    glGenBuffers(1,&stock16Gray);glBindBuffer(GL_SHADER_STORAGE_BUFFER,stock16Gray);
    glBufferData(GL_SHADER_STORAGE_BUFFER,PIXELS,nullptr,GL_DYNAMIC_COPY);
    glGenBuffers(1,&stock16Proof);glBindBuffer(GL_SHADER_STORAGE_BUFFER,stock16Proof);
    glBufferData(GL_SHADER_STORAGE_BUFFER,4,nullptr,GL_DYNAMIC_READ);
}
static inline GrayLevelView preparedGrayLevels(){return preparedGray;}
static inline void cancelPreparedGrayLevels(){preparedGray={};}
// Explicit CPU-consumer bridge for any mix/reset/transition branch the main
// hooks cannot cover. It must run BEFORE that branch dereferences ctl+0x58.
// Does not commit: CPU stock commit remains authoritative after this fallback.
static inline void bridgePreparedGrayLevels(void*ctl,bool includeMixed=false){
    require(preparedGray.ready&&preparedGray.wave==waveId&&ctl==pixelCtl,"gray bridge ownership");
    syncCpuOld(ctl);finishPixels();auto next=readGpu(newLevels());
    std::memcpy(field<void*>(ctl,0x58),next.data(),PIXELS);
    if(includeMixed){auto mixed=readGpu(mixBuffer);std::memcpy(field<void*>(ctl,0x40),mixed.data(),PIXELS);}
    preparedGray={};fullPixelWave=false;oldGpuValid=false;shadowValid=false;
    VLOG("GRAY_LEVELS_CPU_BRIDGE bytes=%zu mixed=%d",PIXELS,includeMixed);
}
// Exact stock16 uses the validated parallel function-prefix scan. Its domain
// counter returns only four control bytes; a violation retains the CPU fallback.
// The algorithm follows the stock-selected w25, not the external w26 request.
// DU/A2/REGAL selection and all transitions remain the main integrator's job.
static inline bool prepareGrayLevels(void*ctl,int mode,int selected,void*rgba){
    preparedGray={};
    if(stockOverride())return false;   // 原厂算法总开关：灰阶量化交回原厂 CPU 路径
    if(modeStock)return false;         // 本模式选了「原厂」：同样不接管
    if((!gpuSource&&(!rgba||configuredMode<2))||gpuFailed||field<int>(ctl,0xbc)!=1440||field<int>(ctl,0xc0)!=720||
       field<int>(ctl,0x280)!=0)return false;
    unsigned internal=field<uint8_t>(ctl,0x15c),algorithm=unsigned(selected)&255u;
    bool reset=field<int>(ctl,0x270)==1&&internal==0&&algorithm==0;
    bool stockVideo=internal==8&&algorithm==8&&(mode&0x400)&&pixelPolicy.binary.algorithm==einkalg::Algorithm::Stock;
    if(field<int>(ctl,0x270)!=0&&!reset)return false;
    EinkImageOps::Levels kind=EinkImageOps::Levels::Raw;
    bool dither16=(internal==2&&algorithm==2)||(internal==5&&algorithm==5&&(mode&0x100));
    // Debug-gated true waveforms: four-level DU4 (WBF7 quantization), GL16
    // (WBF3 table + stock16 kernel) and GLR16 (WBF4 REGAL table).
    bool du4Wave=du4Enabled()&&internal==1&&algorithm==1;
    bool gl16Wave=gl16Enabled()&&internal==5&&algorithm==5;
    bool glr16Wave=glr16Enabled()&&internal==5&&algorithm==5;
    bool stock16Wave=dither16||gl16Wave;
    if(reset||stockVideo)kind=EinkImageOps::Levels::Raw;
    else if(du4Wave)kind=EinkImageOps::Levels::Quaternary;
    else if(glr16Wave)kind=EinkImageOps::Levels::Tone;
    else if(stock16Wave)kind=EinkImageOps::Levels::Tone;
    else if(internal==5&&algorithm==5)kind=EinkImageOps::Levels::Tone;
    else if(internal==8&&algorithm==6)kind=EinkImageOps::Levels::Binary;
    else if((algorithm==3||algorithm==4||algorithm==7)&&internal==8)kind=EinkImageOps::Levels::Raw;
    else return false;
    auto*state=load<void*>(base+0x234300);setupGpu(field<int>(state,0x50));setupPixels();setupFront();
    // Bitmap/lockscreen callers provide landscape RGBA rather than a composer
    // target handle. Upload that existing source once; all subsequent image
    // operations, transition mixes and scans still run on GPU.
    if(!gpuSource)grayFromRgba(rgba);
    if(!grayLevelsProgram)grayLevelsProgram=EinkImageOps::link(EinkImageOps::levelsShader());
    if(!oldGpuValid){upload(oldLevels(),field<void*>(ctl,0x60),PIXELS,6);telemetryBytes(T_UPLOAD,PIXELS);oldGpuValid=true;}
    uint8_t tone[256];
    if(stockVideo){
        alignas(8) uint8_t dummyCtl[0x300]{};uint32_t w=16,h=16;
        std::memcpy(dummyCtl+0xbc,&w,4);std::memcpy(dummyCtl+0xc0,&h,4);
        for(unsigned i=0;i<256;i++)tone[i]=uint8_t(i);
        reinterpret_cast<void(*)(void*,void*)>(base+0x9977c)(tone,dummyCtl);
    }else if(kind==EinkImageOps::Levels::Tone)EinkImageOps::gldTone(load<uint32_t>(base+0x620cb2c),tone);
    else for(unsigned i=0;i<256;i++)tone[i]=uint8_t(i);
    std::memcpy(static_cast<uint8_t*>(ctl)+0x168,tone,sizeof(tone));upload(toneBuffer,tone,sizeof(tone),4);
    finishPixels(); // Preserve the observed driver upload ordering requirement.
    GLuint quantized=grayBuffer;
    if(stockVideo)quantized=prepareStockVideo(ctl,grayBuffer,toneBuffer,gpuSource?gpuWorkPixels(grayInput):rgbaBuffer);
    bool lockDither=load<uint8_t>(base+0x234209)!=0&&internal==5&&algorithm==5&&(mode&0x100)&&dither16;
    bool lockNearest=false;
    if(lockDither){
        char policy[PROP_VALUE_MAX]={};__system_property_get("debug.eink.lock.dither",policy);
        lockNearest=std::strcmp(policy,"nearest")==0;
        VLOG("LOCK_DITHER wave=%llu algorithm=%s mode=%x internal=%u waveform_unchanged=1",(unsigned long long)waveId,lockNearest?"nearest":"stock",mode,internal);
    }
    const bool grayGroup=pixelPolicy.group==einkalg::Group::Clear||pixelPolicy.group==einkalg::Group::Reading;
    const auto grayPolicy=einkalg::gray(grayGroup?pixelPolicy.algorithm:einkalg::Algorithm::Default,dither16,lockNearest);
    if(grayPolicy!=einkalg::Gray::Legacy){
        dither16=grayPolicy==einkalg::Gray::Stock16;
        if(grayPolicy==einkalg::Gray::Nearest16)kind=EinkImageOps::Levels::Nearest16;
        if(grayPolicy==einkalg::Gray::Bayer16)kind=EinkImageOps::Levels::Ordered16;
    }
    if(dither16){
        prepareStock16();
        EinkImageOps::dispatchStock16Prefix(stock16InitProgram,stock16PrefixProgram,grayBuffer,toneBuffer,
                                          stock16Ring,stock16Gray,stock16Proof,1440,720,PIXELS,8,1440);
        finishPixels();glBindBuffer(GL_SHADER_STORAGE_BUFFER,stock16Proof);
        auto*p=static_cast<const uint32_t*>(glMapBufferRange(GL_SHADER_STORAGE_BUFFER,0,4,GL_MAP_READ_BIT));
        require(p,"stock16 domain map");uint32_t violations=*p;
        require(glUnmapBuffer(GL_SHADER_STORAGE_BUFFER),"stock16 domain unmap");
        require(!violations,"stock16 prefix domain violation");quantized=stock16Gray;kind=EinkImageOps::Levels::Raw;
        VLOG("STOCK16_PREFIX wave=%llu pixels=%zu dispatches=91 domain=%u",(unsigned long long)waveId,PIXELS,violations);
    }
    EinkImageOps::dispatchLevels(grayLevelsProgram,quantized,toneBuffer,oldLevels(),newLevels(),mixBuffer,
                                unsigned(PIXELS),1440,kind);
    preparedGray={oldLevels(),newLevels(),mixBuffer,grayBuffer,1440,720,waveId,true};pixelCtl=ctl;
    VLOG("GRAY_LEVELS wave=%llu kind=%u cpu_gray_bridge=0",(unsigned long long)waveId,unsigned(kind));return true;
}
// Host must invoke only at the original state-commit point after its successful
// mix/scan path. Never call both this and hookCommit for the same image.
static inline void commitPreparedGrayLevels(void*ctl){
    require(preparedGray.ready&&preparedGray.wave==waveId&&ctl==pixelCtl,"gray commit ownership");
    oldIndex^=1;cpuOldDirty=true;oldGpuValid=true;shadowValid=false;preparedGray={};
}
// 诊断直通：debug.eink.bypass=1 时墨控完全不介入这一波（等同原厂库），
// 用来判断某个现象到底来自补丁库本身还是我们的钩子。
static bool bypassAll(){char v[PROP_VALUE_MAX]={};__system_property_get("debug.eink.bypass",v);
    return v[0]=='1'&&v[1]=='\0';}
static int hookPreprocess(void*rgba,void*ctl,int mode,int selected){
    fullPixelWave=false;pixelCtl=ctl;pixelRgba=rgba;preparedGray={};
    // 本波是否整条交回原厂 CPU：必须在空白帧分支之前判定，
    // 否则空白/过渡帧仍由 GPU 生成波形索引，物理屏上照样留下规则格子。
    modeStock=modeStockFor(mode);
    if(modeStock!=modeStockLogged){modeStockLogged=modeStock;
        LOG("MODE_STOCK wave=%llu mode=%d stock=%d",(unsigned long long)waveId,mode,modeStock?1:0);}
    {static int seen[16]={};static int seenCount=0;bool known=false;
     for(int i=0;i<seenCount;i++)if(seen[i]==mode)known=true;
     if(!known&&seenCount<16){seen[seenCount++]=mode;LOG("MODE_SEEN %d stock=%d",mode,modeStock?1:0);}}
    if(bypassAll())return 0;
    if(configuredMode>=2&&!gpuFailed&&!modeStock&&!stockOverride()&&field<int>(ctl,0xbc)==1440&&field<int>(ctl,0xc0)==720&&
       field<int>(ctl,0x280)!=0&&field<int>(ctl,0x270)==0&&field<int>(ctl,0x278)==0&&
       field<uint32_t>(ctl,0x160)<einkwave::MaxLiveFrames){
        // Stock blank scan ignores image levels and never commits them. Retain
        // the resident history and skip unused image preprocessing/mixing.
        fullPixelWave=true;VLOG("GPU_BLANK_PRE wave=%llu",(unsigned long long)waveId);return 1;
    }
    if(!modeStock&&!stockOverride()&&frontReady&&!gpuSource&&!gpuFailed){
        try{runFront();gpuSource=true;grayInput=frontRaw;}
        catch(const std::exception&e){telemetryAdd(T_ERRORS);LOG("PRE_FRONT_FALLBACK reason=%s",e.what());
            cpuInputWork(frontRaw,rgba);gpuFailed=true;frontReady=false;}
    }
    pixelPolicy=readPixelPolicy(ctl,mode,selected);
    const auto binary=pixelPolicy.binary;
    const bool video=binary.videoTone;
    // DU4 takeover moves the DU wave class to the real four-level path below
    // (hookMixRegal integrator); the binary A2 candidate must not grab it.
    bool du4Takeover=du4Enabled()&&field<uint8_t>(ctl,0x15c)==1&&(unsigned(selected)&255u)==1u;
    bool candidate=configuredMode>=2&&!gpuFailed&&!stockOverride()&&!modeStock&&field<int>(ctl,0xbc)==1440&&field<int>(ctl,0xc0)==720&&
        ((field<uint8_t>(ctl,0x15c)==8&&(unsigned(selected)&255u)==8u)||
         (field<uint8_t>(ctl,0x15c)==1&&(unsigned(selected)&255u)==1u&&!du4Takeover))&&
        field<int>(ctl,0x270)==0&&field<int>(ctl,0x280)==0&&
        field<int>(ctl,0x160)>0&&field<int>(ctl,0x160)<int(einkwave::MaxLiveFrames)&&
        (!(mode&0x400)||(video&&binary.algorithm!=einkalg::Algorithm::Stock))&&load<int>(base+0x4e46320)==0;
    if(!candidate){
        if(nonA2Preprocess&&!gpuFailed){
            try{if(nonA2Preprocess(ctl,mode,selected)){fullPixelWave=true;return 1;}}
            catch(const std::exception&e){telemetryAdd(T_ERRORS);LOG("GRAY_PREPROCESS_FALLBACK reason=%s",e.what());recoverCpuPixels(ctl);gpuFailed=true;}
            preparedGray={};
        }
        syncCpuOld(ctl);oldGpuValid=false;shadowValid=false;return supplyCpuGray(rgba,ctl,mode);
    }
    uint64_t begin=now();
    try{
        auto*state=load<void*>(base+0x234300);setupGpu(field<int>(state,0x50));setupPixels();setupFront();
        // Reuse the exact service tone-table builder, on only 256 dummy pixels.
        alignas(8) uint8_t tableCtl[0x300]{};uint32_t w=16,h=16;
        std::memcpy(tableCtl+0xbc,&w,4);std::memcpy(tableCtl+0xc0,&h,4);
        uint8_t tone[256];for(int i=0;i<256;i++)tone[i]=uint8_t(i);
        reinterpret_cast<void(*)(void*,void*)>(base+0x9977c)(tone,tableCtl);
        std::memcpy(static_cast<uint8_t*>(ctl)+0x168,tableCtl+0x168,256);
        if(!oldGpuValid){upload(oldLevels(),field<void*>(ctl,0x60),PIXELS,6);telemetryBytes(T_UPLOAD,PIXELS);oldGpuValid=true;
            if(EINK_VERIFY_ALL){auto*o=field<uint8_t*>(ctl,0x60);shadowOld.assign(o,o+PIXELS);shadowValid=true;LOG("DBG_OLD_UPLOAD wave=%llu",(unsigned long long)waveId);}}
        uint64_t uploadBegin=now();
        if(!gpuSource){if(cfaEnabled())cfaGray(rgba);else grayFromRgba(rgba);}
        upload(toneBuffer,tone,256,4);
        // Inputs must be resident before the dispatch; do not rely on driver upload ordering.
        finishPixels();uint64_t uploadEnd=now();
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER,0,mixBuffer);glBindBufferBase(GL_SHADER_STORAGE_BUFFER,7,grayBuffer);glBindBufferBase(GL_SHADER_STORAGE_BUFFER,5,newLevels());
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER,6,oldLevels());glBindBufferBase(GL_SHADER_STORAGE_BUFFER,8,noiseBuffer);
        glUseProgram(preprocessProgram);
        glUniform1ui(uAlgorithmLoc,binary.shaderAlgorithm());glUniform1ui(uVideoToneLoc,video?1u:0u);
        glUniform1ui(uCountLoc,unsigned(PIXELS));glUniform1ui(uWidthLoc,1440u);
        glUniform1f(uGammaLoc,video?std::clamp(propFloat("debug.eink.video.gamma",0.6f),0.05f,2.f):1.f);
        glUniform1f(uBlackLoc,video?std::clamp(propFloat("debug.eink.video.black",20.f),0.f,254.f):0.f);
        // 普通 on 固定 12/255；视频 on/default 保留既有 video.hyst（默认12）。
        // off 必须每波写零，防止上一波视频的 uniform 泄漏到普通模式。
        glUniform1f(uHystLoc,binary.hysteresis?(video?std::clamp(propFloat("debug.eink.video.hyst",12.f),0.f,64.f):12.f)/255.f:0.f);
        glDispatchCompute((PIXELS/4+127)/128,1,1);glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT|GL_BUFFER_UPDATE_BARRIER_BIT);
        fullPixelWave=true;
        preparedGray={oldLevels(),newLevels(),mixBuffer,grayBuffer,1440,720,waveId,true};
        VLOG("PIXEL_ALGORITHM wave=%llu group=%u algorithm=%u video=%d hyst=%d",(unsigned long long)waveId,
             unsigned(pixelPolicy.group),unsigned(binary.algorithm),video,binary.hysteresis);
        // The bit-exact self-check models the Bayer path only; video waves are not checked.
        // The bit-exact self-check models the luminance front end only; CFA
        // color-front gray intentionally differs, so skip while it is armed.
        if((!preValidated||EINK_VERIFY_ALL)&&binary.legacyBayerCheck()&&!cfaEnabled()){
            finishPixels();auto next=readGpu(newLevels()),mixed=readGpu(mixBuffer),old=readGpu(oldLevels());
            size_t levelMismatch=0,indexMismatch=0,grayMismatch=0;auto*input=static_cast<uint8_t*>(rgba);
            auto grayIn=readGpu(grayBuffer);Bytes expected(PIXELS);
            for(size_t i=0;i<PIXELS;i++){
                uint32_t x=i%1440,y=i/1440,xy=x^y;
                unsigned b=((x&1)<<4)|((xy&1)<<5)|((x&2)<<1)|((xy&2)<<2)|((x&4)>>2)|((xy&4)>>1);
                unsigned gray=grayIn[i];
                if(!gpuSource)grayMismatch+=gray!=unsigned((38*input[i*4]+75*input[i*4+1]+15*input[i*4+2])>>7);
                expected[i]=tone[gray]>(b*4+2)?30:0;levelMismatch+=expected[i]!=next[i];
            }
            for(size_t i=0;i<PIXELS;i+=2){
                indexMismatch+=mixed[i]!=uint8_t((old[i]/2<<4)|(old[i+1]/2));
                indexMismatch+=mixed[i+1]!=uint8_t((expected[i]/2<<4)|(expected[i+1]/2));
            }
            // Independent reference: the stock packer itself, (old, new, out, w, h).
            Bytes stockMix(PIXELS);size_t stockMismatch=0;
            reinterpret_cast<void(*)(const uint8_t*,const uint8_t*,uint8_t*,int,int)>(base+0x9a5a8)(old.data(),expected.data(),stockMix.data(),1440,720);
            for(size_t i=0;i<PIXELS;i++)stockMismatch+=stockMix[i]!=mixed[i];
            indexMismatch+=stockMismatch;
            levelMismatch+=grayMismatch;
            LOG("PRE_VERIFY wave=%llu pixels=%zu levels=%zu indices=%zu stock_mix=%zu source=%s",(unsigned long long)waveId,PIXELS,levelMismatch,indexMismatch,stockMismatch,gpuSource?"gpu":"cpu");
            if(EINK_VERIFY_ALL){
                size_t oldMismatch=0,changed=0,gpuChanged=0,bad=0;
                for(size_t i=0;i<PIXELS;i++){
                    if(shadowValid){oldMismatch+=old[i]!=shadowOld[i];changed+=expected[i]!=shadowOld[i];}
                    gpuChanged+=next[i]!=old[i];bad+=(old[i]!=0&&old[i]!=30);
                }
                LOG("DBG wave=%llu shadow=%d old_vs_shadow=%zu expected_changed=%zu gpu_changed=%zu old_nonbinary=%zu blank=%d",(unsigned long long)waveId,
                    shadowValid,oldMismatch,changed,gpuChanged,bad,field<int>(ctl,0x280));
                pendingNext=expected;
            }
            if(!EINK_VERIFY_ALL)require(!levelMismatch&&!indexMismatch,"pixel preprocessing mismatch");preValidated=true;
        }
        VLOG("PRE wave=%llu begin=%llu queued=%llu upload_begin=%llu upload_end=%llu blank=%d",(unsigned long long)waveId,
            (unsigned long long)begin,(unsigned long long)now(),(unsigned long long)uploadBegin,(unsigned long long)uploadEnd,field<int>(ctl,0x280));
        return 1;
    }catch(const std::exception&e){telemetryAdd(T_ERRORS);LOG("GPU_ERROR wave=%llu reason=%s",(unsigned long long)waveId,e.what());recoverCpuPixels(ctl);gpuFailed=true;return supplyCpuGray(rgba,ctl,mode);}
}
static int hookPostprocess(void*){return preparedGray.ready?2:fullPixelWave?1:0;}
static void*hookCommit(void*dst,const void*src,size_t n){
    if(!fullPixelWave)return std::memcpy(dst,src,n);
    require(n==PIXELS,"old-state size");
    if(preparedGray.ready){commitPreparedGrayLevels(pixelCtl);return dst;}
    oldIndex^=1;
    if(EINK_VERIFY_ALL&&shadowValid)shadowOld=pendingNext;
    cpuOldDirty=true;VLOG("GPU_COMMIT wave=%llu bytes=%zu",(unsigned long long)waveId,n);return dst;
}
