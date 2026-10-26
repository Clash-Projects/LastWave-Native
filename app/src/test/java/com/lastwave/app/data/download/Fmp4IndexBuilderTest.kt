package com.lastwave.app.data.download

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class Fmp4IndexBuilderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun box(type: String, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val size = 8 + payload.size
        out.write((size ushr 24) and 0xFF)
        out.write((size ushr 16) and 0xFF)
        out.write((size ushr 8) and 0xFF)
        out.write(size and 0xFF)
        out.write(type.toByteArray(Charsets.US_ASCII))
        out.write(payload)
        return out.toByteArray()
    }

    private fun createMvhdBox(timescale: Int = 1000, duration: Int = 0): ByteArray {
        val payload = ByteArrayOutputStream()
        payload.write(0) // version 0
        payload.write(0); payload.write(0); payload.write(0) // flags
        payload.write(ByteArray(8)) // creation / mod time
        payload.write((timescale ushr 24) and 0xFF)
        payload.write((timescale ushr 16) and 0xFF)
        payload.write((timescale ushr 8) and 0xFF)
        payload.write(timescale and 0xFF)
        payload.write((duration ushr 24) and 0xFF)
        payload.write((duration ushr 16) and 0xFF)
        payload.write((duration ushr 8) and 0xFF)
        payload.write(duration and 0xFF)
        payload.write(ByteArray(80)) // remaining mvhd fields
        return box("mvhd", payload.toByteArray())
    }

    private fun createTkhdBox(trackId: Int = 1, duration: Int = 0): ByteArray {
        val payload = ByteArrayOutputStream()
        payload.write(0) // version 0
        payload.write(0); payload.write(0); payload.write(1) // flags
        payload.write(ByteArray(8)) // creation / mod time
        payload.write((trackId ushr 24) and 0xFF)
        payload.write((trackId ushr 16) and 0xFF)
        payload.write((trackId ushr 8) and 0xFF)
        payload.write(trackId and 0xFF)
        payload.write(ByteArray(4)) // reserved
        payload.write((duration ushr 24) and 0xFF)
        payload.write((duration ushr 16) and 0xFF)
        payload.write((duration ushr 8) and 0xFF)
        payload.write(duration and 0xFF)
        payload.write(ByteArray(60)) // remaining tkhd fields
        return box("tkhd", payload.toByteArray())
    }

    private fun createMdhdBox(timescale: Int = 48000, duration: Int = 0): ByteArray {
        val payload = ByteArrayOutputStream()
        payload.write(0) // version 0
        payload.write(0); payload.write(0); payload.write(0) // flags
        payload.write(ByteArray(8)) // creation / mod time
        payload.write((timescale ushr 24) and 0xFF)
        payload.write((timescale ushr 16) and 0xFF)
        payload.write((timescale ushr 8) and 0xFF)
        payload.write(timescale and 0xFF)
        payload.write((duration ushr 24) and 0xFF)
        payload.write((duration ushr 16) and 0xFF)
        payload.write((duration ushr 8) and 0xFF)
        payload.write(duration and 0xFF)
        payload.write(ByteArray(4)) // language & pre-defined
        return box("mdhd", payload.toByteArray())
    }

    private fun createMoofBox(trackId: Int = 1, decodeTime: Long = 0L, sampleCount: Int = 10, sampleDuration: Int = 1024): ByteArray {
        // tfhd
        val tfhdPayload = ByteArrayOutputStream()
        tfhdPayload.write(0); tfhdPayload.write(0); tfhdPayload.write(0); tfhdPayload.write(0)
        tfhdPayload.write((trackId ushr 24) and 0xFF)
        tfhdPayload.write((trackId ushr 16) and 0xFF)
        tfhdPayload.write((trackId ushr 8) and 0xFF)
        tfhdPayload.write(trackId and 0xFF)
        val tfhd = box("tfhd", tfhdPayload.toByteArray())

        // tfdt (v1: 64-bit decode time)
        val tfdtPayload = ByteArrayOutputStream()
        tfdtPayload.write(1) // version 1
        tfdtPayload.write(0); tfdtPayload.write(0); tfdtPayload.write(0)
        for (i in 7 downTo 0) {
            tfdtPayload.write(((decodeTime ushr (i * 8)) and 0xFF).toInt())
        }
        val tfdt = box("tfdt", tfdtPayload.toByteArray())

        // trun
        val trunPayload = ByteArrayOutputStream()
        trunPayload.write(0)
        trunPayload.write(0); trunPayload.write(1); trunPayload.write(0) // flags: sample-duration-present (0x000100)
        trunPayload.write((sampleCount ushr 24) and 0xFF)
        trunPayload.write((sampleCount ushr 16) and 0xFF)
        trunPayload.write((sampleCount ushr 8) and 0xFF)
        trunPayload.write(sampleCount and 0xFF)
        repeat(sampleCount) {
            trunPayload.write((sampleDuration ushr 24) and 0xFF)
            trunPayload.write((sampleDuration ushr 16) and 0xFF)
            trunPayload.write((sampleDuration ushr 8) and 0xFF)
            trunPayload.write(sampleDuration and 0xFF)
        }
        val trun = box("trun", trunPayload.toByteArray())

        val traf = box("traf", tfhd + tfdt + trun)
        val mfhd = box("mfhd", byteArrayOf(0, 0, 0, 0, 0, 0, 0, 1))
        return box("moof", mfhd + traf)
    }

    private fun createMdatBox(size: Int = 100): ByteArray {
        return box("mdat", ByteArray(size))
    }

    private fun buildSampleFmp4File(): File {
        val file = tempFolder.newFile("sample.m4a")
        val out = ByteArrayOutputStream()

        out.write(box("ftyp", "M4A ".toByteArray(Charsets.US_ASCII)))

        // moov with duration = 0
        val mvhd = createMvhdBox(timescale = 1000, duration = 0)
        val mdhd = createMdhdBox(timescale = 48000, duration = 0)
        val mdia = box("mdia", mdhd)
        val tkhd = createTkhdBox(trackId = 1, duration = 0)
        val trak = box("trak", tkhd + mdia)
        val moov = box("moov", mvhd + trak)
        out.write(moov)

        // 3 fragments
        out.write(createMoofBox(trackId = 1, decodeTime = 0L, sampleCount = 10, sampleDuration = 1024))
        out.write(createMdatBox(200))

        out.write(createMoofBox(trackId = 1, decodeTime = 10240L, sampleCount = 10, sampleDuration = 1024))
        out.write(createMdatBox(200))

        out.write(createMoofBox(trackId = 1, decodeTime = 20480L, sampleCount = 10, sampleDuration = 1024))
        out.write(createMdatBox(200))

        file.writeBytes(out.toByteArray())
        return file
    }

    @Test
    fun testIsFragmentedMp4() {
        val fmp4File = buildSampleFmp4File()
        assertThat(Fmp4IndexBuilder.isFragmentedMp4(fmp4File)).isTrue()
        assertThat(Fmp4IndexBuilder.hasMfraBox(fmp4File)).isFalse()

        val nonFmp4File = tempFolder.newFile("plain.m4a")
        nonFmp4File.writeBytes(box("ftyp", "M4A ".toByteArray(Charsets.US_ASCII)) + box("moov", ByteArray(32)))
        assertThat(Fmp4IndexBuilder.isFragmentedMp4(nonFmp4File)).isFalse()
    }

    @Test
    fun testFinalizeFmp4AppendsMfraAndPatchesDuration() {
        val file = buildSampleFmp4File()
        val initialLength = file.length()

        val success = Fmp4IndexBuilder.finalizeFmp4(file, knownDurationMs = 640L)
        assertThat(success).isTrue()

        // File length should have grown
        assertThat(file.length()).isGreaterThan(initialLength)

        // hasMfraBox should now be true
        assertThat(Fmp4IndexBuilder.hasMfraBox(file)).isTrue()

        // Verify the terminal mfro box at the end
        RandomAccessFile(file, "r").use { raf ->
            val len = raf.length()
            raf.seek(len - 16)
            val footer = ByteArray(16)
            raf.readFully(footer)
            val mfroType = String(footer, 4, 4, Charsets.US_ASCII)
            assertThat(mfroType).isEqualTo("mfro")

            val mfraSize = ((footer[12].toInt() and 0xFF) shl 24) or
                ((footer[13].toInt() and 0xFF) shl 16) or
                ((footer[14].toInt() and 0xFF) shl 8) or
                (footer[15].toInt() and 0xFF)
            assertThat(mfraSize).isGreaterThan(16)

            // Verify mfra box start
            raf.seek(len - mfraSize)
            val mfraHeader = ByteArray(8)
            raf.readFully(mfraHeader)
            val mfraType = String(mfraHeader, 4, 4, Charsets.US_ASCII)
            assertThat(mfraType).isEqualTo("mfra")

            // Verify sidx box is present
            var hasSidx = false
            var pos = 0L
            val hdr = ByteArray(8)
            while (pos + 8 <= len) {
                raf.seek(pos)
                raf.readFully(hdr)
                val sz = ((hdr[0].toInt() and 0xFF) shl 24) or
                    ((hdr[1].toInt() and 0xFF) shl 16) or
                    ((hdr[2].toInt() and 0xFF) shl 8) or
                    (hdr[3].toInt() and 0xFF)
                val type = String(hdr, 4, 4, Charsets.US_ASCII)
                if (type == "sidx") hasSidx = true
                if (sz <= 0) break
                pos += sz
            }
            assertThat(hasSidx).isTrue()
        }

        // Idempotency test: calling finalize again does not duplicate mfra
        val lengthAfterFirst = file.length()
        val secondSuccess = Fmp4IndexBuilder.finalizeFmp4(file, knownDurationMs = 640L)
        assertThat(secondSuccess).isTrue()
        assertThat(file.length()).isEqualTo(lengthAfterFirst)
    }
}
