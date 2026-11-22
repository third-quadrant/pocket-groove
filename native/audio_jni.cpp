#include "mixer.h"
#include "portaudio.h"
#include "pa_win_wasapi.h"
#include <jni.h>
#include <windows.h>
#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <memory>
#include <mutex>
#include <sstream>
#include <string>
#include <thread>
#include <unordered_map>

namespace {
using namespace groove;
struct Engine {
    explicit Engine(double rate):mixer(rate){}
    ~Engine(){if(stream){Pa_AbortStream(stream);Pa_CloseStream(stream);}}
    Mixer mixer;
    PaStream* stream=nullptr;
    std::atomic<uint64_t> sequence{0},underruns{0};
    std::atomic<double> maxCallbackSeconds{0};
    double outputLatency=0;
    int frames=0;
    int device=-1;
    bool exclusive=false;
    std::string description;
};
std::mutex registryMutex;
std::unordered_map<jlong,std::shared_ptr<Engine>> registry;
std::atomic<jlong> nextHandle{1};

class PortAudio {
public:
    PortAudio(){error=Pa_Initialize();}
    ~PortAudio(){if(error==paNoError)Pa_Terminate();}
    PaError error;
};
PortAudio& audio(){static PortAudio instance;return instance;}
void throwJava(JNIEnv* env,const char* type,const std::string& message){
    if(auto c=env->FindClass(type))env->ThrowNew(c,message.c_str());
}
void throwState(JNIEnv* e,const std::string&m){throwJava(e,"java/lang/IllegalStateException",m);}
void throwArgument(JNIEnv* e,const std::string&m){throwJava(e,"java/lang/IllegalArgumentException",m);}
std::shared_ptr<Engine> acquire(JNIEnv* env,jlong handle){
    std::lock_guard<std::mutex> lock(registryMutex);
    auto it=registry.find(handle);
    if(handle==0||it==registry.end()){throwState(env,"오디오 엔진이 닫혔습니다.");return {};}
    return it->second;
}
int callback(const void*,void* output,unsigned long frames,const PaStreamCallbackTimeInfo*,
             PaStreamCallbackFlags flags,void* user) noexcept {
    auto* e=static_cast<Engine*>(user);
    if(flags&paOutputUnderflow)e->underruns.fetch_add(1,std::memory_order_relaxed);
    auto start=std::chrono::steady_clock::now();
    e->mixer.render(static_cast<float*>(output),frames);
    double elapsed=std::chrono::duration<double>(std::chrono::steady_clock::now()-start).count();
    double old=e->maxCallbackSeconds.load(std::memory_order_relaxed);
    while(old<elapsed&&!e->maxCallbackSeconds.compare_exchange_weak(old,elapsed,std::memory_order_relaxed)){}
    return paContinue;
}
PaError openStream(Engine& e,int device,bool exclusive,int frames){
    auto* info=Pa_GetDeviceInfo(device);if(!info||info->maxOutputChannels<2)return paInvalidDevice;
    auto* host=Pa_GetHostApiInfo(info->hostApi);if(!host||host->type!=paWASAPI)return paIncompatibleHostApiSpecificStreamInfo;
    PaStreamParameters out{};out.device=device;out.channelCount=2;out.sampleFormat=paFloat32;out.suggestedLatency=info->defaultLowOutputLatency;
    PaWasapiStreamInfo wasapi{};wasapi.size=sizeof(wasapi);wasapi.hostApiType=paWASAPI;wasapi.version=1;
    if(exclusive){wasapi.flags=paWinWasapiExclusive|paWinWasapiThreadPriority;wasapi.threadPriority=eThreadPriorityProAudio;out.hostApiSpecificStreamInfo=&wasapi;}
    auto err=Pa_OpenStream(&e.stream,nullptr,&out,48000,static_cast<unsigned long>(frames),paClipOff,callback,&e);
    if(err!=paNoError)return err;
    auto* stream=Pa_GetStreamInfo(e.stream);e.outputLatency=stream?stream->outputLatency:0;
    err=Pa_StartStream(e.stream);
    if(err!=paNoError){Pa_CloseStream(e.stream);e.stream=nullptr;}
    return err;
}
std::string describe(int device,bool exclusive,int frames){
    auto* info=Pa_GetDeviceInfo(device);auto* host=info?Pa_GetHostApiInfo(info->hostApi):nullptr;
    std::ostringstream s;s<<(host?host->name:"WASAPI")<<" · "<<(info?info->name:"출력 장치")
        <<" · 48 kHz · "<<frames<<" samples · "<<(exclusive?"단독":"공유");return s.str();
}
bool validTrack(double track,size_t count){return track>=0 && static_cast<size_t>(track)<count;}
bool validArrays(JNIEnv* env,jintArray tracks,jdoubleArray beats,jdoubleArray velocities,
                 jdoubleArray pitches,jdouble length,size_t trackCount){
    if(!tracks||!beats||!velocities||!pitches)return false;
    auto count=env->GetArrayLength(tracks);
    if(count<0||env->GetArrayLength(beats)!=count||env->GetArrayLength(velocities)!=count
       ||env->GetArrayLength(pitches)!=count
       ||count>static_cast<jsize>(MaxHits)||!std::isfinite(length)||length<=0||length>512)return false;
    jint* t=env->GetIntArrayElements(tracks,nullptr);jdouble* b=env->GetDoubleArrayElements(beats,nullptr);
    jdouble* v=env->GetDoubleArrayElements(velocities,nullptr);jdouble* p=env->GetDoubleArrayElements(pitches,nullptr);
    if(!t||!b||!v||!p){
        if(t)env->ReleaseIntArrayElements(tracks,t,JNI_ABORT);
        if(b)env->ReleaseDoubleArrayElements(beats,b,JNI_ABORT);
        if(v)env->ReleaseDoubleArrayElements(velocities,v,JNI_ABORT);
        if(p)env->ReleaseDoubleArrayElements(pitches,p,JNI_ABORT);
        return false;
    }
    bool ok=true;
    for(jsize i=0;i<count;i++)if(t[i]<0||static_cast<size_t>(t[i])>=trackCount||!std::isfinite(b[i])||b[i]<0||b[i]>=length
        ||!std::isfinite(v[i])||v[i]<0.05||v[i]>1
        ||!std::isfinite(p[i])||p[i]< -24||p[i]>24){ok=false;break;}
    env->ReleaseIntArrayElements(tracks,t,JNI_ABORT);
    env->ReleaseDoubleArrayElements(beats,b,JNI_ABORT);
    env->ReleaseDoubleArrayElements(velocities,v,JNI_ABORT);
    env->ReleaseDoubleArrayElements(pitches,p,JNI_ABORT);
    return ok;
}
bool stopStream(JNIEnv* env,Engine& e){
    auto stop=Pa_StopStream(e.stream);
    if(stop!=paNoError&&stop!=paStreamIsStopped){throwJava(env,"java/io/IOException",Pa_GetErrorText(stop));return false;}
    return true;
}
std::vector<std::pair<int,std::vector<PitchVariant>>> variantEntries(
        std::array<std::vector<PitchVariant>,MaxTracks>&& variants){
    std::vector<std::pair<int,std::vector<PitchVariant>>> entries;
    for(size_t i=0;i<MaxTracks;i++){
        if(!variants[i].empty()) entries.emplace_back(static_cast<int>(i),std::move(variants[i]));
    }
    return entries;
}
bool readVariants(JNIEnv* env,jintArray variantTracks,jintArray variantTenths,jobjectArray variantArrays,
                  size_t trackCount,std::array<std::vector<PitchVariant>,MaxTracks>& prepared,size_t& totalBytes){
    if(!variantTracks||!variantTenths||!variantArrays)return false;
    auto count=env->GetArrayLength(variantTracks);
    if(count<0||env->GetArrayLength(variantTenths)!=count||env->GetArrayLength(variantArrays)!=count)return false;
    jint* tracks=env->GetIntArrayElements(variantTracks,nullptr);
    jint* tenths=env->GetIntArrayElements(variantTenths,nullptr);
    if(!tracks||!tenths){
        if(tracks)env->ReleaseIntArrayElements(variantTracks,tracks,JNI_ABORT);
        if(tenths)env->ReleaseIntArrayElements(variantTenths,tenths,JNI_ABORT);
        return false;
    }
    bool ok=true;
    for(jsize i=0;i<count&&ok;i++){
        if(tracks[i]<0||static_cast<size_t>(tracks[i])>=trackCount
           ||tenths[i]< -480||tenths[i]>480){ok=false;break;}
        auto& list=prepared[tracks[i]];
        for(const auto& existing:list)if(existing.tenth==tenths[i]){ok=false;break;}
        if(!ok)break;
        auto arr=static_cast<jfloatArray>(env->GetObjectArrayElement(variantArrays,i));
        if(!arr){ok=false;break;}
        auto size=env->GetArrayLength(arr);
        if(size<=0||size%2||static_cast<size_t>(size)>48*1024*1024){
            env->DeleteLocalRef(arr);ok=false;break;
        }
        totalBytes+=static_cast<size_t>(size)*sizeof(float);
        if(totalBytes>512u*1024u*1024u){env->DeleteLocalRef(arr);ok=false;break;}
        PitchVariant variant;
        variant.tenth=tenths[i];
        variant.pcm.resize(static_cast<size_t>(size));
        env->GetFloatArrayRegion(arr,0,size,variant.pcm.data());
        env->DeleteLocalRef(arr);
        if(env->ExceptionCheck()){ok=false;break;}
        for(float v:variant.pcm)if(!std::isfinite(v)){ok=false;break;}
        if(ok)list.push_back(std::move(variant));
    }
    env->ReleaseIntArrayElements(variantTracks,tracks,JNI_ABORT);
    env->ReleaseIntArrayElements(variantTenths,tenths,JNI_ABORT);
    return ok;
}
void restartStream(JNIEnv* env,Engine& e){
    auto start=Pa_StartStream(e.stream);
    if(start!=paNoError)throwJava(env,"java/io/IOException",Pa_GetErrorText(start));
}
}

