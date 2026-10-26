#pragma once

#include "LinkwitzRileyFilter.h"
#include "WsolaTimeStretcher.h"
#include "BpmPhaseDetector.h"
#include "EchoTailProcessor.h"

#include <cstddef>
#include <cstdint>
#include <vector>
#include <array>
#include <atomic>
#include <mutex>

namespace lastwave::audio {

enum class AutoMixState : int {
    Idle = 0,
    PrebufferingDeckB = 1,
    Analyzing = 2,
    MonitoringOutro = 3,
    FadingAuto = 4,
    FadingFast = 5,
    PostRestore = 6
};

// 2nd-order resonant Biquad filter for DJ sweeps (HPF Q=1.35 club peak, LPF)
struct ResonantFilter final {
    enum class Mode { HighPass, LowPass };

    double b0{1.0}, b1{0.0}, b2{0.0};
    double a1{0.0}, a2{0.0};
    std::array<double, 2> z1{0.0, 0.0};
    std::array<double, 2> z2{0.0, 0.0};

    void update(Mode mode, double sampleRate, double cutoffHz, double q) noexcept;
    void reset() noexcept;
    [[nodiscard]] float tick(float in, std::size_t ch) noexcept;
};

class AutoMixEngine final {
public:
    AutoMixEngine() noexcept;
    ~AutoMixEngine() = default;

    void configure(double sampleRate) noexcept;
    void setEnabled(bool enabled) noexcept;
    [[nodiscard]] bool isEnabled() const noexcept;

    void setBarMode(int bars) noexcept; // 8 or 16 bars
    [[nodiscard]] int getBarMode() const noexcept;

    void triggerFadeNow() noexcept; // 4 bars fast blend (~4-5s) on user Next button
    void startTransition(int durationMs) noexcept;
    void setTransitionProgress(float progress) noexcept;
    void reset() noexcept;

    // Writes decoded Float32 stereo PCM for Deck B (incoming track)
    std::size_t writeDeckBPcm(const float* stereoPcm, std::size_t frames) noexcept;

    // Main real-time process callback: mixes Deck A (in/out stereo) and Deck B
    void process(float* deckAStereo, std::size_t frames) noexcept;

    // Separate real-time processors for dual-sink architecture
    void processDeckA(float* deckAStereo, std::size_t frames, float progress) noexcept;
    void processDeckB(float* deckBStereo, std::size_t frames, float progress) noexcept;

    // In-place buffer processor supporting 16-bit, 24-bit, 32-bit, and Float32
    bool processDeckBuffer(void* pcm, std::size_t frameCount, int encoding, int sampleRate, bool isDeckA) noexcept;

    [[nodiscard]] AutoMixState getState() const noexcept;
    [[nodiscard]] float getTransitionProgress() const noexcept;
    [[nodiscard]] std::size_t getDeckBLeadingTrimFrames() const noexcept;
    [[nodiscard]] std::int64_t getDeckBFirstBeatMs() const noexcept;
    [[nodiscard]] float getDeckABpm() const noexcept;
    [[nodiscard]] float getDeckBBpm() const noexcept;
    [[nodiscard]] std::int64_t getDeckBDropBeatMs() const noexcept;

    void feedDeckARollingBuffer(const float* stereo, std::size_t frames) noexcept;
    void captureDeckALoop(float bpmA) noexcept;
    [[nodiscard]] bool isAtPhraseBoundary(const float* stereo, std::size_t frames) noexcept;

private:
    double sampleRate_{48000.0};
    std::atomic<bool> enabled_{false};
    std::atomic<int> barMode_{8};
    std::atomic<AutoMixState> state_{AutoMixState::Idle};

    LinkwitzRileyFilter deckAFilter_;
    LinkwitzRileyFilter deckBFilter_;
    LinkwitzRileyFilter deckBHpf220_; // Strict complementary 220Hz HPF for Deck B (0% to 75%)
    WsolaTimeStretcher deckBStretcher_;
    BpmPhaseDetector deckADetector_;
    BpmPhaseDetector deckBDetector_;
    EchoTailProcessor deckAEcho_;

    ResonantFilter deckAHpf_; // 2nd-order resonant HPF for 500Hz downbeat tail
    ResonantFilter deckAVocalDetectorBp_; // 300-3500 Hz vocal detector

    mutable std::mutex bufferMutex_;
    std::vector<float> deckBRingBuffer_;
    std::size_t deckBReadPos_{0};
    std::size_t deckBWritePos_{0};
    std::size_t deckBAvailableFrames_{0};

    std::size_t deckBLeadingTrimFrames_{0};
    std::int64_t deckBFirstBeatMs_{0};
    std::int64_t deckBDropBeatMs_{0};
    bool deckBTrimCalculated_{false};

    // Rule 2: 1-Bar Beat-Loop Exit Buffer for Deck A
    std::vector<float> deckARollingBuffer_;
    std::size_t deckARollingWritePos_{0};
    std::vector<float> deckAOneBarLoopBuffer_;
    std::size_t deckALoopFrames_{0};
    std::size_t deckALoopReadPos_{0};
    bool deckALoopActive_{false};

    // Transition tracking
    std::size_t transitionTotalFrames_{0};
    std::size_t transitionCurrentFrame_{0};
    float transitionProgress_{0.0F};
    bool bassSwapped_{false};

    // Outro energy tracking
    float baselineEnergy_{0.0F};
    float shortTermEnergy_{0.0F};
    std::size_t energyHistoryFrames_{0};

    // Scratch buffers for real-time mixing and format conversion
    std::vector<float> deckBScratch_;
    std::vector<float> deckBStretchedScratch_;
    std::vector<float> conversionScratch_;

    void startTransition(AutoMixState mode, int bars) noexcept;
    void detectDeckBFirstBeatAndSilence() noexcept;
};

} // namespace lastwave::audio
