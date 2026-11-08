#pragma once
// GPU-only level mixing and live-wave lookup. No stock control fields are changed.
#include <GLES3/gl31.h>
#include <algorithm>
#include <cstdint>
#include <cstddef>
#include <stdexcept>
#include <string>

namespace einkwave {
constexpr uint32_t Width=1440, Height=720, OutputWidth=384, OutputHeight=725;
constexpr size_t LevelBytes=size_t(Width)*Height, FrameBytes=size_t(OutputWidth)*OutputHeight*4;
// Current stock WBF has up to 159 frames INCLUDING its neutral tail. Allow a
// 159-frame live table, never clamp or synthesize the temperature-selected count.
constexpr uint32_t MaxLiveFrames=159, WindowFrames=8;
struct Rect {uint32_t left=0,top=0,right=Width,bottom=Height;};
inline bool validRect(const Rect&r){return r.left<=r.right&&r.top<=r.bottom&&r.right<=Width&&r.bottom<=Height;}
enum class MixOperation:uint32_t {LevelsY4=0,ModeChange=1,Reset=2};
enum class IndexLayout:uint32_t {PairY4=0,PixelY8Edge=1,PixelY8=2};
// SSBOs contain little-endian packed bytes: FOUR 0..30 levels per uint.
// LevelsY4 reproduces Generation_MixFrame_Y4(old, next, dst, W, H), i.e.
// even byte=old pair; odd byte=next pair. The names describe service call order.
// ModeChange/Reset reproduce the two stock routines using oldLevels as x0.
struct MixRequest {
    GLuint oldLevels=0,newLevels=0,mixed=0,nextState=0;
    MixOperation operation=MixOperation::LevelsY4;
    Rect region{};
};
enum class RegalOperation:uint32_t {Normal=0,ModeChange=1,Reset=2};
struct RegalRequest {
    // mixed MUST already contain the preceding stock destination. Normal and
    // ModeChange leave both u16 boundary pixels of intermediate rows untouched;
    // Reset leaves the second HALF of each output row untouched. A new/zeroed
    // buffer is not a substitute for that history. Caller owns seeding/fencing.
    GLuint oldLevels=0,newLevels=0,mixed=0;
    RegalOperation operation=RegalOperation::Normal;
};
struct State {
    uint64_t generation=1,preparedGeneration=0;
    uint32_t mode=0,temperature=0;
    GLuint oldLevels=0,preparedLevels=0,preparedMix=0;
    bool valid=false,prepared=false;
    void invalidate(){++generation;valid=false;prepared=false;oldLevels=0;preparedLevels=0;preparedMix=0;}
    // Only call at a completed stock state boundary, after any prior scan lease.
    void seed(GLuint levels,uint32_t m,uint32_t temp){invalidate();oldLevels=levels;mode=m;temperature=temp;valid=levels!=0;}
    bool stage(GLuint levels,GLuint mix,uint32_t m,uint32_t temp){
        if(!valid||prepared||!levels||levels==oldLevels||!mix||m!=mode||temp!=temperature)return false;
        preparedGeneration=generation;preparedLevels=levels;preparedMix=mix;prepared=true;return true;
    }
    // The caller must establish a successful GPU fence AND the stock's actual
    // postprocess boundary. Queuing a frame, or rendering the last frame, is not it.
    bool commit(uint64_t expectedGeneration){
        if(!valid||!prepared||generation!=expectedGeneration||preparedGeneration!=generation)return false;
        oldLevels=preparedLevels;prepared=false;preparedLevels=0;preparedMix=0;++generation;return true;
    }
    void cancel(){prepared=false;preparedLevels=0;preparedMix=0;}
};

inline constexpr const char*MixShader=R"(#version 310 es
precision highp int;
layout(local_size_x=128)in;
layout(std430,binding=0)readonly buffer Old{uint oldLevels[];};
layout(std430,binding=1)readonly buffer Next{uint newLevels[];};
layout(std430,binding=2)writeonly buffer Mixed{uint mixed[];};
layout(std430,binding=3)writeonly buffer StateOut{uint stateOut[];};
uniform uint operation;uniform bool writeState;uniform uvec4 region;
uint pair(uint a,uint b){return (((a>>1u)<<4u)|(b>>1u))&255u;}
uint resetPair(uint a,uint b){return ((a==29u?240u:(a>>1u)<<4u)|(b==29u?15u:b>>1u))&255u;}
void main(){uint g=gl_GlobalInvocationID.x;if(g>=259200u)return;
uint o=oldLevels[g],n=newLevels[g],merged=0u;
for(uint k=0u;k<4u;k++){uint i=g*4u+k,x=i%1440u,y=i/1440u;
bool inside=x>=region.x&&x<region.z&&y>=region.y&&y<region.w;
merged|=((inside?n:o)>>(k*8u)&255u)<<(k*8u);}
uint a=o&255u,b=(o>>8u)&255u,c=(o>>16u)&255u,d=o>>24u;
uint e=merged&255u,f=(merged>>8u)&255u,h=(merged>>16u)&255u,j=merged>>24u;
if(operation==0u)mixed[g]=pair(a,b)|(pair(e,f)<<8u)|(pair(c,d)<<16u)|(pair(h,j)<<24u);
else if(operation==1u)mixed[g]=255u|(pair(a,b)<<8u)|(255u<<16u)|(pair(c,d)<<24u);
else mixed[g]=resetPair(a,b)|(255u<<8u)|(resetPair(c,d)<<16u)|(255u<<24u);
// A transition's next state is not implied by its mix. Never publish it here.
if(writeState)stateOut[g]=merged;
})";

