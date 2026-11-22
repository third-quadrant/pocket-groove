#include "pitch_render.h"
#include <algorithm>
#include <cmath>
#include <cstdlib>
#include <iostream>
#include <vector>

namespace {
constexpr double Pi = 3.14159265358979323846;

void check(bool ok, const char* message) {
    if (!ok) { std::cerr << "FAIL: " << message << '\n'; std::exit(1); }
}

std::vector<float> tone(double frequency, double seconds, int rate = 48000, double amplitude = 0.5) {
    const int frames = static_cast<int>(std::round(seconds*rate));
    std::vector<float> stereo(static_cast<size_t>(frames)*2);
    for (int i = 0; i < frames; ++i) {
        float value = static_cast<float>(std::sin(2*Pi*frequency*i/rate)*amplitude);
        stereo[static_cast<size_t>(i)*2] = value;
        stereo[static_cast<size_t>(i)*2 + 1] = value;
    }
    return stereo;
}

double rms(const std::vector<float>& stereo) {
    double sum = 0;
    for (float value : stereo) sum += double(value)*value;
    return std::sqrt(sum/std::max<size_t>(1, stereo.size()));
}

// Zero-crossing estimate is enough to prove a semitone shift reached the output.
double frequency(const std::vector<float>& pcm, int rate) {
    int crossings = 0;
    for (size_t i = 2; i < pcm.size(); i += 2) {
        if (pcm[i - 2] <= 0 && pcm[i] > 0) ++crossings;
    }
    return crossings*double(rate)/double(pcm.size()/2);
}

double correlation(const std::vector<float>& a, const std::vector<float>& b) {
    double ab = 0, aa = 0, bb = 0;
    for (size_t i = 0; i < a.size(); ++i) {
        ab += double(a[i])*b[i];
        aa += double(a[i])*a[i];
        bb += double(b[i])*b[i];
    }
    return ab/std::sqrt(std::max(1e-30, aa*bb));
}

bool near(double actual, double expected, double tolerance) {
    return std::abs(actual - expected) <= expected*tolerance;
}
}

int main() {
    const int rate = 48000;
    const auto c4 = tone(261.63, 1.0, rate);

    {
        auto shifted = groove::renderPitchExact(c4, rate, 12, false);
        check(shifted.size() == c4.size(), "octave up keeps the frame count");
        check(rms(shifted) > 0.01, "octave up is not silent");
        check(near(frequency(shifted, rate), 523.25, 0.04), "octave up raises the frequency");
    }
    {
        auto shifted = groove::renderPitchExact(c4, rate, -12, false);
        check(shifted.size() == c4.size(), "octave down keeps the frame count");
        check(rms(shifted) > 0.01, "octave down is not silent");
        check(near(frequency(shifted, rate), 130.81, 0.05), "octave down lowers the frequency");
    }
    {
        const int steps[] = {0, 1, -1, 7, -7, 12, -12, 24, -24};
        for (int step : steps) {
            auto shifted = groove::renderPitchExact(c4, rate, step, false);
            check(shifted.size() == c4.size(), "semitone step keeps the frame count");
            check(rms(shifted) > 0.01, "semitone step is not silent");
            check(near(frequency(shifted, rate), 261.63*std::pow(2.0, step/12.0), 0.06),
                  "semitone step reaches the expected frequency");
        }
        auto same = groove::renderPitchExact(c4, rate, 0, false);
        check(correlation(c4, same) > 0.9, "zero semitones stays close to the input");
    }
    {
        const float left = 0.4f, right = -0.25f;
        std::vector<float> stereo(c4.size());
        for (size_t i = 0; i < stereo.size(); i += 2) {
            stereo[i] = left;
            stereo[i + 1] = right;
        }
        auto shifted = groove::renderPitchExact(stereo, rate, 7, false);
        double leftRms = 0, rightRms = 0;
        for (size_t i = 0; i < shifted.size(); i += 2) {
            leftRms += double(shifted[i])*shifted[i];
            rightRms += double(shifted[i + 1])*shifted[i + 1];
        }
        check(leftRms > 0 && rightRms > 0, "both stereo channels are rendered");
        check(std::abs(std::sqrt(leftRms/rightRms) - left/std::abs(right)) < 0.05,
              "stereo channel balance is preserved");
    }
    {
        auto shortTone = tone(880, 100.0/rate, rate);
        auto shifted = groove::renderPitchExact(shortTone, rate, 12, false);
        check(shifted.size() == shortTone.size(), "short effect keeps its frame count");
        check(rms(shifted) > 0.001, "short effect keeps audible energy");
        for (float sample : shifted) check(std::isfinite(sample), "short effect stays finite");
        auto sameShort = groove::renderPitchExact(shortTone, rate, 0, false);
        check(correlation(shortTone, sameShort) > 0.9,
              "short effect keeps its shape when the pitch does not change");
    }
    {
        auto longTone = tone(220, 5.0, 44100);
        auto shifted = groove::renderPitchExact(longTone, 44100, -5, true);
        check(shifted.size() == longTone.size(), "long 44.1 kHz source keeps its frame count");
        check(rms(shifted) > 0.01, "long source is not silent");
    }
    {
        auto plain = groove::renderPitchExact(c4, rate, 7, false);
        auto formant = groove::renderPitchExact(c4, rate, 7, true);
        check(plain.size() == formant.size(), "formant mode keeps the frame count");
        check(rms(formant) > 0.01, "formant mode is not silent");
    }
    {
        auto first = groove::renderPitchExact(c4, rate, 3, false);
        auto second = groove::renderPitchExact(c4, rate, 3, false);
        check(first == second, "fixed seed makes rendering deterministic");
    }

    std::cout << "Pitch render checks passed: octaves, semitones, stereo, short/long, formant, determinism.\n";
}
