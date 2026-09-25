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
 *
 * @author DecentPlayer project
 */
class UsbAudioDevice private constructor(private val context: Context) {

    private var usbManager: UsbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var connection: UsbDeviceConnection? = null
    private var currentDevice: UsbDevice? = null
    private var claimedInterface: UsbInterface? = null

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

        // Auto-detect Clock Source ID, best alt setting, and UAC version from USB descriptors
        val uacVersion = parseUacVersion(conn)
        val clockSourceId = parseClockSourceId(conn)
        val (bestAlt, bestBits) = parseBestAltSetting(conn, uacVersion)
        val bestAltInterface = (0 until device.interfaceCount)
            .map { device.getInterface(it) }
            .firstOrNull {
                it.interfaceClass == UsbConstants.USB_CLASS_AUDIO &&
                    it.interfaceSubclass == 2 &&
                    it.alternateSetting == bestAlt
            }
        val bestAltOut = bestAltInterface?.let { iface ->
            (0 until iface.endpointCount).map { iface.getEndpoint(it) }
                .firstOrNull {
                    it.type == UsbConstants.USB_ENDPOINT_XFER_ISOC &&
                        it.direction == UsbConstants.USB_DIR_OUT
                }
        }
        val bestAltFeedback = bestAltInterface?.let { iface ->
            (0 until iface.endpointCount).map { iface.getEndpoint(it) }
                .firstOrNull {
                    it.type == UsbConstants.USB_ENDPOINT_XFER_ISOC &&
                        it.direction == UsbConstants.USB_DIR_IN
                }
        }
        if (bestAltOut != null) {
            endpointOut = bestAltOut.address
            maxPacketSize = bestAltOut.maxPacketSize
        }
        if (bestAltFeedback != null) endpointFeedback = bestAltFeedback.address
        val dataInterval = bestAltOut?.interval ?: 1
        val feedbackPacketSize = bestAltFeedback?.maxPacketSize ?: 4
        val feedbackInterval = bestAltFeedback?.interval ?: dataInterval
        Log.i(TAG, "Auto-detected: clockSourceId=0x${clockSourceId.toString(16)}, " +
                "bestAlt=$bestAlt, bestBits=$bestBits, uacVersion=$uacVersion, " +
                "epOut=0x${endpointOut.toString(16)}, maxPacket=$maxPacketSize, " +
                "dataInterval=$dataInterval, epFb=0x${endpointFeedback.toString(16)}, " +
                "fbPacket=$feedbackPacketSize, fbInterval=$feedbackInterval")

        val info = UsbAudioDeviceInfo(
                connection = conn,
                fd = fd,
                deviceName = device.productName ?: "USB Audio Device",
                interfaceId = interfaceId,
                endpointOutAddress = endpointOut,
                endpointFeedbackAddress = endpointFeedback,
                maxPacketSize = maxPacketSize,
                dataInterval = dataInterval,
                feedbackPacketSize = feedbackPacketSize,
                feedbackInterval = feedbackInterval,
                altSettingCount = altSettingCount,
                clockSourceId = clockSourceId,
                bestAltSetting = bestAlt,
                bestBitDepth = bestBits,
                uacVersion = uacVersion,
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
        // DO NOT close connection — the fd from reset+native claim must be reused
        // The next openDevice() will see connection != null and skip re-opening
    }

    /** All parsed CLOCK_SOURCE (0x0A) entity IDs in AudioControl order. */
    private var parsedClockSourceIds: List<Int> = emptyList()
    /** Optional CLOCK_SELECTOR (0x0B) entity ID and its 1-indexed input pin -> clockSourceId list. */
    private var parsedClockSelectorId: Int = -1
    private var parsedClockSelectorPins: List<Int> = emptyList()
    /** Active clock source ID last selected via setSampleRate(). */
    @Volatile private var activeClockSourceId: Int = -1
    /** Per-Clock-Source supported sample rates discovered via GET_RANGE. */
    private val clockSourceRateMap = mutableMapOf<Int, Set<Int>>()

