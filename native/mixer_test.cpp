#include "mixer.h"
#include <cmath>
#include <cstdlib>
#include <iostream>
#include <memory>

using namespace groove;
static void check(bool ok, const char* message) {
    if (!ok) { std::cerr << "FAIL: " << message << '\n'; std::exit(1); }
}
static Sample constant(float value, size_t frames=100) {
    return Sample{std::vector<float>(frames*2,value), 48000};
}
int main() {
    auto m=std::make_unique<Mixer>(48000);
    m->setTrackCount(7);
    m->samples[0]=constant(.2f);m->samples[1]=constant(.3f);
    float out[32]{};
    m->submit({CommandType::Hit,0,.5,0});m->submit({CommandType::Hit,1,1,0});
    m->render(out,4);
    check(std::abs(out[0]-.4f)<.00001,"simultaneous tracks mix with independent gain");

    m->submit({CommandType::Stop});m->render(out,4);
    check(out[0]==0,"stop silences all voices");

    m->samples[0]=Sample{{0,0,.1f,.1f,.2f,.2f,.3f,.3f,.4f,.4f},48000};
    m->submit({CommandType::Hit,0,1,12});m->render(out,4);
    check(std::abs(out[2]-.2f)<.00001 && std::abs(out[4]-.4f)<.00001 && out[6]==0,
          "octave doubles position and halves duration");

    m->submit({CommandType::Stop});m->render(out,1);
    m->samples[0]=constant(.1f,1);
    m->setLoop({{0,0,.5},{1,0,1.0}},1,240);
    m->submit({CommandType::Play});
    std::vector<float> block(24002);
    m->render(block.data(),12001);
    check(std::abs(block[0]-.35f)<.00001 && std::abs(block[24000]-.35f)<.00001,
          "loop zero fires once per boundary with per-hit velocity");

    m->submit({CommandType::Stop});m->render(out,1);
    m->submit({CommandType::Record,1,1,120});
    m->submit({CommandType::Hit,0,.8,0});m->render(out,1);
    m->submit({CommandType::Hit,1,.6,7});m->render(out,1);
    m->submit({CommandType::FinishRecord});m->render(out,1);
    check(m->hitCount()==2 && m->hits()[0].track==0 && m->hits()[0].velocity==.5
          && m->hits()[1].track==1 && m->hits()[1].velocity==.6 && m->hits()[1].pitch==7,
          "per-track recording replaces only the selected track and keeps backing hits");
    check(m->isPlaying() && !m->isRecording(),"finish recording starts the loop");

    {
        auto p=std::make_unique<Mixer>(48000);
        p->setTrackCount(1);
        p->samples[0]=Sample{{0,0,.1f,.1f,.2f,.2f,.3f,.3f,.4f,.4f},48000,12};
        p->submit({CommandType::Hit,0,1,0});p->render(out,4);
        check(out[0]==0 && std::abs(out[2]-.2f)<.00001 && std::abs(out[4]-.4f)<.00001
              && out[6]==0,
              "per-track pitch doubles sample speed without changing the command pitch");
    }
    {
        auto p=std::make_unique<Mixer>(48000);
        p->setTrackCount(2);
        p->samples[0]=constant(.2f,1);
        p->samples[1]=constant(.3f,1);
        p->setLoop({{0,0,1},{1,0,1}},1,120);
        p->submit({CommandType::Record,1,1,120});p->render(out,1);
        check(std::abs(out[0]-.2f)<.00001,
              "recording one track keeps the other track's backing loop audible");
    }
    {
        auto p=std::make_unique<Mixer>(48000);
        p->setTrackCount(1);
        p->samples[0]=Sample{{0,0,.1f,.1f,.2f,.2f,.3f,.3f,.4f,.4f},48000,0};
        p->setLoop({{0,0,1,12}},1,120);
        p->submit({CommandType::Play});p->render(out,4);
        check(out[0]==0 && std::abs(out[2]-.2f)<.00001 && std::abs(out[4]-.4f)<.00001,
              "each step's note pitch is applied during loop playback");
    }
    {
        auto high=std::make_unique<Mixer>(48000);
        high->setTrackCount(1);
        high->samples[0]=constant(.25f,100);
        high->submit({CommandType::Hit,0,1,12});
        std::vector<float> audio(200);
        high->render(audio.data(),100);
        int highFrames=0;for(int i=0;i<100;i++)if(audio[i*2]!=0)highFrames++;

        auto low=std::make_unique<Mixer>(48000);
        low->setTrackCount(1);
        low->samples[0]=constant(.25f,100);
        low->submit({CommandType::Hit,0,1,-12});
        std::vector<float> lowAudio(400);
        low->render(lowAudio.data(),200);
        int lowFrames=0;for(int i=0;i<200;i++)if(lowAudio[i*2]!=0)lowFrames++;
        check(highFrames==50 && lowFrames==200,
              "higher pitch makes the sample shorter and lower pitch makes it longer");

        auto shifted=std::make_unique<Mixer>(48000);
        shifted->setTrackCount(1);
        shifted->samples[0]=constant(.25f,100);
        shifted->samples[0].pitchShift=true;
        shifted->submit({CommandType::Hit,0,1,12});
        shifted->render(lowAudio.data(),100);
        int shiftedFrames=0;for(int i=0;i<100;i++)if(lowAudio[i*2]>0.05f)shiftedFrames++;
        check(shiftedFrames>=95,
              "pitch shift keeps the sample duration while changing pitch");
    }

    m->submit({CommandType::Stop});m->render(out,1);
    m->setLoop({{0,0,1}},4,120);m->submit({CommandType::Play});m->render(block.data(),12000);
    check(std::abs(m->position()-0.5)<1e-8,"120 BPM advances half a beat in quarter second");
    m->submit({CommandType::Tempo,0,60});m->render(block.data(),12000);
    check(std::abs(m->position()-.75)<1e-8,"tempo change preserves phase");
    m->replaceLoop({{1,0.75,1.0}},4,120);
    check(m->isPlaying() && std::abs(m->position()-.75)<1e-8,
          "live step edits replace loop events without stopping playback");
    m->render(out,1);

    m->samples[6]=constant(.25f,100);
    m->submit({CommandType::Stop});m->render(out,1);
    m->setLoop({{6,0,1}},1,240);m->submit({CommandType::Play});m->render(out,4);
    check(std::abs(out[0]-.25f)<.00001,"seventh and later tracks are mixable");

    size_t accepted=0;while(m->submit({CommandType::Hit,0,1,0}))accepted++;
    check(accepted==QueueSize-1,"command queue has a fixed bound");
    m->render(out,1);check(m->droppedCommands()>0,"queue saturation is reported");
    check(m->lastCommandsProcessed()==128,"audio callback processes at most 128 commands per buffer");
    check(m->activeVoices()<=64,"voice count is bounded under overload");
    for(int i=0;i<8;i++)m->render(out,1);

    m->submit({CommandType::Stop});m->render(out,1);
    std::vector<Hit> crowded(100000,Hit{0,0,1});
    m->setLoop(crowded,1,120);m->submit({CommandType::Play});m->render(out,1);
    check(m->lastLoopEvents()==256 && m->droppedCommands()>=100000-256,
          "pathological same-time loop hits are bounded and counted");

    m->setLoop({},1,120);
    bool rejected=false;
    try { m->setLoop({{7,0,1}},1,120); } catch(const std::invalid_argument&) { rejected=true; }
    check(rejected,"out-of-range track indices are rejected");

    std::cout << "Mixer checks passed: 7+ tracks, velocity, pitch, loop, recording, tempo, overload limits.\n";
}
