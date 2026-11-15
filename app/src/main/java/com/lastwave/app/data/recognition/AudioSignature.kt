package com.lastwave.app.data.recognition

import android.util.Base64
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max

/**
 * Builds the spectral-peak fingerprint the recognition service matches against.
 *
 * Input is mono 16-bit PCM at [SAMPLE_RATE]. Every [HOP] samples a Hann-windowed
 * [FRAME]-point spectrum is taken; a bin is kept as a peak when it is the local
 * maximum of a smoothed time/frequency neighbourhood. Peaks are grouped into four
 * frequency bands and serialised into the service's little-endian binary layout,
 * which carries a CRC-32 over everything after its first two header words.
 */
object AudioSignature {

    const val SAMPLE_RATE = 16_000

    private const val FRAME = 2048
    private const val HOP = 128
    private const val BINS = FRAME / 2 + 1
    private const val HISTORY = 256

    /** Frames of look-back before a bin can be judged against its future neighbours. */
    private const val PEAK_LAG = 46
    private const val ENVELOPE_LAG = 49
    private const val MIN_POWER = 1.0 / 64.0
    private const val POWER_SCALE = 1.0 / (1 shl 17)

    private val SMOOTHING_FRAMES = intArrayOf(-1, -3, -6)
    private val SAME_FRAME_NEIGHBOURS = intArrayOf(-10, -7, -4, -3, 1, 2, 5, 8)
    private val OTHER_FRAME_OFFSETS = intArrayOf(-53, -45, 165, 172, 179, 186, 193, 200, 214, 221, 228, 235, 242, 249)

    /** Peaks below [LOWEST_HZ] or above the last edge are dropped; the rest fall into one band each. */
    private const val LOWEST_HZ = 250.0
    private val BAND_EDGES_HZ = doubleArrayOf(520.0, 1450.0, 3500.0, 5500.0)

    private const val HEADER_BYTES = 48
    private const val RATE_ID = 3 // index of 16 kHz in the service's rate table
    private const val MAGIC_FILE = 0xCAFE2580.toInt()
    private const val MAGIC_SECTION = 0x94119C00.toInt()
    private const val MAGIC_BODY = 0x40000000
    private const val BAND_TAG = 0x60030040

    private val hann = DoubleArray(FRAME) { 0.5 * (1.0 - cos(2.0 * PI * (it + 1) / (FRAME + 1))) }

    private class Peak(val frame: Int, val magnitude: Int, val bin: Int)

    /** `data:` URI ready to send as the signature field. */
    fun toDataUri(pcm: ShortArray): String =
        "data:audio/vnd.shazam.sig;base64," + Base64.encodeToString(encode(pcm), Base64.NO_WRAP)

    /** Serialised bytes; separate from Base64 so unit tests can inspect them. */
    internal fun encode(pcm: ShortArray): ByteArray {
        require(pcm.size >= FRAME) { "need at least $FRAME samples" }
        val bands = Array(BAND_EDGES_HZ.size) { ArrayList<Peak>() }
        extractPeaks(pcm, bands)
        return serialise(bands, pcm.size / HOP * HOP)
    }

    private fun extractPeaks(pcm: ShortArray, bands: Array<ArrayList<Peak>>) {
        val fft = SpectralFft(FRAME)
        val spectra = Array(HISTORY) { DoubleArray(BINS) }
        val envelopes = Array(HISTORY) { DoubleArray(BINS) }
        val frame = DoubleArray(FRAME)
        val power = DoubleArray(BINS)
        val envelope = DoubleArray(BINS)

        var written = 0
        var end = HOP
        while (end <= pcm.size) {
            val begin = end - FRAME
            for (i in 0 until FRAME) {
                val src = begin + i
                frame[i] = if (src >= 0) pcm[src] * hann[i] else 0.0
            }
            fft.powerSpectrum(frame, power)
            val spectrum = spectra[written % HISTORY]
            for (k in 0 until BINS) spectrum[k] = max(power[k] * POWER_SCALE, 1e-10)

            // Widen each bin over its two upper neighbours, then carry the running
            // maximum back into the last few frames' envelopes.
            spectrum.copyInto(envelope)
            for (k in 0 until BINS - 2) envelope[k] = max(envelope[k], max(envelope[k + 1], envelope[k + 2]))
            for (k in 0 until BINS) {
                var running = envelope[k]
                for (back in SMOOTHING_FRAMES) {
                    val past = envelopes[Math.floorMod(written + back, HISTORY)]
                    if (past[k] > running) running = past[k]
                    past[k] = running
                }
            }
            envelope.copyInto(envelopes[written % HISTORY])
            written++

            if (written > PEAK_LAG) pickPeaks(written, spectra, envelopes, bands)
            end += HOP
        }
    }

