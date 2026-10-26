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
    q = std::clamp(q, 0.1, 20.0);

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

AutoMixEngine::AutoMixEngine() noexcept {
    configure(48000.0);
}

void AutoMixEngine::configure(double sampleRate) noexcept {
    if (sampleRate <= 0.0) sampleRate = 48000.0;
    sampleRate_ = sampleRate;

    deckAFilter_.configure(sampleRate_, 180.0);
    deckBFilter_.configure(sampleRate_, 180.0);
    deckBHpf200_.configure(sampleRate_, 200.0);

    deckBStretcher_.configure(sampleRate_, 2);
    deckADetector_.configure(sampleRate_);
    deckBDetector_.configure(sampleRate_);
    deckAEcho_.configure(sampleRate_, 120.0f);
    subtlePhaser_.configure(sampleRate_);

    deckAHpfResonant_.update(ResonantFilter::Mode::HighPass, sampleRate_, 60.0, 1.25);
    deckAVocalDuckFilter_.update(ResonantFilter::Mode::PeakingCut, sampleRate_, 1200.0, 0.7, -9.0);

    const std::size_t maxFrames = static_cast<std::size_t>(sampleRate_ * 40.0); // 40s capacity
    {
        std::lock_guard<std::mutex> lock(bufferMutex_);
        deckBRingBuffer_.assign(maxFrames * 2, 0.0f);
        deckBReadPos_ = 0;
        deckBWritePos_ = 0;
        deckBAvailableFrames_ = 0;
        deckBTrimCalculated_ = false;
    }

    const std::size_t rollFrames = static_cast<std::size_t>(sampleRate_ * 6.0); // 6s rolling buffer
    deckARollingBuffer_.assign(rollFrames * 2, 0.0f);
    deckARollingWritePos_ = 0;
    deckAOneBarLoopBuffer_.clear();
    deckALoopFrames_ = 0;
    deckALoopReadPos_ = 0;
    deckALoopActive_ = false;

    deckBScratch_.resize(4096 * 2, 0.0f);
    deckBStretchedScratch_.resize(8192 * 2, 0.0f);
    conversionScratch_.resize(4096 * 2, 0.0f);
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
    std::lock_guard<std::mutex> lock(planMutex_);
    currentPlan_ = plan;

    if (plan.bpmRatio > 0.0f && std::abs(plan.bpmRatio - 1.0f) <= 0.08f) {
        deckBStretcher_.setTimeRatio(plan.bpmRatio);
    } else {
        deckBStretcher_.setTimeRatio(1.0f);
    }

    deckAEcho_.configure(sampleRate_, plan.bpmA);
    activeGainOffsetDb_ = plan.gainOffsetDb;
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

    deckAFilter_.reset();
    deckBFilter_.reset();
    deckBHpf200_.reset();
    deckAHpfResonant_.reset();
    deckAHpfResonant_.update(ResonantFilter::Mode::HighPass, sampleRate_, 60.0, 1.25);
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

    deckAFilter_.reset();
    deckBFilter_.reset();
    deckBHpf200_.reset();
    deckAHpfResonant_.reset();
    deckAVocalDuckFilter_.reset();

    {
        std::lock_guard<std::mutex> lock(bufferMutex_);
        deckBReadPos_ = 0;
        deckBWritePos_ = 0;
        deckBAvailableFrames_ = 0;
        deckBTrimCalculated_ = false;
    }

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

    std::size_t written = 0;
    {
        std::lock_guard<std::mutex> lock(bufferMutex_);
        const std::size_t capacityFrames = deckBRingBuffer_.size() / 2;
        const std::size_t spaceFrames = capacityFrames - deckBAvailableFrames_;
        written = std::min(frames, spaceFrames);

        for (std::size_t f = 0; f < written; ++f) {
            deckBRingBuffer_[deckBWritePos_ * 2] = stereoPcm[f * 2];
            deckBRingBuffer_[deckBWritePos_ * 2 + 1] = stereoPcm[f * 2 + 1];
            deckBWritePos_ = (deckBWritePos_ + 1) % capacityFrames;
        }
        deckBAvailableFrames_ += written;
    }

    deckBDetector_.process(stereoPcm, written);

    if (!deckBTrimCalculated_ && deckBAvailableFrames_ >= static_cast<std::size_t>(sampleRate_ * 3.0)) {
        detectDeckBFirstBeatAndSilence();
    }

    return written;
}

