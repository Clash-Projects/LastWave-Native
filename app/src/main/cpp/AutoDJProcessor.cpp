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
    // 1. Refill check: when approaching exhaustion (<= 2 tracks remaining)
    if (unplayedCount <= m_refillThreshold) {
        return AutoDJDecision::REFILL_QUEUE;
    }

    // 2. Resilience: if stream expired, silently refresh it
    if (nextStage == TrackLifecycleStage::EXPIRED) {
        return AutoDJDecision::REFRESH_EXPIRED_STREAM;
    }

    // 3. Failure retry bound: 2 retries with backoff, then skip unplayable
    if (nextStage == TrackLifecycleStage::FAILED_RETRYING) {
        if (retryCount >= m_maxRetries) {
            return AutoDJDecision::SKIP_UNPLAYABLE;
        }
        return AutoDJDecision::RESOLVE_NEXT_STREAM;
    }

    // 4. Unknown duration handling
    if (durationMs <= 0) {
        if (nextStage == TrackLifecycleStage::QUEUED) {
            return AutoDJDecision::RESOLVE_NEXT_STREAM;
        }
        if (nextStage == TrackLifecycleStage::STREAM_RESOLVED) {
            return AutoDJDecision::DECODE_HEAD_WINDOW;
        }
        return AutoDJDecision::DO_NOTHING;
    }

    const int64_t remainingMs = std::max<int64_t>(0, durationMs - currentPositionMs);

    // 5. Track finished: remainingMs <= 0
    if (remainingMs <= 0) {
        if (nextStage == TrackLifecycleStage::ARMED_READY) {
            return AutoDJDecision::TRIGGER_TRANSITION;
        }
        if (nextStage == TrackLifecycleStage::RESOLVING_STREAM ||
            nextStage == TrackLifecycleStage::DECODING_HEAD_WINDOW ||
            nextStage == TrackLifecycleStage::QUEUED) {
            return AutoDJDecision::WAIT_FOR_LATE_TRACK;
        }
        if (nextStage == TrackLifecycleStage::STREAM_RESOLVED) {
            return AutoDJDecision::DECODE_HEAD_WINDOW;
        }
        return AutoDJDecision::DO_NOTHING;
    }

    // 6. Active playback progression
    if (isPlaying) {
        // Trigger transition right at exit cue ONLY if armed and ready
        if (remainingMs <= transitionDurationMs && nextStage == TrackLifecycleStage::ARMED_READY) {
            return AutoDJDecision::TRIGGER_TRANSITION;
        }

        // Immediate eager prefetch and pre-decode:
        if (nextStage == TrackLifecycleStage::QUEUED) {
            return AutoDJDecision::RESOLVE_NEXT_STREAM;
        }
        if (nextStage == TrackLifecycleStage::STREAM_RESOLVED) {
            return AutoDJDecision::DECODE_HEAD_WINDOW;
        }

        // At transition edge, if not armed ready, return DO_NOTHING so Deck A plays naturally to end
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