extern "C" JNIEXPORT jstring JNICALL Java_io_pocketgroove_AudioEngine_nDescription(JNIEnv* env,jclass,jlong handle){
    auto e=acquire(env,handle);if(!e)return nullptr;
    return env->NewStringUTF(e->description.c_str());
}

extern "C" JNIEXPORT jobjectArray JNICALL Java_io_pocketgroove_AudioEngine_nDevices(JNIEnv* env,jclass) try {
    if(audio().error!=paNoError){throwJava(env,"java/io/IOException",Pa_GetErrorText(audio().error));return nullptr;}
    bool hasWasapi=false;
    for(int i=0;i<Pa_GetHostApiCount();i++){auto* h=Pa_GetHostApiInfo(i);if(h&&h->type==paWASAPI){hasWasapi=true;break;}}
    if(!hasWasapi){throwJava(env,"java/io/IOException","이 Windows PC에서 WASAPI 오디오 출력을 찾지 못했습니다.");return nullptr;}
    std::vector<std::pair<int,std::string>> devices;int def=Pa_GetDefaultOutputDevice();
    for(int i=0;i<Pa_GetDeviceCount();i++){
        auto* d=Pa_GetDeviceInfo(i);if(!d||d->maxOutputChannels<2)continue;
        auto* h=Pa_GetHostApiInfo(d->hostApi);if(!h||h->type!=paWASAPI)continue;
        devices.emplace_back(i,std::string(d->name)+(i==def?" (기본 장치)":""));
    }
    jclass str=env->FindClass("java/lang/String");auto result=env->NewObjectArray(static_cast<jsize>(devices.size()),str,nullptr);
    for(size_t i=0;i<devices.size();i++){auto entry=std::to_string(devices[i].first)+"\t"+devices[i].second;auto s=env->NewStringUTF(entry.c_str());env->SetObjectArrayElement(result,static_cast<jsize>(i),s);env->DeleteLocalRef(s);}
    return result;
} catch(const std::bad_alloc&){throwJava(env,"java/lang/OutOfMemoryError","오디오 장치 목록을 준비할 메모리가 부족합니다.");return nullptr;}
  catch(const std::exception& e){throwJava(env,"java/io/IOException",e.what());return nullptr;}
  catch(...){throwJava(env,"java/io/IOException","오디오 장치 목록을 준비하지 못했습니다.");return nullptr;}

