package com.lastwave.app.playback

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.os.Build

/** Requests the platform USB mode only for the PCM format actually sent to AudioTrack. */
class UsbBitPerfectOutput(private val manager: AudioManager?) {
    private var device: AudioDeviceInfo? = null
    private var format: AudioFormat? = null
    private var enabled = false
    private var attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
    private var requestedDevice: AudioDeviceInfo? = null

    @Synchronized
    fun setDevice(value: AudioDeviceInfo?) {
        if (device?.id == value?.id) return
        clear()
        device = value
        apply()
    }

    @Synchronized
    fun setEnabled(value: Boolean) {
        if (enabled == value) return
        enabled = value
        apply()
    }

    @Synchronized
    fun setAttributes(value: AudioAttributes) {
        if (attributes == value) return
        clear()
        attributes = value
        apply()
    }

    @Synchronized
    fun setFormat(value: AudioFormat?) {
        if (sameFormat(format, value)) return
        clear()
        format = value
        apply()
    }

    @Synchronized
    fun isConfigured(): Boolean {
        if (Build.VERSION.SDK_INT < 34 || !enabled || requestedDevice == null) return false
        return runCatching {
            val preferred = manager?.getPreferredMixerAttributes(attributes, requestedDevice!!)
            preferred?.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT &&
                sameFormat(preferred.format, format)
        }.getOrDefault(false)
    }

    private fun apply() {
        if (Build.VERSION.SDK_INT < 34) {
            if (enabled) {
                android.util.Log.w(
                    TAG,
                    "BIT-PERFECT mixer bypass requires Android 14+ (API 34); " +
                        "SDK=${Build.VERSION.SDK_INT} cannot bypass the shared mixer",
                )
            }
            return
        }
        val target = device
        val pcm = format
        if (!enabled || target == null || pcm == null) {
            clear()
            return
        }
        if (target.type != AudioDeviceInfo.TYPE_USB_DEVICE && target.type != AudioDeviceInfo.TYPE_USB_HEADSET) return
        runCatching {
            val all = manager?.getSupportedMixerAttributes(target).orEmpty()
            val bitPerfectModes = all.filter {
                it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT
            }
            if (bitPerfectModes.isEmpty()) {
                android.util.Log.w(
                    TAG,
                    "BIT-PERFECT unsupported by DAC ${target.productName}: " +
                        "no BIT_PERFECT mixer mode advertised — clearing any stale preference",
                )
                // Must clear before returning: a previously-granted preference for this
                // device would otherwise persist, routing PCM through the BIT_PERFECT
                // path even though the device can't honour it (→ buzzing noise).
                clear()
                return
            }
            val supported = bitPerfectModes.firstOrNull { sameFormat(it.format, pcm) }
            if (supported == null) {
                val want = "${pcm.encoding}/${pcm.sampleRate}Hz/mask=${pcm.channelMask}"
                val have = bitPerfectModes.mapNotNull { it.format }
                    .joinToString { "${it.encoding}/${it.sampleRate}Hz/mask=${it.channelMask}" }
                android.util.Log.w(
                    TAG,
                    "BIT-PERFECT format mismatch: want $want; DAC offers [$have] — " +
                        "clearing stale preference to prevent mis-routed PCM (buzzing)",
                )
                // Must clear: the previous configure may have set a BIT_PERFECT preference
                // for a different format (e.g. 48 kHz on EarPods). Leaving it active while
                // feeding 44.1 kHz samples produces the exact buzzing symptom reported.
                clear()
                return
            }
            if (manager?.setPreferredMixerAttributes(attributes, target, supported) == true) {
                requestedDevice = target
                android.util.Log.i(
                    TAG,
                    "BIT-PERFECT mixer bypass granted: ${pcm.encoding}/${pcm.sampleRate}Hz " +
                        "-> ${target.productName}",
                )
            } else {
                android.util.Log.w(TAG, "BIT-PERFECT setPreferredMixerAttributes rejected by platform")
            }
        }
    }

    private fun clear() {
        val previous = requestedDevice
        requestedDevice = null
        if (Build.VERSION.SDK_INT >= 34 && previous != null) {
            runCatching { manager?.clearPreferredMixerAttributes(attributes, previous) }
        }
    }

    /**
     * AudioFormat does not implement value equality, so referential `==`
     * never matches a platform-returned descriptor against our request —
     * every USB request silently failed and read-back was always false.
     * Compare the fields that define the wire format instead.
     */
    private fun sameFormat(a: AudioFormat?, b: AudioFormat?): Boolean {
        if (a == null || b == null) return a == null && b == null
        if (a === b) return true
        return runCatching {
            a.encoding == b.encoding &&
                a.sampleRate == b.sampleRate &&
                a.channelMask == b.channelMask
        }.getOrDefault(false)
    }

    private companion object {
        const val TAG = "UsbBitPerfect"
    }
}
