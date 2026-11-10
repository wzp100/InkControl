#pragma once
// GPU input front end. Raw/pending point to three private AHardwareBuffers.
// A shared producer context imports the borrowed SF handle and copies pixels
// on GPU with acquire/copy fences. Pending->raw and previous-frame store use
// rotations; rotation, grayscale and the unchanged-frame check run in one
// compute pass. Auto has an exact statistics hook; only 24 bytes return to CPU.
#include <pthread.h>
#include "service_image_ops.hpp"
#include "service_import.hpp"
static constexpr uintptr_t VAR_WORK=0x2341d0,VAR_RAW=0x2341d8,VAR_PENDING=0x2341e0;
static constexpr uintptr_t GOT_PENDING_MUTEX=0x9ff38,VAR_EPD_MODE=0x9e358;
static constexpr size_t IMAGE_BYTES=1440*720*4;
struct Input{AHardwareBuffer*ahb=nullptr;EGLImageKHR image=EGL_NO_IMAGE_KHR;GLuint texture=0;int fd=-1;uint8_t*map=nullptr;};
static Input inputs[3];
static std::recursive_mutex inputRoles;
static bool inputGpuWritten[3]={},inputReserved[3]={};
struct InputQueueLock{pthread_mutex_t*p;explicit InputQueueLock(pthread_mutex_t*v):p(v){
    require(!pthread_mutex_lock(p),"input queue lock");}~InputQueueLock(){pthread_mutex_unlock(p);}};