    /**
     * Parse raw USB descriptors to find all UAC2 Clock Source entity IDs (0x0A)
     * and any Clock Selector entity (0x0B) for dual-oscillator (44.1k + 48k) DACs.
     *
     * @return Primary Clock Source entity ID, or -1 if not found.
     */
    private fun parseClockSourceId(conn: UsbDeviceConnection): Int {
        val raw = conn.rawDescriptors ?: return -1
        val sources = mutableListOf<Int>()
        var selectorId = -1
        val selectorPins = mutableListOf<Int>()

        var i = 0
        var inAudioControl = false

        while (i + 1 < raw.size) {
            val bLength = raw[i].toInt() and 0xFF
            if (bLength < 2) break
            if (i + bLength > raw.size) break

            val bDescriptorType = raw[i + 1].toInt() and 0xFF

            // Interface descriptor (0x04)
            if (bDescriptorType == 0x04 && bLength >= 9) {
                val bInterfaceClass = raw[i + 5].toInt() and 0xFF
                val bInterfaceSubClass = raw[i + 6].toInt() and 0xFF
                // AudioControl = class 1, subclass 1
                inAudioControl = (bInterfaceClass == 1 && bInterfaceSubClass == 1)
            }

            // CS_INTERFACE descriptor (0x24) inside AudioControl
            if (inAudioControl && bDescriptorType == 0x24 && bLength >= 4) {
                val bDescriptorSubtype = raw[i + 2].toInt() and 0xFF
                // CLOCK_SOURCE = 0x0A
                if (bDescriptorSubtype == 0x0A && bLength >= 5) {
                    val bClockID = raw[i + 3].toInt() and 0xFF
                    if (bClockID > 0 && bClockID !in sources) {
                        sources += bClockID
                        Log.i(TAG, "parseClockSourceId: found CLOCK_SOURCE bClockID=0x${bClockID.toString(16)}")
                    }
                } else if (bDescriptorSubtype == 0x0B && bLength >= 5 && selectorId < 0) {
                    // CLOCK_SELECTOR = 0x0B: [bLength, 0x24, 0x0B, bClockID, bNrInPins, baCSourceID(1..p)...]
                    selectorId = raw[i + 3].toInt() and 0xFF
                    val nrPins = raw[i + 4].toInt() and 0xFF
                    for (p in 0 until nrPins) {
                        val off = i + 5 + p
                        if (off < i + bLength) {
                            selectorPins += raw[off].toInt() and 0xFF
                        }
                    }
                    Log.i(TAG, "parseClockSourceId: found CLOCK_SELECTOR id=0x${selectorId.toString(16)} pins=$selectorPins")
                }
            }

            i += bLength
        }

        parsedClockSourceIds = sources
        parsedClockSelectorId = selectorId
        parsedClockSelectorPins = selectorPins
        val primary = sources.firstOrNull() ?: -1
        activeClockSourceId = primary
        if (primary < 0) {
            Log.w(TAG, "parseClockSourceId: no CLOCK_SOURCE descriptor found")
        }
        return primary
    }

    /**
     * Parse raw USB descriptors to find the best (highest bit depth) alt setting
     * for the AudioStreaming interface.
     *
     * Scans AS Format Type I descriptors (CS_INTERFACE 0x02) for bBitResolution
     * and returns the alt setting with the highest value.
     *
     * @return Pair(altSetting, bitDepth), or Pair(1, 16) as default.
     */
    /** Parsed alt setting: (altNumber, bitResolution) */
    private var parsedAltSettings: List<Pair<Int, Int>> = emptyList()

