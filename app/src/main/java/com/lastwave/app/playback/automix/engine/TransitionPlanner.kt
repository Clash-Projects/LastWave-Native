package com.lastwave.app.playback.automix.engine

import com.lastwave.app.playback.MediaType
import com.lastwave.app.playback.PlayableTrack
import com.lastwave.app.playback.automix.analysis.AcousticSignature
import com.lastwave.app.playback.automix.analysis.AutoMixCamelotClassifier
import com.lastwave.app.playback.automix.analysis.BeatGrid
import com.lastwave.app.playback.automix.analysis.EndingStyle
import com.lastwave.app.playback.automix.analysis.HarmonicCompatibilityTier
import com.lastwave.app.playback.automix.analysis.IntroStyle
import com.lastwave.app.playback.automix.analysis.WindowAnalysis
import com.lastwave.app.playback.automix.api.AutoMixSettings
import com.lastwave.app.playback.automix.api.MixShape
import com.lastwave.app.playback.automix.api.CamelotKey
import com.lastwave.app.playback.automix.api.TransitionDurationMode
import com.lastwave.app.playback.automix.api.TransitionStyle
import kotlin.math.abs
import kotlin.math.roundToLong

sealed interface MixDecision

/** No mix: the queue advances exactly as it would without AutoMix. */
data class MixBypass(val reason: String) : MixDecision

/**
 * One transition. All times are media ms; outgoing times are on the
 * outgoing song's timeline, incoming ones on the incoming song's.
 */
data class MixPlan(
    /** Where the fade begins on the outgoing song. */
    val cueOutMs: Long,
    /** The incoming position heard at [cueOutMs]. */
    val cueInMs: Long,
    /** Length of the fade, in outgoing time. */
    val overlapMs: Long,
    val shape: MixShape,
    val style: TransitionStyle,
    /** Incoming playback speed while beat-locked; 1 otherwise. */
    val tempoRatio: Float = 1f,
    val beatLocked: Boolean = false,
    /** Outgoing time at which the low end moves to the incoming deck. */
    val bassSwapAtMs: Long? = null,
    val bassSwapRampMs: Long = 0L,
    val bpm: Float? = null,
    val keyPair: String? = null,
    /** False only when lyrics say both songs sing during the overlap. */
    val vocalSafe: Boolean = true,
    /** Beat-locked mixes: the grids the lock aligns, and the phrase length in bars. */
    val outGrid: BeatGrid? = null,
    val inGrid: BeatGrid? = null,
    val bars: Int? = null,
) : MixDecision {
    val endOutMs: Long get() = cueOutMs + overlapMs

    /** Incoming position that lines up with outgoing position [outgoingMs]. */
    fun incomingTimeFor(outgoingMs: Long): Long = cueInMs + ((outgoingMs - cueOutMs) * tempoRatio).roundToLong()
}

data class PlanInput(
    val outgoing: PlayableTrack,
    val incoming: PlayableTrack,
    val outgoingDurationMs: Long,
    /** 0 when not known yet. */
    val incomingDurationMs: Long,
    val outgoingSignature: AcousticSignature?,
    val incomingSignature: AcousticSignature?,
    val settings: AutoMixSettings,
    val userSpeed: Float,
    val userPitch: Float,
)

/**
 * Chooses how two songs meet. A beat-matched mix happens only when both
 * grids are confident and the tempos are within a pitch-safe stretch;
 * anything uncertain gets a fade shaped by how the outgoing song actually
 * ends, because a confidently wrong beatmix sounds worse than a clean fade.
 */
object TransitionPlanner {
    const val MIN_TRACK_MS = 30_000L
    private const val MIN_BEAT_CONFIDENCE = 0.6f
    private const val MIN_KEY_CONFIDENCE = 0.5f
    private const val MAX_TEMPO_STRETCH = 0.06f
    private const val MAX_INTRO_TRIM_MS = 10_000L
    private const val MAX_OUTRO_TRIM_MS = 20_000L
    private const val AUTO_BEATMIX_MAX_MS = 20_000L
    private const val UNKNOWN_FADE_MS = 8_000L

