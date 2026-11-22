package com.lastwave.app.playback

import android.content.Context
import android.net.Uri
import com.lastwave.app.playback.dsd.DsdNativeDecimator
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import kotlin.math.pow

data class LocalAudioInspection(
    val mimeType: String,
    val badge: String,
    val isLossless: Boolean,
    val bitDepth: Int? = null,
    val samplingRateKHz: Double? = null,
    val bitrateKbps: Int? = null,
    val durationMs: Long? = null,
)

/**
 * Container-level audio stream properties parsed straight from file headers.
 * Works uniformly across Android API levels and OEMs without depending on
 * `MediaMetadataRetriever` API 31+ keys.
 */
data class AudioHeaderInfo(
    val containerLabel: String,
    val sampleRateHz: Int? = null,
    val bitDepth: Int? = null,
    val channels: Int? = null,
    val bitrateKbps: Int? = null,
    val durationMs: Long? = null,
    val isLossless: Boolean = false,
    val isDsd: Boolean = false,
    val dsdBadge: String? = null,
    val dsdOutputRateHz: Int? = null,
)

/**
 * Lightweight header parser covering FLAC, WAV/RF64, AIFF/AIFC, DSF, DFF (DSDIFF),
 * and MP4/M4A/ALAC atoms. All reads are bounded to tiny header prefixes so probing
 * local or SAF `content://` tracks stays fast on every device.
 */
object AudioHeaderInspector {

    fun inspect(context: Context, urlOrUri: String, mimeHint: String? = null): AudioHeaderInfo? {
        return runCatching {
            val lower = urlOrUri.lowercase()
            val openStream: () -> InputStream? = {
                if (urlOrUri.startsWith("content://")) {
                    context.contentResolver.openInputStream(Uri.parse(urlOrUri))
                } else {
                    val path = urlOrUri.removePrefix("file://")
                    File(path).takeIf { it.isFile && it.canRead() }?.inputStream()
                }
            }
            openStream()?.use { stream ->
                inspectStream(stream, mimeHint ?: lower)
            }
        }.getOrNull()
    }

