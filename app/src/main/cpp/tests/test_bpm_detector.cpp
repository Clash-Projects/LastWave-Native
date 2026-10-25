#include "../BpmPhaseDetector.h"
#include <cassert>
#include <cmath>
#include <vector>
#include <iostream>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

int main() {
    using namespace lastwave::audio;
    BpmPhaseDetector detector;
    detector.configure(48000.0);

    const double sampleRate = 48000.0;
    const double targetBpm = 128.0;
    const std::size_t beatInterval = static_cast<std::size_t>((60.0 / targetBpm) * sampleRate); // 22500 samples
    const std::size_t totalSeconds = 10;
    const std::size_t totalFrames = static_cast<std::size_t>(sampleRate * totalSeconds);

    std::vector<float> stereoAudio(totalFrames * 2, 0.0f);

    // Synthesize kick drums: 80 Hz sine burst of 1500 samples every beatInterval
    for (std::size_t beatStart = 0; beatStart < totalFrames; beatStart += beatInterval) {
        for (std::size_t i = 0; i < 2000 && (beatStart + i) < totalFrames; ++i) {
            float env = 1.0f - static_cast<float>(i) / 2000.0f;
            float kick = std::sin(2.0f * static_cast<float>(M_PI) * 80.0f * static_cast<float>(i) / 48000.0f) * env * env;
            stereoAudio[(beatStart + i) * 2] += kick;
            stereoAudio[(beatStart + i) * 2 + 1] += kick;
        }
    }

    // Add continuous high frequency melody / noise (1500 Hz) that the bandpass should reject
    for (std::size_t i = 0; i < totalFrames; ++i) {
        float melody = std::sin(2.0f * static_cast<float>(M_PI) * 1500.0f * static_cast<float>(i) / 48000.0f) * 0.3f;
        stereoAudio[i * 2] += melody;
        stereoAudio[i * 2 + 1] += melody;
    }

    // Process in 1024-frame chunks
    const std::size_t chunkSize = 1024;
    for (std::size_t offset = 0; offset < totalFrames; offset += chunkSize) {
        const std::size_t frames = std::min(chunkSize, totalFrames - offset);
        detector.process(&stereoAudio[offset * 2], frames);
    }

    auto result = detector.getResult();
    std::cout << "Detected BPM: " << result.bpm << ", Confidence: " << result.confidence
              << ", Last downbeat frame: " << result.lastDownbeatFrame << "\n";

    assert(std::abs(result.bpm - targetBpm) <= 2.5f);
    assert(result.confidence > 0.5f);

    std::cout << "BpmPhaseDetector tests passed!\n";
    return 0;
}
