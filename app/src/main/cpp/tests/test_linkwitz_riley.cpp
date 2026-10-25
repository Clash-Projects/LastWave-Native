#include "../LinkwitzRileyFilter.h"
#include <cassert>
#include <cmath>
#include <vector>
#include <iostream>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

void testFrequency(double freqHz, double expectedHpGain, double expectedLpGain) {
    using namespace lastwave::audio;
    LinkwitzRileyFilter filter;
    filter.configure(48000.0, 160.0);

    const std::size_t frames = 48000;
    std::vector<float> input(frames * 2, 0.0f);
    for (std::size_t i = 0; i < frames; ++i) {
        float sine = std::sin(2.0f * static_cast<float>(M_PI) * static_cast<float>(freqHz) * static_cast<float>(i) / 48000.0f);
        input[i * 2] = sine;
        input[i * 2 + 1] = sine;
    }

    std::vector<float> low(frames * 2, 0.0f);
    std::vector<float> high(frames * 2, 0.0f);
    filter.processSplit(input.data(), low.data(), high.data(), frames);

    // Measure peak amplitudes after filter settles (steady state from frame 20000 to 40000)
    float peakSum = 0.0f;
    float peakLow = 0.0f;
    float peakHigh = 0.0f;
    for (std::size_t i = 20000; i < 40000; ++i) {
        float sum = low[i * 2] + high[i * 2];
        peakSum = std::max(peakSum, std::abs(sum));
        peakLow = std::max(peakLow, std::abs(low[i * 2]));
        peakHigh = std::max(peakHigh, std::abs(high[i * 2]));
    }

    // Sum magnitude must be ~1.0 (flat response across spectrum)
    assert(std::abs(peakSum - 1.0f) < 0.05f);

    if (expectedLpGain >= 0.0) {
        assert(std::abs(peakLow - expectedLpGain) < 0.1f);
    }
    if (expectedHpGain >= 0.0) {
        assert(std::abs(peakHigh - expectedHpGain) < 0.1f);
    }
}

int main() {
    using namespace lastwave::audio;

    // 1. Deep Bass (50 Hz): Low should be ~1.0, High should be strongly attenuated (< 0.05)
    testFrequency(50.0, 0.02, 0.99);

    // 2. High Frequencies (1000 Hz): High should be ~1.0, Low strongly attenuated (< 0.05)
    testFrequency(1000.0, 0.99, 0.02);

    // 3. Crossover point (160 Hz): Both should be at -6 dB (approx 0.5 amplitude)
    testFrequency(160.0, 0.50, 0.50);

    // 4. Test in-place HighPass on 50 Hz vs 1000 Hz
    {
        LinkwitzRileyFilter filter;
        filter.configure(48000.0, 160.0);
        const std::size_t frames = 24000;
        std::vector<float> bass(frames * 2, 0.0f);
        for (std::size_t i = 0; i < frames; ++i) {
            float s = std::sin(2.0f * static_cast<float>(M_PI) * 50.0f * static_cast<float>(i) / 48000.0f);
            bass[i * 2] = s;
            bass[i * 2 + 1] = s;
        }
        filter.processHighPass(bass.data(), frames);
        float peakAfterHp = 0.0f;
        for (std::size_t i = 12000; i < frames; ++i) {
            peakAfterHp = std::max(peakAfterHp, std::abs(bass[i * 2]));
        }
        assert(peakAfterHp < 0.05f); // 50 Hz is cut by > 26 dB!
    }

    std::cout << "LinkwitzRileyFilter tests passed!\n";
    return 0;
}
