package com.lastwave.app.playback

import android.content.Context
import android.media.MediaCodecList
import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector

/**
 * Shared availability, capability, and audio-renderer wiring for the bundled
 * 16 KB-aligned Media3 FFmpeg decoder (`1.9.0+1`).
 */
@OptIn(UnstableApi::class)
object FfmpegCompat {

    private val PLATFORM_ATMOS_MIMES = setOf(
        "audio/eac3-joc",
        "audio/ac4",
        "audio/mha1",
        "audio/mhm1",
    )

    val isUsable: Boolean by lazy {
        runCatching { FfmpegLibrary.isAvailable() }.getOrDefault(false)
    }

    fun recommendedExtensionRendererMode(): Int =
        if (isUsable) DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
        else DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF

    fun supportsMime(mimeType: String): Boolean =
        isUsable && runCatching { FfmpegLibrary.supportsFormat(mimeType) }.getOrDefault(false)

    /**
     * Genuine spatial/Atmos hardware decoders (`audio/eac3-joc`, `ac4`, MPEG-H).
     * Plain `audio/eac3`/`audio/ac3` codecs are often video-passthrough blocks that
     * fail music JOC manifests unless FFmpeg handles the E-AC-3 core.
     */
    fun hasPlatformAtmosDecoder(): Boolean = runCatching {
        val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
        codecList.codecInfos.any { info ->
            !info.isEncoder && info.supportedTypes.any { type ->
                type.lowercase() in PLATFORM_ATMOS_MIMES
            }
        }
    }.getOrDefault(false)

    fun isAtmosCapable(): Boolean =
        isAtmosCapable(
            hasPlatformAtmos = hasPlatformAtmosDecoder(),
            ffmpegEac3 = supportsMime(MimeTypes.AUDIO_E_AC3),
        )

    internal fun isAtmosCapable(hasPlatformAtmos: Boolean, ffmpegEac3: Boolean): Boolean =
        hasPlatformAtmos || ffmpegEac3

    internal fun shouldUsePlatformSpatialRenderer(
        mime: String,
        hasPlatformAtmos: Boolean = hasPlatformAtmosDecoder(),
        ffmpegEac3: Boolean = supportsMime(MimeTypes.AUDIO_E_AC3),
    ): Boolean {
        val lower = mime.lowercase()
        val isSpatial = lower in PLATFORM_ATMOS_MIMES ||
            lower.contains("eac3-joc") ||
            lower.contains("ac4") ||
            lower.contains("mha1") ||
            lower.contains("mhm1")
        if (isSpatial) return true
        val isLegacyDolby = lower.contains("eac3") || lower.contains("ec-3") ||
            lower.contains("ac-3") || lower.contains("ac3")
        if (!isLegacyDolby) return false
        // Only let platform MediaCodec claim legacy AC-3/E-AC-3 ahead of FFmpeg
        // when genuine Atmos HAL exists or FFmpeg isn't available to handle it.
        return hasPlatformAtmos || !ffmpegEac3
    }

    /**
     * Registers renderers in a deterministic order across main playback, silent
     * probe players, and transcoding: genuine spatial MediaCodec (when eligible),
     * then FFmpeg software decoding (guaranteed single instance when usable),
     * then standard MediaCodec fallbacks from [superBuild].
     */
    fun appendAudioRenderers(
        context: Context,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>,
        onConfigureSpatialMediaFormat: ((android.media.MediaFormat, Format) -> Unit)? = null,
        superBuild: () -> Unit,
    ) {
        val spatialRenderer = object : MediaCodecAudioRenderer(
            context,
            mediaCodecSelector,
            enableDecoderFallback,
            eventHandler,
            eventListener,
            audioSink,
        ) {
            override fun supportsFormat(
                selector: MediaCodecSelector,
                format: Format,
            ): Int {
                val mime = format.sampleMimeType?.lowercase().orEmpty()
                if (!shouldUsePlatformSpatialRenderer(mime)) {
                    return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE)
                }
                return super.supportsFormat(selector, format)
            }

            override fun getMediaFormat(
                format: Format,
                codecMimeType: String,
                codecMaxInputSize: Int,
                codecOperatingRate: Float,
            ): android.media.MediaFormat {
                val mediaFormat = super.getMediaFormat(
                    format,
                    codecMimeType,
                    codecMaxInputSize,
                    codecOperatingRate,
                )
                onConfigureSpatialMediaFormat?.invoke(mediaFormat, format)
                return mediaFormat
            }
        }
        out.add(spatialRenderer)
        val beforeSuper = out.size
        superBuild()

        if (isUsable && out.none { it is FfmpegAudioRenderer }) {
            runCatching {
                FfmpegAudioRenderer(eventHandler, eventListener, audioSink)
            }.getOrNull()?.let { ffmpegRenderer ->
                // Insert right after the spatial gate and ahead of standard
                // MediaCodecAudioRenderer added by superBuild.
                out.add(beforeSuper, ffmpegRenderer)
            }
        }
    }
}
