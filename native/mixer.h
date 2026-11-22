#pragma once
#include <array>
#include <atomic>
#include <cstdint>
#include <utility>
#include <vector>

namespace groove {
constexpr size_t MaxTracks=32, MaxHits=100000, MaxVoices=64, QueueSize=1024;
struct PitchVariant { int tenth=0; std::vector<float> pcm; };
struct Sample {
    std::vector<float> pcm;
    double rate=48000,pitch=0;
    bool pitchShift=false;
    std::vector<PitchVariant> variants;
};
struct Hit { int track; double beat; double velocity; double pitch; };
enum class CommandType { Hit, Stop, Play, Record, FinishRecord, Tempo, Parameters, TrackPitch, TrackMode };
struct Command { CommandType type; int track=0; double value=0, extra=0; uint64_t sequence=0; };

class Mixer {
public:
    explicit Mixer(double sampleRate):rate_(sampleRate){}
    std::array<Sample,MaxTracks> samples;
    bool submit(const Command& command) noexcept;
    void render(float* output, size_t frames) noexcept;
    // The following calls run only while the stream is stopped and no callback can run.
    void setTrackCount(size_t count);
    void setVariants(std::vector<std::pair<int,std::vector<PitchVariant>>> variants);
    void clearVoices() noexcept { for(auto&v:voices_)v.active=false; }
    void setLoop(const std::vector<Hit>& hits, double length, double bpm);
    void replaceLoop(const std::vector<Hit>& hits, double length, double bpm);
    std::vector<Hit> copyHits() const;
    size_t trackCount() const noexcept { return trackCount_; }
    double position() const noexcept { return publishedPosition_.load(std::memory_order_relaxed); }
    double length() const noexcept { return publishedLength_.load(std::memory_order_relaxed); }
    bool isRecording() const noexcept { return publishedMode_.load(std::memory_order_acquire)==1; }
    bool isPlaying() const noexcept { return publishedMode_.load(std::memory_order_acquire)==2; }
    size_t hitCount() const noexcept { return publishedCount_.load(std::memory_order_relaxed); }
    size_t activeVoices() const noexcept { return publishedVoices_.load(std::memory_order_relaxed); }
    uint64_t droppedCommands() const noexcept { return dropped_.load(std::memory_order_relaxed); }
    uint32_t lastCommandsProcessed() const noexcept { return lastCommands_.load(std::memory_order_relaxed); }
    uint32_t lastLoopEvents() const noexcept { return lastLoopEvents_.load(std::memory_order_relaxed); }
    uint64_t acknowledged() const noexcept { return acknowledged_.load(std::memory_order_acquire); }
    const std::array<Hit,MaxHits>& hits() const noexcept { return hits_; }
private:
    struct Voice {
        int track=0;
        double position=0, step=1;
        float gain=1;
        int variant=-1;
        bool active=false;
        uint64_t age=0;
    };
    std::array<Voice,MaxVoices> voices_{};
    std::array<Hit,MaxHits> hits_{};
    std::array<Command,QueueSize> commands_{};
    alignas(64) std::atomic<size_t> write_{0};
    alignas(64) std::atomic<size_t> read_{0};
    std::atomic<uint64_t> dropped_{0},acknowledged_{0};
    std::atomic<uint32_t> lastCommands_{0},lastLoopEvents_{0};
    std::atomic<double> publishedPosition_{0},publishedLength_{16};
    std::atomic<size_t> publishedCount_{0},publishedVoices_{0};
    std::atomic<int> publishedMode_{0};
    double rate_,phase_=0,length_=16,bpm_=100,volume_=1,pitch_=0;
    size_t trackCount_=0,count_=0,next_=0;
    int mode_=0,recordTrack_=0;
    uint64_t age_=0;
    void apply(const Command& c) noexcept;
    void loadLoop(const std::vector<Hit>& hits, double length, double bpm, bool preservePlayback);
    void trigger(int track,double gain,double semitones) noexcept;
    void finishRecording(bool manual) noexcept;
    void publish() noexcept;
};
static_assert(std::atomic<double>::is_always_lock_free,"Audio state atomics must not lock");
static_assert(std::atomic<uint64_t>::is_always_lock_free,"Audio counters must not lock");
}