extern "C" JNIEXPORT jlong JNICALL Java_io_pocketgroove_AudioEngine_nOpen(JNIEnv* env,jclass,jint device,jboolean exclusive,jint frames) try {
    if(audio().error!=paNoError){throwJava(env,"java/io/IOException",Pa_GetErrorText(audio().error));return 0;}
    if(device < -1||!(frames==64||frames==128||frames==256||frames==512)){throwArgument(env,"잘못된 오디오 출력 설정입니다.");return 0;}
    int chosen=device<0?Pa_GetDefaultOutputDevice():device;
    auto* info=Pa_GetDeviceInfo(chosen);
    if(!info||info->maxOutputChannels<2){throwJava(env,"java/io/IOException","선택한 출력 장치를 사용할 수 없습니다.");return 0;}
    auto* host=Pa_GetHostApiInfo(info->hostApi);
    if(!host||host->type!=paWASAPI){throwArgument(env,"WASAPI 출력 장치만 선택할 수 있습니다.");return 0;}
    auto engine=std::make_shared<Engine>(48000);engine->frames=frames;
    auto result=openStream(*engine,chosen,exclusive,frames);
    if(result!=paNoError){throwJava(env,"java/io/IOException",std::string(exclusive?"빠른 단독 출력 모드를 열지 못했습니다: ":"공유 출력 모드를 열지 못했습니다: ")+Pa_GetErrorText(result));return 0;}
    engine->description=describe(chosen,exclusive,frames);engine->device=chosen;engine->exclusive=exclusive;
    auto handle=nextHandle.fetch_add(1);
    {std::lock_guard<std::mutex> lock(registryMutex);registry.emplace(handle,engine);}
    return handle;
} catch(const std::bad_alloc&){throwJava(env,"java/lang/OutOfMemoryError","오디오 장치를 준비할 메모리가 부족합니다.");return 0;}
  catch(const std::exception& e){throwJava(env,"java/io/IOException",e.what());return 0;}
  catch(...){throwJava(env,"java/io/IOException","오디오 장치를 열지 못했습니다.");return 0;}