void AutoMixEngine::detectDeckBFirstBeatAndSilence() noexcept {
    std::lock_guard<std::mutex> lock(bufferMutex_);
    if (deckBAvailableFrames_ < static_cast<std::size_t>(sampleRate_ * 1.5)) return;

    const std::size_t totalCapacityFrames = deckBRingBuffer_.size() / 2;
    const std::size_t searchLimit = std::min(
        deckBAvailableFrames_,
        static_cast<std::size_t>(sampleRate_ * 35.0));

    // 2nd-order Butterworth filters: 60-140 Hz sub-bass kick
    const double wKick = 2.0 * M_PI * 95.0 / sampleRate_;
    const double alphaKick = std::sin(wKick) / (2.0 * 1.2);
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
            const std::size_t idx = (deckBReadPos_ + b * B + j) % totalCapacityFrames;
            const double mono = (deckBRingBuffer_[idx * 2] + deckBRingBuffer_[idx * 2 + 1]) * 0.5;

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
                entryCueTimeSec = std::max(0.0, t0 - 4.0 * T); // 1 bar before drop
                break;
            }
        }
        if (foundGridLock) break;
    }

    if (!foundGridLock) {
        // Fallback: silence threshold search
        constexpr float kSilenceThreshold = 0.00562341325f;
        for (std::size_t f = 0; f < searchLimit; ++f) {
            const std::size_t idx = (deckBReadPos_ + f) % totalCapacityFrames;
            const float l = std::abs(deckBRingBuffer_[idx * 2]);
            const float r = std::abs(deckBRingBuffer_[idx * 2 + 1]);
            if (l >= kSilenceThreshold || r >= kSilenceThreshold) {
                dropBeatTimeSec = static_cast<double>(f) / sampleRate_;
                entryCueTimeSec = dropBeatTimeSec;
                foundGridLock = true;
                break;
            }
        }
    }

    const std::size_t chosenCueFrame = static_cast<std::size_t>(entryCueTimeSec * sampleRate_);
    if (chosenCueFrame > 0 && chosenCueFrame < deckBAvailableFrames_) {
        deckBReadPos_ = (deckBReadPos_ + chosenCueFrame) % totalCapacityFrames;
        deckBAvailableFrames_ -= chosenCueFrame;
        deckBLeadingTrimFrames_ = chosenCueFrame;
    }

    deckBFirstBeatMs_ = static_cast<std::int64_t>(entryCueTimeSec * 1000.0);
    deckBDropBeatMs_ = static_cast<std::int64_t>(dropBeatTimeSec * 1000.0);
    deckBTrimCalculated_ = true;
}

float AutoMixEngine::applySoftKneeLimiter(float x) noexcept {
    // -0.5 dBFS soft-knee tanh limiter (threshold ~ 0.944f)
    constexpr float kThreshold = 0.94406f;
    constexpr float kMargin = 1.0f - kThreshold;

    const float absX = std::abs(x);
    if (absX <= kThreshold) {
        return x;
    }

    const float excess = absX - kThreshold;
    const float compressed = kThreshold + kMargin * std::tanh(excess / kMargin);
    return std::copysign(compressed, x);
}

