package com.lastwave.app.playback.dsd

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

data class DsdMetadata(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val artworkBytes: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as DsdMetadata
        return title == other.title &&
            artist == other.artist &&
            album == other.album &&
            albumArtist == other.albumArtist &&
            trackNumber == other.trackNumber &&
            discNumber == other.discNumber &&
            (artworkBytes?.contentEquals(other.artworkBytes) ?: (other.artworkBytes == null))
    }

    override fun hashCode(): Int {
        var result = title?.hashCode() ?: 0
        result = 31 * result + (artist?.hashCode() ?: 0)
        result = 31 * result + (album?.hashCode() ?: 0)
        result = 31 * result + (albumArtist?.hashCode() ?: 0)
        result = 31 * result + (trackNumber ?: 0)
        result = 31 * result + (discNumber ?: 0)
        result = 31 * result + (artworkBytes?.contentHashCode() ?: 0)
        return result
    }
}

/**
 * Lightweight, zero-copy ID3v2 reader for DSF / DFF 1-bit audio files.
 * Seeks directly to the `metadataOffset` pointer in the DSF header
 * without reading intermediate audio blocks.
 */
object DsdMetadataReader {

    fun read(context: Context, audioUrl: String): DsdMetadata? = runCatching {
        if (audioUrl.startsWith("content://")) {
            val uri = Uri.parse(audioUrl)
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                FileInputStream(pfd.fileDescriptor).use { stream ->
                    readFromChannel(stream.channel)
                }
            }
        } else {
            val path = audioUrl.removePrefix("file://")
            val file = File(path)
            if (!file.isFile || !file.canRead()) return null
            FileInputStream(file).use { stream ->
                readFromChannel(stream.channel)
            }
        }
    }.getOrNull()

    private fun readFromChannel(channel: FileChannel): DsdMetadata? {
        val size = channel.size()
        if (size < 28L) return null

        val header = ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN)
        channel.position(0L)
        channel.read(header)
        header.flip()

        val magic = ByteArray(4)
        header.get(magic)
        val fourCc = String(magic, Charsets.US_ASCII)

        val metadataOffset = if (fourCc == "DSD ") {
            header.position(20)
            header.getLong()
        } else if (fourCc == "ID3") {
            // Some files prepend ID3 directly at offset 0
            0L
        } else {
            return null
        }

        if (metadataOffset < 0L || metadataOffset >= size) return null

        channel.position(metadataOffset)
        val id3Head = ByteBuffer.allocate(10).order(ByteOrder.BIG_ENDIAN)
        val readHead = channel.read(id3Head)
        if (readHead < 10) return null
        id3Head.flip()

        val id3Magic = ByteArray(3)
        id3Head.get(id3Magic)
        if (String(id3Magic, Charsets.US_ASCII) != "ID3") return null

        val major = id3Head.get().toInt() and 0xFF
        val revision = id3Head.get().toInt() and 0xFF
        val flags = id3Head.get().toInt() and 0xFF

        val b0 = id3Head.get().toInt() and 0x7F
        val b1 = id3Head.get().toInt() and 0x7F
        val b2 = id3Head.get().toInt() and 0x7F
        val b3 = id3Head.get().toInt() and 0x7F
        val tagSize = (b0 shl 21) or (b1 shl 14) or (b2 shl 7) or b3
        if (tagSize <= 0) return null

        val readSize = tagSize.coerceAtMost(10 * 1024 * 1024)
        val tagBytes = ByteArray(readSize)
        val tagBuf = ByteBuffer.wrap(tagBytes)
        channel.read(tagBuf)

        return parseId3Frames(tagBytes, major, flags)
    }

    private fun parseId3Frames(tagBytes: ByteArray, major: Int, flags: Int): DsdMetadata {
        var offset = 0
        val total = tagBytes.size

        // Skip extended header if present
        if ((flags and 0x40) != 0 && total >= 4) {
            val extSize = if (major == 4) {
                ((tagBytes[0].toInt() and 0x7F) shl 21) or
                    ((tagBytes[1].toInt() and 0x7F) shl 14) or
                    ((tagBytes[2].toInt() and 0x7F) shl 7) or
                    (tagBytes[3].toInt() and 0x7F)
            } else {
                ((tagBytes[0].toInt() and 0xFF) shl 24) or
                    ((tagBytes[1].toInt() and 0xFF) shl 16) or
                    ((tagBytes[2].toInt() and 0xFF) shl 8) or
                    (tagBytes[3].toInt() and 0xFF) + 4
            }
            offset = extSize.coerceIn(0, total)
        }

        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var albumArtist: String? = null
        var trackNumber: Int? = null
        var discNumber: Int? = null
        var artworkBytes: ByteArray? = null

        val minFrameHead = if (major <= 2) 6 else 10

        while (offset + minFrameHead <= total) {
            if (tagBytes[offset] == 0.toByte()) break
            val frameId: String
            val frameSize: Int
            if (major <= 2) {
                frameId = String(tagBytes, offset, 3, Charsets.US_ASCII)
                frameSize = ((tagBytes[offset + 3].toInt() and 0xFF) shl 16) or
                    ((tagBytes[offset + 4].toInt() and 0xFF) shl 8) or
                    (tagBytes[offset + 5].toInt() and 0xFF)
                offset += 6
            } else {
                frameId = String(tagBytes, offset, 4, Charsets.US_ASCII)
                frameSize = if (major == 4) {
                    ((tagBytes[offset + 4].toInt() and 0x7F) shl 21) or
                        ((tagBytes[offset + 5].toInt() and 0x7F) shl 14) or
                        ((tagBytes[offset + 6].toInt() and 0x7F) shl 7) or
                        (tagBytes[offset + 7].toInt() and 0x7F)
                } else {
                    ((tagBytes[offset + 4].toInt() and 0xFF) shl 24) or
                        ((tagBytes[offset + 5].toInt() and 0xFF) shl 16) or
                        ((tagBytes[offset + 6].toInt() and 0xFF) shl 8) or
                        (tagBytes[offset + 7].toInt() and 0xFF)
                }
                offset += 10
            }

            if (frameSize <= 0 || offset + frameSize > total) break
            val frameData = tagBytes.copyOfRange(offset, offset + frameSize)
            offset += frameSize

            when (frameId) {
                "TIT2", "TT2" -> decodeText(frameData)?.takeIf { it.isNotBlank() }?.let { title = it }
                "TPE1", "TP1" -> decodeText(frameData)?.takeIf { it.isNotBlank() }?.let { artist = it }
                "TALB", "TAL" -> decodeText(frameData)?.takeIf { it.isNotBlank() }?.let { album = it }
                "TPE2", "TP2" -> decodeText(frameData)?.takeIf { it.isNotBlank() }?.let { albumArtist = it }
                "TRCK", "TRK" -> decodeText(frameData)?.let(::parseIndexPart)?.let { trackNumber = it }
                "TPOS", "TPA" -> decodeText(frameData)?.let(::parseIndexPart)?.let { discNumber = it }
                "APIC", "PIC" -> {
                    if (artworkBytes == null) {
                        decodeApic(frameData, isV2 = major <= 2)?.let { artworkBytes = it }
                    }
                }
            }
        }

        return DsdMetadata(
            title = title,
            artist = artist,
            album = album,
            albumArtist = albumArtist,
            trackNumber = trackNumber,
            discNumber = discNumber,
            artworkBytes = artworkBytes,
        )
    }

    private fun decodeText(frameData: ByteArray): String? {
        if (frameData.isEmpty()) return null
        val enc = frameData[0].toInt() and 0xFF
        val charset = when (enc) {
            1 -> Charsets.UTF_16
            2 -> Charsets.UTF_16BE
            3 -> Charsets.UTF_8
            else -> Charsets.ISO_8859_1
        }
        return runCatching {
            String(frameData, 1, frameData.size - 1, charset).trim().trim('\u0000')
        }.getOrNull()
    }

    private fun parseIndexPart(raw: String?): Int? {
        if (raw.isNullOrBlank()) return null
        val clean = raw.trim().substringBefore('/').substringBefore(' ').trim()
        return clean.toIntOrNull()?.takeIf { it > 0 }
    }

    private fun decodeApic(frameData: ByteArray, isV2: Boolean): ByteArray? {
        if (frameData.size < 6) return null
        val enc = frameData[0].toInt() and 0xFF
        var pos = 1
        if (isV2) {
            pos += 3
        } else {
            while (pos < frameData.size && frameData[pos] != 0.toByte()) {
                pos++
            }
            pos++
        }
        if (pos >= frameData.size) return null
        val picType = frameData[pos].toInt() and 0xFF
        pos++

        if (enc == 1 || enc == 2) {
            while (pos + 1 < frameData.size && !(frameData[pos] == 0.toByte() && frameData[pos + 1] == 0.toByte())) {
                pos += 2
            }
            pos += 2
        } else {
            while (pos < frameData.size && frameData[pos] != 0.toByte()) {
                pos++
            }
            pos++
        }
        if (pos >= frameData.size) return null

        return frameData.copyOfRange(pos, frameData.size)
    }
}
