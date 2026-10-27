#include "AutoMixEngine.h"

#include <cmath>
#include <cstring>
#include <algorithm>

namespace lastwave::audio {

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

// ============================================================================
// ResonantFilter Implementation
// ============================================================================

void ResonantFilter::update(Mode mode, double sampleRate, double cutoffHz, double q, double gainDb) noexcept {
    if (sampleRate <= 0.0) sampleRate = 48000.0;
    cutoffHz = std::clamp(cutoffHz, 10.0, sampleRate * 0.48);
    q = std::clamp(q, 0.1, 0.7071);

    const double w0 = 2.0 * M_PI * cutoffHz / sampleRate;
    const double cosW0 = std::cos(w0);
    const double sinW0 = std::sin(w0);
    const double alpha = sinW0 / (2.0 * q);

    if (mode == Mode::HighPass) {
        const double a0 = 1.0 + alpha;
        b0 = ((1.0 + cosW0) * 0.5) / a0;
        b1 = (-(1.0 + cosW0)) / a0;
        b2 = ((1.0 + cosW0) * 0.5) / a0;
        a1 = (-2.0 * cosW0) / a0;
        a2 = (1.0 - alpha) / a0;
    } else if (mode == Mode::LowPass) {
        const double a0 = 1.0 + alpha;
        b0 = ((1.0 - cosW0) * 0.5) / a0;
        b1 = (1.0 - cosW0) / a0;
        b2 = ((1.0 - cosW0) * 0.5) / a0;
        a1 = (-2.0 * cosW0) / a0;
        a2 = (1.0 - alpha) / a0;
    } else { // Mode::PeakingCut
        const double A = std::pow(10.0, gainDb / 40.0);
        const double a0 = 1.0 + alpha / A;
        b0 = (1.0 + alpha * A) / a0;
        b1 = (-2.0 * cosW0) / a0;
        b2 = (1.0 - alpha * A) / a0;
        a1 = (-2.0 * cosW0) / a0;
        a2 = (1.0 - alpha / A) / a0;
    }
}

void ResonantFilter::reset() noexcept {
    z1.fill(0.0);
    z2.fill(0.0);
}

float ResonantFilter::tick(float in, std::size_t ch) noexcept {
    const double x = static_cast<double>(in);
    const double y = b0 * x + z1[ch];
    z1[ch] = b1 * x - a1 * y + z2[ch];
    z2[ch] = b2 * x - a2 * y;

    if (std::abs(z1[ch]) < 1.0e-20) z1[ch] = 0.0;
    if (std::abs(z2[ch]) < 1.0e-20) z2[ch] = 0.0;
    if (!std::isfinite(y)) return 0.0f;

    return static_cast<float>(y);
}

// ============================================================================
// SubtlePhaser Implementation
// ============================================================================

void SubtlePhaser::process(float& left, float& right, float send) noexcept {
    if (send <= 0.001f) return;

    // 0.2 Hz slow modulation
    lfoPhase += (2.0 * M_PI * 0.2) / sampleRate;
    if (lfoPhase >= 2.0 * M_PI) lfoPhase -= 2.0 * M_PI;

    const double lfo = 0.5 * (1.0 + std::sin(lfoPhase));
    const double modCutoff = 400.0 + lfo * 1600.0; // 400 Hz to 2000 Hz sweep
    const double w0 = 2.0 * M_PI * modCutoff / sampleRate;
    const double a = (1.0 - std::tan(w0 * 0.5)) / (1.0 + std::tan(w0 * 0.5));

    // 4-stage allpass for left
    double sL = static_cast<double>(left);
    for (std::size_t i = 0; i < 4; ++i) {
        const double apOut = a * sL + allpassStateL[i];
        allpassStateL[i] = sL - a * apOut;
        sL = apOut;
    }

    // 4-stage allpass for right (phase offset)
    double sR = static_cast<double>(right);
    for (std::size_t i = 0; i < 4; ++i) {
        const double apOut = a * sR + allpassStateR[i];
        allpassStateR[i] = sR - a * apOut;
        sR = apOut;
    }

    left = left * (1.0f - send * 0.5f) + static_cast<float>(sL) * (send * 0.5f);
    right = right * (1.0f - send * 0.5f) + static_cast<float>(sR) * (send * 0.5f);
}

// ============================================================================
// AutoMixEngine Implementation
// ============================================================================

AutoMixEngine::AutoMixEngine() noexcept
    : deckBRingBuffer_(192000 * 40 * 2) {
    configure(48000.0);
}

void AutoMixEngine::configure(double sampleRate) noexcept {
    if (sampleRate <= 0.0) sampleRate = 48000.0;
    sampleRate_ = sampleRate;

    deckAHpfL_.reset();
    deckAHpfR_.reset();
    deckBHpfL_.reset();
    deckBHpfR_.reset();
    currentGainA_ = 1.0f;
    currentGainB_ = 0.0f;
    currentCutoffA_ = 20.0f;
    currentCutoffB_ = 200.0f;

    deckBStretcher_.configure(sampleRate_, 2);
    deckADetector_.configure(sampleRate_);
    deckBDetector_.configure(sampleRate_);
    deckAEcho_.configure(sampleRate_, 120.0f);
    subtlePhaser_.configure(sampleRate_);

    deckAVocalDuckFilter_.update(ResonantFilter::Mode::PeakingCut, sampleRate_, 1200.0, 0.7, -9.0);

    deckBRingBuffer_.clear();
    deckBLeadingTrimFrames_ = 0;
    deckBFirstBeatMs_ = 0;
    deckBDropBeatMs_ = 0;
    deckBTrimCalculated_ = false;

    const std::size_t rollFrames = static_cast<std::size_t>(sampleRate_ * 6.0); // 6s rolling buffer
    deckARollingBuffer_.assign(rollFrames * 2, 0.0f);
    deckARollingWritePos_ = 0;
    deckAOneBarLoopBuffer_.assign(rollFrames * 2, 0.0f);
    deckALoopFrames_ = 0;
    deckALoopReadPos_ = 0;
    deckALoopActive_ = false;

    deckBScratch_.assign(16384 * 2, 0.0f);
    deckBStretchedScratch_.assign(32768 * 2, 0.0f);
    conversionScratch_.assign(32768 * 2, 0.0f);
}

void AutoMixEngine::setEnabled(bool enabled) noexcept {
    enabled_.store(enabled, std::memory_order_release);
    if (!enabled) {
        reset();
    }
}

bool AutoMixEngine::isEnabled() const noexcept {
    return enabled_.load(std::memory_order_acquire);
}

void AutoMixEngine::setBarMode(int bars) noexcept {
    barMode_.store(bars, std::memory_order_release);
}

int AutoMixEngine::getBarMode() const noexcept {
    return barMode_.load(std::memory_order_acquire);
}

void AutoMixEngine::setPaused(bool paused) noexcept {
    paused_.store(paused, std::memory_order_release);
}

bool AutoMixEngine::isPaused() const noexcept {
    return paused_.load(std::memory_order_acquire);
}

void AutoMixEngine::setTransitionPlan(const TransitionPlan& plan) noexcept {
    {
        std::lock_guard<std::mutex> lock(planMutex_);
        currentPlan_ = plan;
    }

    planBpmA_.store(plan.bpmA, std::memory_order_relaxed);
    planBpmB_.store(plan.bpmB, std::memory_order_relaxed);
    planBpmRatio_.store(plan.bpmRatio, std::memory_order_relaxed);
    planGainOffsetDb_.store(std::clamp(plan.gainOffsetDb, -6.0f, 0.0f), std::memory_order_relaxed);
    planTextureFx_.store(static_cast<int>(plan.textureFx), std::memory_order_relaxed);

    if (plan.bpmRatio > 0.0f && std::abs(plan.bpmRatio - 1.0f) <= 0.08f) {
        deckBStretcher_.setTimeRatio(plan.bpmRatio);
    } else {
        deckBStretcher_.setTimeRatio(1.0f);
    }

    deckAEcho_.configure(sampleRate_, plan.bpmA);
    activeGainOffsetDb_ = std::clamp(plan.gainOffsetDb, -6.0f, 0.0f);
}

TransitionPlan AutoMixEngine::getTransitionPlan() const noexcept {
    std::lock_guard<std::mutex> lock(planMutex_);
    return currentPlan_;
}

void AutoMixEngine::setDeckBVocalsActive(bool active) noexcept {
    deckBVocalsActive_.store(active, std::memory_order_release);
}

void AutoMixEngine::setDeckAVocalFree(bool vocalFree) noexcept {
    deckAVocalFree_.store(vocalFree, std::memory_order_release);
}

void AutoMixEngine::startTransition(int durationMs) noexcept {
    const int bars = barMode_.load(std::memory_order_acquire);
    startTransition(AutoMixState::FadingAuto, (bars > 0) ? bars : 8);
    if (durationMs > 0) {
        transitionTotalFrames_ = static_cast<std::size_t>(static_cast<double>(durationMs) * 0.001 * sampleRate_);
    }
}

void AutoMixEngine::triggerFadeNow() noexcept {
    // Fast 4-bar blend (~5.0s-7.0s) on manual Next skip
    startTransition(AutoMixState::FadingFast, 4);
}

void AutoMixEngine::startTransition(AutoMixState mode, int bars) noexcept {
    state_.store(mode, std::memory_order_release);
    transitionCurrentFrame_ = 0;
    transitionProgress_ = 0.0f;
    bassSwapped_ = false;

    const float bpmA = deckADetector_.getResult().bpm;
    const float safeBpmA = (bpmA >= 50.0f && bpmA <= 220.0f) ? bpmA : 120.0f;
    const double barSec = (60.0 / static_cast<double>(safeBpmA)) * 4.0;
    const double totalSec = static_cast<double>(bars) * barSec;

    transitionTotalFrames_ = static_cast<std::size_t>(std::clamp(totalSec, 4.0, 26.0) * sampleRate_);
    postRestoreFramesElapsed_ = 0;
    postRestoreTotalFrames_ = static_cast<std::size_t>(sampleRate_ * 10.0); // 10s loudness decay

    // Setup WSOLA ratio for Deck B
    const float bpmB = deckBDetector_.getResult().bpm;
    const float safeBpmB = (bpmB >= 50.0f && bpmB <= 220.0f) ? bpmB : 120.0f;

    float normBpmB = safeBpmB;
    const float ratioRaw = safeBpmA / normBpmB;
    if (ratioRaw >= 1.85f && ratioRaw <= 2.15f) {
        normBpmB *= 2.0f;
    } else if (ratioRaw >= 0.46f && ratioRaw <= 0.54f) {
        normBpmB *= 0.5f;
    }

    if (std::abs(safeBpmA - normBpmB) / normBpmB <= 0.08f) {
        deckBStretcher_.setTimeRatio(safeBpmA / normBpmB);
    } else {
        deckBStretcher_.setTimeRatio(1.0f);
    }

    deckAHpfL_.reset();
    deckAHpfR_.reset();
    deckBHpfL_.reset();
    deckBHpfR_.reset();
    currentGainA_ = 1.0f;
    currentGainB_ = 0.0f;
    currentCutoffA_ = 20.0f;
    currentCutoffB_ = 220.0f;

    deckAVocalDuckFilter_.reset();
    deckAVocalDuckFilter_.update(ResonantFilter::Mode::PeakingCut, sampleRate_, 1200.0, 0.7, -9.0);
    subtlePhaser_.reset();

    deckAEcho_.configure(sampleRate_, safeBpmA);
    deckAEcho_.setEnabled(true);
}

void AutoMixEngine::setTransitionProgress(float progress) noexcept {
    transitionProgress_ = std::clamp(progress, 0.0f, 1.0f);
    if (transitionTotalFrames_ > 0) {
        transitionCurrentFrame_ = static_cast<std::size_t>(transitionProgress_ * static_cast<float>(transitionTotalFrames_));
    }
    AutoMixState curr = state_.load(std::memory_order_acquire);
    if (curr == AutoMixState::Idle || curr == AutoMixState::MonitoringOutro) {
        state_.store(AutoMixState::FadingAuto, std::memory_order_release);
    }
    if (transitionProgress_ >= 1.0f) {
        deckBStretcher_.startRecoveryRamp(1.0f, 6.0f); // 6.0s recovery glide back to 1.000f
        deckAEcho_.setEnabled(false);
        deckALoopActive_ = false;
        state_.store(AutoMixState::PostRestore, std::memory_order_release);
    }
}

void AutoMixEngine::reset() noexcept {
    state_.store(AutoMixState::Idle, std::memory_order_release);
    transitionCurrentFrame_ = 0;
    transitionTotalFrames_ = 0;
    transitionProgress_ = 0.0f;
    bassSwapped_ = false;
    paused_.store(false, std::memory_order_release);

    deckBStretcher_.reset();
    deckADetector_.reset();
    deckBDetector_.reset();
    deckAEcho_.reset();
    deckAEcho_.setEnabled(false);
    subtlePhaser_.reset();

    deckAHpfL_.reset();
    deckAHpfR_.reset();
    deckBHpfL_.reset();
    deckBHpfR_.reset();
    currentGainA_ = 1.0f;
    currentGainB_ = 0.0f;
    currentCutoffA_ = 20.0f;
    currentCutoffB_ = 200.0f;
    deckAVocalDuckFilter_.reset();

    deckALowL_ = 0.0f;
    deckALowR_ = 0.0f;
    deckALpHighL_ = 0.0f;
    deckALpHighR_ = 0.0f;
    deckBLowL_ = 0.0f;
    deckBLowR_ = 0.0f;
    deckBLpHighL_ = 0.0f;
    deckBLpHighR_ = 0.0f;
    smoothTA_ = 0.0f;
    smoothTB_ = 0.0f;
    filterInitA_ = false;
    filterInitB_ = false;

    deckBRingBuffer_.clear();
    deckBLeadingTrimFrames_ = 0;
    deckBFirstBeatMs_ = 0;
    deckBDropBeatMs_ = 0;
    deckBTrimCalculated_ = false;

    deckALoopActive_ = false;
    deckALoopFrames_ = 0;
    deckALoopReadPos_ = 0;
    deckARollingWritePos_ = 0;

    baselineEnergy_ = 0.0f;
    shortTermEnergy_ = 0.0f;
    energyHistoryFrames_ = 0;
}

std::size_t AutoMixEngine::writeDeckBPcm(const float* stereoPcm, std::size_t frames) noexcept {
    if (stereoPcm == nullptr || frames == 0) return 0;

    const std::size_t writtenSamples = deckBRingBuffer_.write(stereoPcm, frames * 2);
    const std::size_t writtenFrames = writtenSamples / 2;

    deckBDetector_.process(stereoPcm, writtenFrames);

    if (!deckBTrimCalculated_ && deckBRingBuffer_.availableToRead() / 2 >= static_cast<std::size_t>(sampleRate_ * 3.0)) {
        detectDeckBFirstBeatAndSilence();
    }

    return writtenFrames;
}

void AutoMixEngine::detectDeckBFirstBeatAndSilence() noexcept {
    const std::size_t availableFrames = deckBRingBuffer_.availableToRead() / 2;
    if (availableFrames < static_cast<std::size_t>(sampleRate_ * 1.5)) return;

    const std::size_t searchLimit = std::min(
        availableFrames,
        static_cast<std::size_t>(sampleRate_ * 35.0));

    std::vector<float> searchBuf(searchLimit * 2);
    const std::size_t peekedSamples = deckBRingBuffer_.peek(searchBuf.data(), searchLimit * 2);
    const std::size_t peekedFrames = peekedSamples / 2;
    if (peekedFrames < static_cast<std::size_t>(sampleRate_ * 1.5)) return;

    // 2nd-order Butterworth filters: 60-140 Hz sub-bass kick (Q = 0.7071)
    const double wKick = 2.0 * M_PI * 95.0 / sampleRate_;
    const double alphaKick = std::sin(wKick) / (2.0 * 0.7071);
    const double a0K = 1.0 + alphaKick;
    const double b0K = alphaKick / a0K, b1K = 0.0, b2K = -alphaKick / a0K;
    const double a1K = (-2.0 * std::cos(wKick)) / a0K, a2K = (1.0 - alphaKick) / a0K;

    double kZ1 = 0.0, kZ2 = 0.0;
    constexpr std::size_t B = 960; // 20ms blocks
    const std::size_t totalBlocks = searchLimit / B;

    struct OnsetEvent {
        double timeSec{0.0};
        bool isKick{false};
    };
    std::vector<OnsetEvent> onsets;

    for (std::size_t b = 0; b < totalBlocks; ++b) {
        double kSum = 0.0, peak = 0.0;
        for (std::size_t j = 0; j < B; ++j) {
            const std::size_t idx = b * B + j;
            const double mono = (searchBuf[idx * 2] + searchBuf[idx * 2 + 1]) * 0.5;

            const double kOut = b0K * mono + kZ1;
            kZ1 = b1K * mono - a1K * kOut + kZ2;
            kZ2 = b2K * mono - a2K * kOut;
            kSum += kOut * kOut;

            const double absMono = std::abs(mono);
            if (absMono > peak) peak = absMono;
        }

        const float kRms = static_cast<float>(std::sqrt(kSum / B));
        // -16 dBFS threshold in linear float amplitude
        constexpr float kKickThreshold = 0.158489319f;
        if (kRms >= kKickThreshold) {
            const double tSec = static_cast<double>(b * B) / sampleRate_;
            onsets.push_back({tSec, true});
        }
    }

    double dropBeatTimeSec = 0.0;
    double entryCueTimeSec = 0.0;
    bool foundGridLock = false;

    // Find 4 consecutive periodic kick transients
    for (std::size_t i = 0; i < onsets.size(); ++i) {
        if (!onsets[i].isKick) continue;
        const double t0 = onsets[i].timeSec;

        for (double testBpm = 75.0; testBpm <= 175.0; testBpm += 1.0) {
            const double T = 60.0 / testBpm;
            int matched = 1;
            for (int step = 1; step < 4; ++step) {
                const double targetTime = t0 + static_cast<double>(step) * T;
                for (std::size_t k = i + 1; k < onsets.size(); ++k) {
                    if (std::abs(onsets[k].timeSec - targetTime) <= 0.045) {
                        matched++;
                        break;
                    }
                }
            }
            if (matched >= 4) {
                foundGridLock = true;
                dropBeatTimeSec = t0;
                entryCueTimeSec = (t0 >= 4.0 * T) ? (t0 - 4.0 * T) : t0; // 1 bar before drop if available, else first beat
                break;
            }
        }
        if (foundGridLock) break;
    }

    if (!foundGridLock) {
        // Fallback: silence threshold search
        constexpr float kSilenceThreshold = 0.00562341325f;
        for (std::size_t f = 0; f < searchLimit; ++f) {
            const float l = std::abs(searchBuf[f * 2]);
            const float r = std::abs(searchBuf[f * 2 + 1]);
            if (l >= kSilenceThreshold || r >= kSilenceThreshold) {
                dropBeatTimeSec = static_cast<double>(f) / sampleRate_;
                entryCueTimeSec = dropBeatTimeSec;
                foundGridLock = true;
                break;
            }
        }
    }

    const std::size_t chosenCueFrame = static_cast<std::size_t>(entryCueTimeSec * sampleRate_);
    if (chosenCueFrame > 0 && chosenCueFrame < availableFrames) {
        deckBRingBuffer_.discard(chosenCueFrame * 2);
        deckBLeadingTrimFrames_ = chosenCueFrame;
    }

    deckBFirstBeatMs_ = static_cast<std::int64_t>(entryCueTimeSec * 1000.0);
    deckBDropBeatMs_ = static_cast<std::int64_t>(dropBeatTimeSec * 1000.0);
    deckBTrimCalculated_ = true;
}
float AutoMixEngine::applySoftKneeLimiter(float x) noexcept {
    const float s = std::tanh(x * 0.85f) * 1.12f;
    return std::clamp(s, -0.999f, 0.999f);
}

static inline float calcSCurve(float x) noexcept {
    const float c = std::clamp(x, 0.0f, 1.0f);
    return 0.5f - 0.5f * std::cos(c * static_cast<float>(M_PI));
}

void AutoMixEngine::processDeckA(float* deckAStereo, std::size_t frames, float progress) noexcept {
    if (deckAStereo == nullptr || frames == 0) return;
    if (paused_.load(std::memory_order_acquire)) return;

    const float targetT = std::clamp(progress, 0.0f, 1.0f);
    const float alphaLow = std::clamp(static_cast<float>(2.0 * M_PI * 200.0 / sampleRate_), 0.005f, 0.08f);
    const float alphaHigh = std::clamp(static_cast<float>(2.0 * M_PI * 3200.0 / sampleRate_), 0.05f, 0.55f);

    for (std::size_t f = 0; f < frames; ++f) {
        smoothTA_ += 0.0003f * (targetT - smoothTA_);
        const float st = std::clamp(smoothTA_, 0.0f, 1.0f);

        const float inL = deckAStereo[f * 2];
        const float inR = deckAStereo[f * 2 + 1];

        if (st <= 0.0001f) {
            continue;
        }

        if (!filterInitA_) {
            deckALowL_ = inL;
            deckALowR_ = inR;
            deckALpHighL_ = inL;
            deckALpHighR_ = inR;
            filterInitA_ = true;
        } else {
            deckALowL_ += alphaLow * (inL - deckALowL_);
            deckALowR_ += alphaLow * (inR - deckALowR_);
            deckALpHighL_ += alphaHigh * (inL - deckALpHighL_);
            deckALpHighR_ += alphaHigh * (inR - deckALpHighR_);
        }

        // Exact 3-band split where (low + mid + high == in) with ZERO phase blur:
        const float midL = deckALpHighL_ - deckALowL_;
        const float midR = deckALpHighR_ - deckALowR_;
        const float highL = inL - deckALpHighL_;
        const float highR = inR - deckALpHighR_;

        // High Band (>3200 Hz): Blends across the entire 9.0 seconds
        const float highGainA = std::cos(st * static_cast<float>(M_PI) * 0.5f);
        // Low Band (<200 Hz): Smoothly swaps in the middle (t = 0.30 -> 0.65)
        const float lowGainA = 1.0f - calcSCurve((st - 0.30f) / 0.35f);
        // Mid Band (200-3200 Hz): Staggered in pure stereo
        const float midGainA = 1.0f - calcSCurve((st - 0.05f) / 0.50f);

        const float headroom = 1.0f - 0.18f * std::sin(st * static_cast<float>(M_PI));
        const float outL = (deckALowL_ * lowGainA + midL * midGainA + highL * highGainA) * headroom;
        const float outR = (deckALowR_ * lowGainA + midR * midGainA + highR * highGainA) * headroom;

        deckAStereo[f * 2] = std::clamp(outL, -0.999f, 0.999f);
        deckAStereo[f * 2 + 1] = std::clamp(outR, -0.999f, 0.999f);
    }
}

void AutoMixEngine::processDeckB(float* deckBStereo, std::size_t frames, float progress) noexcept {
    if (deckBStereo == nullptr || frames == 0) return;
    if (paused_.load(std::memory_order_acquire)) return;

    const float targetT = std::clamp(progress, 0.0f, 1.0f);
    const float alphaLow = std::clamp(static_cast<float>(2.0 * M_PI * 200.0 / sampleRate_), 0.005f, 0.08f);
    const float alphaHigh = std::clamp(static_cast<float>(2.0 * M_PI * 3200.0 / sampleRate_), 0.05f, 0.55f);

    for (std::size_t f = 0; f < frames; ++f) {
        smoothTB_ += 0.0003f * (targetT - smoothTB_);
        const float st = std::clamp(smoothTB_, 0.0f, 1.0f);

        const float inL = deckBStereo[f * 2];
        const float inR = deckBStereo[f * 2 + 1];

        if (st >= 0.9999f) {
            continue;
        }

        if (!filterInitB_) {
            deckBLowL_ = inL;
            deckBLowR_ = inR;
            deckBLpHighL_ = inL;
            deckBLpHighR_ = inR;
            filterInitB_ = true;
        } else {
            deckBLowL_ += alphaLow * (inL - deckBLowL_);
            deckBLowR_ += alphaLow * (inR - deckBLowR_);
            deckBLpHighL_ += alphaHigh * (inL - deckBLpHighL_);
            deckBLpHighR_ += alphaHigh * (inR - deckBLpHighR_);
        }

        // Exact 3-band split where (low + mid + high == in) with ZERO phase blur:
        const float midL = deckBLpHighL_ - deckBLowL_;
        const float midR = deckBLpHighR_ - deckBLowR_;
        const float highL = inL - deckBLpHighL_;
        const float highR = inR - deckBLpHighR_;

        // High Band (>3200 Hz): Blends across the entire 9.0 seconds
        const float highGainB = std::sin(st * static_cast<float>(M_PI) * 0.5f);
        // Low Band (<200 Hz): Smoothly swaps in the middle (t = 0.30 -> 0.65)
        const float lowGainB = calcSCurve((st - 0.30f) / 0.35f);
        // Mid Band (200-3200 Hz): Staggered in pure stereo
        const float midGainB = calcSCurve((st - 0.35f) / 0.50f);

        const float headroom = 1.0f - 0.18f * std::sin(st * static_cast<float>(M_PI));
        const float outL = (deckBLowL_ * lowGainB + midL * midGainB + highL * highGainB) * headroom;
        const float outR = (deckBLowR_ * lowGainB + midR * midGainB + highR * highGainB) * headroom;

        deckBStereo[f * 2] = std::clamp(outL, -0.999f, 0.999f);
        deckBStereo[f * 2 + 1] = std::clamp(outR, -0.999f, 0.999f);
    }
}

void AutoMixEngine::process(float* deckAStereo, std::size_t frames) noexcept {
    if (deckAStereo == nullptr || frames == 0) return;
    if (!enabled_.load(std::memory_order_acquire)) return;
    if (paused_.load(std::memory_order_acquire)) return;

    // Strict lock-free real-time audio thread: zero FFT/BPM calculations on audio thread
    feedDeckARollingBuffer(deckAStereo, frames);

    AutoMixState currState = state_.load(std::memory_order_acquire);

    if (currState == AutoMixState::MonitoringOutro) {
        double frameEnergySum = 0.0;
        for (std::size_t i = 0; i < frames; ++i) {
            const float l = deckAStereo[i * 2];
            const float r = deckAStereo[i * 2 + 1];
            frameEnergySum += (l * l + r * r) * 0.5;
        }
        const float chunkEnergy = static_cast<float>(std::sqrt(frameEnergySum / frames));

        shortTermEnergy_ += 0.05f * (chunkEnergy - shortTermEnergy_);
        if (energyHistoryFrames_ < static_cast<std::size_t>(sampleRate_ * 10.0)) {
            baselineEnergy_ += 0.01f * (chunkEnergy - baselineEnergy_);
            energyHistoryFrames_ += frames;
        }

        if (energyHistoryFrames_ >= static_cast<std::size_t>(sampleRate_ * 3.0) &&
            baselineEnergy_ > 0.02f && shortTermEnergy_ < baselineEnergy_ * 0.35f) {
            startTransition(AutoMixState::FadingAuto, barMode_.load(std::memory_order_acquire));
            currState = state_.load(std::memory_order_acquire);
        }
    }

    if (currState == AutoMixState::FadingAuto || currState == AutoMixState::FadingFast) {
        const float p = (transitionTotalFrames_ > 0)
            ? static_cast<float>(transitionCurrentFrame_) / static_cast<float>(transitionTotalFrames_)
            : 1.0f;
        transitionProgress_ = std::clamp(p, 0.0f, 1.0f);

        const std::size_t framesToRead = std::min(frames, deckBScratch_.size() / 2);
        const std::size_t readSamples = deckBRingBuffer_.read(deckBScratch_.data(), framesToRead * 2);
        const std::size_t actualDeckBFrames = readSamples / 2;
        if (actualDeckBFrames < frames) {
            std::fill(deckBScratch_.data() + actualDeckBFrames * 2, deckBScratch_.data() + frames * 2, 0.0f);
        }

        // Zero time-stretching: native 1.0f playback speed
        std::copy(deckBScratch_.begin(), deckBScratch_.begin() + frames * 2,
                  deckBStretchedScratch_.begin());

        // Apply DSP on Deck A and Deck B
        processDeckA(deckAStereo, frames, transitionProgress_);
        processDeckB(deckBStretchedScratch_.data(), frames, transitionProgress_);

        // Sum signals and apply -0.85f threshold / 0.98f ceiling soft-knee limiter
        for (std::size_t i = 0; i < frames * 2; ++i) {
            const float mixed = deckAStereo[i] + deckBStretchedScratch_[i];
            deckAStereo[i] = applySoftKneeLimiter(mixed);
        }

        transitionCurrentFrame_ += frames;

        if (transitionCurrentFrame_ >= transitionTotalFrames_) {
            deckBStretcher_.startRecoveryRamp(1.0f, 6.0f); // 6.0s smooth glide back
            deckAEcho_.setEnabled(false);
            deckALoopActive_ = false;
            state_.store(AutoMixState::PostRestore, std::memory_order_release);
        }
    } else if (currState == AutoMixState::PostRestore) {
        if (deckBStretcher_.currentRatio() == 1.0f && postRestoreFramesElapsed_ >= postRestoreTotalFrames_) {
            state_.store(AutoMixState::Idle, std::memory_order_release);
        }
    }
}


bool AutoMixEngine::processDeckBuffer(
    void* pcm,
    std::size_t frameCount,
    int encoding,
    int sampleRate,
    bool isDeckA) noexcept {
    if (pcm == nullptr || frameCount == 0) return false;
    if (!enabled_.load(std::memory_order_acquire)) return false;
    if (paused_.load(std::memory_order_acquire)) return true;

    AutoMixState curr = state_.load(std::memory_order_acquire);
    if (curr != AutoMixState::FadingAuto && curr != AutoMixState::FadingFast && curr != AutoMixState::PostRestore) {
        return false;
    }

    frameCount = std::min(frameCount, conversionScratch_.size() / 2);
    float* scratch = conversionScratch_.data();

    // 1. Unpack PCM to Float32 stereo
    switch (encoding) {
        case 0: { // PCM_I16
            const auto* in = static_cast<const std::int16_t*>(pcm);
            for (std::size_t i = 0; i < frameCount * 2; ++i) {
                scratch[i] = static_cast<float>(in[i]) / 32768.0f;
            }
            break;
        }
        case 1: { // PCM_I24_PACKED
            const auto* in = static_cast<const std::uint8_t*>(pcm);
            for (std::size_t i = 0; i < frameCount * 2; ++i) {
                const std::size_t offset = i * 3;
                const std::int32_t val = static_cast<std::int32_t>(in[offset]) |
                    (static_cast<std::int32_t>(in[offset + 1]) << 8) |
                    (static_cast<std::int32_t>(static_cast<std::int8_t>(in[offset + 2])) << 16);
                scratch[i] = static_cast<float>(val) / 8388608.0f;
            }
            break;
        }
        case 2: { // PCM_I32
            const auto* in = static_cast<const std::int32_t*>(pcm);
            for (std::size_t i = 0; i < frameCount * 2; ++i) {
                scratch[i] = static_cast<float>(in[i]) / 2147483648.0f;
            }
            break;
        }
        case 3: { // PCM_FLOAT
            scratch = static_cast<float*>(pcm);
            break;
        }
        default:
            return false;
    }

    // 2. Apply C++ DJ DSP chain
    const float p = transitionProgress_;
    if (isDeckA) {
        processDeckA(scratch, frameCount, p);
        if (transitionTotalFrames_ > 0 && curr != AutoMixState::PostRestore) {
            transitionCurrentFrame_ += frameCount;
            if (transitionCurrentFrame_ < transitionTotalFrames_) {
                transitionProgress_ = static_cast<float>(transitionCurrentFrame_) / static_cast<float>(transitionTotalFrames_);
            } else {
                transitionProgress_ = 1.0f;
                deckBStretcher_.startRecoveryRamp(1.0f, 6.0f);
                deckAEcho_.setEnabled(false);
                deckALoopActive_ = false;
                state_.store(AutoMixState::PostRestore, std::memory_order_release);
            }
        }
    } else {
        processDeckB(scratch, frameCount, p);
    }

    // 3. Pack Float32 back to wire format
    if (encoding != 3) {
        switch (encoding) {
            case 0: { // PCM_I16
                auto* out = static_cast<std::int16_t*>(pcm);
                for (std::size_t i = 0; i < frameCount * 2; ++i) {
                    const auto pcmVal = static_cast<std::int32_t>(std::lrintf(scratch[i] * 32767.0f));
                    out[i] = static_cast<std::int16_t>(std::clamp(pcmVal, -32767, 32767));
                }
                break;
            }
            case 1: { // PCM_I24_PACKED
                auto* out = static_cast<std::uint8_t*>(pcm);
                for (std::size_t i = 0; i < frameCount * 2; ++i) {
                    const auto val = std::clamp(static_cast<std::int32_t>(std::lrintf(scratch[i] * 8388607.0f)), -8388607, 8388607);
                    const std::size_t offset = i * 3;
                    out[offset] = static_cast<std::uint8_t>(val & 0xFF);
                    out[offset + 1] = static_cast<std::uint8_t>((val >> 8) & 0xFF);
                    out[offset + 2] = static_cast<std::uint8_t>((val >> 16) & 0xFF);
                }
                break;
            }
            case 2: { // PCM_I32
                auto* out = static_cast<std::int32_t*>(pcm);
                for (std::size_t i = 0; i < frameCount * 2; ++i) {
                    const double scaled = static_cast<double>(scratch[i]) * 2147483647.0;
                    out[i] = static_cast<std::int32_t>(std::clamp(scaled, -2147483647.0, 2147483647.0));
                }
                break;
            }
            default:
                break;
        }
    }

    return true;
}

void AutoMixEngine::feedDeckARollingBuffer(const float* stereo, std::size_t frames) noexcept {
    if (stereo == nullptr || frames == 0) return;
    const std::size_t capacityFrames = deckARollingBuffer_.size() / 2;
    for (std::size_t f = 0; f < frames; ++f) {
        deckARollingBuffer_[deckARollingWritePos_ * 2] = stereo[f * 2];
        deckARollingBuffer_[deckARollingWritePos_ * 2 + 1] = stereo[f * 2 + 1];
        deckARollingWritePos_ = (deckARollingWritePos_ + 1) % capacityFrames;
    }
}

void AutoMixEngine::captureDeckALoop(float bpmA) noexcept {
    const float safeBpm = (bpmA >= 50.0f && bpmA <= 220.0f) ? bpmA : 120.0f;
    const double barSec = (60.0 / static_cast<double>(safeBpm)) * 4.0;
    const std::size_t loopFrames = static_cast<std::size_t>(barSec * sampleRate_);
    const std::size_t capacityFrames = deckARollingBuffer_.size() / 2;

    if (loopFrames == 0 || loopFrames > capacityFrames || loopFrames * 2 > deckAOneBarLoopBuffer_.size()) return;

    const std::size_t startPos = (deckARollingWritePos_ + capacityFrames - loopFrames) % capacityFrames;
    for (std::size_t f = 0; f < loopFrames; ++f) {
        const std::size_t rPos = (startPos + f) % capacityFrames;
        deckAOneBarLoopBuffer_[f * 2] = deckARollingBuffer_[rPos * 2];
        deckAOneBarLoopBuffer_[f * 2 + 1] = deckARollingBuffer_[rPos * 2 + 1];
    }

    deckALoopFrames_ = loopFrames;
    deckALoopReadPos_ = 0;
    deckALoopActive_ = true;
}

bool AutoMixEngine::isAtPhraseBoundary(const float* stereo, std::size_t frames) noexcept {
    (void)stereo;
    (void)frames;
    return true;
}

AutoMixState AutoMixEngine::getState() const noexcept {
    return state_.load(std::memory_order_acquire);
}

float AutoMixEngine::getTransitionProgress() const noexcept {
    return transitionProgress_;
}

std::size_t AutoMixEngine::getDeckBLeadingTrimFrames() const noexcept {
    return deckBLeadingTrimFrames_;
}

std::size_t AutoMixEngine::getDeckBAvailableFrames() const noexcept {
    return deckBRingBuffer_.availableToRead() / 2;
}

float AutoMixEngine::getDeckBAvailableSeconds() const noexcept {
    if (sampleRate_ <= 0.0) return 0.0f;
    return static_cast<float>(static_cast<double>(getDeckBAvailableFrames()) / sampleRate_);
}

std::int64_t AutoMixEngine::getDeckBFirstBeatMs() const noexcept {
    return deckBFirstBeatMs_;
}

float AutoMixEngine::getDeckABpm() const noexcept {
    return deckADetector_.getResult().bpm;
}

float AutoMixEngine::getDeckBBpm() const noexcept {
    return deckBDetector_.getResult().bpm;
}

std::int64_t AutoMixEngine::getDeckBDropBeatMs() const noexcept {
    return deckBDropBeatMs_;
}

} // namespace lastwave::audio
