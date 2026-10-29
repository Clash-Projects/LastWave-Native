#pragma once

#include <vector>
#include <cmath>
#include <cstdint>
#include <algorithm>

namespace automix {

class BiquadSection {
public:
    enum class FilterType { LOWPASS, HIGHPASS };

    BiquadSection() { reset(); }

    void configure(FilterType type, float sampleRate, float cutoffHz, float q = 0.70710678f);
    void reset();

    inline float process(float inSample) {
        float outSample = b0 * inSample + b1 * z1 + b2 * z2 - a1 * y1 - a2 * y2;
        z2 = z1;
        z1 = inSample;
        y2 = y1;
        y1 = outSample;
        return outSample;
    }

private:
    float b0 = 1.0f, b1 = 0.0f, b2 = 0.0f;
    float a1 = 0.0f, a2 = 0.0f;
    float z1 = 0.0f, z2 = 0.0f;
    float y1 = 0.0f, y2 = 0.0f;
};

// 4th-order (24 dB/oct) Linkwitz-Riley filter made of two cascaded 2nd-order Butterworth biquads
class LinkwitzRiley4thOrder {
public:
    void configure(BiquadSection::FilterType type, float sampleRate, float cutoffHz) {
        stage1.configure(type, sampleRate, cutoffHz, 0.70710678f);
        stage2.configure(type, sampleRate, cutoffHz, 0.70710678f);
    }

    void reset() {
        stage1.reset();
        stage2.reset();
    }

    inline float process(float inSample) {
        return stage2.process(stage1.process(inSample));
    }

private:
    BiquadSection stage1;
    BiquadSection stage2;
};

// 3-Band Spectral Splitter with 120 Hz and 3.8 kHz crossover frequencies
class TriBandCrossover {
public:
    TriBandCrossover();
    void configure(float sampleRate, float lowCutoffHz = 120.0f, float highCutoffHz = 3800.0f);
    void reset();

    // Process single channel sample, splitting into Low, Mid, High
    void processSample(float inSample, float& outLow, float& outMid, float& outHigh);

private:
    float sampleRate_ = 44100.0f;
    LinkwitzRiley4thOrder lowPassLowBand_;
    LinkwitzRiley4thOrder highPassLowBand_;
    LinkwitzRiley4thOrder lowPassHighBand_;
    LinkwitzRiley4thOrder highPassHighBand_;
};

// High-level Dual-Deck Spectral Crossover Mixer
class SpectralDeckCrossover {
public:
    SpectralDeckCrossover();
    void init(float sampleRate);
    void reset();

    // Blend coefficient t in [0.0f, 1.0f]
    // Outgoing deck plays from 0 -> 1, Incoming deck plays from 0 -> 1
    // Executes strict 1-bar sub-bass swap at t = 0.5f midpoint
    void processStereo(
        const float* deckAlphaIn,
        const float* deckBetaIn,
        float* mixedOut,
        int numFrames,
        float progress,
        bool applySpectralSweeps
    );

private:
    float sampleRate_ = 44100.0f;
    TriBandCrossover alphaLeft_;
    TriBandCrossover alphaRight_;
    TriBandCrossover betaLeft_;
    TriBandCrossover betaRight_;
};

} // namespace automix
