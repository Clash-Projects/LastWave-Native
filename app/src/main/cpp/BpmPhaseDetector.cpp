#include "BpmPhaseDetector.h"
#include <cmath>
#include <algorithm>

namespace lastwave::audio {

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

BpmPhaseDetector::BpmPhaseDetector() noexcept
    : envelopeBuffer_(kEnvelopeBufferSize, 0.0F) {
    configure(48000.0);
}

void BpmPhaseDetector::configure(double sampleRate) noexcept {
    if (sampleRate <= 0.0) sampleRate = 48000.0;
    sampleRate_ = sampleRate;

    // Target decimation rate ~200 Hz
    decimationFactor_ = static_cast<std::size_t>(std::max(1.0, std::round(sampleRate / 200.0)));

    // Design Butterworth Bandpass centered at 100 Hz, Q = 1.0 (60-140 Hz passband)
    const double centerFreqHz = 100.0;
    const double q = 1.0;
    const double omega = 2.0 * M_PI * centerFreqHz / sampleRate_;
    const double alpha = std::sin(omega) / (2.0 * q);

    const double a0 = 1.0 + alpha;
    b0_ = alpha / a0;
    b1_ = 0.0;
    b2_ = -alpha / a0;
    a1_ = (-2.0 * std::cos(omega)) / a0;
    a2_ = (1.0 - alpha) / a0;

    reset();
}

void BpmPhaseDetector::reset() noexcept {
    z1_ = 0.0;
    z2_ = 0.0;
    decimationAccumulator_ = 0.0F;
    decimationCounter_ = 0;
    totalFramesProcessed_ = 0;
    envelopeWritePos_ = 0;
    envelopeFull_ = false;
    previousFiltered_ = 0.0F;
    lastPeakFrame_ = 0;
    currentResult_ = BeatDetectionResult{120.0F, 0.0F, 0};
    std::fill(envelopeBuffer_.begin(), envelopeBuffer_.end(), 0.0F);
}

void BpmPhaseDetector::process(const float* stereoInterleaved, std::size_t frames) noexcept {
    if (stereoInterleaved == nullptr || frames == 0) return;

    for (std::size_t i = 0; i < frames; ++i) {
        // Sum to mono
        const float mono = (stereoInterleaved[i * 2] + stereoInterleaved[i * 2 + 1]) * 0.5F;

        // Bandpass filter tick (Direct Form II Transposed)
        const double x = static_cast<double>(mono);
        const double out = b0_ * x + z1_;
        z1_ = b1_ * x - a1_ * out + z2_;
        z2_ = b2_ * x - a2_ * out;

        // Anti-denormal
        if (std::abs(z1_) < 1.0e-20) z1_ = 0.0;
        if (std::abs(z2_) < 1.0e-20) z2_ = 0.0;

        const float filtered = static_cast<float>(out);

        // Half-wave rectify
        const float rectified = std::max(0.0F, filtered);

        // Onset tracking
        if (rectified > onsetThreshold_ && rectified > previousFiltered_) {
            lastPeakFrame_ = totalFramesProcessed_ + i;
        }
        previousFiltered_ = rectified;

        // Accumulate into decimated envelope
        decimationAccumulator_ += rectified;
        decimationCounter_++;

        if (decimationCounter_ >= decimationFactor_) {
            envelopeBuffer_[envelopeWritePos_] = decimationAccumulator_ / static_cast<float>(decimationFactor_);
            envelopeWritePos_ = (envelopeWritePos_ + 1) % kEnvelopeBufferSize;
            if (envelopeWritePos_ == 0) {
                envelopeFull_ = true;
            }
            decimationAccumulator_ = 0.0F;
            decimationCounter_ = 0;
        }
    }

    totalFramesProcessed_ += frames;

    // Run autocorrelation update periodically (e.g. once every ~0.5s of audio)
    if (totalFramesProcessed_ >= sampleRate_ * 1.5) {
        updateAutocorrelation();
    }
}

void BpmPhaseDetector::updateAutocorrelation() noexcept {
    const std::size_t numBins = envelopeFull_ ? kEnvelopeBufferSize : envelopeWritePos_;
    if (numBins < kMaxLag + 50) return;

    // Unroll linear view of envelope
    std::vector<float> linearEnv(numBins);
    if (envelopeFull_) {
        for (std::size_t i = 0; i < numBins; ++i) {
            linearEnv[i] = envelopeBuffer_[(envelopeWritePos_ + i) % kEnvelopeBufferSize];
        }
    } else {
        std::copy(envelopeBuffer_.begin(), envelopeBuffer_.begin() + numBins, linearEnv.begin());
    }

    // Mean-center envelope
    double sum = 0.0;
    for (float v : linearEnv) sum += v;
    const float mean = static_cast<float>(sum / numBins);
    for (float& v : linearEnv) v = std::max(0.0F, v - mean);

    const double envRate = sampleRate_ / static_cast<double>(decimationFactor_);
    const std::size_t minLag = static_cast<std::size_t>(std::round((60.0 / 185.0) * envRate));
    const std::size_t maxLag = static_cast<std::size_t>(std::round((60.0 / 55.0) * envRate));

    float maxCorr = 0.0F;
    std::size_t bestLag = minLag;
    std::vector<float> corrValues(maxLag + 2, 0.0F);

    const std::size_t windowSize = numBins - maxLag;
    if (windowSize < 100) return;

    for (std::size_t lag = minLag; lag <= maxLag; ++lag) {
        double r = 0.0;
        for (std::size_t n = 0; n < windowSize; ++n) {
            r += linearEnv[n] * linearEnv[n + lag];
        }
        const float corr = static_cast<float>(r);
        corrValues[lag] = corr;
        if (corr > maxCorr) {
            maxCorr = corr;
            bestLag = lag;
        }
    }

    if (maxCorr <= 1.0e-6F) return;

    // Parabolic interpolation around peak
    double refinedLag = static_cast<double>(bestLag);
    if (bestLag > minLag && bestLag < maxLag) {
        const double y1 = corrValues[bestLag - 1];
        const double y2 = corrValues[bestLag];
        const double y3 = corrValues[bestLag + 1];
        const double denom = (y1 - 2.0 * y2 + y3);
        if (std::abs(denom) > 1.0e-9) {
            refinedLag += (y1 - y3) / (2.0 * denom);
        }
    }

    const float detectedBpm = static_cast<float>((60.0 * envRate) / refinedLag);

    // Compute confidence: ratio of peak to mean correlation
    double corrSum = 0.0;
    std::size_t count = 0;
    for (std::size_t l = minLag; l <= maxLag; ++l) {
        corrSum += corrValues[l];
        count++;
    }
    const float avgCorr = (count > 0) ? static_cast<float>(corrSum / count) : 1.0F;
    const float confidence = std::clamp((maxCorr - avgCorr) / (maxCorr + 1.0e-5F), 0.0F, 1.0F);

    currentResult_.bpm = std::clamp(detectedBpm, 50.0F, 220.0F);
    currentResult_.confidence = confidence;
    currentResult_.lastDownbeatFrame = lastPeakFrame_;
}

BeatDetectionResult BpmPhaseDetector::getResult() const noexcept {
    return currentResult_;
}

} // namespace lastwave::audio
