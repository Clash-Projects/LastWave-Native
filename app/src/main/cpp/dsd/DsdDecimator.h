#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <vector>

namespace lastwave::dsd {

/**
 * Linear-phase Kaiser-windowed FIR decimator converting 1-bit delta-sigma
 * bitstreams (DSD64/128/256/512) into 32-bit float PCM.
 *
 * Uses per-byte lookup tables (256 entries per byte of filter aperture) so the
 * hot loop reduces to table accumulation per output frame with zero per-bit
 * inner loops.
 */
class DsdDecimator final {
public:
    DsdDecimator(std::int32_t channelCount, std::int32_t decimationFactor, bool lsbFirst);

    void reset() noexcept;

    /**
     * Feeds interleaved DSD bytes (for DSF after de-blocking or DFF natively):
     * for [frameBytes] input byte-frames (each containing [channelCount] bytes),
     * writes interleaved Float32 PCM frames into [outPcm] and returns the number
     * of output frames produced.
     *
     * Note: [input] size must be at least `frameBytes * channelCount`.
     * [outPcm] must have capacity `>= maxOutputFrames(frameBytes) * channelCount`.
     */
    std::size_t processInterleaved(
        const std::uint8_t* input,
        std::size_t frameBytes,
        float* outPcm) noexcept;

    /**
     * Convenience helper for planar channel blocks (DSF storage layout):
     * [channelPlanes] has `channelCount` pointers, each advancing `blockBytes`.
     */
    std::size_t processPlanar(
        const std::uint8_t* const* channelPlanes,
        std::size_t blockBytes,
        float* outPcm) noexcept;

    [[nodiscard]] std::size_t maxOutputFrames(std::size_t inputByteFrames) const noexcept {
        const std::size_t stepBytes = static_cast<std::size_t>(decimationFactor_ / 8);
        if (stepBytes == 0) return 0;
        return (inputByteFrames + bytePhase_ + stepBytes - 1) / stepBytes;
    }

    [[nodiscard]] std::int32_t channelCount() const noexcept { return channelCount_; }
    [[nodiscard]] std::int32_t decimationFactor() const noexcept { return decimationFactor_; }

private:
    static constexpr std::size_t kLutGroupsPer16x = 16;  // 16 bytes = 128 taps at DSD64
    static constexpr float kSacdGain = 1.65f;            // Nominal +4.35 dB compensating SACD 50% mod depth

    std::int32_t channelCount_{2};
    std::int32_t decimationFactor_{16};
    bool lsbFirst_{true};
    std::size_t stepBytes_{2};
    std::size_t filterBytes_{16};

    // Row-major [filterBytes_][256]: LUT contributions indexed chronologically
    // from oldest byte (group 0) to newest byte (filterBytes_ - 1).
    std::vector<float> lut_;
    std::vector<std::uint8_t> history_;  // Per-channel circular ring [channel][filterBytes_]
    std::size_t ringWritePos_{0};
    std::size_t bytePhase_{0};

    void buildFilterTables();
    float convolveChannel(std::size_t ch) const noexcept;
};

}  // namespace lastwave::dsd
