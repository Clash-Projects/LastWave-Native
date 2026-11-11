package com.lastwave.app.data.localmusic

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.lastwave.app.playback.AudioHeaderInspector
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files

class LocalMusicFeaturesTest {

    @Test
    fun localMusicFilters_handlesFlagsPathsAndUnknownDuration() {
        assertThat(
            LocalMusicFilters.isNonMusicAudio("/storage/emulated/0/Music/Song.flac", 210_000L),
        ).isFalse()
        // DSD / containers that omit duration metadata must NOT be dropped as short clips
        assertThat(
            LocalMusicFilters.isNonMusicAudio("/storage/emulated/0/DSD/Track01.dsf", null),
        ).isFalse()
        assertThat(
            LocalMusicFilters.isNonMusicAudio("/storage/emulated/0/DSD/Track01.dsf", 0L),
        ).isFalse()

        // Short clips (<30s), system flags, and non-music folders are filtered
        assertThat(
            LocalMusicFilters.isNonMusicAudio("/storage/emulated/0/Download/sfx.wav", 12_000L),
        ).isTrue()
        assertThat(
            LocalMusicFilters.isNonMusicAudio("/storage/emulated/0/Ringtones/tone.ogg", 45_000L),
        ).isTrue()
        assertThat(
            LocalMusicFilters.isNonMusicAudio("/storage/emulated/0/Music/clip.mp3", 180_000L, isAlarm = true),
        ).isTrue()
        assertThat(
            LocalMusicFilters.isNonMusicAudio("/storage/emulated/0/Recordings/Voice Recorder/memo.m4a", 95_000L),
        ).isTrue()
    }

    @Test
    fun localMusicFilters_parsesTrackAndDiscNumbers() {
        assertThat(LocalMusicFilters.parseNumberPart("03/14")).isEqualTo(3)
        assertThat(LocalMusicFilters.parseNumberPart("12")).isEqualTo(12)
        assertThat(LocalMusicFilters.parseNumberPart("0")).isNull()
        assertThat(LocalMusicFilters.parseNumberPart("-")).isNull()

        assertThat(LocalMusicFilters.splitPackedTrackDisc(1004, null)).isEqualTo(1 to 4)
        assertThat(LocalMusicFilters.splitPackedTrackDisc(2012, null)).isEqualTo(2 to 12)
        assertThat(LocalMusicFilters.splitPackedTrackDisc(7, null)).isEqualTo(null to 7)
        assertThat(LocalMusicFilters.splitPackedTrackDisc(7, 2)).isEqualTo(2 to 7)
    }

    @Test
    fun audioHeaderInspector_parsesDsfAndDffHeaders() {
        // Synthetic DSF header (80 bytes: 28 DSD chunk + 52 fmt chunk)
        val dsf = ByteArray(80)
        putAscii(dsf, 0, "DSD ")
        putLeU64(dsf, 4, 28L)
        putAscii(dsf, 28, "fmt ")
        putLeU64(dsf, 28 + 4, 52L)
        putLeU32(dsf, 28 + 16, 0L) // formatId = 0
        putLeU32(dsf, 28 + 24, 2L) // 2 channels
        putLeU32(dsf, 28 + 28, 2_822_400L) // DSD64
        putLeU32(dsf, 28 + 32, 1L) // 1 bit
        putLeU64(dsf, 28 + 36, 2_822_400L * 10L) // 10 seconds

        val dsfInfo = AudioHeaderInspector.inspectStream(ByteArrayInputStream(dsf), "track.dsf")
        assertThat(dsfInfo).isNotNull()
        assertThat(dsfInfo!!.isDsd).isTrue()
        assertThat(dsfInfo.dsdBadge).isEqualTo("DSD64")
        assertThat(dsfInfo.dsdOutputRateHz).isEqualTo(176_400)
        assertThat(dsfInfo.channels).isEqualTo(2)
        assertThat(dsfInfo.durationMs).isEqualTo(10_000L)

        // Synthetic DFF header (FRM8 + DSD + PROP[SND + FS + CHNL + CMPR] + DSD data chunk)
        val out = ByteArrayOutputStream()
        out.writeAscii("FRM8")
        out.writeBeU64(120L)
        out.writeAscii("DSD ")
        out.writeAscii("PROP")
        out.writeBeU64(50L) // 4 + 16 + 14 + 16
        out.writeAscii("SND ")
        out.writeAscii("FS  "); out.writeBeU64(4L); out.writeBeU32(5_644_800L) // DSD128
        out.writeAscii("CHNL"); out.writeBeU64(2L); out.writeBeU16(2)
        out.writeAscii("CMPR"); out.writeBeU64(4L); out.writeAscii("DSD ")
        out.writeAscii("DSD "); out.writeBeU64(5_644_800L) // 4s audio
        val dffInfo = AudioHeaderInspector.inspectStream(ByteArrayInputStream(out.toByteArray()), "track.dff")
        assertThat(dffInfo).isNotNull()
        assertThat(dffInfo!!.isDsd).isTrue()
        assertThat(dffInfo.dsdBadge).isEqualTo("DSD128")
        assertThat(dffInfo.dsdOutputRateHz).isEqualTo(176_400)
    }

