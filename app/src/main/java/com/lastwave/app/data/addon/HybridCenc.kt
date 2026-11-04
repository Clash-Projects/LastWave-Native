package com.lastwave.app.data.addon

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * hybrid-v1 addon capability: the client downloads encrypted fMP4 chunks
 * straight from the CDN and decrypts them locally (AES-128-CTR / CENC) with
 * the content key the addon server hands out. Port of the desktop
 * `hybrid_cenc.dart`.
 */
class DecryptException(message: String) : Exception(message)

@Serializable
data class HybridRange(
    @SerialName("start") val start: Long = 0,
    @SerialName("end") val end: Long = 0,
) {
    val size: Long get() = end - start + 1
}

@Serializable
data class HybridPlan(
    @SerialName("cdnUrl") val cdnUrl: String = "",
    @SerialName("initRange") val initRange: HybridRange? = null,
    @SerialName("ranges") val ranges: List<HybridRange> = emptyList(),
    @SerialName("key") val key: String = "",
    @SerialName("ivSize") val ivSize: Int = 16,
    @SerialName("constantIv") val constantIv: String = "",
    @SerialName("encrypted") val encrypted: Boolean = false,
) {
    /** Same acceptance rules as the desktop client's `HybridPlan.fromJson`. */
    val isUsable: Boolean
        get() = cdnUrl.isNotBlank() && initRange != null && ranges.isNotEmpty()
}

class TencInfo(val ivSize: Int, val constantIv: ByteArray)

class Subsample(val clear: Int, val enc: Long)

class SencEntry(val iv: ByteArray, val subsamples: List<Subsample>)

class CencBox(val type: String, val start: Int, val contentStart: Int, val end: Int)

object HybridCenc {
    private const val AUDIO_SAMPLE_ENTRY_HEADER = 36
    private val PROTECTION_BOXES = setOf("senc", "saiz", "saio")
    private const val SENC_SUBSAMPLES_FLAG = 0x000002

    private fun u32(b: ByteArray, off: Int): Long =
        ((b[off].toLong() and 0xFF) shl 24) or
            ((b[off + 1].toLong() and 0xFF) shl 16) or
            ((b[off + 2].toLong() and 0xFF) shl 8) or
            (b[off + 3].toLong() and 0xFF)

    private fun u64(b: ByteArray, off: Int): Long = (u32(b, off) shl 32) or u32(b, off + 4)

