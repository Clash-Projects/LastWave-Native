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
    // 5000 + 2500 + 2.5 * 1000 + 5000 = 15000
    int64_t initialLead = estimator.calculateDynamicLeadWindowMs(5000);
    assert(initialLead == 15000);

    // Record a 4000ms slow sample
    estimator.recordSample(4000.0, true);
    assert(estimator.getMeanMs() > 2500.0);
    assert(estimator.getSampleCount() == 1);

    int64_t updatedLead = estimator.calculateDynamicLeadWindowMs(5000);
    assert(updatedLead >= initialLead);
    assert(updatedLead <= 60000);

    std::cout << "  Mean: " << estimator.getMeanMs() << " ms, StdDev: "
              << estimator.getStdDevMs() << " ms, Lead: " << updatedLead << " ms" << std::endl;
    std::cout << "[PASS] LatencyEstimator test passed." << std::endl;
}

void testAutoDJProcessorDecisions() {
    std::cout << "[TEST] Running AutoDJProcessor decision test..." << std::endl;
    AutoDJProcessor processor;

    const int64_t trackDuration = 180000; // 3 min
    const int64_t transitionMs = 5000;

    // 1. Beginning of track (pos = 10s): should DO_NOTHING
    auto d1 = processor.evaluate(10000, trackDuration, true, transitionMs, TrackLifecycleStage::QUEUED, 0, 5);
    assert(d1 == AutoDJDecision::DO_NOTHING);

    // 2. Lead window reached (pos = 168s, remaining = 12s < 15s): should PREFETCH_NEXT
    auto d2 = processor.evaluate(168000, trackDuration, true, transitionMs, TrackLifecycleStage::QUEUED, 0, 5);
    assert(d2 == AutoDJDecision::PREFETCH_NEXT);

    // 3. Track resolved: should PREPARE_STANDBY
    auto d3 = processor.evaluate(168000, trackDuration, true, transitionMs, TrackLifecycleStage::RESOLVED, 0, 5);
    assert(d3 == AutoDJDecision::PREPARE_STANDBY);

    // 4. Transition window reached (pos = 176s, remaining = 4s <= 5s) and READY: should TRIGGER_TRANSITION
    auto d4 = processor.evaluate(176000, trackDuration, true, transitionMs, TrackLifecycleStage::READY, 0, 5);
    assert(d4 == AutoDJDecision::TRIGGER_TRANSITION);

    // 5. Track ends but next track is still RESOLVING: should WAIT_FOR_LATE_TRACK
    auto d5 = processor.evaluate(180000, trackDuration, true, transitionMs, TrackLifecycleStage::RESOLVING, 0, 5);
    assert(d5 == AutoDJDecision::WAIT_FOR_LATE_TRACK);

    // 6. Next track failed with retryCount = 1 (< 3): should PREFETCH_NEXT (retry)
    auto d6 = processor.evaluate(168000, trackDuration, true, transitionMs, TrackLifecycleStage::FAILED, 1, 5);
    assert(d6 == AutoDJDecision::PREFETCH_NEXT);

    // 7. Next track failed with retryCount = 3 (>= 3): should SKIP_FAILED_TRACK
    auto d7 = processor.evaluate(168000, trackDuration, true, transitionMs, TrackLifecycleStage::FAILED, 3, 5);
    assert(d7 == AutoDJDecision::SKIP_FAILED_TRACK);

    // 8. Unplayed queue count <= 2: should REFILL_QUEUE
    auto d8 = processor.evaluate(120000, trackDuration, true, transitionMs, TrackLifecycleStage::READY, 0, 2);
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