    private fun parseBestAltSetting(conn: UsbDeviceConnection, uacVersion: Int): Pair<Int, Int> {
        val raw = conn.rawDescriptors ?: return Pair(1, 16)
        val altSettings = mutableListOf<Pair<Int, Int>>()

        var i = 0
        var currentAlt = 0
        var inAudioStreaming = false
        var bestAlt = 1
        var bestBits = 16

        while (i + 1 < raw.size) {
            val bLength = raw[i].toInt() and 0xFF
            if (bLength < 2) break
            if (i + bLength > raw.size) break

            val bDescriptorType = raw[i + 1].toInt() and 0xFF

            // Interface descriptor (0x04)
            if (bDescriptorType == 0x04 && bLength >= 9) {
                val bInterfaceClass = raw[i + 5].toInt() and 0xFF
                val bInterfaceSubClass = raw[i + 6].toInt() and 0xFF
                val bAlternateSetting = raw[i + 3].toInt() and 0xFF
                inAudioStreaming = (bInterfaceClass == 1 && bInterfaceSubClass == 2)
                if (inAudioStreaming) currentAlt = bAlternateSetting
            }

            // CS_INTERFACE (0x24) in AudioStreaming — Format Type I (subtype 0x02)
            if (inAudioStreaming && bDescriptorType == 0x24 && bLength >= 6) {
                val bDescriptorSubtype = raw[i + 2].toInt() and 0xFF
                if (bDescriptorSubtype == 0x02) {
                    val bSubslotSize: Int
                    val bBitResolution: Int
                    if (uacVersion == 100) {
                        // UAC 1.0: bSubframeSize is at offset 5, bBitResolution at offset 6
                        bSubslotSize = if (bLength >= 7) raw[i + 5].toInt() and 0xFF else 2
                        bBitResolution = if (bLength >= 7) raw[i + 6].toInt() and 0xFF else 16
                    } else {
                        // UAC 2.0/3.0: bSubslotSize is at offset 4, bBitResolution at offset 5
                        bSubslotSize = raw[i + 4].toInt() and 0xFF
                        bBitResolution = raw[i + 5].toInt() and 0xFF
                    }
                    val containerBits = bSubslotSize * 8
                    Log.i(TAG, "parseBestAltSetting: alt=$currentAlt subslotSize=$bSubslotSize bitResolution=$bBitResolution containerBits=$containerBits")

                    if (currentAlt > 0) {
                        altSettings.add(Pair(currentAlt, containerBits))
                    }
                    if (containerBits > bestBits && currentAlt > 0) {
                        bestBits = containerBits
                        bestAlt = currentAlt
                    }
                }
            }

            i += bLength
        }

        parsedAltSettings = altSettings
        Log.i(TAG, "parseBestAltSetting: best alt=$bestAlt bits=$bestBits, all=$altSettings")
        return Pair(bestAlt, bestBits)
    }

    /**
     * Parse the USB Audio Class version from the AudioControl interface header descriptor.
     *
     * Reads the `bcdADC` field (bytes 3–4 of the AC Header CS_INTERFACE descriptor,
     * subtype 0x01) to distinguish UAC 1.0 (0x0100) from UAC 2.0 (0x0200).
     *
     * - UAC 1.0 → returns 100 (simple USB headsets, e.g. Apple EarPods USB-C)
     * - UAC 2.0 → returns 200 (audiophile DACs with Clock Source entities)
     * - Unknown  → returns 0
     *
     * This is used to gate UAC 2.0-only features (exclusive isochronous output,
     * SET_CUR clock control) away from simple headsets that do not implement them.
     */
    fun parseUacVersion(conn: UsbDeviceConnection): Int {
        val raw = conn.rawDescriptors ?: return 0
        var i = 0
        var inAudioControl = false

        while (i + 1 < raw.size) {
            val bLength = raw[i].toInt() and 0xFF
            if (bLength < 2) break
            if (i + bLength > raw.size) break

            val bDescriptorType = raw[i + 1].toInt() and 0xFF

            // Interface descriptor (type 0x04)
            if (bDescriptorType == 0x04 && bLength >= 9) {
                val bInterfaceClass    = raw[i + 5].toInt() and 0xFF
                val bInterfaceSubClass = raw[i + 6].toInt() and 0xFF
                inAudioControl = (bInterfaceClass == 1 && bInterfaceSubClass == 1)
            }

            // CS_INTERFACE (0x24) inside AudioControl — subtype 0x01 = AC Header
            if (inAudioControl && bDescriptorType == 0x24 && bLength >= 5) {
                val bDescriptorSubtype = raw[i + 2].toInt() and 0xFF
                if (bDescriptorSubtype == 0x01) {
                    // bcdADC: bytes at offset 3 (LSB) and 4 (MSB)
                    val bcdLo = raw[i + 3].toInt() and 0xFF
                    val bcdHi = raw[i + 4].toInt() and 0xFF
                    val bcd = (bcdHi shl 8) or bcdLo
                    val version = when (bcd) {
                        0x0100 -> 100  // UAC 1.0
                        0x0200 -> 200  // UAC 2.0
                        0x0300 -> 300  // UAC 3.0 (rare)
                        else   -> 0
                    }
                    Log.i(TAG, "parseUacVersion: bcdADC=0x${bcd.toString(16).padStart(4,'0')} -> UAC $version")
                    return version
                }
            }

            i += bLength
        }
        Log.w(TAG, "parseUacVersion: no AC Header descriptor found, returning 0")
        return 0
    }