static bool inputsReady=false,inputsFailed=false,frontVerified=false;
// Roles: raw = *VAR_RAW, pending = *VAR_PENDING, prevRaw = previous consumed image.
static int frontRaw=-1,prevRaw=-1,grayInput=-1;
static bool frontReady=false,prevValid=false,gpuSource=false,prevArrayStale=false,compared=false;
// FIX(full-refresh-stale-frame): the raw/pending/prev rotation below assumes every
// wave gets a fresh SurfaceFlinger frame. A system-driven full refresh (fingerprint
// / EpdManager.forceClear) has no new composition, so the recycled raw buffer still
// holds an older image and the refresh redraws the previous page. Track SF publishes
// via the bridge and, when none arrived since the last consume, reuse the image that
// is already on the panel instead of the stale recycled buffer.
static int lastConsumed=-1;
static uint64_t sfPublishSeq=0,consumedSeq=0;
// Safety valve: setprop debug.eink.front.reuse 0 restores the old rotation behaviour.
static bool frontReuseEnabled(){char v[PROP_VALUE_MAX]={};__system_property_get("debug.eink.front.reuse",v);return v[0]!='0';}
static GLuint frontProgram,rgbaGrayProgram,grayBuffer,diffBuffer;
static GLint frontCurLoc=-1,frontPrevLoc=-1,frontHasPrevLoc=-1;
static GLuint inputStatsProgram,inputStatsBuffer,inputWorkProgram,inputWorkBuffer;
static uint32_t frontMode=0;
static unsigned videoDither();
static void materialiseGpuWork(int index,void*destination);
static void storePtr(uintptr_t var,const void*p){std::memcpy(reinterpret_cast<void*>(base+var),&p,sizeof(p));}
static int inputIndex(const void*p){for(int i=0;i<3;i++)if(inputs[i].map==p)return i;return -1;}
#ifndef EINK_FRONT
#define EINK_FRONT 1
#endif
static bool frontEnabled(){return EINK_FRONT&&load<uint64_t>(base+0x6300080)>=2&&!gpuFailed;}
static void cpuTranspose(const uint8_t*raw,uint8_t*work){
    // Stock layout: work(x=y_in, y=x_in) = raw(x_in, y_in); raw is 720x1440 portrait.
    auto*src=reinterpret_cast<const uint32_t*>(raw);auto*dst=reinterpret_cast<uint32_t*>(work);
    for(int yb=0;yb<1440;yb+=16)for(int xb=0;xb<720;xb+=16)
        for(int y=yb;y<yb+16;y++)for(int x=xb;x<xb+16;x++)dst[x*1440+y]=src[y*720+x];
}
static void invalidateInput(const Input&in){
    dma_buf_sync sync{};sync.flags=DMA_BUF_SYNC_START|DMA_BUF_SYNC_READ;
    require(!ioctl(in.fd,DMA_BUF_IOCTL_SYNC,&sync),"input CPU read sync start");
    sync.flags=DMA_BUF_SYNC_END|DMA_BUF_SYNC_READ;
    require(!ioctl(in.fd,DMA_BUF_IOCTL_SYNC,&sync),"input CPU read sync end");
}
static void cpuInputWork(int index,void*destination){
    std::lock_guard<std::recursive_mutex> roles(inputRoles);
    require(index>=0&&index<3&&inputsReady&&destination,"CPU input recovery role");
    if(inputGpuWritten[index])invalidateInput(inputs[index]);
    cpuTranspose(inputs[index].map,static_cast<uint8_t*>(destination));
}
static void redirectInputs(){
    if(inputsReady||inputsFailed||!frontEnabled())return;
    try{
        for(auto&in:inputs){
            AHardwareBuffer_Desc desc{};desc.width=1440;desc.height=720;desc.layers=1;desc.format=AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
            desc.usage=AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT|AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE|AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN|AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN;
            int r=AHardwareBuffer_allocate(&desc,&in.ahb);if(r)throw std::runtime_error("input AHB allocate "+std::to_string(r));
            AHardwareBuffer_describe(in.ahb,&desc);
            // The stock code writes contiguous 720-pixel portrait rows; a 1440-wide
            // texture keeps those bytes linear only without row padding.
            require(desc.stride==1440,"input stride "+std::to_string(desc.stride));
            in.fd=dmaFd(in.ahb,IMAGE_BYTES);
            void*p=mmap(nullptr,IMAGE_BYTES,PROT_READ|PROT_WRITE,MAP_SHARED,in.fd,0);require(p!=MAP_FAILED,"input mmap");
            in.map=static_cast<uint8_t*>(p);
        }
        InputQueueLock rawQueue(reinterpret_cast<pthread_mutex_t*>(base+0x233f90));
        InputQueueLock pendingQueue(load<pthread_mutex_t*>(base+GOT_PENDING_MUTEX));
        std::lock_guard<std::recursive_mutex> roles(inputRoles);
        std::memcpy(inputs[1].map,load<uint8_t*>(base+VAR_PENDING),IMAGE_BYTES);storePtr(VAR_PENDING,inputs[1].map);
        // Raw is only written by SurfaceFlinger while this producer thread waits.
        std::memcpy(inputs[0].map,load<uint8_t*>(base+VAR_RAW),IMAGE_BYTES);storePtr(VAR_RAW,inputs[0].map);
        prevRaw=2;prevValid=false;inputsReady=true;
        LOG("INPUT_REDIRECT raw=%p pending=%p prev=%p",inputs[0].map,inputs[1].map,inputs[2].map);
    }catch(const std::exception&e){LOG("INPUT_DISABLED reason=%s",e.what());inputsFailed=true;}
}
static void hookTranspose(){
    std::lock_guard<std::recursive_mutex> roles(inputRoles);
    auto*raw=load<uint8_t*>(base+VAR_RAW);uint32_t mode=load<uint32_t>(base+VAR_EPD_MODE);
    int index=inputIndex(raw);frontReady=false;
    if(frontEnabled()&&inputsReady&&index>=0){
        // No fresh SurfaceFlinger frame since the last consume means the rotation
        // handed us a stale buffer: keep displaying the image already on the panel.
        bool fresh=sfPublishSeq!=consumedSeq;
        int use=index;
        if(!fresh&&frontReuseEnabled()&&lastConsumed>=0){
            use=lastConsumed;
            VLOG("FRONT_REUSE wave=%llu raw=%d reused=%d",(unsigned long long)waveId,index,use);
        }
        if(use<0)use=index;
        if(use>=0){
            frontRaw=use;frontMode=mode;
            frontReady=true;return;
        }
    }
    if(index>=0&&inputGpuWritten[index])invalidateInput(inputs[index]);
    cpuTranspose(raw,load<uint8_t*>(base+VAR_WORK));
}
static void*hookTake(void*dst,const void*src,size_t n){
    std::lock_guard<std::recursive_mutex> roles(inputRoles);
    // Called with the pending mutex held: swap raw/pending instead of copying 4MB.
    if(!inputsReady||n!=IMAGE_BYTES||dst==src||inputIndex(dst)<0||inputIndex(src)<0)return std::memcpy(dst,src,n);
    storePtr(VAR_RAW,src);storePtr(VAR_PENDING,dst);return dst;
}
static void bindInput(int unit,const Input&in);
static void setupFront(){
    if(frontProgram)return;
    // Tiled transpose: a workgroup reads a 16-column x 32-row portrait block with
    // row-contiguous fetches, transposes it in shared memory and writes a 32x16
    // landscape block of packed Y8 (8 uints per landscape row).
    const char*front=R"(#version 310 es
precision highp float;precision highp int;
layout(local_size_x=128)in;
uniform highp sampler2D cur;uniform highp sampler2D prev;uniform bool hasPrev;
layout(std430,binding=7)writeonly buffer Gray{uint gray[];};
layout(std430,binding=8)buffer Diff{uint diff;};
shared uint tile[16][33];
uvec4 texel(highp sampler2D s,uint i){return uvec4(texelFetch(s,ivec2(int(i%1440u),int(i/1440u)),0)*255.0+0.5);}
void main(){uint t=gl_LocalInvocationID.x,X0=gl_WorkGroupID.x*32u,Y0=gl_WorkGroupID.y*16u;
uint col=t%16u,group=t/16u,changed=0u;
for(uint j=0u;j<4u;j++){uint row=group*4u+j,p=(X0+row)*720u+(Y0+col);
uvec4 c=texel(cur,p);tile[col][row]=(38u*c.r+75u*c.g+15u*c.b)>>7u;
if(hasPrev&&any(notEqual(c,texel(prev,p))))changed++;}
barrier();
uint ux=t%8u,uy=t/8u;
gray[(Y0+uy)*360u+X0/4u+ux]=tile[uy][ux*4u]|(tile[uy][ux*4u+1u]<<8u)|(tile[uy][ux*4u+2u]<<16u)|(tile[uy][ux*4u+3u]<<24u);
if(changed>0u)atomicAdd(diff,changed);})";
    const char*rgba=R"(#version 310 es
