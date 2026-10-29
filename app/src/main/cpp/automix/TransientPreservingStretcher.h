#pragma once

#include <vector>
#include <cmath>
#include <cstdint>
#include <algorithm>

namespace automix {

// Phase-locked, transient-preserving time stretcher using overlap-add (WSOLA)
class TransientPreservingStretcher {
public:
    TransientPreservingStretcher();
    void init(float sampleRate, int channels = 2);
    void reset();

    // Set time-stretch ratio (clamped to 0.94f .. 1.06f, corresponding to +-6% tempo stretch)
    void setTempoRatio(float ratio);
    float getTempoRatio() const { return tempoRatio_; }

    // Feeds input interleaved stereo samples and processes into output buffer
    // Returns number of frames generated
    int process(const float* inPcm, int inFrames, float* outPcm, int maxOutFrames);

private:
    float sampleRate_ = 44100.0f;
    int channels_ = 2;
    float tempoRatio_ = 1.0f;

    int windowSize_ = 1024;
    int hopSize_ = 256;
    int searchRange_ = 128;

    std::vector<float> hanningWindow_;
    std::vector<float> inputBuffer_;
    std::vector<float> outputBuffer_;

    void initWindow();
    int findBestMatchOffset(int targetOffset);
};

} // namespace automix
