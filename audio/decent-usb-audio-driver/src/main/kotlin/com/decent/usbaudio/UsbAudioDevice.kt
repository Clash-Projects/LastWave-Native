package com.decent.usbaudio

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log


/**
 * Manages the lifecycle of a USB Audio Class device for bit-perfect output.
 *
 * Responsibilities:
 * - Discover connected USB audio devices
 * - Request user permission via [UsbManager.requestPermission]
 * - Open the device and extract endpoint/interface info
 * - Provide the file descriptor and endpoint addresses to [UsbAudioStream]
 *
 * This class does NOT perform audio I/O — that's handled by the native layer
 * via [UsbAudioStream].
 */
class UsbAudioDevice private constructor(private val context: Context) {

    private var usbManager: UsbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var connection: UsbDeviceConnection? = null
    private var currentDevice: UsbDevice? = null
    private var claimedInterface: UsbInterface? = null
    private var claimedControlInterface: UsbInterface? = null

    companion object {
        private const val TAG = "UsbAudioDevice"
        private const val ACTION_USB_PERMISSION_SUFFIX = ".USB_AUDIO_PERMISSION"

        @Volatile
        private var instance: UsbAudioDevice? = null

        /**
         * Get the singleton instance. All callers share the same connection
         * share the same connection and fd, preventing ENODEV from competing opens.
         */
        fun getInstance(context: Context): UsbAudioDevice {
            return instance ?: synchronized(this) {
                instance ?: UsbAudioDevice(context.applicationContext).also { instance = it }
            }
        }
    }