// Exact stock routines 0x9a140 / 0x9a408 / 0x9a390. Each logical
// pixel has a little-endian u16 index (first input in byte0, second in byte1).
// Reset's stock implementation only writes width bytes per 2*width-byte row;
// its untouched half MUST retain the prior destination contents.
inline constexpr const char*RegalShader=R"(#version 310 es
precision highp int;
layout(local_size_x=128)in;
layout(std430,binding=0)readonly buffer Old{uint oldLevels[];};
layout(std430,binding=1)readonly buffer Next{uint newLevels[];};
layout(std430,binding=2)buffer Mixed{uint mixed[];};
uniform uint operation;
uint oldAt(uint i){return(oldLevels[i>>2u]>>((i&3u)*8u))&255u;}
uint newAt(uint i){return(newLevels[i>>2u]>>((i&3u)*8u))&255u;}
uint pair(uint a,uint b){return (((a>>1u)<<4u)|(b>>1u))&255u;}
uint indexAt(uint i){uint x=i%1440u,y=i/1440u;
if(operation==2u){uint j=y*1440u+x*2u;return pair(oldAt(j),oldAt(j+1u))|65280u;}
// Stock initializes only rows 0 and H-1, then visits x=1..W-2 in
// rows 1..H-2. Preserve HISTORY here, not a fabricated old/new edge.
if(y>0u&&y<719u&&(x==0u||x==1439u))return(mixed[i>>1u]>>((i&1u)*16u))&65535u;
uint o=oldAt(i),n=operation==1u?o:newAt(i);uint lo=operation==1u?30u:o,hi=n;
if(x>0u&&x<1439u&&y>0u&&y<719u&&n==30u&&(operation==1u||o==30u)){
uint ns[4]=uint[4](operation==1u?oldAt(i-1u):newAt(i-1u),operation==1u?oldAt(i-1440u):newAt(i-1440u),
operation==1u?oldAt(i+1u):newAt(i+1u),operation==1u?oldAt(i+1440u):newAt(i+1440u));
uint changed=0u;bool allNewWhite=true,allOldWhite=true;
for(uint k=0u;k<4u;k++){uint j=k==0u?i-1u:k==1u?i-1440u:k==2u?i+1u:i+1440u;
uint v=oldAt(j);changed+=operation==1u?(ns[k]!=30u?1u:0u):(ns[k]!=v?1u:0u);
allNewWhite=allNewWhite&&ns[k]==30u;allOldWhite=allOldWhite&&v==30u;}
if(changed>2u){lo=30u;hi=29u;}
else if(operation==0u&&allNewWhite&&!allOldWhite){lo=30u;hi=31u;}}
return lo|(hi<<8u);}
void main(){uint g=gl_GlobalInvocationID.x;if(g>=259200u)return;uint i=g*4u;
if(operation==2u&&(i%1440u)>=720u)return;
mixed[g*2u]=indexAt(i)|(indexAt(i+1u)<<16u);
mixed[g*2u+1u]=indexAt(i+2u)|(indexAt(i+3u)<<16u);
})";

