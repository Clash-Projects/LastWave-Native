package com.lastwave.app.playback.haptics

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.audiofx.HapticGenerator
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.roundToInt

@Singleton
class MusicHapticsController @Inject constructor(
    @ApplicationContext private val context: Context
) : HapticPulseListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        vm?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager

    private val hapticThread = HandlerThread("LastWave-MusicHaptics", Thread.NORM_PRIORITY).apply {
        start()
    }
    private val hapticHandler = Handler(hapticThread.looper)

    /**
     * Every tap ever created, held weakly so released players drop out.
     * An AudioProcessor owns per-stream filter state and output buffers, so each
     * DefaultAudioSink needs its own instance. Sharing one between the
     * enhanced/fallback sinks and the crossfade standby player corrupted the
     * filters (two playback threads) and let the muted standby fire pulses.
     */
    private val allTaps: MutableSet<MusicHapticsAudioProcessor> =
        Collections.synchronizedSet(
            Collections.newSetFromMap(WeakHashMap<MusicHapticsAudioProcessor, Boolean>())
        )
    private val tapsByPlayer: MutableMap<Player, List<MusicHapticsAudioProcessor>> =
        Collections.synchronizedMap(WeakHashMap())
    @Volatile private var activeTaps: List<MusicHapticsAudioProcessor> = emptyList()
    @Volatile private var dspActive: Boolean = false
    private var boundPlayer: WeakReference<Player>? = null

    /** Pending beat-aligned pulses carry this token so they can be dropped at once. */
    private val pulseToken = Any()
    @Volatile private var lastPulseUptimeMs = 0L

    private var hapticGenerator: HapticGenerator? = null
    private var currentAudioSessionId: Int = C.AUDIO_SESSION_ID_UNSET
    private var isPlayerPlaying: Boolean = false
    private var isUsbExclusiveActive: Boolean = false
    private var isCastingOrAuto: Boolean = false
    private var sessionStabilizeJob: Job? = null

    private val _config = MutableStateFlow(MusicHapticsConfig())
    val config: StateFlow<MusicHapticsConfig> = _config.asStateFlow()

    /** Battery saver can toggle mid-song; re-evaluate instead of only at play/pause. */
    private val powerSaveReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            scope.launch { reconcileEngineState() }
        }
    }

    init {
        runCatching {
            context.registerReceiver(
                powerSaveReceiver,
                IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            )
        }
    }

    private val supportsPrimitiveThud: Boolean by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && vibrator != null) {
            vibrator.areAllPrimitivesSupported(
                VibrationEffect.Composition.PRIMITIVE_THUD,
                VibrationEffect.Composition.PRIMITIVE_LOW_TICK,
                VibrationEffect.Composition.PRIMITIVE_TICK
            )
        } else {
            false
        }
    }

    private val supportsPrimitiveTick: Boolean by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && vibrator != null) {
            vibrator.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_TICK)
        } else {
            false
        }
    }

    private val hasAmplitudeControl: Boolean by lazy {
        runCatching { vibrator?.hasAmplitudeControl() == true }.getOrDefault(false)
    }

    fun isDeviceHapticsCapable(): Boolean = vibrator?.hasVibrator() == true

    fun isHardwareHapticGeneratorAvailable(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            runCatching { HapticGenerator.isAvailable() }.getOrDefault(false)
    }

    /** Creates a fresh tap for one audio sink. Call once per DefaultAudioSink. */
    fun createAudioProcessor(): MusicHapticsAudioProcessor =
        MusicHapticsAudioProcessor(this).also { allTaps.add(it) }

    /** Associates the taps installed in [player]'s sinks with that player. */
    fun registerPlayerTaps(player: Player, taps: List<MusicHapticsAudioProcessor>) {
        tapsByPlayer[player] = taps.toList()
        if (boundPlayer?.get() === player) {
            activeTaps = taps.toList()
            reconcileEngineState()
        }
    }

    /** Seek or other non-gapless discontinuity on the bound player. */
    fun onPositionDiscontinuity() {
        activeTaps.forEach { it.requestTimelineReset() }
        clearPendingPulses()
    }

    fun updateConfig(newConfig: MusicHapticsConfig) {
        _config.value = newConfig
        reconcileEngineState()
    }

    fun setUsbExclusiveModeActive(active: Boolean) {
        if (isUsbExclusiveActive != active) {
            isUsbExclusiveActive = active
            reconcileEngineState()
        }
    }

    fun setCastingOrAutoActive(active: Boolean) {
        if (isCastingOrAuto != active) {
            isCastingOrAuto = active
            reconcileEngineState()
        }
    }

    fun bindToPlayer(player: Player) {
        boundPlayer = WeakReference(player)
        activeTaps = tapsByPlayer[player].orEmpty()
        clearPendingPulses()
        currentAudioSessionId = (player as? ExoPlayer)?.audioSessionId ?: C.AUDIO_SESSION_ID_UNSET
        isPlayerPlaying = player.isPlaying && player.volume > 0f
        reconcileEngineState()
    }

    fun onAudioSessionIdChanged(newSessionId: Int) {
        if (currentAudioSessionId == newSessionId) return
        currentAudioSessionId = newSessionId
        releaseHardwareGenerator()
        // Delay 120ms before enabling hardware HapticGenerator to prevent Android 12 volume spike bug
        sessionStabilizeJob?.cancel()
        sessionStabilizeJob = scope.launch {
            delay(120L)
            reconcileEngineState()
        }
    }

    fun onIsPlayingChanged(isPlaying: Boolean, playerVolume: Float = 1f) {
        isPlayerPlaying = isPlaying && playerVolume > 0f
        reconcileEngineState()
    }

    private fun shouldRunHaptics(): Boolean {
        val cfg = _config.value
        if (!cfg.enabled || !isDeviceHapticsCapable()) return false
        if (!isPlayerPlaying || isUsbExclusiveActive || isCastingOrAuto) return false
        if (cfg.pauseOnBatterySaver && powerManager?.isPowerSaveMode == true) return false
        return true
    }

    private fun shouldUseHardwareGenerator(): Boolean {
        val cfg = _config.value
        if (!isHardwareHapticGeneratorAvailable()) return false
        if (currentAudioSessionId == C.AUDIO_SESSION_ID_UNSET || currentAudioSessionId <= 0) return false
        return when (cfg.engineMode) {
            MusicHapticsEngineMode.HARDWARE_NATIVE -> true
            MusicHapticsEngineMode.DSP_PRECISION -> false
            // HapticGenerator only vibrates when the AudioTrack's haptic channels
            // are unmuted; Media3 never unmutes them (platform default is muted),
            // so Automatic silently produced nothing on most phones. The DSP
            // engine works everywhere and honours mode/intensity.
            MusicHapticsEngineMode.AUTO -> false
        }
    }

    private fun setDspActive(active: Boolean) {
        dspActive = active
        val live = activeTaps
        synchronized(allTaps) {
            allTaps.forEach { tap -> tap.isAnalysisActive = active && live.contains(tap) }
        }
        if (!active) clearPendingPulses()
    }

    private fun clearPendingPulses() {
        hapticHandler.removeCallbacksAndMessages(pulseToken)
    }

    @Synchronized
    private fun reconcileEngineState() {
        val active = shouldRunHaptics()
        if (!active) {
            setDspActive(false)
            disableHardwareGenerator()
            runCatching { vibrator?.cancel() }
            return
        }

        if (shouldUseHardwareGenerator()) {
            setDspActive(false)
            enableHardwareGenerator(currentAudioSessionId)
        } else {
            disableHardwareGenerator()
            setDspActive(true)
        }
    }

    private fun enableHardwareGenerator(sessionId: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        try {
            if (hapticGenerator == null) {
                hapticGenerator = HapticGenerator.create(sessionId)
            }
            hapticGenerator?.enabled = true
        } catch (_: Throwable) {
            // Fallback seamlessly to DSP processor if OEM HAL rejects HapticGenerator
            releaseHardwareGenerator()
            setDspActive(true)
        }
    }

    private fun disableHardwareGenerator() {
        try {
            hapticGenerator?.enabled = false
        } catch (_: Throwable) {
        }
    }

    private fun releaseHardwareGenerator() {
        try {
            hapticGenerator?.enabled = false
            hapticGenerator?.release()
        } catch (_: Throwable) {
        } finally {
            hapticGenerator = null
        }
    }

    override fun onAnalysisFlushed() {
        clearPendingPulses()
    }

    override fun onHapticPulse(
        bassEnergy: Float,
        vocalEnergy: Float,
        isSharpTransient: Boolean,
        leadTimeMs: Long,
    ) {
        if (!dspActive) return
        val cfg = _config.value

        val rawDrive: Float = when (cfg.mode) {
            MusicHapticsMode.BASS_AND_BEATS -> bassEnergy
            MusicHapticsMode.VOCALS_ONLY -> vocalEnergy
            MusicHapticsMode.WHOLE_SONG -> max(bassEnergy, vocalEnergy * 0.85f)
        }
        if (rawDrive < 0.08f) return

        val isBassDominant = cfg.mode != MusicHapticsMode.VOCALS_ONLY && (bassEnergy >= vocalEnergy)
        // The tap sees PCM when it is written to the AudioTrack, ~100-500 ms
        // before it is audible. Fire when the sound actually reaches the ear.
        val fireAt = SystemClock.uptimeMillis() + leadTimeMs.coerceAtLeast(0L)
        hapticHandler.postAtTime({
            if (!dspActive) return@postAtTime
            // Keep a minimum gap between motor kicks so pulses never smear together.
            val now = SystemClock.uptimeMillis()
            if (now - lastPulseUptimeMs < MIN_PULSE_SPACING_MS) return@postAtTime
            lastPulseUptimeMs = now
            dispatchVibrationPulse(
                drive = rawDrive,
                isBassDominant = isBassDominant,
                isSharpTransient = isSharpTransient,
                intensity = cfg.intensity
            )
        }, pulseToken, fireAt)
    }

    fun triggerPreviewPulse(intensity: MusicHapticsIntensity = _config.value.intensity) {
        hapticHandler.post {
            dispatchVibrationPulse(
                drive = 0.85f,
                isBassDominant = true,
                isSharpTransient = true,
                intensity = intensity
            )
        }
    }

    private fun dispatchVibrationPulse(
        drive: Float,
        isBassDominant: Boolean,
        isSharpTransient: Boolean,
        intensity: MusicHapticsIntensity
    ) {
        val vib = vibrator ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && supportsPrimitiveThud) {
                val scale = (drive * intensity.primitiveScale).coerceIn(0.08f, 1.0f)
                val primitiveId = when {
                    isBassDominant && isSharpTransient -> VibrationEffect.Composition.PRIMITIVE_THUD
                    isBassDominant -> VibrationEffect.Composition.PRIMITIVE_LOW_TICK
                    else -> VibrationEffect.Composition.PRIMITIVE_TICK
                }
                val effect = VibrationEffect.startComposition()
                    .addPrimitive(primitiveId, scale)
                    .compose()
                vibrateAsMedia(vib, effect)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && supportsPrimitiveTick && !isBassDominant) {
                val scale = (drive * intensity.primitiveScale).coerceIn(0.10f, 1.0f)
                val effect = VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, scale)
                    .compose()
                vibrateAsMedia(vib, effect)
            } else if (hasAmplitudeControl) {
                val amplitude = (drive * 255f * intensity.amplitudeScale)
                    .roundToInt()
                    .coerceIn(1, 255)
                val durationMs = when {
                    isBassDominant && isSharpTransient -> 18L
                    isBassDominant -> 24L
                    else -> 12L
                }
                vibrateAsMedia(vib, VibrationEffect.createOneShot(durationMs, amplitude))
            } else {
                // No amplitude control: a one-shot always runs at full strength,
                // which made Light identical to Strong. Use the tuned predefined
                // effects and let drive x intensity choose softer or harder ones.
                val strength = drive * intensity.amplitudeScale
                if (strength < 0.22f) return
                val effectId = when {
                    isBassDominant && isSharpTransient && strength > 0.55f -> VibrationEffect.EFFECT_HEAVY_CLICK
                    isBassDominant && strength > 0.35f -> VibrationEffect.EFFECT_CLICK
                    else -> VibrationEffect.EFFECT_TICK
                }
                vibrateAsMedia(vib, VibrationEffect.createPredefined(effectId))
            }
        } catch (_: Throwable) {
            // Never crash audio playback on OEM Vibrator service exceptions
        }
    }

    /** Tags pulses as media vibration so the system "media vibration" setting scales or mutes them. */
    private fun vibrateAsMedia(vib: Vibrator, effect: VibrationEffect) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            vib.vibrate(
                effect,
                VibrationAttributes.Builder().setUsage(VibrationAttributes.USAGE_MEDIA).build()
            )
        } else {
            @Suppress("DEPRECATION")
            vib.vibrate(effect, MEDIA_AUDIO_ATTRIBUTES)
        }
    }

    fun release() {
        sessionStabilizeJob?.cancel()
        setDspActive(false)
        releaseHardwareGenerator()
        runCatching { vibrator?.cancel() }
        runCatching { context.unregisterReceiver(powerSaveReceiver) }
        hapticThread.quitSafely()
    }

    private companion object {
        const val MIN_PULSE_SPACING_MS = 35L
        val MEDIA_AUDIO_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
    }
}
