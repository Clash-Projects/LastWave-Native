package com.lastwave.app.data.recognition

import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.junit.Test

class AudioSourceDetectorTest {

    private fun mockDevice(type: Int): AudioDeviceInfo {
        val device = mockk<AudioDeviceInfo>()
        every { device.type } returns type
        return device
    }

    @Test
    fun wiredHeadsetTriggersInternalCapture() {
        val devices = arrayOf(mockDevice(AudioDeviceInfo.TYPE_WIRED_HEADSET))
        assertThat(AudioSourceDetector.shouldUseInternalPlaybackCapture(devices)).isTrue()
        assertThat(MicCapture.shouldUseInternalPlaybackCapture(devices)).isTrue()
    }

    @Test
    fun wiredHeadphonesTriggerInternalCapture() {
        val devices = arrayOf(mockDevice(AudioDeviceInfo.TYPE_WIRED_HEADPHONES))
        assertThat(AudioSourceDetector.shouldUseInternalPlaybackCapture(devices)).isTrue()
    }

    @Test
    fun usbHeadsetTriggersInternalCapture() {
        val devices = arrayOf(mockDevice(AudioDeviceInfo.TYPE_USB_HEADSET))
        assertThat(AudioSourceDetector.shouldUseInternalPlaybackCapture(devices)).isTrue()
    }

    @Test
    fun bluetoothA2dpTriggersInternalCapture() {
        val devices = arrayOf(mockDevice(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))
        assertThat(AudioSourceDetector.shouldUseInternalPlaybackCapture(devices)).isTrue()
    }

    @Test
    fun bluetoothScoTriggersInternalCapture() {
        val devices = arrayOf(mockDevice(AudioDeviceInfo.TYPE_BLUETOOTH_SCO))
        assertThat(AudioSourceDetector.shouldUseInternalPlaybackCapture(devices)).isTrue()
    }

    @Test
    fun hearingAidTriggersInternalCapture() {
        val devices = arrayOf(mockDevice(AudioDeviceInfo.TYPE_HEARING_AID))
        assertThat(AudioSourceDetector.shouldUseInternalPlaybackCapture(devices)).isTrue()
    }

    @Test
    fun bleHeadsetTriggersInternalCapture() {
        val devices = arrayOf(mockDevice(AudioDeviceInfo.TYPE_BLE_HEADSET))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            assertThat(AudioSourceDetector.shouldUseInternalPlaybackCapture(devices)).isTrue()
        }
    }

    @Test
    fun builtInSpeakerDoesNotTriggerInternalCapture() {
        val devices = arrayOf(
            mockDevice(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER),
            mockDevice(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE),
        )
        assertThat(AudioSourceDetector.shouldUseInternalPlaybackCapture(devices)).isFalse()
    }

    @Test
    fun emptyDevicesDoesNotTriggerInternalCapture() {
        val devices = emptyArray<AudioDeviceInfo>()
        assertThat(AudioSourceDetector.shouldUseInternalPlaybackCapture(devices)).isFalse()
    }

    @Test
    fun audioManagerQueryEvaluatesOutputs() {
        val audioManager = mockk<AudioManager>()
        every { audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS) } returns arrayOf(
            mockDevice(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP),
        )
        assertThat(AudioSourceDetector.shouldUseInternalPlaybackCapture(audioManager)).isTrue()

        every { audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS) } returns arrayOf(
            mockDevice(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER),
        )
        assertThat(AudioSourceDetector.shouldUseInternalPlaybackCapture(audioManager)).isFalse()
    }
}
