package com.lastwave.app.data.recognition

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.abs

/** Records a short clip from the microphone as mono 16-bit PCM. */
object MicCapture {

    /** 44.1 kHz is the one rate every Android device must support for capture. */
    const val CAPTURE_RATE = 44_100

    class Clip(val samples: ShortArray, val sampleRate: Int)

    class CaptureException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /** Caller must already hold RECORD_AUDIO. */
    @SuppressLint("MissingPermission")
    suspend fun record(durationMs: Long): Clip = withContext(Dispatchers.IO) {
        val minBuffer = AudioRecord.getMinBufferSize(
            CAPTURE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) throw CaptureException("Microphone is not available")

        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                CAPTURE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuffer * 2,
            )
        } catch (e: Exception) {
            throw CaptureException("Microphone is not available", e)
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
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
        }
        Clip(pcm, CAPTURE_RATE)
    }

    /** True when the clip never rises above background noise. */
    fun isSilent(samples: ShortArray, threshold: Int = 300): Boolean =
        samples.none { abs(it.toInt()) >= threshold }
}
