package com.lastwave.app.data.recognition

import com.google.common.truth.Truth.assertThat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import kotlin.random.Random
import org.junit.Test

class AudioSignatureTest {

    private fun noise(seconds: Int): ShortArray {
        val rnd = Random(42)
        return ShortArray(AudioSignature.SAMPLE_RATE * seconds) { (rnd.nextInt(-8000, 8000)).toShort() }
    }

    @Test
    fun headerIsConsistent() {
        val bytes = AudioSignature.encode(noise(4))
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertThat(b.getInt(0)).isEqualTo(0xCAFE2580.toInt())
        assertThat(b.getInt(8)).isEqualTo(bytes.size - 48)
        assertThat(b.getInt(48)).isEqualTo(0x40000000)
        assertThat(b.getInt(52)).isEqualTo(bytes.size - 48)
        val crc = CRC32().apply { update(bytes, 8, bytes.size - 8) }
        assertThat(b.getInt(4)).isEqualTo(crc.value.toInt())
    }

    @Test
    fun noiseProducesBandChunks() {
        val bytes = AudioSignature.encode(noise(4))
        assertThat(bytes.size).isGreaterThan(56 + 8)
        val tag = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(56)
        assertThat(tag).isAtLeast(0x60030040)
        assertThat(tag).isAtMost(0x60030043)
    }

    @Test
    fun fftFindsToneBin() {
        val n = 2048
        val frame = DoubleArray(n) { Math.sin(2 * Math.PI * 100 * it / n) }
        val power = DoubleArray(n / 2 + 1)
        SpectralFft(n).powerSpectrum(frame, power)
        assertThat(power.indices.maxByOrNull { power[it] }).isEqualTo(100)
    }
}