precision highp int;
layout(local_size_x=128)in;
layout(std430,binding=3)readonly buffer RGBA{uint rgba[];};
layout(std430,binding=7)writeonly buffer Gray{uint gray[];};
void main(){uint g=gl_GlobalInvocationID.x;if(g>=259200u)return;uint packed=0u;
for(uint k=0u;k<4u;k++){uint c=rgba[g*4u+k];packed|=((38u*(c&255u)+75u*((c>>8u)&255u)+15u*((c>>16u)&255u))>>7u)<<(k*8u);}
gray[g]=packed;})";
    auto link=[](const char*src){GLuint s=compile(GL_COMPUTE_SHADER,src),p=glCreateProgram();glAttachShader(p,s);glLinkProgram(p);
        GLint ok=0;glGetProgramiv(p,GL_LINK_STATUS,&ok);require(ok,"front program link");glDeleteShader(s);return p;};
    frontProgram=link(front);rgbaGrayProgram=link(rgba);
    frontCurLoc=glGetUniformLocation(frontProgram,"cur");frontPrevLoc=glGetUniformLocation(frontProgram,"prev");frontHasPrevLoc=glGetUniformLocation(frontProgram,"hasPrev");
    glGenBuffers(1,&grayBuffer);glBindBuffer(GL_SHADER_STORAGE_BUFFER,grayBuffer);glBufferData(GL_SHADER_STORAGE_BUFFER,1440*720,nullptr,GL_DYNAMIC_COPY);
    glGenBuffers(1,&diffBuffer);glBindBuffer(GL_SHADER_STORAGE_BUFFER,diffBuffer);glBufferData(GL_SHADER_STORAGE_BUFFER,4,nullptr,GL_DYNAMIC_READ);
}
static void setupInputImages(){
    static bool done=false;if(done||!inputsReady)return;
    auto native=reinterpret_cast<PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC>(eglGetProcAddress("eglGetNativeClientBufferANDROID"));
    auto create=reinterpret_cast<PFNEGLCREATEIMAGEKHRPROC>(eglGetProcAddress("eglCreateImageKHR"));
    require(native&&create,"input EGL functions");
    for(auto&in:inputs){
        EGLint attrs[]={EGL_IMAGE_PRESERVED_KHR,EGL_TRUE,EGL_NONE};
        in.image=create(gpuDisplay,EGL_NO_CONTEXT,EGL_NATIVE_BUFFER_ANDROID,native(in.ahb),attrs);
        require(in.image!=EGL_NO_IMAGE_KHR,"input EGLImage");
        glGenTextures(1,&in.texture);bindInput(0,in);
    }
    done=true;
}
#ifndef EINK_REBIND_INPUT
#define EINK_REBIND_INPUT 1
#endif
static void bindInput(int unit,const Input&in){
    // The EGLImage is attached once at setup; EINK_REBIND_INPUT=1 re-attaches per use.
    static auto bind=reinterpret_cast<PFNGLEGLIMAGETARGETTEXTURE2DOESPROC>(eglGetProcAddress("glEGLImageTargetTexture2DOES"));
    require(bind,"input EGL bind function");
    glActiveTexture(GL_TEXTURE0+unit);glBindTexture(GL_TEXTURE_2D,in.texture);
    static bool attached[3]={};int i=int(&in-inputs);
    if(!attached[i]||EINK_REBIND_INPUT){
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);
        bind(GL_TEXTURE_2D,reinterpret_cast<GLeglImageOES>(in.image));attached[i]=true;
    }
}
static void cleanForGpu(const Input&in){
    if(inputGpuWritten[int(&in-inputs)])return;
    // TO_DEVICE begin is a no-op; the end cleans CPU caches over the buffer.
    dma_buf_sync sync{};sync.flags=DMA_BUF_SYNC_START|DMA_BUF_SYNC_WRITE;require(!ioctl(in.fd,DMA_BUF_IOCTL_SYNC,&sync),"input sync start");
    sync.flags=DMA_BUF_SYNC_END|DMA_BUF_SYNC_WRITE;require(!ioctl(in.fd,DMA_BUF_IOCTL_SYNC,&sync),"input sync end");
}
static void finishPixels();
static Bytes readGpu(GLuint buffer);
// Call only on the producer/context thread, with a stable input role. This
// exposes exact stock counters; deciding/changing the mode remains the host's job.
static inline EinkImageOps::AutoStats gpuAutoStats(int index){
    std::lock_guard<std::recursive_mutex> roles(inputRoles);
    require(index>=0&&index<3&&inputsReady,"statistics input role");
    auto*state=load<void*>(base+0x234300);setupGpu(field<int>(state,0x50));setupInputImages();
    if(!inputStatsProgram){inputStatsProgram=EinkImageOps::link(EinkImageOps::statsShader());glGenBuffers(1,&inputStatsBuffer);}
    EinkImageOps::AutoStats zero{};cleanForGpu(inputs[index]);bindInput(0,inputs[index]);
    glBindBuffer(GL_SHADER_STORAGE_BUFFER,inputStatsBuffer);glBufferData(GL_SHADER_STORAGE_BUFFER,sizeof(zero),&zero,GL_DYNAMIC_READ);
    glBindBufferBase(GL_SHADER_STORAGE_BUFFER,8,inputStatsBuffer);
    glUseProgram(inputStatsProgram);glUniform1i(glGetUniformLocation(inputStatsProgram,"source"),0);
    EinkImageOps::uniform(inputStatsProgram,"count",1440*720);EinkImageOps::uniform(inputStatsProgram,"textureWidth",1440);
    glDispatchCompute((1440*720+127)/128,1,1);glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT|GL_BUFFER_UPDATE_BARRIER_BIT);finishPixels();
    glBindBuffer(GL_SHADER_STORAGE_BUFFER,inputStatsBuffer);
    void*p=glMapBufferRange(GL_SHADER_STORAGE_BUFFER,0,sizeof(zero),GL_MAP_READ_BIT);require(p,"statistics map");
    EinkImageOps::AutoStats result{};std::memcpy(&result,p,sizeof(result));require(glUnmapBuffer(GL_SHADER_STORAGE_BUFFER),"statistics unmap");
    VLOG("AUTO_STATS_READBACK bytes=%zu",sizeof(result));return result;
}
static GLuint gpuWorkPixels(int index){
    std::lock_guard<std::recursive_mutex> roles(inputRoles);
    require(index>=0&&index<3&&inputsReady,"GPU work input");
    auto*state=load<void*>(base+0x234300);setupGpu(field<int>(state,0x50));setupInputImages();
    if(!inputWorkProgram){inputWorkProgram=EinkImageOps::link(EinkImageOps::workShader());
        glGenBuffers(1,&inputWorkBuffer);glBindBuffer(GL_SHADER_STORAGE_BUFFER,inputWorkBuffer);
        glBufferData(GL_SHADER_STORAGE_BUFFER,IMAGE_BYTES,nullptr,GL_DYNAMIC_READ);}
    cleanForGpu(inputs[index]);bindInput(0,inputs[index]);glUseProgram(inputWorkProgram);
    glUniform1i(glGetUniformLocation(inputWorkProgram,"source"),0);
    EinkImageOps::uniform(inputWorkProgram,"width",1440);EinkImageOps::uniform(inputWorkProgram,"height",720);
    EinkImageOps::uniform(inputWorkProgram,"textureWidth",1440);
    glBindBufferBase(GL_SHADER_STORAGE_BUFFER,3,inputWorkBuffer);glDispatchCompute((1440*720+127)/128,1,1);
    glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT|GL_BUFFER_UPDATE_BARRIER_BIT);return inputWorkBuffer;
}
static void materialiseGpuWork(int index,void*destination){
    std::lock_guard<std::recursive_mutex> roles(inputRoles);
    require(destination,"work bridge destination");GLuint buffer=gpuWorkPixels(index);finishPixels();
    glBindBuffer(GL_SHADER_STORAGE_BUFFER,buffer);void*p=glMapBufferRange(GL_SHADER_STORAGE_BUFFER,0,IMAGE_BYTES,GL_MAP_READ_BIT);
    require(p,"work bridge map");std::memcpy(destination,p,IMAGE_BYTES);require(glUnmapBuffer(GL_SHADER_STORAGE_BUFFER),"work bridge unmap");
    telemetryBytes(T_READBACK,IMAGE_BYTES);
    VLOG("WORK_RGBA_BRIDGE bytes=%zu",IMAGE_BYTES);
}
static uint32_t runFront(){
    const bool cacheUniforms=useUniformCache();
    std::lock_guard<std::recursive_mutex> roles(inputRoles);
    auto*state=load<void*>(base+0x234300);setupGpu(field<int>(state,0x50));setupFront();
    setupInputImages();
    uint64_t begin=now();auto&cur=inputs[frontRaw];cleanForGpu(cur);uint64_t cleaned=now();
    uint32_t zero=0;glBindBuffer(GL_SHADER_STORAGE_BUFFER,diffBuffer);glBufferSubData(GL_SHADER_STORAGE_BUFFER,0,4,&zero);
    bindInput(0,cur);bindInput(1,prevValid?inputs[prevRaw]:cur);
    glUseProgram(frontProgram);glUniform1i((cacheUniforms?frontCurLoc:glGetUniformLocation(frontProgram,"cur")),0);glUniform1i((cacheUniforms?frontPrevLoc:glGetUniformLocation(frontProgram,"prev")),1);
    glUniform1i((cacheUniforms?frontHasPrevLoc:glGetUniformLocation(frontProgram,"hasPrev")),prevValid);
    glBindBufferBase(GL_SHADER_STORAGE_BUFFER,7,grayBuffer);glBindBufferBase(GL_SHADER_STORAGE_BUFFER,8,diffBuffer);
    glDispatchCompute(1440/32,720/16,1);glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT|GL_BUFFER_UPDATE_BARRIER_BIT);
    uint64_t submitted=now();finishPixels();uint64_t done=now();
    glBindBuffer(GL_SHADER_STORAGE_BUFFER,diffBuffer);auto*p=static_cast<uint32_t*>(glMapBufferRange(GL_SHADER_STORAGE_BUFFER,0,4,GL_MAP_READ_BIT));
    require(p,"diff map");uint32_t diff=*p;require(glUnmapBuffer(GL_SHADER_STORAGE_BUFFER),"diff unmap");
    if(!frontVerified||EINK_VERIFY_ALL){
        auto gray=readGpu(grayBuffer);size_t grayMismatch=0,cpuDiff=0;
        if(inputGpuWritten[frontRaw])invalidateInput(cur);
        if(prevValid&&inputGpuWritten[prevRaw])invalidateInput(inputs[prevRaw]);
        auto*c=reinterpret_cast<const uint32_t*>(cur.map);auto*q=prevValid?reinterpret_cast<const uint32_t*>(inputs[prevRaw].map):nullptr;
        for(size_t l=0;l<1440*720;l++){
            size_t x=l%1440,y=l/1440,pi=x*720+y;uint32_t v=c[pi];
            grayMismatch+=gray[l]!=uint8_t((38*(v&255)+75*((v>>8)&255)+15*((v>>16)&255))>>7);
            if(q)cpuDiff+=v!=q[pi];
        }
        LOG("FRONT_VERIFY wave=%llu gray=%zu diff_gpu=%u diff_cpu=%zu has_prev=%d",(unsigned long long)waveId,grayMismatch,diff,cpuDiff,prevValid);
        if(!EINK_VERIFY_ALL)require(!grayMismatch&&(!q||cpuDiff==diff),"front mismatch");
        frontVerified=true;
    }
    VLOG("FRONT wave=%llu input=%d prev=%d diff=%u clean_us=%llu submit_us=%llu gpu_us=%llu",(unsigned long long)waveId,frontRaw,prevValid?prevRaw:-1,diff,
        (unsigned long long)(cleaned-begin)/1000,(unsigned long long)(submitted-cleaned)/1000,(unsigned long long)(done-submitted)/1000);
    return prevValid?diff:1;
}
static int hookCompare(const void*a,const void*b,size_t n){
    std::lock_guard<std::recursive_mutex> roles(inputRoles);
    compared=true;gpuSource=false;
    if(frontReady){
        try{uint32_t diff=runFront();frontDiff=diff;gpuSource=true;grayInput=frontRaw;return diff?1:0;}
        catch(const std::exception&e){
            telemetryAdd(T_ERRORS);LOG("GPU_ERROR wave=%llu reason=front %s",(unsigned long long)waveId,e.what());gpuFailed=true;frontReady=false;
            cpuInputWork(frontRaw,load<uint8_t*>(base+VAR_WORK));
        }
    }
    // The CPU previous frame is not maintained while GPU-sourced frames run.
    if(prevArrayStale)return 1;
    return std::memcmp(a,b,n);
}
static void*hookStore(void*dst,const void*src,size_t n){
    std::lock_guard<std::recursive_mutex> roles(inputRoles);
    if(gpuSource){
        require(n==IMAGE_BYTES,"input previous size");
        // Auto statistics consume the resident grayInput role in hookAutoStats.
        // The consumed image becomes the previous frame; the old previous buffer
        // becomes the next raw target (SurfaceFlinger only fills raw while waiting).
        // When no new frame was consumed (full-refresh reuse) the roles stay as they
        // are so the recycled target is not exposed as the current image.
        if(frontRaw!=prevRaw){
            storePtr(VAR_RAW,inputs[prevRaw].map);prevRaw=frontRaw;
        }
        lastConsumed=frontRaw;consumedSeq=sfPublishSeq;
        prevValid=true;prevArrayStale=true;frontReady=false;return dst;
    }
    frontReady=false;prevValid=false;prevArrayStale=false;return std::memcpy(dst,src,n);
}
// Called around ModeDecision: a reset/transition path that skipped the compare
// leaves the stock previous frame (white) authoritative.
static void beginDecision(){std::lock_guard<std::recursive_mutex>roles(inputRoles);compared=false;gpuSource=false;frontDiff=UINT32_MAX;}
static void endDecision(){std::lock_guard<std::recursive_mutex>roles(inputRoles);if(!compared){prevValid=false;prevArrayStale=false;frontReady=false;}}