inline GLuint linkShader(GLenum stage,const char*source){
    GLuint shader=glCreateShader(stage),p=0;
    try{
        if(!shader)throw std::runtime_error("wave shader allocation");
        glShaderSource(shader,1,&source,nullptr);glCompileShader(shader);GLint ok=0;
        glGetShaderiv(shader,GL_COMPILE_STATUS,&ok);
        if(!ok){char log[2048]={};glGetShaderInfoLog(shader,sizeof(log),nullptr,log);throw std::runtime_error(log);}
        p=glCreateProgram();if(!p)throw std::runtime_error("wave program allocation");
        glAttachShader(p,shader);glLinkProgram(p);glGetProgramiv(p,GL_LINK_STATUS,&ok);
        if(!ok){char log[2048]={};glGetProgramInfoLog(p,sizeof(log),nullptr,log);throw std::runtime_error(log);}
        glDeleteShader(shader);return p;
    }catch(...){if(p)glDeleteProgram(p);if(shader)glDeleteShader(shader);throw;}
}
inline void check(const char*where){auto e=glGetError();if(e)throw std::runtime_error(std::string(where)+" GL="+std::to_string(e));}
inline bool storage(GLuint b,size_t minimum){
    if(!b||!glIsBuffer(b))return false;
    glBindBuffer(GL_SHADER_STORAGE_BUFFER,b);GLint64 size=0;
    glGetBufferParameteri64v(GL_SHADER_STORAGE_BUFFER,GL_BUFFER_SIZE,&size);
    return size>=static_cast<GLint64>(minimum);
}
class Mixer {
    GLuint program_=0,regalProgram_=0,dummy_=0;
public:
    Mixer()=default;Mixer(const Mixer&)=delete;Mixer&operator=(const Mixer&)=delete;
    // Context-bound objects: call release explicitly on their owning GL thread.
    void release(){if(program_)glDeleteProgram(program_);if(regalProgram_)glDeleteProgram(regalProgram_);
        if(dummy_)glDeleteBuffers(1,&dummy_);program_=regalProgram_=dummy_=0;}
    void dispatchRegal(const RegalRequest&r){
        if(uint32_t(r.operation)>2u||r.mixed==r.oldLevels||r.mixed==r.newLevels||
           !storage(r.oldLevels,LevelBytes)||!storage(r.mixed,LevelBytes*2)||
           (r.operation==RegalOperation::Normal&&!storage(r.newLevels,LevelBytes)))
            throw std::runtime_error("invalid, aliased, or undersized REGAL request");
        if(!regalProgram_)regalProgram_=linkShader(GL_COMPUTE_SHADER,RegalShader);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER,0,r.oldLevels);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER,1,r.operation==RegalOperation::Normal?r.newLevels:r.oldLevels);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER,2,r.mixed);glUseProgram(regalProgram_);
        glUniform1ui(glGetUniformLocation(regalProgram_,"operation"),uint32_t(r.operation));
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT|GL_BUFFER_UPDATE_BARRIER_BIT);
        glDispatchCompute((Width*Height/4+127)/128,1,1);
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT|GL_BUFFER_UPDATE_BARRIER_BIT);check("GPU REGAL mix dispatch");
    }
    void dispatch(const MixRequest&r){
        if(!validRect(r.region)||uint32_t(r.operation)>2u||r.mixed==r.oldLevels||r.mixed==r.newLevels||
           (r.nextState&&(r.operation!=MixOperation::LevelsY4||r.nextState==r.mixed||r.nextState==r.oldLevels||r.nextState==r.newLevels)))
            throw std::runtime_error("invalid or aliased GPU mix request");
        if(!storage(r.oldLevels,LevelBytes)||!storage(r.mixed,LevelBytes)||
           (r.operation==MixOperation::LevelsY4&&!storage(r.newLevels,LevelBytes))||
           (r.nextState&&!storage(r.nextState,LevelBytes)))throw std::runtime_error("GPU mix buffer capacity");
        // Partial transitions require a different stock state machine, reject.
        if(r.operation!=MixOperation::LevelsY4&&(r.region.left||r.region.top||r.region.right!=Width||r.region.bottom!=Height))
            throw std::runtime_error("partial transition mix is not verified");
        if(!program_){
            try{program_=linkShader(GL_COMPUTE_SHADER,MixShader);glGenBuffers(1,&dummy_);
                uint32_t zero=0;glBindBuffer(GL_SHADER_STORAGE_BUFFER,dummy_);glBufferData(GL_SHADER_STORAGE_BUFFER,4,&zero,GL_STATIC_DRAW);check("mix setup");}
            catch(...){release();throw;}
        }
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER,0,r.oldLevels);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER,1,r.operation==MixOperation::LevelsY4?r.newLevels:r.oldLevels);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER,2,r.mixed);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER,3,r.nextState?r.nextState:dummy_);
        glUseProgram(program_);glUniform1ui(glGetUniformLocation(program_,"operation"),uint32_t(r.operation));
        glUniform1i(glGetUniformLocation(program_,"writeState"),r.nextState!=0);
        glUniform4ui(glGetUniformLocation(program_,"region"),r.region.left,r.region.top,r.region.right,r.region.bottom);
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT|GL_BUFFER_UPDATE_BARRIER_BIT);
        glDispatchCompute((Width*Height/4+127)/128,1,1);
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT|GL_BUFFER_UPDATE_BARRIER_BIT);check("GPU mix dispatch");
    }
};