    fun plan(input: PlanInput): MixDecision {
        if (input.outgoing.mediaType != MediaType.SONG || input.incoming.mediaType != MediaType.SONG) {
            return MixBypass("not a song")
        }
        if (input.outgoingDurationMs < MIN_TRACK_MS ||
            input.incomingDurationMs in 1 until MIN_TRACK_MS
        ) {
            return MixBypass("short track")
        }
        if (isAlbumSequence(input.outgoing, input.incoming)) return MixBypass("album sequence plays gapless")
        val outro = input.outgoingSignature?.outro
        val intro = input.incomingSignature?.intro
        val outEnd = outro?.audibleEndMs
            ?.coerceIn(input.outgoingDurationMs - MAX_OUTRO_TRIM_MS, input.outgoingDurationMs)
            ?: input.outgoingDurationMs
        val inStart = intro?.audibleStartMs?.coerceIn(0L, MAX_INTRO_TRIM_MS) ?: 0L
        return beatmix(input, outro, intro, outEnd, inStart) ?: fade(input, outro, intro, outEnd, inStart)
    }

    /** The next track of the same album: the artist sequenced that join, keep it. */
    private fun isAlbumSequence(outgoing: PlayableTrack, incoming: PlayableTrack): Boolean {
        val album = outgoing.album?.trim()?.lowercase().orEmpty()
        val outNumber = outgoing.trackNumber ?: return false
        val inNumber = incoming.trackNumber ?: return false
        return album.isNotEmpty() && album == incoming.album?.trim()?.lowercase() && inNumber == outNumber + 1
    }

    private fun beatmix(
        input: PlanInput,
        outro: WindowAnalysis?,
        intro: WindowAnalysis?,
        outEnd: Long,
        inStart: Long,
    ): MixPlan? {
        // Tempo matching changes the speed; never fight a user-chosen speed or pitch.
        if (input.userSpeed != 1f || input.userPitch != 1f || outro == null || intro == null) return null
        val outGrid = outro.grid?.takeIf { it.confidence >= MIN_BEAT_CONFIDENCE } ?: return null
        val inGrid = intro.grid?.takeIf { it.confidence >= MIN_BEAT_CONFIDENCE } ?: return null
        val ratio = tempoRatio(outGrid, inGrid) ?: return null
        val keys = keyPair(outro, intro)
        val clash = keys?.let {
            AutoMixCamelotClassifier.evaluateCompatibility(it.first, it.second).tier == HarmonicCompatibilityTier.DISSONANT
        } == true
        val barMs = outGrid.barMs
        val preferred = when (input.settings.durationMode) {
            TransitionDurationMode.AUTO -> if (8 * barMs <= AUTO_BEATMIX_MAX_MS) 8 else 4
            TransitionDurationMode.MANUAL -> listOf(4, 8, 16).minBy { abs(it * barMs - input.settings.targetDurationSeconds * 1000.0) }
        }
        val swapEnabled = input.settings.spectralFilterSweepsEnabled
        val shape = if (swapEnabled) MixShape.BASS_SWAP else MixShape.EQUAL_POWER
        // Clashing keys get the shortest phrase so the two harmonies barely meet.
        val best = listOf(16, 8, 4).filter { it <= if (clash) 4 else preferred }.flatMap { bars ->
            val overlap = bars * barMs
            val latest = outGrid.downbeatAtOrBefore(outEnd - overlap)
            val cueOuts = (0..MAX_PHRASE_SEARCH_BARS).map { latest - it * barMs }
                .filter { it >= outro.startMs && it >= input.outgoingDurationMs * 0.5 }
            cueIns(inGrid, intro, inStart, overlap * ratio).flatMap { cueIn ->
                cueOuts.map { cueOut ->
                    Candidate(bars, cueOut, cueIn, overlap, ((latest - cueOut) / barMs).roundToLong().toInt())
                }
            }
        }.map { candidate ->
            candidate to score(candidate, preferred, ratio, shape, outro, intro, input)
        }.maxByOrNull { it.second.total }
            ?.takeIf { it.second.clashMs <= MAX_BEATMIX_CLASH_MS }
            ?: return null
        val (chosen, verdict) = best
        val swapAt = if (swapEnabled) (chosen.cueOut + chosen.bars / 2 * barMs).roundToLong() else null
        return MixPlan(
            cueOutMs = chosen.cueOut.roundToLong(),
            cueInMs = chosen.cueIn.roundToLong(),
            overlapMs = chosen.overlap.roundToLong(),
            shape = shape,
            style = TransitionStyle.HARMONIC_BEATMIX,
            tempoRatio = ratio,
            beatLocked = true,
            bassSwapAtMs = swapAt,
            bassSwapRampMs = outGrid.beatMs.roundToLong(),
            bpm = outGrid.bpm,
            keyPair = keys?.let { "${it.first} \u2192 ${it.second}" },
            vocalSafe = verdict.vocalsKnown && verdict.clashMs == 0.0 && !verdict.cutsLine,
            outGrid = outGrid,
            inGrid = inGrid,
            bars = chosen.bars,
        )
    }

