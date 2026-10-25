#pragma once

#include "LinkwitzRileyFilter.h"
#include "WsolaTimeStretcher.h"
#include "BpmPhaseDetector.h"
#include "EchoTailProcessor.h"

#include <cstddef>
#include <cstdint>
#include <vector>
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

class AutoMixEngine final {
public:
    AutoMixEngine() noexcept;
    ~AutoMixEngine() = default;

    void configure(double sampleRate) noexcept;
    void setEnabled(bool enabled) noexcept;
    [[nodiscard]] bool isEnabled() const noexcept;

    void setBarMode(int bars) noexcept; // 8 or 16 bars
    [[nodiscard]] int getBarMode() const noexcept;

    void triggerFadeNow() noexcept; // 4 bars fast blend
    void reset() noexcept;

    // Writes decoded Float32 stereo PCM for Deck B (incoming track)
    std::size_t writeDeckBPcm(const float* stereoPcm, std::size_t frames) noexcept;

    // Main real-time process callback: mixes Deck A (in/out stereo) and Deck B
    void process(float* deckAStereo, std::size_t frames) noexcept;

    [[nodiscard]] AutoMixState getState() const noexcept;
    [[nodiscard]] float getTransitionProgress() const noexcept;
    [[nodiscard]] std::size_t getDeckBLeadingTrimFrames() const noexcept;
    [[nodiscard]] float getDeckABpm() const noexcept;
    [[nodiscard]] float getDeckBBpm() const noexcept;

private:
    double sampleRate_{48000.0};
    std::atomic<bool> enabled_{false};
    std::atomic<int> barMode_{8};
    std::atomic<AutoMixState> state_{AutoMixState::Idle};

    LinkwitzRileyFilter deckAFilter_;
    LinkwitzRileyFilter deckBFilter_;
    WsolaTimeStretcher deckBStretcher_;
    BpmPhaseDetector deckADetector_;
    BpmPhaseDetector deckBDetector_;
    EchoTailProcessor deckAEcho_;

    mutable std::mutex bufferMutex_;
    std::vector<float> deckBRingBuffer_;
    std::size_t deckBReadPos_{0};
    std::size_t deckBWritePos_{0};
    std::size_t deckBAvailableFrames_{0};

    std::size_t deckBLeadingTrimFrames_{0};
    bool deckBTrimCalculated_{false};

    // Transition tracking
    std::size_t transitionTotalFrames_{0};
    std::size_t transitionCurrentFrame_{0};
    float transitionProgress_{0.0F};
    bool bassSwapped_{false};

    // Outro energy tracking
    float baselineEnergy_{0.0F};
    float shortTermEnergy_{0.0F};
    std::size_t energyHistoryFrames_{0};

    // Scratch buffers for real-time mixing
    std::vector<float> deckBScratch_;
    std::vector<float> deckBStretchedScratch_;

    void startTransition(AutoMixState mode, int bars) noexcept;
    void trimDeckBLeadingSilence() noexcept;
};

} // namespace lastwave::audio
