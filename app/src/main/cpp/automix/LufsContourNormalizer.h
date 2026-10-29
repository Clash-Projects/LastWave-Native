#pragma once

#include <vector>
#include <cmath>
#include <cstdint>
#include <algorithm>

namespace automix {

// EBU R128 K-weighting pre-filter biquad + RLB filter
class KWeightingFilter {
public:
    void init(float sampleRate);
    void reset();
    float process(float sample);

private:
    float b0_pre = 1.0f, b1_pre = 0.0f, b2_pre = 0.0f;
    float a1_pre = 0.0f, a2_pre = 0.0f;
    float z1_pre = 0.0f, z2_pre = 0.0f;

    float b0_rlb = 1.0f, b1_rlb = 0.0f, b2_rlb = 0.0f;
    float a1_rlb = 0.0f, a2_rlb = 0.0f;
    float z1_rlb = 0.0f, z2_rlb = 0.0f;
};

// Loudness normalizer ensuring flat (+-0.5 LU) perceived volume contour during blends
class LufsContourNormalizer {
public:
    LufsContourNormalizer();
    void init(float sampleRate);
    void reset();

    // Measures momentary LUFS of a buffer segment
    float estimateMomentaryLufs(const float* stereoPcm, int numFrames);

    // Computes loudness-balanced gain curve for Outgoing and Incoming decks
    // Target difference is matched within +-0.5 LU
    void computeBalancedGains(
        float outgoingLufs,
        float incomingLufs,
        float progress,
        float& outAlphaGain,
        float& outBetaGain
    );

private:
    float sampleRate_ = 44100.0f;
    KWeightingFilter kFilterLeft_;
    KWeightingFilter kFilterRight_;
};

} // namespace automix