    /**
     * Find the alt setting that matches the given source bit depth exactly.
     * If no exact match, returns the next higher bit depth.
     * Fallback: returns the best (highest) alt setting.
     *
     * @return Pair(altSetting, bitDepth)
     */
    fun findAltSettingForBitDepth(targetBitDepth: Int): Pair<Int, Int> {
        if (parsedAltSettings.isEmpty()) {
            val info = cachedDeviceInfo ?: return Pair(1, 16)
            return Pair(info.bestAltSetting, info.bestBitDepth)
        }

        // Exact match
        val exact = parsedAltSettings.firstOrNull { it.second == targetBitDepth }
        if (exact != null) {
            Log.i(TAG, "findAltSettingForBitDepth($targetBitDepth): exact match alt=${exact.first}")
            return exact
        }

        // Next higher
        val higher = parsedAltSettings
                .filter { it.second > targetBitDepth }
                .minByOrNull { it.second }
        if (higher != null) {
            Log.i(TAG, "findAltSettingForBitDepth($targetBitDepth): next higher alt=${higher.first} bits=${higher.second}")
            return higher
        }

        // Fallback to best
        val best = parsedAltSettings.maxByOrNull { it.second } ?: Pair(1, 16)
        Log.i(TAG, "findAltSettingForBitDepth($targetBitDepth): fallback to best alt=${best.first} bits=${best.second}")
        return best
    }

    private var cachedSupportedRates: List<Int> = emptyList()

    /**
     * Close the USB device and release all resources.
     */
    fun closeDevice() {
        cachedDeviceInfo = null
        cachedSupportedRates = emptyList()
        clockSourceRateMap.clear()
        parsedClockSourceIds = emptyList()
        parsedClockSelectorId = -1
        parsedClockSelectorPins = emptyList()
        activeClockSourceId = -1
        claimedInterface?.let { iface ->
            connection?.releaseInterface(iface)
            claimedInterface = null
        }
        connection?.close()
        connection = null
        currentDevice = null
        Log.i(TAG, "USB device closed")
    }

