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
    private var controlInterfaceId: Int = 0

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

        // Find the AudioStreaming playback interface with an isochronous OUT endpoint
        var streamingInterface: UsbInterface? = null
        var endpointOut = -1
        var endpointFeedback = -1
        var maxPacketSize = 0
        var altSettingCount = 0

        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == UsbConstants.USB_CLASS_AUDIO &&
                iface.interfaceSubclass == 2 &&
                iface.endpointCount > 0) {
                var foundOut = false
                var foundOutAddr = -1
                var foundMaxPacket = 0
                var foundFbAddr = -1
                for (e in 0 until iface.endpointCount) {
                    val ep = iface.getEndpoint(e)
                    if (ep.type == UsbConstants.USB_ENDPOINT_XFER_ISOC) {
                        if (ep.direction == UsbConstants.USB_DIR_OUT) {
                            foundOut = true
                            foundOutAddr = ep.address
                            foundMaxPacket = ep.maxPacketSize
                        } else if (ep.direction == UsbConstants.USB_DIR_IN) {
                            foundFbAddr = ep.address
                        }
                    }
                }
                if (foundOut) {
                    streamingInterface = iface
                    endpointOut = foundOutAddr
                    maxPacketSize = foundMaxPacket
                    if (foundFbAddr > 0) endpointFeedback = foundFbAddr
                    Log.i(TAG, "Selected AudioStreaming output interface ${iface.id} (alt=${iface.alternateSetting}): epOut=0x${endpointOut.toString(16)}, epFb=0x${endpointFeedback.toString(16)}")
                    break
                }
            }
        }

        if (streamingInterface == null || endpointOut < 0) {
            Log.e(TAG, "No suitable AudioStreaming playback interface/endpoint found")
            conn.close()
            return null
        }

        // Count alternate settings specifically for the playback streaming interface
        val targetStreamId = streamingInterface.id
        altSettingCount = (0 until device.interfaceCount).count {
            val iface = device.getInterface(it)
            iface.id == targetStreamId &&
                iface.interfaceClass == UsbConstants.USB_CLASS_AUDIO &&
                iface.interfaceSubclass == 2
        }

        // Claim the AudioControl interface with force=true to disconnect kernel driver
        val controlInterface = (0 until device.interfaceCount)
                .map { device.getInterface(it) }
                .firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_AUDIO && it.interfaceSubclass == 1 }

        if (controlInterface != null) {
            controlInterfaceId = controlInterface.id
            val claimed = conn.claimInterface(controlInterface, true)
            Log.i(TAG, "Claimed AudioControl interface ${controlInterface.id} force=true: $claimed")
        }

        // Claim the AudioStreaming interface with force=true to disconnect kernel driver (snd-usb-audio)
        val claimed = conn.claimInterface(streamingInterface, true)
        Log.i(TAG, "Claimed AudioStreaming interface ${streamingInterface.id} force=true: $claimed " +
                "(alt=${streamingInterface.alternateSetting}, endpoints=${streamingInterface.endpointCount})")
        if (!claimed) {
            Log.e(TAG, "Failed to claim streaming interface — kernel driver may still be active")
            conn.close()
            return null
        }
        claimedInterface = streamingInterface

        // Force alt=0 on the playback streaming interface to stop any streaming left by kernel driver
        val zeroAlt = (0 until device.interfaceCount)
                .map { device.getInterface(it) }
                .firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_AUDIO &&
                        it.interfaceSubclass == 2 &&
                        it.id == streamingInterface.id &&
                        it.alternateSetting == 0 } ?: (0 until device.interfaceCount)
                .map { device.getInterface(it) }
                .firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_AUDIO &&
                        it.interfaceSubclass == 2 && it.alternateSetting == 0 }
        if (zeroAlt != null) {
            conn.setInterface(zeroAlt)
            Log.i(TAG, "Reset streaming interface ${zeroAlt.id} to alt=0 (zero-bandwidth)")
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
                    it.id == interfaceId &&
                    it.alternateSetting == bestAlt
            } ?: (0 until device.interfaceCount)
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

    data class DetailedAltSetting(
        val interfaceId: Int,
        val altSetting: Int,
        val bitDepth: Int,
        val channels: Int,
        val maxPacketSize: Int,
        val interval: Int,
        val endpointOut: Int,
    )
    private var detailedAltSettings: List<DetailedAltSetting> = emptyList()

    /**
     * Parse raw USB descriptors to find all UAC2 Clock Source entity IDs (0x0A)
     * and any Clock Selector entity (0x0B) for dual-oscillator (44.1k + 48k) DACs.
     * Also traces AudioStreaming AS_GENERAL bTerminalLink -> Terminal -> bCSourceID
     * topology to identify the specific Clock entity driving the output stream.
     *
     * @return Primary Clock Source entity ID, or -1 if not found.
     */
    private fun parseClockSourceId(conn: UsbDeviceConnection): Int {
        val raw = conn.rawDescriptors ?: return -1
        val sources = mutableListOf<Int>()
        var selectorId = -1
        val selectorPins = mutableListOf<Int>()
        val selectorPinsMap = mutableMapOf<Int, List<Int>>()
        val multiplierSourceMap = mutableMapOf<Int, Int>()
        val terminalClockMap = mutableMapOf<Int, Int>()
        val streamingTerminalLinks = mutableListOf<Int>()

        var i = 0
        var inAudioControl = false
        var inAudioStreaming = false

        while (i + 1 < raw.size) {
            val bLength = raw[i].toInt() and 0xFF
            if (bLength < 2) break
            if (i + bLength > raw.size) break

            val bDescriptorType = raw[i + 1].toInt() and 0xFF

            // Interface descriptor (0x04)
            if (bDescriptorType == 0x04 && bLength >= 9) {
                val bInterfaceClass = raw[i + 5].toInt() and 0xFF
                val bInterfaceSubClass = raw[i + 6].toInt() and 0xFF
                // AudioControl = class 1, subclass 1; AudioStreaming = class 1, subclass 2
                inAudioControl = (bInterfaceClass == 1 && bInterfaceSubClass == 1)
                inAudioStreaming = (bInterfaceClass == 1 && bInterfaceSubClass == 2)
            }

            // CS_INTERFACE descriptor (0x24) inside AudioControl
            if (inAudioControl && bDescriptorType == 0x24 && bLength >= 4) {
                val bDescriptorSubtype = raw[i + 2].toInt() and 0xFF
                when (bDescriptorSubtype) {
                    0x02 -> {
                        // INPUT_TERMINAL: [bLength, 0x24, 0x02, bTerminalID, wTerminalType(2B), bAssocTerminal, bCSourceID...]
                        if (bLength >= 8) {
                            val termId = raw[i + 3].toInt() and 0xFF
                            val cSourceId = raw[i + 7].toInt() and 0xFF
                            if (cSourceId > 0) terminalClockMap[termId] = cSourceId
                            Log.i(TAG, "parseClockSourceId: InputTerminal id=0x${termId.toString(16)} -> cSource=0x${cSourceId.toString(16)}")
                        }
                    }
                    0x03 -> {
                        // OUTPUT_TERMINAL: [bLength, 0x24, 0x03, bTerminalID, wTerminalType(2B), bAssocTerminal, bSourceID, bCSourceID...]
                        if (bLength >= 9) {
                            val termId = raw[i + 3].toInt() and 0xFF
                            val cSourceId = raw[i + 8].toInt() and 0xFF
                            if (cSourceId > 0) terminalClockMap[termId] = cSourceId
                            Log.i(TAG, "parseClockSourceId: OutputTerminal id=0x${termId.toString(16)} -> cSource=0x${cSourceId.toString(16)}")
                        }
                    }
                    0x0A -> {
                        // CLOCK_SOURCE: [bLength, 0x24, 0x0A, bClockID, ...]
                        if (bLength >= 5) {
                            val bClockID = raw[i + 3].toInt() and 0xFF
                            if (bClockID > 0 && bClockID !in sources) {
                                sources += bClockID
                                Log.i(TAG, "parseClockSourceId: found CLOCK_SOURCE bClockID=0x${bClockID.toString(16)}")
                            }
                        }
                    }
                    0x0B -> {
                        // CLOCK_SELECTOR: [bLength, 0x24, 0x0B, bClockID, bNrInPins, baCSourceID(1..p)...]
                        if (bLength >= 5) {
                            val selId = raw[i + 3].toInt() and 0xFF
                            val nrPins = raw[i + 4].toInt() and 0xFF
                            val pins = mutableListOf<Int>()
                            for (p in 0 until nrPins) {
                                val off = i + 5 + p
                                if (off < i + bLength) {
                                    pins += raw[off].toInt() and 0xFF
                                }
                            }
                            selectorPinsMap[selId] = pins
                            if (selectorId < 0) {
                                selectorId = selId
                                selectorPins.addAll(pins)
                            }
                            Log.i(TAG, "parseClockSourceId: found CLOCK_SELECTOR id=0x${selId.toString(16)} pins=$pins")
                        }
                    }
                    0x0C -> {
                        // CLOCK_MULTIPLIER: [bLength, 0x24, 0x0C, bClockID, bCSourceID, ...]
                        if (bLength >= 5) {
                            val multId = raw[i + 3].toInt() and 0xFF
                            val cSourceId = raw[i + 4].toInt() and 0xFF
                            multiplierSourceMap[multId] = cSourceId
                            Log.i(TAG, "parseClockSourceId: found CLOCK_MULTIPLIER id=0x${multId.toString(16)} -> parent=0x${cSourceId.toString(16)}")
                        }
                    }
                }
            }

            // CS_INTERFACE descriptor (0x24) inside AudioStreaming
            if (inAudioStreaming && bDescriptorType == 0x24 && bLength >= 4) {
                val bDescriptorSubtype = raw[i + 2].toInt() and 0xFF
                // AS_GENERAL = 0x01: offset 3 is bTerminalLink
                if (bDescriptorSubtype == 0x01) {
                    val terminalLink = raw[i + 3].toInt() and 0xFF
                    if (terminalLink > 0 && terminalLink !in streamingTerminalLinks) {
                        streamingTerminalLinks += terminalLink
                        Log.i(TAG, "parseClockSourceId: found AS_GENERAL bTerminalLink=0x${terminalLink.toString(16)}")
                    }
                }
            }

            i += bLength
        }

        // Trace topology from streamingTerminalLinks -> Terminal -> Clock Source/Selector/Multiplier
        val topologyResolvedClockSources = mutableListOf<Int>()
        for (termLink in streamingTerminalLinks) {
            var clkEntity = terminalClockMap[termLink]
            if (clkEntity != null && multiplierSourceMap.containsKey(clkEntity)) {
                clkEntity = multiplierSourceMap[clkEntity]
            }
            if (clkEntity != null) {
                if (selectorPinsMap.containsKey(clkEntity)) {
                    selectorId = clkEntity
                    selectorPins.clear()
                    selectorPins.addAll(selectorPinsMap[clkEntity] ?: emptyList())
                    for (pin in selectorPins) {
                        if (pin !in topologyResolvedClockSources) topologyResolvedClockSources += pin
                    }
                    Log.i(TAG, "parseClockSourceId: resolved terminalLink 0x${termLink.toString(16)} to CLOCK_SELECTOR 0x${clkEntity.toString(16)} with pins $selectorPins")
                } else if (clkEntity in sources) {
                    if (clkEntity !in topologyResolvedClockSources) topologyResolvedClockSources += clkEntity
                    Log.i(TAG, "parseClockSourceId: resolved terminalLink 0x${termLink.toString(16)} directly to CLOCK_SOURCE 0x${clkEntity.toString(16)}")
                }
            }
        }

        val combinedSources = (topologyResolvedClockSources + sources).distinct()

        parsedClockSourceIds = combinedSources
        parsedClockSelectorId = selectorId
        parsedClockSelectorPins = selectorPins
        val primary = combinedSources.firstOrNull() ?: -1
        activeClockSourceId = primary
        if (primary < 0) {
            Log.w(TAG, "parseClockSourceId: no CLOCK_SOURCE descriptor found")
        } else {
            Log.i(TAG, "parseClockSourceId: primary=0x${primary.toString(16)}, all=0x${combinedSources.map { it.toString(16) }}")
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
        val detailed = mutableListOf<DetailedAltSetting>()

        var i = 0
        var currentIfaceId = -1
        var currentAlt = 0
        var inAudioStreaming = false
        var currentChannels = 2
        var currentBitRes = 16
        var currentEpOut = -1
        var currentMaxPacket = 0
        var currentInterval = 1
        var formatFound = false
        var bestAlt = 1
        var bestBits = 16

        fun saveCurrentAlt() {
            if (formatFound && currentAlt > 0) {
                detailed.add(
                    DetailedAltSetting(
                        interfaceId = currentIfaceId,
                        altSetting = currentAlt,
                        bitDepth = currentBitRes,
                        channels = currentChannels,
                        maxPacketSize = currentMaxPacket,
                        interval = currentInterval,
                        endpointOut = currentEpOut,
                    )
                )
            }
        }

        while (i + 1 < raw.size) {
            val bLength = raw[i].toInt() and 0xFF
            if (bLength < 2) break
            if (i + bLength > raw.size) break

            val bDescriptorType = raw[i + 1].toInt() and 0xFF

            // Interface descriptor (0x04)
            if (bDescriptorType == 0x04 && bLength >= 9) {
                saveCurrentAlt()
                val bInterfaceNumber = raw[i + 2].toInt() and 0xFF
                val bAlternateSetting = raw[i + 3].toInt() and 0xFF
                val bInterfaceClass = raw[i + 5].toInt() and 0xFF
                val bInterfaceSubClass = raw[i + 6].toInt() and 0xFF
                inAudioStreaming = (bInterfaceClass == 1 && bInterfaceSubClass == 2)
                currentIfaceId = bInterfaceNumber
                currentAlt = bAlternateSetting
                currentChannels = 2
                currentBitRes = 16
                currentEpOut = -1
                currentMaxPacket = 0
                currentInterval = 1
                formatFound = false
            }

            // CS_INTERFACE (0x24) in AudioStreaming
            if (inAudioStreaming && bDescriptorType == 0x24 && bLength >= 4) {
                val bDescriptorSubtype = raw[i + 2].toInt() and 0xFF
                if (bDescriptorSubtype == 0x01 && bLength >= 11) {
                    // AS_GENERAL: UAC2 has bNrChannels at offset 10
                    val ch = raw[i + 10].toInt() and 0xFF
                    if (ch > 0) currentChannels = ch
                } else if (bDescriptorSubtype == 0x02 && bLength >= 6) {
                    // Format Type I
                    val bSubslotSize: Int
                    val bBitResolution: Int
                    if (uacVersion == 100) {
                        // UAC 1.0: bNrChannels at offset 4, bSubframeSize at offset 5, bBitResolution at offset 6
                        if (bLength >= 5) currentChannels = raw[i + 4].toInt() and 0xFF
                        bSubslotSize = if (bLength >= 7) raw[i + 5].toInt() and 0xFF else 2
                        bBitResolution = if (bLength >= 7) raw[i + 6].toInt() and 0xFF else 16
                    } else {
                        // UAC 2.0/3.0: bSubslotSize is at offset 4, bBitResolution at offset 5
                        bSubslotSize = raw[i + 4].toInt() and 0xFF
                        bBitResolution = raw[i + 5].toInt() and 0xFF
                    }
                    val containerBits = bSubslotSize * 8
                    currentBitRes = containerBits
                    formatFound = true
                    Log.i(TAG, "parseBestAltSetting: iface=$currentIfaceId alt=$currentAlt subslotSize=$bSubslotSize bitResolution=$bBitResolution containerBits=$containerBits ch=$currentChannels")

                    if (currentAlt > 0) {
                        altSettings.add(Pair(currentAlt, containerBits))
                    }
                    if (containerBits > bestBits && currentAlt > 0) {
                        bestBits = containerBits
                        bestAlt = currentAlt
                    }
                }
            }

            // Endpoint descriptor (0x05)
            if (inAudioStreaming && bDescriptorType == 0x05 && bLength >= 7) {
                val epAddr = raw[i + 2].toInt() and 0xFF
                val epAttr = raw[i + 3].toInt() and 0xFF
                val rawPacket = (raw[i + 4].toInt() and 0xFF) or ((raw[i + 5].toInt() and 0xFF) shl 8)
                val interval = raw[i + 6].toInt() and 0xFF
                if ((epAttr and 0x03) == 0x01 && (epAddr and 0x80) == 0) {
                    // Isochronous OUT endpoint
                    currentEpOut = epAddr
                    val extra = (rawPacket shr 11) and 0x3
                    val size = rawPacket and 0x7FF
                    currentMaxPacket = if (extra > 0) size * (1 + extra) else rawPacket
                    currentInterval = interval.coerceAtLeast(1)
                }
            }

            i += bLength
        }
        saveCurrentAlt()

        detailedAltSettings = detailed
        parsedAltSettings = altSettings
        Log.i(TAG, "parseBestAltSetting: best alt=$bestAlt bits=$bestBits, all=$altSettings, detailedCount=${detailed.size}")
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
     * Find the alt setting that matches the given source bit depth, sample rate,
     * and channel count with sufficient endpoint bandwidth.
     * If no exact match, returns the next higher bit depth.
     * Fallback: returns the best (highest) alt setting.
     *
     * @return Pair(altSetting, bitDepth)
     */
    fun findAltSettingForBitDepth(
        targetBitDepth: Int,
        sampleRate: Int = 0,
        channelCount: Int = 2,
    ): Pair<Int, Int> {
        val targetIfaceId = cachedDeviceInfo?.interfaceId ?: -1
        if (detailedAltSettings.isNotEmpty()) {
            val candidates = detailedAltSettings.filter { alt ->
                (targetIfaceId < 0 || alt.interfaceId == targetIfaceId) &&
                    alt.altSetting > 0 &&
                    (alt.endpointOut > 0 || alt.maxPacketSize > 0) &&
                    (channelCount <= 0 || alt.channels <= 0 || alt.channels == channelCount) &&
                    (sampleRate <= 0 || alt.maxPacketSize <= 0 || isPacketSizeSufficient(alt.maxPacketSize, sampleRate, alt.channels.coerceAtLeast(1), alt.bitDepth, alt.interval))
            }

            // Exact match
            val exact = candidates.firstOrNull { it.bitDepth == targetBitDepth }
            if (exact != null) {
                Log.i(TAG, "findAltSettingForBitDepth($targetBitDepth, rate=$sampleRate, ch=$channelCount): exact match alt=${exact.altSetting} bits=${exact.bitDepth}")
                return Pair(exact.altSetting, exact.bitDepth)
            }

            // Next higher
            val higher = candidates.filter { it.bitDepth > targetBitDepth }.minByOrNull { it.bitDepth }
            if (higher != null) {
                Log.i(TAG, "findAltSettingForBitDepth($targetBitDepth, rate=$sampleRate, ch=$channelCount): next higher alt=${higher.altSetting} bits=${higher.bitDepth}")
                return Pair(higher.altSetting, higher.bitDepth)
            }

            // Fallback to highest candidate
            val bestCandidate = candidates.maxByOrNull { it.bitDepth }
            if (bestCandidate != null) {
                Log.i(TAG, "findAltSettingForBitDepth($targetBitDepth, rate=$sampleRate, ch=$channelCount): best candidate alt=${bestCandidate.altSetting} bits=${bestCandidate.bitDepth}")
                return Pair(bestCandidate.altSetting, bestCandidate.bitDepth)
            }
        }

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

    private fun isPacketSizeSufficient(
        maxPacketSize: Int,
        sampleRateHz: Int,
        channels: Int,
        bitDepth: Int,
        interval: Int,
    ): Boolean {
        if (maxPacketSize <= 0 || sampleRateHz <= 0) return true
        val bytesPerFrame = ((bitDepth + 7) / 8) * channels
        val microframes = if (interval > 1) 1 shl (interval - 1).coerceAtMost(4) else 1
        val maxFramesPerPacket = ((sampleRateHz + 7999) / 8000) * microframes + 1
        val requiredBytes = maxFramesPerPacket * bytesPerFrame
        return maxPacketSize >= requiredBytes
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
        detailedAltSettings = emptyList()
        controlInterfaceId = 0
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
            val wIndex = (csId shl 8) or controlInterfaceId
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
                        } else if (dRes == 44100 || (dMin % 44100 == 0 && dMax % 44100 == 0)) {
                            for (std in standardRates) {
                                if (std % 44100 == 0 && std in dMin..dMax) csRates += std
                            }
                        } else if (dRes == 48000 || (dMin % 48000 == 0 && dMax % 48000 == 0)) {
                            for (std in standardRates) {
                                if (std % 48000 == 0 && std in dMin..dMax) csRates += std
                            }
                        } else {
                            csRates += dMin
                            csRates += dMax
                            for (std in standardRates) {
                                if (std % 48000 == 0 && std in dMin..dMax && ((std - dMin) % dRes == 0)) {
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
                            if (lower in 8000..1536000) rates += lower
                            if (upper in 8000..1536000) rates += upper
                            for (std in standardRates) {
                                if (std % 48000 == 0 && std in lower..upper) rates += std
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

        // Single-crystal DACs (only 1 Clock Source and no Clock Selector) derive high-speed
        // clocks from a 48 kHz-family crystal (12 / 24.576 MHz) where 48/96/192/384 kHz have
        // exact integer frames per 125us USB microframe (6/12/24/48). High-rate 44.1 kHz
        // multiples (88.2/176.4/352.8/705.6 kHz -> 11.025/22.05/44.1 frames/microframe) require
        // a dedicated dual-crystal 22.5792/45.1584 MHz oscillator (multiple CLOCK_SOURCEs or
        // CLOCK_SELECTOR 0x0B). Exclude those fractional high-rate 44.1k multiples on single-clock
        // DACs so they cleanly resample via 64-bit libsoxr to 96/192/384/48 kHz.
        val hasDedicated441Crystal = parsedClockSelectorId > 0 ||
            parsedClockSourceIds.size >= 2 ||
            clockSourceRateMap.size >= 2 ||
            rates.none { it % 48000 == 0 }
        if (!hasDedicated441Crystal) {
            val removed = rates.filter { it > 44100 && it % 44100 == 0 }
            if (removed.isNotEmpty()) {
                rates.removeAll(removed.toSet())
                Log.i(
                    TAG,
                    "querySupportedSampleRates: single-clock DAC lacks dedicated 44.1k crystal " +
                        "(sources=${parsedClockSourceIds.size}, selector=$parsedClockSelectorId); " +
                        "routing $removed to 48k-family hardware clock via soxr",
                )
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
        val wIndex = (parsedClockSelectorId shl 8) or controlInterfaceId
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
            val wIndex = (csId shl 8) or controlInterfaceId  // entityId << 8 | controlInterfaceId
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
            val uac1Data3 = byteArrayOf(
                (sampleRateHz and 0xFF).toByte(),
                ((sampleRateHz shr 8) and 0xFF).toByte(),
                ((sampleRateHz shr 16) and 0xFF).toByte(),
            )
            var ret = conn.controlTransfer(
                0x22,
                0x01,
                0x0100,
                epOut,
                uac1Data3,
                uac1Data3.size,
                1000,
            )
            if (ret < 0) {
                ret = conn.controlTransfer(
                    0x22,
                    0x01,
                    0x0100,
                    epOut,
                    data,
                    data.size,
                    1000,
                )
            }
            if (ret >= 0) {
                Log.i(TAG, "setSampleRate($sampleRateHz Hz): SUCCESS via UAC1 endpoint 0x${epOut.toString(16)}")
                return true
            }
        }

        // If SET_CUR was rejected but the hardware clock is already running at requested rate
        // (common on auto-switching or fixed-rate DACs), treat as success.
        val currentRate = readSampleRate()
        if (currentRate == sampleRateHz) {
            Log.i(TAG, "setSampleRate($sampleRateHz Hz): DAC clock already at requested rate ($currentRate Hz)")
            return true
        }

        Log.w(TAG, "setSampleRate($sampleRateHz Hz): all clock source IDs failed (currentRate=$currentRate), DAC may auto-detect")
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
            val wIndex = (csId shl 8) or controlInterfaceId
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
            for (req in intArrayOf(0x81, 0x01)) {
                val uac1Data = ByteArray(3)
                val ret = conn.controlTransfer(
                    0xA2,
                    req, // UAC1 GET_CUR (0x81 or 0x01)
                    0x0100,
                    epOut,
                    uac1Data,
                    uac1Data.size,
                    1000,
                )
                if (ret >= 3) {
                    val rate = readLeInt24(uac1Data, 0)
                    if (rate in 8000..1536000) {
                        Log.i(TAG, "readSampleRate: UAC1 endpoint 0x${epOut.toString(16)} (req=0x${req.toString(16)}) returned $rate Hz")
                        return rate
                    }
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
            val wIndex = (csId shl 8) or controlInterfaceId
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
        val targetIfaceId = cachedDeviceInfo?.interfaceId ?: -1

        // 1. Try matching the exact AudioStreaming interface claimed for playback
        if (targetIfaceId >= 0) {
            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                if (iface.id == targetIfaceId &&
                    iface.interfaceClass == UsbConstants.USB_CLASS_AUDIO &&
                    iface.interfaceSubclass == 2 &&
                    iface.alternateSetting == altSetting) {
                    val result = conn.setInterface(iface)
                    Log.i(TAG, "setAltSetting($altSetting) on playback iface $targetIfaceId: $result " +
                            "(endpoints=${iface.endpointCount})")
                    return result
                }
            }
        }

        // 2. Try any AudioStreaming interface matching alt setting that has an OUT endpoint
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == UsbConstants.USB_CLASS_AUDIO &&
                iface.interfaceSubclass == 2 &&
                iface.alternateSetting == altSetting) {
                val hasOut = (0 until iface.endpointCount).any {
                    val ep = iface.getEndpoint(it)
                    ep.type == UsbConstants.USB_ENDPOINT_XFER_ISOC && ep.direction == UsbConstants.USB_DIR_OUT
                }
                if (hasOut) {
                    val result = conn.setInterface(iface)
                    Log.i(TAG, "setAltSetting($altSetting) on OUT iface ${iface.id}: $result " +
                            "(endpoints=${iface.endpointCount})")
                    return result
                }
            }
        }

        // 3. Fallback: try any AudioStreaming interface with matching alt
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == UsbConstants.USB_CLASS_AUDIO &&
                iface.interfaceSubclass == 2 &&
                iface.alternateSetting == altSetting) {
                val result = conn.setInterface(iface)
                Log.i(TAG, "setAltSetting($altSetting) fallback on iface ${iface.id}: $result")
                return result
            }
        }

        Log.w(TAG, "setAltSetting($altSetting): no matching UsbInterface found")
        return false
    }

}
