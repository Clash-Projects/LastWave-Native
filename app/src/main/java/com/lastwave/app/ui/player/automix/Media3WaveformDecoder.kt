@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.lastwave.app.ui.player.automix

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import androidx.media3.common.AudioAttributes
import androidx.media3.common.AuxEffectInfo
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.source.ClippingMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.ExtractorsFactory
import com.lastwave.app.data.plugin.AlacDashManifestParser
import com.lastwave.app.data.plugin.ModuleDrmFactory
import com.lastwave.app.data.plugin.SegmentedStreamDescriptor
import com.lastwave.app.playback.FfmpegCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.ceil
import kotlin.math.roundToInt

internal data class WaveformPcm(val pcm: FloatArray, val sampleRate: Int, val startMs: Double)

internal suspend fun decodeWaveformPcm(
    context: Context,
    factory: DataSource.Factory,
    extractorsFactory: ExtractorsFactory,
    item: MediaItem,
    fromMs: Long,
    untilMs: Long,
    drmFactory: ModuleDrmFactory,
    descriptor: SegmentedStreamDescriptor?,
): WaveformPcm = withContext(Dispatchers.Main.immediate) {
    val decodeThread = HandlerThread("AutoMixWaveform", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
    val sink = WaveformAudioSink(fromMs * 1_000L, untilMs * 1_000L)
    var previewPlayer: ExoPlayer? = null
    try {
        val renderers = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink = sink
        }.apply {
            setExtensionRendererMode(
                if (FfmpegCompat.isUsable) DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
                else DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF,
            )
            setEnableDecoderFallback(true)
        }
        val defaultSources = DefaultMediaSourceFactory(context, extractorsFactory).setDataSourceFactory(factory)
        val dashSources = DashMediaSource.Factory(factory).setManifestParser(AlacDashManifestParser())
        if (descriptor?.drm != null) {
            defaultSources.setDrmSessionManagerProvider { drmFactory.sessionManagerFor(descriptor) }
            dashSources.setDrmSessionManagerProvider { drmFactory.sessionManagerFor(descriptor) }
        }
        val config = item.localConfiguration
        val isDash = descriptor != null || config?.mimeType == MimeTypes.APPLICATION_MPD ||
            config?.uri?.lastPathSegment?.substringBefore('?')?.endsWith(".mpd", true) == true
        val source = if (isDash) dashSources.createMediaSource(item) else defaultSources.createMediaSource(item)
        // Keep the source's zero origin, seek straight to the cue, and stop decoding at its end.
        val clipped = ClippingMediaSource(source, 0L, untilMs * 1_000L)
        val preview = ExoPlayer.Builder(context, renderers)
            .setLooper(Looper.getMainLooper())
            .setPlaybackLooper(decodeThread.looper)
            .setMediaSourceFactory(defaultSources)
            .setLoadControl(
                DefaultLoadControl.Builder().setBufferDurationsMs(1_000, 10_000, 0, 0).build(),
            )
            .build()
        previewPlayer = preview
        preview.setAudioAttributes(AudioAttributes.DEFAULT, false)
        preview.trackSelectionParameters = TrackSelectionParameters.Builder(context)
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
        preview.volume = 0f
        suspendCancellableCoroutine { continuation ->
            val main = Handler(Looper.getMainLooper())
            fun finish() {
                if (!continuation.isActive) return
                val pcm = sink.window()
                if (pcm != null) continuation.resume(pcm)
                else continuation.resumeWithException(IllegalStateException("No cue-window PCM"))
            }
            sink.onFinished = { main.post { finish() } }
            preview.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) finish()
                }

                override fun onPlayerError(error: PlaybackException) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            })
            preview.setMediaSource(clipped, fromMs)
            preview.prepare()
            preview.playWhenReady = true
        }
    } finally {
        withContext(NonCancellable + Dispatchers.Main.immediate) {
            sink.onFinished = null
            try {
                previewPlayer?.release()
            } finally {
                decodeThread.quitSafely()
            }
        }
    }
}

internal class WaveformAudioSink(private val fromUs: Long, private val untilUs: Long) : AudioSink {
    @Volatile var onFinished: (() -> Unit)? = null
    private var sampleRate = 0
    private var channelCount = 0
    private var encoding = C.ENCODING_INVALID
    private var bytesPerSample = 0
    private var decimation = 1
    private var streamOffsetUs = 0L
    private var positionUs = AudioSink.CURRENT_POSITION_NOT_SET
    private var firstMediaUs = -1L
    private var samples = FloatArray(0)
    private var written = 0
    private var accumulator = 0.0
    private var accumulated = 0
    private var ended = false
    private var parameters = PlaybackParameters.DEFAULT
    private var skipSilence = false
    private var attributes = AudioAttributes.DEFAULT

    override fun setListener(listener: AudioSink.Listener) = Unit
    override fun supportsFormat(format: Format) = pcmBytes(format.pcmEncoding) > 0 && format.sampleRate > 0 && format.channelCount > 0
    override fun getFormatSupport(format: Format) = if (supportsFormat(format)) AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY else AudioSink.SINK_FORMAT_UNSUPPORTED
    override fun getCurrentPositionUs(sourceTimeUs: Boolean) = positionUs
    override fun setOutputStreamOffsetUs(outputStreamOffsetUs: Long) { streamOffsetUs = outputStreamOffsetUs }