void AutoMixEngine::processDeckA(float* deckAStereo, std::size_t frames, float progress) noexcept {
    if (deckAStereo == nullptr || frames == 0) return;
    if (paused_.load(std::memory_order_acquire)) return;

    progress = std::clamp(progress, 0.0f, 1.0f);

    // 1. Vocal-Safe Loop / Beat-Repeat Slicer (t >= 0.55):
    // If Deck A is verified vocal-free, lock into captured 1-bar drum loop
    if (progress >= 0.55f && deckAVocalFree_.load(std::memory_order_acquire)) {
        if (!deckALoopActive_) {
            captureDeckALoop(deckADetector_.getResult().bpm);
        }
        if (deckALoopActive_ && deckALoopFrames_ > 0) {
            for (std::size_t f = 0; f < frames; ++f) {
                deckAStereo[f * 2] = deckAOneBarLoopBuffer_[deckALoopReadPos_ * 2];
                deckAStereo[f * 2 + 1] = deckAOneBarLoopBuffer_[deckALoopReadPos_ * 2 + 1];
                deckALoopReadPos_ = (deckALoopReadPos_ + 1) % deckALoopFrames_;
            }
        }
    }

    // 2. Vocal-Pocket Ducking: dynamic -9.0 dB peaking cut at 1200 Hz if Deck B has vocals
    if (deckBVocalsActive_.load(std::memory_order_acquire)) {
        for (std::size_t f = 0; f < frames; ++f) {
            deckAStereo[f * 2] = deckAVocalDuckFilter_.tick(deckAStereo[f * 2], 0);
            deckAStereo[f * 2 + 1] = deckAVocalDuckFilter_.tick(deckAStereo[f * 2 + 1], 1);
        }
    }

    // 3. Single Texture FX Auto-Chooser (t >= 0.40):
    TransitionTextureFx fx = TransitionTextureFx::None;
    {
        std::lock_guard<std::mutex> lock(planMutex_);
        fx = currentPlan_.textureFx;
    }
    if (progress >= 0.40f && fx != TransitionTextureFx::None) {
        const float send = std::clamp((progress - 0.40f) / 0.20f, 0.0f, 0.35f);
        if (fx == TransitionTextureFx::SubtlePhaser) {
            for (std::size_t f = 0; f < frames; ++f) {
                subtlePhaser_.process(deckAStereo[f * 2], deckAStereo[f * 2 + 1], send);
            }
        } else if (fx == TransitionTextureFx::StereoWidthExpander) {
            // Stereo width expander on Deck A
            const float width = 1.0f + send * 0.8f;
            for (std::size_t f = 0; f < frames; ++f) {
                const float m = (deckAStereo[f * 2] + deckAStereo[f * 2 + 1]) * 0.5f;
                const float s = (deckAStereo[f * 2] - deckAStereo[f * 2 + 1]) * 0.5f * width;
                deckAStereo[f * 2] = m + s;
                deckAStereo[f * 2 + 1] = m - s;
            }
        }
    }

    // 4. Equal-power gain curve with -1.5 dB center overlap trim
    // gainA = cos(t * PI / 2) * centerTrim
    const float gainA = std::cos(progress * static_cast<float>(M_PI) * 0.5f);
    const float centerTrim = 1.0f - 0.1586f * (4.0f * progress * (1.0f - progress));
    const float effGainA = gainA * centerTrim;

    // 5. Complementary 3-Band Crossover & Hard Downbeat Bass-Swap
    if (progress < 0.55f) {
        // Phase 1 (t = 0.00 to 0.55): Deck A holds 100% of the sub-bass (<180 Hz at 0.0 dB)!
        deckAFilter_.processBassAttenuate(deckAStereo, frames, 1.0f, 1.0f);

        for (std::size_t i = 0; i < frames * 2; ++i) {
            deckAStereo[i] *= effGainA;
        }
    } else {
        // Midpoint Downbeat Swap (t = 0.55):
        // Over 1 beat (~400ms), sweep Deck A's resonant High-Pass Filter (Q = 1.25) from 60 Hz up to 550 Hz
        const float swapProgress = std::clamp((progress - 0.55f) / 0.10f, 0.0f, 1.0f);
        const double cutoffHz = 60.0 + swapProgress * (550.0 - 60.0);
        deckAHpfResonant_.update(ResonantFilter::Mode::HighPass, sampleRate_, cutoffHz, 1.25);

        for (std::size_t f = 0; f < frames; ++f) {
            deckAStereo[f * 2] = deckAHpfResonant_.tick(deckAStereo[f * 2], 0) * effGainA;
            deckAStereo[f * 2 + 1] = deckAHpfResonant_.tick(deckAStereo[f * 2 + 1], 1) * effGainA;
        }

        // Outgoing Reverb Wash Tail (t = 0.50 to 1.00 + 1.4s):
        // Auxiliary send (0.0 -> 0.50 wet) into stereo damping reverb + 1/2-beat echo
        const float wetSend = std::clamp((progress - 0.50f) / 0.50f * 0.50f, 0.0f, 0.50f);
        const float dryGain = (1.0f - progress);
        deckAEcho_.process(deckAStereo, frames, wetSend, dryGain, /*apply55WetMix=*/true);
    }
}

void AutoMixEngine::processDeckB(float* deckBStereo, std::size_t frames, float progress) noexcept {
    if (deckBStereo == nullptr || frames == 0) return;
    if (paused_.load(std::memory_order_acquire)) return;

    progress = std::clamp(progress, 0.0f, 1.0f);

    // 1. Equal-power gain curve with -1.5 dB center overlap trim
    // gainB = sin(t * PI / 2) * centerTrim
    const float gainB = std::sin(progress * static_cast<float>(M_PI) * 0.5f);
    const float centerTrim = 1.0f - 0.1586f * (4.0f * progress * (1.0f - progress));

    // 2. Windowed LUFS / RMS Loudness Makeup Gain
    float makeupLinear = 1.0f;
    {
        std::lock_guard<std::mutex> lock(planMutex_);
        if (state_.load(std::memory_order_acquire) == AutoMixState::PostRestore) {
            // Decay gainOffsetDb linearly back to 0.0 dB over 10.0 seconds
            if (postRestoreTotalFrames_ > 0 && postRestoreFramesElapsed_ < postRestoreTotalFrames_) {
                const float decayFactor = 1.0f - static_cast<float>(postRestoreFramesElapsed_) / static_cast<float>(postRestoreTotalFrames_);
                const float decayingDb = currentPlan_.gainOffsetDb * decayFactor;
                makeupLinear = std::pow(10.0f, decayingDb / 20.0f);
                postRestoreFramesElapsed_ += frames;
            } else {
                makeupLinear = 1.0f;
            }
        } else {
            makeupLinear = std::pow(10.0f, currentPlan_.gainOffsetDb / 20.0f);
        }
    }

    const float effGainB = gainB * centerTrim * makeupLinear;

    // 3. Complementary 3-Band Crossover & Hard Downbeat Bass-Swap
    if (progress < 0.55f) {
        // Phase 1 (t = 0.00 to 0.55): Deck B has 2nd-order Linkwitz-Riley HPF at 200 Hz (-18 dB on sub-bass)
        // Hi-hats, percussion, and intro melody glide in cleanly without clashing kicks
        deckBHpf200_.processHighPass(deckBStereo, frames);

        for (std::size_t i = 0; i < frames * 2; ++i) {
            deckBStereo[i] *= effGainB;
        }
    } else {
        // Midpoint Downbeat Swap (t = 0.55):
        // Instantaneously drop Deck B HPF to 20 Hz (full 0.0 dB sub-bass slam)!
        // Zero bass holes, 100% full sub-bass punch and crisp high-end
        for (std::size_t i = 0; i < frames * 2; ++i) {
            deckBStereo[i] *= effGainB;
        }
    }
}

