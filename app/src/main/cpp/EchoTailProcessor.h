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
    struct CombFilter final {
        std::vector<float> buffer;
        std::size_t writeIdx{0};
        float feedback{0.75F};
        float filterState{0.0F};
        float damp{0.25F};

        void init(std::size_t size, float fb, float d) noexcept {
            buffer.assign(std::max<std::size_t>(size, 2), 0.0F);
            writeIdx = 0;
            feedback = fb;
            filterState = 0.0F;
            damp = d;
        }

        void reset() noexcept {
            std::fill(buffer.begin(), buffer.end(), 0.0F);
            writeIdx = 0;
            filterState = 0.0F;
        }

        [[nodiscard]] inline float tick(float in) noexcept {
            const float out = buffer[writeIdx];
            filterState = out * (1.0F - damp) + filterState * damp;
            buffer[writeIdx] = in + filterState * feedback;
            writeIdx = (writeIdx + 1) % buffer.size();
            return out;
        }
    };

    struct AllPassFilter final {
        std::vector<float> buffer;
        std::size_t writeIdx{0};
        float feedback{0.5F};

        void init(std::size_t size, float fb = 0.5F) noexcept {
            buffer.assign(std::max<std::size_t>(size, 2), 0.0F);
            writeIdx = 0;
            feedback = fb;
        }

        void reset() noexcept {
            std::fill(buffer.begin(), buffer.end(), 0.0F);
            writeIdx = 0;
        }

        [[nodiscard]] inline float tick(float in) noexcept {
            const float bufOut = buffer[writeIdx];
            const float out = -in + bufOut;
            buffer[writeIdx] = in + bufOut * feedback;
            writeIdx = (writeIdx + 1) % buffer.size();
            return out;
        }
    };

    EchoTailProcessor() noexcept {
        configure(48000.0, 120.0F);
    }

    void configure(double sampleRate, float bpm = 120.0F) noexcept {
        if (sampleRate <= 0.0) sampleRate = 48000.0;
        sampleRate_ = sampleRate;

        bpm = std::clamp(bpm, 50.0F, 220.0F);
        // Delay time: exactly 1/2 beat
        const double beatSeconds = 60.0 / static_cast<double>(bpm);
        double delaySeconds = beatSeconds * 0.5; // 1/2 beat
        delaySeconds = std::clamp(delaySeconds, 0.15, 0.60);

        delayFrames_ = static_cast<std::size_t>(std::round(delaySeconds * sampleRate_));

        // 1.2s Schroeder reverb decay time (T60 = 1.2s)
        const double decayTime = 1.2;
        const std::array<double, 4> combDelaysMs{29.7, 37.1, 41.1, 43.7};

        for (std::size_t i = 0; i < 4; ++i) {
            const double dSec = combDelaysMs[i] * 0.001;
            const float fb = static_cast<float>(std::exp(-6.907755 * (dSec / decayTime)));
            const std::size_t dLeft = static_cast<std::size_t>(std::round(combDelaysMs[i] * 0.001 * sampleRate_));
            const std::size_t dRight = static_cast<std::size_t>(std::round((combDelaysMs[i] + 1.3) * 0.001 * sampleRate_));
            combL_[i].init(dLeft, fb, 0.28F);
            combR_[i].init(dRight, fb, 0.28F);
        }

        allPassL_[0].init(static_cast<std::size_t>(std::round(5.0 * 0.001 * sampleRate_)), 0.5F);
        allPassL_[1].init(static_cast<std::size_t>(std::round(1.7 * 0.001 * sampleRate_)), 0.5F);
        allPassR_[0].init(static_cast<std::size_t>(std::round(5.3 * 0.001 * sampleRate_)), 0.5F);
        allPassR_[1].init(static_cast<std::size_t>(std::round(1.9 * 0.001 * sampleRate_)), 0.5F);

        // Feedback calculation for main 1/2 beat delay line with T60 = 1.2s
        delayFeedback_ = static_cast<float>(std::exp(-6.907755 * (delaySeconds / decayTime)));

        // 1-pole damping filter at 4500 Hz
        const double dampCutoffHz = 4500.0;
        const double omega = 2.0 * M_PI * dampCutoffHz / sampleRate_;
        dampFactor_ = static_cast<float>(std::clamp(1.0 - std::exp(-omega), 0.05, 0.95));

        // Delay buffer: max 3.0 seconds stereo
        const std::size_t maxFrames = static_cast<std::size_t>(sampleRate_ * 3.0);
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

        for (auto& c : combL_) c.reset();
        for (auto& c : combR_) c.reset();
        for (auto& ap : allPassL_) ap.reset();
        for (auto& ap : allPassR_) ap.reset();
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

    // Processes Deck A audio: sends into 1/2-beat delay + 1.5s Schroeder reverb tail
    void process(
        float* stereoInterleaved,
        std::size_t frames,
        float sendGain,
        float dryGain,
        float wetReturn = 0.75F) noexcept {
        if (stereoInterleaved == nullptr || frames == 0) return;

        sendGain = std::clamp(sendGain, 0.0F, 1.0F);
        wetReturn = std::clamp(wetReturn, 0.0F, 1.0F);

        if (!enabled_ && sendGain <= 0.0F) {
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
            const float inL = stereoInterleaved[inOffset];
            const float inR = stereoInterleaved[inOffset + 1];

            // 1. Read delayed samples from 1/2 beat delay line
            std::size_t readPos = (writeIndex_ >= delayFrames_)
                ? (writeIndex_ - delayFrames_)
                : (totalBufferFrames + writeIndex_ - delayFrames_);

            const float echoL = buffer_[readPos * 2];
            const float echoR = buffer_[readPos * 2 + 1];

            // 1-pole low-pass damping
            filterState_[0] += dampFactor_ * (echoL - filterState_[0]);
            filterState_[1] += dampFactor_ * (echoR - filterState_[1]);

            const float feedbackL = filterState_[0] * delayFeedback_;
            const float feedbackR = filterState_[1] * delayFeedback_;

            buffer_[writeIndex_ * 2] = inL * sendGain + feedbackL;
            buffer_[writeIndex_ * 2 + 1] = inR * sendGain + feedbackR;
            writeIndex_ = (writeIndex_ + 1) % totalBufferFrames;

            // 2. Feed into 1.5s Schroeder reverb network
            const float monoInput = (inL + inR) * 0.5F * sendGain;
            float revL = 0.0F;
            float revR = 0.0F;
            for (std::size_t i = 0; i < 4; ++i) {
                revL += combL_[i].tick(monoInput);
                revR += combR_[i].tick(monoInput);
            }
            revL *= 0.25F;
            revR *= 0.25F;

            revL = allPassL_[1].tick(allPassL_[0].tick(revL));
            revR = allPassR_[1].tick(allPassR_[0].tick(revR));

            // Combined wet signal: 1/2 beat echo + 1.5s Schroeder reverb
            const float wetL = echoL * 0.70F + revL * 0.45F;
            const float wetR = echoR * 0.70F + revR * 0.45F;

            stereoInterleaved[inOffset] = inL * dryGain + wetL * wetReturn;
            stereoInterleaved[inOffset + 1] = inR * dryGain + wetR * wetReturn;
        }
    }

private:
    double sampleRate_{48000.0};
    std::size_t delayFrames_{18000};
    float delayFeedback_{0.55F};
    float dampFactor_{0.45F};
    bool enabled_{false};

    std::vector<float> buffer_;
    std::size_t writeIndex_{0};
    std::array<float, 2> filterState_{0.0F, 0.0F};

    std::array<CombFilter, 4> combL_{};
    std::array<CombFilter, 4> combR_{};
    std::array<AllPassFilter, 2> allPassL_{};
    std::array<AllPassFilter, 2> allPassR_{};
};

} // namespace lastwave::audio
