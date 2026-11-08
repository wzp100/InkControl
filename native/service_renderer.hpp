#pragma once
#include "service_shared.hpp"
#include <chrono>
#include <unordered_map>
#include "service_waveform.hpp"

struct Pool {
    std::vector<std::unique_ptr<Shared>>frames;
    int pending=0;uint64_t wave=0;
    // Rolling windows use absolute wave frame numbers in ringRecords. The scan
    // hook must subtract firstFrame, and validate count/wave, BEFORE dereferencing.
    uint32_t firstFrame=0,count=0;
    bool reserved=false;
};
static Pool pools[2];
static std::condition_variable poolCv;
static int activePool=-1,producerPool=-1;
static GLuint mixBuffer,lutBuffer,templateBuffer,smapBuffer,program;
struct WaveUniforms{GLint frameNumber=-1;GLint neutral=-1;GLint motion=-1;GLint activeFrames=-1;GLint indexLayout=-1;GLint bandDirection=-1;GLint bandFrames=-1;GLint bandRows=-1;GLint bandTail=-1;GLint bandStyle=-1;GLint maskRegion=-1;GLint overlay12=-1;GLint twoRegions=-1;GLint activeRect=-1;GLint secondRect=-1;};
static WaveUniforms waveUniforms;

static EGLDisplay gpuDisplay=EGL_NO_DISPLAY;
static EGLContext gpuContext=EGL_NO_CONTEXT;
static EGLSurface gpuSurface=EGL_NO_SURFACE;
static std::atomic<bool> gpuContextReady{false};
#ifndef EINK_COPY_SCAN
#define EINK_COPY_SCAN 0
#endif
// 1 = diagnostic: CPU copies GPU frames into the stock surfaces instead of direct scanout.
static std::atomic<bool> gpuFailed{false},scanFallback{EINK_COPY_SCAN!=0};
static bool validated=false;
static uint32_t originalFb[2]={0,0};
static void*producerCtl;
static bool eligible;
static int producerSlot;
static int waveformFrames;
static bool fullPixelWave=false;
static GLuint compile(GLenum stage,const char*source){
    GLuint s=glCreateShader(stage);glShaderSource(s,1,&source,nullptr);glCompileShader(s);GLint ok=0;glGetShaderiv(s,GL_COMPILE_STATUS,&ok);
    if(!ok){char log[2048];glGetShaderInfoLog(s,sizeof(log),nullptr,log);glDeleteShader(s);throw std::runtime_error(log);}return s;
}
static void setupGpu(int drm){
    if(gpuDisplay!=EGL_NO_DISPLAY)return;
    gpuDisplay=eglGetDisplay(EGL_DEFAULT_DISPLAY);require(eglInitialize(gpuDisplay,nullptr,nullptr),"eglInitialize");
    EGLint attrs[]={EGL_SURFACE_TYPE,EGL_PBUFFER_BIT,EGL_RENDERABLE_TYPE,EGL_OPENGL_ES3_BIT,EGL_RED_SIZE,8,EGL_GREEN_SIZE,8,EGL_BLUE_SIZE,8,EGL_ALPHA_SIZE,8,EGL_NONE};
    EGLConfig cfg;EGLint count=0;require(eglChooseConfig(gpuDisplay,attrs,&cfg,1,&count)&&count,"eglChooseConfig");
    EGLint ctx[]={EGL_CONTEXT_CLIENT_VERSION,3,EGL_NONE},surf[]={EGL_WIDTH,1,EGL_HEIGHT,1,EGL_NONE};
    gpuContext=eglCreateContext(gpuDisplay,cfg,EGL_NO_CONTEXT,ctx);gpuSurface=eglCreatePbufferSurface(gpuDisplay,cfg,surf);
    require(eglMakeCurrent(gpuDisplay,gpuSurface,gpuSurface,gpuContext),"eglMakeCurrent");
    const char*vertex="#version 310 es\nvoid main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=vec4(p*2.-1.,0.,1.);}";
    const char*fragment=einkwave::FrameShader;
    GLuint vs=compile(GL_VERTEX_SHADER,vertex),fs=compile(GL_FRAGMENT_SHADER,fragment);program=glCreateProgram();glAttachShader(program,vs);glAttachShader(program,fs);glLinkProgram(program);
    GLint ok=0;glGetProgramiv(program,GL_LINK_STATUS,&ok);require(ok,"link frame shader");glDeleteShader(vs);glDeleteShader(fs);
    waveUniforms.frameNumber=glGetUniformLocation(program,"frameNumber");waveUniforms.neutral=glGetUniformLocation(program,"neutral");waveUniforms.motion=glGetUniformLocation(program,"motion");waveUniforms.activeFrames=glGetUniformLocation(program,"activeFrames");waveUniforms.indexLayout=glGetUniformLocation(program,"indexLayout");waveUniforms.bandDirection=glGetUniformLocation(program,"bandDirection");waveUniforms.bandFrames=glGetUniformLocation(program,"bandFrames");waveUniforms.bandRows=glGetUniformLocation(program,"bandRows");waveUniforms.bandTail=glGetUniformLocation(program,"bandTail");waveUniforms.bandStyle=glGetUniformLocation(program,"bandStyle");waveUniforms.maskRegion=glGetUniformLocation(program,"maskRegion");waveUniforms.overlay12=glGetUniformLocation(program,"overlay12");waveUniforms.twoRegions=glGetUniformLocation(program,"twoRegions");waveUniforms.activeRect=glGetUniformLocation(program,"activeRect");waveUniforms.secondRect=glGetUniformLocation(program,"secondRect");
    glGenBuffers(1,&mixBuffer);glGenBuffers(1,&lutBuffer);glGenBuffers(1,&templateBuffer);glGenBuffers(1,&smapBuffer);
    // The frame shader declares SMap even when motion is off; with nothing bound at
    // binding 3 the Mali driver dropped the draw (every live-waveform byte mismatched).
    {uint32_t zero=0;glBindBuffer(GL_SHADER_STORAGE_BUFFER,smapBuffer);glBufferData(GL_SHADER_STORAGE_BUFFER,4,&zero,GL_STATIC_DRAW);}
    GLuint vao;glGenVertexArrays(1,&vao);glBindVertexArray(vao);glDisable(GL_DITHER);glDisable(GL_BLEND);
    LOG("GPU_INIT renderer=%s drm=%d",glGetString(GL_RENDERER),drm);
    gpuContextReady.store(true,std::memory_order_release);
}
static void upload(GLuint b,const void*p,size_t n,int binding){
    glBindBuffer(GL_SHADER_STORAGE_BUFFER,b);glBufferData(GL_SHADER_STORAGE_BUFFER,n,p,GL_STREAM_DRAW);glBindBufferBase(GL_SHADER_STORAGE_BUFFER,binding,b);
}
static void uploadFence(){
    auto create=reinterpret_cast<PFNEGLCREATESYNCKHRPROC>(eglGetProcAddress("eglCreateSyncKHR"));
    auto wait=reinterpret_cast<PFNEGLCLIENTWAITSYNCKHRPROC>(eglGetProcAddress("eglClientWaitSyncKHR"));
    auto destroy=reinterpret_cast<PFNEGLDESTROYSYNCKHRPROC>(eglGetProcAddress("eglDestroySyncKHR"));
    require(create&&wait&&destroy,"upload fence API");
    auto f=create(gpuDisplay,EGL_SYNC_FENCE_KHR,nullptr);require(f!=EGL_NO_SYNC_KHR,"upload fence");
    glFlush();auto r=wait(gpuDisplay,f,0,1000000000ULL);destroy(gpuDisplay,f);require(r==EGL_CONDITION_SATISFIED_KHR,"upload fence timeout");
}
static void renderWave(void*params){
    const bool cacheUniforms=useUniformCache();
    uint64_t begin=now();auto*state=load<void*>(base+0x234300);int drm=field<int>(state,0x50);
    setupGpu(drm);
    int chosen=-1;
    {std::unique_lock<std::mutex>l(ringMutex);
     bool free=poolCv.wait_for(l,std::chrono::seconds(1),[&](){for(int i=0;i<2;i++)if(pools[i].pending==0&&!pools[i].reserved&&i!=activePool){chosen=i;return true;}return false;});
     require(free,"no free complete-wave pool");}
    int active=field<int>(producerCtl,0x160);require(active>0&&active<=32,"unsupported waveform length");waveformFrames=active+1+motionMax;
    auto&pool=pools[chosen];
    while(int(pool.frames.size())<waveformFrames)pool.frames.push_back(std::make_unique<Shared>(gpuDisplay,384,725,
        AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT|AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE|AHARDWAREBUFFER_USAGE_COMPOSER_OVERLAY|AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN,drm));
    auto*mix=field<uint32_t*>(params,0);auto*lut=field<uint8_t*>(params,8);auto*templ=field<uint32_t*>(producerCtl,0x98);
    if(fullPixelWave)glBindBufferBase(GL_SHADER_STORAGE_BUFFER,0,mixBuffer);
    else upload(mixBuffer,mix,1440*720,0);
    upload(lutBuffer,lut,size_t(active)*65536,1);upload(templateBuffer,templ,384*725*4,2);
    if(motionMax>0)upload(smapBuffer,smapHost.data(),smapHost.size(),3);
    else glBindBufferBase(GL_SHADER_STORAGE_BUFFER,3,smapBuffer);
    uploadFence();
    glUseProgram(program);auto frameLoc=(cacheUniforms?waveUniforms.frameNumber:glGetUniformLocation(program,"frameNumber")),neutralLoc=(cacheUniforms?waveUniforms.neutral:glGetUniformLocation(program,"neutral"));
    glUniform1i((cacheUniforms?waveUniforms.motion:glGetUniformLocation(program,"motion")),motionMax>0);glUniform1ui((cacheUniforms?waveUniforms.activeFrames:glGetUniformLocation(program,"activeFrames")),active);
    glUniform1ui((cacheUniforms?waveUniforms.indexLayout:glGetUniformLocation(program,"indexLayout")),0);
    glUniform1ui((cacheUniforms?waveUniforms.bandDirection:glGetUniformLocation(program,"bandDirection")),0);
    glUniform1ui((cacheUniforms?waveUniforms.bandFrames:glGetUniformLocation(program,"bandFrames")),0);
    glUniform1ui((cacheUniforms?waveUniforms.bandRows:glGetUniformLocation(program,"bandRows")),48);
    glUniform1ui((cacheUniforms?waveUniforms.bandTail:glGetUniformLocation(program,"bandTail")),0);
    glUniform1ui((cacheUniforms?waveUniforms.bandStyle:glGetUniformLocation(program,"bandStyle")),0);
    glUniform1i((cacheUniforms?waveUniforms.maskRegion:glGetUniformLocation(program,"maskRegion")),0);
    glUniform1i((cacheUniforms?waveUniforms.overlay12:glGetUniformLocation(program,"overlay12")),0);
    glUniform1i((cacheUniforms?waveUniforms.twoRegions:glGetUniformLocation(program,"twoRegions")),0);
    for(int f=0;f<waveformFrames;f++){
        glBindFramebuffer(GL_FRAMEBUFFER,pool.frames[f]->fbo);glViewport(0,0,384,725);glUniform1ui(frameLoc,f);glUniform1i(neutralLoc,f==waveformFrames-1);
        glDrawArrays(GL_TRIANGLES,0,3);
    }
    auto create=reinterpret_cast<PFNEGLCREATESYNCKHRPROC>(eglGetProcAddress("eglCreateSyncKHR"));
    auto wait=reinterpret_cast<PFNEGLCLIENTWAITSYNCKHRPROC>(eglGetProcAddress("eglClientWaitSyncKHR"));
    auto destroy=reinterpret_cast<PFNEGLDESTROYSYNCKHRPROC>(eglGetProcAddress("eglDestroySyncKHR"));
    require(create&&wait&&destroy,"fence API");auto fence=create(gpuDisplay,EGL_SYNC_FENCE_KHR,nullptr);require(fence!=EGL_NO_SYNC_KHR,"fence creation");
    glFlush();auto status=wait(gpuDisplay,fence,0,1000000000ULL);destroy(gpuDisplay,fence);require(status==EGL_CONDITION_SATISFIED_KHR,"GPU complete-wave timeout");Gpu::check("complete wave");
    uint64_t complete=now();
    // Validate all live outputs on the first eligible wave before any scanout.
    // This readback is startup validation only and excluded from warm timings.
    if(!validated||EINK_VERIFY_ALL){
        std::vector<uint32_t>gpuMix;
        if(fullPixelWave){
            glBindBuffer(GL_SHADER_STORAGE_BUFFER,mixBuffer);
            auto*p=glMapBufferRange(GL_SHADER_STORAGE_BUFFER,0,1440*720,GL_MAP_READ_BIT);
            require(p,"validation mixed indices map");gpuMix.resize(1440*720/4);std::memcpy(gpuMix.data(),p,1440*720);
            require(glUnmapBuffer(GL_SHADER_STORAGE_BUFFER),"validation mixed indices unmap");mix=gpuMix.data();
        }
        size_t mismatch=0;
        for(int f=0;f<waveformFrames;f++){
            auto bytes=pool.frames[f]->snapshot();auto*actual=reinterpret_cast<uint32_t*>(bytes.data());
            for(int y=0;y<725;y++)for(int x=0;x<384;x++){
                int p=y*384+x;uint32_t v=templ[p];
                if(y>=5&&x<360){uint32_t m=mix[(y-5)*360+x];uint8_t data;
                    if(f==waveformFrames-1)data=0;
                    else if(motionMax<=0)data=uint8_t((lut[f*65536+(m&65535)]<<4)|lut[f*65536+(m>>16)]);
                    else{const uint8_t*s=&smapHost[size_t(y-5)*1440+x*4];
                        auto late=[&](uint32_t k,int sk){int ff=f-sk;return ff<0||ff>=active?0:int(lut[ff*65536+k]);};
                        data=uint8_t((((late(m&65535,s[0])>>2)&3)<<6)|((late(m&65535,s[1])&3)<<4)|(((late(m>>16,s[2])>>2)&3)<<2)|(late(m>>16,s[3])&3));}
                    v=(v&0xffffff00)|data;}
                uint32_t expected=(v&0xff00ff00)|((v&255)<<16)|((v>>16)&255);mismatch+=actual[p]!=expected;
            }
        }
        LOG("VERIFY wave=%llu frames=%d motion=%d mismatch=%zu",(unsigned long long)waveId,waveformFrames,motionMax,mismatch);if(!EINK_VERIFY_ALL)require(!mismatch,"live waveform byte mismatch");validated=true;
    }
    {std::lock_guard<std::mutex>l(ringMutex);pool.pending=0;pool.wave=waveId;pool.firstFrame=0;pool.count=uint32_t(waveformFrames);}
    producerPool=chosen;
    VLOG("GPU_WAVE wave=%llu pool=%d frames=%d begin=%llu complete=%llu validated=%llu",(unsigned long long)waveId,chosen,waveformFrames,
        (unsigned long long)begin,(unsigned long long)complete,(unsigned long long)now());
}

