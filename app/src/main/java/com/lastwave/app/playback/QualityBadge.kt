package com.lastwave.app.playback

import kotlin.math.roundToInt

/**
 * Now-playing quality pill text.
 *
 * Describes the SONG, never the decoder's output: the depth comes from the
 * backend/container metadata only, corrected by the fact that a rate above
 * 48kHz is 24-bit in practice. Lossless is formatted as
 * `$bitDepth/${sampleRate}kHz` (e.g. `24/44.1kHz`, `24/48kHz`, `24/88.2kHz`,
 * `24/96kHz`, `24/176.4kHz`, `24/192kHz`, `16/44.1kHz`, `32/384kHz`).
 * At or below 48kHz an unknown depth is shown as the rate alone
 * (`44.1kHz FLAC`) — never a guessed 16 or 24.
 * Spatial mixes use a short `ATMOS` or `SPATIAL` badge.
 */
fun qualityBadgeLabel(state: MusicPlayerState): String {
    val spatial = spatialIndicatorLabel(state.audioCodec)
    if (spatial != null) return spatial

    val codec = state.audioCodec
    val flacLike = isFlacLikeCodec(codec) || state.isLossless
    val rate = state.samplingRateKHz ?: inferSamplingRate(state)
    // One shared rule for every surface: the song's depth, corrected only by
    // the fact that a rate above 48kHz is 24-bit in practice (see
    // resolveDepthForDisplay). The decoder's PCM encoding is never consulted.
    val depth = resolveDepthForDisplay(state.bitDepth, rate)
        ?: inferBitDepth(state.copy(bitDepth = null), allowRateGuess = false)

    if (flacLike && depth != null && rate != null && rate > 0.0) {
        return "$depth/${formatSampleRateKHz(rate)}kHz"
    }
    if (flacLike && rate != null && rate > 0.0) {
        return "${formatSampleRateKHz(rate)}kHz FLAC"
    }
    if (flacLike && depth != null) {
        return "$depth-BIT FLAC"
    }
    if (flacLike) {
        val parsed = parseQualityFromCodec(codec)
        if (parsed != null) return parsed
        return codec?.takeIf { it.isNotBlank() && !it.equals("AUDIO", true) } ?: "FLAC"
    }

    if (codec?.equals("MP3 320k", ignoreCase = true) == true) return "MP3 320 kbps"
    if (codec?.uppercase() in GENERIC_AUDIO_LABELS) return "AUDIO"
    if (codec != null && state.bitrateKbps != null) return "${codec.uppercase()} ${state.bitrateKbps} kbps"
    if (codec != null) return codec.uppercase()
    if (state.bitrateKbps != null) return "${state.bitrateKbps} kbps"
    return "AUDIO"
}

/** Compact chip next to the title when the playing stream is spatial. */
fun spatialIndicatorLabel(codec: String?): String? {
    val c = codec?.uppercase().orEmpty()
    if (c.contains("ATMOS")) return "ATMOS"
    if (c.contains("SPATIAL") || c.contains("360")) return "SPATIAL"
    return null
}

fun isSpatialAudioCodec(codec: String?): Boolean = spatialIndicatorLabel(codec) != null

fun isFlacLikeCodec(codec: String?): Boolean {
    val c = codec?.uppercase().orEmpty()
    if (c.isBlank()) return false
    if (isSpatialAudioCodec(codec)) return false
    return c.contains("FLAC") || c == "LOSSLESS" || c.contains("HI-RES") || c.contains("HI_RES") ||
        Regex("""(?:^|[^\d])(16|24|32)\s*(?:[-_]bit)?\s*[/]\s*(\d{2,3}(?:\.\d+)?)\s*k?""", RegexOption.IGNORE_CASE).containsMatchIn(c)
}

/** Format sample rate in kHz with minimal decimal places (e.g. 44.1, 48, 88.2, 96, 176.4, 192). */
fun formatSampleRateKHz(kHzOrHz: Double): String {
    val kHz = if (kHzOrHz > 1000.0) kHzOrHz / 1000.0 else kHzOrHz
    val rounded = (kHz * 10.0).roundToInt() / 10.0
    return if (rounded % 1.0 == 0.0) rounded.toInt().toString() else rounded.toString()
}

/**
 * The bit depth to display for a stream, from the depth the source reported and
 * its sample rate. Every badge, the details sheet and the Signal Path dialog go
 * through this so they can never disagree.
 *
 * Hi-res means 24-bit in practice. A rate above 48kHz (88.2, 96, 176.4, 192)
 * is a 24-bit master in effectively every real release — a genuine 16-bit
 * stream at those rates is vanishingly rare. So a reported 16 beside such a
 * rate is corrected to 24 rather than shown as 16, and an *unknown* depth at a
 * hi-res rate resolves to 24 too, because the rate is strong enough evidence
 * and half a label ("96kHz FLAC") helps nobody. Above 192kHz the hi-res class is
 * 32-bit, not 24.
 *
 * At or below 48kHz nothing is assumed: a reported 16 stays 16 (CD audio is
 * genuinely 16-bit) and an unknown depth stays unknown, rendering rate-only.
 *
 * This is a deliberate domain assumption, not a measurement. It is isolated
 * here so the assumption is changed in exactly one place.
 */
