package com.decent.usbaudio

import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log

/**
 * Helper for handling USB_DEVICE_ATTACHED intents and permissions.
 *
 * Usage in your Activity:
 * ```
 * override fun onCreate(savedInstanceState: Bundle?) {
 *     super.onCreate(savedInstanceState)
 *     UsbAudioPermissionHelper.handleIntent(this, intent)
 * }
 *
 * override fun onNewIntent(intent: Intent) {
 *     super.onNewIntent(intent)
 *     UsbAudioPermissionHelper.handleIntent(this, intent)
 * }
 * ```
 */
object UsbAudioPermissionHelper {

    private const val TAG = "UsbAudioPermission"

    /**
     * Handle a USB_DEVICE_ATTACHED intent. Claims the device immediately
     * to prevent the kernel snd-usb-audio driver from configuring it.
     *
     * @return The USB device if it was an audio device and was claimed, null otherwise.
     */
    fun handleIntent(context: Context, intent: Intent): UsbDevice? {
        if (intent.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return null

        val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE) ?: return null
        Log.i(TAG, "USB_DEVICE_ATTACHED: ${device.productName}")

        val usbAudioDevice = UsbAudioDevice.getInstance(context)
        val audioDevice = usbAudioDevice.findUsbAudioDevice() ?: return null

        if (usbAudioDevice.hasPermission(audioDevice)) {
            val info = usbAudioDevice.openDevice(audioDevice)
            if (info != null) {
                Log.i(TAG, "USB audio device claimed: ${info.deviceName}")
                return audioDevice
            }
        } else {
            usbAudioDevice.requestPermission(audioDevice) { granted ->
                if (granted) {
                    val info = usbAudioDevice.openDevice(audioDevice)
                    Log.i(TAG, "Permission granted, device claimed: ${info?.deviceName}")
                }
            }
        }
        return null
    }
}
