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
    AutoMixEngine engine;
    engine.configure(48000.0);
    engine.setEnabled(true);
    engine.setBarMode(8); // 8-bar default

    assert(engine.getState() == AutoMixState::Idle);

    // 1. Prepare Deck B track with 0.1s silence (< -45 dBFS = 0.0056) then 128 BPM music
    const std::size_t sampleRate = 48000;
    const std::size_t silenceFrames = 4800; // 0.1s
    const std::size_t musicFrames = 48000 * 20; // 20s
    std::vector<float> deckBTrack((silenceFrames + musicFrames) * 2, 0.0f);

    // Extremely low noise below -45 dBFS (e.g. 0.0001) in silence portion
    for (std::size_t i = 0; i < silenceFrames * 2; ++i) {
        deckBTrack[i] = 0.0001f;
    }
    // Music portion: 128 BPM
    for (std::size_t i = 0; i < musicFrames; ++i) {
        float kick = std::sin(2.0f * static_cast<float>(M_PI) * 80.0f * static_cast<float>(i) / 48000.0f);
        deckBTrack[(silenceFrames + i) * 2] = kick * 0.8f;
        deckBTrack[(silenceFrames + i) * 2 + 1] = kick * 0.8f;
    }

    // Write to Deck B
    std::size_t written = engine.writeDeckBPcm(deckBTrack.data(), silenceFrames + musicFrames);
    assert(written > 0);
    std::cout << "Written Deck B frames: " << written << "\n";

    // Silence trim check: Deck B should have trimmed leading frames below -45 dBFS
    assert(engine.getDeckBLeadingTrimFrames() >= silenceFrames - 50);
    std::cout << "Trimmed leading silence: " << engine.getDeckBLeadingTrimFrames() << " frames\n";

    // 2. Deck A playing: generate 10s of audio, with an outro energy drop at t = 5s
    const std::size_t deckAFrames = sampleRate * 10;
    std::vector<float> deckA(deckAFrames * 2, 0.0f);
    for (std::size_t i = 0; i < deckAFrames; ++i) {
        float gain = (i < sampleRate * 5) ? 0.8f : 0.15f; // Energy drop at 5s
        float s = std::sin(2.0f * static_cast<float>(M_PI) * 120.0f * static_cast<float>(i) / 48000.0f) * gain;
        deckA[i * 2] = s;
        deckA[i * 2 + 1] = s;
    }

    // Process first 4.5 seconds (baseline energy)
    std::vector<float> out(48000 * 2, 0.0f);
    for (std::size_t offset = 0; offset < sampleRate * 4; offset += 1024) {
        engine.process(&deckA[offset * 2], 1024);
    }
    assert(engine.getState() == AutoMixState::MonitoringOutro || engine.getState() == AutoMixState::Idle);

    // 3. Test Fade Now trigger (fast 4-bar blend)
    engine.triggerFadeNow();
    assert(engine.getState() == AutoMixState::FadingFast);
    std::cout << "State after triggerFadeNow: FadingFast (OK)\n";

    // Process through the fade
    float initialProgress = engine.getTransitionProgress();
    assert(initialProgress >= 0.0f && initialProgress < 0.1f);

    for (std::size_t i = 0; i < sampleRate * 4; i += 1024) {
        engine.process(out.data(), 1024);
    }

    float finalProgress = engine.getTransitionProgress();
    std::cout << "Transition progress after 4s: " << finalProgress << "\n";
    assert(finalProgress > 0.4f);

    std::cout << "AutoMixEngine tests passed!\n";
    return 0;
}