// Compatible old-hook admission. Do NOT enlarge this without switching the
// caller from complete-wave renderWave() to the rolling-window API below.
[[maybe_unused]] static bool gpuWaveEligible(void*ctl){
    if(!ctl||gpuFailed)return false;
    int total=field<int>(ctl,0x160);
    return field<uint8_t>(ctl,0x15c)==8&&field<int>(ctl,0x270)==0&&
        field<int>(ctl,0x278)==0&&field<int>(ctl,0x280)==0&&field<int>(ctl,0xc8)==total&&
        total>0&&total<=32&&field<int>(ctl,0xbc)==1440&&field<int>(ctl,0xc0)==720&&
        load<int>(base+0x4e46320)==0;
}
// Explicit admission for verified normal PairY4 states only. This is a contract
// check, not evidence that a producer has actually implemented a mode's pixels.
// CPU-index bridge waves must use gpuBeginWave directly and be labelled as such.
[[maybe_unused]] static bool gpuWaveEligible(void*ctl,const GpuWaveRequest&r){
    if(!ctl||gpuFailed||!einkwave::validWave(r)||!r.gpuIndices||!r.gpuIndexValidated)return false;
    uint8_t mode=field<uint8_t>(ctl,0x15c);
    return (mode==1||mode==2||mode==6||mode==8)&&r.layout==einkwave::IndexLayout::PairY4&&
        field<int>(ctl,0x270)==0&&field<int>(ctl,0x278)==0&&field<int>(ctl,0x280)==0&&
        field<int>(ctl,0x160)==int(r.activeFrames)&&field<int>(ctl,0xc8)==int(r.activeFrames)&&
        field<int>(ctl,0xbc)==1440&&field<int>(ctl,0xc0)==720&&
        load<int>(base+0x4e46320)==0&&!r.overlay12&&!r.bandDirection;
}

