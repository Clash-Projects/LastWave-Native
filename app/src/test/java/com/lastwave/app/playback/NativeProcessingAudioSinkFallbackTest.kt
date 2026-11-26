package com.lastwave.app.playback

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class NativeProcessingAudioSinkFallbackTest {

    private fun unavailableProcessor(): NativePcmAudioProcessor {
        val engine = mockk<NativeAudioEngine>(relaxed = true) {
            every { isAvailable } returns false
        }
        return NativePcmAudioProcessor(engine)
    }

    private fun rawFormat(channels: Int, encoding: Int = C.ENCODING_PCM_16BIT, sampleRate: Int = 48_000): Format =
        Format.Builder()
            .setId("1")
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setChannelCount(channels)
            .setSampleRate(sampleRate)
            .setPcmEncoding(encoding)
            .build()

    @Test
    fun `more than 8 channels folds directly to stereo before AudioTrack configure`() {
        val enhanced = mockk<DefaultAudioSink>(relaxed = true)
        val fallback = mockk<DefaultAudioSink>(relaxed = true)
        val configuredSlot = slot<Format>()
        every { fallback.configure(capture(configuredSlot), any(), any()) } returns Unit

        val sink = NativeProcessingAudioSink(enhanced, fallback, unavailableProcessor())
        sink.configure(rawFormat(channels = 12, encoding = C.ENCODING_PCM_FLOAT), 0, null)

        assertThat(configuredSlot.captured.channelCount).isEqualTo(2)
        assertThat(configuredSlot.captured.pcmEncoding).isEqualTo(C.ENCODING_PCM_FLOAT)
    }

    @Test
    fun `multichannel AudioTrack initialization failure recovers by downmixing to stereo`() {
        val enhanced = mockk<DefaultAudioSink>(relaxed = true)
        val fallback = mockk<DefaultAudioSink>(relaxed = true)
        val format6ch = rawFormat(channels = 6, encoding = C.ENCODING_PCM_16BIT)
        val configuredFormats = mutableListOf<Format>()
        every { fallback.configure(capture(configuredFormats), any(), any()) } returns Unit

        val capturedBuffers = mutableListOf<ByteBuffer>()
        every { fallback.handleBuffer(capture(capturedBuffers), any(), any()) } answers {
            val idx = configuredFormats.last().channelCount
            if (idx > 2) {
                throw AudioSink.InitializationException(
                    "5.1 unsupported",
                    0,
                    format6ch,
                    false,
                    IllegalStateException("5.1 unsupported"),
                )
            }
            val buf = firstArg<ByteBuffer>()
            buf.position(buf.limit())
            true
        }

        val sink = NativeProcessingAudioSink(enhanced, fallback, unavailableProcessor())
        sink.configure(format6ch, 0, null)

        val pcm51 = ByteBuffer.allocateDirect(2 * 6 * 2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(2) {
            pcm51.putShort(10_000) // FL
            pcm51.putShort(2_000)  // FR
            pcm51.putShort(4_000)  // FC
            pcm51.putShort(0)      // LFE
            pcm51.putShort(1_000)  // Ls
            pcm51.putShort(1_000)  // Rs
        }
        pcm51.flip()

        assertThat(sink.handleBuffer(pcm51, 0L, 1)).isTrue()
        assertThat(pcm51.hasRemaining()).isFalse()
        assertThat(configuredFormats.last().channelCount).isEqualTo(2)
        assertThat(capturedBuffers.last().capacity()).isAtLeast(2 * 2 * 2)
    }

    @Test
    fun `direct Float32 AudioTrack initialization failure falls back to PCM16 sink`() {
        val enhanced = mockk<DefaultAudioSink>(relaxed = true)
        val fallback = mockk<DefaultAudioSink>(relaxed = true)
        val floatStereo = rawFormat(channels = 2, encoding = C.ENCODING_PCM_FLOAT)

        every { enhanced.getFormatSupport(any()) } returns AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
        every { enhanced.handleBuffer(any(), any(), any()) } throws AudioSink.InitializationException(
            "Float32 track rejected",
            0,
            floatStereo,
            false,
            IllegalStateException("Float32 track rejected"),
        )
        every { fallback.handleBuffer(any(), any(), any()) } answers {
            val buf = firstArg<ByteBuffer>()
            buf.position(buf.limit())
            true
        }

        val sink = NativeProcessingAudioSink(enhanced, fallback, unavailableProcessor())
        sink.setBitPerfectRequested(true)
        sink.configure(floatStereo, 0, null)

        val pcmFloat = ByteBuffer.allocateDirect(2 * 2 * 4).order(ByteOrder.LITTLE_ENDIAN)
        repeat(4) { pcmFloat.putFloat(0.25f) }
        pcmFloat.flip()

        assertThat(sink.handleBuffer(pcmFloat, 0L, 1)).isTrue()
        verify(exactly = 1) { fallback.configure(floatStereo, 0, null) }
        verify(exactly = 1) { fallback.handleBuffer(any(), 0L, 1) }
    }

    @Test
    fun `unsupported hi-res sample rate falls back to 48 kHz PCM16 through native resampler`() {
        val engine = mockk<NativeAudioEngine>(relaxed = true) {
            every { isAvailable } returns true
            every { configureMediaProcessor(any(), any(), any()) } returns true
            every { processMediaPcm(any(), any(), any(), any(), any(), any(), any()) } answers {
                firstArg<ByteBuffer>()
                arg<Int>(4)
            }
        }
        val processor = NativePcmAudioProcessor(engine)
        val enhanced = mockk<DefaultAudioSink>(relaxed = true)
        val fallback = mockk<DefaultAudioSink>(relaxed = true)
        val hiResFlac = rawFormat(channels = 2, encoding = C.ENCODING_PCM_FLOAT, sampleRate = 192_000)

        val configuredRates = mutableListOf<Int>()
        every { enhanced.getFormatSupport(any()) } returns AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
        every { enhanced.handleBuffer(any(), any(), any()) } throws AudioSink.InitializationException(
            "192k float rejected",
            0,
            hiResFlac,
            false,
            IllegalStateException("192k float rejected"),
        )
        every { fallback.configure(any(), any(), any()) } answers {
            configuredRates += firstArg<Format>().sampleRate
        }
        every { fallback.handleBuffer(any(), any(), any()) } answers {
            val rate = configuredRates.last()
            if (rate != 48_000) {
                throw AudioSink.InitializationException(
                    "192k PCM16 rejected",
                    0,
                    hiResFlac,
                    false,
                    IllegalStateException("192k PCM16 rejected"),
                )
            }
            val buf = firstArg<ByteBuffer>()
            buf.position(buf.limit())
            true
        }

        val sink = NativeProcessingAudioSink(enhanced, fallback, processor)
        sink.configure(hiResFlac, 0, null)

        val pcmFloat = ByteBuffer.allocateDirect(2 * 2 * 4).order(ByteOrder.LITTLE_ENDIAN)
        repeat(4) { pcmFloat.putFloat(0.25f) }
        pcmFloat.flip()

        assertThat(sink.handleBuffer(pcmFloat, 0L, 1)).isTrue()
        assertThat(configuredRates).contains(48_000)
    }

    @Test
    fun `routed 96 kHz DAC skips raw 192 kHz direct track and resamples to 96 kHz`() {
        val engine = mockk<NativeAudioEngine>(relaxed = true) {
            every { isAvailable } returns true
            every { configureMediaProcessor(any(), any(), any()) } returns true
        }
        val processor = NativePcmAudioProcessor(engine)
        val enhanced = mockk<DefaultAudioSink>(relaxed = true)
        val fallback = mockk<DefaultAudioSink>(relaxed = true)
        val configuredRates = mutableListOf<Int>()
        every { enhanced.getFormatSupport(any()) } returns AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
        every { enhanced.configure(any(), any(), any()) } answers {
            configuredRates += firstArg<Format>().sampleRate
        }
        every { fallback.configure(any(), any(), any()) } answers {
            configuredRates += firstArg<Format>().sampleRate
        }

        val sink = NativeProcessingAudioSink(enhanced, fallback, processor)
        sink.setBitPerfectRequested(true)
        sink.setPreferredDeviceSupportedRates(listOf(44_100, 48_000, 96_000))
        sink.setOutputSampleRateOverride(96_000)
        sink.configure(rawFormat(channels = 2, encoding = C.ENCODING_PCM_24BIT, sampleRate = 192_000), 0, null)

        assertThat(configuredRates).containsExactly(96_000)
    }

    @Test
    fun `undisclosed DAC rejecting 192 kHz steps down to 96 kHz before 48 kHz`() {
        val engine = mockk<NativeAudioEngine>(relaxed = true) {
            every { isAvailable } returns true
            every { configureMediaProcessor(any(), any(), any()) } returns true
            every { processMediaPcm(any(), any(), any(), any(), any(), any(), any()) } answers {
                arg<Int>(4)
            }
        }
        val processor = NativePcmAudioProcessor(engine)
        val enhanced = mockk<DefaultAudioSink>(relaxed = true)
        val fallback = mockk<DefaultAudioSink>(relaxed = true)
        val hiResFlac = rawFormat(channels = 2, encoding = C.ENCODING_PCM_FLOAT, sampleRate = 192_000)

        val configuredRates = mutableListOf<Int>()
        every { enhanced.getFormatSupport(any()) } returns AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
        every { enhanced.handleBuffer(any(), any(), any()) } throws AudioSink.InitializationException(
            "192k float rejected",
            0,
            hiResFlac,
            false,
            IllegalStateException("192k float rejected"),
        )
        every { fallback.configure(any(), any(), any()) } answers {
            configuredRates += firstArg<Format>().sampleRate
        }
        every { fallback.handleBuffer(any(), any(), any()) } answers {
            val rate = configuredRates.last()
            if (rate > 96_000) {
                throw AudioSink.InitializationException(
                    ">96k rejected",
                    0,
                    hiResFlac,
                    false,
                    IllegalStateException(">96k rejected"),
                )
            }
            val buf = firstArg<ByteBuffer>()
            buf.position(buf.limit())
            true
        }

        val sink = NativeProcessingAudioSink(enhanced, fallback, processor)
        sink.configure(hiResFlac, 0, null)

        val pcmFloat = ByteBuffer.allocateDirect(2 * 2 * 4).order(ByteOrder.LITTLE_ENDIAN)
        repeat(4) { pcmFloat.putFloat(0.25f) }
        pcmFloat.flip()

        assertThat(sink.handleBuffer(pcmFloat, 0L, 1)).isTrue()
        assertThat(configuredRates).containsExactly(96_000)
    }
}
