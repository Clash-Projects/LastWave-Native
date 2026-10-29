#include "LinkwitzRileyCrossover.h"

namespace automix {

static constexpr float PI_F = 3.14159265358979323846f;

void BiquadSection::reset() {
    z1 = z2 = y1 = y2 = 0.0f;
}

void BiquadSection::configure(FilterType type, float sampleRate, float cutoffHz, float q) {
    reset();
    if (sampleRate <= 0.0f) sampleRate = 44100.0f;
    cutoffHz = std::clamp(cutoffHz, 10.0f, (sampleRate * 0.5f) - 50.0f);

    float w0 = 2.0f * PI_F * cutoffHz / sampleRate;
    float alpha = std::sin(w0) / (2.0f * q);
    float cosw0 = std::cos(w0);

    float a0 = 1.0f + alpha;

    if (type == FilterType::LOWPASS) {
        b0 = ((1.0f - cosw0) * 0.5f) / a0;
        b1 = (1.0f - cosw0) / a0;
        b2 = ((1.0f - cosw0) * 0.5f) / a0;
    } else { // HIGHPASS
        b0 = ((1.0f + cosw0) * 0.5f) / a0;
        b1 = (-(1.0f + cosw0)) / a0;
        b2 = ((1.0f + cosw0) * 0.5f) / a0;
    }

    a1 = (-2.0f * cosw0) / a0;
    a2 = (1.0f - alpha) / a0;
}

TriBandCrossover::TriBandCrossover() {
    configure(44100.0f);
}

void TriBandCrossover::configure(float sampleRate, float lowCutoffHz, float highCutoffHz) {
    sampleRate_ = sampleRate;
    lowPassLowBand_.configure(BiquadSection::FilterType::LOWPASS, sampleRate_, lowCutoffHz);
    highPassLowBand_.configure(BiquadSection::FilterType::HIGHPASS, sampleRate_, lowCutoffHz);

    lowPassHighBand_.configure(BiquadSection::FilterType::LOWPASS, sampleRate_, highCutoffHz);
    highPassHighBand_.configure(BiquadSection::FilterType::HIGHPASS, sampleRate_, highCutoffHz);
}

void TriBandCrossover::reset() {
    lowPassLowBand_.reset();
    highPassLowBand_.reset();
    lowPassHighBand_.reset();
    highPassHighBand_.reset();
}

void TriBandCrossover::processSample(float inSample, float& outLow, float& outMid, float& outHigh) {
    // Stage 1: split into low (< 120Hz) and upper (>= 120Hz)
    outLow = lowPassLowBand_.process(inSample);
    float upperBand = highPassLowBand_.process(inSample);

    // Stage 2: split upper band into mid (120Hz - 3.8kHz) and high (> 3.8kHz)
    outMid = lowPassHighBand_.process(upperBand);
    outHigh = highPassHighBand_.process(upperBand);
}

SpectralDeckCrossover::SpectralDeckCrossover() {
    init(44100.0f);
}

void SpectralDeckCrossover::init(float sampleRate) {
    sampleRate_ = sampleRate;
    alphaLeft_.configure(sampleRate_);
    alphaRight_.configure(sampleRate_);
    betaLeft_.configure(sampleRate_);
    betaRight_.configure(sampleRate_);
}

void SpectralDeckCrossover::reset() {
    alphaLeft_.reset();
    alphaRight_.reset();
    betaLeft_.reset();
    betaRight_.reset();
}

void SpectralDeckCrossover::processStereo(
    const float* deckAlphaIn,
    const float* deckBetaIn,
    float* mixedOut,
    int numFrames,
    float progress,
    bool applySpectralSweeps
) {
    float t = std::clamp(progress, 0.0f, 1.0f);

    float alphaLowGain, betaLowGain;
    float alphaMidGain, betaMidGain;
    float alphaHighGain, betaHighGain;

    if (applySpectralSweeps) {
        if (t < 0.45f) {
            alphaLowGain = 1.0f;
            betaLowGain = 0.0f;
        } else if (t > 0.55f) {
            alphaLowGain = 0.0f;
            betaLowGain = 1.0f;
        } else {
            float normMid = (t - 0.45f) / 0.10f;
            alphaLowGain = std::cos(normMid * PI_F * 0.5f);
            betaLowGain = std::sin(normMid * PI_F * 0.5f);
        }

        alphaMidGain = std::cos(t * PI_F * 0.5f);
        betaMidGain = std::sin(t * PI_F * 0.5f);

        alphaHighGain = std::clamp(1.0f - (t * 1.1f), 0.0f, 1.0f);
        betaHighGain = std::clamp(t * 1.1f, 0.0f, 1.0f);
    } else {
        float gA = std::cos(t * PI_F * 0.5f);
        float gB = std::sin(t * PI_F * 0.5f);
        alphaLowGain = alphaMidGain = alphaHighGain = gA;
        betaLowGain = betaMidGain = betaHighGain = gB;
    }

    for (int i = 0; i < numFrames; ++i) {
        int idx = i * 2;
        float aL = deckAlphaIn ? deckAlphaIn[idx] : 0.0f;
        float aR = deckAlphaIn ? deckAlphaIn[idx + 1] : 0.0f;
        float bL = deckBetaIn ? deckBetaIn[idx] : 0.0f;
        float bR = deckBetaIn ? deckBetaIn[idx + 1] : 0.0f;

        float aLowL, aMidL, aHighL;
        float aLowR, aMidR, aHighR;
        float bLowL, bMidL, bHighL;
        float bLowR, bMidR, bHighR;

        alphaLeft_.processSample(aL, aLowL, aMidL, aHighL);
        alphaRight_.processSample(aR, aLowR, aMidR, aHighR);
        betaLeft_.processSample(bL, bLowL, bMidL, bHighL);
        betaRight_.processSample(bR, bLowR, bMidR, bHighR);

        float outL = (aLowL * alphaLowGain + aMidL * alphaMidGain + aHighL * alphaHighGain) +
                     (bLowL * betaLowGain + bMidL * betaMidGain + bHighL * betaHighGain);

        float outR = (aLowR * alphaLowGain + aMidR * alphaMidGain + aHighR * alphaHighGain) +
                     (bLowR * betaLowGain + bMidR * betaMidGain + bHighR * betaHighGain);

        mixedOut[idx] = std::tanh(outL);
        mixedOut[idx + 1] = std::tanh(outR);
    }
}

} // namespace automix