static einkwave::Mixer waveMixer;
[[maybe_unused]] static void gpuMixLevels(const GpuMixRequest&r){waveMixer.dispatch(r);}
[[maybe_unused]] static void gpuMixRegal(const GpuRegalMixRequest&r){waveMixer.dispatchRegal(r);}
// Access on the producer thread only. A controller owns its independent GPU
// level epoch; seed after CPU recovery, INIT, mode change, or temperature change.
// No opaque ctl field is overwritten by these helpers.
static std::unordered_map<void*,GpuWaveState> waveCtlStates;
struct GpuRegalBuffer {GLuint buffer=0;bool fullDestination=false;};
static std::unordered_map<void*,GpuRegalBuffer> waveRegalBuffers;
[[maybe_unused]] static GpuWaveState&gpuMixState(void*ctl){
    require(ctl!=nullptr,"null GPU mix controller");auto it=waveCtlStates.find(ctl);
    if(it!=waveCtlStates.end())return it->second;
    require(waveCtlStates.size()<4,"GPU mix controller budget");return waveCtlStates.emplace(ctl,GpuWaveState{}).first->second;
}
[[maybe_unused]] static void gpuForgetMixState(void*ctl){
    waveCtlStates.erase(ctl);auto it=waveRegalBuffers.find(ctl);
    // Caller must wait until this controller's rolling windows have completed.
    if(it!=waveRegalBuffers.end()){if(it->second.buffer)glDeleteBuffers(1,&it->second.buffer);waveRegalBuffers.erase(it);}
}
// Returns a controller-owned 2,073,600-byte SSBO. NEVER resize the old 1MiB
// mixBuffer: A2 preprocess and already prepared waves may still refer to it.
// ALL operations preserve part of the destination: Normal/ModeChange keep
// middle-row boundaries; Reset keeps half each row. Every first operation
// therefore requires stock history. The hook must invalidate/reseed this
// destination after any CPU write, mode transition, reset or recovery.
[[maybe_unused]] static GLuint gpuMixRegalForCtl(void*ctl,GLuint oldLevels,GLuint nextLevels,
    einkwave::RegalOperation operation=einkwave::RegalOperation::Normal,const uint8_t*resetSeed=nullptr){
    (void)gpuMixState(ctl);auto it=waveRegalBuffers.find(ctl);
    if(it==waveRegalBuffers.end())it=waveRegalBuffers.emplace(ctl,GpuRegalBuffer{}).first;
    auto&output=it->second;
    if(!output.buffer){
        GLuint b=0;glGenBuffers(1,&b);
        try{require(b!=0,"REGAL output allocation");glBindBuffer(GL_SHADER_STORAGE_BUFFER,b);
            glBufferData(GL_SHADER_STORAGE_BUFFER,einkwave::LevelBytes*2,nullptr,GL_DYNAMIC_COPY);Gpu::check("REGAL output storage");}
        catch(...){if(b)glDeleteBuffers(1,&b);throw;}
        output.buffer=b;
    }
    if(resetSeed){upload(output.buffer,resetSeed,einkwave::LevelBytes*2,2);output.fullDestination=true;}
    require(output.fullDestination,"REGAL operation requires preserved destination seed");
    GpuRegalMixRequest request{oldLevels,nextLevels,output.buffer,operation};
    try{gpuMixRegal(request);output.fullDestination=true;}
    catch(...){output.fullDestination=false;throw;}
    return output.buffer;
}

