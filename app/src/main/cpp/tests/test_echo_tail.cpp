#include "../EchoTailProcessor.h"
#include <cassert>
#include <cmath>
#include <vector>
#include <iostream>

int main() {
    using namespace lastwave::audio;
    EchoTailProcessor echo;
    echo.configure(48000.0, 120.0f); // 120 BPM, delay ~375ms (18000 samples)
    echo.setEnabled(true);

    const std::size_t totalFrames = 48000 * 3; // 3 seconds
    std::vector<float> audio(totalFrames * 2, 0.0f);

    // Single impulse at frame 0
    audio[0] = 1.0f;
    audio[1] = 1.0f;

    // Process: frame 0 has sendGain = 1.0, dryGain = 1.0; rest has sendGain = 0.0, dryGain = 0.0
    echo.process(audio.data(), 1, 1.0f, 1.0f);
    echo.process(&audio[2], totalFrames - 1, 0.0f, 0.0f);

    // Reflection 1: check amplitude around delay interval (18000 frames)
    const std::size_t delaySamples = echo.delayFrames();
    std::cout << "Delay frames: " << delaySamples << " (~" << (delaySamples / 48.0f) << " ms)\n";
    assert(delaySamples >= 9600 && delaySamples <= 24000);

    float firstEchoPeak = 0.0f;
    for (std::size_t i = delaySamples - 10; i <= delaySamples + 10; ++i) {
        firstEchoPeak = std::max(firstEchoPeak, std::abs(audio[i * 2]));
    }
    std::cout << "First echo peak: " << firstEchoPeak << "\n";
    assert(firstEchoPeak > 0.2f && firstEchoPeak < 0.9f);

    // At t = 1.2s (~57600 frames), echo tail should have decayed significantly (< 0.05)
    float tailAt1_2s = 0.0f;
    for (std::size_t i = 57600; i < 57600 + delaySamples; ++i) {
        if (i < totalFrames) {
            tailAt1_2s = std::max(tailAt1_2s, std::abs(audio[i * 2]));
        }
    }
    std::cout << "Peak at ~1.2s: " << tailAt1_2s << "\n";
    assert(tailAt1_2s < 0.05f);

    // At t = 2.0s, echo tail should be nearly silent (< 0.005)
    float tailAt2s = 0.0f;
    for (std::size_t i = 48000 * 2; i < totalFrames; ++i) {
        tailAt2s = std::max(tailAt2s, std::abs(audio[i * 2]));
    }
    std::cout << "Peak after 2.0s: " << tailAt2s << "\n";
    assert(tailAt2s < 0.005f);

    std::cout << "EchoTailProcessor tests passed!\n";
    return 0;
}