    private fun u16(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

    private fun flags24(b: ByteArray, versionOff: Int): Int =
        ((b[versionOff + 1].toInt() and 0xFF) shl 16) or
            ((b[versionOff + 2].toInt() and 0xFF) shl 8) or
            (b[versionOff + 3].toInt() and 0xFF)

    private fun ascii(b: ByteArray, off: Int, len: Int): String =
        String(b, off, len, Charsets.ISO_8859_1)

    private fun writeAscii(b: ByteArray, off: Int, str: String) {
        for (i in str.indices) b[off + i] = str[i].code.toByte()
    }

    fun iterBoxes(buf: ByteArray, start: Int, end: Int): List<CencBox> {
        val out = ArrayList<CencBox>()
        var off = start
        while (off + 8 <= end) {
            var size = u32(buf, off)
            val type = ascii(buf, off + 4, 4)
            var header = 8
            if (size == 1L) {
                if (off + 16 > end) break
                size = u64(buf, off + 8)
                header = 16
            } else if (size == 0L) {
                size = (end - off).toLong()
            }
            if (size < header || off + size > end) break
            out.add(CencBox(type, off, off + header, (off + size).toInt()))
            off += size.toInt()
        }
        return out
    }

    fun findBox(buf: ByteArray, start: Int, end: Int, type: String): CencBox? =
        iterBoxes(buf, start, end).firstOrNull { it.type == type }

    fun findPath(buf: ByteArray, start: Int, end: Int, path: List<String>): CencBox? {
        var curStart = start
        var curEnd = end
        var found: CencBox? = null
        for (type in path) {
            found = findBox(buf, curStart, curEnd, type) ?: return null
            curStart = found.contentStart
            curEnd = found.end
        }
        return found
    }

    private fun parseTenc(buf: ByteArray, contentStart: Int, boxEnd: Int): TencInfo {
        if (contentStart + 8 + 16 > boxEnd) throw DecryptException("tenc box truncated")
        val isProtected = buf[contentStart + 6].toInt() and 0xFF
        val ivSize = buf[contentStart + 7].toInt() and 0xFF
        var constantIv = ByteArray(0)
        if (ivSize == 0 && isProtected != 0) {
            val off = contentStart + 8 + 16
            if (off < boxEnd) {
                val civSize = buf[off].toInt() and 0xFF
                if (off + 1 + civSize <= boxEnd) {
                    constantIv = buf.copyOfRange(off + 1, off + 1 + civSize)
                }
            }
        }
        return TencInfo(ivSize, constantIv)
    }

    /**
     * Restores the original sample format (frma) and neutralises the sinf box,
     * in place, so the player sees a clear fMP4. Returns the tenc parameters.
     */
    fun patchInitSegment(buf: ByteArray): TencInfo {
        val moov = findBox(buf, 0, buf.size, "moov") ?: throw DecryptException("no moov box found")
        val stsd = findPath(buf, moov.contentStart, moov.end, listOf("trak", "mdia", "minf", "stbl", "stsd"))
            ?: throw DecryptException("no moov/trak/mdia/minf/stbl/stsd box")

        var ivSize: Int? = null
        var constantIv = ByteArray(0)

        for (entry in iterBoxes(buf, stsd.contentStart + 8, stsd.end)) {
            val childStart = entry.start + AUDIO_SAMPLE_ENTRY_HEADER
            if (childStart >= entry.end) continue
            val sinf = findBox(buf, childStart, entry.end, "sinf") ?: continue

            findBox(buf, sinf.contentStart, sinf.end, "frma")?.let { frma ->
                writeAscii(buf, entry.start + 4, ascii(buf, frma.contentStart, 4))
            }
            findBox(buf, sinf.contentStart, sinf.end, "schm")?.let { schm ->
                val scheme = ascii(buf, schm.contentStart + 4, 4)
                if (scheme != "cenc") {
                    throw DecryptException("unsupported protection scheme $scheme; only cenc is supported")
                }
            }
            findPath(buf, sinf.contentStart, sinf.end, listOf("schi", "tenc"))?.let { tenc ->
                val info = parseTenc(buf, tenc.contentStart, tenc.end)
                ivSize = info.ivSize
                constantIv = info.constantIv
            }
            writeAscii(buf, sinf.start + 4, "free")
        }

        // A surviving pssh makes Media3 attach DrmInitData to the (now clear)
        // audio format. With no DrmSessionManager the renderer then reports
        // FORMAT_UNSUPPORTED_DRM, the track is never selected, and the player
        // runs a silent standalone clock: progress moves, no audio, seeks snap
        // back to 0. The samples are decrypted on-device, so drop it.
        neutralizePssh(buf, moov.contentStart, moov.end)

        return TencInfo(
            ivSize ?: throw DecryptException("no protected sample entry / tenc found in moov"),
            constantIv,
        )
    }

    private fun parseSenc(buf: ByteArray, contentStart: Int, boxEnd: Int, ivSize: Int): List<SencEntry> {
        val flags = flags24(buf, contentStart)
        val sampleCount = u32(buf, contentStart + 4)
        var off = contentStart + 8
        val entries = ArrayList<SencEntry>()
        var i = 0L
        while (i < sampleCount) {
            if (off + ivSize > boxEnd) break
            val iv = if (ivSize > 0) buf.copyOfRange(off, off + ivSize) else ByteArray(0)
            off += ivSize
            val subsamples = ArrayList<Subsample>()
            if ((flags and SENC_SUBSAMPLES_FLAG) != 0) {
                if (off + 2 > boxEnd) break
                val subCount = u16(buf, off)
                off += 2
                for (s in 0 until subCount) {
                    if (off + 6 > boxEnd) break
                    subsamples.add(Subsample(u16(buf, off), u32(buf, off + 2)))
                    off += 6
                }
            }
            entries.add(SencEntry(iv, subsamples))
            i++
        }
        return entries
    }

    private fun normalizeIv(iv: ByteArray, constantIv: ByteArray): ByteArray {
        val value = if (iv.isEmpty()) constantIv else iv
        val out = ByteArray(16)
        System.arraycopy(value, 0, out, 0, minOf(value.size, 16))
        return out
    }

    fun importContentKey(hexKey: String): ByteArray {
        val clean = hexKey.trim()
            .replace(Regex("^0x", RegexOption.IGNORE_CASE), "")
            .replace(Regex("[^0-9a-fA-F]"), "")
        if (clean.length != 32) throw DecryptException("content key must be 16 bytes of hex, got ${clean.length / 2}")
        return ByteArray(16) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    private fun decryptSample(
        buf: ByteArray,
        position: Int,
        size: Int,
        iv16: ByteArray,
        subsamples: List<Subsample>,
        key: ByteArray,
    ) {
        // (offset, length) of every encrypted span of this sample.
        val spans = ArrayList<IntArray>()
        if (subsamples.isEmpty()) {
            spans.add(intArrayOf(position, size))
        } else {
            var off = position
            for (sub in subsamples) {
                off += sub.clear
                if (sub.enc > 0) spans.add(intArrayOf(off, sub.enc.toInt()))
                off += sub.enc.toInt()
            }
        }
        val total = spans.sumOf { it[1] }
        if (total <= 0) return
        // CENC subsamples are one continuous CTR keystream across all spans.
        val packed = ByteArray(total)
        var w = 0
        for (s in spans) {
            System.arraycopy(buf, s[0], packed, w, s[1])
            w += s[1]
        }
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv16))
        val plain = cipher.doFinal(packed)
        var r = 0
        for (s in spans) {
            System.arraycopy(plain, r, buf, s[0], s[1])
            r += s[1]
        }
    }

