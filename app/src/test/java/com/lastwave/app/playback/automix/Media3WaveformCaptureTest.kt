@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.lastwave.app.playback.automix

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.audio.AudioSink
import com.lastwave.app.ui.player.automix.WaveformAudioSink
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class Media3WaveformCaptureTest {
    private val rendererOffsetUs = 1_000_000_000_000L

    @Test
    fun pcm16_cropsInMediaTimeButAdvancesTheRendererClockAndConsumesTheBuffer() {
        val sink = WaveformAudioSink(fromUs = 2_000L, untilUs = 6_000L)
        sink.configure(format(C.ENCODING_PCM_16BIT, channels = 1), 0, null)
        sink.setOutputStreamOffsetUs(rendererOffsetUs)
        var finished = 0
        sink.onFinished = { finished++ }
        val buffer = pcm16(-32_768, -24_576, -16_384, -8_192, 0, 8_192, 16_384, 24_576, 32_767, 4_096)

        assertTrue(sink.handleBuffer(buffer, rendererOffsetUs, 0))

        val captured = requireNotNull(sink.window())
        assertEquals(4, captured.pcm.size)
        assertArrayEquals(floatArrayOf(-0.5f, -0.25f, 0f, 0.25f), captured.pcm, 0f)
        assertEquals(1_000, captured.sampleRate)
        assertEquals(2.0, captured.startMs, 0.0)
        assertEquals(buffer.limit(), buffer.position())
        assertFalse(buffer.hasRemaining())
        assertEquals(rendererOffsetUs + 10_000L, sink.getCurrentPositionUs(false))
        assertTrue(sink.isEnded())
        assertEquals(1, finished)
    }

    @Test
    fun floatStereo_averagesChannelsAndAdvancesByFramesRatherThanSamples() {
        val sink = WaveformAudioSink(fromUs = 10_000L, untilUs = 14_000L)
        sink.configure(format(C.ENCODING_PCM_FLOAT, channels = 2), 0, null)
        sink.setOutputStreamOffsetUs(rendererOffsetUs)
        val buffer = pcmFloat(1f, -1f, 0.75f, 0.25f, -0.75f, -0.25f, 0.25f, 0.75f)

        assertTrue(sink.handleBuffer(buffer, rendererOffsetUs + 10_000L, 0))

        val captured = requireNotNull(sink.window())
        assertArrayEquals(floatArrayOf(0f, 0.5f, -0.5f, 0.5f), captured.pcm, 0f)
        assertEquals(10.0, captured.startMs, 0.0)
        assertEquals(buffer.limit(), buffer.position())
        assertEquals(rendererOffsetUs + 14_000L, sink.getCurrentPositionUs(true))
    }

    @Test
    fun flushBeforeASeek_discardsOldSamplesAndResetsTheCaptureClock() {
        val sink = WaveformAudioSink(fromUs = 0L, untilUs = 4_000L)
        sink.configure(format(C.ENCODING_PCM_16BIT, channels = 1), 0, null)
        sink.setOutputStreamOffsetUs(rendererOffsetUs)
        assertTrue(sink.handleBuffer(pcm16(8_192, 16_384, 24_576, 0), rendererOffsetUs, 0))
        assertArrayEquals(floatArrayOf(0.25f, 0.5f, 0.75f, 0f), requireNotNull(sink.window()).pcm, 0f)
        assertTrue(sink.isEnded())

        sink.flush()

        assertNull(sink.window())
        assertEquals(AudioSink.CURRENT_POSITION_NOT_SET, sink.getCurrentPositionUs(false))
        assertFalse(sink.isEnded())
        val seekOffsetUs = rendererOffsetUs + 90_000L
        sink.setOutputStreamOffsetUs(seekOffsetUs)
        val buffer = pcm16(-8_192, -16_384)
        assertTrue(sink.handleBuffer(buffer, seekOffsetUs + 1_000L, 0))
        sink.playToEndOfStream()

        val captured = requireNotNull(sink.window())
        assertArrayEquals(floatArrayOf(-0.25f, -0.5f), captured.pcm, 0f)
        assertEquals(1.0, captured.startMs, 0.0)
        assertEquals(buffer.limit(), buffer.position())
        assertEquals(seekOffsetUs + 3_000L, sink.getCurrentPositionUs(false))
        assertTrue(sink.isEnded())
    }

    private fun format(encoding: Int, channels: Int): Format = Format.Builder()
        .setSampleMimeType(MimeTypes.AUDIO_RAW)
        .setPcmEncoding(encoding)
        .setSampleRate(1_000)
        .setChannelCount(channels)
        .build()

    private fun pcm16(vararg values: Int): ByteBuffer =
        ByteBuffer.allocateDirect(values.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            values.forEach { putShort(it.toShort()) }
            flip()
        }

    private fun pcmFloat(vararg values: Float): ByteBuffer =
        ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
            values.forEach { putFloat(it) }
            flip()
        }
}