extern "C" JNIEXPORT void JNICALL Java_io_pocketgroove_AudioEngine_nReconfigure(JNIEnv* env,jclass,jlong handle,jint device,jboolean exclusive,jint frames) try {
    auto e=acquire(env,handle);if(!e)return;
    if(device < -1||!(frames==64||frames==128||frames==256||frames==512)){throwArgument(env,"잘못된 오디오 출력 설정입니다.");return;}
    int chosen=device<0?Pa_GetDefaultOutputDevice():device;
    int oldDevice=e->device,oldFrames=e->frames;bool oldExclusive=e->exclusive;
    if(!stopStream(env,*e))return;
    Pa_CloseStream(e->stream);e->stream=nullptr;
    auto result=openStream(*e,chosen,exclusive,frames);
    if(result==paNoError){e->device=chosen;e->exclusive=exclusive;e->frames=frames;e->description=describe(chosen,exclusive,frames);return;}
    auto requestedError=std::string(Pa_GetErrorText(result));
    auto restored=openStream(*e,oldDevice,oldExclusive,oldFrames);
    if(restored==paNoError){e->device=oldDevice;e->exclusive=oldExclusive;e->frames=oldFrames;e->description=describe(oldDevice,oldExclusive,oldFrames);
        throwJava(env,"java/io/IOException","새 출력 설정을 적용하지 못했습니다. 이전 설정으로 복구했습니다: "+requestedError);return;}
    throwJava(env,"java/io/IOException","출력 설정 변경과 이전 설정 복구 모두 실패했습니다: "+requestedError+"; "+Pa_GetErrorText(restored));
} catch(const std::bad_alloc&){throwJava(env,"java/lang/OutOfMemoryError","출력 설정을 변경할 메모리가 부족합니다.");}
  catch(const std::exception& e){throwJava(env,"java/io/IOException",e.what());}
  catch(...){throwJava(env,"java/io/IOException","오디오 출력을 변경하지 못했습니다.");}