    /**
     * Find the first connected USB audio output device.
     *
     * Scans all USB devices for one with an AudioStreaming interface
     * (class=1, subclass=2) that has an isochronous OUT endpoint.
     *
     * @return The USB device, or null if none found.
     */
    fun findUsbAudioDevice(): UsbDevice? {
        for (device in usbManager.deviceList.values) {
            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                // USB Audio Class: class=1 (Audio), subclass=2 (AudioStreaming)
                if (iface.interfaceClass == UsbConstants.USB_CLASS_AUDIO &&
                    iface.interfaceSubclass == 2) {
                    Log.i(TAG, "Found USB audio device: ${device.productName} " +
                            "(vendor=0x${device.vendorId.toString(16)}, " +
                            "product=0x${device.productId.toString(16)})")
                    return device
                }
            }
        }
        Log.d(TAG, "No USB audio device found")
        return null
    }

    /**
     * Check if we already have permission to access the device.
     */
    fun hasPermission(device: UsbDevice): Boolean {
        return usbManager.hasPermission(device)
    }

    /**
     * Request permission from the user to access the USB device.
     *
     * @param device   The USB device to request access for.
     * @param callback Called with true if permission granted, false otherwise.
     */
    fun requestPermission(device: UsbDevice, callback: (Boolean) -> Unit) {
        if (usbManager.hasPermission(device)) {
            Log.i(TAG, "Permission already granted for ${device.productName}")
            callback(true)
            return
        }

        val intent = Intent(context.packageName + ACTION_USB_PERMISSION_SUFFIX)
        intent.setPackage(context.packageName)
        val permissionIntent = PendingIntent.getBroadcast(
                context, 0,
                intent,
                PendingIntent.FLAG_MUTABLE
        )

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action == context.packageName + ACTION_USB_PERMISSION_SUFFIX) {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    Log.i(TAG, "USB permission result: granted=$granted for ${device.productName}")
                    context.unregisterReceiver(this)
                    callback(granted)
                }
            }
        }

        val filter = IntentFilter(context.packageName + ACTION_USB_PERMISSION_SUFFIX)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }

        usbManager.requestPermission(device, permissionIntent)
        Log.i(TAG, "Permission requested for ${device.productName}")
    }

    /**
     * Open the USB device and extract all information needed for audio I/O.
     *
     * Finds the AudioStreaming interface, locates the isochronous OUT and
     * feedback IN endpoints, and returns everything the native layer needs.
     *
     * @param device The USB audio device to open.
     * @return Device info with fd and endpoint addresses, or null on failure.
     */
    /** Cached device info from the last successful openDevice() call. */
    private var cachedDeviceInfo: UsbAudioDeviceInfo? = null

    fun openDevice(device: UsbDevice): UsbAudioDeviceInfo? {
        // Return cached info if already open with valid connection
        val cached = cachedDeviceInfo
        if (cached != null && connection != null) {
            Log.i(TAG, "Device already open, reusing fd=${cached.fd}")
            return cached
        }
        // Close any stale connection before opening new
        closeDevice()
        val conn = usbManager.openDevice(device)
        if (conn == null) {
            Log.e(TAG, "Failed to open device ${device.productName}")
            return null
        }

        // Find the AudioStreaming interface and its endpoints
        var streamingInterface: UsbInterface? = null
        var endpointOut = -1
        var endpointFeedback = -1
        var maxPacketSize = 0
        var altSettingCount = 0

        // Count alternate settings for the streaming interface
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == UsbConstants.USB_CLASS_AUDIO &&
                iface.interfaceSubclass == 2) {
                altSettingCount++

                // Look for endpoints in non-zero alt settings
                if (iface.endpointCount > 0 && streamingInterface == null) {
                    streamingInterface = iface

                    for (e in 0 until iface.endpointCount) {
                        val ep = iface.getEndpoint(e)
                        when {
                            // Isochronous OUT endpoint (audio data)
                            ep.type == UsbConstants.USB_ENDPOINT_XFER_ISOC &&
                                    ep.direction == UsbConstants.USB_DIR_OUT -> {
                                endpointOut = ep.address
                                maxPacketSize = ep.maxPacketSize
                                Log.i(TAG, "Found ISO OUT endpoint: address=0x${ep.address.toString(16)}, " +
                                        "maxPacket=$maxPacketSize, interval=${ep.interval}")
                            }
                            // Isochronous IN endpoint (feedback)
                            ep.type == UsbConstants.USB_ENDPOINT_XFER_ISOC &&
                                    ep.direction == UsbConstants.USB_DIR_IN -> {
                                endpointFeedback = ep.address
                                Log.i(TAG, "Found ISO IN (feedback) endpoint: address=0x${ep.address.toString(16)}, " +
                                        "interval=${ep.interval}")
                            }
                        }
                    }
                }
            }
        }

        if (streamingInterface == null || endpointOut < 0) {
            Log.e(TAG, "No suitable AudioStreaming interface/endpoint found")
            conn.close()
            return null
        }

        // Claim the AudioControl interface (0) with force=true to disconnect kernel driver
        val controlInterface = (0 until device.interfaceCount)
                .map { device.getInterface(it) }
                .firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_AUDIO && it.interfaceSubclass == 1 }

        if (controlInterface != null) {
            val claimed = conn.claimInterface(controlInterface, true)
            Log.i(TAG, "Claimed AudioControl interface ${controlInterface.id} force=true: $claimed")
            if (claimed) claimedControlInterface = controlInterface
        }

        // Claim the AudioStreaming interface with force=true to disconnect kernel driver (snd-usb-audio)
        // NOTE: We claim the zero-bandwidth alt setting (alt=0). The actual streaming alt setting
        // will be activated later via setInterface() which allocates USB bandwidth.
        val claimed = conn.claimInterface(streamingInterface, true)
        Log.i(TAG, "Claimed AudioStreaming interface ${streamingInterface.id} force=true: $claimed " +
                "(alt=${streamingInterface.alternateSetting}, endpoints=${streamingInterface.endpointCount})")
        if (!claimed) {
            Log.e(TAG, "Failed to claim streaming interface — kernel driver may still be active")
            conn.close()
            return null
        }
        claimedInterface = streamingInterface

        // Force alt=0 to stop any streaming left by kernel driver
        val zeroAlt = (0 until device.interfaceCount)
                .map { device.getInterface(it) }
                .firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_AUDIO &&
                        it.interfaceSubclass == 2 && it.alternateSetting == 0 }
        if (zeroAlt != null) {
            conn.setInterface(zeroAlt)
            Log.i(TAG, "Reset streaming to alt=0 (zero-bandwidth)")
        }
        Thread.sleep(100)

        // Log all available alt settings for debugging
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == UsbConstants.USB_CLASS_AUDIO && iface.interfaceSubclass == 2) {
                Log.d(TAG, "  AudioStreaming alt=${iface.alternateSetting}: " +
                        "id=${iface.id}, endpoints=${iface.endpointCount}")
            }
        }

        val fd = conn.fileDescriptor
        val interfaceId = streamingInterface.id

        Log.i(TAG, "Device opened: ${device.productName}, fd=$fd, " +
                "iface=$interfaceId, epOut=0x${endpointOut.toString(16)}, " +
                "epFb=0x${endpointFeedback.toString(16)}, " +
                "maxPacket=$maxPacketSize, altSettings=$altSettingCount")

        connection = conn
        currentDevice = device

        // Auto-detect Clock Source ID and best alt setting from USB descriptors
        val clockSourceId = parseClockSourceId(conn)
        val (bestAlt, bestBits) = parseBestAltSetting(conn)
        parseStreamingEndpoints(conn.rawDescriptors)
        Log.i(TAG, "Auto-detected: clockSourceId=0x${clockSourceId.toString(16)}, " +
                "bestAlt=$bestAlt, bestBits=$bestBits")

        val info = UsbAudioDeviceInfo(
                connection = conn,
                fd = fd,
                deviceName = device.productName ?: "USB Audio Device",
                interfaceId = interfaceId,
                endpointOutAddress = endpointOut,
                endpointFeedbackAddress = endpointFeedback,
                maxPacketSize = maxPacketSize,
                altSettingCount = altSettingCount,
                clockSourceId = clockSourceId,
                bestAltSetting = bestAlt,
                bestBitDepth = bestBits,
                controlInterfaceId = controlInterface?.id ?: 0,
        )
        cachedDeviceInfo = info
        return info
    }

    /**
     * Perform a USB device reset via native ioctl, then close and reopen.
     * This clears any stale clock/endpoint state left by the kernel driver.
     * After reset, the DAC reinitializes and will accept our SET_CUR.
     */
    fun resetAndReopen() {
        val conn = connection ?: return
        val fd = conn.fileDescriptor

        Log.i(TAG, "Performing REAL USBDEVFS_RESET on fd=$fd...")

        // Real USB port reset via native ioctl — resets DAC clock state
        val ret = UsbAudioStream.nativeUsbReset(fd)
        Log.i(TAG, "USBDEVFS_RESET result: $ret")

        // Reset releases all interface claims. The fd remains valid.
        // Clear cache so openDevice re-claims, but KEEP the connection
        // so the same fd is reused (native claims are on this fd).
        cachedDeviceInfo = null
        claimedInterface = null
        claimedControlInterface = null
        activeClockId = -1
        // DO NOT close connection — the fd from reset+native claim must be reused
        // The next openDevice() will see connection != null and skip re-opening
    }

    /**
     * Parse UAC2 clock entities from raw descriptors.
     *
     * @return First Clock Source ID, or -1 if none.
     */
    private fun parseClockSourceId(conn: UsbDeviceConnection): Int {
        val raw = conn.rawDescriptors ?: return -1
        parsedClockSources = emptyList()
        parsedClockSourceIds = emptyList()
        parsedClockSelectorId = -1
        parsedSelectorPins = intArrayOf()
        pathClockId = -1
        activeClockId = -1

        var i = 0
        var inAudioControl = false
        var firstClock = -1

        while (i + 1 < raw.size) {
            val bLength = raw[i].toInt() and 0xFF
            if (bLength < 2) break
            if (i + bLength > raw.size) break

            val bDescriptorType = raw[i + 1].toInt() and 0xFF

            if (bDescriptorType == 0x04 && bLength >= 9) {
                val bInterfaceClass = raw[i + 5].toInt() and 0xFF
                val bInterfaceSubClass = raw[i + 6].toInt() and 0xFF
                inAudioControl = (bInterfaceClass == 1 && bInterfaceSubClass == 1)
            }

            if (inAudioControl && bDescriptorType == 0x24 && bLength >= 3) {
                val bDescriptorSubtype = raw[i + 2].toInt() and 0xFF
                when (bDescriptorSubtype) {
                    0x02 -> if (bLength >= 17) {
                        // UAC2 INPUT_TERMINAL: bCSourceID at offset 7.
                        if (pathClockId < 0) {
                            pathClockId = raw[i + 7].toInt() and 0xFF
                            Log.i(TAG, "INPUT_TERMINAL bCSourceID=0x${pathClockId.toString(16)}")
                        }
                    }
                    0x0A -> if (bLength >= 5) {
                        val bClockID = raw[i + 3].toInt() and 0xFF
                        val attr = raw[i + 4].toInt() and 0xFF
                        Log.i(
                            TAG,
                            "CLOCK_SOURCE bClockID=0x${bClockID.toString(16)} attr=0x${attr.toString(16)}",
                        )
                        parsedClockSources = parsedClockSources + ClockSource(bClockID, attr)
                        parsedClockSourceIds = parsedClockSourceIds + bClockID
                        if (firstClock < 0) firstClock = bClockID
                    }
                    0x0B -> if (bLength >= 5) {
                        parsedClockSelectorId = raw[i + 3].toInt() and 0xFF
                        val nrPins = raw[i + 4].toInt() and 0xFF
                        if (nrPins in 1..16 && bLength >= 5 + nrPins) {
                            parsedSelectorPins = IntArray(nrPins) { p ->
                                raw[i + 5 + p].toInt() and 0xFF
                            }
                        }
                        Log.i(
                            TAG,
                            "CLOCK_SELECTOR id=0x${parsedClockSelectorId.toString(16)} " +
                                "pins=${parsedSelectorPins.joinToString { "0x${it.toString(16)}" }}",
                        )
                    }
                }
            }

            i += bLength
        }

        if (firstClock < 0) {
            Log.w(TAG, "parseClockSourceId: no CLOCK_SOURCE descriptor found")
        }
        return firstClock
    }

    private data class ClockSource(val id: Int, val attributes: Int) {
        val clockType: Int get() = attributes and 0x3
        val fixed: Boolean get() = clockType == 0x1
    }

    private data class ParsedAlt(
        val alt: Int,
        val bitResolution: Int,
        val subslotSize: Int,
    ) {
        val wireBits: Int get() = (subslotSize * 8).coerceAtLeast(bitResolution)
    }

    private data class IsoOutEp(
        val address: Int,
        val maxPacketBytes: Int,
        val interval: Int,
    )

    private var parsedAltSettings: List<ParsedAlt> = emptyList()
    private var parsedClockSources: List<ClockSource> = emptyList()
    private var parsedClockSourceIds: List<Int> = emptyList()
    private var parsedClockSelectorId: Int = -1
    private var parsedSelectorPins: IntArray = intArrayOf()
    private var pathClockId: Int = -1
    private var activeClockId: Int = -1
    private var parsedIsoOut: Map<Int, IsoOutEp> = emptyMap()
    private var parsedIsoFb: Map<Int, Int> = emptyMap()

    private fun parseBestAltSetting(conn: UsbDeviceConnection): Pair<Int, Int> {
        val raw = conn.rawDescriptors ?: return Pair(1, 16)
        val altSettings = mutableListOf<ParsedAlt>()

        var i = 0
        var currentAlt = 0
        var inAudioStreaming = false
        var bestAlt = 1
        var bestWire = 16

        while (i + 1 < raw.size) {
            val bLength = raw[i].toInt() and 0xFF
            if (bLength < 2) break
            if (i + bLength > raw.size) break

            val bDescriptorType = raw[i + 1].toInt() and 0xFF

            if (bDescriptorType == 0x04 && bLength >= 9) {
                val bInterfaceClass = raw[i + 5].toInt() and 0xFF
                val bInterfaceSubClass = raw[i + 6].toInt() and 0xFF
                val bAlternateSetting = raw[i + 3].toInt() and 0xFF
                inAudioStreaming = (bInterfaceClass == 1 && bInterfaceSubClass == 2)
                if (inAudioStreaming) currentAlt = bAlternateSetting
            }

            if (inAudioStreaming && bDescriptorType == 0x24 && bLength >= 6) {
                val bDescriptorSubtype = raw[i + 2].toInt() and 0xFF
                val bFormatType = raw[i + 3].toInt() and 0xFF
                if (bDescriptorSubtype == 0x02 && bFormatType == 0x01) {
                    val subslot: Int
                    val bits: Int
                    if (bLength == 6) {
                        subslot = raw[i + 4].toInt() and 0xFF
                        bits = raw[i + 5].toInt() and 0xFF
                    } else {
                        subslot = raw[i + 5].toInt() and 0xFF
                        bits = raw[i + 6].toInt() and 0xFF
                    }
                    Log.i(
                        TAG,
                        "parseBestAltSetting: alt=$currentAlt subslot=$subslot bitResolution=$bits",
                    )
                    if (currentAlt > 0 && subslot in 1..4 && bits in 8..32) {
                        val parsed = ParsedAlt(currentAlt, bits, subslot)
                        altSettings.add(parsed)
                        if (parsed.wireBits > bestWire) {
                            bestWire = parsed.wireBits
                            bestAlt = currentAlt
                        }
                    }
                }
            }

            i += bLength
        }

        parsedAltSettings = altSettings
        Log.i(TAG, "parseBestAltSetting: best alt=$bestAlt wireBits=$bestWire, all=$altSettings")
        return Pair(bestAlt, bestWire)
    }

    private fun parseStreamingEndpoints(raw: ByteArray?) {
        parsedIsoOut = emptyMap()
        parsedIsoFb = emptyMap()
        if (raw == null) return
        val outs = mutableMapOf<Int, IsoOutEp>()
        val fbs = mutableMapOf<Int, Int>()
        var i = 0
        var currentAlt = 0
        var inAudioStreaming = false
        while (i + 1 < raw.size) {
            val bLength = raw[i].toInt() and 0xFF
            if (bLength < 2) break
            if (i + bLength > raw.size) break
            val bDescriptorType = raw[i + 1].toInt() and 0xFF
            if (bDescriptorType == 0x04 && bLength >= 9) {
                val bInterfaceClass = raw[i + 5].toInt() and 0xFF
                val bInterfaceSubClass = raw[i + 6].toInt() and 0xFF
                inAudioStreaming = (bInterfaceClass == 1 && bInterfaceSubClass == 2)
                if (inAudioStreaming) currentAlt = raw[i + 3].toInt() and 0xFF
            }
            if (inAudioStreaming && bDescriptorType == 0x05 && bLength >= 7) {
                val addr = raw[i + 2].toInt() and 0xFF
                val attr = raw[i + 3].toInt() and 0xFF
                val wMax = (raw[i + 4].toInt() and 0xFF) or
                    ((raw[i + 5].toInt() and 0xFF) shl 8)
                val interval = (raw[i + 6].toInt() and 0xFF).coerceAtLeast(1)
                if (attr and 0x03 == 1) {
                    if (addr and 0x80 == 0) {
                        val bytes = hsIsoPacketBytes(wMax)
                        outs[currentAlt] = IsoOutEp(addr, bytes, interval)
                        Log.i(
                            TAG,
                            "ISO OUT alt=$currentAlt addr=0x${addr.toString(16)} " +
                                "wMax=0x${wMax.toString(16)} bytes=$bytes interval=$interval",
                        )
                    } else {
                        fbs[currentAlt] = addr
                    }
                }
            }
            i += bLength
        }
        parsedIsoOut = outs
        parsedIsoFb = fbs
    }

    private fun hsIsoPacketBytes(wMaxPacketSize: Int): Int {
        if (wMaxPacketSize <= 0) return 0
        val size = wMaxPacketSize and 0x7FF
        val extra = (wMaxPacketSize shr 11) and 0x3
        return size * (1 + extra)
    }

    /**
     * Find the alt setting that matches the given source bit depth exactly.
     * If no exact match, returns the next higher bit depth.
     * Fallback: returns the best (highest) alt setting.
     *
     * @return Pair(altSetting, bitDepth)
     */
    fun findAltSettingForBitDepth(
        targetBitDepth: Int,
        sampleRateHz: Int = 48_000,
        channelCount: Int = 2,
    ): Pair<Int, Int> {
        val ranked = parsedAltSettings.map { alt ->
            val packet = parsedIsoOut[alt.alt]?.maxPacketBytes
                ?: isoPacketCapacity(endpointsForAlt(alt.alt)?.third ?: 0)
            val needed = minIsoPacketBytes(sampleRateHz, channelCount, alt.wireBits, 1)
            Triple(alt, packet, needed)
        }
        val capable = ranked.filter { it.second >= it.third }
        val pool = (if (capable.isNotEmpty()) capable else ranked.sortedByDescending { it.second })
            .map { it.first }

        fun pick(from: List<ParsedAlt>): ParsedAlt? {
            val exact = from.filter { it.bitResolution == targetBitDepth }
            // 24-bit packed (3 bytes) makes 11-frame packets at 88.2 kHz a
            // length the host cannot DMA. A 4-byte subslot stays aligned.
            val aligned = exact.filter { it.subslotSize >= 4 }
            val exactPick = (if (aligned.isNotEmpty()) aligned else exact)
                .minByOrNull { kotlin.math.abs(it.wireBits - targetBitDepth) }
            if (exactPick != null) return exactPick
            val higher = from.filter { it.bitResolution > targetBitDepth || it.wireBits > targetBitDepth }
                .minByOrNull { it.wireBits }
            if (higher != null) return higher
            return from.maxByOrNull { it.wireBits }
        }

        val chosen = pick(pool)
        if (chosen != null) {
            Log.i(
                TAG,
                "findAltSettingForBitDepth($targetBitDepth, $sampleRateHz Hz): " +
                    "alt=${chosen.alt} resolution=${chosen.bitResolution} " +
                    "wire=${chosen.wireBits} subslot=${chosen.subslotSize}",
            )
            return Pair(chosen.alt, chosen.wireBits)
        }
        val info = cachedDeviceInfo ?: return Pair(1, 16)
        return Pair(info.bestAltSetting, info.bestBitDepth)
    }

    /**
     * Close the USB device and release all resources.
     */
    fun closeDevice() {
        cachedDeviceInfo = null
        claimedInterface?.let { iface ->
            connection?.releaseInterface(iface)
            claimedInterface = null
        }
        claimedControlInterface?.let { iface ->
            connection?.releaseInterface(iface)
            claimedControlInterface = null
        }
        connection?.close()
        connection = null
        currentDevice = null
        activeClockId = -1
        Log.i(TAG, "USB device closed")
    }

    fun endpointsForAlt(altSetting: Int): Triple<Int, Int, Int>? {
        parsedIsoOut[altSetting]?.let { ep ->
            val fb = parsedIsoFb[altSetting] ?: parsedIsoFb.values.firstOrNull() ?: -1
            return Triple(ep.address, fb, ep.maxPacketBytes)
        }
        val device = currentDevice ?: return null
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass != UsbConstants.USB_CLASS_AUDIO ||
                iface.interfaceSubclass != 2 ||
                iface.alternateSetting != altSetting
            ) {
                continue
            }
            var endpointOut = -1
            var endpointFeedback = -1
            var maxPacketSize = 0
            for (e in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(e)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_ISOC) continue
                if (ep.direction == UsbConstants.USB_DIR_OUT) {
                    endpointOut = ep.address
                    maxPacketSize = ep.maxPacketSize
                } else if (ep.direction == UsbConstants.USB_DIR_IN) {
                    endpointFeedback = ep.address
                }
            }
            if (endpointOut >= 0) return Triple(endpointOut, endpointFeedback, maxPacketSize)
        }
        return null
    }

    fun isoIntervalForAlt(altSetting: Int): Int =
        parsedIsoOut[altSetting]?.interval ?: 1

    private fun clockWIndex(csId: Int): Int {
        val iface = cachedDeviceInfo?.controlInterfaceId ?: 0
        return (csId shl 8) or iface
    }

    private fun isoPacketCapacity(raw: Int): Int {
        if (raw <= 0) return 0
        val extra = (raw shr 11) and 0x3
        val size = raw and 0x7FF
        return if (extra > 0) size * (1 + extra) else raw
    }

    private fun minIsoPacketBytes(rateHz: Int, channels: Int, bitDepth: Int, interval: Int = 1): Int {
        val bpf = ((bitDepth + 7) / 8).coerceAtLeast(1) * channels.coerceIn(1, 8)
        val microframes = if (interval > 1) 1 shl (interval - 1).coerceAtMost(4) else 1
        val frames = ((rateHz + 7999) / 8000) * microframes + 1
        return frames * bpf
    }

    private fun knownClockIds(): List<Int> {
        val parsed = parsedClockSources.map { it.id }.filter { it > 0 }
        if (parsed.isNotEmpty()) return parsed.distinct()
        return parsedClockSourceIds.filter { it > 0 }.distinct()
    }

    private fun setSelectorPin(pin: Int): Boolean {
        val conn = connection ?: return false
        if (parsedClockSelectorId <= 0) return true
        val data = byteArrayOf(pin.toByte())
        val ret = conn.controlTransfer(
            0x21, 0x01, 0x0100, clockWIndex(parsedClockSelectorId), data, 1, 1000,
        )
        if (ret < 0) {
            Log.w(TAG, "CLOCK_SELECTOR pin=$pin failed ret=$ret")
            return false
        }
        Log.i(TAG, "CLOCK_SELECTOR pin=$pin ret=$ret")
        Thread.sleep(5)
        return true
    }

    private fun clockValidOrUnknown(csId: Int): Boolean {
        val conn = connection ?: return false
        val data = ByteArray(1)
        val ret = conn.controlTransfer(0xA1, 0x01, 0x0200, clockWIndex(csId), data, 1, 1000)
        if (ret < 1) return true
        return (data[0].toInt() and 0x01) == 1
    }

    private fun clockSupportsRate(csId: Int, hz: Int): Boolean? {
        val conn = connection ?: return null
        val data = ByteArray(2 + 12 * 16)
        val ret = conn.controlTransfer(0xA1, 0x02, 0x0100, clockWIndex(csId), data, data.size, 1000)
        if (ret < 2) return null
        val n = (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8)
        if (n <= 0 || n > 16 || ret < 2 + 12 * n) return null
        var o = 2
        repeat(n) {
            val min = le32(data, o)
            val max = le32(data, o + 4)
            val res = le32(data, o + 8).coerceAtLeast(1)
            o += 12
            if (hz in min..max && (hz - min) % res == 0) return true
        }
        return false
    }

    private fun le32(data: ByteArray, o: Int): Int {
        return (data[o].toInt() and 0xFF) or
            ((data[o + 1].toInt() and 0xFF) shl 8) or
            ((data[o + 2].toInt() and 0xFF) shl 16) or
            ((data[o + 3].toInt() and 0xFF) shl 24)
    }

    private fun tryLockClock(clockId: Int, sampleRateHz: Int, selectorPin: Int?): Boolean {
        val src = parsedClockSources.find { it.id == clockId }
        if (selectorPin != null && !setSelectorPin(selectorPin)) return false
        val supports = clockSupportsRate(clockId, sampleRateHz)
        if (supports == false) {
            Log.i(TAG, "clock=0x${clockId.toString(16)} GET_RANGE does not list $sampleRateHz Hz (still trying SET_CUR)")
        }
        if (src?.fixed != true) {
            if (!setCurSampleRate(clockId, sampleRateHz)) {
                Log.w(TAG, "SET_CUR $sampleRateHz clock=0x${clockId.toString(16)} failed")
            }
            Thread.sleep(12)
        }
        val got = readSampleRateFrom(clockId)
        if (got != sampleRateHz) {
            Log.w(TAG, "clock=0x${clockId.toString(16)} GET_CUR=$got wanted=$sampleRateHz")
            return false
        }
        if (!clockValidOrUnknown(clockId)) {
            Log.w(TAG, "clock=0x${clockId.toString(16)} CLOCK_VALID=0 at $sampleRateHz Hz")
            return false
        }
        activeClockId = clockId
        Log.i(
            TAG,
            "lockSampleRate($sampleRateHz Hz): clock=0x${clockId.toString(16)} pin=$selectorPin",
        )
        return true
    }

    /**
     * Pin the UAC2 clock path onto a source that actually reports [sampleRateHz].
     * Never SET_CUR onto guessed entity IDs (that used to hit the Clock Selector
     * / Feature Unit and leave 88.2/176.4/352.8 unlocked → analog static).
     */
    fun lockSampleRate(sampleRateHz: Int): Boolean {
        activeClockId = -1
        if (connection == null) return false
        val clocks = knownClockIds()
        if (clocks.isEmpty()) {
            Log.w(TAG, "lockSampleRate($sampleRateHz Hz): no CLOCK_SOURCE in descriptors")
            return false
        }

        val attempts = linkedSetOf<Pair<Int, Int?>>()
        if (parsedClockSelectorId > 0 && parsedSelectorPins.isNotEmpty()) {
            parsedSelectorPins.forEachIndexed { idx, clockId ->
                if (clockId > 0) attempts.add(clockId to (idx + 1))
            }
        } else {
            val path = pathClockId
            if (path > 0) attempts.add(path to null)
            clocks.forEach { id -> attempts.add(id to null) }
        }

        val ranked = attempts.sortedBy { (id, _) ->
            when (clockSupportsRate(id, sampleRateHz)) {
                true -> 0
                null -> 1
                false -> 2
            }
        }
        for ((clockId, pin) in ranked) {
            if (tryLockClock(clockId, sampleRateHz, pin)) return true
        }
        Log.w(TAG, "lockSampleRate($sampleRateHz Hz): no GET_CUR+VALID match")
        return false
    }

    fun setSampleRate(sampleRateHz: Int): Boolean = lockSampleRate(sampleRateHz)

    fun selectClockForSampleRate(sampleRateHz: Int): Boolean = lockSampleRate(sampleRateHz)

    /**
     * Read the current sample rate from the DAC via UAC2 GET_CUR.
     * This verifies whether our SET_CUR actually took effect.
     */
    fun readSampleRate(): Int {
        val preferred = listOfNotNull(activeClockId.takeIf { it > 0 }) + knownClockIds()
        for (csId in preferred.distinct()) {
            val rate = readSampleRateFrom(csId)
            if (rate > 0) {
                Log.i(TAG, "readSampleRate: GET_CUR clockSourceId=0x${csId.toString(16)} returned $rate Hz")
                return rate
            }
        }
        Log.w(TAG, "readSampleRate: all GET_CUR attempts failed")
        return -1
    }

    /**
     * Read the CLOCK_VALID control from the DAC via UAC2 GET_CUR.
     * This checks whether the Clock Source entity's clock is locked and stable
     * after a sample rate change. Standard practice per UAC2 spec: verify clock after SET_CUR before proceeding.
     *
     * UAC2 spec: Clock Source descriptor, CS = 0x02 (CUR_CLOCK_VALID_CONTROL)
     * Returns: true if clock is valid, false if not or on error.
     */
    fun readClockValid(): Boolean {
        val id = activeClockId
        if (id > 0) {
            val ok = clockValidOrUnknown(id)
            Log.i(TAG, "readClockValid: active clock=0x${id.toString(16)} valid=$ok")
            return ok
        }
        for (csId in knownClockIds()) {
            val data = ByteArray(1)
            val conn = connection ?: return false
            val ret = conn.controlTransfer(0xA1, 0x01, 0x0200, clockWIndex(csId), data, 1, 1000)
            if (ret >= 1) {
                val valid = data[0].toInt() and 0x01
                Log.i(TAG, "readClockValid: clockSourceId=0x${csId.toString(16)} valid=$valid")
                return valid == 1
            }
        }
        Log.w(TAG, "readClockValid: all GET_CUR attempts failed")
        return false
    }

    private fun setCurSampleRate(csId: Int, sampleRateHz: Int): Boolean {
        val conn = connection ?: return false
        val data = ByteArray(4)
        data[0] = (sampleRateHz and 0xFF).toByte()
        data[1] = ((sampleRateHz shr 8) and 0xFF).toByte()
        data[2] = ((sampleRateHz shr 16) and 0xFF).toByte()
        data[3] = ((sampleRateHz shr 24) and 0xFF).toByte()
        val ret = conn.controlTransfer(0x21, 0x01, 0x0100, clockWIndex(csId), data, data.size, 1000)
        return ret >= 0
    }

    private fun readSampleRateFrom(csId: Int): Int {
        val conn = connection ?: return -1
        val data = ByteArray(4)
        val wIndex = clockWIndex(csId)
        val ret = conn.controlTransfer(0xA1, 0x01, 0x0100, wIndex, data, data.size, 1000)
        val retUac1 = if (ret < 4) {
            conn.controlTransfer(0xA1, 0x81, 0x0100, wIndex, data, data.size, 1000)
        } else {
            ret
        }
        if (retUac1 < 4) return -1
        return (data[0].toInt() and 0xFF) or
            ((data[1].toInt() and 0xFF) shl 8) or
            ((data[2].toInt() and 0xFF) shl 16) or
            ((data[3].toInt() and 0xFF) shl 24)
    }

    /**
     * Set the alternate setting on the streaming interface via Java API.
     * This may properly allocate USB bandwidth, which the native ioctl might not.
     */
    fun setAltSetting(altSetting: Int): Boolean {
        val conn = connection ?: return false
        val device = currentDevice ?: return false

        // Find the UsbInterface with the matching alt setting
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == UsbConstants.USB_CLASS_AUDIO &&
                iface.interfaceSubclass == 2 &&
                iface.alternateSetting == altSetting) {
                val result = conn.setInterface(iface)
                Log.i(TAG, "setAltSetting($altSetting) via Java API: $result " +
                        "(iface id=${iface.id}, endpoints=${iface.endpointCount})")
                return result
            }
        }

        Log.w(TAG, "setAltSetting($altSetting): no matching UsbInterface found, " +
                "trying all AudioStreaming interfaces...")

        // Fallback: try any AudioStreaming interface with matching alt
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == UsbConstants.USB_CLASS_AUDIO &&
                iface.interfaceSubclass == 2) {
                Log.d(TAG, "  interface $i: id=${iface.id} alt=${iface.alternateSetting} " +
                        "endpoints=${iface.endpointCount}")
            }
        }

        return false
    }

}
