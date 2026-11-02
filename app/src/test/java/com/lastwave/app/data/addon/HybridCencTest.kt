package com.lastwave.app.data.addon

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class HybridCencTest {
    private val sampleIv = ByteArray(16) { (0x11 * (it + 1) and 0xFF).toByte() }
    private val keyHex = "00112233445566778899aabbccddeeff"

    private fun concat(vararg parts: ByteArray): ByteArray = parts.fold(ByteArray(0)) { a, b -> a + b }
    private fun u32(v: Int): ByteArray = ByteBuffer.allocate(4).putInt(v).array()

    private fun box(type: String, vararg parts: ByteArray): ByteArray {
        val body = concat(*parts)
        return u32(8 + body.size) + type.toByteArray(Charsets.ISO_8859_1) + body
    }

    private fun fullBox(type: String, flags: Int, vararg parts: ByteArray): ByteArray =
        box(type, byteArrayOf(0, (flags shr 16).toByte(), (flags shr 8).toByte(), flags.toByte()), *parts)

    private fun initSegment(ivSize: Int): ByteArray {
        val tenc = ByteArray(2 + 1 + 1 + 16 + 1 + 16)
        tenc[2] = 1
        tenc[3] = ivSize.toByte()
        sampleIv.copyInto(tenc, 4)
        tenc[20] = 16
        sampleIv.copyInto(tenc, 21)
        val sinf = box(
            "sinf",
            box("frma", "fLaC".toByteArray()),
            fullBox("schm", 0, "cenc".toByteArray()),
            box("schi", fullBox("tenc", 0, tenc)),
        )
        val entry = box("encv", ByteArray(28), sinf)
        val stsd = fullBox("stsd", 0, u32(1), entry)
        return box("moov", box("trak", box("mdia", box("minf", box("stbl", stsd)))))
    }

    private fun mediaSegment(payload: ByteArray): ByteArray {
        fun moof(dataOffset: Int): ByteArray {
            val senc = fullBox("senc", 0, u32(1), sampleIv)
            val trun = fullBox("trun", 0x301, u32(1), u32(dataOffset), u32(payload.size), u32(payload.size))
            val traf = box("traf", fullBox("tfhd", 0), trun, senc)
            return box("moof", fullBox("mfhd", 0, u32(1)), traf)
        }
        val offset = moof(0).size + 8
        return moof(offset) + box("mdat", payload)
    }

    @Test
    fun patchInitRestoresSampleFormatAndHidesSinf() {
        val init = initSegment(16)
        val size = init.size
        val info = HybridCenc.patchInitSegment(init)
        assertThat(info.ivSize).isEqualTo(16)
        assertThat(info.constantIv).isEmpty()
        assertThat(init.size).isEqualTo(size)

        val stsd = HybridCenc.findPath(init, 0, init.size, listOf("moov", "trak", "mdia", "minf", "stbl", "stsd"))!!
        val entry = HybridCenc.iterBoxes(init, stsd.contentStart + 8, stsd.end).first()
        assertThat(String(init, entry.start + 4, 4)).isEqualTo("fLaC")
        assertThat(HybridCenc.findBox(init, entry.start + 36, entry.end, "sinf")).isNull()
    }

    @Test
    fun patchInitReadsConstantIvWhenPerSampleIvIsZero() {
        val info = HybridCenc.patchInitSegment(initSegment(0))
        assertThat(info.ivSize).isEqualTo(0)
        assertThat(info.constantIv).isEqualTo(sampleIv)
    }

    @Test
    fun decryptSegmentRestoresPlaintext() {
        val key = HybridCenc.importContentKey(keyHex)
        val plain = ByteArray(64) { ((it * 7 + 3) and 0xFF).toByte() }
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(sampleIv))
        val segment = mediaSegment(cipher.doFinal(plain))
        val length = segment.size

        HybridCenc.decryptSegment(segment, key, 16, ByteArray(0), 0)

        assertThat(segment.size).isEqualTo(length)
        val mdat = HybridCenc.findBox(segment, 0, segment.size, "mdat")!!
        assertThat(segment.copyOfRange(mdat.contentStart, mdat.end)).isEqualTo(plain)
        assertThat(HybridCenc.findPath(segment, 0, segment.size, listOf("moof", "traf", "senc"))).isNull()
    }

    @Test(expected = DecryptException::class)
    fun badKeyLengthIsRejected() {
        HybridCenc.importContentKey("abcd")
    }
}