extern "C" JNIEXPORT void JNICALL Java_io_pocketgroove_AudioEngine_nInstall(
    JNIEnv* env,jclass,jlong handle,jobjectArray sampleArrays,jintArray rates,jdoubleArray trackPitches,
    jbooleanArray pitchShiftModes,jintArray tracks,jdoubleArray beats,jdoubleArray velocities,
    jdoubleArray hitPitches,jdouble length,jdouble bpm,
    jintArray variantTracks,jintArray variantTenths,jobjectArray variantArrays) try {
    auto e=acquire(env,handle);if(!e)return;
    if(!sampleArrays||!rates||!trackPitches||!pitchShiftModes
       ||env->GetArrayLength(sampleArrays)!=env->GetArrayLength(rates)
       ||env->GetArrayLength(trackPitches)!=env->GetArrayLength(rates)
       ||env->GetArrayLength(pitchShiftModes)!=env->GetArrayLength(rates)){
        throwArgument(env,"트랙 수, 샘플 수, 피치 수 또는 피치 방식이 다릅니다.");return;
    }
    auto trackCount=env->GetArrayLength(sampleArrays);
    if(trackCount<1||trackCount>static_cast<jsize>(MaxTracks)||!std::isfinite(bpm)||bpm<40||bpm>240){throwArgument(env,"트랙 수 또는 템포가 올바르지 않습니다.");return;}
    if(!validArrays(env,tracks,beats,velocities,hitPitches,length,static_cast<size_t>(trackCount))){throwArgument(env,"비트에 잘못된 트랙, 타격, 세기 또는 음높이가 있습니다.");return;}

    jint rateValues[MaxTracks]{};env->GetIntArrayRegion(rates,0,trackCount,rateValues);if(env->ExceptionCheck())return;
    jdouble pitchValues[MaxTracks]{};env->GetDoubleArrayRegion(trackPitches,0,trackCount,pitchValues);if(env->ExceptionCheck())return;
    jboolean modeValues[MaxTracks]{};env->GetBooleanArrayRegion(pitchShiftModes,0,trackCount,modeValues);if(env->ExceptionCheck())return;
    std::array<Sample,MaxTracks> prepared;size_t total=0;
    for(int i=0;i<trackCount;i++){
        if(!std::isfinite(pitchValues[i])||pitchValues[i]< -12||pitchValues[i]>12){
            throwArgument(env,"트랙 피치가 올바르지 않습니다.");return;
        }
        prepared[i].pitch=pitchValues[i];
        prepared[i].pitchShift=modeValues[i]!=0;
        auto arr=static_cast<jfloatArray>(env->GetObjectArrayElement(sampleArrays,i));
        if(!arr){throwArgument(env,"누락된 드럼 소리가 있습니다.");return;}
        auto size=env->GetArrayLength(arr);
        if(size<=0||size%2||size>32*1024*1024||rateValues[i]<8000||rateValues[i]>192000){
            env->DeleteLocalRef(arr);throwArgument(env,"PCM 소리 크기 또는 샘플레이트가 올바르지 않습니다.");return;
        }
        total+=static_cast<size_t>(size);
        if(total>48*1024*1024){env->DeleteLocalRef(arr);throwArgument(env,"드럼 소리 전체가 너무 큽니다.");return;}
        prepared[i].pcm.resize(static_cast<size_t>(size));prepared[i].rate=rateValues[i];
        env->GetFloatArrayRegion(arr,0,size,prepared[i].pcm.data());env->DeleteLocalRef(arr);
        if(env->ExceptionCheck())return;
        for(float v:prepared[i].pcm)if(!std::isfinite(v)){throwArgument(env,"PCM 안에 잘못된 숫자가 있습니다.");return;}
    }
    auto count=env->GetArrayLength(tracks);
    jint* trackValues=env->GetIntArrayElements(tracks,nullptr);if(!trackValues)return;
    jdouble* beatValues=env->GetDoubleArrayElements(beats,nullptr);if(!beatValues){env->ReleaseIntArrayElements(tracks,trackValues,JNI_ABORT);return;}
    jdouble* velocityValues=env->GetDoubleArrayElements(velocities,nullptr);
    jdouble* hitPitchValues=env->GetDoubleArrayElements(hitPitches,nullptr);
    if(!velocityValues||!hitPitchValues){
        env->ReleaseIntArrayElements(tracks,trackValues,JNI_ABORT);
        env->ReleaseDoubleArrayElements(beats,beatValues,JNI_ABORT);
        if(velocityValues)env->ReleaseDoubleArrayElements(velocities,velocityValues,JNI_ABORT);
        if(hitPitchValues)env->ReleaseDoubleArrayElements(hitPitches,hitPitchValues,JNI_ABORT);
        return;
    }
    std::vector<Hit> hits;hits.reserve(static_cast<size_t>(count));
    for(jsize i=0;i<count;i++)hits.push_back({trackValues[i],beatValues[i],velocityValues[i],hitPitchValues[i]});
    env->ReleaseIntArrayElements(tracks,trackValues,JNI_ABORT);
    env->ReleaseDoubleArrayElements(beats,beatValues,JNI_ABORT);
    env->ReleaseDoubleArrayElements(velocities,velocityValues,JNI_ABORT);
    env->ReleaseDoubleArrayElements(hitPitches,hitPitchValues,JNI_ABORT);

    std::array<std::vector<PitchVariant>,MaxTracks> preparedVariants;size_t variantBytes=0;
    if(!readVariants(env,variantTracks,variantTenths,variantArrays,static_cast<size_t>(trackCount),
                     preparedVariants,variantBytes)){
        throwArgument(env,"피치 PCM 또는 음높이가 올바르지 않습니다.");return;
    }

    if(!stopStream(env,*e))return;
    try{
        e->mixer.setTrackCount(static_cast<size_t>(trackCount));
        for(int i=0;i<trackCount;i++)e->mixer.samples[i]=std::move(prepared[i]);
        e->mixer.setVariants(variantEntries(std::move(preparedVariants)));
        e->mixer.setLoop(hits,length,bpm);
    } catch(...) {
        Pa_StartStream(e->stream);
        throwArgument(env,"샘플 또는 반복 길이가 올바르지 않습니다.");return;
    }
    restartStream(env,*e);
} catch(const std::bad_alloc&){throwJava(env,"java/lang/OutOfMemoryError","오디오 샘플을 준비할 메모리가 부족합니다.");}
  catch(const std::exception& e){throwJava(env,"java/io/IOException",e.what());}
  catch(...){throwJava(env,"java/io/IOException","오디오 샘플을 설치하지 못했습니다.");}

