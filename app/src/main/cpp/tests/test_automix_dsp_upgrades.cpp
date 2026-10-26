#include "../AutoMixEngine.h"
#include <cassert>
#include <cmath>
#include <vector>
#include <iostream>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

int main() {
    using namespace lastwave::audio;
    std::cout << "Testing Automix 5 Mandatory DSP Upgrades...\n";

    AutoMixEngine engine;
    engine.configure(48000.0);
    engine.setEnabled(true);

    // 1. Test Resonant HPF on Deck A (30 -> 900 Hz, Q=1.35)
    // Low frequency test (50 Hz sine wave)
    const std::size_t N = 4800;
    std::vector<float> sine50Hz(N * 2);
    for (std::size_t i = 0; i < N; ++i) {
        float val = std::sin(2.0f * static_cast<float>(M_PI) * 50.0f * static_cast<float>(i) / 48000.0f);
        sine50Hz[i * 2] = val;
        sine50Hz[i * 2 + 1] = val;
    }

    // At progress p = 0.0, 50 Hz should pass through largely intact
    std::vector<float> deckA_p0 = sine50Hz;
    engine.processDeckA(deckA_p0.data(), N, 0.0F);
    float rms_p0 = 0.0f;
    for (std::size_t i = 2000; i < N; ++i) rms_p0 += deckA_p0[i * 2] * deckA_p0[i * 2];
    rms_p0 = std::sqrt(rms_p0 / (N - 2000));

    // At progress p = 1.0, cutoff is 900 Hz, so 50 Hz should be heavily attenuated
    std::vector<float> deckA_p1 = sine50Hz;
    engine.processDeckA(deckA_p1.data(), N, 1.0F);
    float rms_p1 = 0.0f;
    for (std::size_t i = 2000; i < N; ++i) rms_p1 += deckA_p1[i * 2] * deckA_p1[i * 2];
    rms_p1 = std::sqrt(rms_p1 / (N - 2000));

    std::cout << "Deck A 50Hz RMS: at p=0.0: " << rms_p0 << ", at p=1.0 (cutoff 900Hz): " << rms_p1 << "\n";
    assert(rms_p0 > 0.6f);
    assert(rms_p1 < 0.05f); // >20dB attenuation

    // 2. Test Deck B Bass-Swap (-18 dB on <180 Hz before 50%, 0 dB after 50%)
    // 80 Hz sine wave for Deck B
    std::vector<float> sine80Hz(N * 2);
    for (std::size_t i = 0; i < N; ++i) {
        float val = std::sin(2.0f * static_cast<float>(M_PI) * 80.0f * static_cast<float>(i) / 48000.0f);
        sine80Hz[i * 2] = val;
        sine80Hz[i * 2 + 1] = val;
    }

    // At p = 0.25 (before 75% drop): <220 Hz is high-passed (<0.05 RMS)
    std::vector<float> deckB_early = sine80Hz;
    engine.processDeckB(deckB_early.data(), N, 0.25F);
    float rms_b_early = 0.0f;
    for (std::size_t i = 2000; i < N; ++i) rms_b_early += deckB_early[i * 2] * deckB_early[i * 2];
    rms_b_early = std::sqrt(rms_b_early / (N - 2000));

    // At p = 0.80 (after 75% downbeat drop): snapped to full punch 0 dB (~0.707)
    std::vector<float> deckB_late = sine80Hz;
    engine.processDeckB(deckB_late.data(), N, 0.80F);
    float rms_b_late = 0.0f;
    for (std::size_t i = 2000; i < N; ++i) rms_b_late += deckB_late[i * 2] * deckB_late[i * 2];
    rms_b_late = std::sqrt(rms_b_late / (N - 2000));

    std::cout << "Deck B 80Hz Bass RMS: at p=0.25 (220Hz HPF): " << rms_b_early << ", at p=0.80 (75% drop 0dB punch): " << rms_b_late << "\n";
    assert(rms_b_early < 0.05f); // Rule 3: 200 Hz high-passed out
    assert(rms_b_late > 0.55f);   // Rule 3: 0 dB full sub-bass punch after 55% drop

    // 3. Test In-place processDeckBuffer on 16-bit PCM
    engine.triggerFadeNow();
    assert(engine.getState() == AutoMixState::FadingFast);

    std::vector<std::int16_t> pcm16(N * 2);
    for (std::size_t i = 0; i < N; ++i) {
        pcm16[i * 2] = static_cast<std::int16_t>(sine50Hz[i * 2] * 30000.0f);
        pcm16[i * 2 + 1] = static_cast<std::int16_t>(sine50Hz[i * 2 + 1] * 30000.0f);
    }
    bool ok = engine.processDeckBuffer(pcm16.data(), N, 0, 48000, true);
    assert(ok);
    std::cout << "In-place PCM16 processDeckBuffer OK\n";

    // 4. Test Deck B first beat onset detection
    const std::size_t silenceFrames = 4800; // 0.1s silence
    const std::size_t trackFrames = 48000 * 5;
    std::vector<float> testDeckB(trackFrames * 2, 0.0f);
    // Silent intro
    for (std::size_t i = 0; i < silenceFrames * 2; ++i) testDeckB[i] = 0.0001f;
    // Kick drum at 100 Hz with amplitude 0.8 (> -16 dBFS = 0.1585)
    for (std::size_t i = silenceFrames; i < trackFrames; ++i) {
        float kick = std::sin(2.0f * static_cast<float>(M_PI) * 100.0f * static_cast<float>(i - silenceFrames) / 48000.0f) * 0.8f;
        testDeckB[i * 2] = kick;
        testDeckB[i * 2 + 1] = kick;
    }

    AutoMixEngine engine2;
    engine2.configure(48000.0);
    engine2.setEnabled(true);
    engine2.writeDeckBPcm(testDeckB.data(), trackFrames);

    std::int64_t dropBeatMs = engine2.getDeckBDropBeatMs();
    std::int64_t firstBeatMs = engine2.getDeckBFirstBeatMs();
    std::cout << "Detected Deck B drop beat at: " << dropBeatMs << " ms (entry cue: " << firstBeatMs << " ms)\n";
    assert(dropBeatMs >= 90 && dropBeatMs <= 130); // ~100-120ms block window

    // 5. Test startTransition(durationMs) & setTransitionProgress(progress)
    AutoMixEngine engine3;
    engine3.configure(48000.0);
    engine3.setEnabled(true);
    engine3.startTransition(8000);
    assert(engine3.getState() == AutoMixState::FadingAuto);
    assert(engine3.getTransitionProgress() == 0.0f);

    engine3.setTransitionProgress(0.5f);
    assert(std::abs(engine3.getTransitionProgress() - 0.5f) < 0.01f);

    engine3.setTransitionProgress(1.0f);
    assert(engine3.getState() == AutoMixState::PostRestore);
    std::cout << "startTransition & setTransitionProgress OK\n";

    std::cout << "All Automix DSP Upgrade assertions passed successfully!\n";
    return 0;
}
