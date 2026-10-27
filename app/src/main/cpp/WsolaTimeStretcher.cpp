#include "WsolaTimeStretcher.h"
#include <cmath>
#include <algorithm>
#include <cstring>

namespace lastwave::audio {

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

WsolaTimeStretcher::WsolaTimeStretcher() noexcept {
    configure(48000.0, 2);
}

void WsolaTimeStretcher::configure(double sampleRate, std::size_t channels) noexcept {
    if (sampleRate <= 0.0) sampleRate = 48000.0;
    sampleRate_ = sampleRate;
    channels_ = (channels == 1) ? 1 : 2;

    hannWindow_.resize(kWindowSize);
    for (std::size_t i = 0; i < kWindowSize; ++i) {
        // Periodic Hann window
        hannWindow_[i] = static_cast<float>(
            0.5 * (1.0 - std::cos(2.0 * M_PI * static_cast<double>(i) / static_cast<double>(kWindowSize))));
    }

    inputBuffer_.reserve(kWindowSize * 8 * channels_);
    outputBuffer_.reserve(kWindowSize * 8 * channels_);
    prevTemplate_.resize(kWindowSize * channels_, 0.0F);

    reset();
}

void WsolaTimeStretcher::reset() noexcept {
    inputBuffer_.clear();
    outputBuffer_.clear();
    std::fill(prevTemplate_.begin(), prevTemplate_.end(), 0.0F);
    nominalAnalysisPos_ = 0.0;
    hasPreviousTemplate_ = false;
    currentRatio_ = targetRatio_;
    rampActive_ = false;
}

void WsolaTimeStretcher::setTimeRatio(float ratio) noexcept {
    targetRatio_ = std::clamp(ratio, 0.85F, 1.15F);
    if (!rampActive_) {
        currentRatio_ = targetRatio_;
    }
}

void WsolaTimeStretcher::startRecoveryRamp(float targetRatio, float durationSeconds) noexcept {
    rampTarget_ = std::clamp(targetRatio, 0.85F, 1.15F);
    durationSeconds = std::max(0.05F, durationSeconds);
    const double totalHops = (durationSeconds * sampleRate_) / static_cast<double>(kSynthesisHop);
    rampStepPerHop_ = static_cast<float>((rampTarget_ - currentRatio_) / std::max(1.0, totalHops));
    rampActive_ = true;
}

void WsolaTimeStretcher::updateRamp() noexcept {
    if (!rampActive_) return;
    currentRatio_ += rampStepPerHop_;
    if ((rampStepPerHop_ > 0.0F && currentRatio_ >= rampTarget_) ||
        (rampStepPerHop_ < 0.0F && currentRatio_ <= rampTarget_)) {
        currentRatio_ = rampTarget_;
        targetRatio_ = rampTarget_;
        rampActive_ = false;
    }
}

std::size_t WsolaTimeStretcher::findBestOffset(std::size_t nominalPos) const noexcept {
    if (!hasPreviousTemplate_ || inputBuffer_.size() < (nominalPos + kWindowSize + kSearchWindow) * channels_) {
        return nominalPos;
    }

    const std::size_t minPos = (nominalPos >= kSearchWindow) ? (nominalPos - kSearchWindow) : 0;
    const std::size_t maxPos = nominalPos + kSearchWindow;
    const std::size_t maxAllowed = (inputBuffer_.size() / channels_) - kWindowSize;
    const std::size_t clampedMax = std::min(maxPos, maxAllowed);

    if (minPos >= clampedMax) return nominalPos;

    float bestScore = -1.0e30F;
    std::size_t bestPos = nominalPos;

    for (std::size_t cand = minPos; cand <= clampedMax; cand += 2) {
        float score = 0.0F;
        const float* candPtr = &inputBuffer_[cand * channels_];
        const float* prevPtr = prevTemplate_.data();

        for (std::size_t i = 0; i < kWindowSize; i += 2) {
            score += prevPtr[i * channels_] * candPtr[i * channels_];
        }

        if (score > bestScore) {
            bestScore = score;
            bestPos = cand;
        }
    }

    return bestPos;
}

std::size_t WsolaTimeStretcher::process(
    const float* inputStereo,
    std::size_t inputFrames,
    float* outputStereo,
    std::size_t outputCapacityFrames) noexcept {
    if (outputStereo == nullptr || outputCapacityFrames == 0) return 0;

    // Append input to buffer
    if (inputStereo != nullptr && inputFrames > 0) {
        const std::size_t samples = inputFrames * channels_;
        inputBuffer_.insert(inputBuffer_.end(), inputStereo, inputStereo + samples);
    }

    const std::size_t minInputNeeded = (kWindowSize + kSearchWindow * 2) * channels_;

    // Generate synthesis hops
    while (inputBuffer_.size() >= minInputNeeded) {
        updateRamp();

        const double analysisHop = static_cast<double>(kSynthesisHop) / static_cast<double>(currentRatio_);
        const std::size_t nominalPos = static_cast<std::size_t>(std::max(0.0, nominalAnalysisPos_));
        const std::size_t bestPos = findBestOffset(nominalPos);

        // Current start of new window in outputBuffer
        // The overlap region is the last (kWindowSize - kSynthesisHop) frames
        const std::size_t currentOutFrames = outputBuffer_.size() / channels_;
        const std::size_t synthesisStart = (currentOutFrames >= (kWindowSize - kSynthesisHop))
            ? (currentOutFrames - (kWindowSize - kSynthesisHop))
            : 0;

        // Resize outputBuffer to accommodate synthesisStart + kWindowSize frames
        const std::size_t requiredFrames = synthesisStart + kWindowSize;
        if (currentOutFrames < requiredFrames) {
            outputBuffer_.resize(requiredFrames * channels_, 0.0F);
        }

        // Overlap-add windowed segment
        for (std::size_t i = 0; i < kWindowSize; ++i) {
            const float w = hannWindow_[i];
            const std::size_t outIdx = (synthesisStart + i) * channels_;
            const std::size_t inIdx = (bestPos + i) * channels_;
            for (std::size_t ch = 0; ch < channels_; ++ch) {
                outputBuffer_[outIdx + ch] += inputBuffer_[inIdx + ch] * w;
            }
        }

        // Save synthesis segment (shifted forward by kSynthesisHop) as template for next hop
        for (std::size_t i = 0; i < kWindowSize; ++i) {
            const std::size_t srcIndex = bestPos + kSynthesisHop + i;
            for (std::size_t ch = 0; ch < channels_; ++ch) {
                if (srcIndex * channels_ + ch < inputBuffer_.size()) {
                    prevTemplate_[i * channels_ + ch] = inputBuffer_[srcIndex * channels_ + ch] * hannWindow_[i];
                } else {
                    prevTemplate_[i * channels_ + ch] = 0.0F;
                }
            }
        }
        hasPreviousTemplate_ = true;

        // Advance input buffer by analysisHop
        const std::size_t consumedFrames = static_cast<std::size_t>(analysisHop);
        if (consumedFrames * channels_ < inputBuffer_.size()) {
            inputBuffer_.erase(inputBuffer_.begin(), inputBuffer_.begin() + consumedFrames * channels_);
        } else {
            inputBuffer_.clear();
        }
        nominalAnalysisPos_ = 0.0;
    }

    // Drain ready frames from outputBuffer_ to outputStereo
    // Ready frames are those beyond the overlap region (up to outputBuffer_.size() - (kWindowSize - kSynthesisHop))
    std::size_t framesWritten = 0;
    const std::size_t overlapFrames = kWindowSize - kSynthesisHop;
    if (outputBuffer_.size() > overlapFrames * channels_) {
        const std::size_t availableFrames = (outputBuffer_.size() / channels_) - overlapFrames;
        framesWritten = std::min(availableFrames, outputCapacityFrames);

        std::memcpy(outputStereo, outputBuffer_.data(), framesWritten * channels_ * sizeof(float));
        outputBuffer_.erase(outputBuffer_.begin(), outputBuffer_.begin() + framesWritten * channels_);
    }

    return framesWritten;
}

std::size_t WsolaTimeStretcher::flush(float* outputStereo, std::size_t outputCapacityFrames) noexcept {
    if (outputStereo == nullptr || outputCapacityFrames == 0) return 0;

    const std::size_t availableFrames = outputBuffer_.size() / channels_;
    const std::size_t framesToCopy = std::min(availableFrames, outputCapacityFrames);

    if (framesToCopy > 0) {
        std::memcpy(outputStereo, outputBuffer_.data(), framesToCopy * channels_ * sizeof(float));
        outputBuffer_.erase(outputBuffer_.begin(), outputBuffer_.begin() + framesToCopy * channels_);
    }
    return framesToCopy;
}

} // namespace lastwave::audio
