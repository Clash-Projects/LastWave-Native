package com.lastwave.app.playback

import android.hardware.usb.UsbDeviceConnection
import android.util.Log
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * UAC Feature Unit volume (SET_CUR on the AudioControl interface).
 *
 * Analog/digital gain after the PCM payload stays bit-identical at any
 * listening level. [attach] only returns true when SET_CUR actually changes
 * GET_CUR — a successful control transfer alone is not proof the DAC
 * attenuates. Dongles without a real volume control fail closed so the
 * exclusive path can software-scale instead of claiming hardware volume.
 */
class UacFeatureVolume(
    private val connection: UsbDeviceConnection,
    private val controlInterfaceId: Int,
) {
    var available: Boolean = false
        private set

    private var featureUnitId: Int = 0
    private var channels: IntArray = intArrayOf(0)
    private var minRaw: Int = DEFAULT_MIN_RAW
    private var maxRaw: Int = DEFAULT_MAX_RAW
    private var resRaw: Int = DEFAULT_RES_RAW

    fun attach(): Boolean {
        available = false
        val ids = parseFeatureUnitIds().ifEmpty { FALLBACK_UNIT_IDS.toList() }
        for (id in ids.distinct()) {
            val found = discoverChannels(id)
            if (found.isEmpty()) continue
            val range = queryVolumeRange(id, found)
            minRaw = range.first
            maxRaw = range.second
            resRaw = range.third
            val verifiedChannels = verifyWritableChannels(id, found)
            if (verifiedChannels.isEmpty()) continue
            featureUnitId = id
            channels = verifiedChannels
            available = true
            Log.i(
                TAG,
                "Feature Unit volume verified id=0x${id.toString(16)} " +
                    "iface=$controlInterfaceId ch=${verifiedChannels.joinToString()} " +
                    "range=[${minRaw / 256.0}dB..${maxRaw / 256.0}dB res=${resRaw / 256.0}dB]",
            )
            return true
        }
        Log.i(TAG, "No writable UAC Feature Unit volume control")
        return false
    }

    fun setNormalized(volume: Float): Boolean {
        if (!available || featureUnitId == 0) return false
        val clamped = volume.coerceIn(0f, 1f)
        val coded = encodedVolume(clamped, minRaw, maxRaw, resRaw)
        var ok = false
        for (channel in channels) {
            if (writeVolume(featureUnitId, channel, coded)) ok = true
        }
        writeMute(featureUnitId, clamped <= 0f)
        if (ok && clamped in 0.001f..0.85f && coded < -512) {
            val checkChannel = channels.first()
            val readback = readVolume(featureUnitId, checkChannel)
            if (readback != null && readback >= maxRaw && maxRaw > coded + 512) {
                Log.w(
                    TAG,
                    "DAC ignored SET_CUR coded=$coded on ch=$checkChannel (readback=$readback); failing closed",
                )
                return false
            }
        }
        return ok
    }

    private fun discoverChannels(unitId: Int): IntArray {
        val found = CHANNELS_TO_TRY.filter { readVolume(unitId, it) != null }
        return found.toIntArray()
    }

    private fun queryVolumeRange(unitId: Int, found: IntArray): Triple<Int, Int, Int> {
        for (ch in found) {
            readUac2Range(unitId, ch)?.let { return it }
            readUac1Range(unitId, ch)?.let { return it }
        }
        return Triple(DEFAULT_MIN_RAW, DEFAULT_MAX_RAW, DEFAULT_RES_RAW)
    }

    /**
     * UAC 2.0 GET_RANGE (bRequest = 0x02) on Feature Unit Volume control.
     * Layout: wNumSubRanges (2B) + N * [wMin (2B), wMax (2B), wRes (2B)].
     */
    private fun readUac2Range(unitId: Int, channel: Int): Triple<Int, Int, Int>? {
        val wIndex = (unitId shl 8) or controlInterfaceId
        val wValue = VOLUME_WVALUE or (channel and 0xFF)
        val buf = ByteArray(64)
        val ret = connection.controlTransfer(
            0xA1,
            0x02,
            wValue,
            wIndex,
            buf,
            buf.size,
            TIMEOUT_MS,
        )
        if (ret < 8) return null
        val count = (buf[0].toInt() and 0xFF) or ((buf[1].toInt() and 0xFF) shl 8)
        if (count <= 0) return null
        var overallMin = Int.MAX_VALUE
        var overallMax = Int.MIN_VALUE
        var bestRes = DEFAULT_RES_RAW
        val maxEntries = ((ret - 2) / 6).coerceAtMost(count)
        for (idx in 0 until maxEntries) {
            val base = 2 + idx * 6
            val subMin = ((buf[base].toInt() and 0xFF) or ((buf[base + 1].toInt() and 0xFF) shl 8)).toShort().toInt()
            val subMax = ((buf[base + 2].toInt() and 0xFF) or ((buf[base + 3].toInt() and 0xFF) shl 8)).toShort().toInt()
            val subRes = ((buf[base + 4].toInt() and 0xFF) or ((buf[base + 5].toInt() and 0xFF) shl 8)).toShort().toInt()
            if (subMin < overallMin) overallMin = subMin
            if (subMax > overallMax) overallMax = subMax
            if (subRes > 0 && subRes < bestRes) bestRes = subRes
        }
        if (overallMin >= overallMax) return null
        return Triple(
            overallMin.coerceIn(-32767, -256),
            overallMax.coerceIn(overallMin + 256, 0),
            bestRes.coerceIn(1, 1024),
        )
    }

    /**
     * UAC 1.0 GET_MIN (0x82), GET_MAX (0x83), GET_RES (0x84).
     */
    private fun readUac1Range(unitId: Int, channel: Int): Triple<Int, Int, Int>? {
        val minVal = readControlShort(unitId, channel, 0x82) ?: return null
        val maxVal = readControlShort(unitId, channel, 0x83) ?: return null
        if (minVal >= maxVal) return null
        val resVal = readControlShort(unitId, channel, 0x84)?.takeIf { it > 0 } ?: DEFAULT_RES_RAW
        return Triple(
            minVal.coerceIn(-32767, -256),
            maxVal.coerceIn(minVal + 256, 0),
            resVal.coerceIn(1, 1024),
        )
    }

    private fun readControlShort(unitId: Int, channel: Int, request: Int): Int? {
        val data = ByteArray(2)
        val wIndex = (unitId shl 8) or controlInterfaceId
        val wValue = VOLUME_WVALUE or (channel and 0xFF)
        val ret = connection.controlTransfer(
            0xA1,
            request,
            wValue,
            wIndex,
            data,
            data.size,
            TIMEOUT_MS,
        )
        if (ret < 2) return null
        val raw = (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8)
        return raw.toShort().toInt()
    }

    private fun verifyWritableChannels(unitId: Int, found: IntArray): IntArray {
        val writable = mutableListOf<Int>()
        val step = resRaw.coerceAtLeast(256)
        val testCandidate = (maxRaw - 12 * step).coerceIn(minRaw, maxRaw - step)
        for (channel in found) {
            val original = readVolume(unitId, channel) ?: continue
            val test = if (kotlin.math.abs(original - testCandidate) >= step) {
                testCandidate
            } else {
                (minRaw + 2 * step).coerceIn(minRaw, maxRaw)
            }
            if (!writeVolume(unitId, channel, test)) continue
            runCatching { Thread.sleep(VERIFY_SLEEP_MS) }
            val updated = readVolume(unitId, channel)
            writeVolume(unitId, channel, original)
            val changed = updated != null && updated != original
            Log.i(
                TAG,
                "verify FU 0x${unitId.toString(16)} ch=$channel original=$original " +
                    "test=$test readback=$updated writable=$changed",
            )
            if (changed) {
                writable += channel
            }
        }
        return writable.toIntArray()
    }

    private fun readVolume(unitId: Int, channel: Int): Int? {
        val data = ByteArray(2)
        val wIndex = (unitId shl 8) or controlInterfaceId
        val wValue = VOLUME_WVALUE or (channel and 0xFF)
        for (request in intArrayOf(0x01, 0x81)) {
            val ret = connection.controlTransfer(
                0xA1,
                request,
                wValue,
                wIndex,
                data,
                data.size,
                TIMEOUT_MS,
            )
            if (ret >= 2) {
                val raw = (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8)
                return raw.toShort().toInt()
            }
        }
        return null
    }

    private fun writeVolume(unitId: Int, channel: Int, coded: Int): Boolean {
        val data = ByteArray(2)
        data[0] = (coded and 0xFF).toByte()
        data[1] = ((coded shr 8) and 0xFF).toByte()
        val wIndex = (unitId shl 8) or controlInterfaceId
        val wValue = VOLUME_WVALUE or (channel and 0xFF)
        val ret = connection.controlTransfer(
            0x21,
            0x01,
            wValue,
            wIndex,
            data,
            data.size,
            TIMEOUT_MS,
        )
        return ret >= 0
    }

    private fun writeMute(unitId: Int, mute: Boolean): Boolean {
        val data = byteArrayOf(if (mute) 1 else 0)
        val wIndex = (unitId shl 8) or controlInterfaceId
        var ok = false
        for (channel in channels) {
            val wValue = MUTE_WVALUE or (channel and 0xFF)
            val ret = connection.controlTransfer(
                0x21,
                0x01,
                wValue,
                wIndex,
                data,
                data.size,
                TIMEOUT_MS,
            )
            if (ret >= 0) ok = true
        }
        return ok
    }

    private fun parseFeatureUnitIds(): List<Int> {
        val raw = connection.rawDescriptors ?: return emptyList()
        val ids = mutableListOf<Int>()
        var i = 0
        var inAudioControl = false
        while (i + 1 < raw.size) {
            val length = raw[i].toInt() and 0xFF
            if (length < 2 || i + length > raw.size) break
            val type = raw[i + 1].toInt() and 0xFF
            if (type == 0x04 && length >= 9) {
                val ifaceClass = raw[i + 5].toInt() and 0xFF
                val ifaceSub = raw[i + 6].toInt() and 0xFF
                inAudioControl = ifaceClass == 1 && ifaceSub == 1
            }
            if (inAudioControl && type == 0x24 && length >= 4) {
                val subtype = raw[i + 2].toInt() and 0xFF
                if (subtype == FEATURE_UNIT_SUBTYPE) {
                    ids += raw[i + 3].toInt() and 0xFF
                }
            }
            i += length
        }
        return ids
    }

    companion object {
        const val TAG = "UacFeatureVolume"
        const val FEATURE_UNIT_SUBTYPE = 0x06
        const val VOLUME_WVALUE = 0x0200
        const val MUTE_WVALUE = 0x0100
        const val TIMEOUT_MS = 80
        const val VERIFY_SLEEP_MS = 8L
        const val MUTE = 0x8000
        const val DEFAULT_MIN_RAW = -15872 // -62.0 dB in 1/256 dB units
        const val DEFAULT_MAX_RAW = 0      //   0.0 dB
        const val DEFAULT_RES_RAW = 256    //   1.0 dB step alignment
        private const val PERCEPTUAL_FLOOR_DB = -62.0
        val CHANNELS_TO_TRY = intArrayOf(0, 1, 2)
        val FALLBACK_UNIT_IDS = intArrayOf(0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A)

        /**
         * Maps normalized slider position [volume] in (0, 1] to a perceptual
         * dB attenuation (-62 dB at 0+ .. 0 dB at 1.0), so step 1/15 (~6.7%)
         * is ~ -53.8 dB instead of -23.5 dB, then aligns to [resRaw] within
         * the DAC's hardware [minRaw]..[maxRaw] range.
         */
        fun perceptualDb(volume: Float, minDb: Double = PERCEPTUAL_FLOOR_DB, maxDb: Double = 0.0): Double {
            val v = volume.coerceIn(0f, 1f).toDouble()
            if (v <= 0.0) return -127.0
            val floor = minDb.coerceIn(-72.0, -48.0)
            val curved = v.pow(0.75)
            return (floor + (maxDb - floor) * curved).coerceIn(minDb, maxDb)
        }

        fun perceptualLinearGain(volume: Float): Float {
            val v = volume.coerceIn(0f, 1f)
            if (v <= 0f) return 0f
            if (v >= 0.999f) return 1f
            val db = perceptualDb(v, PERCEPTUAL_FLOOR_DB, 0.0)
            return 10.0.pow(db / 20.0).toFloat().coerceIn(0f, 1f)
        }

        fun encodedVolume(
            volume: Float,
            minRaw: Int = DEFAULT_MIN_RAW,
            maxRaw: Int = DEFAULT_MAX_RAW,
            resRaw: Int = DEFAULT_RES_RAW,
        ): Int {
            if (volume <= 0f) return MUTE
            val minDb = (minRaw / 256.0).coerceAtMost(-24.0)
            val maxDb = (maxRaw / 256.0).coerceAtMost(0.0)
            val db = perceptualDb(volume, minDb, maxDb)
            val rawTarget = (db * 256.0).roundToInt()
            val step = resRaw.coerceAtLeast(1)
            val aligned = if (step > 1) {
                minRaw + ((rawTarget - minRaw + step / 2) / step) * step
            } else {
                rawTarget
            }
            return aligned.coerceIn(minRaw, maxRaw)
        }
    }
}
