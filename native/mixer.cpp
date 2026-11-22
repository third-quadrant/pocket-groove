#include "mixer.h"
#include <algorithm>
#include <cmath>
#include <stdexcept>

namespace groove {
namespace {
inline void readPcm(const std::vector<float>& pcm,double position,float& left,float& right){
    size_t frames=pcm.size()/2;
    if(position<0 || position>=static_cast<double>(frames)){left=right=0;return;}
    size_t index=static_cast<size_t>(position);
    size_t next=std::min(index+1,frames-1);
    float fraction=static_cast<float>(position-index);
    left=pcm[index*2]+(pcm[next*2]-pcm[index*2])*fraction;
    right=pcm[index*2+1]+(pcm[next*2+1]-pcm[index*2+1])*fraction;
}
}
bool Mixer::submit(const Command& c) noexcept {
    auto write=write_.load(std::memory_order_relaxed),next=(write+1)%QueueSize;
    if(next==read_.load(std::memory_order_acquire)){dropped_.fetch_add(1,std::memory_order_relaxed);return false;}
    commands_[write]=c;write_.store(next,std::memory_order_release);return true;
}
void Mixer::setTrackCount(size_t count) {
    if(count<1 || count>MaxTracks) throw std::invalid_argument("Invalid track count");
    trackCount_=count;
    for(auto&v:voices_)v.active=false;
    publish();
}
void Mixer::setLoop(const std::vector<Hit>& hits,double length,double bpm) {
    loadLoop(hits,length,bpm,false);
}
void Mixer::replaceLoop(const std::vector<Hit>& hits,double length,double bpm) {
    loadLoop(hits,length,bpm,true);
}
void Mixer::loadLoop(const std::vector<Hit>& hits,double length,double bpm,bool preservePlayback) {
    if(hits.size()>MaxHits || !std::isfinite(length) || length<=0 || length>512 || !std::isfinite(bpm) || bpm<40 || bpm>240)
        throw std::invalid_argument("Invalid loop");
    for(const auto& hit:hits)if(hit.track<0 || static_cast<size_t>(hit.track)>=trackCount_
            || !std::isfinite(hit.beat) || hit.beat<0 || hit.beat>=length
            || !std::isfinite(hit.velocity) || hit.velocity<0.05 || hit.velocity>1
            || !std::isfinite(hit.pitch) || hit.pitch< -24 || hit.pitch>24)
        throw std::invalid_argument("Invalid hit");
    int savedMode=mode_;double savedPhase=phase_;
    std::copy(hits.begin(),hits.end(),hits_.begin());count_=hits.size();
    std::sort(hits_.begin(),hits_.begin()+count_,[](const Hit&a,const Hit&b){return a.beat<b.beat;});
    length_=length;bpm_=bpm;
    if(preservePlayback && savedMode==2 && savedPhase<length){
        mode_=2;phase_=savedPhase;
        next_=static_cast<size_t>(std::upper_bound(hits_.begin(),hits_.begin()+count_,phase_+1e-10,
                [](double beat,const Hit& hit){return beat<hit.beat;})-hits_.begin());
    }else{
        phase_=0;next_=0;mode_=0;
        for(auto&v:voices_)v.active=false;
    }
    publish();
}
std::vector<Hit> Mixer::copyHits() const { return {hits_.begin(),hits_.begin()+count_}; }
void Mixer::trigger(int track,double gain,double semitones) noexcept {
    if(track<0 || static_cast<size_t>(track)>=trackCount_ || samples[track].pcm.empty())return;
    Voice* target=nullptr;
    for(auto&v:voices_)if(!v.active){target=&v;break;}
    if(!target)target=&*std::min_element(voices_.begin(),voices_.end(),[](const Voice&a,const Voice&b){return a.age<b.age;});
    double ratio=std::pow(2.0,(pitch_+samples[track].pitch+semitones)/12.0);
    double scale=samples[track].rate/rate_;
    target->track=track;
    target->position=0;
    target->ratio=ratio;
    target->pitchShift=samples[track].pitchShift;
    target->step=target->pitchShift?1.0:scale*ratio;
    double outputLength=samples[track].pcm.size()/2.0/scale;
    target->hop=std::min(512.0,std::max(16.0,outputLength/4.0));
    target->gain=static_cast<float>(gain);
    target->active=true;
    target->age=++age_;
}
void Mixer::finishRecording(bool manual) noexcept {
    (void)manual;
    if(mode_!=1)return;
    std::sort(hits_.begin(),hits_.begin()+count_,[](const Hit&a,const Hit&b){return a.beat<b.beat;});
    phase_=0;next_=0;mode_=count_?2:0;
}
void Mixer::apply(const Command& c) noexcept {
    switch(c.type){
    case CommandType::Hit:
        trigger(c.track,volume_*c.value,c.extra);
        if(mode_==1 && c.track==recordTrack_ && phase_<length_){
            if(count_<MaxHits)hits_[count_++]={c.track,phase_,c.value,c.extra};
            else dropped_.fetch_add(1,std::memory_order_relaxed);
        }
        break;
    case CommandType::Stop:
        if(mode_==1)finishRecording(true);
        mode_=0;phase_=0;next_=0;for(auto&v:voices_)v.active=false;break;
    case CommandType::Play:
        mode_=count_?2:0;phase_=0;next_=0;break;
    case CommandType::Record: {
        for(auto&v:voices_)v.active=false;
        recordTrack_=c.track;
        size_t kept=0;
        for(size_t i=0;i<count_;i++)if(hits_[i].track!=recordTrack_)hits_[kept++]=hits_[i];
        count_=kept;
        next_=0;phase_=0;length_=c.value;bpm_=c.extra;mode_=1;break;
    }
    case CommandType::FinishRecord:finishRecording(true);break;
    case CommandType::Tempo:if(mode_!=1)bpm_=c.value;break;
    case CommandType::Parameters:volume_=c.value;pitch_=c.extra;break;
    case CommandType::TrackPitch:
        if(c.track>=0 && static_cast<size_t>(c.track)<trackCount_)samples[c.track].pitch=c.value;
        break;
    case CommandType::TrackMode:
        if(c.track>=0 && static_cast<size_t>(c.track)<trackCount_)
            samples[c.track].pitchShift=c.value!=0;
        break;
    }
}
void Mixer::publish() noexcept {
    publishedPosition_.store(phase_,std::memory_order_relaxed);
    publishedLength_.store(length_,std::memory_order_relaxed);
    publishedMode_.store(mode_,std::memory_order_release);
    publishedCount_.store(count_,std::memory_order_relaxed);
    size_t active=0;for(const auto&v:voices_)if(v.active)active++;
    publishedVoices_.store(active,std::memory_order_relaxed);
}
void Mixer::render(float* out,size_t frames) noexcept {
    uint64_t ack=0;uint32_t processed=0;
    for(size_t n=0;n<128;n++){
        auto read=read_.load(std::memory_order_relaxed);
        if(read==write_.load(std::memory_order_acquire))break;
        auto c=commands_[read];read_.store((read+1)%QueueSize,std::memory_order_release);
        apply(c);ack=c.sequence;processed++;
    }
    size_t loopTriggers=0;
    for(size_t f=0;f<frames;f++){
        if(mode_==1 && phase_+1e-10>=length_)finishRecording(false);
        if(mode_==1 || mode_==2){
            while(next_<count_ && hits_[next_].beat<=phase_+1e-10 && loopTriggers<256){
                auto&hit=hits_[next_];
                if(mode_==2 || hit.track!=recordTrack_){trigger(hit.track,volume_*hit.velocity,hit.pitch);loopTriggers++;}
                next_++;
            }
            if(loopTriggers==256&&next_<count_&&hits_[next_].beat<=phase_+1e-10){
                auto first=hits_.begin()+next_,last=hits_.begin()+count_;
                auto end=std::upper_bound(first,last,phase_+1e-10,[](double beat,const Hit& hit){return beat<hit.beat;});
                dropped_.fetch_add(static_cast<uint64_t>(end-first),std::memory_order_relaxed);
                next_=static_cast<size_t>(end-hits_.begin());
            }
        }
        float l=0,r=0;
        for(auto&v:voices_)if(v.active){
            const auto& pcm=samples[v.track].pcm;
            if(pcm.empty()){v.active=false;continue;}
            if(v.pitchShift){
                size_t length=pcm.size()/2;
                double scale=samples[v.track].rate/rate_;
                double outputLength=length/scale;
                if(v.position>=outputLength){v.active=false;continue;}
                const double hop=v.hop;
                double grain=std::floor(v.position/hop);
                double fraction=v.position-grain*hop;
                double weight=grain<1?1.0:0.5+0.5*(fraction/hop);
                double maxStart=std::max(0.0,length-hop*v.ratio*scale);
                double currentStart=std::min(grain*hop*scale,maxStart);
                double previousStart=std::min((grain-1)*hop*scale,maxStart);
                float currentLeft=0,currentRight=0,previousLeft=0,previousRight=0;
                readPcm(pcm,currentStart+fraction*v.ratio*scale,currentLeft,currentRight);
                if(grain>=1)readPcm(pcm,previousStart+(hop+fraction)*v.ratio*scale,
                                     previousLeft,previousRight);
                l+=(currentLeft*weight+previousLeft*(1.0-weight))*v.gain;
                r+=(currentRight*weight+previousRight*(1.0-weight))*v.gain;
                v.position+=1;
            }else{
                size_t length=pcm.size()/2,index=static_cast<size_t>(v.position);
                if(index>=length){v.active=false;continue;}
                size_t next=std::min(index+1,length-1);float fraction=static_cast<float>(v.position-index);
                l+=(pcm[index*2]+(pcm[next*2]-pcm[index*2])*fraction)*v.gain;
                r+=(pcm[index*2+1]+(pcm[next*2+1]-pcm[index*2+1])*fraction)*v.gain;
                v.position+=v.step;
            }
        }
        out[f*2]=std::clamp(l,-1.f,1.f);out[f*2+1]=std::clamp(r,-1.f,1.f);
        if(mode_){phase_+=bpm_/(60.0*rate_);if(mode_==2 && phase_+1e-10>=length_){phase_=std::max(0.0,phase_-length_);next_=0;}}
    }
    publish();
    lastCommands_.store(processed,std::memory_order_relaxed);
    lastLoopEvents_.store(static_cast<uint32_t>(loopTriggers),std::memory_order_relaxed);
    if(ack)acknowledged_.store(ack,std::memory_order_release);
}
}