internal fun resolveDepthForDisplay(bitDepth: Int?, rateKHz: Double?): Int? {
    val reported = bitDepth?.takeIf { it > 0 }
    val rate = rateKHz ?: 0.0
    val hiRes = rate > 48.0
    return when {
        // A reported depth of its own, unless it is a 16 at a hi-res rate.
        reported != null && (reported > 16 || !hiRes) -> reported
        rate > 192.0 -> 32
        hiRes -> 24
        else -> null
    }
}

/**
 * Standard detailed badge for downloads, track details sheets, and metadata cards.
 * Formats every tier cleanly: e.g. "24-BIT / 96k", "24-BIT / 48k", "24-BIT / 44.1k",
 * "16-BIT / 48k", "16-BIT / 44.1k", "24-BIT / 192k".
 */
fun formatDetailedQualityBadge(bitDepth: Int?, sampleRateKHzOrHz: Double?, isAtmos: Boolean = false): String {
    if (isAtmos) return "DOLBY ATMOS"
    val rateKHz = if ((sampleRateKHzOrHz ?: 0.0) > 1000.0) (sampleRateKHzOrHz ?: 0.0) / 1000.0 else (sampleRateKHzOrHz ?: 0.0)
    val depth = resolveDepthForDisplay(bitDepth, rateKHz)
    return when {
        depth != null && rateKHz > 0.0 -> "$depth-BIT / ${formatSampleRateKHz(rateKHz)}k"
        rateKHz > 0.0 -> "${formatSampleRateKHz(rateKHz)}k FLAC"
        depth != null -> "$depth-BIT FLAC"
        else -> "FLAC"
    }
}

internal fun inferBitDepth(state: MusicPlayerState, allowRateGuess: Boolean = true): Int? {
    val codec = state.audioCodec?.uppercase().orEmpty()
    if (codec.contains("32-BIT") || codec.contains("32BIT") || codec.contains("32/")) return 32
    // NOTE: "HI-RES" alone never implies 24-bit (it describes the rate, and
    // our own rate-derived "HI-RES FLAC" labels would loop back into a
    // fabricated 24). Only explicit depth claims count here.
    if (codec.contains("24-BIT") || codec.contains("24BIT") || codec.contains("24/")) return 24
    if (codec.contains("16-BIT") || codec.contains("16BIT") || codec.contains("CD") || codec.contains("16/")) return 16

    val explicit = state.bitDepth?.takeIf { it > 0 }
    if (explicit != null) return explicit

    // No bit-depth guessing from bitrate. A stereo 24-bit/44.1kHz FLAC lands
    // anywhere from ~500 to ~2500 kbps depending purely on how well that
    // recording compresses, so "400..1150 kbps => 16-bit" relabelled ordinary
    // 24-bit tracks as 16-bit and hid the real format behind a coincidence of
    // bitrate buckets. An unknown depth must stay unknown; the callers render
    // rate-only ("44.1kHz FLAC") rather than assert a number we cannot verify.
    //
    // The last-resort rate guess below is likewise disallowed where honesty
    // matters (badges and signal path): a 16-bit/96kHz FLAC is valid, so the
    // rate alone must never assert a depth.
    if (!allowRateGuess) return null
    val rate = state.samplingRateKHz
    if (rate != null && rate > 48.0) {
        return if (rate > 192.0) 32 else 24
    }
    return null
}