    /**
     * Unified local audio probe used by both stream resolution and notification/UI
     * badge publishing. Combines container-header facts with `MediaMetadataRetriever`
     * without assuming bit depths on unknown containers.
     */
    fun inspectLocalTrackQuality(
        context: Context,
        targetUrl: String,
        fallbackMime: String? = null,
        knownBitrate: Int? = null,
        knownDurationMs: Long? = null,
    ): LocalAudioInspection {
        val uri = if (targetUrl.startsWith("/") && !targetUrl.startsWith("file://")) {
            Uri.fromFile(File(targetUrl))
        } else {
            Uri.parse(targetUrl)
        }
        val decodedLower = (runCatching { Uri.decode(uri.path ?: targetUrl) }.getOrNull() ?: targetUrl).lowercase()
        val extMime = com.lastwave.app.data.generate.LocalAudioFormats.mimeFor(targetUrl)
        var effectiveMime = fallbackMime?.takeIf(String::isNotBlank)
            ?: extMime
            ?: if (targetUrl.startsWith("content://")) {
                runCatching { context.contentResolver.getType(uri) }.getOrNull()
            } else {
                null
            }
            ?: "audio/flac"

        var bitrateKbps: Int? = knownBitrate?.takeIf { it > 0 }
        var bitDepth: Int? = null
        var samplingRateKHz: Double? = null
        var fileDurationMs: Long? = null

        val header = inspect(context, targetUrl, effectiveMime)

        if (header?.isDsd != true) {
            val retriever = android.media.MediaMetadataRetriever()
            try {
                if (targetUrl.startsWith("content://")) {
                    retriever.setDataSource(context, uri)
                } else {
                    retriever.setDataSource(uri.path ?: targetUrl.removePrefix("file://"))
                }
                retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
                    ?.lowercase()?.takeIf { it.isNotBlank() }?.let { rMime ->
                        if (fallbackMime.isNullOrBlank() && extMime == null) {
                            effectiveMime = rMime
                        }
                    }
                fileDurationMs = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()?.takeIf { it > 0L }
                if (bitrateKbps == null) {
                    bitrateKbps = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_BITRATE)
                        ?.toIntOrNull()?.let { it / 1000 }?.takeIf { it > 0 }
                }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    samplingRateKHz = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)
                        ?.toDoubleOrNull()?.let { it / 1000.0 }?.takeIf { it > 0.0 }
                    bitDepth = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE)
                        ?.toIntOrNull()?.takeIf { it > 0 }
                }
            } catch (_: Exception) {
                // Header probe below still identifies DSD, AIFF, WAV, FLAC, ALAC cleanly.
            } finally {
                runCatching { retriever.release() }
            }
        }

        if (header != null) {
            if (header.isDsd) {
                val outRateKHz = (header.dsdOutputRateHz ?: 176_400) / 1000.0
                return LocalAudioInspection(
                    mimeType = effectiveMime,
                    badge = header.dsdBadge ?: "DSD64",
                    isLossless = true,
                    bitDepth = 1,
                    samplingRateKHz = outRateKHz,
                    bitrateKbps = bitrateKbps,
                    durationMs = header.durationMs ?: fileDurationMs ?: knownDurationMs?.takeIf { it > 0L },
                )
            }
            if (bitDepth == null) bitDepth = header.bitDepth?.takeIf { it > 0 }
            if (samplingRateKHz == null) {
                samplingRateKHz = header.sampleRateHz?.takeIf { it > 0 }?.div(1000.0)
            }
            if (bitrateKbps == null) bitrateKbps = header.bitrateKbps?.takeIf { it > 0 }
            if (fileDurationMs == null) fileDurationMs = header.durationMs?.takeIf { it > 0L }
        }

        val m = effectiveMime.lowercase()
        val isAlac = header?.containerLabel == "ALAC" || decodedLower.endsWith(".alac")
        val isAiff = header?.containerLabel == "AIFF" || m.contains("aiff") ||
            decodedLower.endsWith(".aiff") || decodedLower.endsWith(".aif") || decodedLower.endsWith(".aifc")
        val isWav = header?.containerLabel == "WAV" || m.contains("wav") ||
            decodedLower.endsWith(".wav") || decodedLower.endsWith(".wave")
        val isFlac = header?.containerLabel == "FLAC" || m.contains("flac") || decodedLower.endsWith(".flac")
        val isLossless = (header?.isLossless == true) || isFlac || isWav || isAiff || isAlac

        val containerName = when {
            isAiff -> "AIFF"
            isWav -> "WAV"
            isAlac -> "ALAC"
            else -> "FLAC"
        }
        val resolvedBadge = when {
            m.contains("eac3") || m.contains("ec-3") || m.contains("ac3") || m.contains("ac-3") ||
                decodedLower.endsWith(".ac3") || decodedLower.endsWith(".ec3") || decodedLower.endsWith(".eac3") -> "DOLBY"
            m.contains("ac4") || decodedLower.endsWith(".ac4") -> "DOLBY AC-4"
            isLossless -> {
                val d = bitDepth?.takeIf { it > 0 }
                val r = samplingRateKHz?.takeIf { it > 0.0 }
                when {
                    d != null && r != null -> "$d/${formatSampleRateKHz(r)}kHz"
                    r != null -> "${formatSampleRateKHz(r)}kHz $containerName"
                    (d ?: 0) > 16 -> "HI-RES $containerName"
                    else -> containerName
                }
            }
            m.contains("opus") -> "OPUS"
            m.contains("ogg") || decodedLower.endsWith(".ogg") || decodedLower.endsWith(".oga") -> "OGG"
            m.contains("mp4") || m.contains("m4a") || m.contains("aac") ||
                decodedLower.endsWith(".m4a") || decodedLower.endsWith(".aac") -> "AAC"
            m.contains("mp3") || m.contains("mpeg") || decodedLower.endsWith(".mp3") || decodedLower.endsWith(".mp2") -> "MP3"
            m.contains("matroska") || decodedLower.endsWith(".mka") || decodedLower.endsWith(".webm") -> "MKA"
            else -> "AUDIO"
        }

        return LocalAudioInspection(
            mimeType = effectiveMime,
            badge = resolvedBadge,
            isLossless = isLossless,
            bitDepth = if (isLossless) bitDepth?.takeIf { it > 0 } else null,
            samplingRateKHz = samplingRateKHz?.takeIf { it > 0.0 },
            bitrateKbps = bitrateKbps,
            durationMs = fileDurationMs ?: knownDurationMs?.takeIf { it > 0L },
        )
    }

    fun inspectStream(input: InputStream, hint: String = ""): AudioHeaderInfo? {
        val probe = ByteArray(16 * 1024)
        var filled = 0
        while (filled < probe.size) {
            val n = input.read(probe, filled, probe.size - filled)
            if (n <= 0) break
            filled += n
        }
        if (filled < 12) return null

        val head = probe.copyOf(filled)
        val fourCc = String(head, 0, 4, Charsets.US_ASCII)

        // 1. DSF ('DSD ')
        if (fourCc == "DSD " && head.size >= 80) {
            return parseDsfHeader(head)
        }

        // 2. DFF / DSDIFF ('FRM8' ... 'DSD ')
        if (fourCc == "FRM8" && head.size >= 16 &&
            String(head, 12, 4, Charsets.US_ASCII) == "DSD "
        ) {
            return parseDffHeader(head)
        }

        // 3. AIFF / AIFC ('FORM' ... 'AIFF' / 'AIFC')
        if (fourCc == "FORM" && head.size >= 16) {
            val sub = String(head, 8, 4, Charsets.US_ASCII)
            if (sub == "AIFF" || sub == "AIFC") {
                return parseAiffHeader(head, isCompressed = sub == "AIFC")
            }
        }

        // 4. WAV ('RIFF' ... 'WAVE')
        if ((fourCc == "RIFF" || fourCc == "RF64") && head.size >= 36 &&
            String(head, 8, 4, Charsets.US_ASCII) == "WAVE"
        ) {
            return parseWavHeader(head)
        }

        // 5. FLAC (including optional leading ID3v2)
        if (fourCc == "fLaC" || (head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() && head[2] == '3'.code.toByte() && hint.contains("flac"))) {
            val combined = java.io.SequenceInputStream(ByteArrayInputStream(head), input)
            val info = readFlacStreamInfo(combined)
            if (info != null) {
                val sr = info.sampleRateHz ?: 0
                val ts = info.totalSamples ?: 0L
                val durMs = if (sr > 0 && ts > 0L) (ts * 1000L) / sr else null
                return AudioHeaderInfo(
                    containerLabel = "FLAC",
                    sampleRateHz = info.sampleRateHz,
                    bitDepth = info.bitDepth,
                    channels = info.channels,
                    durationMs = durMs,
                    isLossless = true,
                )
            }
        }

        // 6. MP4 / M4A / ALAC ('ftyp' at offset 4)
        if (head.size >= 24 && String(head, 4, 4, Charsets.US_ASCII) == "ftyp") {
            return parseMp4AlacHeader(head)
        }

        return null
    }

    internal fun parseDsfHeader(buf: ByteArray): AudioHeaderInfo? {
        if (buf.size < 80) return null
        if (String(buf, 0, 4, Charsets.US_ASCII) != "DSD ") return null
        val fmtOffset = leU64(buf, 4).toInt().coerceIn(28, 256)
        if (buf.size < fmtOffset + 52) return null
        if (String(buf, fmtOffset, 4, Charsets.US_ASCII) != "fmt ") return null
        val formatId = leU32(buf, fmtOffset + 16).toInt()
        if (formatId != 0) return null
        val channels = leU32(buf, fmtOffset + 24).toInt().coerceIn(1, 8)
        val dsdRateHz = leU32(buf, fmtOffset + 28).toInt().takeIf { it > 0 } ?: return null
        val sampleCount = leU64(buf, fmtOffset + 36)
        val durationMs = if (sampleCount > 0L) (sampleCount * 1000L) / dsdRateHz else null
        val badge = DsdNativeDecimator.dsdBadgeForRate(dsdRateHz)
        val factor = DsdNativeDecimator.chooseDecimationFactor(dsdRateHz)
        return AudioHeaderInfo(
            containerLabel = badge,
            sampleRateHz = dsdRateHz,
            bitDepth = 1,
            channels = channels,
            durationMs = durationMs,
            isLossless = true,
            isDsd = true,
            dsdBadge = badge,
            dsdOutputRateHz = (dsdRateHz / factor).coerceAtLeast(44_100),
        )
    }

    internal fun parseDffHeader(buf: ByteArray): AudioHeaderInfo? {
        if (buf.size < 16 || String(buf, 0, 4, Charsets.US_ASCII) != "FRM8") return null
        if (String(buf, 12, 4, Charsets.US_ASCII) != "DSD ") return null
        var pos = 16
        var dsdRateHz: Int? = null
        var channels: Int? = null
        var isUncompressed = true
        var dsdDataBytes: Long? = null

        while (pos + 12 <= buf.size) {
            val id = String(buf, pos, 4, Charsets.US_ASCII)
            val size = beU64(buf, pos + 4)
            val bodyStart = pos + 12
            if (size < 0L) break
            if (id == "PROP" && bodyStart + 4 <= buf.size) {
                if (String(buf, bodyStart, 4, Charsets.US_ASCII) == "SND ") {
                    var sub = bodyStart + 4
                    val propEnd = minOf(buf.size.toLong(), bodyStart.toLong() + size).toInt()
                    while (sub + 12 <= propEnd) {
                        val subId = String(buf, sub, 4, Charsets.US_ASCII)
                        val subSize = beU64(buf, sub + 4)
                        val subBody = sub + 12
                        when {
                            subId == "FS  " && subBody + 4 <= buf.size ->
                                dsdRateHz = beU32(buf, subBody).toInt()
                            subId == "CHNL" && subBody + 2 <= buf.size ->
                                channels = beU16(buf, subBody)
                            subId == "CMPR" && subBody + 4 <= buf.size -> {
                                val cmpr = String(buf, subBody, 4, Charsets.US_ASCII)
                                isUncompressed = (cmpr == "DSD ")
                            }
                        }
                        if (subSize <= 0L) break
                        val step = subBody + subSize + (subSize and 1L)
                        if (step > buf.size) break
                        sub = step.toInt()
                    }
                }
            } else if (id == "DSD ") {
                dsdDataBytes = size
                break
            }
            val next = bodyStart.toLong() + size + (size and 1L)
            if (next <= pos || next > Int.MAX_VALUE) break
            if (next >= buf.size) break
            pos = next.toInt()
        }

        if (!isUncompressed || dsdRateHz == null || dsdRateHz <= 0) return null
        val ch = (channels ?: 2).coerceIn(1, 8)
        val badge = DsdNativeDecimator.dsdBadgeForRate(dsdRateHz)
        val factor = DsdNativeDecimator.chooseDecimationFactor(dsdRateHz)
        val durationMs = dsdDataBytes?.let { bytes ->
            // bytes / ch = 8 DSD bit-periods
            val sampleBits = (bytes / ch) * 8L
            (sampleBits * 1000L) / dsdRateHz
        }
        return AudioHeaderInfo(
            containerLabel = badge,
            sampleRateHz = dsdRateHz,
            bitDepth = 1,
            channels = ch,
            durationMs = durationMs,
            isLossless = true,
            isDsd = true,
            dsdBadge = badge,
            dsdOutputRateHz = (dsdRateHz / factor).coerceAtLeast(44_100),
        )
    }

    internal fun parseAiffHeader(buf: ByteArray, isCompressed: Boolean): AudioHeaderInfo? {
        if (buf.size < 16) return null
        var pos = 12
        while (pos + 8 <= buf.size) {
            val id = String(buf, pos, 4, Charsets.US_ASCII)
            val size = beU32(buf, pos + 4)
            val body = pos + 8
            if (id == "COMM" && body + 18 <= buf.size) {
                val channels = beU16(buf, body).coerceIn(1, 16)
                val frames = beU32(buf, body + 2)
                val bitsPerSample = beU16(buf, body + 6)
                val sampleRateHz = decodeIeeeExtended80(buf, body + 8).toInt().takeIf { it > 0 } ?: return null
                if (isCompressed && body + 22 <= buf.size) {
                    val comp = String(buf, body + 18, 4, Charsets.US_ASCII)
                    val ok = comp == "NONE" || comp == "sowt" || comp == "twos" ||
                        comp.equals("fl32", ignoreCase = true) || comp.equals("fl64", ignoreCase = true)
                    if (!ok) return null
                }
                val durMs = if (frames > 0L) (frames * 1000L) / sampleRateHz else null
                val kbps = ((sampleRateHz.toLong() * channels * bitsPerSample) / 1000L).toInt().takeIf { it > 0 }
                return AudioHeaderInfo(
                    containerLabel = "AIFF",
                    sampleRateHz = sampleRateHz,
                    bitDepth = bitsPerSample.takeIf { it > 0 },
                    channels = channels,
                    bitrateKbps = kbps,
                    durationMs = durMs,
                    isLossless = true,
                )
            }
            if (size < 0L) break
            val next = body.toLong() + size + (size and 1L)
            if (next <= pos || next > buf.size) break
            pos = next.toInt()
        }
        return null
    }

    internal fun parseWavHeader(buf: ByteArray): AudioHeaderInfo? {
        if (buf.size < 36) return null
        var pos = 12
        var channels: Int? = null
        var sampleRateHz: Int? = null
        var bitDepth: Int? = null
        var audioFormat: Int? = null
        var dataBytes: Long? = null
        var byteRate: Long? = null

        while (pos + 8 <= buf.size) {
            val id = String(buf, pos, 4, Charsets.US_ASCII)
            val size = leU32(buf, pos + 4)
            val body = pos + 8
            if (id == "fmt " && body + 16 <= buf.size) {
                audioFormat = leU16(buf, body)
                channels = leU16(buf, body + 2)
                sampleRateHz = leU32(buf, body + 4).toInt()
                byteRate = leU32(buf, body + 8)
                bitDepth = leU16(buf, body + 14)
                // WAVE_FORMAT_EXTENSIBLE (0xFFFE) can specify validBitsPerSample at body+18
                if (audioFormat == 0xFFFE && size >= 40 && body + 26 <= buf.size) {
                    val validBits = leU16(buf, body + 18)
                    if (validBits in 8..64) bitDepth = validBits
                    audioFormat = leU16(buf, body + 24)
                }
            } else if (id == "data") {
                dataBytes = size
                break
            }
            if (size < 0L) break
            val next = body.toLong() + size + (size and 1L)
            if (next <= pos || next > buf.size) break
            pos = next.toInt()
        }

        val sr = sampleRateHz?.takeIf { it > 0 } ?: return null
        val isPcmLossless = audioFormat == 1 || audioFormat == 3
        val ch = (channels ?: 2).coerceAtLeast(1)
        val durMs = if (dataBytes != null && byteRate != null && byteRate > 0L) {
            (dataBytes * 1000L) / byteRate
        } else null
        val kbps = byteRate?.let { ((it * 8L) / 1000L).toInt() }?.takeIf { it > 0 }
        return AudioHeaderInfo(
            containerLabel = "WAV",
            sampleRateHz = sr,
            bitDepth = bitDepth?.takeIf { it > 0 },
            channels = ch,
            bitrateKbps = kbps,
            durationMs = durMs,
            isLossless = isPcmLossless,
        )
    }

    internal fun parseMp4AlacHeader(buf: ByteArray): AudioHeaderInfo? {
        // Scan buffered atoms for the 24-byte ALACSpecificConfig inside an 'alac' atom.
        // Layout: [size(4)][alac(4)][version+flags(4)] +
        //   alacSpecificConfig: frameLength(4), compatibleVersion(1), bitDepth(1),
        //   pb(1), mb(1), kb(1), numChannels(1), maxRun(2), maxFrameBytes(4),
        //   avgBitRate(4), sampleRate(4) -> total 24 bytes after version+flags.
        val needle = byteArrayOf('a'.code.toByte(), 'l'.code.toByte(), 'a'.code.toByte(), 'c'.code.toByte())
        var i = 8
        while (i + 32 <= buf.size) {
            if (buf[i] == needle[0] && buf[i + 1] == needle[1] && buf[i + 2] == needle[2] && buf[i + 3] == needle[3]) {
                val atomSize = beU32(buf, i - 4)
                if (atomSize in 36..64 && i + 32 <= buf.size) {
                    val cfg = i + 8
                    val version = buf[i + 4].toInt() and 0xFF
                    if (version == 0 && cfg + 24 <= buf.size) {
                        val depth = buf[cfg + 5].toInt() and 0xFF
                        val ch = buf[cfg + 9].toInt() and 0xFF
                        val avgBitRate = beU32(buf, cfg + 16).toInt()
                        val sr = beU32(buf, cfg + 20).toInt()
                        if (depth in 16..32 && ch in 1..8 && sr in 8_000..384_000) {
                            return AudioHeaderInfo(
                                containerLabel = "ALAC",
                                sampleRateHz = sr,
                                bitDepth = depth,
                                channels = ch,
                                bitrateKbps = (avgBitRate / 1000).takeIf { it > 0 },
                                isLossless = true,
                            )
                        }
                    }
                }
            }
            i++
        }
        return null
    }

    /** Decodes 10-byte IEEE 754 standard extended float (big-endian), used in AIFF COMM chunks. */
    fun decodeIeeeExtended80(buf: ByteArray, offset: Int): Double {
        if (offset + 10 > buf.size) return 0.0
        val expHi = buf[offset].toInt() and 0xFF
        val expLo = buf[offset + 1].toInt() and 0xFF
        val sign = (expHi and 0x80) != 0
        val expon = ((expHi and 0x7F) shl 8) or expLo
        var hiMant = 0L
        for (i in 2..5) {
            hiMant = (hiMant shl 8) or (buf[offset + i].toLong() and 0xFFL)
        }
        var loMant = 0L
        for (i in 6..9) {
            loMant = (loMant shl 8) or (buf[offset + i].toLong() and 0xFFL)
        }
        if (expon == 0 && hiMant == 0L && loMant == 0L) return 0.0
        if (expon == 0x7FFF) return Double.NaN
        val e = expon - 16383
        val hi = hiMant.toDouble() * 2.0.pow(e - 31)
        val lo = loMant.toDouble() * 2.0.pow(e - 63)
        val value = hi + lo
        return if (sign) -value else value
    }

    fun leU16(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    fun leU32(b: ByteArray, o: Int): Long =
        (b[o].toLong() and 0xFFL) or
            ((b[o + 1].toLong() and 0xFFL) shl 8) or
            ((b[o + 2].toLong() and 0xFFL) shl 16) or
            ((b[o + 3].toLong() and 0xFFL) shl 24)

    fun leU64(b: ByteArray, o: Int): Long {
        var r = 0L
        for (i in 7 downTo 0) {
            r = (r shl 8) or (b[o + i].toLong() and 0xFFL)
        }
        return r
    }

    fun beU16(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)

    fun beU32(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0xFFL) shl 24) or
            ((b[o + 1].toLong() and 0xFFL) shl 16) or
            ((b[o + 2].toLong() and 0xFFL) shl 8) or
            (b[o + 3].toLong() and 0xFFL)

    fun beU64(b: ByteArray, o: Int): Long {
        var r = 0L
        for (i in 0..7) {
            r = (r shl 8) or (b[o + i].toLong() and 0xFFL)
        }
        return r
    }
}
