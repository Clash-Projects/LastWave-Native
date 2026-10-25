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

AutoMixEngine::AutoMixEngine() noexcept {
    configure(48000.0);
}

void AutoMixEngine::configure(double sampleRate) noexcept {
    if (sampleRate <= 0.0) sampleRate = 48000.0;
    sampleRate_ = sampleRate;

    deckAFilter_.configure(sampleRate_, 160.0);
    deckBFilter_.configure(sampleRate_, 160.0);
    deckBStretcher_.configure(sampleRate_, 2);
    deckADetector_.configure(sampleRate_);
    deckBDetector_.configure(sampleRate_);
    deckAEcho_.configure(sampleRate_, 120.0F);

    // Buffer capacity: 30 seconds of stereo Float32
    const std::size_t ringFrames = static_cast<std::size_t>(sampleRate_ * 30.0);
    {
        std::lock_guard<std::mutex> lock(bufferMutex_);
        deckBRingBuffer_.assign(ringFrames * 2, 0.0F);
        deckBReadPos_ = 0;
        deckBWritePos_ = 0;
        deckBAvailableFrames_ = 0;
    }

    deckBScratch_.resize(4096 * 2, 0.0F);
    deckBStretchedScratch_.resize(4096 * 4, 0.0F);

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
    deckBStretcher_.reset();
    deckADetector_.reset();
    deckBDetector_.reset();
    deckAEcho_.reset();

    deckBLeadingTrimFrames_ = 0;
    deckBTrimCalculated_ = false;
    transitionTotalFrames_ = 0;
    transitionCurrentFrame_ = 0;
    transitionProgress_ = 0.0F;
    bassSwapped_ = false;

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

    // Calculate leading silence trim on first chunk
    if (!deckBTrimCalculated_ && deckBAvailableFrames_ >= static_cast<std::size_t>(sampleRate_ * 0.5)) {
        trimDeckBLeadingSilence();
    }

    AutoMixState curr = state_.load(std::memory_order_acquire);
    if (curr == AutoMixState::Idle) {
        state_.store(AutoMixState::MonitoringOutro, std::memory_order_release);
    }

    return framesToWrite;
}

void AutoMixEngine::trimDeckBLeadingSilence() noexcept {
    const std::size_t totalCapacityFrames = deckBRingBuffer_.size() / 2;
    const std::size_t searchLimit = std::min(deckBAvailableFrames_, static_cast<std::size_t>(sampleRate_ * 5.0)); // up to 5s

    std::size_t trimFrames = 0;
    for (std::size_t f = 0; f < searchLimit; ++f) {
        const std::size_t idx = (deckBReadPos_ + f) % totalCapacityFrames;
        const float l = std::abs(deckBRingBuffer_[idx * 2]);
        const float r = std::abs(deckBRingBuffer_[idx * 2 + 1]);
        if (l >= kSilenceThresholdLinear || r >= kSilenceThresholdLinear) {
            trimFrames = f;
            break;
        }
    }

    if (trimFrames > 0 && trimFrames < deckBAvailableFrames_) {
        deckBReadPos_ = (deckBReadPos_ + trimFrames) % totalCapacityFrames;
        deckBAvailableFrames_ -= trimFrames;
        deckBLeadingTrimFrames_ = trimFrames;
    }
    deckBTrimCalculated_ = true;
}

void AutoMixEngine::triggerFadeNow() noexcept {
    // 4-bar fast blend on user Next button press
    startTransition(AutoMixState::FadingFast, 4);
}

void AutoMixEngine::startTransition(AutoMixState mode, int bars) noexcept {
    state_.store(mode, std::memory_order_release);

    float bpmA = deckADetector_.getResult().bpm;
    if (bpmA < 50.0F || bpmA > 220.0F) bpmA = 120.0F;

    float bpmB = deckBDetector_.getResult().bpm;
    if (bpmB < 50.0F || bpmB > 220.0F) bpmB = bpmA;

    // Transition duration = bars * 4 * (60 / bpmA) seconds
    const double barSeconds = 4.0 * (60.0 / static_cast<double>(bpmA));
    const double transitionSeconds = std::max(2.0, static_cast<double>(bars) * barSeconds);
    transitionTotalFrames_ = static_cast<std::size_t>(std::round(transitionSeconds * sampleRate_));
    transitionCurrentFrame_ = 0;
    transitionProgress_ = 0.0F;
    bassSwapped_ = false;

    // Tempo sync if within +/- 8% (including half/double time)
    float ratio = 1.0F;
    const float diffDirect = std::abs(bpmB - bpmA) / bpmA;
    const float diffHalf = std::abs(bpmB * 2.0F - bpmA) / bpmA;
    const float diffDouble = std::abs(bpmB * 0.5F - bpmA) / bpmA;

    if (diffDirect <= 0.08F) {
        ratio = bpmA / bpmB;
    } else if (diffHalf <= 0.08F) {
        ratio = bpmA / (bpmB * 2.0F);
    } else if (diffDouble <= 0.08F) {
        ratio = bpmA / (bpmB * 0.5F);
    }

    deckBStretcher_.setTimeRatio(ratio);

    // Prepare filters and echo
    deckAFilter_.reset();
    deckBFilter_.reset();
    deckAEcho_.configure(sampleRate_, bpmA);
    deckAEcho_.setEnabled(true);
}

void AutoMixEngine::process(float* deckAStereo, std::size_t frames) noexcept {
    if (deckAStereo == nullptr || frames == 0) return;

    if (!enabled_.load(std::memory_order_acquire)) {
        return;
    }

    // Monitor Deck A BPM
    deckADetector_.process(deckAStereo, frames);

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

        // Midpoint bass-swap: occurs at 50% downbeat
        if (transitionProgress_ >= 0.5F && !bassSwapped_) {
            bassSwapped_ = true;
        }

        const float deckAGain = static_cast<float>(std::cos(transitionProgress_ * (M_PI * 0.5)));
        const float deckBGain = static_cast<float>(std::sin(transitionProgress_ * (M_PI * 0.5)));

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

        // Linkwitz-Riley Bass-Swap
        if (!bassSwapped_) {
            deckBFilter_.processHighPass(deckBStretchedScratch_.data(), frames);
        } else {
            deckAFilter_.processHighPass(deckAStereo, frames);
        }

        // Deck A Echo tail
        const float echoSend = (transitionProgress_ >= 0.5F)
            ? ((transitionProgress_ - 0.5F) * 2.0F)
            : 0.0F;
        deckAEcho_.process(deckAStereo, frames, echoSend, deckAGain);

        // Mix Deck B into deckAStereo
        for (std::size_t i = 0; i < frames * 2; ++i) {
            deckAStereo[i] += deckBStretchedScratch_[i] * deckBGain;
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

AutoMixState AutoMixEngine::getState() const noexcept {
    return state_.load(std::memory_order_acquire);
}

float AutoMixEngine::getTransitionProgress() const noexcept {
    return transitionProgress_;
}

std::size_t AutoMixEngine::getDeckBLeadingTrimFrames() const noexcept {
    return deckBLeadingTrimFrames_;
}

float AutoMixEngine::getDeckABpm() const noexcept {
    return deckADetector_.getResult().bpm;
}

float AutoMixEngine::getDeckBBpm() const noexcept {
    return deckBDetector_.getResult().bpm;
}

} // namespace lastwave::audio
