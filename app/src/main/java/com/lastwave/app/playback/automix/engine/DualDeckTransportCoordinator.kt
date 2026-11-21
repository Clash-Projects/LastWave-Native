package com.lastwave.app.playback.automix.engine

import android.os.SystemClock
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.lastwave.app.playback.PlayableTrack
import com.lastwave.app.playback.automix.analysis.AcousticSignatureExtractor
import com.lastwave.app.playback.automix.analysis.AcousticSignature
import com.lastwave.app.playback.automix.analysis.AnalysisInput
import com.lastwave.app.playback.automix.analysis.WindowAnalysis
import com.lastwave.app.playback.automix.api.AutoMixPreferences
import com.lastwave.app.playback.automix.api.AutoMixSettings
import com.lastwave.app.playback.automix.api.MixShape
import com.lastwave.app.playback.automix.api.BlendPhase
import com.lastwave.app.playback.automix.api.DeckBlendTelemetry
import com.lastwave.app.playback.automix.api.MixDeckView
import com.lastwave.app.playback.automix.api.MixView
import com.lastwave.app.playback.automix.api.TrackChromeSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Sole owner of an AutoMix transition: analysis requests, the plan, the
 * standby's cue, the blend, the hand-over and every way it can end. Main
 * thread only.
 *
 * A running blend holds immutable references to its two decks, so nothing
 * MusicPlayer does to `player` mid-blend can make it fade the wrong deck,
 * and every interruption goes through [endTransition], which commits a blend
 * that has already handed over and reverts one that has not.
 */