    private fun pickPeaks(
        written: Int,
        spectra: Array<DoubleArray>,
        envelopes: Array<DoubleArray>,
        bands: Array<ArrayList<Peak>>,
    ) {
        val spectrum = spectra[Math.floorMod(written - PEAK_LAG, HISTORY)]
        val envelope = envelopes[Math.floorMod(written - ENVELOPE_LAG, HISTORY)]
        val frameNumber = written - PEAK_LAG

        for (bin in 10 until BINS - 8) {
            val power = spectrum[bin]
            if (power < MIN_POWER || power < envelope[bin]) continue

            var ceiling = 0.0
            for (n in SAME_FRAME_NEIGHBOURS) if (envelope[bin + n] > ceiling) ceiling = envelope[bin + n]
            if (power <= ceiling) continue
            for (n in OTHER_FRAME_OFFSETS) {
                val v = envelopes[Math.floorMod(written + n, HISTORY)][bin - 1]
                if (v > ceiling) ceiling = v
            }
            if (power <= ceiling) continue

            // Parabolic interpolation on the log level gives a sub-bin frequency (1/64 bin units).
            val below = logLevel(spectrum[bin - 1])
            val above = logLevel(spectrum[bin + 1])
            val level = logLevel(power)
            val curvature = level * 2 - below - above
            if (curvature <= 0) continue
            val refinedBin = bin * 64 + (above - below) * 32 / curvature
            val hz = refinedBin * (SAMPLE_RATE / 2.0 / (FRAME / 2) / 64.0)

            if (hz < LOWEST_HZ || hz > BAND_EDGES_HZ.last()) continue
            val band = BAND_EDGES_HZ.indexOfFirst { hz < it }.let { if (it < 0) BAND_EDGES_HZ.lastIndex else it }
            bands[band].add(Peak(frameNumber, level.toInt(), refinedBin.toInt()))
        }
    }

    private fun logLevel(power: Double) = ln(max(MIN_POWER, power)) * 1477.3 + 6144

    private fun serialise(bands: Array<ArrayList<Peak>>, sampleCount: Int): ByteArray {
        val contents = ByteArrayOutputStream()
        bands.forEachIndexed { index, peaks ->
            if (peaks.isEmpty()) return@forEachIndexed
            val encoded = encodePeaks(peaks)
            contents.putInt(BAND_TAG + index)
            contents.putInt(encoded.size)
            contents.write(encoded)
            repeat(Math.floorMod(-encoded.size, 4)) { contents.write(0) }
        }
        val body = contents.toByteArray()

        val out = ByteArrayOutputStream(HEADER_BYTES + 8 + body.size)
        out.putInt(MAGIC_FILE)
        out.putInt(0) // checksum, patched below
        out.putInt(body.size + 8)
        out.putInt(MAGIC_SECTION)
        repeat(3) { out.putInt(0) }
        out.putInt(RATE_ID shl 27)
        repeat(2) { out.putInt(0) }
        out.putInt((sampleCount + SAMPLE_RATE * 0.24).toInt())
        out.putInt((15 shl 19) + 0x40000)
        out.putInt(MAGIC_BODY)
        out.putInt(body.size + 8)
        out.write(body)

        val bytes = out.toByteArray()
        val crc = CRC32().apply { update(bytes, 8, bytes.size - 8) }.value.toInt()
        for (i in 0 until 4) bytes[4 + i] = (crc ushr (8 * i)).toByte()
        return bytes
    }

    /** Frame numbers are delta-coded; a gap of 255+ frames is escaped with an absolute value. */
    private fun encodePeaks(peaks: List<Peak>): ByteArray {
        val out = ByteArrayOutputStream()
        var previous = 0
        for (p in peaks) {
            if (p.frame - previous >= 255) {
                out.write(0xFF)
                out.putInt(p.frame)
                previous = p.frame
            }
            out.write(p.frame - previous)
            out.putShort(p.magnitude)
            out.putShort(p.bin)
            previous = p.frame
        }
        return out.toByteArray()
    }

    private fun ByteArrayOutputStream.putInt(v: Int) {
        for (i in 0 until 4) write(v ushr (8 * i))
    }

    private fun ByteArrayOutputStream.putShort(v: Int) {
        write(v)
        write(v ushr 8)
    }
}
