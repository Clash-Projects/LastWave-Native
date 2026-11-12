#include "TransientPreservingStretcher.h"

namespace automix {

static constexpr float PI_F = 3.14159265358979323846f;

TransientPreservingStretcher::TransientPreservingStretcher() {
    init(44100.0f, 2);
}

void TransientPreservingStretcher::init(float sampleRate, int channels) {
    sampleRate_ = sampleRate;
    channels_ = channels;
    tempoRatio_ = 1.0f;

    windowSize_ = static_cast<int>(sampleRate_ * 0.025f);
    if (windowSize_ % 2 != 0) windowSize_++;
    hopSize_ = windowSize_ / 4;
    searchRange_ = hopSize_ / 2;

    initWindow();
    reset();
}

void TransientPreservingStretcher::initWindow() {
    hanningWindow_.resize(static_cast<size_t>(windowSize_));
    for (int i = 0; i < windowSize_; ++i) {
        hanningWindow_[static_cast<size_t>(i)] = 0.5f * (1.0f - std::cos(2.0f * PI_F * static_cast<float>(i) / static_cast<float>(windowSize_ - 1)));
    }
}

void TransientPreservingStretcher::reset() {
    inputBuffer_.clear();
    outputBuffer_.clear();
}

void TransientPreservingStretcher::setTempoRatio(float ratio) {
    tempoRatio_ = std::clamp(ratio, 0.94f, 1.06f);
}

int TransientPreservingStretcher::findBestMatchOffset(int targetOffset) {
    int totalInputFrames = static_cast<int>(inputBuffer_.size() / static_cast<size_t>(channels_));
    if (targetOffset < searchRange_ || targetOffset + windowSize_ + searchRange_ >= totalInputFrames) {
        return targetOffset;
    }

    int bestOffset = targetOffset;
    float bestCorr = -1e9f;

    for (int delta = -searchRange_; delta <= searchRange_; delta += 2) {
        int candidateOffset = targetOffset + delta;
        float corr = 0.0f;
        float normA = 0.0f;
        float normB = 0.0f;

        for (int i = 0; i < windowSize_; i += 4) {
            float sA = inputBuffer_[static_cast<size_t>((targetOffset + i) * channels_)];
            float sB = inputBuffer_[static_cast<size_t>((candidateOffset + i) * channels_)];
            corr += sA * sB;
            normA += sA * sA;
            normB += sB * sB;
        }

        float denom = std::sqrt(normA * normB) + 1e-6f;
        float ncc = corr / denom;
        if (ncc > bestCorr) {
            bestCorr = ncc;
            bestOffset = candidateOffset;
        }
    }

    return bestOffset;
}

int TransientPreservingStretcher::process(const float* inPcm, int inFrames, float* outPcm, int maxOutFrames) {
    if (!inPcm || inFrames <= 0 || !outPcm || maxOutFrames <= 0) return 0;

    if (std::abs(tempoRatio_ - 1.0f) < 0.001f) {
        int copyFrames = std::min(inFrames, maxOutFrames);
        std::copy(inPcm, inPcm + (copyFrames * channels_), outPcm);
        return copyFrames;
    }

    size_t prevSize = inputBuffer_.size();
    size_t addSize = static_cast<size_t>(inFrames * channels_);
    inputBuffer_.resize(prevSize + addSize);
    std::copy(inPcm, inPcm + addSize, inputBuffer_.data() + prevSize);

    int inTotalFrames = static_cast<int>(inputBuffer_.size() / static_cast<size_t>(channels_));
    int synthesisHop = hopSize_;
    int analysisHop = static_cast<int>(static_cast<float>(synthesisHop) / tempoRatio_);

    int outFramesGenerated = 0;
    int readCursor = 0;

    while (readCursor + windowSize_ + searchRange_ <= inTotalFrames && outFramesGenerated + windowSize_ <= maxOutFrames) {
        int bestReadOffset = findBestMatchOffset(readCursor);

        for (int i = 0; i < windowSize_; ++i) {
            float w = hanningWindow_[static_cast<size_t>(i)];
            for (int c = 0; c < channels_; ++c) {
                float sample = inputBuffer_[static_cast<size_t>((bestReadOffset + i) * channels_ + c)] * w;
                outPcm[(outFramesGenerated + i) * channels_ + c] += sample;
            }
        }

        outFramesGenerated += synthesisHop;
        readCursor += analysisHop;
    }

    if (readCursor > 0 && readCursor < inTotalFrames) {
        size_t offset = static_cast<size_t>(readCursor * channels_);
        std::vector<float> remaining(inputBuffer_.begin() + offset, inputBuffer_.end());
        inputBuffer_ = std::move(remaining);
    } else if (readCursor >= inTotalFrames) {
        inputBuffer_.clear();
    }

    return outFramesGenerated;
}

} // namespace automix
