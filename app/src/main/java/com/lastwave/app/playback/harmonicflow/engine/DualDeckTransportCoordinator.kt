package com.lastwave.app.playback.harmonicflow.engine

import android.content.Context
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.lastwave.app.data.model.PlayableTrack
import com.lastwave.app.playback.harmonicflow.analysis.AcousticSignatureExtractor
import com.lastwave.app.playback.harmonicflow.api.BlendPhase
import com.lastwave.app.playback.harmonicflow.api.DeckBlendTelemetry
import com.lastwave.app.playback.harmonicflow.api.HarmonicFlowController
import com.lastwave.app.playback.harmonicflow.api.HarmonicFlowPreferences
import com.lastwave.app.playback.harmonicflow.api.HarmonicFlowSettings
import com.lastwave.app.playback.harmonicflow.api.TrackChromeSummary
import com.lastwave.app.playback.harmonicflow.api.TransitionStyle
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DualDeckTransportCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: HarmonicFlowPreferences,
    private val signatureExtractor: AcousticSignatureExtractor,
) : HarmonicFlowController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _telemetry = MutableStateFlow(DeckBlendTelemetry.IDLE)
    override val telemetry: StateFlow<DeckBlendTelemetry> = _telemetry.asStateFlow()

    private var currentSettings = HarmonicFlowSettings()
    private var isHardwareBypassEngaged = false
    private var isJamRoomActive = false

    private var activeSpliceJob: Job? = null
    private var currentSplicePlan: SplicePlan? = null

    // Track reference callbacks into MusicPlayer
    var onPerformDeckSwap: ((outgoing: ExoPlayer, incoming: ExoPlayer) -> Unit)? = null
    var onTransitionComplete: (() -> Unit)? = null

    init {
        scope.launch {
            preferences.settings.collectLatest { settings ->
                currentSettings = settings
                if (!settings.harmonicFlowEnabled) {
                    cancelTransition(revertToPrimary = true)
                }
            }
        }
    }

    fun onTrackProgressTick(
        primaryPlayer: ExoPlayer,
        secondaryPlayer: ExoPlayer?,
        currentTrack: PlayableTrack?,
        nextTrack: PlayableTrack?,
        positionMs: Long,
        durationMs: Long,
    ) {
        if (!currentSettings.harmonicFlowEnabled || isHardwareBypassEngaged || isJamRoomActive) {
            if (_telemetry.value.phase != BlendPhase.IDLE) {
                _telemetry.value = DeckBlendTelemetry.IDLE
            }
            return
        }

        if (currentTrack == null || durationMs <= 0L) return

        // 1. Look-ahead pre-analysis trigger: 25% progress or 90s remaining
        val remainingMs = durationMs - positionMs
        if ((positionMs > durationMs * 0.25f || remainingMs < 90_000L) && nextTrack != null) {
            if (_telemetry.value.phase == BlendPhase.IDLE) {
                _telemetry.update {
                    it.copy(
                        phase = BlendPhase.PRE_ANALYZING,
                        incomingTrackPreview = TrackChromeSummary(
                            title = nextTrack.title,
                            artist = nextTrack.artist,
                            artworkUrl = nextTrack.artworkUri?.toString(),
                            durationMs = nextTrack.durationMs ?: 0L,
                        )
                    )
                }

                signatureExtractor.preAnalyzeTrack(nextTrack, nextTrack.playbackUrl) { sig ->
                    val plan = SpliceTrajectorySynthesizer.synthesizePlan(
                        outgoingTrack = currentTrack,
                        outgoingSignature = null,
                        incomingTrack = nextTrack,
                        incomingSignature = sig,
                        settings = currentSettings,
                    )
                    currentSplicePlan = plan

                    if (!plan.isBypass) {
                        _telemetry.update {
                            it.copy(
                                phase = BlendPhase.ARMED,
                                spliceStartPositionMs = plan.cueOutStartMs,
                                spliceDurationMs = plan.durationMs,
                                activeTransitionStyle = plan.transitionStyle,
                                harmonicKeyPair = sig.camelotKey.toString(),
                                syncedBpm = sig.beatGrid.bpm.value,
                            )
                        }
                    } else {
                        _telemetry.update { it.copy(phase = BlendPhase.IDLE) }
                    }
                }
            }
        }

        // 2. Trigger Splice at Splice Horizon
        val plan = currentSplicePlan
        if (plan != null && !plan.isBypass && _telemetry.value.phase == BlendPhase.ARMED) {
            if (positionMs >= plan.cueOutStartMs && secondaryPlayer != null) {
                startBlendExecution(primaryPlayer, secondaryPlayer, plan)
            }
        }
    }

    private fun startBlendExecution(
        primaryPlayer: ExoPlayer,
        secondaryPlayer: ExoPlayer,
        plan: SplicePlan,
    ) {
        activeSpliceJob?.cancel()
        val durationMs = plan.durationMs.coerceAtLeast(1000L)

        _telemetry.update {
            it.copy(
                phase = if (plan.requiresPhraseLoop) BlendPhase.LOOP_LOCKED else BlendPhase.BLENDING,
                blendProgress = 0.0f,
                activeTransitionStyle = plan.transitionStyle,
                loopBarsActive = if (plan.requiresPhraseLoop) plan.loopBarsCount else null,
            )
        }

        // Safely reset any manual speed/pitch shifts during transition
        primaryPlayer.setPlaybackSpeed(1.0f)
        secondaryPlayer.setPlaybackSpeed(1.0f)

        secondaryPlayer.seekTo(plan.cueInStartMs)
        secondaryPlayer.play()

        activeSpliceJob = scope.launch {
            val startTime = System.currentTimeMillis()
            var swapped = false

            while (isActive) {
                val elapsed = System.currentTimeMillis() - startTime
                val progress = (elapsed.toFloat() / durationMs.toFloat()).coerceIn(0.0f, 1.0f)

                _telemetry.update { it.copy(blendProgress = progress) }

                // Equal-power volume curve application
                val gainOut = kotlin.math.cos(progress * Math.PI * 0.5).toFloat()
                val gainIn = kotlin.math.sin(progress * Math.PI * 0.5).toFloat()
                primaryPlayer.volume = gainOut
                secondaryPlayer.volume = gainIn

                // Dominant energy handoff point (65% through transition window):
                // Perform atomic deck swap and hand over primary role
                if (progress >= 0.65f && !swapped) {
                    swapped = true
                    onPerformDeckSwap?.invoke(primaryPlayer, secondaryPlayer)
                }

                if (progress >= 1.0f) break
                delay(20L)
            }

            primaryPlayer.volume = 0.0f
            secondaryPlayer.volume = 1.0f
            primaryPlayer.pause()

            _telemetry.update {
                it.copy(
                    phase = BlendPhase.MIX_COMPLETED,
                    blendProgress = 1.0f,
                )
            }

            onTransitionComplete?.invoke()

            // Display MIXED ✓ for 1,500 ms before settling to IDLE
            delay(1_500L)
            _telemetry.update { DeckBlendTelemetry.IDLE }
            currentSplicePlan = null
        }
    }

    override fun cancelTransition(revertToPrimary: Boolean) {
        activeSpliceJob?.cancel()
        activeSpliceJob = null
        currentSplicePlan = null
        _telemetry.update { DeckBlendTelemetry.IDLE }
    }

    override fun forceCommitTransition() {
        activeSpliceJob?.cancel()
        activeSpliceJob = null
        _telemetry.update {
            it.copy(
                phase = BlendPhase.MIX_COMPLETED,
                blendProgress = 1.0f,
            )
        }
        scope.launch {
            delay(1_500L)
            _telemetry.update { DeckBlendTelemetry.IDLE }
            currentSplicePlan = null
        }
    }

    override fun triggerImmediateTransition(customDurationMs: Long?) {
        val duration = customDurationMs ?: (currentSettings.targetDurationSeconds * 1000L)
        val plan = SplicePlan(
            cueOutStartMs = 0L,
            cueInStartMs = 0L,
            durationMs = duration,
            transitionStyle = TransitionStyle.HARMONIC_BEATMIX,
        )
        currentSplicePlan = plan
    }

    override fun handleSeekEvent(targetPositionMs: Long) {
        val plan = currentSplicePlan ?: return
        if (_telemetry.value.phase == BlendPhase.BLENDING || _telemetry.value.phase == BlendPhase.LOOP_LOCKED) {
            if (targetPositionMs < plan.cueOutStartMs) {
                // Scrubbing before splice start: cancel incoming blend and restore primary to unity
                cancelTransition(revertToPrimary = true)
            } else if (targetPositionMs >= plan.cueOutStartMs + (plan.durationMs * 0.65f)) {
                // Scrubbing past dominant handoff: immediately commit transition
                forceCommitTransition()
            }
        }
    }

    override fun updateHardwareBypassState(isHardwareBypassActive: Boolean) {
        isHardwareBypassEngaged = isHardwareBypassActive
        if (isHardwareBypassActive) {
            cancelTransition(revertToPrimary = true)
        }
    }

    fun setJamRoomActive(active: Boolean) {
        isJamRoomActive = active
        if (active) {
            cancelTransition(revertToPrimary = true)
        }
    }
}