    private class Candidate(val bars: Int, val cueOut: Double, val cueIn: Double, val overlap: Double, val barsBack: Int)

    private class Verdict(val total: Double, val clashMs: Double, val cutsLine: Boolean, val vocalsKnown: Boolean)

    /**
     * Where the incoming song may enter: its first downbeat after any silence,
     * or a later downbeat chosen so one of its energy rises (a drop or chorus)
     * lands exactly as the mix ends. Never more than [MAX_INTRO_SKIP_BARS] in,
     * and only while its grid is still the analysed one.
     */
    private fun cueIns(grid: BeatGrid, intro: WindowAnalysis, inStart: Long, incomingSpan: Double): List<Double> {
        val first = grid.downbeatAtOrAfter(inStart.toDouble())
        val latest = first + MAX_INTRO_SKIP_BARS * grid.barMs
        val dropAligned = intro.phrases.filter { it.shiftDb > 0 }
            .map { grid.downbeatAtOrBefore(it.ms - incomingSpan + grid.barMs / 4) }
            .filter { it in first..latest }
        return (listOf(first) + dropAligned).distinct().filter { it + incomingSpan <= intro.endMs }
    }

    /**
     * Judges a candidate the way a DJ listens: start on a section change,
     * let the incoming drop hit as the old song leaves, never sing over
     * singing, never turn a line down mid-word, never hide a new vocal under
     * the old song, and keep as much of the outgoing outro as possible.
     */
    private fun score(
        candidate: Candidate,
        preferredBars: Int,
        ratio: Float,
        shape: MixShape,
        outro: WindowAnalysis,
        intro: WindowAnalysis,
        input: PlanInput,
    ): Verdict {
        val bar = candidate.overlap / candidate.bars
        val sectionAtStart = outro.phrases.filter { abs(it.ms - candidate.cueOut) <= bar / 4 }
            .maxOfOrNull { abs(it.shiftDb).toDouble() } ?: 0.0
        val incomingEnd = candidate.cueIn + candidate.overlap * ratio
        val dropAtEnd = intro.phrases.filter { it.shiftDb > 0 && abs(it.ms - incomingEnd) <= bar / 4 }
            .maxOfOrNull { it.shiftDb.toDouble() } ?: 0.0
        val vocals = VocalCheck.of(
            input.outgoingSignature, input.incomingSignature,
            candidate.cueOut, candidate.cueIn, candidate.overlap, ratio, shape,
        )
        val total = sectionAtStart.coerceAtMost(PHRASE_SCORE_CAP) +
            dropAtEnd.coerceAtMost(PHRASE_SCORE_CAP) -
            candidate.barsBack * LOST_BAR_COST -
            (preferredBars / candidate.bars - 1) * SHORTER_PHRASE_COST -
            vocals.clashMs / 1000.0 * CLASH_COST_PER_SECOND -
            (if (vocals.cutsLine) CUT_LINE_COST else 0.0) -
            (if (vocals.hidesEntry) HIDDEN_ENTRY_COST else 0.0) -
            (candidate.cueIn - intro.startMs).coerceAtLeast(0.0) / bar * SKIPPED_INTRO_BAR_COST
        return Verdict(total, vocals.clashMs, vocals.cutsLine, vocals.known)
    }