// New rolling renderer has separate live buffers so legacy A2 preprocessing
// cannot accidentally overwrite its frozen LUT/template. All calls belong to
// the producer's EGL thread. Ring/flip ownership remains under ringMutex.
struct GpuRollingWave {
    GLuint indices=0,lut=0,templ=0,smap=0;
    GpuWaveRequest request{};
    uint32_t nextFrame=0;
    bool ready=false;
};
static GpuRollingWave rollingWave;
constexpr size_t GPU_ROLLING_BUDGET=64u*1024u*1024u;

static void waveFence(){
    auto create=reinterpret_cast<PFNEGLCREATESYNCKHRPROC>(eglGetProcAddress("eglCreateSyncKHR"));
    auto wait=reinterpret_cast<PFNEGLCLIENTWAITSYNCKHRPROC>(eglGetProcAddress("eglClientWaitSyncKHR"));
    auto destroy=reinterpret_cast<PFNEGLDESTROYSYNCKHRPROC>(eglGetProcAddress("eglDestroySyncKHR"));
    require(create&&wait&&destroy,"rolling wave fence API");
    auto f=create(gpuDisplay,EGL_SYNC_FENCE_KHR,nullptr);require(f!=EGL_NO_SYNC_KHR,"rolling fence creation");
    glFlush();auto result=wait(gpuDisplay,f,0,1000000000ULL);destroy(gpuDisplay,f);
    require(result==EGL_CONDITION_SATISFIED_KHR,"rolling frame timeout");Gpu::check("rolling frame completion");
}
[[maybe_unused]] static void gpuBeginWave(const GpuWaveRequest&r){
    require(einkwave::validWave(r),"invalid rolling live-wave request");
    require(!rollingWave.ready||rollingWave.nextFrame==rollingWave.request.totalFrames,"unfinished rolling wave");
    // Frozen GPU index/start-map input must stay immutable until all windows
    // have completed. This also excludes in-place mixing while scan is active.
    auto*state=load<void*>(base+0x234300);setupGpu(field<int>(state,0x50));
    require(!r.gpuIndices||einkwave::storage(r.gpuIndices,r.indexBytes),"rolling GPU indices capacity");
    require(!r.gpuStartMap||einkwave::storage(r.gpuStartMap,einkwave::LevelBytes),"rolling GPU start-map capacity");
    require(!r.gpuLut||einkwave::storage(r.gpuLut,size_t(r.activeFrames)*65536u),"rolling GPU LUT capacity");
    auto&w=rollingWave;
    w.ready=false;
    if(!w.indices){
        GLuint ids[4]={};glGenBuffers(4,ids);w.indices=ids[0];w.lut=ids[1];w.templ=ids[2];w.smap=ids[3];
        require(w.indices&&w.lut&&w.templ&&w.smap,"rolling buffer allocation");
    }
    if(!r.gpuIndices)upload(w.indices,r.cpuIndices,r.indexBytes,0);
    else glBindBufferBase(GL_SHADER_STORAGE_BUFFER,0,r.gpuIndices);
    if(r.gpuLut)glBindBufferBase(GL_SHADER_STORAGE_BUFFER,1,r.gpuLut);
    else upload(w.lut,r.liveLut,r.neutralOnly?4:size_t(r.activeFrames)*65536u,1);
    upload(w.templ,r.controlTemplate,einkwave::FrameBytes,2);
    uint32_t zero=0;
    if(r.gpuStartMap)glBindBufferBase(GL_SHADER_STORAGE_BUFFER,3,r.gpuStartMap);
    else upload(w.smap,r.cpuStartMap?r.cpuStartMap:reinterpret_cast<const uint8_t*>(&zero),r.cpuStartMap?einkwave::LevelBytes:sizeof(zero),3);
    glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT|GL_BUFFER_UPDATE_BARRIER_BIT);
    waveFence();w.request=r;w.nextFrame=0;w.ready=true;
    VLOG("GPU_ROLL_BEGIN wave=%llu active=%u total=%u layout=%u indices=%s generation=%llu",
        (unsigned long long)r.wave,r.activeFrames,r.totalFrames,uint32_t(r.layout),r.gpuIndices?"GPU":"CPU_BRIDGE",
        (unsigned long long)r.stateGeneration);
}

