package com.lastwave.app.playback

import android.hardware.usb.UsbDeviceConnection
import android.util.Log
import kotlin.math.ln

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

    fun attach(): Boolean {
        available = false
        val ids = parseFeatureUnitIds().ifEmpty { FALLBACK_UNIT_IDS.toList() }
        for (id in ids.distinct()) {
            val found = discoverChannels(id)
            if (found.isEmpty()) continue
            if (!verifyWritable(id, found)) continue
            featureUnitId = id
            channels = found
            available = true
            Log.i(
                TAG,
                "Feature Unit volume verified id=0x${id.toString(16)} " +
                    "iface=$controlInterfaceId ch=${found.joinToString()}",
            )
            return true
        }
        Log.i(TAG, "No writable UAC Feature Unit volume control")
        return false
    }

    fun setNormalized(volume: Float): Boolean {
        if (!available || featureUnitId == 0) return false
        val clamped = volume.coerceIn(0f, 1f)
        val coded = encodedVolume(clamped)
        var ok = false
        for (channel in channels) {
            if (writeVolume(featureUnitId, channel, coded)) ok = true
        }
        writeMute(featureUnitId, clamped <= 0f)
        return ok
    }

    private fun discoverChannels(unitId: Int): IntArray {
        val found = CHANNELS_TO_TRY.filter { readVolume(unitId, it) != null }
        return found.toIntArray()
    }

    private fun verifyWritable(unitId: Int, found: IntArray): Boolean {
        val channel = found.first()
        val original = readVolume(unitId, channel) ?: return false
        val test = if (original == 0 || original == MUTE.toShort().toInt()) {
            (-20 * 256)
        } else {
            0
        }
        if (!writeVolume(unitId, channel, test)) return false
        runCatching { Thread.sleep(VERIFY_SLEEP_MS) }
        val updated = readVolume(unitId, channel)
        writeVolume(unitId, channel, original)
        val changed = updated != null && updated != original
        Log.i(
            TAG,
            "verify FU 0x${unitId.toString(16)} ch=$channel original=$original " +
                "test=$test readback=$updated writable=$changed",
        )
        return changed
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

    private companion object {
        const val TAG = "UacFeatureVolume"
        const val FEATURE_UNIT_SUBTYPE = 0x06
        const val VOLUME_WVALUE = 0x0200
        const val MUTE_WVALUE = 0x0100
        const val TIMEOUT_MS = 80
        const val VERIFY_SLEEP_MS = 8L
        const val MUTE = 0x8000
        val CHANNELS_TO_TRY = intArrayOf(0, 1, 2)
        val FALLBACK_UNIT_IDS = intArrayOf(0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A)

        fun encodedVolume(volume: Float): Int {
            if (volume <= 0f) return MUTE
            val db = (20.0 * ln(volume.toDouble()) / ln(10.0)).coerceIn(-127.0, 0.0)
            return (db * 256.0).toInt().coerceIn(-32767, 0)
        }
    }
}
