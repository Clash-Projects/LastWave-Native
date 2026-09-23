package com.decent.usbaudio

import android.hardware.usb.UsbDeviceConnection

/**
 * Information about an opened USB audio device, ready for native I/O.
 */
data class UsbAudioDeviceInfo(
    val connection: UsbDeviceConnection,
    val fd: Int,
    val deviceName: String,
    val interfaceId: Int,
    val endpointOutAddress: Int,
    val endpointFeedbackAddress: Int,
    val maxPacketSize: Int,
    val altSettingCount: Int,
    val clockSourceId: Int,
    val bestAltSetting: Int,
    val bestBitDepth: Int,
    /**
     * USB Audio Class version parsed from the AudioControl header descriptor's
     * bcdADC field. 100 = UAC 1.0, 200 = UAC 2.0, 0 = unknown/unparsed.
     * Used to gate UAC 2.0-only features (e.g. exclusive isochronous output,
     * Clock Source SET_CUR) away from simple USB headsets like Apple EarPods.
     */
    val uacVersion: Int = 0,
)