extern "C" JNIEXPORT void JNICALL Java_io_pocketgroove_AudioEngine_nSetLoop(
    JNIEnv* env,jclass,jlong handle,jintArray tracks,jdoubleArray beats,jdoubleArray velocities,
    jdoubleArray hitPitches,jdouble length,jdouble bpm) try {
    auto e=acquire(env,handle);if(!e)return;
    if(!validArrays(env,tracks,beats,velocities,hitPitches,length,e->mixer.trackCount())
       ||!std::isfinite(bpm)||bpm<40||bpm>240){throwArgument(env,"비트에 잘못된 트랙, 타격, 세기, 음높이 또는 템포가 있습니다.");return;}
    auto count=env->GetArrayLength(tracks);
    jint* t=env->GetIntArrayElements(tracks,nullptr);jdouble* b=env->GetDoubleArrayElements(beats,nullptr);
    jdouble* v=env->GetDoubleArrayElements(velocities,nullptr);jdouble* p=env->GetDoubleArrayElements(hitPitches,nullptr);
    if(!t||!b||!v||!p){
        if(t)env->ReleaseIntArrayElements(tracks,t,JNI_ABORT);
        if(b)env->ReleaseDoubleArrayElements(beats,b,JNI_ABORT);
        if(v)env->ReleaseDoubleArrayElements(velocities,v,JNI_ABORT);
        if(p)env->ReleaseDoubleArrayElements(hitPitches,p,JNI_ABORT);
        return;
    }
    std::vector<Hit> hits;hits.reserve(static_cast<size_t>(count));
    for(jsize i=0;i<count;i++)hits.push_back({t[i],b[i],v[i],p[i]});
    env->ReleaseIntArrayElements(tracks,t,JNI_ABORT);
    env->ReleaseDoubleArrayElements(beats,b,JNI_ABORT);
    env->ReleaseDoubleArrayElements(velocities,v,JNI_ABORT);
    env->ReleaseDoubleArrayElements(hitPitches,p,JNI_ABORT);
    if(!stopStream(env,*e))return;
    try{e->mixer.replaceLoop(hits,length,bpm);}catch(...){restartStream(env,*e);throwArgument(env,"비트를 반영하지 못했습니다.");return;}
    restartStream(env,*e);
} catch(const std::bad_alloc&){throwJava(env,"java/lang/OutOfMemoryError","비트를 준비할 메모리가 부족합니다.");}
  catch(const std::exception& e){throwJava(env,"java/io/IOException",e.what());}
  catch(...){throwJava(env,"java/io/IOException","비트를 반영하지 못했습니다.");}

