package com.lastwave.app.playback

/**
 * Monotonic stage timing for one quality hot-swap request.
 *
 * The previous pipeline only logged the final hot-swap line, so a slow switch
 * could not be attributed to resolution, validation, the offset probe or the
 * deck alignment. Marks are cheap and side-effect free; [finish] returns one
 * report exactly once, which the player writes to both logcat and the in-app
 * diagnostics journal.
 *
 * The clock is injectable so the stage arithmetic is unit-testable without
 * Android. Production uses [System.nanoTime] (monotonic and unaffected by
 * wall-clock changes).
 */
internal class QualitySwitchTrace(
    private val trackTitle: String,
    private val requestedQuality: Int,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private val requestedAtMs = nowMs()
    private var resolveStartMs = 0L
    private var resolveEndMs = 0L
    private var validationStartMs = 0L
    private var validationEndMs = 0L
    private var probeStartMs = 0L
    private var probeEndMs = 0L
    private var alignmentStartMs = 0L
    private var alignmentEndMs = 0L
    private var fadeStartMs = 0L
    private var lagMs: Long? = null
    private var offsetConfidence: Double? = null
    private var offsetCacheHit = false
    private var alignRounds = 0
    private var driftMs: Long? = null
    private var finished = false

    @Synchronized
    fun markResolveStart() {
        if (resolveStartMs == 0L) resolveStartMs = nowMs()
    }

    @Synchronized
    fun markResolveEnd() {
        if (resolveEndMs == 0L) resolveEndMs = nowMs()
    }

    @Synchronized
    fun markValidationStart() {
        if (validationStartMs == 0L) validationStartMs = nowMs()
    }

    @Synchronized
    fun markValidationEnd() {
        if (validationEndMs == 0L) validationEndMs = nowMs()
    }

    @Synchronized
    fun markProbeStart() {
        if (probeStartMs == 0L) probeStartMs = nowMs()
    }

    @Synchronized
    fun markProbeEnd(lagMs: Long?, confidence: Double?, cacheHit: Boolean) {
        if (probeEndMs == 0L) probeEndMs = nowMs()
        this.lagMs = lagMs
        offsetConfidence = confidence
        offsetCacheHit = cacheHit
    }

    @Synchronized
    fun markAlignmentStart() {
        if (alignmentStartMs == 0L) alignmentStartMs = nowMs()
    }

    @Synchronized
    fun markAlignmentEnd(rounds: Int, driftMs: Long) {
        if (alignmentEndMs == 0L) alignmentEndMs = nowMs()
        alignRounds = rounds
        this.driftMs = driftMs
    }

    @Synchronized
    fun markFadeStart() {
        if (fadeStartMs == 0L) fadeStartMs = nowMs()
    }

    @Synchronized
    fun finish(outcome: String): QualitySwitchReport? {
        if (finished) return null
        finished = true
        val finishedAtMs = nowMs()
        return QualitySwitchReport(
            trackTitle = trackTitle,
            requestedQuality = requestedQuality,
            outcome = outcome,
            gateMs = elapsed(requestedAtMs, resolveStartMs),
            resolveMs = elapsedOrNull(resolveStartMs, resolveEndMs),
            validationMs = elapsedOrNull(validationStartMs, validationEndMs),
            probeMs = elapsedOrNull(probeStartMs, probeEndMs),
            alignmentMs = elapsedOrNull(alignmentStartMs, alignmentEndMs),
            resolveToSwitchMs = elapsedOrNull(resolveEndMs, fadeStartMs),
            totalMs = (finishedAtMs - requestedAtMs).coerceAtLeast(0L),
            lagMs = lagMs,
            offsetConfidence = offsetConfidence,
            offsetCacheHit = offsetCacheHit,
            alignRounds = alignRounds,
            driftMs = driftMs,
        )
    }

    private fun elapsed(startMs: Long, endMs: Long): Long =
        if (startMs == 0L || endMs < startMs) 0L else endMs - startMs

    private fun elapsedOrNull(startMs: Long, endMs: Long): Long? =
        if (startMs == 0L || endMs < startMs) null else endMs - startMs
}

internal data class QualitySwitchReport(
    val trackTitle: String,
    val requestedQuality: Int,
    val outcome: String,
    val gateMs: Long,
    val resolveMs: Long?,
    val validationMs: Long?,
    val probeMs: Long?,
    val alignmentMs: Long?,
    val resolveToSwitchMs: Long?,
    val totalMs: Long,
    val lagMs: Long?,
    val offsetConfidence: Double?,
    val offsetCacheHit: Boolean,
    val alignRounds: Int,
    val driftMs: Long?,
) {
    fun logLine(): String = buildString {
        append("track='").append(trackTitle.take(48)).append("'")
        append(" q=").append(requestedQuality)
        append(" outcome=").append(outcome)
        append(" gateMs=").append(gateMs)
        append(" resolveMs=").append(resolveMs ?: -1L)
        append(" validationMs=").append(validationMs ?: -1L)
        append(" probeMs=").append(probeMs ?: -1L)
        append(" alignMs=").append(alignmentMs ?: -1L)
        append(" resolveToSwitchMs=").append(resolveToSwitchMs ?: -1L)
        append(" totalMs=").append(totalMs)
        append(" lagMs=").append(lagMs ?: Long.MIN_VALUE)
        append(" confidence=").append(offsetConfidence?.let { "%.2f".format(it) } ?: "-")
        append(" offset=").append(if (offsetCacheHit) "cache" else "probe")
        append(" rounds=").append(alignRounds)
        append(" driftMs=").append(driftMs ?: Long.MIN_VALUE)
    }
}