    /**
     * Queries all hardware-clock sample rates supported by the connected USB DAC.
     * Combines UAC 2.0 Clock Source GET_RANGE (CS_SAM_FREQ_CONTROL = 0x01) across
     * all Clock Source entities (supporting dual-crystal 44.1k+48k DACs) and
     * UAC 1.0 AudioStreaming Format Type I tSamFreq descriptor tables.
     */
    fun querySupportedSampleRates(): List<Int> {
        if (cachedSupportedRates.isNotEmpty()) return cachedSupportedRates
        val conn = connection ?: return emptyList()
        val rates = linkedSetOf<Int>()

        // 1. Probe UAC 2.0 Clock Source GET_RANGE (bRequest = 0x02, wValue = 0x0100)
        val detectedId = cachedDeviceInfo?.clockSourceId ?: -1
        val candidateIds = buildList {
            addAll(parsedClockSourceIds)
            if (detectedId > 0) add(detectedId)
            addAll(listOf(0x05, 0x09, 0x0A, 0x0B, 0x0C, 0x28, 0x29, 0x06, 0x07, 0x08, 0x10, 0x20))
        }.distinct()
        val standardRates = intArrayOf(
            44100, 48000, 88200, 96000, 176400, 192000, 352800, 384000, 705600, 768000
        )
        val rangeBuf = ByteArray(258)
        val stopOnFirstHit = parsedClockSourceIds.size <= 1
        for (csId in candidateIds) {
            val wIndex = (csId shl 8) or 0
            val ret = conn.controlTransfer(
                0xA1,
                0x02,   // GET_RANGE
                0x0100, // CS_SAM_FREQ_CONTROL
                wIndex,
                rangeBuf,
                rangeBuf.size,
                500,
            )
            if (ret >= 14) {
                val csRates = linkedSetOf<Int>()
                val count = (rangeBuf[0].toInt() and 0xFF) or ((rangeBuf[1].toInt() and 0xFF) shl 8)
                val maxEntries = ((ret - 2) / 12).coerceAtMost(count)
                for (idx in 0 until maxEntries) {
                    val base = 2 + idx * 12
                    val dMin = readLeInt32(rangeBuf, base)
                    val dMax = readLeInt32(rangeBuf, base + 4)
                    val dRes = readLeInt32(rangeBuf, base + 8)
                    if (dMin in 8000..1536000 && dMax >= dMin) {
                        if (dMin == dMax || dRes <= 0) {
                            csRates += dMin
                        } else {
                            for (std in standardRates) {
                                if (std in dMin..dMax && ((std - dMin) % dRes == 0)) {
                                    csRates += std
                                }
                            }
                        }
                    }
                }
                if (csRates.isNotEmpty()) {
                    clockSourceRateMap[csId] = csRates
                    rates.addAll(csRates)
                    if (stopOnFirstHit && csId !in parsedClockSourceIds) break
                }
            }
        }

        // 2. Parse UAC 1.0 Format Type I discrete/continuous tSamFreq from raw USB descriptors
        val raw = conn.rawDescriptors
        if (raw != null) {
            var i = 0
            var inAudioStreaming = false
            while (i + 1 < raw.size) {
                val bLength = raw[i].toInt() and 0xFF
                if (bLength < 2 || i + bLength > raw.size) break
                val bDescriptorType = raw[i + 1].toInt() and 0xFF
                if (bDescriptorType == 0x04 && bLength >= 9) {
                    val cls = raw[i + 5].toInt() and 0xFF
                    val sub = raw[i + 6].toInt() and 0xFF
                    inAudioStreaming = (cls == 1 && sub == 2)
                } else if (inAudioStreaming && bDescriptorType == 0x24 && bLength >= 8) {
                    val subtype = raw[i + 2].toInt() and 0xFF
                    if (subtype == 0x02) { // FORMAT_TYPE
                        val samFreqType = raw[i + 7].toInt() and 0xFF
                        if (samFreqType == 0 && bLength >= 14) {
                            val lower = readLeInt24(raw, i + 8)
                            val upper = readLeInt24(raw, i + 11)
                            for (std in standardRates) {
                                if (std in lower..upper) rates += std
                            }
                        } else if (samFreqType > 0) {
                            for (k in 0 until samFreqType) {
                                val off = i + 8 + k * 3
                                if (off + 2 < i + bLength) {
                                    val freq = readLeInt24(raw, off)
                                    if (freq in 8000..1536000) rates += freq
                                }
                            }
                        }
                    }
                }
                i += bLength
            }
        }

        val sorted = rates.sorted()
        if (sorted.isNotEmpty()) {
            cachedSupportedRates = sorted
            Log.i(TAG, "querySupportedSampleRates: DAC hardware clock rates=$sorted (perClock=$clockSourceRateMap)")
        }
        return sorted
    }

    private fun readLeInt24(buf: ByteArray, offset: Int): Int =
        (buf[offset].toInt() and 0xFF) or
            ((buf[offset + 1].toInt() and 0xFF) shl 8) or
            ((buf[offset + 2].toInt() and 0xFF) shl 16)

    private fun readLeInt32(buf: ByteArray, offset: Int): Int =
        (buf[offset].toInt() and 0xFF) or
            ((buf[offset + 1].toInt() and 0xFF) shl 8) or
            ((buf[offset + 2].toInt() and 0xFF) shl 16) or
            ((buf[offset + 3].toInt() and 0xFF) shl 24)