    private fun processTraf(
        buf: ByteArray,
        traf: CencBox,
        moofStartInFile: Long,
        segmentStart: Long,
        ivSize: Int,
        constantIv: ByteArray,
        key: ByteArray,
    ) {
        val tfhd = findBox(buf, traf.contentStart, traf.end, "tfhd") ?: return
        val tfhdFlags = flags24(buf, tfhd.contentStart)
        var off = tfhd.contentStart + 8
        var baseDataOffset: Long? = null
        var defaultSize = 0L

        if ((tfhdFlags and 0x000001) != 0) { baseDataOffset = u64(buf, off); off += 8 }
        if ((tfhdFlags and 0x000002) != 0) off += 4
        if ((tfhdFlags and 0x000008) != 0) off += 4
        if ((tfhdFlags and 0x000010) != 0) { defaultSize = u32(buf, off); off += 4 }

        val senc = findBox(buf, traf.contentStart, traf.end, "senc") ?: return
        val sencEntries = parseSenc(buf, senc.contentStart, senc.end, ivSize)
        if (sencEntries.isEmpty()) return

        val base = baseDataOffset ?: moofStartInFile
        var sampleIndex = 0
        for (box in iterBoxes(buf, traf.contentStart, traf.end)) {
            if (box.type != "trun") continue
            val trunFlags = flags24(buf, box.contentStart)
            val count = u32(buf, box.contentStart + 4)
            var toff = box.contentStart + 8
            var dataOffset = 0L
            if ((trunFlags and 0x000001) != 0) { dataOffset = u32(buf, toff).toInt().toLong(); toff += 4 }
            if ((trunFlags and 0x000004) != 0) toff += 4

            var pos = base + dataOffset
            var i = 0L
            while (i < count) {
                var sampleSize = defaultSize
                if ((trunFlags and 0x000100) != 0) toff += 4
                if ((trunFlags and 0x000200) != 0) { sampleSize = u32(buf, toff); toff += 4 }
                if ((trunFlags and 0x000400) != 0) toff += 4
                if ((trunFlags and 0x000800) != 0) toff += 4

                val entry = sencEntries.getOrNull(sampleIndex)
                val iv = entry?.iv ?: ByteArray(0)
                if (iv.isNotEmpty() || constantIv.isNotEmpty()) {
                    val bufferPos = pos - segmentStart
                    if (bufferPos >= 0 && bufferPos + sampleSize <= buf.size) {
                        decryptSample(
                            buf, bufferPos.toInt(), sampleSize.toInt(),
                            normalizeIv(iv, constantIv), entry?.subsamples ?: emptyList(), key,
                        )
                    }
                }
                pos += sampleSize
                sampleIndex++
                i++
            }
        }

        // Strip protection boxes so the player does not look for DRM.
        for (box in iterBoxes(buf, traf.contentStart, traf.end)) {
            if (box.type in PROTECTION_BOXES) {
                writeAscii(buf, box.start + 4, "free")
            } else if ((box.type == "sbgp" || box.type == "sgpd") && box.contentStart + 8 <= box.end &&
                ascii(buf, box.contentStart + 4, 4) == "seig"
            ) {
                writeAscii(buf, box.start + 4, "free")
            }
        }
    }

    /** Renames direct-child `pssh` boxes in [start, end) to `free` (same size, so offsets hold). */
    fun neutralizePssh(buf: ByteArray, start: Int, end: Int) {
        for (box in iterBoxes(buf, start, end)) {
            if (box.type == "pssh") writeAscii(buf, box.start + 4, "free")
        }
    }

    /** Decrypts every moof/mdat pair of [buf] in place. [segmentStart] is the file offset of buf[0]. */
    fun decryptSegment(buf: ByteArray, key: ByteArray, ivSize: Int, constantIv: ByteArray, segmentStart: Long) {
        var pendingMoof: CencBox? = null
        for (box in iterBoxes(buf, 0, buf.size)) {
            if (box.type == "moof") { pendingMoof = box; continue }
            if (box.type == "mdat" && pendingMoof != null) {
                val moofStartInFile = segmentStart + pendingMoof.start
                for (child in iterBoxes(buf, pendingMoof.contentStart, pendingMoof.end)) {
                    if (child.type == "traf") {
                        processTraf(buf, child, moofStartInFile, segmentStart, ivSize, constantIv, key)
                    }
                }
                // Media3 also reads moof-level pssh into the format's DrmInitData.
                neutralizePssh(buf, pendingMoof.contentStart, pendingMoof.end)
                pendingMoof = null
            }
        }
    }
}
