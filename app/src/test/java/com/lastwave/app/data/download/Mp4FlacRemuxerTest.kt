package com.lastwave.app.data.download

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Regression tests for the Tidal FLAC-in-MP4 unwrap.
 *
 * Tidal's lossless DASH is FLAC carried in an ISO-BMFF container, so the raw
 * download is an `.m4a` that players and tag tools report as AAC. The remux must
 * produce a genuine `.flac` whose audio frames are the untouched originals.
 */
class Mp4FlacRemuxerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val streamInfo: ByteArray = intArrayOf(
        0x10, 0x00, 0x10, 0x00, 0x00, 0x26, 0x70, 0x00, // block/frame sizes
        0x48, 0x54, 0x2B, 0x11, 0x03, 0x70, 0x03, 0x75, // rate/channels/depth
        0x19, 0xBF, 0x52, 0x7A, 0x2C, 0x95, 0x57, 0x10,
        0xB5, 0xE9, 0xF0, 0xA2, 0xA1, 0x65, 0x7B, 0xC4,
        0xED, 0x45,
    ).map { it.toByte() }.toByteArray()

    /** `dfLa` is a FullBox: version/flags then the metadata blocks. */
    private fun dfLaBox(): ByteArray {
        val payload = ByteArrayOutputStream().apply {
            write(byteArrayOf(0, 0, 0, 0))
            write(byteArrayOf(0x80.toByte(), 0x00.toByte(), 0x00.toByte(), 0x22.toByte()))
            write(streamInfo)
        }.toByteArray()
        return box("dfLa", payload)
    }

    private fun frameBytes(marker: Byte): ByteArray {
        val frames = ByteArrayOutputStream()
        repeat(8) {
            frames.write(0xFF)
            frames.write(0xF8)
            frames.write(marker.toInt())
            frames.write(0x00)
        }
        return frames.toByteArray()
    }

    private fun buildContainer(withFlac: Boolean, frames: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(box("ftyp", "M4A ".toByteArray(Charsets.US_ASCII)))
        if (withFlac) {
            // moov { trak { mdia { minf { stbl { stsd { fLaC { dfLa } } } } } } }
            val stsd = box("stsd", box("fLaC", dfLaBox()))
            val stbl = box("stbl", stsd)
            val minf = box("minf", stbl)
            val mdia = box("mdia", minf)
            val trak = box("trak", mdia)
            out.write(box("moov", trak))
        } else {
            out.write(box("moov", box("trak", box("stbl", box("stsd", box("mp4a", byteArrayOf(0)))))))
        }
        out.write(box("moof", byteArrayOf(1, 2, 3, 4)))
        out.write(box("mdat", frames))
        out.write(box("moof", byteArrayOf(5, 6, 7, 8)))
        out.write(box("mdat", frameBytes(0x11.toByte())))
        return out.toByteArray()
    }

    private fun box(type: String, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val size = payload.size + 8
        out.write((size ushr 24) and 0xFF)
        out.write((size ushr 16) and 0xFF)
        out.write((size ushr 8) and 0xFF)
        out.write(size and 0xFF)
        out.write(type.toByteArray(Charsets.US_ASCII))
        out.write(payload)
        return out.toByteArray()
    }

    @Test
    fun unwrapsFlacFramesIntoNativeFlac() {
        val input = tempFolder.newFile("raw.m4a")
        val framesA = frameBytes(0x0A.toByte())
        input.writeBytes(buildContainer(withFlac = true, frames = framesA))

        val output = File(tempFolder.root, "out.flac")
        assertThat(Mp4FlacRemuxer.remux(input, output)).isTrue()

        val bytes = output.readBytes()
        // "fLaC" magic + STREAMINFO metadata block header.
        assertThat(String(bytes, 0, 4, Charsets.US_ASCII)).isEqualTo("fLaC")
        assertThat(bytes.copyOfRange(4, 8)).isEqualTo(byteArrayOf(0x80.toByte(), 0x00.toByte(), 0x00.toByte(), 0x22.toByte()))
        // STREAMINFO payload must be the container's, byte for byte.
        assertThat(bytes.copyOfRange(8, 42)).isEqualTo(streamInfo)
        // Audio: both mdat payloads, in order, unmodified.
        val expectedAudio = framesA + frameBytes(0x11.toByte())
        assertThat(bytes.copyOfRange(42, bytes.size)).isEqualTo(expectedAudio)
    }

    @Test
    fun reportsNonFlacContainerAsNotFlac() {
        val input = tempFolder.newFile("aac.m4a")
        input.writeBytes(buildContainer(withFlac = false, frames = frameBytes(0x0A.toByte())))
        val output = File(tempFolder.root, "aac.flac")

        assertThat(Mp4FlacRemuxer.isMp4Flac(input)).isFalse()
        assertThat(Mp4FlacRemuxer.remux(input, output)).isFalse()
        assertThat(output.exists()).isFalse()
    }

    @Test
    fun detectsFlacContainer() {
        val input = tempFolder.newFile("flac.m4a")
        input.writeBytes(buildContainer(withFlac = true, frames = frameBytes(0x0A.toByte())))
        assertThat(Mp4FlacRemuxer.isMp4Flac(input)).isTrue()
    }

    @Test
    fun detectsAtmosContainerAndNeverAsFlac() {
        val atmos = tempFolder.newFile("atmos.m4a")
        atmos.writeBytes(
            box("ftyp", "M4A ".toByteArray(Charsets.US_ASCII)) +
                box("moov", box("trak", box("stbl", box("stsd", box("ec-3", box("dec3", byteArrayOf(0))))))) +
                box("mdat", frameBytes(0x0A.toByte())),
        )
        assertThat(Mp4FlacRemuxer.isMp4Spatial(atmos)).isTrue()
        assertThat(Mp4FlacRemuxer.isMp4Flac(atmos)).isFalse()

        val flac = tempFolder.newFile("stereo.m4a")
        flac.writeBytes(buildContainer(withFlac = true, frames = frameBytes(0x0A.toByte())))
        assertThat(Mp4FlacRemuxer.isMp4Spatial(flac)).isFalse()
    }

    @Test
    fun fillsZeroTotalSamplesFromFragmentDurationsSoTheFlacIsSeekable() {
        // fMP4 muxers leave total_samples = 0 (bits 108..143 of STREAMINFO).
        val zeroTotal = streamInfo.copyOf().apply {
            this[13] = (this[13].toInt() and 0xF0).toByte()
            for (i in 14..17) this[i] = 0
        }
        val dfLa = box("dfLa", byteArrayOf(0, 0, 0, 0, 0x80.toByte(), 0, 0, 0x22) + zeroTotal)
        // STREAMINFO says 176400 Hz; mdhd timescale matches, so ticks == samples.
        val mdhd = box("mdhd", ByteArray(8) + byteArrayOf(0x00, 0x02, 0xB1.toByte(), 0x10) + ByteArray(4))
        val moov = box("moov", box("trak", box("mdia", mdhd + box("minf", box("stbl", box("stsd", box("fLaC", dfLa)))))))
        // Each fragment: 2 samples x default duration 4096 (tfhd flag 0x08).
        val tfhd = box("tfhd", byteArrayOf(0, 0, 0, 0x08, 0, 0, 0, 1, 0, 0, 0x10, 0))
        val trun = box("trun", byteArrayOf(0, 0, 0, 0, 0, 0, 0, 2))
        val moof = box("moof", box("traf", tfhd + trun))
        val input = tempFolder.newFile("zero_total.m4a")
        input.writeBytes(
            box("ftyp", "M4A ".toByteArray(Charsets.US_ASCII)) + moov +
                moof + box("mdat", frameBytes(0x0A.toByte())) +
                moof + box("mdat", frameBytes(0x11.toByte())),
        )

        val output = File(tempFolder.root, "zero_total.flac")
        assertThat(Mp4FlacRemuxer.remux(input, output)).isTrue()

        val info = output.readBytes().copyOfRange(8, 42)
        val total = ((info[13].toLong() and 0x0F) shl 32) or
            ((info[14].toLong() and 0xFF) shl 24) or ((info[15].toLong() and 0xFF) shl 16) or
            ((info[16].toLong() and 0xFF) shl 8) or (info[17].toLong() and 0xFF)
        assertThat(total).isEqualTo(4L * 4096)
        // Everything else in STREAMINFO stays bit-exact.
        assertThat(info.copyOfRange(0, 13)).isEqualTo(zeroTotal.copyOfRange(0, 13))
        assertThat(info.copyOfRange(18, 34)).isEqualTo(zeroTotal.copyOfRange(18, 34))
    }

    @Test
    fun refusesTruncatedContainer() {
        val input = tempFolder.newFile("truncated.m4a")
        val full = buildContainer(withFlac = true, frames = frameBytes(0x0A.toByte()))
        input.writeBytes(full.copyOfRange(0, 20))
        val output = File(tempFolder.root, "truncated.flac")
        assertThat(Mp4FlacRemuxer.remux(input, output)).isFalse()
    }
}
