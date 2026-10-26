#include "AutoMixEngine.h"
#include <cmath>
#include <algorithm>
#include <cstring>

namespace lastwave::audio {

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

// -45 dBFS threshold in linear float amplitude
static constexpr float kSilenceThresholdLinear = 0.00562341325F;
// -16 dBFS threshold in linear float amplitude (10^(-16/20))
static constexpr float kKickThresholdLinear = 0.158489319F;
// -18 dB linear gain for bass cut (10^(-18/20))
static constexpr float kMinus18DbLinear = 0.125892541F;
// -6 dB linear gain for smooth musical bass crossover (10^(-6/20))
static constexpr float kMinus6DbLinear = 0.501187234F;

void ResonantFilter::update(Mode mode, double sampleRate, double cutoffHz, double q) noexcept {
    cutoffHz = std::clamp(cutoffHz, 15.0, sampleRate * 0.48);
    q = std::clamp(q, 0.2, 5.0);

    const double omega = 2.0 * M_PI * cutoffHz / sampleRate;
    const double sinOmega = std::sin(omega);
    const double cosOmega = std::cos(omega);
    const double alpha = sinOmega / (2.0 * q);

    const double a0 = 1.0 + alpha;
    if (mode == Mode::HighPass) {
        b0 = ((1.0 + cosOmega) * 0.5) / a0;
        b1 = (-(1.0 + cosOmega)) / a0;
        b2 = ((1.0 + cosOmega) * 0.5) / a0;
    } else {
        b0 = ((1.0 - cosOmega) * 0.5) / a0;
        b1 = (1.0 - cosOmega) / a0;
        b2 = ((1.0 - cosOmega) * 0.5) / a0;
    }
    a1 = (-2.0 * cosOmega) / a0;
    a2 = (1.0 - alpha) / a0;
}

void ResonantFilter::reset() noexcept {
    z1.fill(0.0);
    z2.fill(0.0);
}

float ResonantFilter::tick(float in, std::size_t ch) noexcept {
    const double x = static_cast<double>(in);
    const double out = b0 * x + z1[ch];
    z1[ch] = b1 * x - a1 * out + z2[ch];
    z2[ch] = b2 * x - a2 * out;
    if (std::abs(z1[ch]) < 1.0e-20) z1[ch] = 0.0;
    if (std::abs(z2[ch]) < 1.0e-20) z2[ch] = 0.0;
    if (!std::isfinite(out) || !std::isfinite(z1[ch]) || !std::isfinite(z2[ch])) {
        z1[ch] = 0.0;
        z2[ch] = 0.0;
        return 0.0F;
    }
    return static_cast<float>(out);
}

AutoMixEngine::AutoMixEngine() noexcept {
    configure(48000.0);
}

void AutoMixEngine::configure(double sampleRate) noexcept {
    if (sampleRate <= 0.0) sampleRate = 48000.0;
    sampleRate_ = sampleRate;

    // Linkwitz-Riley 180 Hz crossover for bass tracking & 220 Hz strict complementary HPF for Deck B
    deckAFilter_.configure(sampleRate_, 180.0);
    deckBFilter_.configure(sampleRate_, 180.0);
    deckBHpf220_.configure(sampleRate_, 220.0);
    deckBStretcher_.configure(sampleRate_, 2);
    deckADetector_.configure(sampleRate_);
    deckBDetector_.configure(sampleRate_);
    deckAEcho_.configure(sampleRate_, 120.0F);

    deckAHpf_.update(ResonantFilter::Mode::HighPass, sampleRate_, 500.0, 1.1);
    deckAVocalDetectorBp_.update(ResonantFilter::Mode::HighPass, sampleRate_, 300.0, 0.7);

    // Buffer capacity: 35 seconds of stereo Float32 for Deck B (Rule 1 scan window)
    const std::size_t ringFrames = static_cast<std::size_t>(sampleRate_ * 35.0);
    {
        std::lock_guard<std::mutex> lock(bufferMutex_);
        deckBRingBuffer_.assign(ringFrames * 2, 0.0F);
        deckBReadPos_ = 0;
        deckBWritePos_ = 0;
        deckBAvailableFrames_ = 0;
    }

    // Rule 2: 4 seconds rolling buffer for 1-bar loop capture
    deckARollingBuffer_.assign(static_cast<std::size_t>(sampleRate_ * 4.0) * 2, 0.0F);
    deckARollingWritePos_ = 0;
    deckALoopFrames_ = 0;
    deckALoopReadPos_ = 0;
    deckALoopActive_ = false;

    deckBScratch_.resize(4096 * 2, 0.0F);
    deckBStretchedScratch_.resize(4096 * 4, 0.0F);
    conversionScratch_.resize(4096 * 2, 0.0F);

    reset();
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
    barMode_.store((bars >= 16) ? 16 : 8, std::memory_order_release);
}

int AutoMixEngine::getBarMode() const noexcept {
    return barMode_.load(std::memory_order_acquire);
}

void AutoMixEngine::reset() noexcept {
    state_.store(AutoMixState::Idle, std::memory_order_release);
    {
        std::lock_guard<std::mutex> lock(bufferMutex_);
        std::fill(deckBRingBuffer_.begin(), deckBRingBuffer_.end(), 0.0F);
        deckBReadPos_ = 0;
        deckBWritePos_ = 0;
        deckBAvailableFrames_ = 0;
    }

    deckAFilter_.reset();
    deckBFilter_.reset();
    deckBHpf220_.reset();
    deckBStretcher_.reset();
    deckADetector_.reset();
    deckBDetector_.reset();
    deckAEcho_.reset();
    deckAHpf_.reset();
    deckAVocalDetectorBp_.reset();

    deckBLeadingTrimFrames_ = 0;
    deckBFirstBeatMs_ = 0;
    deckBDropBeatMs_ = 0;
    deckBTrimCalculated_ = false;
    transitionTotalFrames_ = 0;
    transitionCurrentFrame_ = 0;
    transitionProgress_ = 0.0F;
    bassSwapped_ = false;

    deckALoopFrames_ = 0;
    deckALoopReadPos_ = 0;
    deckALoopActive_ = false;
    deckARollingWritePos_ = 0;

    baselineEnergy_ = 0.0F;
    shortTermEnergy_ = 0.0F;
    energyHistoryFrames_ = 0;
}

std::size_t AutoMixEngine::writeDeckBPcm(const float* stereoPcm, std::size_t frames) noexcept {
    if (stereoPcm == nullptr || frames == 0) return 0;

    std::lock_guard<std::mutex> lock(bufferMutex_);
    const std::size_t totalCapacityFrames = deckBRingBuffer_.size() / 2;
    const std::size_t roomFrames = totalCapacityFrames - deckBAvailableFrames_;
    const std::size_t framesToWrite = std::min(frames, roomFrames);

    if (framesToWrite == 0) return 0;

    for (std::size_t f = 0; f < framesToWrite; ++f) {
        deckBRingBuffer_[deckBWritePos_ * 2] = stereoPcm[f * 2];
        deckBRingBuffer_[deckBWritePos_ * 2 + 1] = stereoPcm[f * 2 + 1];
        deckBWritePos_ = (deckBWritePos_ + 1) % totalCapacityFrames;
    }
    deckBAvailableFrames_ += framesToWrite;

    // Feed Deck B BPM detector
    deckBDetector_.process(stereoPcm, framesToWrite);

    // Calculate smart intro cue (first beat onset) and silence trim
    if (!deckBTrimCalculated_ && deckBAvailableFrames_ >= static_cast<std::size_t>(sampleRate_ * 1.0)) {
        detectDeckBFirstBeatAndSilence();
    }

    AutoMixState curr = state_.load(std::memory_order_acquire);
    if (curr == AutoMixState::Idle) {
        state_.store(AutoMixState::MonitoringOutro, std::memory_order_release);
    }

    return framesToWrite;
}

void AutoMixEngine::detectDeckBFirstBeatAndSilence() noexcept {
    const std::size_t totalCapacityFrames = deckBRingBuffer_.size() / 2;
    // Rule 1: Scan first 35 seconds of Deck B to find beat-grid lock
    const std::size_t searchLimit = std::min(
        deckBAvailableFrames_,
        static_cast<std::size_t>(sampleRate_ * 35.0));

    if (searchLimit < static_cast<std::size_t>(sampleRate_ * 2.0)) return;

    // 2nd-order Butterworth filters: 60-130 Hz sub-bass kick, 250-500 Hz snare body
    const double wKick = 2.0 * M_PI * 95.0 / sampleRate_;
    const double alphaKick = std::sin(wKick) / (2.0 * 1.2);
    const double a0K = 1.0 + alphaKick;
    const double b0K = alphaKick / a0K, b1K = 0.0, b2K = -alphaKick / a0K;
    const double a1K = (-2.0 * std::cos(wKick)) / a0K, a2K = (1.0 - alphaKick) / a0K;

    const double wSnare = 2.0 * M_PI * 350.0 / sampleRate_;
    const double alphaSnare = std::sin(wSnare) / (2.0 * 1.0);
    const double a0S = 1.0 + alphaSnare;
    const double b0S = alphaSnare / a0S, b1S = 0.0, b2S = -alphaSnare / a0S;
    const double a1S = (-2.0 * std::cos(wSnare)) / a0S, a2S = (1.0 - alphaSnare) / a0S;

    double kZ1 = 0.0, kZ2 = 0.0, sZ1 = 0.0, sZ2 = 0.0;
    constexpr std::size_t B = 960; // 20ms blocks
    const std::size_t totalBlocks = searchLimit / B;

    std::vector<float> kickRms(totalBlocks, 0.0F);
    std::vector<float> drumRms(totalBlocks, 0.0F);
    std::vector<float> crest(totalBlocks, 0.0F);

    for (std::size_t b = 0; b < totalBlocks; ++b) {
        double kSum = 0.0, sSum = 0.0, peak = 0.0;
        for (std::size_t j = 0; j < B; ++j) {
            const std::size_t idx = (deckBReadPos_ + b * B + j) % totalCapacityFrames;
            const double mono = (deckBRingBuffer_[idx * 2] + deckBRingBuffer_[idx * 2 + 1]) * 0.5;

            const double kOut = b0K * mono + kZ1;
            kZ1 = b1K * mono - a1K * kOut + kZ2;
            kZ2 = b2K * mono - a2K * kOut;

            const double sOut = b0S * mono + sZ1;
            sZ1 = b1S * mono - a1S * sOut + sZ2;
            sZ2 = b2S * mono - a2S * sOut;

            kSum += kOut * kOut;
            sSum += sOut * sOut;
            peak = std::max(peak, std::abs(kOut));
        }
        const float kR = static_cast<float>(std::sqrt(kSum / B));
        const float sR = static_cast<float>(std::sqrt(sSum / B));
        kickRms[b] = kR;
        drumRms[b] = kR + sR * 0.6F;
        crest[b] = (kR > 1e-5F) ? static_cast<float>(peak / kR) : 0.0F;
    }

    struct OnsetPoint {
        double timeSec;
        float rmsDb;
        bool isKick;
    };
    std::vector<OnsetPoint> onsets;

    for (int b = 2; b < static_cast<int>(totalBlocks) - 2; ++b) {
        const float kDb = 20.0F * std::log10(std::max(1e-5F, kickRms[b]));
        const float dDb = 20.0F * std::log10(std::max(1e-5F, drumRms[b]));

        if (drumRms[b] >= drumRms[b-1] && drumRms[b] >= drumRms[b-2] &&
            drumRms[b] >= drumRms[b+1] && drumRms[b] >= drumRms[b+2]) {
            if (dDb > -18.0F) {
                const double t = static_cast<double>(b * B) / sampleRate_;
                const bool kickTransient = (kDb > -16.0F && crest[b] >= 1.35F);
                if (onsets.empty() || (t - onsets.back().timeSec) >= 0.22) {
                    onsets.push_back({t, dDb, kickTransient});
                } else if (dDb > onsets.back().rmsDb) {
                    onsets.back() = {t, dDb, kickTransient};
                }
            }
        }
    }

    // Grid lock detection: find 4 consecutive beats with consistent IOI (70 to 180 BPM)
    double dropBeatTimeSec = 0.0;
    double entryCueTimeSec = 0.0;
    bool foundGridLock = false;

    for (std::size_t i = 0; i < onsets.size(); ++i) {
        if (!onsets[i].isKick) continue; // Must initiate on a sub-bass kick
        const double t0 = onsets[i].timeSec;

        for (double testBpm = 75.0; testBpm <= 175.0; testBpm += 1.0) {
            const double T = 60.0 / testBpm;
            int matched = 1;

            for (int step = 1; step <= 3; ++step) {
                const double expectedT = t0 + step * T;
                for (std::size_t k = i + 1; k < onsets.size(); ++k) {
                    if (std::abs(onsets[k].timeSec - expectedT) <= 0.08) {
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

    // Fallback: half-time kick search (bars of 4T)
    if (!foundGridLock) {
        for (std::size_t i = 0; i < onsets.size(); ++i) {
            if (!onsets[i].isKick) continue;
            const double t0 = onsets[i].timeSec;

            for (double testBpm = 75.0; testBpm <= 175.0; testBpm += 1.0) {
                const double T = 60.0 / testBpm;
                const double bar = 4.0 * T;
                int matchedBars = 1;

                for (int step = 1; step <= 2; ++step) {
                    const double expectedBar = t0 + step * bar;
                    for (std::size_t k = i + 1; k < onsets.size(); ++k) {
                        if (std::abs(onsets[k].timeSec - expectedBar) <= 0.12) {
                            matchedBars++;
                            break;
                        }
                    }
                }

                if (matchedBars >= 3) {
                    foundGridLock = true;
                    dropBeatTimeSec = t0;
                    entryCueTimeSec = std::max(0.0, t0 - bar);
                    break;
                }
            }
            if (foundGridLock) break;
        }
    }

    if (!foundGridLock) {
        for (std::size_t f = 0; f < searchLimit; ++f) {
            const std::size_t idx = (deckBReadPos_ + f) % totalCapacityFrames;
            const float l = std::abs(deckBRingBuffer_[idx * 2]);
            const float r = std::abs(deckBRingBuffer_[idx * 2 + 1]);
            if (l >= kSilenceThresholdLinear || r >= kSilenceThresholdLinear) {
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

void AutoMixEngine::feedDeckARollingBuffer(const float* stereo, std::size_t frames) noexcept {
    if (stereo == nullptr || frames == 0) return;
    const std::size_t cap = deckARollingBuffer_.size() / 2;
    if (cap == 0) return;
    for (std::size_t f = 0; f < frames; ++f) {
        deckARollingBuffer_[deckARollingWritePos_ * 2] = stereo[f * 2];
        deckARollingBuffer_[deckARollingWritePos_ * 2 + 1] = stereo[f * 2 + 1];
        deckARollingWritePos_ = (deckARollingWritePos_ + 1) % cap;
    }
}

void AutoMixEngine::captureDeckALoop(float bpmA) noexcept {
    if (bpmA < 50.0F || bpmA > 220.0F) bpmA = 120.0F;
    const double barSeconds = 4.0 * (60.0 / static_cast<double>(bpmA));
    deckALoopFrames_ = static_cast<std::size_t>(std::round(barSeconds * sampleRate_));
    const std::size_t cap = deckARollingBuffer_.size() / 2;
    if (deckALoopFrames_ > cap || deckALoopFrames_ == 0) {
        deckALoopFrames_ = std::min(cap, static_cast<std::size_t>(sampleRate_ * 2.0));
    }
    deckAOneBarLoopBuffer_.resize(deckALoopFrames_ * 2);
    const std::size_t startPos = (deckARollingWritePos_ + cap - deckALoopFrames_) % cap;
    for (std::size_t f = 0; f < deckALoopFrames_; ++f) {
        const std::size_t idx = (startPos + f) % cap;
        deckAOneBarLoopBuffer_[f * 2] = deckARollingBuffer_[idx * 2];
        deckAOneBarLoopBuffer_[f * 2 + 1] = deckARollingBuffer_[idx * 2 + 1];
    }
    deckALoopReadPos_ = 0;
    deckALoopActive_ = true;
}

bool AutoMixEngine::isAtPhraseBoundary(const float* stereo, std::size_t frames) noexcept {
    if (stereo == nullptr || frames == 0) return true;
    double energySum = 0.0;
    for (std::size_t f = 0; f < frames; ++f) {
        float mono = (stereo[f * 2] + stereo[f * 2 + 1]) * 0.5F;
        float filtered = deckAVocalDetectorBp_.tick(mono, 0);
        energySum += filtered * filtered;
    }
    float rms = static_cast<float>(std::sqrt(energySum / frames));
    float db = (rms > 1e-5F) ? (20.0F * std::log10(rms)) : -60.0F;
    return (db < -26.0F);
}

std::int64_t AutoMixEngine::getDeckBDropBeatMs() const noexcept {
    return deckBDropBeatMs_;
}

void AutoMixEngine::triggerFadeNow() noexcept {
    // 4-bar fast blend (~4-5s) on user Next button press
    startTransition(AutoMixState::FadingFast, 4);
}

void AutoMixEngine::startTransition(AutoMixState mode, int bars) noexcept {
    state_.store(mode, std::memory_order_release);

    float bpmA = deckADetector_.getResult().bpm;
    if (bpmA < 50.0F || bpmA > 220.0F) bpmA = 120.0F;

    float bpmB = deckBDetector_.getResult().bpm;
    if (bpmB < 50.0F || bpmB > 220.0F) bpmB = bpmA;

    // Rule 2: Capture 1-bar loop exit bed from Deck A
    captureDeckALoop(bpmA);

    // Transition duration = bars * 4 * (60 / bpmA) seconds
    const double barSeconds = 4.0 * (60.0 / static_cast<double>(bpmA));
    double transitionSeconds = static_cast<double>(bars) * barSeconds;
    if (mode == AutoMixState::FadingFast) {
        transitionSeconds = std::clamp(transitionSeconds, 4.0, 5.5);
    } else {
        transitionSeconds = std::max(4.0, transitionSeconds);
    }

    transitionTotalFrames_ = static_cast<std::size_t>(std::round(transitionSeconds * sampleRate_));
    transitionCurrentFrame_ = 0;
    transitionProgress_ = 0.0F;
    bassSwapped_ = false;

    // BPM Tempo Lock: Match within +/- 10% (including half/double time)
    float ratio = 1.0F;
    const float diffDirect = std::abs(bpmB - bpmA) / bpmA;
    const float diffHalf = std::abs(bpmB * 2.0F - bpmA) / bpmA;
    const float diffDouble = std::abs(bpmB * 0.5F - bpmA) / bpmA;

    if (diffDirect <= 0.10F) {
        ratio = bpmA / bpmB;
    } else if (diffHalf <= 0.10F) {
        ratio = bpmA / (bpmB * 2.0F);
    } else if (diffDouble <= 0.10F) {
        ratio = bpmA / (bpmB * 0.5F);
    }

    deckBStretcher_.setTimeRatio(ratio);

    // Prepare filters, 220Hz HPF, sweeps, and echo
    deckAFilter_.reset();
    deckBFilter_.reset();
    deckBHpf220_.reset();
    deckAHpf_.reset();
    deckAEcho_.configure(sampleRate_, bpmA);
    deckAEcho_.setEnabled(true);
}

void AutoMixEngine::startTransition(int durationMs) noexcept {
    AutoMixState curr = state_.load(std::memory_order_acquire);
    if (curr == AutoMixState::FadingAuto || curr == AutoMixState::FadingFast) {
        return;
    }
    const double sec = (durationMs > 0) ? (static_cast<double>(durationMs) / 1000.0) : 16.0;
    state_.store(AutoMixState::FadingAuto, std::memory_order_release);

    float bpmA = deckADetector_.getResult().bpm;
    if (bpmA < 50.0F || bpmA > 220.0F) bpmA = 120.0F;

    float bpmB = deckBDetector_.getResult().bpm;
    if (bpmB < 50.0F || bpmB > 220.0F) bpmB = bpmA;

    // Rule 2: Capture 1-bar loop exit bed from Deck A
    captureDeckALoop(bpmA);

    transitionTotalFrames_ = static_cast<std::size_t>(std::round(sec * sampleRate_));
    transitionCurrentFrame_ = 0;
    transitionProgress_ = 0.0F;
    bassSwapped_ = false;

    float ratio = 1.0F;
    const float diffDirect = std::abs(bpmB - bpmA) / bpmA;
    const float diffHalf = std::abs(bpmB * 2.0F - bpmA) / bpmA;
    const float diffDouble = std::abs(bpmB * 0.5F - bpmA) / bpmA;

    if (diffDirect <= 0.10F) {
        ratio = bpmA / bpmB;
    } else if (diffHalf <= 0.10F) {
        ratio = bpmA / (bpmB * 2.0F);
    } else if (diffDouble <= 0.10F) {
        ratio = bpmA / (bpmB * 0.5F);
    }
    deckBStretcher_.setTimeRatio(ratio);

    deckAFilter_.reset();
    deckBFilter_.reset();
    deckBHpf220_.reset();
    deckAHpf_.reset();
    deckAEcho_.configure(sampleRate_, bpmA);
    deckAEcho_.setEnabled(true);
}

void AutoMixEngine::setTransitionProgress(float progress) noexcept {
    transitionProgress_ = std::clamp(progress, 0.0F, 1.0F);
    if (transitionTotalFrames_ > 0) {
        transitionCurrentFrame_ = static_cast<std::size_t>(transitionProgress_ * static_cast<float>(transitionTotalFrames_));
    }
    AutoMixState curr = state_.load(std::memory_order_acquire);
    if (curr == AutoMixState::Idle || curr == AutoMixState::MonitoringOutro) {
        state_.store(AutoMixState::FadingAuto, std::memory_order_release);
    }
    if (transitionProgress_ >= 1.0F) {
        deckBStretcher_.startRecoveryRamp(1.0F, 5.0F);
        deckAEcho_.setEnabled(false);
        deckALoopActive_ = false;
        state_.store(AutoMixState::PostRestore, std::memory_order_release);
    }
}

void AutoMixEngine::processDeckA(float* deckAStereo, std::size_t frames, float progress) noexcept {
    if (deckAStereo == nullptr || frames == 0) return;

    progress = std::clamp(progress, 0.0F, 1.0F);

    // Rule 2: If 1-bar loop is active, Deck A plays the captured drum loop exit bed!
    if (deckALoopActive_ && deckALoopFrames_ > 0) {
        for (std::size_t f = 0; f < frames; ++f) {
            deckAStereo[f * 2] = deckAOneBarLoopBuffer_[deckALoopReadPos_ * 2];
            deckAStereo[f * 2 + 1] = deckAOneBarLoopBuffer_[deckALoopReadPos_ * 2 + 1];
            deckALoopReadPos_ = (deckALoopReadPos_ + 1) % deckALoopFrames_;
        }
    }

    // Rule 3: Zero Bass-Hole Guarantee (Complementary Low-Band Crossover)
    if (progress < 0.75F) {
        // From 0% to 75%: Deck A keeps 100% of its sub-bass (<180 Hz at 0 dB)!
        deckAFilter_.processBassAttenuate(deckAStereo, frames, 1.0F, 1.0F);

        // Gentle volume envelope: solid 1.0 through 50%, then 0.75 by 75%
        float volA = 1.0F;
        if (progress > 0.50F) {
            volA = 1.0F - (progress - 0.50F) / 0.25F * 0.25F;
        }
        for (std::size_t i = 0; i < frames * 2; ++i) {
            deckAStereo[i] *= volA;
        }
    } else {
        // At exactly 75% downbeat (The Drop):
        // Instantly high-pass Deck A at 500 Hz (cutting all sub-bass and low-mids)
        deckAHpf_.update(ResonantFilter::Mode::HighPass, sampleRate_, 500.0, 1.1);
        const float tailProg = (progress - 0.75F) / 0.25F; // 0..1 over final 25%

        for (std::size_t f = 0; f < frames; ++f) {
            deckAStereo[f * 2] = deckAHpf_.tick(deckAStereo[f * 2], 0) * 0.25F; // -12dB mid cut (Rule 4)
            deckAStereo[f * 2 + 1] = deckAHpf_.tick(deckAStereo[f * 2 + 1], 1) * 0.25F;
        }

        // Send into 1.2s dub-echo + reverb tail decaying to 0
        const float dryGain = (1.0F - tailProg) * 0.5F;
        deckAEcho_.process(deckAStereo, frames, 1.0F, dryGain, /*apply55WetMix=*/true);
    }
}

void AutoMixEngine::processDeckB(float* deckBStereo, std::size_t frames, float progress) noexcept {
    if (deckBStereo == nullptr || frames == 0) return;

    progress = std::clamp(progress, 0.0F, 1.0F);

    // Rule 3: Zero Bass-Hole Guarantee & NO Low-Pass Muffle!
    // From 0% to 75%: High-pass filtered at 220 Hz (bringing in crisp highs and mids cleanly)
    if (progress < 0.75F) {
        deckBHpf220_.processHighPass(deckBStereo, frames);

        // Enters clearly at 0.35 volume, ramping to 1.0 by 0.75
        float volB = 0.35F + (progress / 0.75F) * 0.65F;
        for (std::size_t i = 0; i < frames * 2; ++i) {
            deckBStereo[i] *= volB;
        }
    } else {
        // At 75% downbeat: Instantaneous full-range snap! (0 dB across all frequencies)
        // Deck B has 100% full sub-bass, 100% full highs, 100% volume
    }
}

void AutoMixEngine::process(float* deckAStereo, std::size_t frames) noexcept {
    if (deckAStereo == nullptr || frames == 0) return;

    if (!enabled_.load(std::memory_order_acquire)) {
        return;
    }

    // Monitor Deck A BPM & feed rolling buffer for 1-bar loop capture
    deckADetector_.process(deckAStereo, frames);
    feedDeckARollingBuffer(deckAStereo, frames);

    AutoMixState currState = state_.load(std::memory_order_acquire);

    // Outro energy drop detector during MonitoringOutro
    if (currState == AutoMixState::MonitoringOutro) {
        double frameEnergySum = 0.0;
        for (std::size_t i = 0; i < frames; ++i) {
            const float l = deckAStereo[i * 2];
            const float r = deckAStereo[i * 2 + 1];
            frameEnergySum += (l * l + r * r) * 0.5;
        }
        const float chunkEnergy = static_cast<float>(std::sqrt(frameEnergySum / frames));

        shortTermEnergy_ += 0.05F * (chunkEnergy - shortTermEnergy_);
        if (energyHistoryFrames_ < static_cast<std::size_t>(sampleRate_ * 10.0)) {
            baselineEnergy_ += 0.01F * (chunkEnergy - baselineEnergy_);
            energyHistoryFrames_ += frames;
        }

        if (energyHistoryFrames_ >= static_cast<std::size_t>(sampleRate_ * 3.0) &&
            baselineEnergy_ > 0.02F && shortTermEnergy_ < baselineEnergy_ * 0.35F) {
            startTransition(AutoMixState::FadingAuto, barMode_.load(std::memory_order_acquire));
            currState = state_.load(std::memory_order_acquire);
        }
    }

    // If fading (FadingAuto or FadingFast)
    if (currState == AutoMixState::FadingAuto || currState == AutoMixState::FadingFast) {
        const float p = (transitionTotalFrames_ > 0)
            ? static_cast<float>(transitionCurrentFrame_) / static_cast<float>(transitionTotalFrames_)
            : 1.0F;
        transitionProgress_ = std::clamp(p, 0.0F, 1.0F);

        // Read Deck B frames from prebuffer ring
        if (deckBScratch_.size() < frames * 2) {
            deckBScratch_.resize(frames * 2, 0.0F);
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
            std::fill(deckBScratch_.begin() + actualDeckBFrames * 2, deckBScratch_.begin() + frames * 2, 0.0F);
        }

        // Time-stretch Deck B if ratio != 1.0
        if (deckBStretchedScratch_.size() < frames * 4) {
            deckBStretchedScratch_.resize(frames * 4, 0.0F);
        }
        std::size_t stretchedFrames = deckBStretcher_.process(
            deckBScratch_.data(), frames, deckBStretchedScratch_.data(), frames * 2);
        if (stretchedFrames < frames) {
            std::copy(deckBScratch_.begin() + stretchedFrames * 2, deckBScratch_.begin() + frames * 2,
                      deckBStretchedScratch_.begin() + stretchedFrames * 2);
        }

        // Apply 3-stage C++ DSP on Deck A and Deck B
        processDeckA(deckAStereo, frames, transitionProgress_);
        processDeckB(deckBStretchedScratch_.data(), frames, transitionProgress_);

        // Mix Deck B into deckAStereo
        for (std::size_t i = 0; i < frames * 2; ++i) {
            deckAStereo[i] += deckBStretchedScratch_[i];
        }

        transitionCurrentFrame_ += frames;

        // Fade complete!
        if (transitionCurrentFrame_ >= transitionTotalFrames_) {
            deckBStretcher_.startRecoveryRamp(1.0F, 5.0F);
            deckAEcho_.setEnabled(false);
            state_.store(AutoMixState::PostRestore, std::memory_order_release);
        }
    } else if (currState == AutoMixState::PostRestore) {
        if (deckBStretcher_.currentRatio() == 1.0F) {
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
                scratch[i] = static_cast<float>(in[i]) / 32768.0F;
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
                scratch[i] = static_cast<float>(val) / 8388608.0F;
            }
            break;
        }
        case 2: { // PCM_I32
            const auto* in = static_cast<const std::int32_t*>(pcm);
            for (std::size_t i = 0; i < frameCount * 2; ++i) {
                scratch[i] = static_cast<float>(in[i]) / 2147483648.0F;
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
                transitionProgress_ = 1.0F;
                deckBStretcher_.startRecoveryRamp(1.0F, 5.0F);
                deckAEcho_.setEnabled(false);
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
                    const float clamped = std::clamp(scratch[i], -1.0F, 1.0F);
                    out[i] = static_cast<std::int16_t>(std::round(clamped * 32767.0F));
                }
                break;
            }
            case 1: { // PCM_I24_PACKED
                auto* out = static_cast<std::uint8_t*>(pcm);
                for (std::size_t i = 0; i < frameCount * 2; ++i) {
                    const float clamped = std::clamp(scratch[i], -1.0F, 1.0F);
                    const auto val = static_cast<std::int32_t>(std::round(clamped * 8388607.0F));
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
                    const float clamped = std::clamp(scratch[i], -1.0F, 1.0F);
                    out[i] = static_cast<std::int32_t>(std::round(clamped * 2147483647.0F));
                }
                break;
            }
            default:
                break;
        }
    }

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

} // namespace lastwave::audio