internal fun inferSamplingRate(state: MusicPlayerState): Double? {
    val explicit = state.samplingRateKHz?.takeIf { it > 0.0 }
    if (explicit != null) return explicit

    val codec = state.audioCodec.orEmpty()
    // Prefer the rate half of an explicit "depth / rate" label ("24-BIT / 96k",
    // "16/44.1kHz"): the generic number scan below matches the FIRST number,
    // which is the bit depth (24), not the rate (96).
    Regex("""(?:^|[^\d])(?:16|24|32)\s*(?:[-_]bit)?\s*/\s*(\d{2,3}(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
        .find(codec)?.groupValues?.getOrNull(1)?.toDoubleOrNull()?.takeIf { it > 0.0 }
        ?.let { return it }
    val match = Regex("""(?:^|[^\d])(\d{2,3}(?:\.\d+)?)\s*(?:k|khz)?(?:[^\d]|$)""", RegexOption.IGNORE_CASE).find(codec)
    if (match != null) {
        val v = match.groupValues[1].toDoubleOrNull()
        if (v != null) {
            return when {
                v in listOf(44.1, 48.0, 88.2, 96.0, 176.4, 192.0, 352.8, 384.0) -> v
                v > 1000.0 -> v / 1000.0
                else -> null
            }
        }
    }
    return null
}

internal fun parseQualityFromCodec(codec: String?): String? {
    val c = codec.orEmpty()
    if (c.isBlank()) return null
    val match = Regex("""(?:^|[^\d])(16|24|32)\s*(?:[-_]bit)?\s*[/]\s*(\d{2,3}(?:\.\d+)?)\s*k?""", RegexOption.IGNORE_CASE).find(c)
    if (match != null) {
        // Trust an explicit "depth/rate" label verbatim: a parsed 16/96 is
        // a real (if unusual) combination, never to be "corrected" to 24.
        val depth = match.groupValues[1].toIntOrNull() ?: 16
        val rate = match.groupValues[2].toDoubleOrNull()
        if (rate != null) {
            return "$depth/${formatSampleRateKHz(rate)}kHz"
        }
    }
    return null
}

private val GENERIC_AUDIO_LABELS = setOf("AUDIO", "LOCAL AUDIO")

/**
 * Ground-truth FLAC format facts read straight from the file's STREAMINFO
 * block — no MediaMetadataRetriever, no decoder, no API-level gate.
 *
 * Why this exists: the pill was rendering rate-only ("44.1kHz FLAC" /
 * "48kHz FLAC") on many devices because [MusicPlayerState.bitDepth] was null.
 * The retriever path only runs on API 31+ and still returns null for
 * BITS_PER_SAMPLE on several OEMs/files, while the decoder's PCM width is
 * deliberately never used as source depth. STREAMINFO is written by every
 * FLAC encoder, is identical on every device, and carries both the sample
 * rate (20 bits) and the bits-per-sample (5 bits, stored value + 1).
 */
data class FlacStreamInfo(val bitDepth: Int?, val sampleRateHz: Int?)

/** Parse one 34-byte STREAMINFO body into depth + rate. Null when invalid. */
fun parseFlacStreamInfoBody(body: ByteArray, offset: Int = 0): FlacStreamInfo? {
    if (body.size - offset < 34) return null
    val b10 = body[offset + 10].toInt() and 0xFF
    val b11 = body[offset + 11].toInt() and 0xFF
    val b12 = body[offset + 12].toInt() and 0xFF
    val b13 = body[offset + 13].toInt() and 0xFF
    val sampleRateHz = (b10 shl 12) or (b11 shl 4) or ((b12 shr 4) and 0x0F)
    val bpsMinusOne = ((b12 and 0x01) shl 4) or ((b13 shr 4) and 0x0F)
    val bitDepth = bpsMinusOne + 1
    if (sampleRateHz < 8000 || sampleRateHz > 768000) return null
    if (bitDepth < 4 || bitDepth > 32) return null
    return FlacStreamInfo(bitDepth = bitDepth, sampleRateHz = sampleRateHz)
}

/**
 * Scan a byte buffer holding the start of a FLAC file (optional ID3v2 prefix,
 * "fLaC" marker, then metadata blocks) for STREAMINFO. Returns null when the
 * buffer is not FLAC or STREAMINFO is not within [validBytes].
 */
fun parseFlacHeaderBytes(data: ByteArray, validBytes: Int = data.size): FlacStreamInfo? {
    var pos = 0
    val end = validBytes.coerceAtMost(data.size)
    // Optional ID3v2 prefix ("ID3" + ver(2) + flags(1) + 4 synchsafe bytes).
    if (end - pos >= 10 && data[0] == 'I'.code.toByte() && data[1] == 'D'.code.toByte() &&
        data[2] == '3'.code.toByte()
    ) {
        val size = ((data[6].toInt() and 0x7F) shl 21) or
            ((data[7].toInt() and 0x7F) shl 14) or
            ((data[8].toInt() and 0x7F) shl 7) or
            (data[9].toInt() and 0x7F)
        pos = 10 + size
    }
    if (end - pos < 4) return null
    if (!(data[pos] == 'f'.code.toByte() && data[pos + 1] == 'L'.code.toByte() &&
            data[pos + 2] == 'a'.code.toByte() && data[pos + 3] == 'C'.code.toByte())
    ) return null
    pos += 4
    repeat(64) {
        if (end - pos < 4) return null
        val header = data[pos].toInt() and 0xFF
        val isLast = (header and 0x80) != 0
        val type = header and 0x7F
        val len = ((data[pos + 1].toInt() and 0xFF) shl 16) or
            ((data[pos + 2].toInt() and 0xFF) shl 8) or
            (data[pos + 3].toInt() and 0xFF)
        pos += 4
        if (len < 0 || end - pos < len) return null
        if (type == 0) return parseFlacStreamInfoBody(data, pos)
        pos += len
        if (isLast) return null
    }
    return null
}

/**
 * Read STREAMINFO from an open stream (file or content URI). Consumes only the
 * header bytes needed (ID3 prefix + "fLaC" + block headers + 34-byte
 * STREAMINFO); picture blocks are skipped without loading them. Never throws.
 */
fun readFlacStreamInfo(input: java.io.InputStream): FlacStreamInfo? {
    return try {
        fun readFully(buf: ByteArray, off: Int = 0, len: Int = buf.size - off): Boolean {
            var o = off
            var remaining = len
            while (remaining > 0) {
                val n = input.read(buf, o, remaining)
                if (n <= 0) return false
                o += n
                remaining -= n
            }
            return true
        }
        fun skipFully(n: Long): Boolean {
            var remaining = n
            while (remaining > 0) {
                val skipped = input.skip(remaining)
                if (skipped <= 0) {
                    // skip() may return 0 — fall back to a single-byte read.
                    if (input.read() == -1) return false
                    remaining -= 1
                } else {
                    remaining -= skipped
                }
            }
            return true
        }
        val id3 = ByteArray(10)
        if (!readFully(id3)) return null
        var first = id3
        if (first[0] == 'I'.code.toByte() && first[1] == 'D'.code.toByte() && first[2] == '3'.code.toByte()) {
            val size = ((first[6].toInt() and 0x7F) shl 21) or
                ((first[7].toInt() and 0x7F) shl 14) or
                ((first[8].toInt() and 0x7F) shl 7) or
                (first[9].toInt() and 0x7F)
            if (!skipFully(size.toLong())) return null
            val magic = ByteArray(4)
            if (!readFully(magic)) return null
            first = ByteArray(10) // recycle variable; first 4 bytes hold the magic
            magic.copyInto(first, 0, 0, 4)
            if (!(first[0] == 'f'.code.toByte() && first[1] == 'L'.code.toByte() &&
                    first[2] == 'a'.code.toByte() && first[3] == 'C'.code.toByte())
            ) return null
        } else {
            if (!(first[0] == 'f'.code.toByte() && first[1] == 'L'.code.toByte() &&
                    first[2] == 'a'.code.toByte() && first[3] == 'C'.code.toByte())
            ) return null
            // We already consumed 10 bytes: 4 magic + 6 bytes of the first
            // block header area. Re-assemble from the buffer.
            // Put the 6 lookahead bytes back into play via a small prefix copy.
            val lookahead = first.copyOfRange(4, 10)
            // Continue parsing below using lookahead + stream.
            return readFlacBlocksWithPrefix(lookahead, input)
        }
        // ID3 path: stream is positioned right after "fLaC".
        readFlacBlocks(input)
    } catch (_: Exception) {
        null
    }
}

private fun readFlacBlocks(input: java.io.InputStream): FlacStreamInfo? {
    try {
        repeat(64) {
            val h = ByteArray(4)
            var o = 0
            while (o < 4) {
                val n = input.read(h, o, 4 - o)
                if (n <= 0) return null
                o += n
            }
            val header = h[0].toInt() and 0xFF
            val isLast = (header and 0x80) != 0
            val type = header and 0x7F
            val len = ((h[1].toInt() and 0xFF) shl 16) or
                ((h[2].toInt() and 0xFF) shl 8) or
                (h[3].toInt() and 0xFF)
            if (len < 0 || len > 32 * 1024 * 1024) return null
            if (type == 0) {
                if (len < 34) return null
                val body = ByteArray(34)
                var bo = 0
                while (bo < 34) {
                    val n = input.read(body, bo, 34 - bo)
                    if (n <= 0) return null
                    bo += n
                }
                // Skip any STREAMINFO remainder (should be none: len == 34).
                var rest = (len - 34).toLong()
                while (rest > 0) {
                    val s = input.skip(rest)
                    if (s <= 0) {
                        if (input.read() == -1) break
                        rest -= 1
                    } else rest -= s
                }
                return parseFlacStreamInfoBody(body, 0)
            }
            var remaining = len.toLong()
            while (remaining > 0) {
                val s = input.skip(remaining)
                if (s <= 0) {
                    if (input.read() == -1) return null
                    remaining -= 1
                } else remaining -= s
            }
            if (isLast) return null
        }
    } catch (_: Exception) {
        return null
    }
    return null
}

private fun readFlacBlocksWithPrefix(prefix: ByteArray, input: java.io.InputStream): FlacStreamInfo? {
    try {
        // Stitch the 6 buffered bytes in front of the live stream.
        val head = prefix + ByteArray(0)
        val buffered = java.io.SequenceInputStream(
            java.io.ByteArrayInputStream(head),
            input,
        )
        return readFlacBlocks(buffered)
    } catch (_: Exception) {
        return null
    }
}