// Fragment shader is shared by the compatible complete-wave path and the new
// rolling path. LUT entries are STOCK live 64KiB tables, never WBF substitutes.
inline constexpr const char*FrameShader=R"(#version 310 es
precision highp float;precision highp int;
layout(std430,binding=0)readonly buffer Mix{uint mixPixels[];};
layout(std430,binding=1)readonly buffer Lut{uint lut[];};
layout(std430,binding=2)readonly buffer Template{uint templ[];};
layout(std430,binding=3)readonly buffer SMap{uint smap[];};
layout(location=0)out vec4 color;
uniform uint frameNumber;uniform bool neutral;uniform bool motion;uniform uint activeFrames;
uniform uint indexLayout;uniform uvec4 activeRect;uniform uint bandDirection;uniform uint bandFrames;uniform uint bandRows;uniform uint bandTail;uniform uint bandStyle;
uniform bool overlay12;uniform bool maskRegion;uniform bool twoRegions;uniform uvec4 secondRect;
bool inGroups(uvec4 r,uint x,uint row){uint begin=r.x>>2u,count=((r.z>>1u)-(r.x>>1u)+1u)>>1u;
return x>=begin&&x<begin+count&&row>=r.y&&row<r.w;}
uint lookup(uint k,uint f){if(f>=activeFrames)return 0u;k+=f*65536u;return(lut[k>>2u]>>((k&3u)*8u))&255u;}
uint late(uint k,uint s){if(frameNumber<s)return 0u;return lookup(k,frameNumber-s);}
void main(){uint x=uint(gl_FragCoord.x),y=uint(gl_FragCoord.y),p=y*384u+x,t=templ[p];
if(y>=5u&&x<360u){uint row=y-5u,g=row*360u+x,data=0u,f=frameNumber;bool drive=true;
if(bandDirection!=0u){
// 分带揭示：每行按 bandRows 划带，delay 压缩到波形前 1/3，保证任何带都在
// 末帧之前启动并有足够帧数走完 LUT。bandFrames 由 validWave 约束，不再作上界。
uint rows=bandRows>0u?bandRows:48u,steps=719u/rows+1u;
// pageanim.style：0 横向 / 1 纵向 / 2 斜向 / 3 反斜向 / 4 环绕 / 5 中心。
// 把二维坐标折成一维「揭示次序」，再交给下面的逐带延迟；行坐标 0..719，
// 列坐标 0..359（每 4 个像素一组），统一折到 0..719 区间。
// 注意轴：row（y-5）是屏幕的横向轴（720 宽，2 像素一组），x 是纵向轴（1440 高，
// 4 像素一组）。所以 0 横向＝竖条从左向右推，1 纵向＝横条从上向下推。
uint coord=row;
if(bandStyle==1u)coord=x*2u;
else if(bandStyle==2u)coord=(row+x*2u)/2u;
else if(bandStyle==3u)coord=(719u-row+x*2u)/2u;
else if(bandStyle==4u||bandStyle==5u){
uint dr=row>359u?row-359u:359u-row,dc=x>179u?(x-179u)*2u:(179u-x)*2u;
coord=(dr+dc)/2u;
if(coord>359u)coord=359u;
if(bandStyle==4u)coord=359u-coord;}
if(coord>719u)coord=719u;
if(bandDirection==2u)coord=719u-coord;
// bandTail 是「整条波形为分带错峰额外延长出来的帧数」。每带仍然是
// f = frameNumber - delay、从 LUT 第 0 帧一直走到第 activeFrames-1 帧，
// 一条不落地跑完整条波形 —— 这是「刷新干净」的关键：
// 只错峰启动、不延长总帧数，后启动的带会被截掉尾部收敛帧，像素停在
// 中间灰阶，旧页就留下浅灰残影。
uint bandIndex=coord/rows;
if(bandIndex>=steps)bandIndex=steps-1u;
uint delay=steps>1u?bandIndex*bandTail/(steps-1u):0u;
if(delay>bandTail)delay=bandTail;
f=frameNumber>delay?frameNumber-delay:0u;
// 走完 LUT 之后 lookup() 自然返回 0（中性，保持像素），不需要再截断。
drive=frameNumber>=delay;}
if(neutral)data=0u;
// 未揭示的区域必须输出中性驱动字节（保持当前画面），不能把控制模板的原始
// 驱动字节送出去：面板级 forceClearGhosting 不产生新合成帧，会把那个中间态
// 直接刷到屏幕上定格成大面积黑底白字（智墨键单击=刷新，连按两次尤其容易命中）。
else if(!drive)data=0u;
else if(indexLayout==0u){uint m=mixPixels[g],lo=m&65535u,hi=m>>16u;
if(!motion)data=(lookup(lo,f)<<4u)|lookup(hi,f);
else{uint s=smap[g];data=(((late(lo,s&255u)>>2u)&3u)<<6u)|((late(lo,(s>>8u)&255u)&3u)<<4u)|
(((late(hi,(s>>16u)&255u)>>2u)&3u)<<2u)|(late(hi,s>>24u)&3u);}}
else{uint a=mixPixels[g*2u],b=mixPixels[g*2u+1u];
uint d0=lookup(a&65535u,f),d1=lookup(a>>16u,f),d2=lookup(b&65535u,f),d3=lookup(b>>16u,f);
if(indexLayout==1u){if(x==0u)d0=d2;if(x==359u)d3=d2;}
if(motion){uint s=smap[g];
uint k0=indexLayout==1u&&x==0u?b&65535u:a&65535u;
uint k3=indexLayout==1u&&x==359u?b&65535u:b>>16u;
d0=late(k0,s&255u);d1=late(a>>16u,(s>>8u)&255u);
d2=late(b&65535u,(s>>16u)&255u);d3=late(k3,s>>24u);}
data=(d0<<6u)|(d1<<4u)|(d2<<2u)|d3;}
if(maskRegion&&overlay12&&!neutral){
// Inline stock region loops start at floor(left/4), advance four pixels
// while (left/2 + 2*n) < floor(right/2), and OR a complete drive byte.
// Outside that group range the entire template word remains untouched.
if(!inGroups(activeRect,x,row)&&!(twoRegions&&inGroups(secondRect,x,row))){
color=vec4(float((t>>16u)&255u),float((t>>8u)&255u),float(t&255u),float(t>>24u))/255.;return;}
}
else if(maskRegion&&!neutral){for(uint k=0u;k<4u;k++){uint px=x*4u+k;
if(px<activeRect.x||px>=activeRect.z||row<activeRect.y||row>=activeRect.w)data&=~(3u<<((3u-k)*2u));}}
data&=255u;if(overlay12&&!neutral)t|=data|(data<<12u);else t=(t&0xffffff00u)|data;}
color=vec4(float((t>>16u)&255u),float((t>>8u)&255u),float(t&255u),float(t>>24u))/255.;
})";

