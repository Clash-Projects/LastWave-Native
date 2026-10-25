#pragma once

#include <cmath>
#include <cstddef>
#include <vector>
#include <array>
#include <algorithm>

namespace lastwave::audio {

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

class EchoTailProcessor final {
public:
    EchoTailProcessor() noexcept {
        configure(48000.0, 120.0F);
    }

    void configure(double sampleRate, float bpm = 120.0F) noexcept {
        if (sampleRate <= 0.0) sampleRate = 48000.0;
        sampleRate_ = sampleRate;

        bpm = std::clamp(bpm, 50.0F, 220.0F);
        // Delay time: 3/8 beat (dotted eighth) or 1/2 beat
        // Clamp delay between 200ms and 450ms
        const double beatSeconds = 60.0 / static_cast<double>(bpm);
        double delaySeconds = beatSeconds * 0.5; // half beat
        if (delaySeconds > 0.45) delaySeconds = beatSeconds * 0.25; // 1/4 beat for slow tempos
        delaySeconds = std::clamp(delaySeconds, 0.18, 0.45);

        delayFrames_ = static_cast<std::size_t>(std::round(delaySeconds * sampleRate_));

        // Feedback calculation for T60 = 1.2s
        // g^(1.2 / delaySeconds) = 0.001 (-60 dB)
        // ln(g) = -6.907755 * (delaySeconds / 1.2)
        const double decayTime = 1.2;
        feedbackGain_ = static_cast<float>(std::exp(-6.907755 * (delaySeconds / decayTime)));

        // Damping 1-pole filter at ~4500 Hz
        const double dampCutoffHz = 4500.0;
        const double omega = 2.0 * M_PI * dampCutoffHz / sampleRate_;
        dampFactor_ = static_cast<float>(std::clamp(1.0 - std::exp(-omega), 0.05, 0.95));

        // Buffer size: max 2.5 seconds stereo
        const std::size_t maxFrames = static_cast<std::size_t>(sampleRate_ * 2.5);
        buffer_.assign(maxFrames * 2, 0.0F);
        writeIndex_ = 0;
        filterState_[0] = 0.0F;
        filterState_[1] = 0.0F;
    }

    void reset() noexcept {
        std::fill(buffer_.begin(), buffer_.end(), 0.0F);
        writeIndex_ = 0;
        filterState_[0] = 0.0F;
        filterState_[1] = 0.0F;
    }

    void setEnabled(bool enabled) noexcept {
        enabled_ = enabled;
    }

    [[nodiscard]] bool isEnabled() const noexcept {
        return enabled_;
    }

    [[nodiscard]] std::size_t delayFrames() const noexcept {
        return delayFrames_;
    }

    void process(float* stereoInterleaved, std::size_t frames, float sendGain, float dryGain) noexcept {
        if (stereoInterleaved == nullptr || frames == 0) return;
        if (!enabled_ && sendGain <= 0.0F) {
            // Apply dry gain only
            if (std::abs(dryGain - 1.0F) > 0.0001F) {
                for (std::size_t i = 0; i < frames * 2; ++i) {
                    stereoInterleaved[i] *= dryGain;
                }
            }
            return;
        }

        const std::size_t totalBufferFrames = buffer_.size() / 2;

        for (std::size_t f = 0; f < frames; ++f) {
            const std::size_t inOffset = f * 2;

            // Read delayed samples
            std::size_t readPos = (writeIndex_ >= delayFrames_)
                ? (writeIndex_ - delayFrames_)
                : (totalBufferFrames + writeIndex_ - delayFrames_);

            const float echoL = buffer_[readPos * 2];
            const float echoR = buffer_[readPos * 2 + 1];

            // 1-pole low-pass damping filter on feedback
            filterState_[0] += dampFactor_ * (echoL - filterState_[0]);
            filterState_[1] += dampFactor_ * (echoR - filterState_[1]);

            // Feedback with decay
            const float feedbackL = filterState_[0] * feedbackGain_;
            const float feedbackR = filterState_[1] * feedbackGain_;

            // Input send into delay buffer
            const float inL = stereoInterleaved[inOffset];
            const float inR = stereoInterleaved[inOffset + 1];

            buffer_[writeIndex_ * 2] = inL * sendGain + feedbackL;
            buffer_[writeIndex_ * 2 + 1] = inR * sendGain + feedbackR;

            writeIndex_ = (writeIndex_ + 1) % totalBufferFrames;

            // Mix dry output + echo return
            stereoInterleaved[inOffset] = inL * dryGain + echoL * 0.75F;
            stereoInterleaved[inOffset + 1] = inR * dryGain + echoR * 0.75F;
        }
    }

private:
    double sampleRate_{48000.0};
    std::size_t delayFrames_{18000};
    float feedbackGain_{0.3F};
    float dampFactor_{0.45F};
    bool enabled_{false};

    std::vector<float> buffer_;
    std::size_t writeIndex_{0};
    std::array<float, 2> filterState_{0.0F, 0.0F};
};

} // namespace lastwave::audio