void AutoMixEngine::process(float* deckAStereo, std::size_t frames) noexcept {
    if (deckAStereo == nullptr || frames == 0) return;
    if (!enabled_.load(std::memory_order_acquire)) return;
    if (paused_.load(std::memory_order_acquire)) return;

    deckADetector_.process(deckAStereo, frames);
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

        if (deckBScratch_.size() < frames * 2) {
            deckBScratch_.resize(frames * 2, 0.0f);
        }

        std::size_t actualDeckBFrames = 0;
        {
            std::lock_guard<std::mutex> lock(bufferMutex_);
            const std::size_t totalCapacityFrames = deckBRingBuffer_.size() / 2;
            actualDeckBFrames = std::min(frames, deckBAvailableFrames_);
            for (std::size_t f = 0; f < actualDeckBFrames; ++f) {
                deckBScratch_[f * 2] = deckBRingBuffer_[deckBReadPos_ * 2];
                deckBScratch_[f * 2 + 1] = deckBRingBuffer_[deckBReadPos_ * 2 + 1];
                deckBReadPos_ = (deckBReadPos_ + 1) % totalCapacityFrames;
            }
            deckBAvailableFrames_ -= actualDeckBFrames;
        }
        if (actualDeckBFrames < frames) {
            std::fill(deckBScratch_.begin() + actualDeckBFrames * 2, deckBScratch_.begin() + frames * 2, 0.0f);
        }

        // Time-stretch Deck B
        if (deckBStretchedScratch_.size() < frames * 4) {
            deckBStretchedScratch_.resize(frames * 4, 0.0f);
        }
        std::size_t stretchedFrames = deckBStretcher_.process(
            deckBScratch_.data(), frames, deckBStretchedScratch_.data(), frames * 2);
        if (stretchedFrames < frames) {
            std::copy(deckBScratch_.begin() + stretchedFrames * 2, deckBScratch_.begin() + frames * 2,
                      deckBStretchedScratch_.begin() + stretchedFrames * 2);
        }

        // Apply DSP on Deck A and Deck B
        processDeckA(deckAStereo, frames, transitionProgress_);
        processDeckB(deckBStretchedScratch_.data(), frames, transitionProgress_);

        // Sum signals and apply -0.5 dBFS soft-knee tanh limiter
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

    if (conversionScratch_.size() < frameCount * 2) {
        conversionScratch_.resize(frameCount * 2);
    }
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

    // Apply soft-knee limiter
    for (std::size_t i = 0; i < frameCount * 2; ++i) {
        scratch[i] = applySoftKneeLimiter(scratch[i]);
    }

    // 3. Pack Float32 back to wire format
    if (encoding != 3) {
        switch (encoding) {
            case 0: { // PCM_I16
                auto* out = static_cast<std::int16_t*>(pcm);
                for (std::size_t i = 0; i < frameCount * 2; ++i) {
                    const float clamped = std::clamp(scratch[i], -1.0f, 1.0f);
                    out[i] = static_cast<std::int16_t>(std::round(clamped * 32767.0f));
                }
                break;
            }
            case 1: { // PCM_I24_PACKED
                auto* out = static_cast<std::uint8_t*>(pcm);
                for (std::size_t i = 0; i < frameCount * 2; ++i) {
                    const float clamped = std::clamp(scratch[i], -1.0f, 1.0f);
                    const auto val = static_cast<std::int32_t>(std::round(clamped * 8388607.0f));
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
                    const float clamped = std::clamp(scratch[i], -1.0f, 1.0f);
                    out[i] = static_cast<std::int32_t>(std::round(clamped * 2147483647.0f));
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

    if (loopFrames == 0 || loopFrames > capacityFrames) return;

    deckAOneBarLoopBuffer_.resize(loopFrames * 2);
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