struct WaveRequest {
    GLuint gpuIndices=0;const uint8_t*cpuIndices=nullptr;size_t indexBytes=0;
    const uint8_t*liveLut=nullptr;size_t lutBytes=0;GLuint gpuLut=0;
    const uint32_t*controlTemplate=nullptr;size_t templateBytes=0;
    GLuint gpuStartMap=0;const uint8_t*cpuStartMap=nullptr;size_t startMapBytes=0;
    uint32_t activeFrames=0,totalFrames=0,maxDelay=0;
    // bandTail：为分带错峰额外延长的帧数。每带从 LUT 第 0 帧走满 activeFrames 帧，
    // 所以总帧数必须是 activeFrames + bandTail + 1，否则后启动的带会被截尾。
    uint32_t bandDirection=0,bandFrames=0,bandRows=48,bandTail=0,bandStyle=0;
    IndexLayout layout=IndexLayout::PairY4;Rect region{},secondRegion{};
    bool maskRegion=false,overlay12=false,gpuIndexValidated=false,neutralOnly=false,twoRegions=false;
    uint64_t wave=0,stateGeneration=0;
};
inline bool validWave(const WaveRequest&r){
    size_t need=r.neutralOnly?4:r.layout==IndexLayout::PairY4?LevelBytes:LevelBytes*2;
    bool start=r.gpuStartMap||r.cpuStartMap;
    bool frames=r.neutralOnly?(r.activeFrames==0&&r.totalFrames>0&&r.totalFrames<=MaxLiveFrames+1u&&
                              !r.maxDelay&&!start&&!r.bandDirection&&r.layout==IndexLayout::PairY4):
                             (r.activeFrames>0&&r.activeFrames<=MaxLiveFrames&&
                              r.totalFrames==r.activeFrames+r.maxDelay+(r.bandDirection?r.bandTail:0u)+1u);
    return uint32_t(r.layout)<=2u&&validRect(r.region)&&frames&&r.maxDelay<=255u&&r.bandDirection<=2u&&
        r.bandRows>=4u&&r.bandRows<=720u&&r.bandTail<=24u&&r.bandStyle<=5u&&
        r.liveLut&&r.lutBytes>=(r.neutralOnly?4:size_t(r.activeFrames)*65536u)&&r.controlTemplate&&r.templateBytes>=FrameBytes&&
        ((r.gpuIndices!=0)!=(r.cpuIndices!=nullptr))&&r.indexBytes>=need&&
        ((r.gpuStartMap!=0)!=(r.cpuStartMap!=nullptr)||!start)&&
        (!r.maxDelay||start)&&(!start||r.startMapBytes>=LevelBytes)&&
        (!start||((r.layout==IndexLayout::PairY4||r.layout==IndexLayout::PixelY8Edge)&&r.bandDirection==0))&&
        (!r.bandDirection||(!r.maxDelay&&r.bandFrames<r.activeFrames))&&
        (!r.overlay12||(r.maskRegion&&!start&&!r.bandDirection&&r.layout==IndexLayout::PixelY8))&&
        (!r.twoRegions||(r.maskRegion&&r.overlay12&&validRect(r.secondRegion)));
}
} // namespace einkwave

using GpuMixRequest=einkwave::MixRequest;
using GpuRegalMixRequest=einkwave::RegalRequest;
using GpuWaveRequest=einkwave::WaveRequest;
using GpuWaveState=einkwave::State;