    override fun configure(format: Format, specifiedBufferSizeMs: Int, outputChannels: IntArray?) {
        if (!supportsFormat(format)) throw AudioSink.ConfigurationException("Waveform capture requires PCM", format)
        sampleRate = format.sampleRate
        channelCount = format.channelCount
        encoding = format.pcmEncoding
        bytesPerSample = pcmBytes(encoding)
        decimation = (sampleRate / 11_025.0).roundToInt().coerceAtLeast(1)
        samples = FloatArray(((untilUs - fromUs) / 1_000_000.0 * sampleRate / decimation).toInt().coerceAtLeast(1) + 1_024)
        clearCapture()
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        if (sampleRate <= 0) return false
        if (ended) {
            buffer.position(buffer.limit())
            return true
        }
        val bytes = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val frameBytes = channelCount * bytesPerSample
        val frames = bytes.remaining() / frameBytes
        val mediaUs = presentationTimeUs - streamOffsetUs
        val first = ceil((fromUs - mediaUs) * sampleRate / 1_000_000.0).toInt().coerceIn(0, frames)
        val last = ceil((untilUs - mediaUs) * sampleRate / 1_000_000.0).toInt().coerceIn(first, frames)
        if (first < last && firstMediaUs < 0L) firstMediaUs = mediaUs + first * 1_000_000L / sampleRate
        val base = bytes.position()
        for (frame in first until last) {
            var mono = 0.0
            for (channel in 0 until channelCount) {
                val offset = base + (frame * channelCount + channel) * bytesPerSample
                mono += sample(bytes, offset)
            }
            accumulator += mono / channelCount
            if (++accumulated == decimation) {
                append((accumulator / decimation).toFloat())
                accumulator = 0.0
                accumulated = 0
            }
        }
        buffer.position(buffer.limit())
        // Media3's renderer clock includes the stream offset; only range cropping subtracts it.
        positionUs = presentationTimeUs + frames * 1_000_000L / sampleRate
        if (mediaUs + frames * 1_000_000L / sampleRate >= untilUs) finish()
        return true
    }

    @Synchronized fun window(): WaveformPcm? {
        if (firstMediaUs < 0 || written == 0) return null
        return WaveformPcm(samples.copyOf(written), sampleRate / decimation, firstMediaUs / 1_000.0)
    }

    private fun append(value: Float) {
        if (written == samples.size) samples = samples.copyOf(samples.size.coerceAtLeast(1) * 2)
        samples[written++] = value
    }

    private fun finish() {
        if (ended) return
        if (accumulated > 0) {
            append((accumulator / accumulated).toFloat())
            accumulated = 0
            accumulator = 0.0
        }
        ended = true
        onFinished?.invoke()
    }

    private fun clearCapture() {
        positionUs = AudioSink.CURRENT_POSITION_NOT_SET
        firstMediaUs = -1L
        written = 0
        accumulator = 0.0
        accumulated = 0
        ended = false
    }

    private fun pcmBytes(pcm: Int) = when (pcm) {
        C.ENCODING_PCM_8BIT -> 1
        C.ENCODING_PCM_16BIT -> 2
        C.ENCODING_PCM_24BIT -> 3
        C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 4
        else -> 0
    }

    private fun sample(buffer: ByteBuffer, offset: Int): Double = when (encoding) {
        C.ENCODING_PCM_8BIT -> ((buffer.get(offset).toInt() and 0xFF) - 128) / 128.0
        C.ENCODING_PCM_16BIT -> buffer.getShort(offset) / 32_768.0
        C.ENCODING_PCM_24BIT -> ((buffer.get(offset).toInt() and 0xFF) or
            ((buffer.get(offset + 1).toInt() and 0xFF) shl 8) or (buffer.get(offset + 2).toInt() shl 16)) / 8_388_608.0
        C.ENCODING_PCM_32BIT -> buffer.getInt(offset) / 2_147_483_648.0
        C.ENCODING_PCM_FLOAT -> buffer.getFloat(offset).toDouble()
        else -> 0.0
    }

    override fun play() = Unit
    override fun handleDiscontinuity() = Unit
    override fun playToEndOfStream() { finish() }
    override fun isEnded() = ended
    override fun hasPendingData() = false
    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) { parameters = playbackParameters }
    override fun getPlaybackParameters() = parameters
    override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) { skipSilence = skipSilenceEnabled }
    override fun getSkipSilenceEnabled() = skipSilence
    override fun setAudioAttributes(audioAttributes: AudioAttributes) { attributes = audioAttributes }
    override fun getAudioAttributes() = attributes
    override fun setAudioSessionId(audioSessionId: Int) = Unit
    override fun setAuxEffectInfo(auxEffectInfo: AuxEffectInfo) = Unit
    override fun enableTunnelingV21() = Unit
    override fun disableTunneling() = Unit
    override fun setVolume(volume: Float) = Unit
    override fun pause() = Unit
    override fun flush() { clearCapture() }
    override fun reset() = Unit
}
