#include "../WsolaTimeStretcher.h"
#include <cassert>
#include <cmath>
#include <vector>
#include <iostream>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

int main() {
    using namespace lastwave::audio;
    WsolaTimeStretcher stretcher;
    stretcher.configure(48000.0, 2);

    const std::size_t inFrames = 48000;
    std::vector<float> input(inFrames * 2, 0.0f);
    // 440 Hz sine wave
    for (std::size_t i = 0; i < inFrames; ++i) {
        float sine = std::sin(2.0f * static_cast<float>(M_PI) * 440.0f * static_cast<float>(i) / 48000.0f);
        input[i * 2] = sine;
        input[i * 2 + 1] = sine;
    }

    // Test 1: Time stretch ratio 1.05 (+5% longer)
    stretcher.setTimeRatio(1.05f);
    std::vector<float> output1(static_cast<std::size_t>(inFrames * 1.5) * 2, 0.0f);
    std::size_t totalOut1 = 0;

    const std::size_t chunk = 1024;
    for (std::size_t offset = 0; offset < inFrames; offset += chunk) {
        std::size_t frames = std::min(chunk, inFrames - offset);
        totalOut1 += stretcher.process(&input[offset * 2], frames, &output1[totalOut1 * 2], inFrames * 1.5 - totalOut1);
    }
    totalOut1 += stretcher.flush(&output1[totalOut1 * 2], inFrames * 1.5 - totalOut1);

    std::cout << "Input frames: " << inFrames << ", Output frames (1.05x): " << totalOut1 << "\n";
    // Ratio should be close to 1.05 (within 2%)
    float measuredRatio1 = static_cast<float>(totalOut1) / static_cast<float>(inFrames);
    assert(std::abs(measuredRatio1 - 1.05f) < 0.03f);

    // Pitch check: count positive zero-crossings in output between frames 5000 and 25000
    std::size_t zeroCrossings = 0;
    for (std::size_t i = 5000; i < 25000; ++i) {
        if (output1[i * 2] <= 0.0f && output1[(i + 1) * 2] > 0.0f) {
            zeroCrossings++;
        }
    }
    // Frequency = zeroCrossings / (20000 / 48000) = zeroCrossings * 2.4
    float measuredFreq = static_cast<float>(zeroCrossings) * (48000.0f / 20000.0f);
    std::cout << "Measured frequency: " << measuredFreq << " Hz (Expected ~440 Hz)\n";
    assert(std::abs(measuredFreq - 440.0f) < 5.0f); // Pitch is locked to 440 Hz!

    // Test 2: Recovery ramp to 1.0x
    stretcher.reset();
    stretcher.setTimeRatio(1.08f);
    // Ramp back to 1.0x over 0.25 seconds (12000 frames)
    stretcher.startRecoveryRamp(1.0f, 0.25f);

    std::vector<float> rampOut(24000 * 2, 0.0f);
    std::size_t totalRampOut = 0;
    for (std::size_t offset = 0; offset < 12000; offset += chunk) {
        totalRampOut += stretcher.process(&input[offset * 2], chunk, &rampOut[totalRampOut * 2], 24000 - totalRampOut);
    }

    std::cout << "Ratio after recovery ramp: " << stretcher.currentRatio() << "\n";
    assert(std::abs(stretcher.currentRatio() - 1.0f) < 0.005f);

    std::cout << "WsolaTimeStretcher tests passed!\n";
    return 0;
}