extern "C" JNIEXPORT void JNICALL Java_io_pocketgroove_AudioEngine_nSetVariants(
    JNIEnv* env,jclass,jlong handle,jintArray variantTracks,jintArray variantTenths,
    jobjectArray variantArrays) try {
    auto e=acquire(env,handle);if(!e)return;
    std::array<std::vector<PitchVariant>,MaxTracks> prepared;size_t totalBytes=0;
    if(!readVariants(env,variantTracks,variantTenths,variantArrays,e->mixer.trackCount(),prepared,totalBytes)){
        throwArgument(env,"피치 PCM 또는 음높이가 올바르지 않습니다.");return;
    }
    if(!stopStream(env,*e))return;
    try{e->mixer.setVariants(variantEntries(std::move(prepared)));}
    catch(...){restartStream(env,*e);throwArgument(env,"피치 PCM을 반영하지 못했습니다.");return;}
    restartStream(env,*e);
} catch(const std::bad_alloc&){throwJava(env,"java/lang/OutOfMemoryError","피치 PCM을 준비할 메모리가 부족합니다.");}
  catch(const std::exception& e){throwJava(env,"java/io/IOException",e.what());}
  catch(...){throwJava(env,"java/io/IOException","피치 PCM을 반영하지 못했습니다.");}

