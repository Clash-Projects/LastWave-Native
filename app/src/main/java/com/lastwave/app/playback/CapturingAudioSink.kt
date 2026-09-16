package com.lastwave.app.playback

import androidx.media3.common.AudioAttributes
import androidx.media3.common.AuxEffectInfo
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.audio.AudioSink
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * AudioSink that captures decoded PCM to a file instead of playing it.
 *
 * Position reports written audio (never wall-clock), so the player decodes
 * as fast as the CPU allows rather than in realtime. Linear transcode only:
 * no seeks are issued, flush() only clears the ended flag, counters stay
 * monotonic. Supports PCM 16-bit and PCM float (converted to 16-bit).
 */
class CapturingAudioSink(
    private val outFile: File,
) : AudioSink {

    var sampleRate: Int = 0
        private set
    var channelCount: Int = 0
        private set
    var pcmEncoding: Int = C.ENCODING_INVALID
        private set
    var framesWritten: Long = 0L
        private set

    private var output: OutputStream? = null
    private var bytesPerFrame: Int = 0
    private var ended: Boolean = false
    private var playbackParameters: PlaybackParameters = PlaybackParameters.DEFAULT
    private var skipSilenceEnabled: Boolean = false
    private var audioAttributes: AudioAttributes = AudioAttributes.DEFAULT
    private var listener: AudioSink.Listener? = null

    fun isConfigured(): Boolean = output != null && sampleRate > 0 && channelCount > 0

    override fun setListener(listener: AudioSink.Listener) {
        this.listener = listener
    }

    override fun supportsFormat(format: Format): Boolean = isPcm(format)

    override fun getFormatSupport(format: Format): Int =
        if (isPcm(format)) AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
        else AudioSink.SINK_FORMAT_UNSUPPORTED

    override fun getCurrentPositionUs(sourceTimeUs: Boolean): Long {
        val rate = sampleRate
        if (rate <= 0) return 0L
        return framesWritten * 1_000_000L / rate
    }

    override fun configure(format: Format, specifiedBufferSizeMs: Int, outputChannels: IntArray?) {
        if (!isPcm(format)) {
            throw AudioSink.ConfigurationException("Capturing sink needs PCM16/FLOAT", format)
        }
        sampleRate = format.sampleRate
        channelCount = format.channelCount
        pcmEncoding = format.pcmEncoding
        bytesPerFrame = channelCount * if (pcmEncoding == C.ENCODING_PCM_FLOAT) 4 else 2
        framesWritten = 0L
        ended = false
        output?.runCatchingClose()
        output = outFile.outputStream().buffered()
    }

    override fun play() {
        // No output device; capture runs on handleBuffer calls.
    }

    override fun handleDiscontinuity() {
        // Linear capture: ignore timestamp jumps, keep appending.
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        val out = output ?: return false
        val bytes = if (pcmEncoding == C.ENCODING_PCM_FLOAT) {
            floatToS16Bytes(buffer)
        } else {
            ByteArray(buffer.remaining()).also { buffer.get(it) }
        }
        if (bytes.isNotEmpty()) {
            out.write(bytes)
            framesWritten += bytes.size / bytesPerFrame.coerceAtLeast(1)
        }
        return false
    }

    override fun playToEndOfStream() {
        ended = true
    }

    override fun isEnded(): Boolean = ended

    override fun hasPendingData(): Boolean = false

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        this.playbackParameters = playbackParameters
    }

    override fun getPlaybackParameters(): PlaybackParameters = playbackParameters

    override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) {
        this.skipSilenceEnabled = skipSilenceEnabled
    }

    override fun getSkipSilenceEnabled(): Boolean = skipSilenceEnabled

    override fun setAudioAttributes(audioAttributes: AudioAttributes) {
        this.audioAttributes = audioAttributes
    }

    override fun getAudioAttributes(): AudioAttributes = audioAttributes

    override fun setAudioSessionId(audioSessionId: Int) {
        // No session without AudioTrack.
    }

    override fun setAuxEffectInfo(auxEffectInfo: AuxEffectInfo) {
        // No effects chain in capture mode.
    }

    override fun enableTunnelingV21() {
        // Tunneled output is incompatible with capture; audio-only source.
    }

    override fun disableTunneling() {
        // No-op.
    }

    override fun setVolume(volume: Float) {
        // Capture is always full-scale; player volume must stay 1.0.
    }

    override fun pause() {
        // No-op.
    }

    override fun flush() {
        // Linear transcode never seeks; keep counters monotonic, clear ended.
        ended = false
    }

    override fun reset() {
        runCatching { output?.close() }
        output = null
    }

    private fun isPcm(format: Format): Boolean {
        val enc = format.pcmEncoding
        return (enc == C.ENCODING_PCM_16BIT || enc == C.ENCODING_PCM_FLOAT) &&
            format.sampleRate > 0 && format.channelCount > 0
    }

    private fun floatToS16Bytes(buffer: ByteBuffer): ByteArray {
        val floats = buffer.order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val out = ByteArray(floats.remaining() * 2)
        var o = 0
        while (floats.hasRemaining()) {
            val v = (floats.get() * 32767f).toInt().coerceIn(-32768, 32767)
            out[o++] = (v and 0xFF).toByte()
            out[o++] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun OutputStream.runCatchingClose() {
        runCatching { close() }
    }
}