    private fun fade(
        input: PlanInput,
        outro: WindowAnalysis?,
        intro: WindowAnalysis?,
        outEnd: Long,
        inStart: Long,
    ): MixPlan {
        var shape = MixShape.EQUAL_POWER
        var overlap = when {
            input.settings.durationMode == TransitionDurationMode.MANUAL ->
                input.settings.targetDurationSeconds * 1000L
            outro == null -> UNKNOWN_FADE_MS
            outro.ending == EndingStyle.FADE_OUT ->
                (outEnd - (outro.fadeStartMs ?: outEnd)).coerceIn(6_000L, 12_000L)
            outro.ending == EndingStyle.COLD -> {
                // Let the last hit land: the next song rises under it and takes over at the stop.
                shape = MixShape.HOLD_OUTGOING
                if (intro?.intro == IntroStyle.COLD) 500L else 2_000L
            }
            else -> 6_000L
        }
        overlap = overlap.coerceAtMost((input.outgoingDurationMs * 0.4).toLong()).coerceAtLeast(500L)
        var cueOut = (outEnd - overlap).coerceAtLeast(0L)
        val outSig = input.outgoingSignature
        val inSig = input.incomingSignature
        // Wait for the singer: a line still running while the old song turns
        // down is cut mid-word, so the fade starts when that line ends.
        val lineEnd = outSig?.vocals
            ?.filter { it.startMs < cueOut + overlap * 3 / 4 && it.endMs > cueOut }
            ?.maxOfOrNull { it.endMs }
        if (lineEnd != null && shape != MixShape.HOLD_OUTGOING) {
            cueOut = minOf(lineEnd, outEnd - MIN_VOCAL_FADE_MS)
            overlap = outEnd - cueOut
        }
        // A new vocal should not start while the next song is still faint under the old one.
        val firstLine = inSig?.vocals?.firstOrNull { it.endMs > inStart }?.startMs
        if (firstLine != null && firstLine < inStart + overlap / 2) {
            overlap = maxOf(MIN_VOCAL_FADE_MS, (firstLine - inStart) * 2).coerceAtMost(outEnd - cueOut)
            cueOut = outEnd - overlap
        }
        val outVocals = outSig?.hasVocalsBetween(cueOut, outEnd)
        val inVocals = inSig?.hasVocalsBetween(inStart, inStart + overlap)
        if (outVocals == true && inVocals == true && overlap > VOCAL_CLASH_MS) {
            overlap = VOCAL_CLASH_MS
            cueOut = outEnd - overlap
        }
        val swap = input.settings.spectralFilterSweepsEnabled && when (shape) {
            MixShape.HOLD_OUTGOING -> overlap >= 1_500L
            else -> overlap >= 6_000L
        }
        if (swap && shape == MixShape.EQUAL_POWER) shape = MixShape.BASS_SWAP
        return MixPlan(
            cueOutMs = cueOut,
            cueInMs = inStart,
            overlapMs = overlap,
            shape = shape,
            style = if (swap) TransitionStyle.SPECTRAL_SWEEP else TransitionStyle.PERCEPTUAL_FADE,
            bassSwapAtMs = when {
                !swap -> null
                shape == MixShape.BASS_SWAP -> cueOut + overlap / 2
                else -> cueOut + overlap
            },
            bassSwapRampMs = if (shape == MixShape.BASS_SWAP) 1_500L else 250L,
            keyPair = keyPair(outro, intro)?.let { "${it.first} → ${it.second}" },
            vocalSafe = !(outVocals == true && inVocals == true) && outVocals != null && inVocals != null,
        )
    }

    /** Incoming speed that matches the outgoing tempo, allowing half/double time, or null if too far. */
    private fun tempoRatio(outGrid: BeatGrid, inGrid: BeatGrid): Float? =
        listOf(0.5f, 1f, 2f)
            .map { factor -> outGrid.bpm / (inGrid.bpm * factor) }
            .minBy { abs(it - 1f) }
            .takeIf { abs(it - 1f) <= MAX_TEMPO_STRETCH }

    private fun keyPair(outro: WindowAnalysis?, intro: WindowAnalysis?): Pair<CamelotKey, CamelotKey>? {
        val out = outro?.camelotKey?.takeIf { outro.keyConfidence >= MIN_KEY_CONFIDENCE } ?: return null
        val incoming = intro?.camelotKey?.takeIf { intro.keyConfidence >= MIN_KEY_CONFIDENCE } ?: return null
        return CamelotKey(out) to CamelotKey(incoming)
    }

    private const val VOCAL_CLASH_MS = 4_000L
    private const val MIN_VOCAL_FADE_MS = 3_000L
    // Each bar earlier drops that much of the outgoing outro; four bars is a DJ-sized trade.
    private const val MAX_PHRASE_SEARCH_BARS = 4
    private const val MAX_INTRO_SKIP_BARS = 8
    private const val MAX_BEATMIX_CLASH_MS = 1_500.0
    // Score weights, in "dB of phrase change" so each trades off against phrasing.
    private const val PHRASE_SCORE_CAP = 8.0
    private const val LOST_BAR_COST = 1.5
    private const val SHORTER_PHRASE_COST = 2.0
    private const val CLASH_COST_PER_SECOND = 4.0
    private const val CUT_LINE_COST = 6.0
    private const val HIDDEN_ENTRY_COST = 4.0
    private const val SKIPPED_INTRO_BAR_COST = 0.75
}
