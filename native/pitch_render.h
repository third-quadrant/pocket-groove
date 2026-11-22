#pragma once
#include <vector>

namespace groove {
// Signalsmith's practical range; wider shifts are not part of the instrument design.
constexpr double MaxPitchSemitones = 48.0;

// Renders interleaved stereo at its own sample rate. The returned buffer always has
// the same frame count as the input so event timing and project length stay unchanged.
std::vector<float> renderPitchExact(const std::vector<float>& stereo, int sampleRate,
                                    double semitones, bool preserveFormants);
}
