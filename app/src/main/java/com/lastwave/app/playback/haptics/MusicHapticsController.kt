package com.lastwave.app.playback.haptics

import android.content.Context
import android.media.audiofx.HapticGenerator
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
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

    val audioProcessor = MusicHapticsAudioProcessor(this)

    private var hapticGenerator: HapticGenerator? = null
    private var currentAudioSessionId: Int = C.AUDIO_SESSION_ID_UNSET
    private var isPlayerPlaying: Boolean = false
    private var isUsbExclusiveActive: Boolean = false
    private var isCastingOrAuto: Boolean = false
    private var sessionStabilizeJob: Job? = null

    private val _config = MutableStateFlow(MusicHapticsConfig())
    val config: StateFlow<MusicHapticsConfig> = _config.asStateFlow()

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

    fun isDeviceHapticsCapable(): Boolean = vibrator?.hasVibrator() == true

    fun isHardwareHapticGeneratorAvailable(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            runCatching { HapticGenerator.isAvailable() }.getOrDefault(false)
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
            MusicHapticsEngineMode.AUTO -> {
                // Use hardware HapticGenerator only for standard Whole Song + Medium intensity;
                // use custom DSP whenever Bass-only, Vocals-only, or custom Light/Strong scaling is chosen.
                cfg.mode == MusicHapticsMode.WHOLE_SONG &&
                    cfg.intensity == MusicHapticsIntensity.MEDIUM
            }
        }
    }

    private fun reconcileEngineState() {
        val active = shouldRunHaptics()
        if (!active) {
            audioProcessor.isAnalysisActive = false
            disableHardwareGenerator()
            vibrator?.cancel()
            return
        }

        if (shouldUseHardwareGenerator()) {
            audioProcessor.isAnalysisActive = false
            enableHardwareGenerator(currentAudioSessionId)
        } else {
            disableHardwareGenerator()
            audioProcessor.isAnalysisActive = true
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
            audioProcessor.isAnalysisActive = true
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

    override fun onHapticPulse(
        bassEnergy: Float,
        vocalEnergy: Float,
        isSharpTransient: Boolean
    ) {
        if (!audioProcessor.isAnalysisActive) return
        val cfg = _config.value

        val rawDrive: Float = when (cfg.mode) {
            MusicHapticsMode.BASS_AND_BEATS -> bassEnergy
            MusicHapticsMode.VOCALS_ONLY -> vocalEnergy
            MusicHapticsMode.WHOLE_SONG -> max(bassEnergy, vocalEnergy * 0.85f)
        }
        if (rawDrive < 0.08f) return

        hapticHandler.post {
            dispatchVibrationPulse(
                drive = rawDrive,
                isBassDominant = cfg.mode != MusicHapticsMode.VOCALS_ONLY && (bassEnergy >= vocalEnergy),
                isSharpTransient = isSharpTransient,
                intensity = cfg.intensity
            )
        }
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
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val attrs = VibrationAttributes.Builder()
                        .setUsage(VibrationAttributes.USAGE_MEDIA)
                        .build()
                    vib.vibrate(effect, attrs)
                } else {
                    vib.vibrate(effect)
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && supportsPrimitiveTick && !isBassDominant) {
                val scale = (drive * intensity.primitiveScale).coerceIn(0.10f, 1.0f)
                val effect = VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, scale)
                    .compose()
                vib.vibrate(effect)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val amplitude = (drive * 255f * intensity.amplitudeScale)
                    .roundToInt()
                    .coerceIn(1, 255)
                val durationMs = when {
                    isBassDominant && isSharpTransient -> 18L
                    isBassDominant -> 24L
                    else -> 12L
                }
                vib.vibrate(VibrationEffect.createOneShot(durationMs, amplitude))
            }
        } catch (_: Throwable) {
            // Never crash audio playback on OEM Vibrator service exceptions
        }
    }

    fun release() {
        sessionStabilizeJob?.cancel()
        audioProcessor.isAnalysisActive = false
        releaseHardwareGenerator()
        vibrator?.cancel()
        hapticThread.quitSafely()
    }
}
