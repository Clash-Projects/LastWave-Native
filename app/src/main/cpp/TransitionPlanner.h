#pragma once

#include <cstddef>
#include <cstdint>
#include <vector>
#include <string>
#include <algorithm>
#include <cmath>

namespace lastwave::audio {

enum class PhraseLengthMode : int32_t {
    Auto = 0,
    Bars8 = 8,
    Bars16 = 16,
    FastSkip4 = 4
};

enum class TransitionTextureFx : int32_t {
    None = 0,
    ResonantFilterSweep = 1,
    StereoWidthExpander = 2,
    SubtlePhaser = 3
};

struct LyricTimeInterval {
    int64_t startMs{0};
    int64_t endMs{0};
    bool isWordSynced{false};
};

struct TransitionPlan {
    int64_t outCueMs{0};           // Deck A start of transition (clamped to [dur-30s, dur-4s])
    int64_t inCueMs{0};            // Deck B entry point (skips spoken skits, snapped to 1-2 bars pre-drop)
    int64_t durationMs{8000};      // Overlap blend duration in milliseconds
    int32_t bars{8};               // Number of bars in overlap
    float bpmA{120.0f};
    float bpmB{120.0f};
    float bpmRatio{1.0f};          // WSOLA time-stretch ratio for Deck B
    float gainOffsetDb{0.0f};      // Loudness makeup gain for Deck B
    TransitionTextureFx textureFx{TransitionTextureFx::None};
    bool isVocalSafeLoop{true};    // Deck A safe for beat-repeat / 1-bar loop
    bool isSkitSkipped{false};
};

class TransitionPlanner final {
public:
    static constexpr double kMinBpm = 50.0;
    static constexpr double kMaxBpm = 220.0;

    TransitionPlanner() noexcept = default;

    /**
     * Computes the deterministic club transition plan between Deck A and Deck B.
     * Inspects only the tail 30.0s of Deck A and the head 35.0s of Deck B.
     */
    static TransitionPlan planTransition(
        int64_t durationMsA,
        const float* deckATailPcm,
        std::size_t deckATailFrames,
        double sampleRateA,
        const std::vector<LyricTimeInterval>& lyricsA,
        int64_t durationMsB,
        const float* deckBHeadPcm,
        std::size_t deckBHeadFrames,
        double sampleRateB,
        const std::vector<LyricTimeInterval>& lyricsB,
        PhraseLengthMode phraseMode = PhraseLengthMode::Auto,
        bool isManualSkip = false,
        int64_t currentDeckAPosMs = 0
    ) noexcept;

    // Spectral Ratio: 300-3500 Hz mid vs 60-140 Hz sub-bass in 100ms frames
    static void computeSpectralRatios(
        const float* stereoPcm,
        std::size_t frames,
        double sampleRate,
        std::vector<float>& outMidEnergyDb,
        std::vector<float>& outSubBassEnergyDb,
        std::vector<float>& outRmsDb
    ) noexcept;

    // Detects whether a vocal line is singing at a given millisecond timestamp
    static bool isVocalActive(
        int64_t timeMs,
        const std::vector<LyricTimeInterval>& lyrics,
        const std::vector<float>& midEnergyDb,
        const std::vector<float>& subBassEnergyDb,
        int64_t bufferStartMs = 0
    ) noexcept;

    // Gated RMS calculation in dBFS
    static float computeGatedRmsDb(
        const float* stereoPcm,
        std::size_t frames
    ) noexcept;
};

} // namespace lastwave::audio
