#include "TransitionPlanner.h"
#include "BpmPhaseDetector.h"

#include <cmath>
#include <algorithm>
#include <numeric>

namespace lastwave::audio {

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

namespace {

// Simple biquad bandpass filter helper
struct BiquadBP {
    double b0{0.0}, b1{0.0}, b2{0.0}, a1{0.0}, a2{0.0};
    double z1{0.0}, z2{0.0};

    void init(double sampleRate, double centerHz, double q) {
        if (sampleRate <= 0.0) sampleRate = 48000.0;
        const double w0 = 2.0 * M_PI * centerHz / sampleRate;
        const double alpha = std::sin(w0) / (2.0 * q);
        const double a0 = 1.0 + alpha;

        b0 = (alpha) / a0;
        b1 = 0.0;
        b2 = (-alpha) / a0;
        a1 = (-2.0 * std::cos(w0)) / a0;
        a2 = (1.0 - alpha) / a0;
        z1 = 0.0;
        z2 = 0.0;
    }

    float tick(float in) {
        const double x = static_cast<double>(in);
        const double y = b0 * x + z1;
        z1 = b1 * x - a1 * y + z2;
        z2 = b2 * x - a2 * y;
        return static_cast<float>(y);
    }
};

} // namespace

void TransitionPlanner::computeSpectralRatios(
    const float* stereoPcm,
    std::size_t frames,
    double sampleRate,
    std::vector<float>& outMidEnergyDb,
    std::vector<float>& outSubBassEnergyDb,
    std::vector<float>& outRmsDb
) noexcept {
    outMidEnergyDb.clear();
    outSubBassEnergyDb.clear();
    outRmsDb.clear();

    if (stereoPcm == nullptr || frames == 0 || sampleRate <= 0.0) return;

    // 100ms analysis frame
    const std::size_t hopFrames = static_cast<std::size_t>(sampleRate * 0.10);
    if (hopFrames == 0) return;

    // Filter 1: Sub-bass (center ~90 Hz, Q ~ 1.1 => ~60 Hz - 140 Hz)
    BiquadBP subBassFilter;
    subBassFilter.init(sampleRate, 90.0, 1.1);

    // Filter 2: Mid vocal band (center ~1200 Hz, Q ~ 0.35 => ~300 Hz - 3500 Hz)
    BiquadBP midBandFilter;
    midBandFilter.init(sampleRate, 1200.0, 0.35);

    const std::size_t numHops = frames / hopFrames;
    outMidEnergyDb.reserve(numHops);
    outSubBassEnergyDb.reserve(numHops);
    outRmsDb.reserve(numHops);

    for (std::size_t h = 0; h < numHops; ++h) {
        const std::size_t startFrame = h * hopFrames;
        double sumTotal = 0.0;
        double sumSubBass = 0.0;
        double sumMid = 0.0;

        for (std::size_t f = 0; f < hopFrames; ++f) {
            const std::size_t idx = (startFrame + f) * 2;
            const float mono = (stereoPcm[idx] + stereoPcm[idx + 1]) * 0.5f;

            sumTotal += mono * mono;
            const float sb = subBassFilter.tick(mono);
            sumSubBass += sb * sb;
            const float mid = midBandFilter.tick(mono);
            sumMid += mid * mid;
        }

        const double meanTotal = std::max(1e-12, sumTotal / hopFrames);
        const double meanSub = std::max(1e-12, sumSubBass / hopFrames);
        const double meanMid = std::max(1e-12, sumMid / hopFrames);

        const float rmsDb = static_cast<float>(10.0 * std::log10(meanTotal));
        const float subDb = static_cast<float>(10.0 * std::log10(meanSub));
        const float midDb = static_cast<float>(10.0 * std::log10(meanMid));

        outRmsDb.push_back(rmsDb);
        outSubBassEnergyDb.push_back(subDb);
        outMidEnergyDb.push_back(midDb);
    }
}

bool TransitionPlanner::isVocalActive(
    int64_t timeMs,
    const std::vector<LyricTimeInterval>& lyrics,
    const std::vector<float>& midEnergyDb,
    const std::vector<float>& subBassEnergyDb,
    int64_t bufferStartMs
) noexcept {
    // 1. First priority: Check exact synced lyrics if present
    for (const auto& line : lyrics) {
        if (timeMs >= line.startMs && timeMs <= line.endMs) {
            return true;
        }
    }

    // 2. Supplement with spectral mid vs sub-bass energy ratio
    const int64_t relMs = timeMs - bufferStartMs;
    if (relMs >= 0) {
        const std::size_t frameIdx = static_cast<std::size_t>(relMs / 100);
        if (frameIdx < midEnergyDb.size() && frameIdx < subBassEnergyDb.size()) {
            const float mid = midEnergyDb[frameIdx];
            const float sub = subBassEnergyDb[frameIdx];
            // Mid energy higher than sub-bass or vocal presence prominent (> -30 dBFS)
            if (mid > -28.0f && (mid - sub) > -4.0f) {
                return true;
            }
        }
    }

    return false;
}

float TransitionPlanner::computeGatedRmsDb(
    const float* stereoPcm,
    std::size_t frames
) noexcept {
    if (stereoPcm == nullptr || frames == 0) return -60.0f;

    // Gated RMS: ignore silent frames (< -50 dBFS)
    double energySum = 0.0;
    std::size_t activeCount = 0;

    for (std::size_t f = 0; f < frames; ++f) {
        const float l = stereoPcm[f * 2];
        const float r = stereoPcm[f * 2 + 1];
        const double monoSq = 0.5 * (l * l + r * r);
        if (monoSq > 1e-5) { // > -50 dBFS
            energySum += monoSq;
            activeCount++;
        }
    }

    if (activeCount == 0) return -60.0f;
    const double rms = std::sqrt(energySum / static_cast<double>(activeCount));
    return static_cast<float>(20.0 * std::log10(std::max(1e-4, rms)));
}

TransitionPlan TransitionPlanner::planTransition(
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
    PhraseLengthMode phraseMode,
    bool isManualSkip,
    int64_t currentDeckAPosMs
) noexcept {
    TransitionPlan plan;

    // 1. Detect BPM and Phase for Deck A and Deck B
    BpmPhaseDetector detectorA;
    detectorA.configure(sampleRateA);
    if (deckATailPcm != nullptr && deckATailFrames > 0) {
        detectorA.process(deckATailPcm, deckATailFrames);
    }
    const auto resultA = detectorA.getResult();
    plan.bpmA = (resultA.bpm >= 50.0f && resultA.bpm <= 220.0f) ? resultA.bpm : 120.0f;

    BpmPhaseDetector detectorB;
    detectorB.configure(sampleRateB);
    if (deckBHeadPcm != nullptr && deckBHeadFrames > 0) {
        detectorB.process(deckBHeadPcm, deckBHeadFrames);
    }
    const auto resultB = detectorB.getResult();
    plan.bpmB = (resultB.bpm >= 50.0f && resultB.bpm <= 220.0f) ? resultB.bpm : 120.0f;

    // Normalize Deck B's BPM for half/double-time compatibility
    float normBpmB = plan.bpmB;
    const float ratioRaw = plan.bpmA / normBpmB;
    if (ratioRaw >= 1.85f && ratioRaw <= 2.15f) {
        normBpmB *= 2.0f; // Half-time to double-time align
    } else if (ratioRaw >= 0.46f && ratioRaw <= 0.54f) {
        normBpmB *= 0.5f;
    }

    // Time-stretch ratio: lock within ±8%
    const float bpmDelta = (plan.bpmA - normBpmB) / normBpmB;
    if (std::abs(bpmDelta) <= 0.08f) {
        plan.bpmRatio = plan.bpmA / normBpmB;
    } else {
        plan.bpmRatio = 1.0f;
    }

    const double barDurationSec = (60.0 / static_cast<double>(plan.bpmA)) * 4.0;
    const int64_t barMs = static_cast<int64_t>(barDurationSec * 1000.0);
    const int64_t beatMs = std::max<int64_t>(250, barMs / 4);

    // 2. Spectral analysis for Deck A (Tail 30s) and Deck B (Head 35s)
    std::vector<float> midA, subA, rmsA;
    computeSpectralRatios(deckATailPcm, deckATailFrames, sampleRateA, midA, subA, rmsA);

    std::vector<float> midB, subB, rmsB;
    computeSpectralRatios(deckBHeadPcm, deckBHeadFrames, sampleRateB, midB, subB, rmsB);

    // 3. Spoken Intro Skit Detection on Deck B (first 35.0s)
    // Scan for quiet monologue, spoken skit, or ambient pad:
    // characterized by 300Hz-3.5kHz speech energy or < -20 dBFS RMS without periodic 60-140Hz kick
    int64_t beatDropMs = 0;
    bool skitFound = false;

    if (!rmsB.empty() && !subB.empty()) {
        int consecutiveKicks = 0;
        int64_t lastKickTimeMs = -1;
        int64_t expectedPeriodMs = beatMs;

        for (std::size_t i = 0; i < subB.size(); ++i) {
            const int64_t tMs = static_cast<int64_t>(i * 100);
            const float subDb = subB[i];
            const float midDb = (i < midB.size()) ? midB[i] : -60.0f;
            const float rDb = (i < rmsB.size()) ? rmsB[i] : -60.0f;

            // Spoken skit characteristic: mid speech energy > sub bass energy while total RMS < -18 dBFS
            if (tMs < 15000 && rDb < -18.0f && midDb > subDb + 3.0f) {
                skitFound = true;
            }

            // Real musical kick transient: sub-bass pulse with RMS > -15 dBFS
            const bool isKick = (subDb > -16.0f && rDb > -15.0f);
            if (isKick) {
                if (lastKickTimeMs >= 0) {
                    const int64_t delta = tMs - lastKickTimeMs;
                    if (std::abs(delta - expectedPeriodMs) <= expectedPeriodMs * 0.20 ||
                        std::abs(delta - expectedPeriodMs * 2) <= expectedPeriodMs * 0.20) {
                        consecutiveKicks++;
                    } else {
                        consecutiveKicks = 1;
                    }
                } else {
                    consecutiveKicks = 1;
                }
                lastKickTimeMs = tMs;

                if (consecutiveKicks >= 4) {
                    // Beat drop locked!
                    beatDropMs = lastKickTimeMs - (3 * expectedPeriodMs);
                    break;
                }
            }
        }
    }

    if (skitFound && beatDropMs > 4000) {
        plan.isSkitSkipped = true;
        // Set Deck B's entry cue exactly 1 bar or 2 bars before beat drop
        const int64_t preDropLeadMs = (beatDropMs >= barMs * 2) ? (barMs * 2) : barMs;
        plan.inCueMs = std::max<int64_t>(0, beatDropMs - preDropLeadMs);
    } else {
        plan.isSkitSkipped = false;
        plan.inCueMs = 0;
    }

    // Never Enter Mid-Sentence: ensure inCueMs never lands in the middle of a sung line or rap sentence
    for (const auto& line : lyricsB) {
        if (plan.inCueMs >= line.startMs && plan.inCueMs <= line.endMs) {
            // Landed mid-sentence: advance inCueMs cleanly to before or after the phrase
            if (line.startMs > 500 && plan.inCueMs - line.startMs < 1000) {
                plan.inCueMs = std::max<int64_t>(0, line.startMs - 200);
            } else {
                plan.inCueMs = line.endMs + 100;
            }
        }
    }

    // 4. Outgoing Deck A Vocal End Detection & Transition Start (outCueMs)
    const int64_t window30sStartMs = std::max<int64_t>(0, durationMsA - 30000);
    int64_t lastVocalEndMs = window30sStartMs;
    bool foundVocalEndInWindow = false;

    // Check lyrics in final 30s window
    for (const auto& line : lyricsA) {
        if (line.endMs >= window30sStartMs && line.startMs <= durationMsA - 4000) {
            if (line.endMs > lastVocalEndMs) {
                lastVocalEndMs = line.endMs;
                foundVocalEndInWindow = true;
            }
        }
    }

    // Supplement with spectral mid-band vocal drop (where 300Hz-3.5kHz drops >= 6 dB relative to drum bed)
    if (!foundVocalEndInWindow && !midA.empty() && !subA.empty()) {
        const std::size_t startHop = (window30sStartMs > durationMsA - 30000)
            ? static_cast<std::size_t>((window30sStartMs - (durationMsA - 30000)) / 100) : 0;

        for (std::size_t i = startHop; i < midA.size(); ++i) {
            const float midDb = midA[i];
            const float subDb = subA[i];
            if ((subDb - midDb) >= 6.0f && midDb < -26.0f) {
                lastVocalEndMs = window30sStartMs + static_cast<int64_t>(i * 100);
                foundVocalEndInWindow = true;
                break;
            }
        }
    }

    if (isManualSkip) {
        // Automix on Skip: start immediately from Deck A current position
        plan.outCueMs = currentDeckAPosMs;
    } else {
        // Snap outCueMs to the first downbeat on or immediately after vocal phrase ends
        int64_t candidateOutCue = lastVocalEndMs;
        if (barMs > 0) {
            const int64_t rem = candidateOutCue % barMs;
            if (rem > 0) {
                candidateOutCue += (barMs - rem); // Snap to next downbeat
            }
        }
        // Hard 30-Second Window Clamp: strictly clamped to [durationMs - 30_000, durationMs - 4_000]
        const int64_t clampMin = std::max<int64_t>(0, durationMsA - 30000);
        const int64_t clampMax = std::max<int64_t>(clampMin, durationMsA - 4000);
        plan.outCueMs = std::clamp(candidateOutCue, clampMin, clampMax);
    }

    // 5. Phrase Length Selection
    if (isManualSkip) {
        // Fast 4-bar blend for manual skip (~5.0s - 7.0s)
        plan.bars = 4;
        const double fastSec = 4.0 * barDurationSec;
        plan.durationMs = static_cast<int64_t>(std::clamp(fastSec * 1000.0, 4500.0, 7500.0));
    } else if (phraseMode == PhraseLengthMode::Bars8) {
        plan.bars = 8;
        const double sec = 8.0 * barDurationSec;
        plan.durationMs = static_cast<int64_t>(std::clamp(sec * 1000.0, 6000.0, 18000.0));
    } else if (phraseMode == PhraseLengthMode::Bars16) {
        plan.bars = 16;
        const double sec = 16.0 * barDurationSec;
        plan.durationMs = static_cast<int64_t>(std::clamp(sec * 1000.0, 10000.0, 26000.0));
    } else { // Auto Mode
        // If Deck B has immediate lead vocals within 2 bars of inCueMs, select tight 2-Bar or 4-Bar
        const bool deckBImmediateVocals = isVocalActive(
            plan.inCueMs + (barMs / 2), lyricsB, midB, subB, 0);

        if (deckBImmediateVocals) {
            plan.bars = 4;
            const double sec = 4.0 * barDurationSec;
            plan.durationMs = static_cast<int64_t>(std::clamp(sec * 1000.0, 3500.0, 7000.0));
        } else {
            // Pure instrumental intro beat -> select 8-bar club phrase blend
            plan.bars = 8;
            const double sec = 8.0 * barDurationSec;
            plan.durationMs = static_cast<int64_t>(std::clamp(sec * 1000.0, 8000.0, 14000.0));
        }
    }

    // 6. Loudness Matching (Zero Volume Cliffs)
    // Compute gated RMS of Deck A tail 15s and Deck B head 15s
    const std::size_t frames15sA = static_cast<std::size_t>(std::min<double>(sampleRateA * 15.0, static_cast<double>(deckATailFrames)));
    const std::size_t frames15sB = static_cast<std::size_t>(std::min<double>(sampleRateB * 15.0, static_cast<double>(deckBHeadFrames)));

    const float rmsA_dB = computeGatedRmsDb(
        (deckATailFrames >= frames15sA) ? (deckATailPcm + (deckATailFrames - frames15sA) * 2) : deckATailPcm,
        frames15sA
    );
    const float rmsB_dB = computeGatedRmsDb(deckBHeadPcm, frames15sB);

    // Attenuation only: clamp(rmsA_dB - rmsB_dB, -6.0f, 0.0f) to prevent clipping/distortion
    plan.gainOffsetDb = std::clamp(rmsA_dB - rmsB_dB, -6.0f, 0.0f);

    // 7. Vocal-Safe Loop / Beat-Repeat Slicer Eligibility on Deck A (t >= 0.55)
    // Check if Deck A is vocal-free from outCueMs + 0.55 * durationMs to track end
    const int64_t loopCheckStartMs = plan.outCueMs + static_cast<int64_t>(plan.durationMs * 0.55);
    plan.isVocalSafeLoop = !isVocalActive(loopCheckStartMs, lyricsA, midA, subA, window30sStartMs);

    // 8. Single Texture FX Auto-Chooser
    // Deterministically select at most one subtle transition texture
    if (rmsA_dB > -11.0f && plan.bars >= 8) {
        plan.textureFx = TransitionTextureFx::ResonantFilterSweep;
    } else if (!skitFound && rmsB_dB > -14.0f && plan.bars >= 8) {
        plan.textureFx = TransitionTextureFx::StereoWidthExpander;
    } else if (plan.bars >= 8) {
        plan.textureFx = TransitionTextureFx::SubtlePhaser;
    } else {
        plan.textureFx = TransitionTextureFx::None;
    }

    return plan;
}

} // namespace lastwave::audio
