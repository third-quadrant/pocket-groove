#include "pitch_render.h"

#include "signalsmith-stretch.h"

#include <algorithm>
#include <cmath>
#include <stdexcept>

namespace {
constexpr int Channels = 2;
}

std::vector<float> groove::renderPitchExact(const std::vector<float>& stereo, int sampleRate,
                                            double semitones, bool preserveFormants) {
    if (stereo.empty() || stereo.size() % Channels != 0)
        throw std::invalid_argument("PCM must be non-empty interleaved stereo");
    if (sampleRate < 8000 || sampleRate > 192000)
        throw std::invalid_argument("Sample rate must be 8-192 kHz");
    if (!std::isfinite(semitones) || std::abs(semitones) > MaxPitchSemitones)
        throw std::invalid_argument("Pitch must be within +/-48 semitones");
    for (float sample : stereo) {
        if (!std::isfinite(sample)) throw std::invalid_argument("PCM contains a non-finite sample");
    }

    const size_t frames = stereo.size()/Channels;
    // A fixed seed keeps the realtime cache and WAV export bit-identical.
    signalsmith::stretch::SignalsmithStretch<float> stretch(1);
    stretch.presetDefault(Channels, static_cast<float>(sampleRate));
    stretch.setTransposeSemitones(static_cast<float>(semitones));
    if (preserveFormants) stretch.setFormantFactor(1.0f, true);

    const size_t seekLength = static_cast<size_t>(stretch.outputSeekLength(1.0f));
    // exact() refuses inputs shorter than its seek length; silence padding keeps short
    // effects renderable while the caller keeps the original frame count.
    const size_t padded = std::max(frames, seekLength + 1);
    std::vector<float> left(frames, 0.0f);
    std::vector<float> right(frames, 0.0f);
    for (size_t i = 0; i < frames; ++i) {
        left[i] = stereo[i*Channels];
        right[i] = stereo[i*Channels + 1];
    }
    left.resize(padded, 0.0f);
    right.resize(padded, 0.0f);
    std::vector<float> outLeft(padded), outRight(padded);
    float* inputs[Channels] = {left.data(), right.data()};
    float* outputs[Channels] = {outLeft.data(), outRight.data()};

    if (!stretch.exact(inputs, static_cast<int>(padded), outputs, static_cast<int>(padded)))
        throw std::runtime_error("Signalsmith Stretch could not render this sample");

    std::vector<float> result(frames*Channels);
    for (size_t i = 0; i < frames; ++i) {
        result[i*Channels] = outLeft[i];
        result[i*Channels + 1] = outRight[i];
    }
    for (float sample : result) {
        if (!std::isfinite(sample)) throw std::runtime_error("Pitch render produced a non-finite sample");
    }
    return result;
}
