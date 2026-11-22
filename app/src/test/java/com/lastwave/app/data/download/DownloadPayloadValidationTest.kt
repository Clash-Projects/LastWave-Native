package com.lastwave.app.data.download

import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DownloadPayloadValidationTest {
    @get:Rule val temp = TemporaryFolder()
    private val manager = mockk<TrackDownloadManager>()

    @Test fun acceptsSupportedContainersRegardlessOfAdvertisedExtension() {
        val headers = mapOf(
            "m4a" to byteArrayOf(0, 0, 0, 24, 102, 116, 121, 112),
            "webm" to byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()),
            "opus" to "OggS".toByteArray(),
            "mp3" to "ID3".toByteArray(),
            "flac" to "fLaC".toByteArray(),
        )
        headers.forEach { (expected, header) ->
            val file = temp.newFile().apply { writeBytes(ByteArray(2_048).also { header.copyInto(it) }) }
            assertEquals(expected, manager.validateDownloadedAudio(file))
        }
    }

    @Test fun rejectsLargeErrorPayloadEvenWithMisleadingContentType() {
        val file = temp.newFile().apply { writeText("<html>Access denied</html>".repeat(100)) }
        assertThrows(DownloadProtocolException::class.java) { manager.validateDownloadedAudio(file) }
    }

    @Test fun rejectsTruncatedPayloadEvenWithValidSignature() {
        val file = temp.newFile().apply { writeBytes("OggS".toByteArray() + ByteArray(24)) }
        assertThrows(DownloadProtocolException::class.java) { manager.validateDownloadedAudio(file) }
    }
}
