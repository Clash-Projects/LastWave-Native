#ifndef AUTODJ_PROCESSOR_H
#define AUTODJ_PROCESSOR_H

#include <cstdint>
#include <cmath>
#include <algorithm>

namespace lastwave {

enum class TrackLifecycleStage : int32_t {
    QUEUED = 0,
    RESOLVING = 1,
    RESOLVED = 2,
    PREPARING = 3,
    READY = 4,
    PLAYING = 5,
    FINISHED = 6,
    WAITING_LATE = 7,
    FAILED = 8,
    CANCELLED = 9
};

enum class AutoDJDecision : int32_t {
    DO_NOTHING = 0,
    PREFETCH_NEXT = 1,
    PREPARE_STANDBY = 2,
    TRIGGER_TRANSITION = 3,
    WAIT_FOR_LATE_TRACK = 4,
    SKIP_FAILED_TRACK = 5,
    REFILL_QUEUE = 6
};

class LatencyEstimator {
public:
    LatencyEstimator(double initialMeanMs = 2500.0, double initialStdDevMs = 1000.0, double alpha = 0.2)
        : m_meanLatencyMs(initialMeanMs),
          m_varianceLatencyMs(initialStdDevMs * initialStdDevMs),
          m_alpha(alpha),
          m_sampleCount(0) {}

    void recordSample(double latencyMs, bool wasSuccess) {
        if (!wasSuccess) {
            // Penalize failure by nudging mean upward to be safer
            m_meanLatencyMs = std::min(m_meanLatencyMs * 1.15, 15000.0);
            return;
        }
        m_sampleCount++;
        double diff = latencyMs - m_meanLatencyMs;
        m_meanLatencyMs += m_alpha * diff;
        m_varianceLatencyMs = (1.0 - m_alpha) * m_varianceLatencyMs + m_alpha * (diff * diff);
    }

    double getMeanMs() const { return m_meanLatencyMs; }
    double getStdDevMs() const { return std::sqrt(std::max(0.0, m_varianceLatencyMs)); }
    int64_t getSampleCount() const { return m_sampleCount; }

    int64_t calculateDynamicLeadWindowMs(int64_t transitionDurationMs, int64_t bufferMarginMs = 5000) const {
        double lead = static_cast<double>(transitionDurationMs) + m_meanLatencyMs + (2.5 * getStdDevMs()) + static_cast<double>(bufferMarginMs);
        int64_t leadClamped = static_cast<int64_t>(std::clamp(lead, 15000.0, 60000.0));
        return leadClamped;
    }

private:
    double m_meanLatencyMs;
    double m_varianceLatencyMs;
    double m_alpha;
    int64_t m_sampleCount;
};

class AutoDJProcessor {
public:
    static constexpr int32_t DEFAULT_MAX_RETRIES = 3;
    static constexpr int32_t DEFAULT_REFILL_THRESHOLD = 5; // Maintain 6-song Auto DJ queue horizon (1 playing + 5 upcoming)

    AutoDJProcessor(int32_t maxRetries = DEFAULT_MAX_RETRIES, int32_t refillThreshold = DEFAULT_REFILL_THRESHOLD);
    ~AutoDJProcessor() = default;

    void recordResolutionLatency(int64_t latencyMs, bool wasSuccess);

    AutoDJDecision evaluate(
        int64_t currentPositionMs,
        int64_t durationMs,
        bool isPlaying,
        int64_t transitionDurationMs,
        TrackLifecycleStage nextStage,
        int32_t retryCount,
        int32_t unplayedCount
    );

    int64_t getDynamicLeadWindowMs(int64_t transitionDurationMs) const;
    void getLatencyStats(float& outMeanMs, float& outStdDevMs, float& outLeadWindowMs, int64_t transitionDurationMs) const;

    void setMaxRetries(int32_t maxRetries) { m_maxRetries = maxRetries; }
    void setRefillThreshold(int32_t threshold) { m_refillThreshold = threshold; }

private:
    LatencyEstimator m_latencyEstimator;
    int32_t m_maxRetries;
    int32_t m_refillThreshold;
};

} // namespace lastwave

#endif // AUTODJ_PROCESSOR_H
