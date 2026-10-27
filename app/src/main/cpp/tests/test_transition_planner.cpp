#include "../TransitionPlanner.h"
#include "../AutoMixEngine.h"
#include "../AutoDJProcessor.h"

#include <iostream>
#include <cassert>
#include <vector>
#include <cmath>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

int main() {
    using namespace lastwave::audio;
    using namespace lastwave;
    std::cout << "Testing Native C++ Auto DJ & Club Automix Engine...\n";

    const double sampleRate = 48000.0;

    // ========================================================================
    // Part 1: Auto DJ Processor Tests
    // ========================================================================
    std::cout << "--- Testing Auto DJ Processor Lifecycle & Refill ---\n";
    AutoDJProcessor autoDj(2, 2); // max 2 retries, refill at <= 2 remaining

    // 1. Queue exhaustion refill check
    AutoDJDecision dRefill = autoDj.evaluate(
        0, 180000, true, 8000,
        TrackLifecycleStage::QUEUED, 0, /*unplayedCount=*/2
    );
    assert(dRefill == AutoDJDecision::REFILL_QUEUE);
    std::cout << "Refill on <= 2 tracks remaining: OK\n";

    // 2. Early resolution: queued item starts resolving immediately
    AutoDJDecision dResolve = autoDj.evaluate(
        1000, 180000, true, 8000,
        TrackLifecycleStage::QUEUED, 0, /*unplayedCount=*/5
    );
    assert(dResolve == AutoDJDecision::RESOLVE_NEXT_STREAM);
    std::cout << "Start resolving next track immediately: OK\n";

    // 3. Advance arming: 40s before planned exit cue (remaining <= 40000 + 8000 = 48000ms)
    AutoDJDecision dArm = autoDj.evaluate(
        135000, 180000, true, 8000, // remaining 45000ms <= 48000ms
        TrackLifecycleStage::STREAM_RESOLVED, 0, /*unplayedCount=*/4
    );
    assert(dArm == AutoDJDecision::DECODE_HEAD_WINDOW);
    std::cout << "Advance arming 40s before exit cue: OK\n";

    // 4. Retry limits: bounded to 2 retries, then SKIP_UNPLAYABLE
    AutoDJDecision dRetry1 = autoDj.evaluate(
        50000, 180000, true, 8000,
        TrackLifecycleStage::FAILED_RETRYING, 1, 4
    );
    assert(dRetry1 == AutoDJDecision::RESOLVE_NEXT_STREAM);

    AutoDJDecision dSkip = autoDj.evaluate(
        50000, 180000, true, 8000,
        TrackLifecycleStage::FAILED_RETRYING, 2, 4
    );
    assert(dSkip == AutoDJDecision::SKIP_UNPLAYABLE);
    std::cout << "Max 2 retries followed by SKIP_UNPLAYABLE: OK\n";

    // ========================================================================
    // Part 2: Transition Planner Tests
    // ========================================================================
    std::cout << "--- Testing Transition Planner Hard 30s Clamp & Skit Skipper ---\n";

    // 1. Hard 30-Second Window Clamp Test
    // Track duration: 200,000 ms.
    // outCueMs MUST be clamped to [200000 - 30000, 200000 - 4000] = [170000, 196000]
    std::vector<LyricTimeInterval> lyricsA = {
        {160000, 168000, true}, // Last vocal ends at 168000 ms (before the 30s window)
    };
    std::vector<LyricTimeInterval> lyricsB;

    TransitionPlan plan1 = TransitionPlanner::planTransition(
        200000, nullptr, 0, sampleRate, lyricsA,
        200000, nullptr, 0, sampleRate, lyricsB,
        PhraseLengthMode::Bars8
    );

    std::cout << "Computed outCueMs: " << plan1.outCueMs << " ms (clamped to [170000, 196000])\n";
    assert(plan1.outCueMs >= 170000);
    assert(plan1.outCueMs <= 196000);

    // 2. Lyrics-aware vocal end snapping
    // Last vocal ends at 178,200 ms (inside final 30s window).
    std::vector<LyricTimeInterval> lyricsA_late = {
        {172000, 178200, true}
    };
    TransitionPlan planLate = TransitionPlanner::planTransition(
        200000, nullptr, 0, sampleRate, lyricsA_late,
        200000, nullptr, 0, sampleRate, lyricsB,
        PhraseLengthMode::Bars8
    );
    std::cout << "Computed vocal-snapped outCueMs: " << planLate.outCueMs << " ms\n";
    assert(planLate.outCueMs >= 178200); // Snapped on or after vocal end
    assert(planLate.outCueMs <= 196000);

    // 3. Deck B Spoken Skit Skipper Test
    // Create synthetic Deck B: 10 seconds of spoken monologue (quiet speech ~1kHz, RMS -25 dBFS)
    // followed by beat drop at 10.0s (100 Hz kick, RMS -10 dBFS, 120 BPM = 500ms intervals)
    const std::size_t deckBFrames = static_cast<std::size_t>(sampleRate * 25.0);
    std::vector<float> deckBPcm(deckBFrames * 2, 0.0f);

    // First 10s: quiet monologue (speech around 1000 Hz, amplitude 0.05 => -26 dBFS)
    const std::size_t skitFrames = static_cast<std::size_t>(sampleRate * 10.0);
    for (std::size_t f = 0; f < skitFrames; ++f) {
        float speech = 0.05f * std::sin(2.0f * static_cast<float>(M_PI) * 1000.0f * static_cast<float>(f) / 48000.0f);
        deckBPcm[f * 2] = speech;
        deckBPcm[f * 2 + 1] = speech;
    }

    // From 10s onwards: real kick drum at 120 BPM (500ms = 24000 frames)
    for (std::size_t k = 0; k < 20; ++k) {
        const std::size_t kickStart = skitFrames + k * 24000;
        for (std::size_t f = 0; f < 4800 && (kickStart + f) < deckBFrames; ++f) {
            float kick = 0.8f * std::sin(2.0f * static_cast<float>(M_PI) * 90.0f * static_cast<float>(f) / 48000.0f) *
                std::exp(-static_cast<float>(f) / 1200.0f);
            deckBPcm[(kickStart + f) * 2] += kick;
            deckBPcm[(kickStart + f) * 2 + 1] += kick;
        }
    }

    TransitionPlan planSkit = TransitionPlanner::planTransition(
        200000, nullptr, 0, sampleRate, lyricsA,
        200000, deckBPcm.data(), deckBFrames, sampleRate, lyricsB,
        PhraseLengthMode::Bars8
    );
    std::cout << "Skit skipped: " << (planSkit.isSkitSkipped ? "YES" : "NO")
              << ", Deck B inCueMs: " << planSkit.inCueMs << " ms\n";
    assert(planSkit.isSkitSkipped);
    assert(planSkit.inCueMs >= 6000 && planSkit.inCueMs <= 10000); // Snapped 1-2 bars before drop!

    // ========================================================================
    // Part 3: Dual-Deck Transition DSP Suite Tests
    // ========================================================================
    std::cout << "--- Testing Transition DSP & Midpoint Bass-Swap ---\n";
    AutoMixEngine engine;
    engine.configure(sampleRate);
    engine.setEnabled(true);
    engine.setTransitionPlan(plan1);

    // 1. Equal-Power Gain Curve & Midpoint Bass-Swap at t = 0.55
    const std::size_t N = 4800; // 100ms
    std::vector<float> sine60Hz(N * 2);
    for (std::size_t f = 0; f < N; ++f) {
        float val = std::sin(2.0f * static_cast<float>(M_PI) * 60.0f * static_cast<float>(f) / 48000.0f);
        sine60Hz[f * 2] = val;
        sine60Hz[f * 2 + 1] = val;
    }

    // Phase 1 (t = 0.25): Deck A holds 100% of sub-bass (<180 Hz)
    std::vector<float> deckA_p25 = sine60Hz;
    engine.processDeckA(deckA_p25.data(), N, 0.25f);
    float rmsA_p25 = 0.0f;
    for (std::size_t i = 1000; i < N; ++i) rmsA_p25 += deckA_p25[i * 2] * deckA_p25[i * 2];
    rmsA_p25 = std::sqrt(rmsA_p25 / (N - 1000));

    // Phase 1 (t = 0.25): Deck B has 200 Hz HPF (sub-bass heavily attenuated)
    std::vector<float> deckB_p25 = sine60Hz;
    engine.processDeckB(deckB_p25.data(), N, 0.25f);
    float rmsB_p25 = 0.0f;
    for (std::size_t i = 1000; i < N; ++i) rmsB_p25 += deckB_p25[i * 2] * deckB_p25[i * 2];
    rmsB_p25 = std::sqrt(rmsB_p25 / (N - 1000));

    std::cout << "t=0.25 Sub-bass RMS: Deck A = " << rmsA_p25 << ", Deck B = " << rmsB_p25 << "\n";
    assert(rmsA_p25 > 0.50f); // Deck A keeps bass (> 0.50f with correlated-bass headroom compensation)
    assert(rmsB_p25 < 0.08f); // Deck B 200 Hz HPF removes sub-bass

    // Midpoint Bass Swap (t = 0.70):
    // Deck B HPF dropped to 20 Hz (full sub-bass punch!)
    // Deck A resonant HPF swept to 550 Hz (sub-bass cut)
    std::vector<float> deckA_p70 = sine60Hz;
    engine.processDeckA(deckA_p70.data(), N, 0.70f);
    float rmsA_p70 = 0.0f;
    for (std::size_t i = 1000; i < N; ++i) rmsA_p70 += deckA_p70[i * 2] * deckA_p70[i * 2];
    rmsA_p70 = std::sqrt(rmsA_p70 / (N - 1000));

    std::vector<float> deckB_p70 = sine60Hz;
    engine.processDeckB(deckB_p70.data(), N, 0.70f);
    float rmsB_p70 = 0.0f;
    for (std::size_t i = 1000; i < N; ++i) rmsB_p70 += deckB_p70[i * 2] * deckB_p70[i * 2];
    rmsB_p70 = std::sqrt(rmsB_p70 / (N - 1000));

    std::cout << "t=0.70 Sub-bass RMS: Deck A = " << rmsA_p70 << ", Deck B = " << rmsB_p70 << "\n";
    assert(rmsA_p70 < 0.10f); // Deck A bass swept away
    assert(rmsB_p70 > 0.45f); // Deck B delivers full bass punch!

    // 2. Vocal-Pocket Ducking: dynamic -9.0 dB peaking cut at 1200 Hz when Deck B has vocals
    std::vector<float> sine1200Hz(N * 2);
    for (std::size_t f = 0; f < N; ++f) {
        float val = std::sin(2.0f * static_cast<float>(M_PI) * 1200.0f * static_cast<float>(f) / 48000.0f);
        sine1200Hz[f * 2] = val;
        sine1200Hz[f * 2 + 1] = val;
    }

    engine.setDeckBVocalsActive(false);
    std::vector<float> deckA_noDuck = sine1200Hz;
    engine.processDeckA(deckA_noDuck.data(), N, 0.10f);
    float rmsNoDuck = 0.0f;
    for (std::size_t i = 1000; i < N; ++i) rmsNoDuck += deckA_noDuck[i * 2] * deckA_noDuck[i * 2];
    rmsNoDuck = std::sqrt(rmsNoDuck / (N - 1000));

    engine.setDeckBVocalsActive(true);
    std::vector<float> deckA_ducked = sine1200Hz;
    engine.processDeckA(deckA_ducked.data(), N, 0.10f);
    float rmsDucked = 0.0f;
    for (std::size_t i = 1000; i < N; ++i) rmsDucked += deckA_ducked[i * 2] * deckA_ducked[i * 2];
    rmsDucked = std::sqrt(rmsDucked / (N - 1000));

    std::cout << "Vocal Ducking 1200Hz RMS: without duck = " << rmsNoDuck << ", with duck = " << rmsDucked << "\n";
    assert(rmsDucked < rmsNoDuck * 0.50f); // -9 dB cut = ~0.35x amplitude
    std::cout << "Vocal-Pocket Ducking -9.0 dB cut: OK\n";

    // 3. Fast manual skip test
    engine.triggerFadeNow();
    assert(engine.getState() == AutoMixState::FadingFast);
    std::cout << "Automix on Skip fast transition trigger: OK\n";

    std::cout << "\n>>> ALL NATIVE C++ AUTO DJ & AUTOMIX TESTS PASSED! <<<\n";
    return 0;
}
