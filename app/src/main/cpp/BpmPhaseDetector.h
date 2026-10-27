#pragma once

#include <cstddef>
#include <cstdint>
#include <vector>
#include <array>

namespace lastwave::audio {

struct BeatDetectionResult final {
    float bpm{120.0F};
    float confidence{0.0F};
    std::uint64_t lastDownbeatFrame{0};
};

class BpmPhaseDetector final {
public:
    static constexpr std::size_t kDecimationRate = 240; // 48000 / 240 = 200 Hz envelope rate
    static constexpr std::size_t kEnvelopeBufferSize = 1024; // ~5.12 seconds buffer
    static constexpr std::size_t kMinLag = 66;  // 181.8 BPM @ 200Hz
    static constexpr std::size_t kMaxLag = 200; // 60.0 BPM @ 200Hz

    BpmPhaseDetector() noexcept;
    void configure(double sampleRate) noexcept;
    void reset() noexcept;
    void process(const float* stereoInterleaved, std::size_t frames) noexcept;
    [[nodiscard]] BeatDetectionResult getResult() const noexcept;

private:
    double sampleRate_{48000.0};
    std::size_t decimationFactor_{240};

    // 2nd-order Butterworth bandpass (60-140 Hz)
    double b0_{0.0};
    double b1_{0.0};
    double b2_{0.0};
    double a1_{0.0};
    double a2_{0.0};
    double z1_{0.0};
    double z2_{0.0};

    // Envelope decimation accumulator
    float decimationAccumulator_{0.0F};
    std::size_t decimationCounter_{0};
    std::uint64_t totalFramesProcessed_{0};

    // Ring buffer of decimated envelope
    std::vector<float> envelopeBuffer_;
    std::size_t envelopeWritePos_{0};
    bool envelopeFull_{false};

    // Onset and peak tracking
    float previousFiltered_{0.0F};
    float onsetThreshold_{0.01F};
    std::uint64_t lastPeakFrame_{0};

    // Cached result
    BeatDetectionResult currentResult_{};
    void updateAutocorrelation() noexcept;
};

} // namespace lastwave::audio
