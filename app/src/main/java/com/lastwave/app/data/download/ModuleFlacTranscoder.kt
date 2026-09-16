package com.lastwave.app.data.download

import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.lastwave.app.data.plugin.ModuleDrmFactory
import com.lastwave.app.data.plugin.SegmentedDashBridge
import com.lastwave.app.data.plugin.SegmentedStreamDescriptor
import com.lastwave.app.playback.CapturingAudioSink
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class TranscodedFlac(
    val file: File,
    val sampleRate: Int,
    val channels: Int,
)

/**
 * Decrypts a downloaded module track into a plain, universally playable FLAC.
 *
 * Path: local-file MPD (offline bytes, zero CDN) -> headless ExoPlayer with a
 * capturing sink (proven streaming DRM path, decode runs flat-out, not
 * realtime) -> PCM file -> FLAC encode -> true .flac.
 *
 * Returns null on ANY failure (no FLAC encoder, secure-output refusal, decode
 * error, timeout) so the caller falls back to the license-persisted
 * encrypted path. All player calls happen on the calling thread; listener
 * callbacks only resume the coroutine.
 */
@UnstableApi
@Singleton
class ModuleFlacTranscoder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val drmFactory: ModuleDrmFactory,
    private val segBridge: SegmentedDashBridge,
) {
    companion object {
        private const val MAX_TRANSCODE_MS = 10 * 60 * 1000L
        private const val PUMP_BYTES = 64 * 1024
    }

    suspend fun transcodeToFlac(
        sourceFile: File,
        descriptor: SegmentedStreamDescriptor,
        title: String,
        artist: String,
        album: String?,
        artworkUri: String?,
    ): TranscodedFlac? = withContext(Dispatchers.IO) {
        runCatching {
            withTimeout(MAX_TRANSCODE_MS) {
                doTranscode(sourceFile, descriptor, title, artist, album, artworkUri)
            }
        }.getOrNull()
    }

    private suspend fun doTranscode(
        sourceFile: File,
        descriptor: SegmentedStreamDescriptor,
        title: String,
        artist: String,
        album: String?,
        artworkUri: String?,
    ): TranscodedFlac {
        val pcmFile = File.createTempFile("trans_pcm_", ".pcm", context.cacheDir)
        val sink = CapturingAudioSink(pcmFile)
        // Local-file MPD: same bytes the download verified, no network except
        // the streaming license exchange (module envelope via the callback).
        val localMpd = segBridge.mpdUriForBase(
            descriptor,
            Uri.fromFile(sourceFile).toString(),
        )
        val item = androidx.media3.common.MediaItem.Builder()
            .setMediaId("transcode:${descriptor.trackId}")
            .setUri(localMpd)
            .setMimeType(MimeTypes.APPLICATION_MPD)
            .setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder()
                    .setTitle(title).setArtist(artist).setAlbumTitle(album)
                    .setArtworkUri(artworkUri?.let(Uri::parse))
                    .setIsPlayable(true).build(),
            )
            .apply {
                descriptor.drm?.let { drm ->
                    if (!drm.pssh.isBlank() && !drm.licenseUrl.isBlank()) {
                        setDrmConfiguration(
                            androidx.media3.common.MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID)
                                .setLicenseUri(drm.licenseUrl)
                                .setMultiSession(false)
                                .apply {
                                    val headers = descriptor.headers.toMutableMap()
                                    if (headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
                                        headers["User-Agent"] = "LastWave/4.1.0"
                                    }
                                    setLicenseRequestHeaders(headers)
                                }
                                .build(),
                        )
                    }
                }
            }
            .build()

        // NOTE: deliberately NOT reusing ModuleDrmFactory.sessionManagerFor
        // here: that manager is tuned for the foreground player; the transcode
        // player owns an isolated DefaultDrmSessionManager with the same
        // module-envelope callback so license behavior stays identical.
        val renderers = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): androidx.media3.exoplayer.audio.AudioSink = sink
        }
        val mediaSourceFactory = DefaultMediaSourceFactory(context)
            .setDrmSessionManagerProvider { drmFactory.sessionManagerFor(descriptor) }
        val player = ExoPlayer.Builder(context, renderers)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
        try {
            awaitEnded(player, item)
            if (!sink.isConfigured() || sink.framesWritten <= 0) {
                error("No PCM captured")
            }
            val flacFile = File.createTempFile("trans_flac_", ".flac", context.cacheDir)
            if (!encodePcmToFlac(pcmFile, sink.sampleRate, sink.channelCount, flacFile)) {
                runCatching { flacFile.delete() }
                error("FLAC encode unavailable")
            }
            return TranscodedFlac(flacFile, sink.sampleRate, sink.channelCount)
        } finally {
            runCatching { player.release() }
            runCatching { pcmFile.delete() }
        }
    }

    private suspend fun awaitEnded(
        player: ExoPlayer,
        item: androidx.media3.common.MediaItem,
    ): Unit = suspendCancellableCoroutine { cont ->
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED && cont.isActive) {
                    player.removeListener(this)
                    cont.resume(Unit)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (cont.isActive) {
                    player.removeListener(this)
                    cont.resumeWithException(error)
                }
            }
        }
        player.addListener(listener)
        cont.invokeOnCancellation {
            runCatching { player.removeListener(listener) }
        }
        player.setMediaItem(item)
        player.prepare()
        player.playWhenReady = true
    }

    /**
     * PCM16 file -> framed .flac. STREAMINFO comes from the encoder's csd-0;
     * frames are appended verbatim (encoder output is already framed).
     */
    private fun encodePcmToFlac(pcmFile: File, sampleRate: Int, channels: Int, outFile: File): Boolean {
        if (sampleRate <= 0 || channels <= 0) return false
        val codec = try {
            MediaCodec.createEncoderByType(MimeTypes.AUDIO_FLAC)
        } catch (_: Exception) {
            return false
        }
        try {
            val format = MediaFormat().apply {
                setString(MediaFormat.KEY_MIME, MimeTypes.AUDIO_FLAC)
                setInteger(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                setInteger(MediaFormat.KEY_CHANNEL_COUNT, channels)
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            var streamInfo: ByteArray? = null
            val pendingFrames = mutableListOf<ByteArray>()
            var out: java.io.OutputStream? = null
            fun ensureOutput(): java.io.OutputStream {
                var o = out
                if (o == null) {
                    val csd = streamInfo ?: error("no STREAMINFO")
                    o = outFile.outputStream().buffered()
                    o.write("fLaC".toByteArray(Charsets.US_ASCII))
                    // METADATA_BLOCK_HEADER: last-block + STREAMINFO(0) + length 34
                    o.write(byteArrayOf(0x90.toByte(), 0x00, 0x00, 0x22.toByte()))
                    o.write(csd)
                    for (f in pendingFrames) o.write(f)
                    pendingFrames.clear()
                    out = o
                }
                return o
            }
            fun onStreamInfo(bytes: ByteArray) {
                if (streamInfo == null) {
                    streamInfo = bytes
                    // Frames may have arrived before the header: flush them now.
                    if (pendingFrames.isNotEmpty()) ensureOutput()
                }
            }
            pcmFile.inputStream().buffered().use { input ->
                var inputEos = false
                var outputEos = false
                val bufferInfo = MediaCodec.BufferInfo()
                val chunk = ByteArray(PUMP_BYTES)
                while (!outputEos) {
                    if (!inputEos) {
                        val inIndex = codec.dequeueInputBuffer(10_000)
                        if (inIndex >= 0) {
                            val inBuf = codec.getInputBuffer(inIndex) ?: error("null input")
                            inBuf.clear()
                            var read = 0
                            while (read < inBuf.remaining()) {
                                val n = input.read(chunk, 0, minOf(chunk.size, inBuf.remaining()))
                                if (n < 0) break
                                inBuf.put(chunk, 0, n)
                                read += n
                            }
                            if (read <= 0) {
                                codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputEos = true
                            } else {
                                codec.queueInputBuffer(inIndex, 0, read, 0, 0)
                            }
                        }
                    }
                    val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                    when {
                        outIndex >= 0 -> {
                            val outBuf = codec.getOutputBuffer(outIndex)
                            if (outBuf != null && bufferInfo.size > 0) {
                                val bytes = ByteArray(bufferInfo.size)
                                outBuf.position(bufferInfo.offset)
                                outBuf.limit(bufferInfo.offset + bufferInfo.size)
                                outBuf.get(bytes)
                                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                    onStreamInfo(bytes)
                                } else if (streamInfo != null) {
                                    ensureOutput().write(bytes)
                                } else {
                                    pendingFrames.add(bytes)
                                }
                            }
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                outputEos = true
                            }
                            codec.releaseOutputBuffer(outIndex, false)
                        }
                        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            codec.outputFormat.getByteBuffer("csd-0")?.let { csdBuf ->
                                val bytes = ByteArray(csdBuf.remaining())
                                csdBuf.get(bytes)
                                onStreamInfo(bytes)
                            }
                        }
                        // INFO_TRY_AGAIN_LATER: loop.
                    }
                }
            }
            if (out == null && streamInfo != null && pendingFrames.isNotEmpty()) {
                ensureOutput()
            }
            out?.flush()
            out?.close()
            return streamInfo != null && outFile.length() > 42
        } catch (_: Exception) {
            runCatching { outFile.delete() }
            return false
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
    }
}