    @Test
    fun audioHeaderInspector_parsesAiffWavAndAlac() {
        // Synthetic AIFF (24-bit / 96 kHz stereo)
        val aiff = ByteArrayOutputStream()
        aiff.writeAscii("FORM")
        aiff.writeBeU32(48L)
        aiff.writeAscii("AIFF")
        aiff.writeAscii("COMM")
        aiff.writeBeU32(18L)
        aiff.writeBeU16(2) // channels
        aiff.writeBeU32(192_000L) // frames (2 seconds at 96kHz)
        aiff.writeBeU16(24) // bits
        aiff.write(byteArrayOf(0x40, 0x0F, 0xBB.toByte(), 0x80.toByte(), 0, 0, 0, 0, 0, 0)) // 96000 Hz IEEE extended
        val aiffInfo = AudioHeaderInspector.inspectStream(ByteArrayInputStream(aiff.toByteArray()), "track.aiff")
        assertThat(aiffInfo).isNotNull()
        assertThat(aiffInfo!!.containerLabel).isEqualTo("AIFF")
        assertThat(aiffInfo.sampleRateHz).isEqualTo(96_000)
        assertThat(aiffInfo.bitDepth).isEqualTo(24)
        assertThat(aiffInfo.durationMs).isEqualTo(2_000L)

        // Synthetic WAV (24-bit / 48 kHz)
        val wav = ByteArray(44)
        putAscii(wav, 0, "RIFF")
        putLeU32(wav, 4, 36L)
        putAscii(wav, 8, "WAVE")
        putAscii(wav, 12, "fmt ")
        putLeU32(wav, 16, 16L)
        putLeU16(wav, 20, 1) // PCM
        putLeU16(wav, 22, 2) // stereo
        putLeU32(wav, 24, 48_000L)
        putLeU32(wav, 28, 288_000L) // 48k * 2 * 3
        putLeU16(wav, 32, 6)
        putLeU16(wav, 34, 24)
        putAscii(wav, 36, "data")
        putLeU32(wav, 40, 288_000L) // 1s
        val wavInfo = AudioHeaderInspector.inspectStream(ByteArrayInputStream(wav), "track.wav")
        assertThat(wavInfo).isNotNull()
        assertThat(wavInfo!!.containerLabel).isEqualTo("WAV")
        assertThat(wavInfo.sampleRateHz).isEqualTo(48_000)
        assertThat(wavInfo.bitDepth).isEqualTo(24)
        assertThat(wavInfo.durationMs).isEqualTo(1_000L)

        // Synthetic MP4/ALAC header
        val alac = ByteArrayOutputStream()
        alac.writeBeU32(20L); alac.writeAscii("ftyp"); alac.writeAscii("M4A "); alac.writeBeU32(0L); alac.writeAscii("M4A ")
        alac.writeBeU32(36L); alac.writeAscii("alac"); alac.writeBeU32(0L) // version=0, flags=0
        alac.writeBeU32(4096L) // frameLength
        alac.write(0) // compatibleVersion
        alac.write(24) // bitDepth
        alac.write(byteArrayOf(40, 10, 14, 2)) // pb, mb, kb, numChannels=2
        alac.writeBeU16(255) // maxRun
        alac.writeBeU32(16384L) // maxFrameBytes
        alac.writeBeU32(950_000L) // avgBitRate
        alac.writeBeU32(44_100L) // sampleRate
        val alacInfo = AudioHeaderInspector.inspectStream(ByteArrayInputStream(alac.toByteArray()), "track.m4a")
        assertThat(alacInfo).isNotNull()
        assertThat(alacInfo!!.containerLabel).isEqualTo("ALAC")
        assertThat(alacInfo.sampleRateHz).isEqualTo(44_100)
        assertThat(alacInfo.bitDepth).isEqualTo(24)
    }