    private fun selectClockSelectorPinIfNeeded(conn: UsbDeviceConnection, targetClockSourceId: Int) {
        if (parsedClockSelectorId <= 0 || parsedClockSelectorPins.isEmpty()) return
        val pinZeroIndex = parsedClockSelectorPins.indexOf(targetClockSourceId)
        if (pinZeroIndex < 0) return
        val pinNumber = (pinZeroIndex + 1).toByte() // UAC2 Clock Selector pins are 1-based
        val pinData = byteArrayOf(pinNumber)
        val wIndex = (parsedClockSelectorId shl 8) or 0
        val ret = conn.controlTransfer(
            0x21,
            0x01,   // SET_CUR
            0x0100, // CS_CLOCK_SELECTOR_CONTROL
            wIndex,
            pinData,
            pinData.size,
            500,
        )
        Log.i(TAG, "selectClockSelectorPin: selector=0x${parsedClockSelectorId.toString(16)} -> pin=$pinNumber (csId=0x${targetClockSourceId.toString(16)}, ret=$ret)")
    }

    /**
     * Set the sample rate on a UAC2 Clock Source entity via SET_CUR control transfer,
     * with Clock Selector switching for dual-oscillator DACs and UAC1 Endpoint fallback.
     */
    fun setSampleRate(sampleRateHz: Int): Boolean {
        val conn = connection ?: return false

        val data = ByteArray(4)
        data[0] = (sampleRateHz and 0xFF).toByte()
        data[1] = ((sampleRateHz shr 8) and 0xFF).toByte()
        data[2] = ((sampleRateHz shr 16) and 0xFF).toByte()
        data[3] = ((sampleRateHz shr 24) and 0xFF).toByte()

        // Prioritize the Clock Source entity whose GET_RANGE explicitly includes sampleRateHz
        // (critical for dual-oscillator DACs with separate 44.1kHz and 48kHz crystals).
        val matchingClockSources = clockSourceRateMap.entries
            .filter { sampleRateHz in it.value }
            .map { it.key }
        val detectedId = cachedDeviceInfo?.clockSourceId ?: -1
        val clockSourceIds = buildList {
            addAll(matchingClockSources)
            addAll(parsedClockSourceIds)
            if (detectedId > 0) add(detectedId)
            if (isEmpty()) {
                addAll(
                    listOf(
                        0x05, 0x09, 0x0A, 0x0B, 0x0C, 0x0D,
                        0x28, 0x29, 0x2A, 0x06, 0x07, 0x08,
                        0x10, 0x11, 0x12, 0x20, 0x21, 0x22,
                    )
                )
            }
        }.distinct()

        for (csId in clockSourceIds) {
            selectClockSelectorPinIfNeeded(conn, csId)
            val wIndex = (csId shl 8) or 0  // entityId << 8 | audioControlInterface(0)
            val ret = conn.controlTransfer(
                    0x21,    // bmRequestType: Host-to-Device, Class, Interface
                    0x01,    // bRequest: SET_CUR
                    0x0100,  // wValue: CS_SAM_FREQ_CONTROL
                    wIndex,
                    data,
                    data.size,
                    1000     // timeout ms
            )
            if (ret >= 0) {
                activeClockSourceId = csId
                Log.i(TAG, "setSampleRate($sampleRateHz Hz): SUCCESS with clockSourceId=0x${csId.toString(16)} (wIndex=0x${wIndex.toString(16)}, ret=$ret)")
                return true
            }
        }

        // UAC 1.0 fallback: Endpoint SAMPLING_FREQ_CONTROL (bmRequestType=0x22, bRequest=0x01, wValue=0x0100)
        val epOut = cachedDeviceInfo?.endpointOutAddress ?: -1
        if (epOut > 0) {
            val uac1Data = byteArrayOf(
                (sampleRateHz and 0xFF).toByte(),
                ((sampleRateHz shr 8) and 0xFF).toByte(),
                ((sampleRateHz shr 16) and 0xFF).toByte(),
            )
            val ret = conn.controlTransfer(
                0x22,
                0x01,
                0x0100,
                epOut,
                uac1Data,
                uac1Data.size,
                1000,
            )
            if (ret >= 0) {
                Log.i(TAG, "setSampleRate($sampleRateHz Hz): SUCCESS via UAC1 endpoint 0x${epOut.toString(16)}")
                return true
            }
        }

        Log.w(TAG, "setSampleRate($sampleRateHz Hz): all clock source IDs failed, DAC may auto-detect")
        return false
    }

