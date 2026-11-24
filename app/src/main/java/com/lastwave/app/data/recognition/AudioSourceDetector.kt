package com.lastwave.app.data.recognition

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

object AudioSourceDetector {

    /**
     * Determines whether recognition should use internal playback audio capture instead of
     * the device microphone based on currently active audio output routing.
     *
     * Returns true when any connected wired headset/headphones or Bluetooth audio output
     * device is active, and false when routing solely through the built-in speaker/earpiece.
     */
    fun shouldUseInternalPlaybackCapture(context: Context): Boolean {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        return shouldUseInternalPlaybackCapture(audioManager)
    }

    fun shouldUseInternalPlaybackCapture(audioManager: AudioManager): Boolean {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        return shouldUseInternalPlaybackCapture(devices)
    }

    fun shouldUseInternalPlaybackCapture(devices: Array<AudioDeviceInfo>): Boolean {
        return devices.any { device ->
            when (device.type) {
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_HEARING_AID -> true
                else -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && device.type == AudioDeviceInfo.TYPE_BLE_HEADSET) {
                        true
                    } else {
                        false
                    }
                }
            }
        }
    }
}
