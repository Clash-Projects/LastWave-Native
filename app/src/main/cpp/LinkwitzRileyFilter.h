#pragma once

#include <cmath>
#include <cstddef>
#include <array>
#include <algorithm>

namespace lastwave::audio {

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

class LinkwitzRileyFilter final {
public:
    struct Biquad final {
        double b0{1.0};
        double b1{0.0};
        double b2{0.0};
        double a1{0.0};
        double a2{0.0};
        std::array<double, 2> z1{0.0, 0.0};
        std::array<double, 2> z2{0.0, 0.0};

        inline void clear() noexcept {
            z1.fill(0.0);
            z2.fill(0.0);
        }

        [[nodiscard]] inline float tick(float input, std::size_t channel) noexcept {
            const double x = static_cast<double>(input);
            const double out = b0 * x + z1[channel];
            z1[channel] = b1 * x - a1 * out + z2[channel];
            z2[channel] = b2 * x - a2 * out;

            // Anti-denormal guard
            if (std::abs(z1[channel]) < 1.0e-20) z1[channel] = 0.0;
            if (std::abs(z2[channel]) < 1.0e-20) z2[channel] = 0.0;
            if (!std::isfinite(out) || !std::isfinite(z1[channel]) || !std::isfinite(z2[channel])) {
                z1[channel] = 0.0;
                z2[channel] = 0.0;
                return 0.0F;
            }
            return static_cast<float>(out);
        }
    };

    LinkwitzRileyFilter() noexcept = default;

    void configure(double sampleRate, double crossoverHz = 180.0) noexcept {
        if (sampleRate <= 0.0) sampleRate = 48000.0;
        sampleRate_ = sampleRate;
        crossoverHz_ = std::clamp(crossoverHz, 20.0, sampleRate * 0.45);

        const double omega = 2.0 * M_PI * crossoverHz_ / sampleRate_;
        const double sinOmega = std::sin(omega);
        const double cosOmega = std::cos(omega);
        // Butterworth Q = 1/sqrt(2)
        const double q = 0.7071067811865475;
        const double alpha = sinOmega / (2.0 * q);

        // 2nd-order Butterworth Low-Pass
        const double a0Lp = 1.0 + alpha;
        const double b0Lp = ((1.0 - cosOmega) * 0.5) / a0Lp;
        const double b1Lp = (1.0 - cosOmega) / a0Lp;
        const double b2Lp = ((1.0 - cosOmega) * 0.5) / a0Lp;
        const double a1Lp = (-2.0 * cosOmega) / a0Lp;
        const double a2Lp = (1.0 - alpha) / a0Lp;

        // 2nd-order Butterworth High-Pass
        const double a0Hp = 1.0 + alpha;
        const double b0Hp = ((1.0 + cosOmega) * 0.5) / a0Hp;
        const double b1Hp = (-(1.0 + cosOmega)) / a0Hp;
        const double b2Hp = ((1.0 + cosOmega) * 0.5) / a0Hp;
        const double a1Hp = (-2.0 * cosOmega) / a0Hp;
        const double a2Hp = (1.0 - alpha) / a0Hp;

        // Cascade two Butterworth filters to create 4th-order Linkwitz-Riley (LR4)
        for (int i = 0; i < 2; ++i) {
            lpStage_[i].b0 = b0Lp;
            lpStage_[i].b1 = b1Lp;
            lpStage_[i].b2 = b2Lp;
            lpStage_[i].a1 = a1Lp;
            lpStage_[i].a2 = a2Lp;

            hpStage_[i].b0 = b0Hp;
            hpStage_[i].b1 = b1Hp;
            hpStage_[i].b2 = b2Hp;
            hpStage_[i].a1 = a1Hp;
            hpStage_[i].a2 = a2Hp;
        }
        reset();
    }

    void reset() noexcept {
        for (int i = 0; i < 2; ++i) {
            lpStage_[i].clear();
            hpStage_[i].clear();
        }
    }

    // Splits interleaved stereo input into low (<crossover) and high (>=crossover) stereo streams
    void processSplit(
        const float* inStereo,
        float* outLowStereo,
        float* outHighStereo,
        std::size_t frames) noexcept {
        if (inStereo == nullptr || frames == 0) return;

        for (std::size_t f = 0; f < frames; ++f) {
            const std::size_t offset = f * 2;
            for (std::size_t ch = 0; ch < 2; ++ch) {
                const float in = inStereo[offset + ch];
                // Low-pass cascade
                float low = lpStage_[0].tick(in, ch);
                low = lpStage_[1].tick(low, ch);

                // High-pass cascade
                float high = hpStage_[0].tick(in, ch);
                high = hpStage_[1].tick(high, ch);

                if (outLowStereo != nullptr) outLowStereo[offset + ch] = low;
                if (outHighStereo != nullptr) outHighStereo[offset + ch] = high;
            }
        }
    }

    // Attenuates low frequencies (<crossoverHz) by lowGain and high frequencies by highGain
    void processBassAttenuate(
        float* stereo,
        std::size_t frames,
        float lowGain,
        float highGain = 1.0F) noexcept {
        if (stereo == nullptr || frames == 0) return;

        for (std::size_t f = 0; f < frames; ++f) {
            const std::size_t offset = f * 2;
            for (std::size_t ch = 0; ch < 2; ++ch) {
                const float in = stereo[offset + ch];
                float low = lpStage_[0].tick(in, ch);
                low = lpStage_[1].tick(low, ch);

                float high = hpStage_[0].tick(in, ch);
                high = hpStage_[1].tick(high, ch);

                stereo[offset + ch] = low * lowGain + high * highGain;
            }
        }
    }

    // In-place High-Pass filtering (Bass Cut) on interleaved stereo
    void processHighPass(float* stereo, std::size_t frames) noexcept {
        if (stereo == nullptr || frames == 0) return;

        for (std::size_t f = 0; f < frames; ++f) {
            const std::size_t offset = f * 2;
            for (std::size_t ch = 0; ch < 2; ++ch) {
                float val = hpStage_[0].tick(stereo[offset + ch], ch);
                val = hpStage_[1].tick(val, ch);
                stereo[offset + ch] = val;
            }
        }
    }

    // In-place Low-Pass filtering on interleaved stereo
    void processLowPass(float* stereo, std::size_t frames) noexcept {
        if (stereo == nullptr || frames == 0) return;

        for (std::size_t f = 0; f < frames; ++f) {
            const std::size_t offset = f * 2;
            for (std::size_t ch = 0; ch < 2; ++ch) {
                float val = lpStage_[0].tick(stereo[offset + ch], ch);
                val = lpStage_[1].tick(val, ch);
                stereo[offset + ch] = val;
            }
        }
    }

    [[nodiscard]] double crossoverFrequency() const noexcept {
        return crossoverHz_;
    }

private:
    double sampleRate_{48000.0};
    double crossoverHz_{160.0};
    std::array<Biquad, 2> lpStage_{};
    std::array<Biquad, 2> hpStage_{};
};

} // namespace lastwave::audio
