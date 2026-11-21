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
        // Clashing keys get the shortest phrase so the two harmonies barely meet.
        val candidates = listOf(16, 8, 4).filter { it <= if (clash) 4 else preferred }
        for (bars in candidates) {
            val overlap = bars * barMs
            val cueOut = outGrid.downbeatAtOrBefore(outEnd - overlap)
            if (cueOut < outro.startMs || cueOut < input.outgoingDurationMs * 0.5) continue
            val cueIn = inGrid.downbeatAtOrAfter(inStart.toDouble())
            // The incoming grid is only known inside its analysed window.
            if (cueIn + overlap * ratio > intro.endMs) continue
            val outLong = cueOut.roundToLong()
            val inLong = cueIn.roundToLong()
            val overlapLong = overlap.roundToLong()
            val outVocals = input.outgoingSignature?.hasVocalsBetween(outLong, outLong + overlapLong)
            val inVocals = input.incomingSignature?.hasVocalsBetween(inLong, inLong + (overlap * ratio).roundToLong())
            if (outVocals == true && inVocals == true) continue
            val swapAt = if (input.settings.spectralFilterSweepsEnabled) {
                (cueOut + bars / 2 * barMs).roundToLong()
            } else {
                null
            }
            return MixPlan(
                cueOutMs = outLong,
                cueInMs = inLong,
                overlapMs = overlapLong,
                shape = if (swapAt != null) MixShape.BASS_SWAP else MixShape.EQUAL_POWER,
                style = TransitionStyle.HARMONIC_BEATMIX,
                tempoRatio = ratio,
                beatLocked = true,
                bassSwapAtMs = swapAt,
                bassSwapRampMs = outGrid.beatMs.roundToLong(),
                bpm = outGrid.bpm,
                keyPair = keys?.let { "${it.first} → ${it.second}" },
                vocalSafe = outVocals != null && inVocals != null,
            )
        }
        return null
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
        val outVocals = input.outgoingSignature?.hasVocalsBetween(cueOut, outEnd)
        val inVocals = input.incomingSignature?.hasVocalsBetween(inStart, inStart + overlap)
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
}
