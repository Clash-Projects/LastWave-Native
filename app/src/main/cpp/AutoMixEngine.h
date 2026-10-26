#pragma once

#include "LinkwitzRileyFilter.h"
#include "WsolaTimeStretcher.h"
#include "BpmPhaseDetector.h"
#include "EchoTailProcessor.h"
#include "TransitionPlanner.h"

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

// 2nd-order resonant Biquad filter for DJ sweeps and peaking cuts
struct ResonantFilter final {
    enum class Mode { HighPass, LowPass, PeakingCut };

    double b0{1.0}, b1{0.0}, b2{0.0};
    double a1{0.0}, a2{0.0};
    std::array<double, 2> z1{0.0, 0.0};
    std::array<double, 2> z2{0.0, 0.0};

    void update(Mode mode, double sampleRate, double cutoffHz, double q, double gainDb = 0.0) noexcept;
    void reset() noexcept;
    [[nodiscard]] float tick(float in, std::size_t ch) noexcept;
};

// Subtle Phaser for single texture FX
struct SubtlePhaser final {
    double sampleRate{48000.0};
    double lfoPhase{0.0};
    std::array<double, 4> allpassStateL{0.0, 0.0, 0.0, 0.0};
    std::array<double, 4> allpassStateR{0.0, 0.0, 0.0, 0.0};

    void configure(double sr) noexcept {
        sampleRate = (sr > 0.0) ? sr : 48000.0;
        lfoPhase = 0.0;
        allpassStateL.fill(0.0);
        allpassStateR.fill(0.0);
    }

    void reset() noexcept {
        lfoPhase = 0.0;
        allpassStateL.fill(0.0);
        allpassStateR.fill(0.0);
    }

    void process(float& left, float& right, float send) noexcept;
};

class AutoMixEngine final {
public:
    AutoMixEngine() noexcept;
    ~AutoMixEngine() = default;

    void configure(double sampleRate) noexcept;
    void setEnabled(bool enabled) noexcept;
    [[nodiscard]] bool isEnabled() const noexcept;

    void setBarMode(int bars) noexcept; // 0 = Auto, 8, or 16 bars
    [[nodiscard]] int getBarMode() const noexcept;

    void setPaused(bool paused) noexcept;
    [[nodiscard]] bool isPaused() const noexcept;

    void setTransitionPlan(const TransitionPlan& plan) noexcept;
    [[nodiscard]] TransitionPlan getTransitionPlan() const noexcept;

    void setDeckBVocalsActive(bool active) noexcept;
    void setDeckAVocalFree(bool vocalFree) noexcept;

    void triggerFadeNow() noexcept; // Fast 4-bar blend (~5.0s-7.0s) on user Next button
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
    std::atomic<bool> paused_{false};

    // Filters and Processors
    LinkwitzRileyFilter deckAFilter_;
    LinkwitzRileyFilter deckBFilter_;
    LinkwitzRileyFilter deckBHpf200_; // 2nd-order LR HPF at 200 Hz for Deck B (Phase 1: t in [0.0, 0.55])
    WsolaTimeStretcher deckBStretcher_;
    BpmPhaseDetector deckADetector_;
    BpmPhaseDetector deckBDetector_;
    EchoTailProcessor deckAEcho_;

    ResonantFilter deckAHpfResonant_; // Resonant HPF (Q=1.25) swept from 60 Hz to 550 Hz on midpoint bass swap
    ResonantFilter deckAVocalDuckFilter_; // Dynamic -9.0 dB peaking cut (1200 Hz, Q=0.7) when Deck B has vocals
    SubtlePhaser subtlePhaser_;

    mutable std::mutex planMutex_;
    TransitionPlan currentPlan_{};
    std::atomic<bool> deckBVocalsActive_{false};
    std::atomic<bool> deckAVocalFree_{true};

    mutable std::mutex bufferMutex_;
    std::vector<float> deckBRingBuffer_;
    std::size_t deckBReadPos_{0};
    std::size_t deckBWritePos_{0};
    std::size_t deckBAvailableFrames_{0};

    std::size_t deckBLeadingTrimFrames_{0};
    std::int64_t deckBFirstBeatMs_{0};
    std::int64_t deckBDropBeatMs_{0};
    bool deckBTrimCalculated_{false};

    // 1-Bar Beat-Loop Exit Buffer for Deck A
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

    // Post-transition loudness and tempo restoration
    float activeGainOffsetDb_{0.0F};
    std::size_t postRestoreFramesElapsed_{0};
    std::size_t postRestoreTotalFrames_{0};

    // Outro energy tracking
    float baselineEnergy_{0.0F};
    float shortTermEnergy_{0.0F};
    std::size_t energyHistoryFrames_{0};

    // Scratch buffers
    std::vector<float> deckBScratch_;
    std::vector<float> deckBStretchedScratch_;
    std::vector<float> conversionScratch_;

    void startTransition(AutoMixState mode, int bars) noexcept;
    void detectDeckBFirstBeatAndSilence() noexcept;
    [[nodiscard]] static float applySoftKneeLimiter(float x) noexcept;
};

} // namespace lastwave::audio
