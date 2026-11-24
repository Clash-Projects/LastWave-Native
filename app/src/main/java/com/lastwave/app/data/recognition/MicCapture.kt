package com.lastwave.app.data.recognition

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.abs

/** Records a short clip from the microphone or internal playback as mono 16-bit PCM. */
object MicCapture {

    /** 44.1 kHz is the one rate every Android device must support for capture. */
    const val CAPTURE_RATE = 44_100

    class Clip(val samples: ShortArray, val sampleRate: Int)

    class CaptureException(message: String, cause: Throwable? = null) : Exception(message, cause)

    fun shouldUseInternalPlaybackCapture(context: Context): Boolean =
        AudioSourceDetector.shouldUseInternalPlaybackCapture(context)

    fun shouldUseInternalPlaybackCapture(audioManager: AudioManager): Boolean =
        AudioSourceDetector.shouldUseInternalPlaybackCapture(audioManager)

    fun shouldUseInternalPlaybackCapture(devices: Array<AudioDeviceInfo>): Boolean =
        AudioSourceDetector.shouldUseInternalPlaybackCapture(devices)

    /**
     * Records an audio clip of [durationMs].
     * If [mediaProjection] is provided on API 29+, captures internal playback audio.
     * Otherwise, captures from the device microphone.
     * Caller must already hold RECORD_AUDIO.
     */
    @SuppressLint("MissingPermission")
    suspend fun record(
        durationMs: Long,
        mediaProjection: MediaProjection? = null,
    ): Clip = withContext(Dispatchers.IO) {
        val minBuffer = AudioRecord.getMinBufferSize(
            CAPTURE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) throw CaptureException("Microphone is not available")

        var usedProjection = false
        val recorder = try {
            if (mediaProjection != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                usedProjection = true
                createPlaybackCaptureRecord(mediaProjection, minBuffer)
            } else {
                createMicRecord(minBuffer)
            }
        } catch (e: Exception) {
            if (mediaProjection != null) {
                // Fall back gracefully to microphone if internal playback capture fails
                try {
                    usedProjection = false
                    createMicRecord(minBuffer)
                } catch (fallbackEx: Exception) {
                    throw CaptureException("Microphone is not available", fallbackEx)
                }
            } else {
                throw CaptureException("Microphone is not available", e)
            }
        }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            if (usedProjection) {
                // Fall back gracefully to microphone
                return@withContext record(durationMs, null)
            }
            throw CaptureException("Microphone is busy or unavailable")
        }

        val target = (CAPTURE_RATE * durationMs / 1000).toInt()
        val pcm = ShortArray(target)
        var filled = 0
        try {
            recorder.startRecording()
            val chunk = ShortArray(minBuffer / 2)
            while (filled < target) {
                currentCoroutineContext().ensureActive()
                val read = recorder.read(chunk, 0, minOf(chunk.size, target - filled))
                if (read < 0) throw CaptureException("Microphone read failed ($read)")
                System.arraycopy(chunk, 0, pcm, filled, read)
                filled += read
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: CaptureException) {
            throw e
        } catch (e: Exception) {
            throw CaptureException("Could not record audio", e)
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
            if (mediaProjection != null) {
                runCatching { mediaProjection.stop() }
            }
        }
        Clip(pcm, CAPTURE_RATE)
    }

    @SuppressLint("MissingPermission")
    private fun createMicRecord(minBuffer: Int): AudioRecord {
        return AudioRecord(
            MediaRecorder.AudioSource.MIC,
            CAPTURE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuffer * 2,
        )
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    @SuppressLint("MissingPermission")
    private fun createPlaybackCaptureRecord(
        mediaProjection: MediaProjection,
        minBuffer: Int,
    ): AudioRecord {
        val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(CAPTURE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()

        return AudioRecord.Builder()
            .setAudioPlaybackCaptureConfig(config)
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(minBuffer * 2)
            .build()
    }

    /** True when the clip never rises above background noise. */
    fun isSilent(samples: ShortArray, threshold: Int = 300): Boolean =
        samples.none { abs(it.toInt()) >= threshold }
}