    @Test
    fun containerLyricsReader_extractsId3UsltSyltFlacAndMp4Lyrics() {
        // 1. ID3v2.3 USLT
        val usltPayload = byteArrayOf(3) + "eng".toByteArray() + byteArrayOf(0) + "Line one\nLine two".toByteArray()
        val id3Uslt = buildId3v23("USLT", usltPayload)
        val parsedUslt = LocalContainerLyricsReader.extractFromStream(BufferedInputStream(ByteArrayInputStream(id3Uslt)))
        assertThat(parsedUslt).isNotNull()
        assertThat(parsedUslt!!.text).isEqualTo("Line one\nLine two")
        assertThat(parsedUslt.isLrcFormatted).isFalse()

        // 2. ID3v2.3 SYLT (ms timestamps)
        val syltBody = ByteArrayOutputStream()
        syltBody.write(3) // UTF-8
        syltBody.write("eng".toByteArray())
        syltBody.write(2) // timestamp format = ms
        syltBody.write(1) // content type = lyrics
        syltBody.write(0) // empty descriptor
        syltBody.write("First line".toByteArray())
        syltBody.write(0)
        syltBody.writeBeU32(1500L)
        val parsedSylt = LocalContainerLyricsReader.extractFromStream(
            BufferedInputStream(ByteArrayInputStream(buildId3v23("SYLT", syltBody.toByteArray()))),
        )
        assertThat(parsedSylt).isNotNull()
        assertThat(parsedSylt!!.isLrcFormatted).isTrue()
        assertThat(parsedSylt.text).contains("[00:01.50]First line")

        // 3. FLAC VORBIS_COMMENT
        val comment = "LYRICS=[00:05.25]Vorbis synced".toByteArray(Charsets.UTF_8)
        val vcBlob = ByteArrayOutputStream()
        vcBlob.writeLeU32(0L) // vendor len
        vcBlob.writeLeU32(1L) // entry count
        vcBlob.writeLeU32(comment.size.toLong())
        vcBlob.write(comment)
        val vcBytes = vcBlob.toByteArray()
        val flac = ByteArrayOutputStream()
        flac.writeAscii("fLaC")
        flac.write(0x80 or 4) // last block = true, type = VORBIS_COMMENT (4)
        flac.write((vcBytes.size ushr 16) and 0xFF)
        flac.write((vcBytes.size ushr 8) and 0xFF)
        flac.write(vcBytes.size and 0xFF)
        flac.write(vcBytes)
        val parsedFlac = LocalContainerLyricsReader.extractFromStream(BufferedInputStream(ByteArrayInputStream(flac.toByteArray())))
        assertThat(parsedFlac).isNotNull()
        assertThat(parsedFlac!!.isLrcFormatted).isTrue()
        assertThat(parsedFlac.sourceLabel).isEqualTo("Embedded FLAC")
        assertThat(parsedFlac.text).contains("Vorbis synced")

        // 4. MP4 moov -> udta -> meta -> ilst -> ©lyr
        val lyricBytes = "MP4 verse".toByteArray(Charsets.UTF_8)
        val dataBox = wrapMp4("data", ByteArray(8) + lyricBytes)
        val lyrBox = ByteArrayOutputStream().apply {
            writeBeU32((8 + dataBox.size).toLong())
            write(byteArrayOf(0xA9.toByte(), 'l'.code.toByte(), 'y'.code.toByte(), 'r'.code.toByte()))
            write(dataBox)
        }.toByteArray()
        val ilstBox = wrapMp4("ilst", lyrBox)
        val metaBox = wrapMp4("meta", ByteArray(4) + ilstBox)
        val udtaBox = wrapMp4("udta", metaBox)
        val moovBox = wrapMp4("moov", udtaBox)
        val mp4 = ByteArrayOutputStream().apply {
            writeBeU32(20L); writeAscii("ftyp"); writeAscii("M4A "); writeBeU32(0L); writeAscii("M4A ")
            write(moovBox)
        }.toByteArray()
        val parsedMp4 = LocalContainerLyricsReader.extractFromStream(BufferedInputStream(ByteArrayInputStream(mp4)))
        assertThat(parsedMp4).isNotNull()
        assertThat(parsedMp4!!.text).isEqualTo("MP4 verse")
        assertThat(parsedMp4.sourceLabel).isEqualTo("Embedded M4A")
    }