extern "C" JNIEXPORT void JNICALL Java_io_pocketgroove_AudioEngine_nCommand(JNIEnv* env,jclass,jlong handle,jint type,jint track,jdouble value,jdouble extra,jboolean wait){
    auto e=acquire(env,handle);if(!e)return;
    if(type<0||type>8){throwArgument(env,"알 수 없는 오디오 명령입니다.");return;}
    if(type==0&&(!validTrack(track,e->mixer.trackCount())||!std::isfinite(value)||value<0.05||value>1||!std::isfinite(extra)||extra< -24||extra>24)){
        throwArgument(env,"트랙, 타격 세기 또는 음높이 값이 올바르지 않습니다.");return;
    }
    if(type==3&&(!validTrack(track,e->mixer.trackCount())||!std::isfinite(value)||value<1||value>512||!std::isfinite(extra)||extra<40||extra>240)){
        throwArgument(env,"녹음 트랙, 길이 또는 템포가 올바르지 않습니다.");return;
    }
    if(type==7&&(!validTrack(track,e->mixer.trackCount())||!std::isfinite(value)||value< -12||value>12)){
        throwArgument(env,"트랙 피치가 올바르지 않습니다.");return;
    }
    if(type==8&&(!validTrack(track,e->mixer.trackCount())||(value!=0&&value!=1))){
        throwArgument(env,"트랙 피치 방식이 올바르지 않습니다.");return;
    }
    if(type==5&&(!std::isfinite(value)||value<40||value>240)){throwArgument(env,"템포는 40–240 BPM이어야 합니다.");return;}
    if(type==6&&(!std::isfinite(value)||value<0||value>1||!std::isfinite(extra)||extra< -12||extra>12)){throwArgument(env,"음량 또는 피치 값이 올바르지 않습니다.");return;}
    Command c{static_cast<CommandType>(type),track,value,extra,e->sequence.fetch_add(1)+1};
    if(!e->mixer.submit(c)){throwState(env,"오디오 엔진이 바쁩니다. 잠시 뒤 다시 시도하세요.");return;}
    if(wait){auto until=std::chrono::steady_clock::now()+std::chrono::milliseconds(500);
        while(e->mixer.acknowledged()<c.sequence&&std::chrono::steady_clock::now()<until)std::this_thread::sleep_for(std::chrono::milliseconds(1));
        if(e->mixer.acknowledged()<c.sequence)throwState(env,"오디오 엔진이 응답하지 않습니다.");
    }
}

extern "C" JNIEXPORT jdoubleArray JNICALL Java_io_pocketgroove_AudioEngine_nState(JNIEnv* env,jclass,jlong handle){
    auto e=acquire(env,handle);if(!e)return nullptr;auto& m=e->mixer;
    double data[]={m.position(),m.length(),static_cast<double>(m.hitCount()),static_cast<double>(m.isRecording()?1:m.isPlaying()?2:0),
        e->outputLatency,static_cast<double>(e->underruns.load(std::memory_order_relaxed)),static_cast<double>(m.droppedCommands()),
        e->maxCallbackSeconds.load(std::memory_order_relaxed)};
    auto result=env->NewDoubleArray(8);env->SetDoubleArrayRegion(result,0,8,data);return result;
}

extern "C" JNIEXPORT jdoubleArray JNICALL Java_io_pocketgroove_AudioEngine_nRecording(JNIEnv* env,jclass,jlong handle) try {
    auto e=acquire(env,handle);if(!e)return nullptr;
    if(e->mixer.isRecording()){throwState(env,"녹음 중에는 비트를 가져올 수 없습니다.");return nullptr;}
    auto hits=e->mixer.copyHits();
    auto result=env->NewDoubleArray(static_cast<jsize>(1+hits.size()*4));if(!result)return nullptr;
    double data[4];data[0]=e->mixer.length();
    env->SetDoubleArrayRegion(result,0,1,data);
    for(size_t i=0;i<hits.size();i++){
        data[0]=hits[i].track;data[1]=hits[i].beat;data[2]=hits[i].velocity;data[3]=hits[i].pitch;
        env->SetDoubleArrayRegion(result,static_cast<jsize>(1+i*4),4,data);
    }
    return result;
} catch(const std::bad_alloc&){throwJava(env,"java/lang/OutOfMemoryError","녹음 내용을 가져올 메모리가 부족합니다.");return nullptr;}
  catch(const std::exception& e){throwJava(env,"java/io/IOException",e.what());return nullptr;}
  catch(...){throwJava(env,"java/io/IOException","녹음 내용을 읽지 못했습니다.");return nullptr;}

extern "C" JNIEXPORT void JNICALL Java_io_pocketgroove_AudioEngine_nClose(JNIEnv*,jclass,jlong handle){
    std::shared_ptr<Engine> e;
    {std::lock_guard<std::mutex> lock(registryMutex);auto it=registry.find(handle);if(it==registry.end())return;e=std::move(it->second);registry.erase(it);}
    if(e->stream){Pa_StopStream(e->stream);Pa_CloseStream(e->stream);e->stream=nullptr;}
}