@Singleton
class DualDeckTransportCoordinator @Inject constructor(
    preferences: AutoMixPreferences,
    private val analyzer: AcousticSignatureExtractor,
) {
    class Tick(
        val primary: ExoPlayer,
        val standby: ExoPlayer?,
        val current: PlayableTrack?,
        val currentItem: MediaItem?,
        val next: PlayableTrack?,
        val nextItem: MediaItem?,
        val positionMs: Long,
        val durationMs: Long,
    )

    private class Session(
        val outgoing: ExoPlayer,
        val incoming: ExoPlayer,
        val plan: MixPlan,
        val userParameters: PlaybackParameters,
        val outgoingEq: DeckEqStage?,
        val incomingEq: DeckEqStage?,
        val outgoingKicks: KickTap?,
        val incomingKicks: KickTap?,
        val startedAtMs: Long,
    ) {
        var handedOver = false
        var job: Job? = null
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _telemetry = MutableStateFlow(DeckBlendTelemetry.IDLE)
    val telemetry: StateFlow<DeckBlendTelemetry> = _telemetry.asStateFlow()

    private var settings = AutoMixSettings()
    val isEnabled: Boolean get() = settings.autoMixEnabled
    private var hardwareBypass = false
    private var jamActive = false
    private var transportPaused = false
    var baseVolume = 1f
        private set

    /** Both decks are audible and owned by AutoMix: neither may be re-armed or reused. */
    val isBlending: Boolean get() = session != null

    /** MusicPlayer hooks, installed once at start-up. */
    var onPerformDeckSwap: ((outgoing: ExoPlayer, incoming: ExoPlayer) -> Unit)? = null
    var onTransitionComplete: (() -> Unit)? = null
    var analysisInput: ((MediaItem) -> AnalysisInput?)? = null
    /** The deck's EQ stage and kick tap; null when its sink is not on the processed float path. */
    var deckSink: ((ExoPlayer) -> Pair<DeckEqStage, KickTap>?)? = null

    private var pairKey: String? = null
    private var decision: MixDecision? = null
    private var decisionInputs: String? = null
    private var cuedStandbyFor: String? = null
    private var session: Session? = null
    private var tempoRestore: Job? = null
    private var tempoOwned: Pair<ExoPlayer, PlaybackParameters>? = null
    private var swapping = false
    private var armedKicks: KickTap? = null
    /** The songs and analysis the current decision was made from, for the mix view. */
    private var planContext: PlanContext? = null

    private class PlanContext(
        val outgoing: PlayableTrack,
        val incoming: PlayableTrack,
        val outgoingDurationMs: Long,
        val outgoingSignature: AcousticSignature?,
        val incomingSignature: AcousticSignature?,
    )

    init {
        scope.launch {
            preferences.settings.collect { latest ->
                val changed = latest != settings
                settings = latest
                if (!latest.autoMixEnabled) endTransition() else if (changed && session == null) resetPlan()
            }
        }
    }

    fun setBaseVolume(volume: Float) {
        baseVolume = volume.coerceIn(0f, 1f)
    }

    /** Pause/resume from the transport: a blend holds still instead of finishing unheard. */
    fun setTransportPaused(paused: Boolean) {
        if (transportPaused == paused) return
        transportPaused = paused
        val active = session ?: return
        runCatching {
            if (paused) {
                active.outgoing.pause()
                active.incoming.pause()
            } else {
                active.outgoing.play()
                active.incoming.play()
            }
        }
    }

    fun updateHardwareBypassState(active: Boolean) {
        hardwareBypass = active
        if (active) endTransition()
    }

    /** Listen Together keeps every listener on the same song edge: no mixing. */
    fun setJamActive(active: Boolean) {
        jamActive = active
        if (active) endTransition()
    }

    /**
     * The speed and pitch the user chose for [player], while AutoMix is
     * stretching it for a beat match; null when AutoMix does not own its tempo.
     */
    fun userParametersFor(player: Player): PlaybackParameters? =
        session?.takeIf { it.incoming === player && it.plan.beatLocked }?.userParameters
            ?: tempoOwned?.takeIf { it.first === player }?.second

    fun onTick(tick: Tick) {
        if (session != null) return
        if (!isEnabled || hardwareBypass || jamActive) {
            resetPlan()
            return
        }
        val current = tick.current
        val currentItem = tick.currentItem
        val next = tick.next
        val nextItem = tick.nextItem
        if (current == null || currentItem == null || next == null || nextItem == null || tick.durationMs <= 0L ||
            tick.primary.repeatMode == Player.REPEAT_MODE_ONE || nextItem.mediaId == currentItem.mediaId
        ) {
            resetPlan()
            return
        }
        val outKey = analysisKey(currentItem)
        val inKey = analysisKey(nextItem)
        val pair = "$outKey>$inKey"
        if (pair != pairKey) {
            resetPlan()
            pairKey = pair
        }
        val remaining = tick.durationMs - tick.positionMs
        if (tick.positionMs < tick.durationMs / 4 && remaining > LOOK_AHEAD_MS) return

        requestAnalysis(outKey, current, currentItem)
        requestAnalysis(inKey, next, nextItem)
        val outSignature = analyzer.signature(outKey)
        val inSignature = analyzer.signature(inKey)
        // Mid-glide after a beatmix the deck runs at the matched tempo, not the user's.
        val userParameters = userParametersFor(tick.primary) ?: tick.primary.playbackParameters
        val inputs = "${outSignature != null}|${inSignature != null}|${tick.durationMs / 1_000}|" +
            "${userParameters.speed}|${userParameters.pitch}"
        val settled = analyzer.isSettled(outKey) && analyzer.isSettled(inKey)
        if (inputs != decisionInputs && (settled || remaining <= DECIDE_BY_REMAINING_MS)) {
            decisionInputs = inputs
            decision = TransitionPlanner.plan(
                PlanInput(
                    outgoing = current,
                    incoming = next,
                    outgoingDurationMs = tick.durationMs,
                    incomingDurationMs = inSignature?.durationMs ?: next.durationMs ?: 0L,
                    outgoingSignature = outSignature,
                    incomingSignature = inSignature,
                    settings = settings,
                    userSpeed = userParameters.speed,
                    userPitch = userParameters.pitch,
                ),
            ).also { logDecision(current, next, it) }
            planContext = PlanContext(current, next, tick.durationMs, outSignature, inSignature)
            cuedStandbyFor = null
        }
        publishPlan(next)

        val plan = decision as? MixPlan ?: return
        val standby = tick.standby ?: return
        if (standby.currentMediaItem?.mediaId != nextItem.mediaId) return
        val startIn = (plan.cueInMs - (if (plan.beatLocked) LOCK_LEAD_MS * plan.tempoRatio else 0f).toLong())
            .coerceAtLeast(0L)
        // Cue the standby while it is still idle so it is buffered at the cue point, not at 0:00.
        if (cuedStandbyFor != pair && !standby.isPlaying) {
            if (abs(standby.currentPosition - startIn) > CUE_TOLERANCE_MS) standby.seekTo(startIn)
            cuedStandbyFor = pair
            // The outgoing deck's kicks are measured ahead of the mix; the
            // incoming one's during the silent lock-in.
            if (plan.beatLocked) armedKicks = deckSink?.invoke(tick.primary)?.second?.also { it.enabled = true }
        }
        if (transportPaused || !tick.primary.playWhenReady || standby.playbackState != Player.STATE_READY) return
        val startOutAt = plan.cueOutMs - ((plan.cueInMs - startIn) / plan.tempoRatio).toLong()
        if (tick.positionMs < startOutAt) return
        val runPlan = if (tick.positionMs > plan.cueOutMs + LATE_START_TOLERANCE_MS) {
            latePlan(plan, tick.positionMs) ?: return
        } else {
            plan
        }
        // A beat-locked deck starts where the outgoing song actually is, not where the tick expected it.
        val from = if (runPlan.beatLocked) runPlan.incomingTimeFor(tick.positionMs).coerceAtLeast(0L) else runPlan.cueInMs
        start(tick.primary, standby, runPlan, from)
    }

    /** The user landed inside or past the window (seek, late resolve): a short clean fade from here. */
    private fun latePlan(plan: MixPlan, positionMs: Long): MixPlan? {
        val left = plan.endOutMs - positionMs
        if (left < MIN_LATE_FADE_MS) return null
        val late = MixPlan(
            cueOutMs = positionMs,
            cueInMs = plan.cueInMs,
            overlapMs = minOf(left, LATE_FADE_MS),
            shape = MixShape.EQUAL_POWER,
            style = com.lastwave.app.playback.automix.api.TransitionStyle.PERCEPTUAL_FADE,
        )
        decision = late
        return late
    }

    private fun start(outgoing: ExoPlayer, incoming: ExoPlayer, plan: MixPlan, startIn: Long) {
        finishTempoGlide()
        val userParameters = outgoing.playbackParameters
        val outSink = deckSink?.invoke(outgoing)
        val inSink = deckSink?.invoke(incoming)
        incoming.volume = 0f
        if (abs(incoming.currentPosition - startIn) > CUE_TOLERANCE_MS) incoming.seekTo(startIn)
        incoming.playbackParameters = if (plan.beatLocked) PlaybackParameters(plan.tempoRatio, 1f) else userParameters
        plan.bassSwapAtMs?.let { swapAt ->
            val rampUs = plan.bassSwapRampMs * 1000
            val swapInUs = plan.incomingTimeFor(swapAt) * 1000
            outSink?.first?.schedule(bass = EqAutomation(swapAt * 1000, 1f, 0f, rampUs), treble = null)
            // The incoming song enters on its mids: highs open over the first
            // half, the bass arrives on the swap downbeat.
            val trebleOpens = plan.shape == MixShape.BASS_SWAP
            inSink?.first?.schedule(
                bass = EqAutomation(swapInUs, 0f, 1f, (rampUs * plan.tempoRatio).toLong()),
                treble = if (trebleOpens) {
                    EqAutomation(plan.cueInMs * 1000, 0f, 1f, (swapInUs - plan.cueInMs * 1000).coerceAtLeast(1L))
                } else {
                    null
                },
            )
        }
        if (plan.beatLocked) {
            outSink?.second?.enabled = true
            inSink?.second?.enabled = true
        }
        incoming.play()
        val started = Session(
            outgoing, incoming, plan, userParameters,
            outSink?.first, inSink?.first, outSink?.second, inSink?.second,
            SystemClock.elapsedRealtime(),
        )
        session = started
        _telemetry.update {
            it.copy(
                phase = BlendPhase.BLENDING,
                blendProgress = 0f,
                activeTransitionStyle = plan.style,
                spliceStartPositionMs = plan.cueOutMs,
                spliceDurationMs = plan.overlapMs,
                syncedBpm = plan.bpm,
                harmonicKeyPair = plan.keyPair,
                isVocalProtected = plan.vocalSafe,
                view = mixView(plan),
            )
        }
        Log.i(TAG, "Blend start: ${describe(plan)} incomingAt=${startIn}ms")
        started.job = scope.launch { run(started) }
    }

    private suspend fun run(active: Session) {
        val lock = if (active.plan.beatLocked) PhaseLock(active.plan, active.outgoingKicks, active.incomingKicks) else null
        while (scope.isActive) {
            delay(TICK_MS)
            if (transportPaused) {
                lock?.reset()
                continue
            }
            val outgoing = active.outgoing
            val incoming = active.incoming
            val elapsed = SystemClock.elapsedRealtime() - active.startedAtMs
            val outgoingDone = outgoing.playbackState == Player.STATE_ENDED || outgoing.playerError != null
            if (incoming.playerError != null) {
                Log.w(TAG, "Incoming deck failed mid-blend", incoming.playerError)
                endTransition()
                return
            }
            val inPos = incoming.currentPosition
            // Progress follows the incoming deck's own clock: if it stalls
            // buffering, the blend waits instead of fading into silence.
            val progress = ((inPos - active.plan.cueInMs).toFloat() /
                (active.plan.overlapMs * active.plan.tempoRatio)).coerceIn(0f, 1f)
            lock?.update(outgoing, incoming, progress)
            val (outGain, inGain) = MixCurve.gains(progress, active.plan.shape)
            outgoing.volume = if (outgoingDone) 0f else outGain * baseVolume
            incoming.volume = inGain * baseVolume
            _telemetry.update { it.copy(blendProgress = progress) }
            if (!active.handedOver && (progress >= MixCurve.handOverPoint(active.plan.shape) || outgoingDone)) {
                handOver(active)
            }
            // A deck that never starts must not hold the queue forever.
            val overdue = elapsed > active.plan.overlapMs / active.plan.tempoRatio + LOCK_LEAD_MS + WATCHDOG_SLACK_MS
            if (progress >= 1f || overdue) break
        }
        complete(active)
    }

    private fun handOver(active: Session) {
        active.handedOver = true
        swapping = true
        try {
            onPerformDeckSwap?.invoke(active.outgoing, active.incoming)
        } catch (error: Exception) {
            Log.e(TAG, "Deck hand-over failed", error)
        } finally {
            swapping = false
        }
    }

    private fun complete(active: Session) {
        if (session !== active) return
        if (!active.handedOver) handOver(active)
        session = null
        stopKickTaps(active)
        silence(active.outgoing)
        active.incoming.volume = baseVolume
        active.outgoingEq?.clear()
        active.incomingEq?.restore()
        if (active.plan.beatLocked) glideTempo(active.incoming, active.userParameters)
        runCatching { onTransitionComplete?.invoke() }
        Log.i(TAG, "Blend complete")
        resetPlan(keepTelemetry = true)
        _telemetry.update { it.copy(phase = BlendPhase.MIX_COMPLETED, blendProgress = 1f) }
        scope.launch {
            delay(COMPLETED_BADGE_MS)
            if (session == null && _telemetry.value.phase == BlendPhase.MIX_COMPLETED) {
                _telemetry.value = DeckBlendTelemetry.IDLE
            }
        }
    }

    /**
     * Ends whatever AutoMix is doing, right now. Called for every
     * interruption (seek, skip, queue edit, speed, Cast, errors, release): a
     * blend past its hand-over is committed to the new song, one before it is
     * reverted to the old song at full level.
     */
    fun endTransition() {
        // The hand-over itself replays MusicPlayer's transition callbacks.
        if (swapping) return
        finishTempoGlide()
        val active = session
        if (active == null) {
            resetPlan()
            return
        }
        session = null
        active.job?.cancel()
        stopKickTaps(active)
        if (active.handedOver) {
            silence(active.outgoing)
            active.incoming.volume = baseVolume
            if (active.plan.beatLocked) runCatching { active.incoming.playbackParameters = active.userParameters }
            active.outgoingEq?.clear()
            active.incomingEq?.restore()
            runCatching { onTransitionComplete?.invoke() }
            Log.i(TAG, "Blend interrupted after hand-over: committed")
        } else {
            silence(active.incoming)
            runCatching { active.incoming.playbackParameters = active.userParameters }
            active.outgoing.volume = baseVolume
            active.incomingEq?.clear()
            active.outgoingEq?.restore()
            Log.i(TAG, "Blend interrupted before hand-over: reverted")
        }
        resetPlan()
    }

    private fun stopKickTaps(active: Session) {
        active.outgoingKicks?.enabled = false
        active.incomingKicks?.enabled = false
    }

    private fun silence(deck: ExoPlayer) {
        runCatching {
            deck.volume = 0f
            deck.pause()
        }
    }

    /** Eases the incoming deck from the matched tempo back to the user's speed, too slowly to hear. */
    private fun glideTempo(deck: ExoPlayer, target: PlaybackParameters) {
        val from = deck.playbackParameters.speed
        if (abs(from - target.speed) < 0.001f) {
            runCatching { deck.playbackParameters = target }
            return
        }
        tempoOwned = deck to target
        tempoRestore = scope.launch {
            for (step in 1..GLIDE_STEPS) {
                delay(GLIDE_STEP_MS)
                val speed = from + (target.speed - from) * step / GLIDE_STEPS
                runCatching { deck.playbackParameters = PlaybackParameters(speed, target.pitch) }
            }
            tempoOwned = null
        }
    }

    private fun finishTempoGlide() {
        val owned = tempoOwned ?: return
        tempoRestore?.cancel()
        tempoOwned = null
        runCatching { owned.first.playbackParameters = owned.second }
    }

    private fun requestAnalysis(key: String, track: PlayableTrack, item: MediaItem) {
        if (analyzer.isSettled(key)) return
        val input = analysisInput?.invoke(item) ?: return
        analyzer.request(key, track, input)
    }

    private fun publishPlan(next: PlayableTrack) {
        val preview = TrackChromeSummary(
            title = next.title,
            artist = next.artist,
            artworkUrl = next.artworkUrl,
            durationMs = next.durationMs ?: 0L,
        )
        _telemetry.value = when (val current = decision) {
            null -> DeckBlendTelemetry(phase = BlendPhase.PRE_ANALYZING, incomingTrackPreview = preview)
            is MixBypass -> DeckBlendTelemetry.IDLE
            is MixPlan -> DeckBlendTelemetry(
                phase = BlendPhase.ARMED,
                activeTransitionStyle = current.style,
                syncedBpm = current.bpm,
                harmonicKeyPair = current.keyPair,
                spliceStartPositionMs = current.cueOutMs,
                spliceDurationMs = current.overlapMs,
                incomingTrackPreview = preview,
                isVocalProtected = current.vocalSafe,
                view = mixView(current),
            )
        }
    }

    private fun resetPlan(keepTelemetry: Boolean = false) {
        armedKicks?.enabled = false
        armedKicks = null
        pairKey = null
        decision = null
        decisionInputs = null
        cuedStandbyFor = null
        planContext = null
        if (!keepTelemetry && _telemetry.value.phase != BlendPhase.MIX_COMPLETED) {
            _telemetry.value = DeckBlendTelemetry.IDLE
        }
    }

    /** Null unless both songs have analysed waveforms around their cue points. */
    private fun mixView(plan: MixPlan): MixView? {
        val context = planContext ?: return null
        val outro = context.outgoingSignature?.outro?.takeIf { it.bands.isNotEmpty() } ?: return null
        val intro = context.incomingSignature?.intro?.takeIf { it.bands.isNotEmpty() } ?: return null
        fun deck(track: PlayableTrack, durationMs: Long, window: WindowAnalysis, bpm: Float?) = MixDeckView(
            title = track.title,
            artist = track.artist,
            artworkUrl = track.artworkUrl,
            durationMs = durationMs,
            bpm = bpm,
            camelotKey = window.camelotKey?.takeIf { window.keyConfidence >= KEY_DISPLAY_CONFIDENCE },
            bandsStartMs = window.startMs,
            bands = window.bands,
        )
        return MixView(
            outgoing = deck(context.outgoing, context.outgoingDurationMs, outro, outro.grid?.takeIf { it.confidence >= BPM_DISPLAY_CONFIDENCE }?.bpm),
            incoming = deck(
                context.incoming,
                context.incomingSignature.durationMs,
                intro,
                intro.grid?.takeIf { it.confidence >= BPM_DISPLAY_CONFIDENCE }?.bpm,
            ),
            cueOutMs = plan.cueOutMs,
            cueInMs = plan.cueInMs,
            overlapMs = plan.overlapMs,
            tempoRatio = plan.tempoRatio,
            shape = plan.shape,
            bassSwapAtMs = plan.bassSwapAtMs,
            bassSwapRampMs = plan.bassSwapRampMs,
            downbeatMs = plan.outGrid?.downbeatMs,
            barMs = plan.outGrid?.barMs,
            bars = plan.bars,
        )
    }

    private fun logDecision(outgoing: PlayableTrack, incoming: PlayableTrack, decision: MixDecision) {
        val detail = when (decision) {
            is MixBypass -> "bypass (${decision.reason})"
            is MixPlan -> describe(decision)
        }
        Log.i(TAG, "Plan '${outgoing.title}' -> '${incoming.title}': $detail")
    }

    private fun describe(plan: MixPlan) =
        "${plan.style} ${plan.shape} out=${plan.cueOutMs}ms in=${plan.cueInMs}ms len=${plan.overlapMs}ms " +
            "tempo=${plan.tempoRatio} bpm=${plan.bpm} keys=${plan.keyPair} bassSwap=${plan.bassSwapAtMs} " +
            "vocalSafe=${plan.vocalSafe}"

    /**
     * Keeps the incoming deck on the outgoing deck's beat. ExoPlayer positions
     * on both decks follow their own audio clocks, so the gap between the
     * expected and actual incoming position is the audible flam. A large gap
     * is fixed by a seek while the incoming deck is still near silent; the
     * rest by nudging its speed at most 1.5 %.
     *
     * The target is corrected by where each deck's kicks really land in its
     * playback ([KickTap]) relative to its analysed grid, so a fixed offset
     * between the analysis decode and playback (codec priming) cannot flam.
     */
    private class PhaseLock(
        private val plan: MixPlan,
        private val outgoingKicks: KickTap?,
        private val incomingKicks: KickTap?,
    ) {
        private var smoothed = 0.0
        private var samples = 0
        private var seeks = 0
        private var lastSpeed = plan.tempoRatio
        private var givenUp = false
        private var badSinceMs = 0L
        /** How long a seek takes to become audible; learned from the first seek's miss. */
        private var seekLatencyMs = 0.0
        private var measuringSeek = false
        private var correctionMs = 0.0
        private var loggedCorrection = false

        fun reset() {
            samples = 0
        }

        fun update(outgoing: ExoPlayer, incoming: ExoPlayer, progress: Float) {
            if (givenUp || progress >= RELEASE_PROGRESS) {
                setSpeed(incoming, plan.tempoRatio)
                return
            }
            if (!incoming.isPlaying || !outgoing.isPlaying) {
                samples = 0
                return
            }
            updateCorrection()
            val error = incoming.currentPosition - plan.incomingTimeFor(outgoing.currentPosition) - correctionMs
            smoothed = if (samples == 0) error else smoothed * 0.7 + error * 0.3
            if (++samples < SETTLE_SAMPLES) return
            if (measuringSeek) {
                // The outgoing deck kept moving while the seek refilled: that delay shows as a lag.
                measuringSeek = false
                seekLatencyMs = (seekLatencyMs - smoothed / plan.tempoRatio).coerceIn(0.0, MAX_SEEK_LATENCY_MS)
            }
            val now = SystemClock.elapsedRealtime()
            if (abs(smoothed) > SEEK_THRESHOLD_MS && seeks < MAX_SEEKS && progress < SEEK_WINDOW_PROGRESS) {
                seeks++
                samples = 0
                measuringSeek = true
                val target = incoming.currentPosition - smoothed + seekLatencyMs * plan.tempoRatio
                incoming.seekTo(target.toLong().coerceAtLeast(0L))
                return
            }
            if (abs(smoothed) > GIVE_UP_MS) {
                if (badSinceMs == 0L) badSinceMs = now
                if (now - badSinceMs > GIVE_UP_AFTER_MS) {
                    Log.w(TAG, "Beat lock lost (${smoothed.toInt()}ms); continuing as a plain fade")
                    givenUp = true
                }
            } else {
                badSinceMs = 0L
            }
            val correction = if (abs(smoothed) < DEADBAND_MS) 0.0 else (-smoothed / CORRECTION_HORIZON_MS).coerceIn(-MAX_NUDGE, MAX_NUDGE)
            setSpeed(incoming, (plan.tempoRatio * (1.0 + correction)).toFloat())
        }

        /**
         * Incoming target shift: real incoming kicks sit [inOffset] after its
         * grid beats, real outgoing kicks [outOffset] after theirs, so aligning
         * real kicks moves the incoming target by inOffset - outOffset * ratio.
         */
        private fun updateCorrection() {
            val outGrid = plan.outGrid ?: return
            val inGrid = plan.inGrid ?: return
            val inOffset = KickTiming.offsetMs(incomingKicks?.onsetsSince(0L), inGrid) ?: return
            val outOffset = KickTiming.offsetMs(outgoingKicks?.onsetsSince(0L), outGrid) ?: return
            correctionMs = inOffset - outOffset * plan.tempoRatio
            if (!loggedCorrection) {
                loggedCorrection = true
                Log.i(TAG, "Live kick correction ${"%.1f".format(correctionMs)}ms (in=${"%.1f".format(inOffset)} out=${"%.1f".format(outOffset)})")
            }
        }

        private fun setSpeed(incoming: ExoPlayer, speed: Float) {
            if (abs(speed - lastSpeed) < MIN_SPEED_STEP) return
            lastSpeed = speed
            runCatching { incoming.playbackParameters = PlaybackParameters(speed, 1f) }
        }

        private companion object {
            const val SETTLE_SAMPLES = 8
            const val SEEK_THRESHOLD_MS = 25.0
            const val MAX_SEEKS = 3
            const val MAX_SEEK_LATENCY_MS = 400.0
            const val SEEK_WINDOW_PROGRESS = 0.15f
            const val RELEASE_PROGRESS = 0.9f
            const val DEADBAND_MS = 4.0
            const val CORRECTION_HORIZON_MS = 1_500.0
            const val MAX_NUDGE = 0.015
            const val MIN_SPEED_STEP = 0.001f
            const val GIVE_UP_MS = 120.0
            const val GIVE_UP_AFTER_MS = 4_000L
        }
    }

    companion object {
        private const val TAG = "AutoMix"
        private const val TICK_MS = 20L
        /** Analysis starts at 25 % of the song or this close to its end. */
        private const val LOOK_AHEAD_MS = 90_000L
        /** Pending analysis gives way to a safe plan once the song is this close to its end. */
        private const val DECIDE_BY_REMAINING_MS = 35_000L
        /** Beat-locked mixes start the silent incoming deck early to settle the lock. */
        private const val LOCK_LEAD_MS = 3_000L
        private const val CUE_TOLERANCE_MS = 40L
        private const val LATE_START_TOLERANCE_MS = 1_000L
        private const val MIN_LATE_FADE_MS = 2_000L
        private const val LATE_FADE_MS = 6_000L
        private const val WATCHDOG_SLACK_MS = 20_000L
        private const val COMPLETED_BADGE_MS = 1_500L
        private const val GLIDE_STEPS = 40
        private const val GLIDE_STEP_MS = 100L
        private const val BPM_DISPLAY_CONFIDENCE = 0.6f
        private const val KEY_DISPLAY_CONFIDENCE = 0.5f

        /** One stream, one analysis: the cache key the player itself uses, else the media id. */
        fun analysisKey(item: MediaItem): String = item.localConfiguration?.customCacheKey ?: item.mediaId
    }
}
