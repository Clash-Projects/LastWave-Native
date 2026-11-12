#include "LufsContourNormalizer.h"

namespace automix {

static constexpr float PI_F = 3.14159265358979323846f;

void KWeightingFilter::init(float sampleRate) {
    reset();
    if (sampleRate <= 0.0f) sampleRate = 44100.0f;

    float f0 = 1500.0f;
    float gainDb = 4.0f;
    float A = std::pow(10.0f, gainDb / 40.0f);
    float w0 = 2.0f * PI_F * f0 / sampleRate;
    float cosw0 = std::cos(w0);
    float sinw0 = std::sin(w0);
    float alpha = sinw0 / 2.0f * std::sqrt((A + 1.0f / A) * (1.0f / 0.7071f - 1.0f) + 2.0f);

    float a0 = (A + 1.0f) - (A - 1.0f) * cosw0 + 2.0f * std::sqrt(A) * alpha;
    b0_pre = (A * ((A + 1.0f) + (A - 1.0f) * cosw0 + 2.0f * std::sqrt(A) * alpha)) / a0;
    b1_pre = (-2.0f * A * ((A - 1.0f) + (A + 1.0f) * cosw0)) / a0;
    b2_pre = (A * ((A + 1.0f) + (A - 1.0f) * cosw0 - 2.0f * std::sqrt(A) * alpha)) / a0;
    a1_pre = (2.0f * ((A - 1.0f) - (A + 1.0f) * cosw0)) / a0;
    a2_pre = ((A + 1.0f) - (A - 1.0f) * cosw0 - 2.0f * std::sqrt(A) * alpha) / a0;

    float f_rlb = 38.0f;
    float w_rlb = 2.0f * PI_F * f_rlb / sampleRate;
    float cos_rlb = std::cos(w_rlb);
    float alpha_rlb = std::sin(w_rlb) / (2.0f * 0.5f);

    float a0_rlb = 1.0f + alpha_rlb;
    b0_rlb = ((1.0f + cos_rlb) * 0.5f) / a0_rlb;
    b1_rlb = (-(1.0f + cos_rlb)) / a0_rlb;
    b2_rlb = ((1.0f + cos_rlb) * 0.5f) / a0_rlb;
    a1_rlb = (-2.0f * cos_rlb) / a0_rlb;
    a2_rlb = (1.0f - alpha_rlb) / a0_rlb;
}

void KWeightingFilter::reset() {
    z1_pre = z2_pre = 0.0f;
    z1_rlb = z2_rlb = 0.0f;
}

float KWeightingFilter::process(float sample) {
    float y_pre = b0_pre * sample + z1_pre;
    z1_pre = b1_pre * sample - a1_pre * y_pre + z2_pre;
    z2_pre = b2_pre * sample - a2_pre * y_pre;

    float y_rlb = b0_rlb * y_pre + z1_rlb;
    z1_rlb = b1_rlb * y_pre - a1_rlb * y_rlb + z2_rlb;
    z2_rlb = b2_rlb * y_pre - a2_rlb * y_rlb;

    return y_rlb;
}

LufsContourNormalizer::LufsContourNormalizer() {
    init(44100.0f);
}

void LufsContourNormalizer::init(float sampleRate) {
    sampleRate_ = sampleRate;
    kFilterLeft_.init(sampleRate_);
    kFilterRight_.init(sampleRate_);
}

void LufsContourNormalizer::reset() {
    kFilterLeft_.reset();
    kFilterRight_.reset();
}

float LufsContourNormalizer::estimateMomentaryLufs(const float* stereoPcm, int numFrames) {
    if (!stereoPcm || numFrames <= 0) return -70.0f;

    double sumSqL = 0.0;
    double sumSqR = 0.0;

    for (int i = 0; i < numFrames; ++i) {
        float fL = kFilterLeft_.process(stereoPcm[i * 2]);
        float fR = kFilterRight_.process(stereoPcm[i * 2 + 1]);
        sumSqL += static_cast<double>(fL) * static_cast<double>(fL);
        sumSqR += static_cast<double>(fR) * static_cast<double>(fR);
    }

    double meanSqL = sumSqL / static_cast<double>(numFrames);
    double meanSqR = sumSqR / static_cast<double>(numFrames);
    double totalEnergy = meanSqL + meanSqR;

    if (totalEnergy <= 1e-12) return -70.0f;

    double lufs = -0.691 + 10.0 * std::log10(totalEnergy);
    return static_cast<float>(std::clamp(lufs, -70.0, 0.0));
}

void LufsContourNormalizer::computeBalancedGains(
    float outgoingLufs,
    float incomingLufs,
    float progress,
    float& outAlphaGain,
    float& outBetaGain
) {
    float t = std::clamp(progress, 0.0f, 1.0f);

    float deltaLufs = incomingLufs - outgoingLufs;
    deltaLufs = std::clamp(deltaLufs, -4.0f, 4.0f);

    float betaTrimGain = std::pow(10.0f, -deltaLufs / 20.0f);

    float sAlpha = std::cos(t * PI_F * 0.5f);
    float sBeta = std::sin(t * PI_F * 0.5f);

    outAlphaGain = sAlpha;
    outBetaGain = sBeta * betaTrimGain;
}

} // namespace automix