    /**
     * Read the current sample rate from the DAC via UAC2 GET_CUR (or UAC1 Endpoint GET_CUR).
     * This verifies whether our SET_CUR actually took effect.
     */
    fun readSampleRate(): Int {
        val conn = connection ?: return -1
        val data = ByteArray(4)

        val detectedId = cachedDeviceInfo?.clockSourceId ?: -1
        val clockSourceIds = buildList {
            if (activeClockSourceId > 0) add(activeClockSourceId)
            addAll(parsedClockSourceIds)
            if (detectedId > 0) add(detectedId)
            if (isEmpty()) addAll(listOf(0x05, 0x09, 0x0A, 0x0B, 0x0C, 0x28, 0x29))
        }.distinct()
        for (csId in clockSourceIds) {
            val wIndex = (csId shl 8) or 0
            val ret = conn.controlTransfer(
                    0xA1,    // bmRequestType: Device-to-Host, Class, Interface
                    0x01,    // bRequest: GET_CUR (actually CUR is 0x01 for both)
                    0x0100,  // wValue: CS_SAM_FREQ_CONTROL
                    wIndex,
                    data,
                    data.size,
                    1000
            )
            if (ret >= 4) {
                val rate = (data[0].toInt() and 0xFF) or
                        ((data[1].toInt() and 0xFF) shl 8) or
                        ((data[2].toInt() and 0xFF) shl 16) or
                        ((data[3].toInt() and 0xFF) shl 24)
                Log.i(TAG, "readSampleRate: GET_CUR clockSourceId=0x${csId.toString(16)} " +
                        "returned $rate Hz (raw=${data.joinToString(",") { "0x${(it.toInt() and 0xFF).toString(16)}" }})")
                return rate
            }
        }

        val epOut = cachedDeviceInfo?.endpointOutAddress ?: -1
        if (epOut > 0) {
            val uac1Data = ByteArray(3)
            val ret = conn.controlTransfer(
                0xA2,
                0x81, // UAC1 GET_CUR
                0x0100,
                epOut,
                uac1Data,
                uac1Data.size,
                1000,
            )
            if (ret >= 3) {
                val rate = readLeInt24(uac1Data, 0)
                if (rate in 8000..1536000) {
                    Log.i(TAG, "readSampleRate: UAC1 endpoint 0x${epOut.toString(16)} returned $rate Hz")
                    return rate
                }
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
        val conn = connection ?: return false
        val data = ByteArray(1)

        val detectedId = cachedDeviceInfo?.clockSourceId ?: -1
        val clockSourceIds = if (detectedId > 0) intArrayOf(detectedId)
                else intArrayOf(0x05, 0x09, 0x0A, 0x0B, 0x0C, 0x28, 0x29)
        for (csId in clockSourceIds) {
            val wIndex = (csId shl 8) or 0
            val ret = conn.controlTransfer(
                    0xA1,    // bmRequestType: Device-to-Host, Class, Interface
                    0x01,    // bRequest: GET_CUR
                    0x0200,  // wValue: CS=0x02 (CLOCK_VALID_CONTROL), CN=0x00
                    wIndex,
                    data,
                    data.size,
                    1000
            )
            if (ret >= 1) {
                val valid = data[0].toInt() and 0x01
                Log.i(TAG, "readClockValid: clockSourceId=0x${csId.toString(16)} valid=$valid")
                return valid == 1
            }
        }
        Log.w(TAG, "readClockValid: all GET_CUR attempts failed")
        return false
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