// Returns a RESERVED pool index. No ring record is published on failure.
// count=1 is intended for normal rolling scan; count<=8 prefetches a window.
// Acquire before the stock Update of that frame, then publish only after this
// function succeeds. Do not invoke after changing a stock remaining counter.
[[maybe_unused]] static int gpuRenderWaveWindow(uint32_t first,uint32_t count=1){
    const bool cacheUniforms=useUniformCache();
    auto&w=rollingWave;const auto&r=w.request;
    require(w.ready&&first==w.nextFrame&&count>0&&count<=einkwave::WindowFrames&&
        first<r.totalFrames&&count<=r.totalFrames-first,"rolling frame order/range");
    int chosen=-1;
    {std::unique_lock<std::mutex>lock(ringMutex);
        bool free=poolCv.wait_for(lock,std::chrono::seconds(1),[&](){
            for(int i=0;i<2;i++)if(pools[i].pending==0&&!pools[i].reserved&&i!=activePool){chosen=i;return true;}
            return false;
        });
        require(free,"no free rolling frame pool");pools[chosen].reserved=true;
    }
    auto&pool=pools[chosen];
    try{
        // Shrink a free legacy complete-wave pool before reusing it. It has no
        // queued or active DRM framebuffer, so deletion is safe on this thread.
        if(pool.frames.size()>einkwave::WindowFrames)pool.frames.resize(einkwave::WindowFrames);
        int drm=field<int>(load<void*>(base+0x234300),0x50);
        while(pool.frames.size()<count)pool.frames.push_back(std::make_unique<Shared>(gpuDisplay,384,725,
            AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT|AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE|
            AHARDWAREBUFFER_USAGE_COMPOSER_OVERLAY|AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN,drm));
        // Count real AHB strides, not only nominal 384x725 bytes. Include both
        // free/active pools and conservative external SSBO costs in the budget.
        size_t bytes=size_t(r.activeFrames)*65536u+einkwave::FrameBytes+r.indexBytes+
            einkwave::LevelBytes*4;
        for(const auto&candidate:pools)for(const auto&f:candidate.frames)bytes+=f->length;
        require(bytes<=GPU_ROLLING_BUDGET,"rolling resource budget exceeded");
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER,0,r.gpuIndices?r.gpuIndices:w.indices);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER,1,r.gpuLut?r.gpuLut:w.lut);glBindBufferBase(GL_SHADER_STORAGE_BUFFER,2,w.templ);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER,3,r.gpuStartMap?r.gpuStartMap:w.smap);
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT|GL_BUFFER_UPDATE_BARRIER_BIT);
        glUseProgram(program);glUniform1ui((cacheUniforms?waveUniforms.activeFrames:glGetUniformLocation(program,"activeFrames")),r.activeFrames);
        glUniform1i((cacheUniforms?waveUniforms.motion:glGetUniformLocation(program,"motion")),r.gpuStartMap||r.cpuStartMap);
        glUniform1ui((cacheUniforms?waveUniforms.indexLayout:glGetUniformLocation(program,"indexLayout")),uint32_t(r.layout));
        glUniform1ui((cacheUniforms?waveUniforms.bandDirection:glGetUniformLocation(program,"bandDirection")),r.bandDirection);
        glUniform1ui((cacheUniforms?waveUniforms.bandFrames:glGetUniformLocation(program,"bandFrames")),r.bandFrames);
        glUniform1ui((cacheUniforms?waveUniforms.bandRows:glGetUniformLocation(program,"bandRows")),r.bandRows);
        glUniform1ui((cacheUniforms?waveUniforms.bandTail:glGetUniformLocation(program,"bandTail")),r.bandTail);
        glUniform1ui((cacheUniforms?waveUniforms.bandStyle:glGetUniformLocation(program,"bandStyle")),r.bandStyle);
        glUniform1i((cacheUniforms?waveUniforms.maskRegion:glGetUniformLocation(program,"maskRegion")),r.maskRegion);
        glUniform1i((cacheUniforms?waveUniforms.overlay12:glGetUniformLocation(program,"overlay12")),r.overlay12);
        glUniform1i((cacheUniforms?waveUniforms.twoRegions:glGetUniformLocation(program,"twoRegions")),r.twoRegions);
        glUniform4ui((cacheUniforms?waveUniforms.activeRect:glGetUniformLocation(program,"activeRect")),r.region.left,r.region.top,r.region.right,r.region.bottom);
        glUniform4ui((cacheUniforms?waveUniforms.secondRect:glGetUniformLocation(program,"secondRect")),r.secondRegion.left,r.secondRegion.top,r.secondRegion.right,r.secondRegion.bottom);
        for(uint32_t i=0;i<count;i++){
            uint32_t frame=first+i;
            glBindFramebuffer(GL_FRAMEBUFFER,pool.frames[i]->fbo);glViewport(0,0,384,725);
            glUniform1ui((cacheUniforms?waveUniforms.frameNumber:glGetUniformLocation(program,"frameNumber")),frame);
            // An inline region's terminal table ORs zero into the template;
            // stock blank instead clears the full drive byte. Preserve both semantics.
            glUniform1i((cacheUniforms?waveUniforms.neutral:glGetUniformLocation(program,"neutral")),r.neutralOnly||(!r.overlay12&&frame==r.totalFrames-1));
            glDrawArrays(GL_TRIANGLES,0,3);
        }
        waveFence();
        {std::lock_guard<std::mutex>lock(ringMutex);pool.wave=r.wave;pool.firstFrame=first;pool.count=count;}
        w.nextFrame+=count;
        VLOG("GPU_ROLL_WINDOW wave=%llu first=%u count=%u pool=%d budget=%zu",(unsigned long long)r.wave,first,count,chosen,bytes);
        return chosen;
    }catch(...){
        {std::lock_guard<std::mutex>lock(ringMutex);pool.reserved=false;pool.count=0;}
        poolCv.notify_all();throw;
    }
}
// Call ONLY once every frame of the returned window has a ring record and its
// pending counter increment, or once the unqueued window is explicitly dropped.
// This never clears pending and never frees the current scan framebuffer.
[[maybe_unused]] static void gpuReleaseWaveWindow(int p){
    require(p>=0&&p<2,"rolling pool index");
    {std::lock_guard<std::mutex>lock(ringMutex);require(pools[p].reserved,"rolling pool not reserved");pools[p].reserved=false;}
    poolCv.notify_all();
}
// Resolve a consumer's ABSOLUTE frame under ringMutex before accessing frames.
[[maybe_unused]] static size_t gpuWavePoolFrame(int p,uint64_t wave,uint32_t frame){
    require(p>=0&&p<2,"consumer pool index");const auto&pool=pools[p];
    require(pool.wave==wave&&frame>=pool.firstFrame&&frame-pool.firstFrame<pool.count,"stale rolling ring record");
    return size_t(frame-pool.firstFrame);
}
// Safe before any scan publication: roll back descriptors only. The caller
// restores GPU/CPU level state and lets stock generate the SAME frame. After
// publication, drain outstanding leases before releasing resources/seed state.
[[maybe_unused]] static void gpuCancelRollingWave(){rollingWave.ready=false;}