static bool importResolve(void*,einkimport::Config&cfg,std::array<void*,3>&tokens)noexcept{
    std::lock_guard<std::recursive_mutex>roles(inputRoles);
    if(!inputsReady||!frontEnabled()||!gpuContextReady.load(std::memory_order_acquire))return false;
    cfg.display=gpuDisplay;cfg.share=gpuContext;
    for(int i=0;i<3;++i){cfg.targets[i]=inputs[i].ahb;tokens[i]=inputs[i].map;}return true;
}
static int importReserve(void*,einkimport::PublishKind,void*target)noexcept{
    inputRoles.lock();int i=inputIndex(target);
    if(!frontEnabled()||i<0||inputReserved[i]||(prevValid&&i==prevRaw)||(frontReady&&gpuSource&&i==grayInput)){
        inputRoles.unlock();return -1;
    }inputReserved[i]=true;return i;
}
static void importRelease(void*,einkimport::PublishKind,int slot,bool commit)noexcept{
    if(commit){inputGpuWritten[slot]=true;++sfPublishSeq;telemetryAdd(T_INPUT_GPU);}
    inputReserved[slot]=false;inputRoles.unlock();
}
static bool importFallback(void*,const char*reason)noexcept{
    try{
        std::lock_guard<std::recursive_mutex>roles(inputRoles);
        telemetryAdd(T_INPUT_CPU);
        bool warming=!inputsReady||!gpuContextReady.load(std::memory_order_acquire);
        if(!warming){
            for(int i=0;i<3;++i)if(inputGpuWritten[i]){invalidateInput(inputs[i]);inputGpuWritten[i]=false;}
            if(frontEnabled()){
                telemetryAdd(T_ERRORS);LOG("INPUT_IMPORT_FALLBACK reason=%s",reason);
                gpuFailed=true;prevArrayStale=true;prevValid=false;frontReady=false;
            }
        }return true;
    }catch(const std::exception&e){LOG("INPUT_IMPORT_BLOCKED reason=%s",e.what());return false;}
}
static void hookDumpFrameBuffer(void*layer)noexcept{
    einkimport::Host host{};host.resolve=importResolve;host.reserve=importReserve;
    host.release=importRelease;host.cpuFallback=importFallback;
    static einkimport::StockBridge bridge(base,host);bridge.dump(layer);
}
static void*hookRawFill(void*destination,int value,size_t bytes){
    std::lock_guard<std::recursive_mutex>roles(inputRoles);int i=inputIndex(destination);
    if(i>=0){if(inputGpuWritten[i])invalidateInput(inputs[i]);inputGpuWritten[i]=false;telemetryAdd(T_INPUT_CPU);}
    return std::memset(destination,value,bytes);
}