    @Test
    fun offlineRepository_persistsLikesAndPlaylistsIsolated() = runTest {
        val dir = Files.createTempDirectory("lastwave_offline_test").toFile()
        try {
            val ctx = mockk<Context>()
            every { ctx.filesDir } returns dir

            val repo = LocalOfflineRepository(ctx)
            assertThat(repo.toggleLike("file:///music/a.flac")).isTrue()
            assertThat(repo.isLiked("/music/a.flac")).isTrue()

            val pl = repo.createPlaylist("Hi-Res", listOf("file:///music/a.flac", "/music/b.dsf"))
            assertThat(pl.name).isEqualTo("Hi-Res")
            assertThat(pl.trackUrls).containsExactly("/music/a.flac", "/music/b.dsf").inOrder()

            repo.renamePlaylist(pl.id, "Master DSD")
            val added = repo.addTracksToPlaylist(pl.id, listOf("/music/b.dsf", "/music/c.aiff"))
            assertThat(added).isEqualTo(1)

            // Re-open repository against same filesDir to verify disk persistence
            val reloaded = LocalOfflineRepository(ctx)
            assertThat(reloaded.isLiked("/music/a.flac")).isTrue()
            val reloadedPl = reloaded.playlists.value.single()
            assertThat(reloadedPl.name).isEqualTo("Master DSD")
            assertThat(reloadedPl.trackUrls).containsExactly("/music/a.flac", "/music/b.dsf", "/music/c.aiff").inOrder()

            reloaded.pruneMissing(setOf("/music/a.flac"))
            assertThat(reloaded.playlists.value.single().trackUrls).containsExactly("/music/a.flac")

            reloaded.deletePlaylist(pl.id)
            assertThat(reloaded.playlists.value).isEmpty()
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun buildId3v23(frameId: String, payload: ByteArray): ByteArray {
        val frameSize = payload.size
        val bodySize = 10 + frameSize
        return ByteArrayOutputStream().apply {
            writeAscii("ID3")
            write(3); write(0); write(0) // v2.3.0
            writeSyncSafe(bodySize)
            writeAscii(frameId)
            writeBeU32(frameSize.toLong())
            write(0); write(0) // frame flags
            write(payload)
        }.toByteArray()
    }

    private fun wrapMp4(type: String, payload: ByteArray): ByteArray = ByteArrayOutputStream().apply {
        writeBeU32((8 + payload.size).toLong())
        writeAscii(type)
        write(payload)
    }.toByteArray()

    private fun putAscii(dst: ByteArray, offset: Int, s: String) {
        s.toByteArray(Charsets.US_ASCII).copyInto(dst, offset)
    }

    private fun putLeU16(dst: ByteArray, o: Int, v: Int) {
        dst[o] = (v and 0xFF).toByte()
        dst[o + 1] = ((v ushr 8) and 0xFF).toByte()
    }

    private fun putLeU32(dst: ByteArray, o: Int, v: Long) {
        dst[o] = (v and 0xFFL).toByte()
        dst[o + 1] = ((v ushr 8) and 0xFFL).toByte()
        dst[o + 2] = ((v ushr 16) and 0xFFL).toByte()
        dst[o + 3] = ((v ushr 24) and 0xFFL).toByte()
    }

    private fun putLeU64(dst: ByteArray, o: Int, v: Long) {
        for (i in 0..7) dst[o + i] = ((v ushr (i * 8)) and 0xFFL).toByte()
    }

    private fun ByteArrayOutputStream.writeAscii(s: String) = write(s.toByteArray(Charsets.US_ASCII))

    private fun ByteArrayOutputStream.writeBeU16(v: Int) {
        write((v ushr 8) and 0xFF); write(v and 0xFF)
    }

    private fun ByteArrayOutputStream.writeBeU32(v: Long) {
        write(((v ushr 24) and 0xFFL).toInt())
        write(((v ushr 16) and 0xFFL).toInt())
        write(((v ushr 8) and 0xFFL).toInt())
        write((v and 0xFFL).toInt())
    }

    private fun ByteArrayOutputStream.writeBeU64(v: Long) {
        for (i in 7 downTo 0) write(((v ushr (i * 8)) and 0xFFL).toInt())
    }

    private fun ByteArrayOutputStream.writeLeU32(v: Long) {
        write((v and 0xFFL).toInt())
        write(((v ushr 8) and 0xFFL).toInt())
        write(((v ushr 16) and 0xFFL).toInt())
        write(((v ushr 24) and 0xFFL).toInt())
    }

    private fun ByteArrayOutputStream.writeSyncSafe(v: Int) {
        write((v ushr 21) and 0x7F)
        write((v ushr 14) and 0x7F)
        write((v ushr 7) and 0x7F)
        write(v and 0x7F)
    }
}
