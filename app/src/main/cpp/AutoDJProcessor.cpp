#include "AutoDJProcessor.h"

namespace lastwave {

AutoDJProcessor::AutoDJProcessor(int32_t maxRetries, int32_t refillThreshold)
    : m_latencyEstimator(2500.0, 1000.0, 0.2),
      m_maxRetries(maxRetries),
      m_refillThreshold(refillThreshold) {}

void AutoDJProcessor::recordResolutionLatency(int64_t latencyMs, bool wasSuccess) {
    m_latencyEstimator.recordSample(static_cast<double>(latencyMs), wasSuccess);
}

AutoDJDecision AutoDJProcessor::evaluate(
    int64_t currentPositionMs,
    int64_t durationMs,
    bool isPlaying,
    int64_t transitionDurationMs,
    TrackLifecycleStage nextStage,
    int32_t retryCount,
    int32_t unplayedCount
) {
    // 1. Refill check: if queue is running out of unplayed tracks, refill early
    if (unplayedCount <= m_refillThreshold) {
        if (durationMs <= 0 || currentPositionMs >= (durationMs / 2) || (durationMs - currentPositionMs) <= 60000) {
            return AutoDJDecision::REFILL_QUEUE;
        }
    }

    // 2. Terminal failure handling
    if (nextStage == TrackLifecycleStage::FAILED) {
        if (retryCount >= m_maxRetries) {
            return AutoDJDecision::SKIP_FAILED_TRACK;
        }
        return AutoDJDecision::PREFETCH_NEXT;
    }

    if (durationMs <= 0) {
        // Unknown duration or stream without fixed duration: prefetch if queued
        if (nextStage == TrackLifecycleStage::QUEUED) {
            return AutoDJDecision::PREFETCH_NEXT;
        }
        if (nextStage == TrackLifecycleStage::RESOLVED) {
            return AutoDJDecision::PREPARE_STANDBY;
        }
        return AutoDJDecision::DO_NOTHING;
    }

    const int64_t remainingMs = std::max<int64_t>(0, durationMs - currentPositionMs);
    const int64_t leadWindowMs = m_latencyEstimator.calculateDynamicLeadWindowMs(transitionDurationMs);

    // 3. Track has reached or exceeded its duration
    if (remainingMs <= 0) {
        if (nextStage == TrackLifecycleStage::READY) {
            return AutoDJDecision::TRIGGER_TRANSITION;
        }
        if (nextStage == TrackLifecycleStage::RESOLVING ||
            nextStage == TrackLifecycleStage::PREPARING ||
            nextStage == TrackLifecycleStage::QUEUED) {
            return AutoDJDecision::WAIT_FOR_LATE_TRACK;
        }
        if (nextStage == TrackLifecycleStage::RESOLVED) {
            return AutoDJDecision::PREPARE_STANDBY;
        }
        return AutoDJDecision::DO_NOTHING;
    }

    // 4. Normal active playback progression
    if (isPlaying) {
        // Ready for transition right at the transition boundary
        if (remainingMs <= transitionDurationMs && nextStage == TrackLifecycleStage::READY) {
            return AutoDJDecision::TRIGGER_TRANSITION;
        }

        // Within predictive lead window: start background resolution
        if (remainingMs <= leadWindowMs) {
            if (nextStage == TrackLifecycleStage::QUEUED) {
                return AutoDJDecision::PREFETCH_NEXT;
            }
            if (nextStage == TrackLifecycleStage::RESOLVED) {
                return AutoDJDecision::PREPARE_STANDBY;
            }
            // If already inside transition window but track is still resolving/preparing,
            // signal anticipated late track
            if (remainingMs <= transitionDurationMs &&
                (nextStage == TrackLifecycleStage::RESOLVING || nextStage == TrackLifecycleStage::PREPARING)) {
                return AutoDJDecision::WAIT_FOR_LATE_TRACK;
            }
        }
    }

    return AutoDJDecision::DO_NOTHING;
}

int64_t AutoDJProcessor::getDynamicLeadWindowMs(int64_t transitionDurationMs) const {
    return m_latencyEstimator.calculateDynamicLeadWindowMs(transitionDurationMs);
}

void AutoDJProcessor::getLatencyStats(
    float& outMeanMs,
    float& outStdDevMs,
    float& outLeadWindowMs,
    int64_t transitionDurationMs
) const {
    outMeanMs = static_cast<float>(m_latencyEstimator.getMeanMs());
    outStdDevMs = static_cast<float>(m_latencyEstimator.getStdDevMs());
    outLeadWindowMs = static_cast<float>(m_latencyEstimator.calculateDynamicLeadWindowMs(transitionDurationMs));
}

} // namespace lastwave
