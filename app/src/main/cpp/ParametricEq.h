#pragma once

#include <array>
#include <atomic>
#include <cstddef>
#include <cstdint>

namespace lastwave::audio {

/**
 * Parametric equalizer (AutoEQ / manual PEQ).
 *
 * One process-wide configuration is published from the control thread
 * ([publish]) and picked up lock-free by every instance on its next block:
 * the native DSP chain (mixer / converter paths) and the in-place processor
 * the Kotlin sink runs on the direct routes (USB exclusive, internal direct,
 * bit-perfect AudioTrack). Each instance owns its own filter state and
 * rebuilds coefficients when the configuration revision or its sample rate
 * changes. Filter state is kept across coefficient changes so edits do not
 * reset the signal.
 */
class ParametricEq final {
public:
    static constexpr std::size_t kMaxFilters = 20;
    static constexpr std::size_t kMaxChannels = 2;

    enum FilterType : std::int32_t {
        kPeaking = 0,
        kLowShelf = 1,
        kHighShelf = 2,
        kLowPass = 3,
        kHighPass = 4,
    };

    // PCM encodings accepted by processPcm (same values as Kotlin's
    // NativePcmEncoding: 16-bit, packed 24-bit, 32-bit int, float).
    static constexpr std::int32_t kPcm16 = 0;
    static constexpr std::int32_t kPcm24Packed = 1;
    static constexpr std::int32_t kPcm32 = 2;
    static constexpr std::int32_t kPcmFloat = 3;

    /** Control thread. Arrays hold [count] entries; count is clamped to kMaxFilters. */
    static void publish(
        bool enabled,
        float preampDb,
        const std::int32_t* types,
        const float* frequenciesHz,
        const float* gainsDb,
        const float* qs,
        const std::uint8_t* filterEnabled,
        std::size_t count) noexcept;

    /** True when a published, enabled configuration has at least one active filter or a preamp. */
    static bool isEngaged() noexcept;

    void configure(double sampleRate, std::int32_t channels) noexcept;
    void reset() noexcept;

    /**
     * Float interleaved, in place. Returns true when the EQ was applied
     * (false = disabled, nothing touched).
     */
    bool process(float* interleaved, std::int32_t frameCount, std::int32_t channelCount) noexcept;

    /**
     * Integer / float PCM in its own packing, in place (direct routes).
     * 16-bit output gets TPDF dither. Returns true when applied.
     */
    bool processPcm(std::uint8_t* data, std::size_t byteCount, std::int32_t encoding) noexcept;

    /** Magnitude in dB of the published configuration at [frequency] (preamp included). */
    static double responseDb(double sampleRate, double frequency) noexcept;

private:
    struct Coefficients final {
        double b0{1.0};
        double b1{0.0};
        double b2{0.0};
        double a1{0.0};
        double a2{0.0};
    };

    struct Spec final {
        std::int32_t type{kPeaking};
        double frequency{1000.0};
        double gainDb{0.0};
        double q{0.707};
    };

    static Coefficients design(const Spec& spec, double sampleRate) noexcept;
    static double magnitude(const Coefficients& c, double sampleRate, double frequency) noexcept;
    void syncConfiguration() noexcept;

    inline float tick(float input, std::size_t channel) noexcept {
        double value = static_cast<double>(input);
        for (std::size_t i = 0; i < activeCount_; ++i) {
            const Coefficients& c = coefficients_[i];
            double& z1 = z1_[i][channel];
            double& z2 = z2_[i][channel];
            const double out = c.b0 * value + z1;
            z1 = c.b1 * value - c.a1 * out + z2;
            z2 = c.b2 * value - c.a2 * out;
            value = out;
        }
        return static_cast<float>(value * preampGain_);
    }

    double sampleRate_{48000.0};
    std::int32_t channels_{2};
    std::uint32_t appliedRevision_{0xFFFFFFFFU};
    double appliedSampleRate_{0.0};
    bool enabled_{false};
    double preampGain_{1.0};
    std::size_t activeCount_{0};
    std::array<Coefficients, kMaxFilters> coefficients_{};
    std::array<std::array<double, kMaxChannels>, kMaxFilters> z1_{};
    std::array<std::array<double, kMaxChannels>, kMaxFilters> z2_{};
    std::array<std::int32_t, kMaxFilters> slotSource_{
        -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1};
    std::uint32_t ditherState_{0x12345678U};
};

}  // namespace lastwave::audio
