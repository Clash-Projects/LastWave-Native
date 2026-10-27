#include "AutoDJProcessor.h"
#include <iostream>
#include <cassert>

using namespace lastwave;

void testLatencyEstimator() {
    std::cout << "[TEST] Running LatencyEstimator test..." << std::endl;
    LatencyEstimator estimator(2500.0, 1000.0, 0.2);

    assert(estimator.getMeanMs() == 2500.0);
    assert(estimator.getStdDevMs() == 1000.0);

    // Initial lead window with 5000ms transition:
    // clamped to [20000, 70000]
    int64_t initialLead = estimator.calculateDynamicLeadWindowMs(5000);
    assert(initialLead == 20000);

    // Record a 4000ms slow sample
    estimator.recordSample(4000.0, true);
    assert(estimator.getMeanMs() > 2500.0);
    assert(estimator.getSampleCount() == 1);

    int64_t updatedLead = estimator.calculateDynamicLeadWindowMs(5000);
    assert(updatedLead >= initialLead);
    assert(updatedLead <= 70000);

    std::cout << "  Mean: " << estimator.getMeanMs() << " ms, StdDev: "
              << estimator.getStdDevMs() << " ms, Lead: " << updatedLead << " ms" << std::endl;
    std::cout << "[PASS] LatencyEstimator test passed." << std::endl;
}

void testAutoDJProcessorDecisions() {
    std::cout << "[TEST] Running AutoDJProcessor decision test..." << std::endl;
    AutoDJProcessor processor;

    const int64_t trackDuration = 180000; // 3 min
    const int64_t transitionMs = 5000;

    // 1. Beginning of track (pos = 10s): should eagerly prefetch next track immediately
    auto d1 = processor.evaluate(10000, trackDuration, true, transitionMs, TrackLifecycleStage::QUEUED, 0, 5);
    assert(d1 == AutoDJDecision::RESOLVE_NEXT_STREAM);

    // 2. Track resolved: should decode head window immediately
    auto d2 = processor.evaluate(168000, trackDuration, true, transitionMs, TrackLifecycleStage::STREAM_RESOLVED, 0, 5);
    assert(d2 == AutoDJDecision::DECODE_HEAD_WINDOW);

    // 3. Decoding head window in progress: DO_NOTHING until PCM buffer is verified
    auto d3 = processor.evaluate(168000, trackDuration, true, transitionMs, TrackLifecycleStage::DECODING_HEAD_WINDOW, 0, 5);
    assert(d3 == AutoDJDecision::DO_NOTHING);

    // 4. Transition window reached (pos = 176s, remaining = 4s <= 5s) and ARMED_READY: should TRIGGER_TRANSITION
    auto d4 = processor.evaluate(176000, trackDuration, true, transitionMs, TrackLifecycleStage::ARMED_READY, 0, 5);
    assert(d4 == AutoDJDecision::TRIGGER_TRANSITION);

    // 5. Track ends but next track is still in-flight: should WAIT_FOR_LATE_TRACK
    auto d5 = processor.evaluate(180000, trackDuration, true, transitionMs, TrackLifecycleStage::RESOLVING_STREAM, 0, 5);
    assert(d5 == AutoDJDecision::WAIT_FOR_LATE_TRACK);

    // 6. Next track failed with retryCount = 1 (< 2): should RESOLVE_NEXT_STREAM (retry)
    auto d6 = processor.evaluate(168000, trackDuration, true, transitionMs, TrackLifecycleStage::FAILED_RETRYING, 1, 5);
    assert(d6 == AutoDJDecision::RESOLVE_NEXT_STREAM);

    // 7. Next track failed with retryCount = 2 (>= 2): should SKIP_UNPLAYABLE
    auto d7 = processor.evaluate(168000, trackDuration, true, transitionMs, TrackLifecycleStage::FAILED_RETRYING, 2, 5);
    assert(d7 == AutoDJDecision::SKIP_UNPLAYABLE);

    // 8. Unplayed queue count <= 2: should REFILL_QUEUE
    auto d8 = processor.evaluate(120000, trackDuration, true, transitionMs, TrackLifecycleStage::ARMED_READY, 0, 2);
    assert(d8 == AutoDJDecision::REFILL_QUEUE);

    std::cout << "[PASS] AutoDJProcessor decision test passed." << std::endl;
}

int main() {
    std::cout << "=== Running AutoDJProcessor Test Suite ===" << std::endl;
    testLatencyEstimator();
    testAutoDJProcessorDecisions();
    std::cout << "=== All AutoDJProcessor Tests Passed Successfully ===" << std::endl;
    return 0;
}
